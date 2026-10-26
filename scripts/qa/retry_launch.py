#!/usr/bin/env python3
"""Second-attempt launcher for a compatibility campaign.

The first pass can record a transport/engine failure (DeadObjectException,
ENGINE_NOT_CONNECTED, a stale per-space recovery fence) or a one-off crash.
This script re-runs only the requested packages, either against the original
installation or a fresh qa2-* space, using the same four-signal verdict as the
first pass.  Evidence is appended to <out>/retry.jsonl; the first-pass journal
is never modified.

Usage:
  python3 scripts/qa/retry_launch.py --run compat-run1 --out compat-run1-retry \
      --only pkg1,pkg2 --fresh pkg3 --window 20
"""
import argparse
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "tools"))

import device_campaign as dc  # noqa: E402
from tpmcp import McpError  # noqa: E402


def load_latest(path):
    latest = {}
    if not os.path.exists(path):
        return latest
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            latest[(row.get("phase"), row.get("package"))] = row
    return latest


def ensure_import(engine, package, space, journal):
    reply = engine.call(
        "application_manage",
        {"action": "import_device", "operationId": f"retry-imp-{dc.slug(package)}-{int(time.time())}",
         "space": space, "package": package, "expectedGeneration": ""},
        timeout=600,
    )
    result = reply.get("result") or {}
    if reply.get("isError") or result.get("state") != "SUCCEEDED":
        return None, (reply.get("error") or result.get("error") or "IMPORT_FAILED")[:400]
    journal.add(phase="import", package=package, space=space, state="SUCCEEDED",
                generation=result.get("generation", ""), operationId=result.get("operationId", ""), retry=True)
    return result.get("generation", ""), ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--out", default="compat-run1-retry")
    parser.add_argument("--only", required=True, help="comma separated packages")
    parser.add_argument("--fresh", default="", help="comma separated packages to re-import into a qa2-* space")
    parser.add_argument("--space-prefix", default="qa2-", help="prefix for fresh retry spaces (default qa2-)")
    parser.add_argument("--window", type=float, default=20.0)
    parser.add_argument("--settle", type=float, default=1.5)
    options = parser.parse_args()

    root = os.path.abspath(os.path.join(HERE, "..", ".."))
    run_dir = os.path.join(root, "artifacts", options.run)
    out_dir = os.path.join(root, "artifacts", options.out)
    os.makedirs(os.path.join(out_dir, "logs"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "shots"), exist_ok=True)
    journal = dc.Journal(os.path.join(out_dir, "retry.jsonl"))
    latest = load_latest(os.path.join(run_dir, "journal.jsonl"))
    fresh = {p.strip() for p in options.fresh.split(",") if p.strip()}
    packages = [p.strip() for p in options.only.split(",") if p.strip()]

    engine = dc.Engine()
    dc.cleanup_targets(engine)
    for package in packages:
        imp = latest.get(("import", package), {})
        space = imp.get("space") or dc.space_for(package)
        generation = imp.get("generation", "")
        record = {"phase": "launch", "package": package, "space": space, "attempt": 2}
        if package in fresh or not generation:
            space = options.space_prefix + dc.slug(package).lower()
            record["space"] = space
            try:
                engine.call("space_manage", {"action": "create", "operationId": f"retry-space-{dc.slug(package).lower()}-{int(time.time() * 1000) % 100000}",
                                             "space": space, "name": ("复测 " + dc.slug(package))[:24]})
            except Exception as error:
                if "ALREADY" not in str(error):
                    record.update(verdict="SPACE_FAILED", error=str(error)[:300])
                    journal.add(**record)
                    print(f"[retry] {package:45} SPACE_FAILED {str(error)[:80]}", flush=True)
                    continue
            generation, error = ensure_import(engine, package, space, journal)
            if not generation:
                record.update(verdict="IMPORT_FAILED", error=error)
                journal.add(**record)
                print(f"[retry] {package:45} IMPORT_FAILED {error[:80]}", flush=True)
                continue

        target = {"space": space, "package": package, "installGeneration": generation}
        record["installGeneration"] = generation
        operation = f"retry-start-{dc.slug(package)}-{int(time.time())}"
        epoch = time.time()
        started = time.time()
        dc.adb(["shell", "am start -n dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity"])
        time.sleep(1.2)
        try:
            reply = engine.call("process_start", {"operationId": operation, "target": target, "component": "", "probeRevision": ""}, timeout=180)
        except Exception as error:
            record.update(verdict="TRANSPORT_ERROR", error=str(error)[:400], seconds=round(time.time() - started, 1))
            journal.add(**record)
            print(f"[retry] {package:45} TRANSPORT_ERROR {str(error)[:80]}", flush=True)
            continue
        if reply.get("isError") and "PROCESS_ALREADY_RUNNING" in (reply.get("error") or ""):
            dc.cleanup_targets(engine, package)
            operation += "-r"
            reply = engine.call("process_start", {"operationId": operation, "target": target, "component": "", "probeRevision": ""}, timeout=180)
        if reply.get("isError") or not (reply.get("result") or {}).get("pid"):
            message = reply.get("error") or ""
            record.update(verdict="SCOPE_FENCED" if "RECOVERY_SCOPE_UNKNOWN" in message else "START_REJECTED",
                          error=message[:600], seconds=round(time.time() - started, 1))
            journal.add(**record)
            print(f"[retry] {package:45} {record['verdict']:18} {message[:80]}", flush=True)
            continue

        result = reply["result"]
        pid = result.get("pid")
        process_generation = result.get("processGeneration", "")
        record.update(pid=pid, processGeneration=process_generation)
        deadline = time.time() + options.window
        alive = True
        focus_package = ""
        resumed_hits = set()
        while time.time() < deadline:
            time.sleep(2)
            try:
                alive = dc.live_target(engine, package, generation, process_generation) is not None
            except Exception:
                alive = True
            if not alive:
                break
            focus_package, resumed_hits, _ = dc.foreground_package()
            if focus_package == package or package in resumed_hits:
                break
        foreground = focus_package == package or package in resumed_hits
        ui_nodes = 0
        ui_error = ""
        if alive:
            try:
                tree = engine.call("process_inspect", {"action": "ui_tree", "target": {**target, "processGeneration": process_generation}, "includeInvisible": False}, timeout=120)
                if tree.get("ok") is False:
                    ui_error = (tree.get("errorCode") or "") + ":" + (tree.get("error") or "")[:160]
                else:
                    blob = json.dumps(tree.get("result") or {}, ensure_ascii=False)
                    ui_nodes = len(dc.re.findall(r'"(?:text|className|bounds|children)"', blob))
            except Exception as error:
                ui_error = str(error)[:160]
        text, fatals = dc.crash_evidence(package, epoch, pid, out_dir)
        anr = [line for line in text.splitlines() if f"ANR in {package}" in line]
        rendered = ui_nodes >= 3 or foreground
        if not alive:
            verdict = "CRASH_ON_START" if fatals else "PROCESS_GONE"
        elif anr:
            verdict = "ANR"
        elif not rendered:
            verdict = "NO_FOREGROUND_SCREEN"
        else:
            verdict = "PASS"
        record.update(verdict=verdict, alive=alive, foreground=foreground, uiTreeNodes=ui_nodes,
                      uiError=ui_error, fatals=fatals, focus=_last_focus(), seconds=round(time.time() - started, 1))
        if verdict != "PASS":
            shot = os.path.join(out_dir, "shots", f"{dc.slug(package)}.png")
            dc.adb(["shell", "screencap -p /sdcard/_qa_retry.png"])
            subprocess.run(["adb", "-s", "d861a4fa", "pull", "/sdcard/_qa_retry.png", shot], capture_output=True, text=True)
            dc.adb(["shell", "rm -f /sdcard/_qa_retry.png"])
            record["screenshot"] = os.path.relpath(shot, root)
        if alive or process_generation:
            stop = dc.stop_target(engine, {**target, "processGeneration": process_generation})
            if stop:
                record["stopError"] = stop
        journal.add(**record)
        print(f"[retry] {package:45} {verdict:20} alive={alive} fg={foreground} {record['seconds']:5.1f}s", flush=True)
        time.sleep(options.settle)
    journal.close()


def _last_focus():
    line = dc.adb(["shell", "dumpsys window | grep -E 'mCurrentFocus' | tail -1"]).strip()
    return line[:300]


if __name__ == "__main__":
    main()
