#!/usr/bin/env python3
"""One-guest diagnostic: clean slots, start, then read every observable signal.

Usage: probe_one.py <package> [--keep] [--seconds 8]

Prints, in order:
  * process_start reply (pid / processGeneration / any error)
  * application_query(process) detail
  * process_inspect(ui_tree) digest
  * window manager focus lines and resumed activity
  * whether the guest survived the window
"""
import json
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from tpmcp import _Session  # noqa: E402

SPACE = "default"


def adb(args):
    return subprocess.run(["adb", "-s", "d861a4fa", *args], capture_output=True, text=True, timeout=90).stdout


def targets(mcp):
    return (mcp.call("application_query", {"action": "targets"}).get("result") or {}).get("targets") or []


def stop(mcp, target):
    payload = {k: target[k] for k in ("space", "package", "installGeneration") if target.get(k)}
    if target.get("processGeneration"):
        payload["processGeneration"] = target["processGeneration"]
    try:
        return mcp.call("application_stop", {"operationId": f"probe-stop-{int(time.time() * 1000) % 1000000}", "target": payload})
    except Exception as error:
        return {"error": str(error)[:200]}


def main(argv):
    package = argv[1]
    keep = "--keep" in argv
    seconds = float(argv[argv.index("--seconds") + 1]) if "--seconds" in argv else 8.0

    mcp = _Session(timeout=180)
    mcp.init()
    for target in targets(mcp):
        stop(mcp, target)
    time.sleep(1)

    installations = ((mcp.call("runtime_query", {"action": "applications"}).get("result") or {}).get("installations") or [])
    match = [i for i in installations if i["package"] == package]
    if not match:
        print("NOT_INSTALLED", package)
        return 1
    generation = match[0].get("installGeneration") or match[0].get("generation")

    reply = mcp.call(
        "process_start",
        {"operationId": f"probe-{int(time.time())}", "target": {"space": SPACE, "package": package, "installGeneration": generation},
         "component": "", "probeRevision": ""},
        timeout=180,
    )
    print("START:", json.dumps(reply, ensure_ascii=False)[:600])
    result = reply.get("result") or {}
    if not result.get("pid"):
        return 2

    time.sleep(seconds)
    target = {"space": SPACE, "package": package, "installGeneration": generation, "processGeneration": result.get("processGeneration", "")}
    print("PROCESS:", json.dumps(mcp.call("application_query", {"action": "process", "target": target}), ensure_ascii=False)[:900])
    tree = mcp.call("process_inspect", {"action": "ui_tree", "target": target})
    print("UI_TREE:", json.dumps(tree, ensure_ascii=False)[:900])
    print("WINDOW:", adb(["shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | tail -3"]).strip()[:400])
    print("RESUMED:", adb(["shell", "dumpsys activity activities | grep -E 'ResumedActivity' | tail -3"]).strip()[:400])
    alive = any(t.get("package") == package for t in targets(mcp))
    print("ALIVE_AFTER_WINDOW:", alive)
    if not keep:
        print("STOP:", json.dumps(stop(mcp, {**target}), ensure_ascii=False)[:300])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
