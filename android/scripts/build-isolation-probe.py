#!/usr/bin/env python3
"""Build the permission probe with explicit installed SDK/JDK tools; never install or grant."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--platform", required=True, help="Installed platform directory, e.g. android-37.0")
    parser.add_argument("--build-tools", required=True, help="Installed build-tools version")
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "isolation-probe"
    build = root / "build"
    tools = args.sdk.resolve() / "build-tools" / args.build_tools
    java = args.java_home.resolve()
    android_jar = args.sdk.resolve() / "platforms" / args.platform / "android.jar"
    for path in [android_jar, java / "bin/javac", java / "bin/keytool"] + [tools / name for name in ["aapt2", "d8", "zipalign", "apksigner"]]:
        if not path.is_file():
            raise SystemExit(f"Required tool missing: {path}")
    environment = dict(os.environ, JAVA_HOME=str(java))
    environment["PATH"] = str(java / "bin") + os.pathsep + environment.get("PATH", "")

    def run(*command):
        subprocess.run([str(item) for item in command], env=environment, check=True, cwd=root)

    classes = build / "classes"
    dex = build / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    # Rebuild only this probe's generated outputs, never another module's files.
    for item in classes.rglob("*.class"):
        item.unlink()
    for item in dex.glob("*.dex"):
        item.unlink()
    run(java / "bin/javac", "--release", "17", "-classpath", android_jar,
        "-d", classes, *sorted((root / "src").rglob("*.java")))
    run(tools / "d8", "--release", "--min-api", "28", "--lib", android_jar,
        "--output", dex, *sorted(classes.rglob("*.class")))
    unsigned = build / "unsigned.apk"
    run(tools / "aapt2", "link", "-I", android_jar, "--manifest", root / "AndroidManifest.xml", "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for item in sorted(dex.glob("*.dex")):
            apk.write(item, item.name)
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    key = build / "probe-test-only.p12"
    if not key.exists():
        run(java / "bin/keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "probe-test-only", "-keypass", "probe-test-only", "-alias", "probe",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=Tripoder Isolation Probe Test Only", "-noprompt")
    output = build / "isolation-probe.apk"
    run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "probe",
        "--ks-pass", "pass:probe-test-only", "--out", output, aligned)
    run(tools / "apksigner", "verify", "--verbose", output)
    print(json.dumps({"apk": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
