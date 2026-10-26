#!/usr/bin/env python3
"""Install the separate QA host and a separate bootstrap harness; preserve existing installations."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'android/scripts'))
from runtime_apk_builder import ApkBuilder, sha

os.umask(0o077)
parser=argparse.ArgumentParser()
parser.add_argument('--build-dir',type=Path,default=REPO/'android/product/build')
parser.add_argument('--package',default='dev.tripoder.nativetlsqa')
parser.add_argument('--output',type=Path,default=REPO/'.tripoder-local/native-tls')
parser.add_argument('--source-root',type=Path,default=REPO)
parser.add_argument('--retry-engine',action='store_true',help='Explicit one-time recovery for this QA host')
args=parser.parse_args()
source_root=args.source_root.resolve()
if args.package not in ('dev.tripoder.nativetlsqa','dev.tripoder.networksessionqa'):raise RuntimeError('OWNED_QA_PACKAGE_REQUIRED')
build = args.build_dir.resolve()
receipt = json.loads((build / 'build-receipt.json').read_text())
host = receipt['artifacts']['engine']
if host['package'] != args.package or sha(host['path']) != host['sha256']:
    raise RuntimeError('EXACT_QA_HOST_REQUIRED')
for name, digest in receipt['sourceSha256'].items():
    if sha(source_root / name) != digest:
        raise RuntimeError('BUILD_INPUT_CHANGED:' + name)
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
manifest = out / 'ControlManifest.xml'
manifest.write_text((build / 'generated/ControlTestManifest.xml').read_text().replace('package="dev.tripoder.controltest"', 'package="dev.tripoder.nativetlscontrol"'))
builder = ApkBuilder(source_root, out / 'build', '/opt/homebrew/share/android-commandlinetools', 'android-37.0', '37.0.0',
                     '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home', REPO / 'android/runtime-probe/build/acceptance-only.p12')
sources = sorted((source_root / 'android/control/src').rglob('*.java')) + sorted((source_root / 'android/control-test/src').rglob('*.java'))
harness = builder.apk('native-tls-control', sources, manifest, assets=build / 'test-assets')

def adb(*args, check=True):
    return subprocess.run(['adb', *map(str, args)], check=check, capture_output=True, text=True, timeout=60)

for apk in (host['path'], harness):
    print(adb('install', '--no-incremental', '-r', '-t', apk).stdout.strip())
run = 'control-' + uuid.uuid4().hex[:16]
remote = '/sdcard/Android/data/' + host['package'] + '/files/'
process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'runId', run,
                            *(['-e','retryEngine','true'] if args.retry_engine else []),'dev.tripoder.nativetlscontrol/dev.tripoder.controltest.NetworkMcpBootstrap'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
for _ in range(100):
    if adb('shell', 'test', '-f', remote + 'launch-workbench-' + run + '.json', check=False).returncode == 0:
        break
    if process.poll() is not None:
        raise RuntimeError('BOOTSTRAP_EXITED')
    time.sleep(.2)
adb('shell', 'am', 'start', '-W', '-n', host['package'] + '/dev.tripoder.hooklab.WorkbenchActivity')
stdout, stderr = process.communicate(timeout=60)
(out / 'bootstrap.txt').write_text(stdout + stderr)
result = json.loads(adb('shell', 'cat', remote + 'result-' + run + '.json').stdout)
(out / 'bootstrap-result.json').write_text(json.dumps(result, indent=2))
if not result['passed']:
    raise RuntimeError('BOOTSTRAP_FAILED:' + result.get('error', 'unknown'))
connection = out / 'connection.json'
connection.write_text(adb('shell', 'cat', remote + 'mcp-connection-' + run + '.json').stdout)
adb('shell', 'rm', remote + 'mcp-connection-' + run + '.json')
adb('shell', 'am', 'start', '-W', '-n', host['package'] + '/dev.tripoder.hooklab.WorkbenchActivity')
print(json.dumps({'connection': str(connection), 'hostSha256': host['sha256'], 'qaHost': host['package']}))
