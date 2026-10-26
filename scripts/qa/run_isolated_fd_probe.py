#!/usr/bin/env python3
"""Install and run the self-owned isolated FD probe; preserves every evidence/data file."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='d861a4fa')
parser.add_argument('--evidence', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
receipt_path = repo / 'android/isolated-fd-probe/build/receipt.json'
receipt = json.loads(receipt_path.read_text())
pkg = receipt['package']
if pkg != 'dev.tripoder.isolatedfdprobe':
    raise RuntimeError('OWNED_INDEPENDENT_PROBE_REQUIRED')
apk = Path(receipt['apk'])
if hashlib.sha256(apk.read_bytes()).hexdigest() != receipt['apkSha256']:
    raise RuntimeError('OWNED_PROBE_APK_CHANGED')
for name, expected in receipt['sources'].items():
    if hashlib.sha256((repo / name).read_bytes()).hexdigest() != expected:
        raise RuntimeError('OWNED_PROBE_SOURCE_CHANGED:' + name)
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(receipt_path, out / 'build-receipt.json')
shutil.copyfile(apk, out / 'probe.apk')
adb = ['adb', '-s', args.serial]


def run(*command):
    return subprocess.run(adb + list(map(str, command)), capture_output=True, text=True, timeout=90)


def files():
    return {name for name in run('shell', 'ls', '/sdcard/Android/data/' + pkg + '/files/').stdout.splitlines() if name.startswith('isolated-fd-') and name.endswith('.json')}


install = run('install', '--no-incremental', '-r', out / 'probe.apk')
(out / 'install.txt').write_text(install.stdout + install.stderr)
if install.returncode:
    raise RuntimeError('OWNED_PROBE_INSTALL_FAILED:' + install.stdout + install.stderr)
for companion in receipt.get('companions', []):
    source = Path(companion['apk'])
    if companion['package'] != 'dev.tripoder.isolatedfdpayload' or hashlib.sha256(source.read_bytes()).hexdigest() != companion['sha256']:
        raise RuntimeError('OWNED_COMPANION_CHANGED')
    shutil.copyfile(source, out / 'owned-public-payload.apk')
    installed = run('install', '--no-incremental', '-r', out / 'owned-public-payload.apk')
    (out / 'companion-install.txt').write_text(installed.stdout + installed.stderr)
    if installed.returncode:
        raise RuntimeError('OWNED_COMPANION_INSTALL_FAILED')
previous = files()
# Stop only this test APK's prior processes. Retained test files are never removed.
launch = run('shell', 'am', 'start', '-S', '-n', pkg + '/' + pkg + '.OwnerActivity')
(out / 'launch.txt').write_text(launch.stdout + launch.stderr)
deadline = time.monotonic() + 200
last = None
while time.monotonic() < deadline:
    fresh = files() - previous
    if len(fresh) > 1:
        raise RuntimeError('CONCURRENT_OWNED_PROBE_RUNS')
    if fresh:
        device = '/sdcard/Android/data/' + pkg + '/files/' + next(iter(fresh))
        try:
            last = json.loads(run('shell', 'cat', device).stdout)
        except ValueError:
            pass
        if last:
            (out / 'progress.json').write_text(json.dumps(last, ensure_ascii=False, indent=2) + '\n')
            if last.get('phase') in ('complete', 'failed'):
                break
    time.sleep(1)
if last is None:
    last = {'phase': 'UNKNOWN', 'reason': 'OBSERVATION_MISSING'}
elif last.get('phase') not in ('complete', 'failed'):
    last['hostObservation'] = 'DEADLINE_WITHOUT_TERMINAL_RESULT'
last.update(probeApkSha256=receipt['apkSha256'], dataCleared=False, stoppedOnlyPriorProbeProcesses=True)
(out / 'result.json').write_text(json.dumps(last, ensure_ascii=False, indent=2) + '\n')
run('shell', 'am', 'force-stop', pkg)
run('shell', 'am', 'start', '-n', 'dev.tripoder.compat20261023/dev.tripoder.hooklab.WorkbenchActivity')
print(json.dumps({'phase': last.get('phase'), 'cases': last.get('cases', []), 'detail': last.get('detail')}, ensure_ascii=False))
