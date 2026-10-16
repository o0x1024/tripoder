#!/usr/bin/env python3
"""Compile the new Android fixture and run owned host/DEX checks; never touches a device or shared build."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

here = Path(__file__).resolve().parent
repo = here.parents[2]
output = here / "build"
java = Path(os.environ.get("TRIPODER_JAVA_HOME", "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"))
sdk = Path(os.environ.get("TRIPODER_ANDROID_SDK", "/opt/homebrew/share/android-commandlinetools"))
jar = sdk / "platforms/android-37.0/android.jar"
existing = repo / "android/self-hook-probe/build/self-hook-probe/classes"
classes = output / "classes"
dex = output / "dex"
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(parents=True, exist_ok=True)
fixture = sorted((here / "dev").rglob("*.java"))
script = sorted((repo / "android/runtime/src/dev/tripoder/runtime/script").glob("*.java"))
host = here / "host/ScriptFixtureHostCheck.java"
subprocess.run([str(java / "bin/javac"), "--release", "17", "-Xlint:all", "-classpath", os.pathsep.join(map(str, [jar, existing])), "-d", str(classes), *map(str, fixture + script + [host])], check=True)
# The existing probe manifest has minSdkVersion 36 / targetSdkVersion 37; use its actual DEX floor.
subprocess.run([str(sdk / "build-tools/37.0.0/d8"), "--release", "--min-api", "36", "--lib", str(jar), "--output", str(dex),
                str(classes / "dev/tripoder/selfhookprobe/ScriptOwnedFixture.class"), str(classes / "dev/tripoder/selfhookprobe/ScriptOwnedCaller.class")], check=True,
               env={**os.environ, "JAVA_HOME": str(java)})
result = subprocess.run([str(java / "bin/java"), "-ea", "-cp", os.pathsep.join(map(str, [classes, existing, jar])), "dev.tripoder.selfhookprobe.ScriptFixtureHostCheck", str(dex / "classes.dex")], check=True, capture_output=True, text=True)
print(result.stdout, end="")
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
receipt = {"schema": 1, "scope": "owned-host-codec-and-caller-dex-only", "deviceTested": False, "managedHookTested": False,
           "result": result.stdout.strip(), "sourceHashes": {str(p.relative_to(repo)): sha(p) for p in fixture + script + [host, Path(__file__).resolve()]},
           "dexSha256": sha(dex / "classes.dex"), "androidJarSha256": sha(jar), "dexMinApi": 36,
           "existingDependencyClassHashes": {name: sha(existing / name) for name in [
               "dev/tripoder/selfhook/ManagedHook.class", "dev/tripoder/selfhook/dex/DexFile.class", "dev/tripoder/selfhook/dex/DexCallerClosure.class",
               "dev/tripoder/selfhookprobe/ProbeFiles.class", "dev/tripoder/selfhookprobe/ProbeEnvironment.class", "dev/tripoder/selfhookprobe/ArtProbe.class", "dev/tripoder/selfhookprobe/ArtCompilation.class"]}}
(output / "host-report.json").write_text(json.dumps(receipt, indent=2) + "\n")
