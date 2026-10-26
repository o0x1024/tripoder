#!/usr/bin/env python3
"""Second pass over incompatible apps: longer window, full guest logcat, engine diagnostics.

The first campaign answers *whether* an app reaches its first screen. This pass answers
*why not* for every app that did not: it re-imports into a clean space (so an earlier
recovery fence cannot hide the real failure), watches the guest for up to `--window`
seconds, keeps every log line that mentions the guest pid or package, and records the
engine's own diagnostics plus the OS-level facts that decide ABI/native/loader behaviour.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))
from tpmcp import _Session, McpError  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "artifacts")
SKIP_PREFIXES = ("dev.tripoder.",)


def adb(args, timeout=120):
    try:
        return subprocess.run(["adb", "-s", "d861a4fa", *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def slug(package):
    return re.sub(r"[^A-Za-z0-9]+", "-", package).strip("-")[:40]


class Engine:
    def __init__(self):
        self.session = None

    def connect(self):
        self.session = _Session(timeout=180)
        self.session.init()
        return self.session

    def call(self, tool, arguments, timeout=180):
        last = None
        for index in range(4):
            try:
                if self.session is None:
                    self.connect()
                return self.session.call(tool, arguments, timeout=timeout)
            except (OSError, McpError) as error:
                last = error
                self.session = None
                time.sleep(2 + 3 * index)
        raise last


def facts(package):
    dump = adb(["shell", f"pm dump {package}"])
    out = {"package": package}
    for key, pattern in (
        ("primaryCpuAbi", r"primaryCpuAbi=(\S+)"),
        ("secondaryCpuAbi", r"secondaryCpuAbi=(\S+)"),
        ("versionName", r"versionName=(\S+)"),
        ("minSdk", r"minSdk=(\d+)"),
        ("targetSdk", r"targetSdk=(\d+)"),
        ("splits", r"splits=\[([^\]]*)\]"),
        ("dataDir", r"dataDir=(\S+)"),
        ("nativeLibraryDir", r"nativeLibraryDir=(\S+)"),
    ):
        match = re.search(pattern, dump)
        out[key] = match.group(1).strip() if match else ""
    flags = re.search(r"flags=\[\s*([^\]]*)\]", dump)
    out["flags"] = " ".join((flags.group(1) if flags else "").split())[:400]
    out["extractNativeLibs"] = "EXTRACT_NATIVE_LIBS" in out["flags"]
    out["debuggable"] = "DEBUGGABLE" in out["flags"]
    processes = re.findall(r"android:process=(\S+)", dump)
    out["secondaryProcesses"] = sorted(set(processes))[:12]
    perms = re.findall(r"android\.permission\.[A-Z_0-9]+", dump)
    out["permissions"] = sorted(set(perms))[:40]
    providers = re.findall(r"Provider\{[^}]*?([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)", dump)
    out["providers"] = sorted(set(providers))[:12]
    return out


def guest_log(epoch, pid, package):
    raw = adb(["logcat", "-d", "-v", "epoch", "-t", "6000"])
    lines = []
    for line in raw.splitlines():
        match = re.match(r"^\s*(\d+(?:\.\d+)?)\s", line)
        if not match or float(match.group(1)) < epoch - 3:
            continue
        if f" {pid} " in line or package in line:
            lines.append(line.rstrip())
    return lines[-400:]


def target_events(engine, space, package, generation, process_generation=""):
    try:
        reply = engine.call("application_query", {"action": "targets"})
    except Exception:
        return None
    for target in (reply.get("result") or {}).get("targets") or []:
        if target.get("package") == package and target.get("space") == space:
            if generation and target.get("installGeneration") and target["installGeneration"] != generation:
                continue
            if process_generation and target.get("processGeneration") not in (None, process_generation):
                continue
            return target
    return None


def focus_package():
    line = adb(["shell", "dumpsys window | grep -E 'mCurrentFocus' | tail -1"]).strip()
    match = re.search(r"u0 ([A-Za-z0-9_.]+)/", line)
    return (match.group(1) if match else ""), line[:200]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--out", default="")
    parser.add_argument("--window", type=float, default=45.0)
    parser.add_argument("--only", default="")
    parser.add_argument("--limit", type=int, default=0)
    options = parser.parse_args()

    run_dir = os.path.join(OUT, options.run)
    out_dir = os.path.join(OUT, options.out or (options.run + "-deep"))
    os.makedirs(os.path.join(out_dir, "logs"), exist_ok=True)

    verdicts = {}
    with open(os.path.join(run_dir, "journal.jsonl"), "r", encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("phase") == "launch":
                verdicts[row["package"]] = row
    failed = [p for p, row in verdicts.items() if row.get("verdict") != "PASS"]
    failed.sort()
    if options.only:
        wanted = {p.strip() for p in options.only.split(",") if p.strip()}
        failed = [p for p in failed if p in wanted]
    if options.limit:
        failed = failed[: options.limit]
    print(f"[deep] {len(failed)} apps to analyse", flush=True)

    engine = Engine()
    results = {}
    result_path = os.path.join(out_dir, "deep.json")
    if os.path.exists(result_path):
        with open(result_path, "r", encoding="utf-8") as handle:
            results = json.load(handle)

    for index, package in enumerate(failed, 1):
        if package in results:
            continue
        space = "qa2-" + slug(package).lower()
        record = {"package": package, "space": space, "facts": facts(package)}
        # Fresh space: a fenced predecessor must not be able to explain this verdict away.
        try:
            engine.call("space_manage", {"action": "create", "operationId": f"qa2-space-{slug(package).lower()}-{int(time.time() * 1000) % 100000}",
                                         "space": space, "name": ("复测 " + slug(package))[:24]})
        except Exception as error:
            record["spaceError"] = str(error)[:200]
        started = time.time()
        try:
            imported = engine.call("application_manage", {"action": "import_device", "operationId": f"qa2-imp-{slug(package)}-{int(time.time())}",
                                                          "space": space, "package": package, "expectedGeneration": ""}, timeout=600)
        except Exception as error:
            record["importError"] = str(error)[:300]
            results[package] = record
            continue
        record["import"] = {"state": (imported.get("result") or {}).get("state", ""), "error": (imported.get("error") or "")[:300],
                            "errorCode": imported.get("errorCode", ""), "seconds": round(time.time() - started, 1)}
        generation = (imported.get("result") or {}).get("generation", "")
        record["installGeneration"] = generation
        if record["import"]["state"] != "SUCCEEDED":
            results[package] = record
            with open(result_path, "w", encoding="utf-8") as handle:
                json.dump(results, handle, ensure_ascii=False, indent=1)
            print(f"[deep] {index}/{len(failed)} {package:42} IMPORT {record['import']['state']} {record['import']['error'][:60]}", flush=True)
            continue

        adb(["shell", "am start -n dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity"])
        time.sleep(1.2)
        epoch = time.time()
        operation = f"qa2-start-{slug(package)}-{int(time.time())}"
        target = {"space": space, "package": package, "installGeneration": generation}
        try:
            reply = engine.call("process_start", {"operationId": operation, "target": target, "component": "", "probeRevision": ""}, timeout=180)
        except Exception as error:
            record["startError"] = str(error)[:300]
            results[package] = record
            continue
        if reply.get("isError") or not (reply.get("result") or {}).get("pid"):
            record["start"] = {"state": "REJECTED", "error": (reply.get("error") or "")[:300], "errorCode": reply.get("errorCode", "")}
            results[package] = record
            with open(result_path, "w", encoding="utf-8") as handle:
                json.dump(results, handle, ensure_ascii=False, indent=1)
            print(f"[deep] {index}/{len(failed)} {package:42} START_REJECTED {record['start']['error'][:70]}", flush=True)
            continue

        result = reply["result"]
        pid = result.get("pid")
        process_generation = result.get("processGeneration", "")
        record["start"] = {"pid": pid, "processGeneration": process_generation, "operationId": operation}
        timeline = []
        deadline = time.time() + options.window
        alive = True
        while time.time() < deadline:
            time.sleep(2)
            alive = target_events(engine, space, package, generation, process_generation) is not None
            focused, focus_line = focus_package()
            timeline.append({"t": round(time.time() - epoch, 1), "alive": alive,
                             "focus": focused, "window": focus_line[:120]})
            if not alive:
                break
            if focused == package:
                break
        record["timeline"] = timeline
        record["aliveAtEnd"] = alive
        record["seconds"] = round(time.time() - started, 1)
        record["log"] = guest_log(epoch, pid, package)

        if alive:
            target_full = {**target, "processGeneration": process_generation}
            for action in ("ui_tree", "diagnostics", "modules", "hook_status"):
                request = {"action": action, "target": target_full}
                if action == "ui_tree":
                    request["includeInvisible"] = False
                try:
                    reply = engine.call("process_inspect", request, timeout=120)
                    record[action] = reply.get("result") if reply.get("ok") is not False else {"error": (reply.get("error") or "")[:300],
                                                                                             "errorCode": reply.get("errorCode", "")}
                except Exception as error:
                    record[action] = {"error": str(error)[:200]}
        # Reclaim the slot; note a rejection but never let it hide the evidence above.
        try:
            stop = engine.call("application_stop", {"operationId": f"qa2-stop-{slug(package)}-{int(time.time() * 1000) % 100000}", "target": target}, timeout=120)
            if stop.get("ok") is False:
                record["stopError"] = (stop.get("errorCode") or "") + ":" + (stop.get("error") or "")[:160]
        except Exception as error:
            record["stopError"] = str(error)[:200]

        results[package] = record
        with open(result_path, "w", encoding="utf-8") as handle:
            json.dump(results, handle, ensure_ascii=False, indent=1)
        verdict = "ALIVE" if alive else "GONE"
        print(f"[deep] {index}/{len(failed)} {package:42} {verdict:5} {record['seconds']:5.1f}s log={len(record['log'])}", flush=True)

    print(f"[deep] done -> {result_path}", flush=True)


if __name__ == "__main__":
    main()
