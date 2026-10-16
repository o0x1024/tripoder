#!/usr/bin/env python3
"""Operate only the foreground Tripoder preview/owned guest, using observed native bounds."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--evidence", required=True, type=Path)
parser.add_argument("action", choices=["snapshot", "tap", "scroll"])
parser.add_argument("value")
parser.add_argument("--description", action="store_true")
parser.add_argument("--within", help="Restrict a repeated control to the nearest card containing this exact text")
args = parser.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)
remote = "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/preview-ui.xml"


def adb(*parts):
    return subprocess.run(["adb", "-s", args.serial, *parts], check=True, capture_output=True).stdout


adb("shell", "uiautomator", "dump", remote)
data = adb("shell", "cat", remote)
tree = ET.fromstring(data)
nodes = list(tree.iter("node"))
packages = {n.get("package") for n in nodes if n.get("package")}
if not packages or packages - {"dev.tripoder.runtimeprobe", "dev.tripoder.previewguest"}:
    raise SystemExit("Foreground is not the owned preview; no input or screenshot captured")
if args.action == "snapshot":
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.value):
        raise SystemExit("Invalid evidence label")
    (args.evidence / (args.value + ".xml")).write_bytes(data)
    screenshot = "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/preview-screen.png"
    adb("shell", "screencap", "-p", screenshot)
    png = adb("exec-out", "cat", screenshot)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit("Screenshot was not a PNG")
    (args.evidence / (args.value + ".png")).write_bytes(png)
    print(json.dumps([{"text": n.get("text"), "description": n.get("content-desc"), "bounds": n.get("bounds"), "enabled": n.get("enabled")} for n in nodes if n.get("text") or n.get("content-desc")], ensure_ascii=False))
elif args.action == "tap":
    key = "content-desc" if args.description else "text"
    parents = {child: parent for parent in tree.iter() for child in parent}
    scope = tree
    if args.within:
        anchors = [n for n in nodes if n.get("text") == args.within]
        if len(anchors) != 1:
            raise SystemExit("Card anchor is not unique in the observed UI")
        scope = anchors[0]
        while not any(n.get(key) == args.value for n in scope.iter("node")):
            scope = parents.get(scope)
            if scope is None:
                raise SystemExit("Control not present in the observed card")
    matches = [n for n in scope.iter("node") if n.get(key) == args.value]
    if len(matches) != 1:
        raise SystemExit(f"Expected one observed target, found {len(matches)}")
    target = matches[0]
    if target.get("enabled") != "true":
        raise SystemExit("Observed target is disabled")
    while target.get("clickable") != "true":
        target = parents.get(target)
        if target is None:
            raise SystemExit("Target has no clickable parent")
    left, top, right, bottom = map(int, re.findall(r"\d+", target.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    print(json.dumps({"tapped": args.value, "bounds": target.get("bounds")}, ensure_ascii=False))
else:
    scrolls = [n for n in nodes if n.get("scrollable") == "true"]
    if len(scrolls) != 1 or args.value not in ("up", "down"):
        raise SystemExit("Expected one observed scroll region and up/down")
    left, top, right, bottom = map(int, re.findall(r"\d+", scrolls[0].get("bounds")))
    upper, lower = top + (bottom - top) // 4, bottom - (bottom - top) // 4
    start, end = (lower, upper) if args.value == "down" else (upper, lower)
    adb("shell", "input", "swipe", str((left + right) // 2), str(start), str((left + right) // 2), str(end), "350")
    print(json.dumps({"scrolled": args.value}))
