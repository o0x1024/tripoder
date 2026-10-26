#!/usr/bin/env python3
"""Build a fixed-version owned peer for one explicit provider wire contract."""
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
parser.add_argument('--protocol', required=True, type=int, choices=(2,))
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
source = repo / 'android/compatibility-test/provider-peer-fixture'
build = repo / ('android/compatibility-test/build/provider-peer-v' + str(args.protocol))
generated = build / 'generated'
generated.mkdir(parents=True, exist_ok=True)
manifest = generated / 'AndroidManifest.xml'
manifest.write_text((source / 'AndroidManifest.xml').read_text().replace('android:versionCode="1"', 'android:versionCode="' + str(args.protocol) + '"'))
protocol = generated / 'PeerProtocol.java'
protocol.write_text('package dev.tripoder.providerpeer; final class PeerProtocol { static final int VERSION=' + str(args.protocol) + '; }\n')
builder = ApkBuilder(repo, build,
    Path('/opt/homebrew/share/android-commandlinetools'), 'android-37.0', '37.0.0',
    Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'),
    repo / 'android/runtime-probe/build/acceptance-only.p12')
apk = builder.apk('provider-peer-fixture', [*sorted(source.glob('*.java')), protocol], manifest)
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(apk, out / 'fixture.apk')
hashes = {}
for item in [*sorted(source.iterdir()), protocol, manifest, Path(__file__)]:
    if item.is_file():
        relative = item.resolve().relative_to(repo)
        target = out / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, target)
        hashes[str(relative)] = hashlib.sha256(item.read_bytes()).hexdigest()
(out / 'receipt.json').write_text(json.dumps({'wireProtocol': args.protocol, 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes}, indent=2) + '\n')
print(out / 'fixture.apk')
