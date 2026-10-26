#!/usr/bin/env python3
"""Freeze the owned true-isolated Service lifecycle fixture only."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import sys
import os
import subprocess
import zipfile

repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

parser = argparse.ArgumentParser()
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
source = repo / 'android/compatibility-test/isolated-service-fixture'
builder = ApkBuilder(repo, repo / 'android/compatibility-test/build/isolated-service-fixture',
    Path('/opt/homebrew/share/android-commandlinetools'), 'android-37.0', '37.0.0',
    Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'),
    repo / 'android/runtime-probe/build/acceptance-only.p12')
apk = builder.apk('isolated-service-fixture', sorted(source.glob('*.java')), source / 'AndroidManifest.xml', resources=source / 'res')
# Ten DEX entries expose numeric order: the duplicate Marker in classes10
# must never shadow the definition in classes2. Every input is self-owned.
extra = []
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
sdk = Path('/opt/homebrew/share/android-commandlinetools')
build = repo / 'android/compatibility-test/build/isolated-service-fixture'
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')
for number in range(2, 11):
    directory = build / ('multidex-' + str(number));directory.mkdir(exist_ok=True)
    classes, dex = directory / 'classes', directory / 'dex'
    classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
    name = 'OwnedDexMarker' if number in (2, 10) else 'OwnedPadding' + str(number)
    path = directory / (name + '.java')
    path.write_text('package dev.tripoder.isolatedservicecontract; public final class ' + name + ' { public static int answer(){return ' + str(number) + ';} }')
    subprocess.run([str(java / 'bin/javac'), '--release', '17', '-d', str(classes), str(path)], check=True, env=env)
    subprocess.run([str(sdk / 'build-tools/37.0.0/d8'), '--release', '--min-api', '37', '--lib', str(sdk / 'platforms/android-37.0/android.jar'), '--output', str(dex), *map(str, classes.rglob('*.class'))], check=True, env=env)
    extra.append((dex / 'classes.dex', 'classes' + str(number) + '.dex'))
unsigned = build / 'multidex-unsigned.apk'
with zipfile.ZipFile(apk) as original, zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED) as target:
    for entry in original.infolist():
        if not entry.filename.startswith('META-INF/'):
            target.writestr(entry.filename, original.read(entry))
    for path, name in extra:
        target.write(path, name)
aligned, final = build / 'multidex-aligned.apk', build / 'multidex-fixture.apk'
subprocess.run([str(sdk / 'build-tools/37.0.0/zipalign'), '-f', '4', str(unsigned), str(aligned)], check=True, env=env)
subprocess.run([str(sdk / 'build-tools/37.0.0/apksigner'), 'sign', '--ks', str(repo / 'android/runtime-probe/build/acceptance-only.p12'), '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', str(final), str(aligned)], check=True, env=env)
apk = final
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(apk, out / 'fixture.apk')
hashes = {}
for item in [*source.rglob('*'), Path(__file__)]:
    if item.is_file():
        relative = item.resolve().relative_to(repo)
        target = out / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
        hashes[str(relative)] = hashlib.sha256(item.read_bytes()).hexdigest()
(out / 'receipt.json').write_text(json.dumps({'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes}, indent=2) + '\n')
print(out / 'fixture.apk')
