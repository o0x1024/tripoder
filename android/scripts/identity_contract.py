#!/usr/bin/env python3
"""Assert the identity contract against TripoderIdentitySnapshot facadeAnswer records.

Contract: docs/identity-provenance-contract.md

The runtime prints one `facadeAnswer={...}` line per guest process: the install facts as the *guest's own*
PackageManager answers them, next to what the platform holds for the same package name. This script re-derives
the pass/fail decision from the raw fields instead of trusting the runtime's own `verdict=` token: a diagnostic
that grades itself would not have caught the shadowed branch that produced the container's name in the first
place.

Hard failure (contract R1): the host package appears in any facade answer - package, installer, install source
or the uid reverse lookup. Those are the answers a distribution check compares, and "the container installed
me" is the literal statement of an unofficial build.

Usage:
    identity_contract.py --logcat                 # adb -s <serial> logcat -d
    identity_contract.py --file saved.logcat
    identity_contract.py --stdin < saved.logcat
    identity_contract.py --file saved.logcat --json
"""
import argparse
import json
import re
import subprocess
import sys

TAG = "TripoderIdentitySnapshot"
DEFAULT_HOST = "dev.tripoder.q6"
DEFAULT_SERIAL = "d861a4fa"
FACADE_KEYS = ("package", "uid", "versionCode", "versionName", "signer", "sourceDir",
               "installer", "installSource", "uidPackages")


def _section(line, key):
    """Return the body of `key={...}` with brace nesting respected, or None."""
    marker = key + "={"
    start = line.find(marker)
    if start < 0:
        return None
    start += len(marker) - 1  # index of the opening brace
    depth = 0
    for index in range(start, len(line)):
        char = line[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return line[start + 1:index]
    return None


def _pairs(body):
    """Split a `k=v,k=...` body on commas that are not inside braces or brackets."""
    result = {}
    depth = 0
    current = []
    for char in body + ",":
        if char in "{[":
            depth += 1
        elif char in "}]":
            depth -= 1
        if char == "," and depth == 0:
            token = "".join(current).strip()
            current = []
            if not token or "=" not in token:
                continue
            name, _, value = token.partition("=")
            result[name.strip()] = value.strip()
            continue
        current.append(char)
    return result


def _list(value):
    if value is None:
        return []
    text = value.strip()
    if text in ("null", ""):
        return []
    if text.startswith("[") and text.endswith("]"):
        text = text[1:-1]
    return [item.strip() for item in text.split(",") if item.strip()]


def parse_record(line):
    """One logcat line -> dict of facade answers plus the runtime's own tokens."""
    body = _section(line, "facadeAnswer")
    if body is None:
        return None
    record = _pairs(body)
    record["installSource"] = _pairs(_section(line, "installSource") or "")
    record["uidPackagesList"] = _list(record.get("uidPackages"))
    leak = re.search(r"hostLeak=([^\s]+)", line)
    verdict = re.search(r"verdict=([^\s]+)", line)
    record["hostLeak"] = leak.group(1) if leak else None
    record["verdict"] = verdict.group(1) if verdict else None
    record["sourceDirForm"] = "container-private" if "sourceDirForm=container-private" in line else None
    record["line"] = line
    return record


def facade_records(text):
    """Every facadeAnswer record in a logcat text, oldest first."""
    records = []
    for line in (text or "").splitlines():
        if TAG not in line or "facadeAnswer=" not in line:
            continue
        record = parse_record(line)
        if record is not None:
            records.append(record)
    return records


def violations(record, host=DEFAULT_HOST):
    """Contract violations in one record, recomputed from the raw fields (never from `verdict=`)."""
    problems = []
    if record.get("package") == host:
        problems.append("R1 package=%s" % host)

    installer = record.get("installer")
    if installer == host:
        problems.append("R1 installer=%s" % host)

    source = record.get("installSource") or {}
    for field in ("installer", "initiating", "originating"):
        if source.get(field) == host:
            problems.append("R1 installSource.%s=%s" % (field, host))

    if host in record.get("uidPackagesList", []):
        problems.append("R1 uidPackages contains %s" % host)

    # The runtime's own verdict must agree with the recomputation; a disagreement means the diagnostic is broken.
    reported = record.get("verdict") or ""
    if reported.startswith("HOST_IDENTITY_LEAK") and not problems:
        problems.append("verdict=%s but no leaking field found" % reported)
    if record.get("hostLeak") not in (None, "none") and not problems:
        problems.append("hostLeak=%s but no leaking field found" % record.get("hostLeak"))
    return problems


def warnings(record):
    """Known divergences: reported, never failing (contract section 6)."""
    notes = []
    if record.get("sourceDirForm") != "container-private":
        notes.append("sourceDir is not under the container data dir: %s" % record.get("sourceDir"))
    if record.get("installSource") and record["installSource"].get("installer") not in (None, "", "null"):
        notes.append("installSource.installer=%s (expected no store for an imported copy)"
                     % record["installSource"]["installer"])
    if record.get("installer") not in (None, "null"):
        notes.append("installer=%s (expected null for an imported copy)" % record.get("installer"))
    return notes


def read_logcat(serial):
    result = subprocess.run(["adb", "-s", serial, "logcat", "-d", "-s", TAG + ":I"],
                            capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise SystemExit("ADB_FAILED: " + result.stderr.strip())
    return result.stdout


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--logcat", action="store_true", help="read the device log buffer (adb logcat -d)")
    parser.add_argument("--file", help="read a saved logcat file")
    parser.add_argument("--stdin", action="store_true", help="read logcat text from stdin")
    parser.add_argument("--host", default=DEFAULT_HOST, help="host (container) package name")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="adb serial for --logcat")
    parser.add_argument("--json", action="store_true", help="emit the parsed records as JSON")
    args = parser.parse_args(argv)

    if args.logcat:
        text = read_logcat(args.serial)
    elif args.file:
        text = open(args.file, "r", encoding="utf-8", errors="replace").read()
    elif args.stdin:
        text = sys.stdin.read()
    else:
        parser.error("one of --logcat/--file/--stdin is required")

    records = facade_records(text)
    if args.json:
        print(json.dumps(records, ensure_ascii=False, indent=2))

    if not records:
        print("NO_RECORD: no %s facadeAnswer line in this window" % TAG)
        return 0

    failures = 0
    for record in records:
        problems = violations(record, args.host)
        summary = {key: record.get(key) for key in ("package", "installer", "installSource", "uidPackages", "verdict")}
        if problems:
            failures += 1
            print("FAIL " + json.dumps(summary, ensure_ascii=False))
            for problem in problems:
                print("     " + problem)
        else:
            print("PASS " + json.dumps(summary, ensure_ascii=False))
        for note in warnings(record):
            print("     warn: " + note)

    print("records=%d failures=%d" % (len(records), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
