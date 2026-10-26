#!/usr/bin/env python3
"""Build the owned native-service probe only; never installs, launches or clears data."""
from pathlib import Path
import hashlib, json, subprocess, sys, zipfile

source = Path(__file__).resolve().parent
repo = source.parents[1]
sys.path.insert(0, str(repo / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

sdk = Path('/opt/homebrew/share/android-commandlinetools')
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
tools = sdk / 'build-tools/37.0.0'
build = source / 'build'
inputs = build / 'inputs'
inputs.mkdir(parents=True, exist_ok=True)
clang = sdk / 'ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android37-clang'
library = inputs / 'libperfnative.so'
subprocess.run([str(clang), '-shared', '-fPIC', '-Wl,-z,max-page-size=16384', '-Wl,-soname,libperfnative.so', str(source / 'native/probe.c'), '-o', str(library)], check=True)
builder = ApkBuilder(repo, build, sdk, 'android-37.0', '37.0.0', java, repo / 'android/runtime-probe/build/acceptance-only.p12')
raw = builder.apk('perf-fixture', sorted((source / 'src').glob('*.java')), source / 'AndroidManifest.xml', libraries=[library])
unsigned, aligned, final = [inputs / ('perf-fixture' + suffix) for suffix in ('-unsigned.apk', '-aligned.apk', '-public.apk')]
with zipfile.ZipFile(raw) as original, zipfile.ZipFile(unsigned, 'w') as target:
    for entry in original.infolist():
        if entry.filename.startswith('META-INF/'):
            continue
        contents = original.read(entry.filename)
        entry.compress_type = zipfile.ZIP_STORED if entry.filename.startswith('lib/') else entry.compress_type
        target.writestr(entry, contents)
subprocess.run([str(tools / 'zipalign'), '-f', '-P', '16', '4', str(unsigned), str(aligned)], check=True)
subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(repo / 'android/runtime-probe/build/acceptance-only.p12'), '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', str(final), str(aligned)], check=True)
subprocess.run([str(tools / 'apksigner'), 'verify', str(final)], check=True)
print(json.dumps({'apk': str(final), 'sha256': hashlib.sha256(final.read_bytes()).hexdigest(), 'deviceExecuted': False}))
