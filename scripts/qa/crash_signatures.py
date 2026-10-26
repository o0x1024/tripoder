#!/usr/bin/env python3
"""Extract one normalized crash signature per failing package from journal + logcat.

The campaign logcat files are time-window captures, so they can contain crashes from
*other* processes.  Every line is therefore attributed to a pid/package before it is
used as evidence for a guest.
"""
import argparse
import collections
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

SKIP_LINE = (
    "at android.", "at java.", "at kotlin.", "at androidx.", "at dev.tripoder.",
    "at com.android.internal.", "at libcore.", "at dalvik.",
)
NOISE = (
    "beginning of", "---------", "libc    :", "DEBUG   :", "Build fingerprint",
    "Revision:", "ABI:", "Timestamp:", "pid:", "uid:", "signal ", "backtrace:",
    "code around", "stack:", "memory map", "---------",
)


def load_rows(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return rows


def index(rows):
    facts, imports, launches = {}, {}, {}
    for row in rows:
        pkg = row.get("package")
        if not pkg:
            continue
        if row.get("phase") == "inventory":
            facts[pkg] = row
        elif row.get("phase") == "import":
            imports[pkg] = row
        elif row.get("phase") == "launch":
            launches[pkg] = row
    return facts, imports, launches


def line_pid(line):
    match = re.match(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+", line)
    if not match:
        return None, None, None
    return match.group(2), match.group(4), match.group(1)


def blocks(lines):
    """Split logcat into crash blocks starting at FATAL EXCEPTION / Fatal signal / ANR."""
    current, out = [], []
    for line in lines:
        if ("FATAL EXCEPTION" in line or "Fatal signal" in line or "ANR in " in line) and current:
            out.append(current)
            current = [line]
        elif current:
            current.append(line)
        else:
            current = [line]
    if current:
        out.append(current)
    return out


def signature(block, package, pid):
    """Return (kind, exception, first_app_frame, detail) for one crash block."""
    text = "\n".join(block)
    kind = "CRASH"
    if "ANR in " in text:
        kind = "ANR"
    elif "Fatal signal" in text:
        kind = "NATIVE"
    exception = ""
    app_frame = ""
    detail = ""
    for line in block:
        body = re.sub(r"^\s*\d+\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+\S+\s*:\s?", "", line)
        body = body.strip()
        if not body:
            continue
        if body.startswith("Process: "):
            if package and package not in body and f"PID: {pid}" not in body:
                return None
            detail = detail or body
        elif body.startswith("Caused by: "):
            if not exception or exception.startswith("RuntimeException: Unable to start activity"):
                exception = body[len("Caused by: "):]
        elif not exception and re.match(r"^[A-Za-z_$][\w.$]*(Exception|Error|Throwable)(:|\s|$)", body):
            exception = body
        elif body.startswith("java.lang.") and "Exception" in body.split(":")[0]:
            exception = exception or body
        elif body.startswith("Abort message:"):
            detail = detail or body
        if not app_frame and body.startswith("at ") and not any(body.startswith(s) for s in SKIP_LINE):
            app_frame = body
    detail = detail or next((ln.strip() for ln in block if "Process: " in ln), "")
    return kind, exception, app_frame, detail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--json", action="store_true")
    options = parser.parse_args()
    run_dir = os.path.join(ROOT, "artifacts", options.run)
    facts, imports, launches = index(load_rows(os.path.join(run_dir, "journal.jsonl")))
    result = []
    for pkg, run in sorted(launches.items()):
        pid = str(run.get("pid") or "")
        verdict = run.get("verdict")
        log_path = os.path.join(run_dir, "logs", re.sub(r"[^A-Za-z0-9]+", "-", pkg).strip("-") + ".logcat.txt")
        lines = open(log_path, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(log_path) else []
        own = []
        for block in blocks(lines):
            text = "\n".join(block)
            # A block belongs to the guest only when it names the guest.  pid alone is not
            # enough: Android reuses pids, and the capture window may include the next app.
            named = f"Process: {pkg}" in text or f"ANR in {pkg} " in text
            native = f">>> {pkg} <<<" in text
            if named or native:
                own.append(block)
        if not own and pid:
            # Native tombstone blocks do not always carry "Process:"; fall back to pid only
            # for blocks that contain a Fatal signal and no other package name.
            for block in blocks(lines):
                text = "\n".join(block)
                pids = {line_pid(ln)[0] for ln in block if line_pid(ln)[0]}
                if "Fatal signal" in text and pid in pids and f">>> {pkg} <<<" in text:
                    own.append(block)
        sig = signature(own[0], pkg, pid) if own else None
        fatal_count = sum(1 for block in own for ln in block if "FATAL EXCEPTION" in ln)
        record = {
            "package": pkg,
            "verdict": verdict,
            "pid": pid,
            "error": (run.get("error") or "")[:300],
            "uiError": (run.get("uiError") or "")[:200],
            "kind": sig[0] if sig else "",
            "exception": sig[1] if sig else "",
            "frame": sig[2] if sig else "",
            "detail": sig[3] if sig else "",
            "ownBlocks": len(own),
            "fatalCount": fatal_count,
            "log": os.path.relpath(log_path, ROOT) if lines else "",
        }
        result.append(record)
    if options.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        counts = collections.Counter((r["verdict"], r["exception"].split(":")[0] if r["exception"] else "") for r in result)
        for (verdict, exc), count in counts.most_common():
            print(f"{count:3}  {verdict:18} {exc}")
        print()
        for r in result:
            if r["verdict"] == "PASS":
                continue
            print(f"{r['package']:45} {r['verdict']:18} {r['exception'][:100]}")
            if r["frame"]:
                print(f"    {r['frame'][:150]}")


if __name__ == "__main__":
    main()
