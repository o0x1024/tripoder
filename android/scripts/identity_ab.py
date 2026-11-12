#!/usr/bin/env python3
"""Side-by-side install facts: the device's own install vs the guest facade answer.

Contract: docs/identity-provenance-contract.md (section 3 and section 7)

Why an A/B table and not a single log line: five of the seven facts can only be judged by comparison. The
device column is the platform's truth for the same package name; the container column is what the guest is
told through the object it actually holds. A fact that differs between the columns is a defect *unless* it is
one of the registered divergences (section 6), which is why they are printed as such instead of as failures.

The container column is read from a `TripoderIdentitySnapshot facadeAnswer={...}` log line, because that is the
one record taken through the guest's own PackageManager. Without a rebuild that line does not exist yet and the
table says so instead of guessing.

Usage:
    identity_ab.py --native-only
    identity_ab.py                          # native (adb) + container (logcat)
    identity_ab.py --logcat saved.log       # container facts from a saved logcat
    identity_ab.py --markdown               # emit the evidence table
"""
import argparse
import re
import subprocess
import sys

from identity_contract import DEFAULT_HOST, DEFAULT_SERIAL, facade_records

GUEST = "com.ss.android.ugc.aweme"


def adb(serial, *argv, check=True, timeout=60):
    result = subprocess.run(["adb", "-s", serial, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise SystemExit("ADB_FAILED: " + result.stderr.strip())
    return result.stdout


def native_facts(serial, pkg):
    """The platform's own answer for a package the device has installed."""
    dump = adb(serial, "shell", "dumpsys", "package", pkg)
    listing = adb(serial, "shell", "pm", "list", "packages", "-f", pkg, check=False)
    facts = {"package": pkg}

    def pick(pattern, text=dump):
        match = re.search(pattern, text)
        return match.group(1).strip() if match else None

    # Newer dumps print `appId=`, older ones `userId=`; both are the kernel uid of the install.
    facts["uid"] = pick(r"appId=(\d+)") or pick(r"userId=(\d+)")
    facts["versionCode"] = pick(r"versionCode=(\d+)")
    facts["versionName"] = pick(r"versionName=([^\s]+)")
    facts["codePath"] = pick(r"codePath=([^\s]+)")
    facts["installer"] = pick(r"installerPackageName=([^\s]+)")
    facts["targetSdk"] = pick(r"targetSdk=(\d+)")
    facts["signatures"] = pick(r"signatures=PackageSignatures\{[^}]*signatures:\[([^\]]*)\]")
    facts["apk"] = None
    for line in listing.splitlines():
        if line.startswith("package:") and line.endswith("=" + pkg):
            facts["apk"] = line[len("package:"):-len("=" + pkg)]
    return facts


def container_facts(logcat_text):
    records = facade_records(logcat_text)
    return records[-1] if records else None


def table(native, container, guest=GUEST, host=DEFAULT_HOST):
    def cell(value):
        return "`%s`" % value if value not in (None, "") else "n/a"

    source = (container or {}).get("installSource") or {}
    rows = [
        ("package (Self)", cell(native.get("package")), cell((container or {}).get("package")),
         "must equal the guest package"),
        ("uid (kernel, Attribution)", cell(native.get("uid")), cell((container or {}).get("uid")),
         "registered divergence: the guest runs under the host uid"),
        ("versionCode / versionName", "%s / %s" % (cell(native.get("versionCode")), cell(native.get("versionName"))),
         "%s / %s" % (cell((container or {}).get("versionCode")), cell((container or {}).get("versionName"))),
         "must match: same APK"),
        ("signer (Self)", cell(native.get("signatures")), cell((container or {}).get("signer")),
         "same APK bytes, so same signer; dumpsys prints hash codes, the facade prints SHA-256"),
        ("installer (Provenance)", cell(native.get("installer")), cell((container or {}).get("installer")),
         "truthfully different: the store delivered the device install, not the imported copy"),
        ("install source (Provenance)",
         "device: `%s` delivered it" % native.get("installer"),
         "`initiating=%s, originating=%s`" % (source.get("initiating"), source.get("originating")),
         "truthfully different for the same reason"),
        ("sourceDir form (Provenance)", "`%s`" % (native.get("codePath") or "").split("/base.apk")[0],
         cell((container or {}).get("sourceDir")),
         "registered divergence (section 6): the copy lives in the container's data dir"),
    ]
    lines = [
        "| 事实 | 真机原生安装 | 客体门面回答 | 判定 |",
        "| --- | --- | --- | --- |",
    ]
    for name, left, right, note in rows:
        lines.append("| %s | %s | %s | %s |" % (name, left, right, note))
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--package", default=GUEST)
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--logcat", help="logcat file holding TripoderIdentitySnapshot lines")
    parser.add_argument("--native-only", action="store_true", help="skip the container column")
    parser.add_argument("--markdown", action="store_true", help="emit the markdown table only")
    args = parser.parse_args(argv)

    native = native_facts(args.serial, args.package)
    container = None
    if not args.native_only:
        if args.logcat:
            container = container_facts(open(args.logcat, "r", encoding="utf-8", errors="replace").read())
        else:
            container = container_facts(adb(args.serial, "shell", "logcat", "-d", "-s",
                                            "TripoderIdentitySnapshot:I", check=False))

    if args.markdown:
        print(table(native, container, args.package, args.host))
        return 0

    print("native   " + " ".join("%s=%s" % item for item in sorted(native.items())))
    if container is None:
        print("container NO_RECORD: no facadeAnswer line in this window "
              "(the build under test predates IdentitySnapshot.logFacade)")
    else:
        print("container " + str({key: container.get(key) for key in
                                 ("package", "uid", "installer", "installSource", "uidPackages", "verdict")}))
    print()
    print(table(native, container, args.package, args.host))
    return 0


if __name__ == "__main__":
    sys.exit(main())
