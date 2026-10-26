#!/usr/bin/env python3
"""Dump the current screen's text nodes with tap coordinates.

Usage:
  ui_dump.py                 # list text/content-desc nodes with centers
  ui_dump.py <label-substr>  # tap the first node whose text/desc contains the substring
  ui_dump.py --tap X Y       # raw tap
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
REMOTE = "/sdcard/_ui_dump.xml"


def adb(args, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(3):
        adb(["shell", f"rm -f {REMOTE}"])
        adb(["shell", f"uiautomator dump {REMOTE}"])
        raw = adb(["shell", f"cat {REMOTE}"]).stdout
        if "<hierarchy" in raw:
            return raw
        time.sleep(1)
    raise SystemExit("UI_DUMP_FAILED")


def nodes(raw):
    start = raw.index("<hierarchy")
    root = ET.fromstring(raw[start:])
    out = []
    for node in root.iter("node"):
        label = (node.get("text") or "").strip() or (node.get("content-desc") or "").strip()
        bounds = node.get("bounds") or ""
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not label or not match:
            continue
        x1, y1, x2, y2 = (int(v) for v in match.groups())
        out.append(
            {
                "label": label,
                "text": (node.get("text") or "").strip(),
                "desc": (node.get("content-desc") or "").strip(),
                "cls": (node.get("class") or "").split(".")[-1],
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
                "bounds": (x1, y1, x2, y2),
                "enabled": node.get("enabled"),
            }
        )
    return out


def main(argv):
    raw = dump()
    items = nodes(raw)
    if len(argv) >= 3 and argv[1] == "--tap":
        x, y = int(argv[2]), int(argv[3])
        adb(["shell", f"input tap {x} {y}"])
        print(f"tapped {x},{y}")
        return 0
    if len(argv) >= 2 and not argv[1].startswith("-"):
        needle = argv[1]
        hits = [n for n in items if needle in n["label"]]
        if not hits:
            print(f"NOT_FOUND {needle!r}")
            for n in items[:40]:
                print("  ", n["label"][:60], n["center"], n["cls"])
            return 1
        target = hits[0]
        adb(["shell", f"input tap {target['center'][0]} {target['center'][1]}"])
        print(f"tapped {target['label'][:40]!r} @ {target['center']}")
        return 0
    for n in items:
        print(f"{n['label'][:70]!r:74} {n['center']} {n['cls']} enabled={n['enabled']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
