#!/usr/bin/env python3
"""Bring the engine's `:authority` controller back when it dies mid-campaign.

The engine keeps `:recovery` and the main process alive, but the controller that serves MCP
is only (re)started by the workbench UI: `engine.connect()` binds/starts it. Without this the
whole rest of a campaign is refused with `ENGINE_NOT_CONNECTED`, which is exactly how the
first full run produced 38 unusable `MODE_SET_FAILED` rows.

Exit code 0 means `runtime_query status` answers ok.
"""
import re
import subprocess
import sys
import time

sys.path.insert(0, "scripts/qa")
from device_campaign import Engine, adb  # noqa: E402

WORKBENCH = "dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity"


def connected():
    try:
        reply = Engine(retries=1).call("runtime_query", {"action": "status"}, timeout=25, attempts=1)
    except Exception:
        return False
    return not (reply.get("ok") is False or reply.get("isError"))


def tap(label, save="/sdcard/_repair.xml"):
    """Tap the centre of a node by its text. False when the label is not on screen."""
    adb(["shell", f"uiautomator dump {save}"])
    xml = adb(["shell", f"cat {save}"])
    pattern = r'text="' + re.escape(label) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    found = re.search(pattern, xml)
    if not found:
        return False
    x = (int(found.group(1)) + int(found.group(3))) // 2
    y = (int(found.group(2)) + int(found.group(4))) // 2
    adb(["shell", f"input tap {x} {y}"])
    return True


def repair(restart=False):
    adb(["shell", f"am start -n {WORKBENCH}"])
    time.sleep(2.5)
    if connected():
        return True
    if restart:
        adb(["shell", "am force-stop dev.tripoder.engine"])
        time.sleep(1.5)
        adb(["shell", f"am start -n {WORKBENCH}"])
        time.sleep(4)
        if connected():
            return True
    # The workbench shows "重新连接" when the controller lease is gone; the settings page
    # (one tab right) has "开启网络 MCP", which also creates the controller.
    for _ in range(5):
        for label in ("重新连接", "开启网络 MCP", "连接工具"):
            if tap(label):
                time.sleep(4)
                break
        if connected():
            return True
        time.sleep(2.5)
    return False


def main():
    restart = "--restart" in sys.argv
    ok = repair(restart=restart)
    print("engine: " + ("connected" if ok else "STILL_DISCONNECTED"), flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
