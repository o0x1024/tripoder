#!/usr/bin/env python3
"""Build the independent owned probe only; never installs, launches or clears data."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile

source = Path(__file__).resolve().parent
repo = source.parents[1]
sys.path.insert(0, str(repo / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

sdk = Path('/opt/homebrew/share/android-commandlinetools')
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
tools = sdk / 'build-tools/37.0.0'
platform = sdk / 'platforms/android-37.0/android.jar'
clang = sdk / 'ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android37-clang'
build = source / 'build'
inputs, assets = build / 'inputs', build / 'assets'
inputs.mkdir(parents=True, exist_ok=True)
assets.mkdir(parents=True, exist_ok=True)


def run(*args):
    subprocess.run(list(map(str, args)), check=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


leaf, dependent, checks = [inputs / name for name in ('libowned_fd_payload.so', 'libowned_fd_dependent.so', 'libisolatedfdchecks.so')]
common = [clang, '-shared', '-fPIC', '-Wl,-z,max-page-size=16384']
run(*common, '-nostdlib', '-Wl,-soname,libowned_fd_payload.so', source / 'native/payload.c', '-o', leaf)
run(*common, '-nostdlib', '-Wl,-soname,libowned_fd_dependent.so', source / 'native/dependent.c', '-L' + str(inputs), '-lowned_fd_payload', '-o', dependent)
run(*common, '-Wl,-soname,libisolatedfdchecks.so', source / 'native/fd_checks.c', '-ldl', '-o', checks)
for library in (leaf, dependent):
    shutil.copyfile(library, assets / library.name)

classes, dex = inputs / 'payload-classes', inputs / 'payload-dex'
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)
run(java / 'bin/javac', '--release', '17', '-cp', platform, '-d', classes, *sorted((source / 'payload').glob('*.java')))
run(tools / 'd8', '--release', '--min-api', '37', '--lib', platform, '--output', dex, *classes.rglob('*.class'))
shutil.copyfile(dex / 'classes.dex', assets / 'payload.dex')
compiled = inputs / 'resources.zip'
run(tools / 'aapt2', 'compile', '--dir', source / 'payload/res', '-o', compiled)
run(tools / 'aapt2', 'link', '-I', platform, '--manifest', source / 'payload/AndroidManifest.xml', '-o', assets / 'payload-resources.apk', compiled)
jni_libraries = []
for named in (False, True):
    suffix = 'named_' if named else ''
    leaf_name, driver_name = f'libowned_jni_{suffix}leaf.so', f'libowned_jni_{suffix}driver.so'
    jni_leaf, jni_driver = inputs / leaf_name, inputs / driver_name
    flags = ['-DOWNED_NAMED'] if named else []
    leaf_soname = ['-Wl,-soname,' + leaf_name] if named else []
    run(*common, *flags, *leaf_soname, source / 'native/jni_leaf.c', '-o', jni_leaf)
    run(*common, *flags, '-Wl,-soname,' + driver_name, source / 'native/jni_driver.c', '-L' + str(inputs), '-lowned_jni_' + suffix + 'leaf', '-o', jni_driver)
    jni_libraries.extend([jni_leaf, jni_driver])
# An explicit APK-FD namespace case, not a retry after a denied private-file load.
unaligned = inputs / 'payload-jni-unaligned.apk'
shutil.copyfile(assets / 'payload-resources.apk', unaligned)
with zipfile.ZipFile(unaligned, 'a', compression=zipfile.ZIP_STORED) as archive:
    archive.write(dex / 'classes.dex', 'classes.dex')
    for library in jni_libraries:
        archive.write(library, 'lib/arm64-v8a/' + library.name)
run(tools / 'zipalign', '-f', '-P', '16', '4', unaligned, assets / 'payload-jni.apk')
run(tools / 'zipalign', '-c', '-P', '16', '4', assets / 'payload-jni.apk')
builder = ApkBuilder(repo, build, sdk, 'android-37.0', '37.0.0', java, repo / 'android/runtime-probe/build/acceptance-only.p12')
# This probe owns every byte of its generated APK. The public-APK cases use
# Android's direct ZIP library loading, which requires uncompressed alignment.
def public_apk(original_apk, name):
    unsigned, aligned, final = [inputs / (name + suffix) for suffix in ('-unsigned.apk', '-aligned.apk', '-public.apk')]
    with zipfile.ZipFile(original_apk) as original, zipfile.ZipFile(unsigned, 'w') as target:
        for entry in original.infolist():
            if entry.filename.startswith('META-INF/'):
                continue
            contents = original.read(entry.filename)
            entry.compress_type = zipfile.ZIP_STORED if entry.filename.startswith('lib/') else entry.compress_type
            target.writestr(entry, contents)
    run(tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    run(tools / 'apksigner', 'sign', '--ks', repo / 'android/runtime-probe/build/acceptance-only.p12', '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', final, aligned)
    run(tools / 'apksigner', 'verify', final)
    return final

other = public_apk(builder.apk('isolated-public-payload', sorted((source / 'payload').glob('*.java')), source / 'public-payload/AndroidManifest.xml', libraries=jni_libraries), 'owned-other-native')
shutil.copyfile(other, assets / 'payload-other.apk')
apk = public_apk(builder.apk('isolated-fd-probe', [*sorted((source / 'src').glob('*.java')), source / 'payload/NativePayload.java'], source / 'AndroidManifest.xml', assets=assets, libraries=[checks, *jni_libraries]), 'owned-probe-native')
receipt = {'package': 'dev.tripoder.isolatedfdprobe', 'apk': str(apk), 'apkSha256': digest(apk), 'companions': [{'package': 'dev.tripoder.isolatedfdpayload', 'apk': str(other), 'sha256': digest(other)}], 'inputs': {p.name: digest(p) for p in sorted(assets.iterdir())}, 'sources': {str(p.relative_to(repo)): digest(p) for p in sorted(source.rglob('*')) if p.is_file() and build not in p.parents}, 'deviceExecuted': False}
(build / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'apk': str(apk), 'sha256': receipt['apkSha256'], 'deviceExecuted': False}))
