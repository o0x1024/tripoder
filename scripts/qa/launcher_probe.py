#!/usr/bin/env python3
"""Record how many MAIN/LAUNCHER activities each third-party package exposes.

The device query is the authority here, not a guess derived from import failures:
`cmd package query-activities` runs as shell (no package-visibility filter), while the
engine's own PackageManager may be unable to see data-app packages at all.
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def adb(args, timeout=120):
    result = subprocess.run(["adb", "-s", "d861a4fa", *args], capture_output=True, text=True,
                            timeout=timeout, errors="replace")
    if result.returncode != 0:
        raise SystemExit(f"adb failed: {result.stderr.strip()[:300]}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    options = parser.parse_args()
    run_dir = os.path.join(ROOT, "artifacts", options.run)

    packages = []
    for line in adb(["shell", "pm list packages -3"]).splitlines():
        name = line.strip().replace("package:", "")
        if name and not name.startswith("dev.tripoder."):
            packages.append(name)

    raw = adb(["shell", "cmd", "package", "query-activities", "--brief",
               "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER"])
    counts = {}
    for line in raw.splitlines():
        match = re.match(r"^\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$", line)
        if not match:
            continue
        counts[match.group(1)] = counts.get(match.group(1), 0) + 1

    data = {pkg: counts.get(pkg, 0) for pkg in sorted(packages)}
    target = os.path.join(run_dir, "launcher.json")
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=1)
    with_entry = sum(1 for value in data.values() if value)
    print(f"packages={len(data)} with_launcher={with_entry} without={len(data) - with_entry} -> {target}")
    print("no launcher:", ", ".join(pkg for pkg, value in data.items() if not value))


if __name__ == "__main__":
    sys.exit(main())
