#!/usr/bin/env python3
"""Freeze a self-owned APK for native versus guest factory contract comparison."""
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
source = repo / 'android/compatibility-test/factory-fixture'
builder = ApkBuilder(repo, repo / 'android/compatibility-test/build/factory-fixture',
    Path('/opt/homebrew/share/android-commandlinetools'), 'android-37.0', '37.0.0',
    Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'),
    repo / 'android/runtime-probe/build/acceptance-only.p12')
apk = builder.apk('factory-contract-fixture', sorted(source.glob('*.java')), source / 'AndroidManifest.xml')
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(apk, out / 'fixture.apk')
hashes = {}
for item in sorted(source.iterdir()):
    if item.is_file():
        target = out / 'source' / item.relative_to(repo)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
        hashes[str(item.relative_to(repo))] = hashlib.sha256(item.read_bytes()).hexdigest()
hashes[str(Path(__file__).relative_to(repo))] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
(out / 'receipt.json').write_text(json.dumps({'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes}, indent=2) + '\n')
print(out / 'fixture.apk')
