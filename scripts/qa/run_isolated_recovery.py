#!/usr/bin/env python3
"""One real owned authority crash with a held isolated service; never clears or replays."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import threading
import time
from paired_log import structural_log

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='d861a4fa')
parser.add_argument('--snapshot', required=True, type=Path)
parser.add_argument('--fixture', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
snapshot, fixture = args.snapshot.resolve(), args.fixture.resolve()
receipt = json.loads((snapshot / 'product-receipt.json').read_text())
engine = receipt['artifacts']['engine']
if engine['package'] != 'dev.tripoder.compat20261023' or not receipt['recoveryAcceptanceFixture']:
    raise RuntimeError('OWNED_QA_RECOVERY_FIXTURE_REQUIRED')
fixture_receipt = json.loads((fixture.parent / 'receipt.json').read_text())
digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
if digest != fixture_receipt['apkSha256']:
    raise RuntimeError('OWNED_RECOVERY_FIXTURE_CHANGED')
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
adb = ['adb', '-s', args.serial]


def run(*command, timeout=30):
    return subprocess.run([*adb, *map(str, command)], capture_output=True, text=True, timeout=timeout)


paths = run('shell', 'pm', 'path', engine['package']).stdout.splitlines()
if len(paths) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9/._~+=-]+/base\.apk', paths[0]):
    raise RuntimeError('OWNED_INSTALLED_ENGINE_PATH_INVALID')
if run('shell', 'sha256sum', paths[0][8:]).stdout.split()[0] != engine['sha256']:
    raise RuntimeError('OWNED_INSTALLED_ENGINE_MISMATCH')
if engine['package']+'.tests/dev.tripoder.hooklab.IsolatedRecoveryIntegration' not in run('shell', 'pm', 'list', 'instrumentation').stdout:
    raise RuntimeError('OWNED_RECOVERY_HARNESS_REQUIRED')
stage = '/sdcard/Android/data/' + engine['package'] + '/files/isolated-recovery-fixture.apk'
uploaded = run('push', fixture, stage)
if uploaded.returncode:
    raise RuntimeError('OWNED_RECOVERY_FIXTURE_UPLOAD_FAILED')
shutil.copyfile(fixture, out / 'fixture.apk')
shutil.copyfile(fixture.parent / 'receipt.json', out / 'fixture-receipt.json')
shutil.copyfile(snapshot / 'product-receipt.json', out / 'product-receipt.json')
for source in ['IsolatedRecoveryIntegration.java', 'IsolatedExecutionInspection.java', 'JobMutationInspection.java']:
    shutil.copyfile(repo / 'android/compatibility-test' / source, out / source)
shutil.copyfile(Path(__file__), out / 'run-isolated-recovery.py')
shutil.copyfile(repo / 'scripts/qa/paired_log.py', out / 'paired-log.py')
shutil.copyfile(repo / 'android/compatibility-test/build/paired.apk', out / 'paired-harness.apk')
run_id = 'owned-recovery-' + str(int(time.time()))
clock = run('shell', 'date', '+%s').stdout.strip()
if not re.fullmatch(r'\d{10,12}', clock):
    raise RuntimeError('OWNED_DEVICE_CLOCK_UNCONFIRMED')
captured, owners = [], set()
for suffix in ('', ':authority', ':recovery'):
    owners.update(pid for pid in run('shell', 'pidof', engine['package']+suffix).stdout.split() if pid.isdigit())
logger = subprocess.Popen([*adb, 'logcat', '-v', 'epoch', '-T', '1'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors='replace', bufsize=1)


def collect():
    for line in logger.stdout:
        match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+', line)
        announced = re.search(r'Start proc (\d+):'+re.escape(engine['package']), line)
        if announced:
            owners.add(announced[1])
        if match and float(match[1]) >= int(clock) and (match[2] in owners or 'Tripoder' in line or engine['package'] in line or 'dev.tripoder.isolatedservicecontract' in line):
            captured.append(structural_log(line))


reader = threading.Thread(target=collect, daemon=True)
reader.start()
command = [*adb, 'shell', 'am', 'instrument', '-w', '-e', 'run', run_id, '-e', 'fixture', stage, '-e', 'fixtureSha', digest,
           engine['package'] + '.tests/dev.tripoder.hooklab.IsolatedRecoveryIntegration']
with (out / 'instrumentation.txt').open('wb') as stream:
    process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
    time.sleep(2)
    run('shell', 'input', 'keyevent', '224')
    run('shell', 'am', 'start', '-n', engine['package'] + '/dev.tripoder.hooklab.WorkbenchActivity')
    time.sleep(2)
    dumped = run('shell', 'uiautomator', 'dump', '/sdcard/owned-recovery-workbench.xml', timeout=15)
    ui = run('shell', 'cat', '/sdcard/owned-recovery-workbench.xml').stdout
    button = re.search(r'text="重新连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
    if button:
        run('shell', 'input', 'tap', str((int(button[1])+int(button[3]))//2), str((int(button[2])+int(button[4]))//2))
    deadline, last = time.monotonic()+300, None
    while process.poll() is None and time.monotonic() < deadline:
        value = run('shell', 'cat', '/sdcard/Android/data/'+engine['package']+'/files/isolated-recovery-'+run_id+'.json')
        try:
            progress = json.loads(value.stdout)
            (out / 'progress.json').write_text(json.dumps(progress, ensure_ascii=False, indent=2)+'\n')
            if progress.get('phase') != last:
                print(progress.get('phase'), flush=True)
                last = progress.get('phase')
        except ValueError:
            pass
        time.sleep(1)
    timed_out = process.poll() is None
    if timed_out:
        process.kill()  # Only this host-side adb observer, never an Android PID.
    process.wait(timeout=5)
logger.terminate()
reader.join(timeout=5)
logger.wait(timeout=5)
(out / 'logcat.txt').write_text('\n'.join(captured)+'\n')
text = (out / 'instrumentation.txt').read_text()
payload = next((line.split('report=',1)[1] for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')), None)
result = json.loads(payload) if payload else {'passed': False, 'reason': 'OWNED_RECOVERY_RESULT_MISSING', 'observerTimedOut': timed_out}
result.update(engineSha256=engine['sha256'], fixtureSha256=digest, sourceSnapshot=str(snapshot), dataCleared=False)
(out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({'passed': result.get('passed'), 'phase': result.get('phase'), 'checks': len(result.get('checks',[])), 'error': result.get('error')}, ensure_ascii=False))
if not result.get('passed'):
    raise SystemExit(1)
