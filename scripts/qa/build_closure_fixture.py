#!/usr/bin/env python3
"""Build owned contracts and the same-package foreign resource negative fixture."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import sys

repo = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repo / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

parser = argparse.ArgumentParser()
parser.add_argument('--push', action='store_true')
parser.add_argument('--serial', default='d861a4fa')
parser.add_argument('--evidence', type=Path)
args = parser.parse_args()
sdk = Path('/opt/homebrew/share/android-commandlinetools')
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
build = repo / 'android/compatibility-test/build/owned-fixture'
builder = ApkBuilder(repo, build, sdk, 'android-37.0', '37.0.0', java, repo / 'android/runtime-probe/build/acceptance-only.p12')
foreign = repo / 'android/compatibility-test/resource-origin-fixture'
assets = build / 'owned-assets'
assets.mkdir(exist_ok=True)
compiled = build / 'foreign-resources.zip'
builder.run(builder.tools / 'aapt2', 'compile', '--dir', foreign / 'res', '-o', compiled)
builder.run(builder.tools / 'aapt2', 'link', '-I', builder.platform, '--manifest', foreign / 'AndroidManifest.xml', '-o', assets / 'foreign-resources.apk', compiled)
source = repo / 'android/compatibility-test/receiver-fixture'
apk = builder.apk('closure-fixture', sorted(source.glob('*.java')), source / 'AndroidManifest.xml', resources=source / 'res', assets=assets)
if args.evidence:
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(apk, out / 'fixture.apk')
    hashes = {}
    for directory in (source, foreign):
        for item in sorted(directory.rglob('*')):
            if item.is_file():
                relative = item.relative_to(repo)
                target = out / 'source' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(item, target)
                hashes[str(relative)] = hashlib.sha256(item.read_bytes()).hexdigest()
    hashes[str(Path(__file__).relative_to(repo))] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (out / 'receipt.json').write_text(json.dumps({'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'sourceSha256': hashes}, indent=2) + '\n')
if args.push:
    subprocess.run(['adb', '-s', args.serial, 'push', str(apk), '/sdcard/Android/data/dev.tripoder.compat20261023/files/closure-fixture.apk'], check=True)
print(apk)
