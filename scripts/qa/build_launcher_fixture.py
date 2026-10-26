#!/usr/bin/env python3
"""Freeze the owned INFO/LAUNCHER, alias, priority and permission-selector contract."""
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
source = repo / 'android/compatibility-test/launcher-fixture'
builder = ApkBuilder(repo, repo / 'android/compatibility-test/build/launcher-fixture',
    Path('/opt/homebrew/share/android-commandlinetools'), 'android-37.0', '37.0.0',
    Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'),
    repo / 'android/runtime-probe/build/acceptance-only.p12')
apk = builder.apk('launcher-contract-fixture', sorted(source.glob('*.java')), source / 'AndroidManifest.xml', resources=source / 'res')
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(apk, out / 'fixture.apk')
hashes = {}
for item in [*sorted(source.rglob('*')), Path(__file__)]:
    if item.is_file():
        relative = item.resolve().relative_to(repo)
        target = out / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
        hashes[str(relative)] = hashlib.sha256(item.read_bytes()).hexdigest()
(out / 'receipt.json').write_text(json.dumps({'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes}, indent=2) + '\n')
print(out / 'fixture.apk')
