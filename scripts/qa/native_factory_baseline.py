#!/usr/bin/env python3
"""Observe only the installed self-owned factory fixture, preserving all data."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import time
from paired_capture import capture

parser = argparse.ArgumentParser()
parser.add_argument('--fixture', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
parser.add_argument('--serial', default='d861a4fa')
args = parser.parse_args()
pkg = 'dev.tripoder.factorycontract'
adb = ['adb', '-s', args.serial]
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
receipt = json.loads((args.fixture / 'receipt.json').read_text())
expected = receipt['apkSha256']
if hashlib.sha256((args.fixture / 'fixture.apk').read_bytes()).hexdigest() != expected:
    raise ValueError('FROZEN_OWNED_FIXTURE_CHANGED')

def run(*values, timeout=60):
    return subprocess.run([*adb, *values], capture_output=True, text=True, timeout=timeout)

paths = run('shell', 'pm', 'path', pkg).stdout.splitlines()
if len(paths) != 1 or not paths[0].startswith('package:'):
    raise ValueError('OWNED_NATIVE_FIXTURE_NOT_INSTALLED')
actual = run('shell', 'sha256sum', paths[0][8:]).stdout.split()[0]
if actual != expected:
    raise ValueError('OWNED_NATIVE_INSTALL_DIGEST_MISMATCH')
owner = re.fullmatch(r'package:' + re.escape(pkg) + r' uid:(\d+)\s*',
    run('shell', 'cmd', 'package', 'list', 'packages', '-U', pkg).stdout)
if owner is None:
    raise ValueError('OWNED_NATIVE_UID_UNCONFIRMED')
uid = int(owner[1])
clock = run('shell', 'date', '+%s').stdout.strip()
if not re.fullmatch(r'\d{10,12}', clock):
    raise ValueError('DEVICE_LOG_CLOCK_UNCONFIRMED')
start = int(clock)
launched = run('shell', 'am', 'start', '-W', '-n', pkg + '/.Main')
(out / 'start.txt').write_text(launched.stdout + launched.stderr)
time.sleep(10)
pids = run('shell', 'pidof', pkg).stdout.split()
raw = ''
for pid in pids:
    collected = run('logcat', '-d', '-b', 'all', '-v', 'epoch', '--pid=' + pid,
        'TripoderFactoryContract:I', 'LoadedApk:E', 'AndroidRuntime:E', '*:S')
    if collected.returncode != 0:
        raise RuntimeError('OWNED_NATIVE_LOG_COLLECTION_FAILED')
    raw += collected.stdout
rows = []
for line in raw.splitlines():
    match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+', line)
    if match and float(match[1]) >= start and match[2] in pids:
        rows.append(line)
(out / 'owned-logcat.txt').write_text('\n'.join(rows) + '\n')
observed = None
for line in rows:
    if 'TripoderFactoryContract' in line and '{' in line:
        observed = json.loads(line[line.index('{'):])
# Only this self-owned fixture's output directory is created; no target-app
# directory or existing contents are removed or modified.
prepared = run('shell', 'mkdir', '-p', '/sdcard/Android/data/' + pkg + '/files')
if prepared.returncode != 0:
    raise RuntimeError('OWNED_NATIVE_OUTPUT_DIRECTORY_UNAVAILABLE')
ui = capture(run, out, pkg, 'owned-native-factory', pkg, uid)
stopped = run('shell', 'am', 'force-stop', pkg)
report = {
    'scope': 'self-owned-native-sdk-contract', 'package': pkg, 'uid': uid,
    'fixtureSha256': expected, 'installedSha256': actual, 'pids': pids,
    'deviceLogStartedEpochLowerBound': start,
    'observation': observed, 'nativeSdkFactoryWarning': any(
        'Unable to instantiate appComponentFactory' in line for line in rows),
    'stopConfirmed': stopped.returncode == 0,
    'dataCleared': False, 'businessAccepted': False, 'uiCapture': ui,
    'passed': bool(pids and observed and observed.get('passed')),
}
(out / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: report[k] for k in ('passed', 'fixtureSha256', 'observation', 'nativeSdkFactoryWarning')}, ensure_ascii=False))
