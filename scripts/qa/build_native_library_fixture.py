#!/usr/bin/env python3
"""Freeze an owned JNI/DT_NEEDED identity probe, with and without DT_SONAME."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import sys

repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

parser = argparse.ArgumentParser()
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
source = repo / 'android/compatibility-test/native-library-fixture'
build = repo / 'android/compatibility-test/build/native-library-fixture'
builder = ApkBuilder(repo, build, Path('/opt/homebrew/share/android-commandlinetools'),
                     'android-37.0', '37.0.0',
                     Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'),
                     repo / 'android/runtime-probe/build/acceptance-only.p12')
compiler = Path('/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang')
libraries = []
for named in (False, True):
    suffix = 'named' if named else ''
    dependency = build / ('libowned' + suffix + 'dependency.so')
    driver = build / ('libowned' + suffix + 'driver.so')
    for role, output in [('dependency', dependency), ('driver', driver)]:
        text = (source / (role + '.c')).read_text()
        if named:
            text = text.replace('owned_dependency_', 'owned_named_dependency_')
            text = text.replace('Main_dependency', 'Main_namedDependency')
            text = text.replace('Main_driverDependency', 'Main_namedDriverDependency')
        generated = build / (suffix + role + '.c')
        generated.write_text(text)
        command = [compiler, '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                   '-Wl,-z,max-page-size=16384', generated, '-o', output]
        if named:
            command += ['-Wl,-soname,' + output.name]
        if role == 'driver':
            command += ['-L' + str(build), '-lowned' + suffix + 'dependency', '-Wl,--no-undefined']
        builder.run(*command)
        libraries.append(output)
apk = builder.apk('native-library-contract-fixture', sorted(source.glob('*.java')),
                  source / 'AndroidManifest.xml', libraries=libraries)
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(apk, out / 'fixture.apk')
hashes = {}
for item in [*sorted(source.iterdir()), Path(__file__)]:
    if item.is_file():
        relative = item.resolve().relative_to(repo)
        target = out / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
        hashes[str(relative)] = hashlib.sha256(item.read_bytes()).hexdigest()
(out / 'receipt.json').write_text(json.dumps({
    'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes,
    'libraries': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in libraries}
}, indent=2) + '\n')
print(out / 'fixture.apk')
