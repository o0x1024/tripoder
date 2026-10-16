#!/usr/bin/env python3
"""Build the isolated software-VM experiment. Dependency preparation is explicit and local."""
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
    parser.add_argument("--platform", required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "vm-probe"
    build = root / "build"
    lock = json.loads((root / "dependencies.lock.json").read_text())
    if json.loads((build / "assets/media.json").read_text()) != lock["media"]:
        raise SystemExit("Media manifest differs from the pinned lock")
    native = build / "native/output"
    native_receipt = json.loads((native / "receipt.json").read_text())
    for name, expected in native_receipt["inputs"].items():
        if hashlib.sha256((root / "native" / name).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Native build input changed; rebuild the engine: {name}")
    for name, expected in native_receipt["libraries"].items():
        if hashlib.sha256((native / name).read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Native dependency hash mismatch: {name}")
    (build / "assets/native-build.json").write_text(json.dumps(native_receipt, indent=2) + "\n")
    tools = args.sdk.resolve() / "build-tools" / args.build_tools
    java = args.java_home.resolve()
    android_jar = args.sdk.resolve() / "platforms" / args.platform / "android.jar"
    environment = dict(os.environ, JAVA_HOME=str(java))
    environment["PATH"] = str(java / "bin") + os.pathsep + environment.get("PATH", "")

    def run(*command):
        subprocess.run([str(item) for item in command], env=environment, check=True, cwd=root)

    classes, dex = build / "classes", build / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(exist_ok=True)
    for item in classes.rglob("*.class"):
        item.unlink()
    for item in dex.glob("*.dex"):
        item.unlink()
    run(java / "bin/javac", "--release", "17", "-classpath", android_jar,
        "-d", classes, *sorted((root / "src").rglob("*.java")))
    run(tools / "d8", "--release", "--min-api", "33", "--lib", android_jar,
        "--output", dex, *sorted(classes.rglob("*.class")))
    unsigned = build / "unsigned.apk"
    run(tools / "aapt2", "link", "-I", android_jar, "--manifest", root / "AndroidManifest.xml",
        "-A", build / "assets", "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for item in sorted(dex.glob("*.dex")):
            apk.write(item, item.name)
        for name in sorted(native_receipt["libraries"]):
            item = native / name
            apk.write(item, "lib/arm64-v8a/" + item.name)
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    key = build / "vm-probe-test-only.p12"
    if not key.exists():
        run(java / "bin/keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "vm-probe-test-only", "-keypass", "vm-probe-test-only", "-alias", "probe",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
            "-dname", "CN=Tripoder VM Probe Test Only", "-noprompt")
    output = build / "vm-probe.apk"
    run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "probe",
        "--ks-pass", "pass:vm-probe-test-only", "--out", output, aligned)
    run(tools / "apksigner", "verify", "--verbose", output)
    print(json.dumps({"apk": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
