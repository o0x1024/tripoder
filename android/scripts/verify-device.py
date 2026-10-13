#!/usr/bin/env python3
"""Deploy/observe only. All target calls and Hooks execute autonomously inside the APK."""
import argparse
import hashlib
import json
import re
import socket
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--skip-install', action='store_true', help='Verify the already installed matching APK')
parser.add_argument('--offline', action='store_true', help='Stop the host ADB server during the delayed self-test')
suites = parser.add_mutually_exclusive_group()
suites.add_argument('--guest', action='store_true', help='Run the independent APK Hook suite')
suites.add_argument('--workbench', action='store_true', help='Run dynamic TypeScript and local AI transport contract tests')
suites.add_argument('--component', action='store_true', help='Run Activity, graphics, input, files and environment tests')
suites.add_argument('--project', action='store_true', help='Run project persistence, process switching and stale-client rejection')
parser.add_argument('--restart', action='store_true', help='Also verify guest process restart and data retention')
parser.add_argument('--label', default='', help='Optional simple artifact folder prefix')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
apk = root / 'android/app/build/outputs/apk/release/app-release.apk'
if args.restart and not args.guest:
    parser.error('--restart requires --guest')
if args.label and not re.fullmatch(r'[A-Za-z0-9._-]+', args.label):
    parser.error('--label must be a simple folder name')
guest_process = args.guest or args.workbench or args.component
suite = 'guest' if args.guest else 'workbench' if args.workbench else 'component' if args.component else 'project' if args.project else 'device'
prefix = args.label or suite
out = root / 'artifacts' / (prefix + ('-offline' if args.offline else '-connected'))
out.mkdir(parents=True, exist_ok=True)
package = 'dev.tripoder.hooklab'


def adb(*parts):
    result = subprocess.run(['adb', '-s', args.serial, *parts], capture_output=True)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr).decode(errors='replace'))
    return result.stdout


if not args.skip_install:
    print(adb('install', '-r', str(apk)).decode(), flush=True)
adb('shell', 'am', 'force-stop', package)
time.sleep(1)
device_epoch_ms = int(adb('shell', 'date', '+%s').decode().strip()) * 1000
clock_offset_ms = device_epoch_ms - time.time() * 1000
delay = 20 if args.offline else 0
extras = ['--ez', 'guest', 'true'] if args.guest or args.component else []
if args.component:
    extras += ['--ez', 'component', 'true']
if args.workbench:
    extras += ['--ez', 'workbench', 'true', '--ez', 'selfTest', 'true']
if args.project:
    extras += ['--ez', 'projectTest', 'true']
if args.restart:
    extras += ['--ez', 'verifyRestart', 'true']
print(adb('shell', 'am', 'start', '-S', '-W', '-n', package + '/.MainActivity', '--ei', 'autoDelaySeconds', str(delay), *extras).decode(), flush=True)
host = {'mode': 'host-adb-stopped' if args.offline else 'adb-connected',
        'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'deviceClockOffsetMsApprox': round(clock_offset_ms)}
if args.offline:
    subprocess.run(['adb', 'kill-server'], check=True)
    host['adbStoppedAtHostMs'] = round(time.time() * 1000)
    print('Host ADB stopped; waiting 40 seconds for the autonomous on-device test.', flush=True)
    host['hostAdbPortClosedForWholeInterval'] = True
    host['hostAdbPortChecks'] = 0
    try:
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(('127.0.0.1', 5037), timeout=0.2):
                    host['hostAdbPortClosedForWholeInterval'] = False
            except ConnectionRefusedError:
                pass
            except OSError:
                # Only a refused connection proves that there was no local listener.
                host['hostAdbPortClosedForWholeInterval'] = False
            host['hostAdbPortChecks'] += 1
            time.sleep(0.5)
    finally:
        host['adbRestartedAtHostMs'] = round(time.time() * 1000)
        subprocess.run(['adb', 'start-server'], check=True)
        adb('wait-for-device')
else:
    time.sleep(15)

destination = out / 'result.json'
result_name = suite + '-result.json' if guest_process or args.project else 'last-result.json'
adb('pull', '/sdcard/Android/data/' + package + '/files/' + result_name, str(destination))
report = json.loads(destination.read_text())
assert report['passed'], report.get('error', report)
assert report['checks'] and all(c['passed'] for c in report['checks'])
assert report['uid'] >= 10000 and not report['debuggable'] and not report['debuggerConnected']
if guest_process:
    assert report['hostApkSha256'] == host['apkSha256']
    assert report['pid'] != report['hostPid']
    guest_apk = root / 'android/guest-fixture/build/outputs/apk/release/guest-fixture-release.apk'
    host['guestApkSha256'] = hashlib.sha256(guest_apk.read_bytes()).hexdigest()
    assert report['guestApkSha256'] == host['guestApkSha256'] == report['finalAgent']['evidence']['target']['apkSha256']
    host['guestNotInstalledInSystem'] = not adb('shell', 'pm', 'list', 'packages', 'dev.tripoder.fixture').strip()
    assert host['guestNotInstalledInSystem'], 'Remove the separately installed fixture before using this specific acceptance check'
elif args.project:
    assert report['hostApkSha256'] == host['apkSha256']
else:
    assert report['finalAgent']['evidence']['target']['apkSha256'] == host['apkSha256']
assert report['startedAtMs'] >= device_epoch_ms, 'Stale result from an earlier run'
if args.offline:
    assert host['hostAdbPortClosedForWholeInterval'], 'An ADB listener returned during the test interval'
    lower = host['adbStoppedAtHostMs'] + clock_offset_ms + 2000
    upper = host['adbRestartedAtHostMs'] + clock_offset_ms - 2000
    host['entireTestWithinHostAdbStoppedInterval'] = lower < report['startedAtMs'] <= report['finishedAtMs'] < upper
    assert host['entireTestWithinHostAdbStoppedInterval'], 'Test did not fit fully inside ADB-stopped interval'
    # The phone's USB debugging switch/authorization was not modified. This records exactly what was tested.
    host['phoneAdbAuthorizationRevoked'] = False
if args.restart:
    restart_file = out / 'restart-result.json'
    adb('pull', '/sdcard/Android/data/' + package + '/files/guest-restart-result.json', str(restart_file))
    restart = json.loads(restart_file.read_text())
    assert restart['passed'] and restart['after']['session'] == report['session']
    assert restart['before']['startedAtMs'] >= device_epoch_ms
    assert restart['before']['hostApkSha256'] == restart['after']['hostApkSha256'] == host['apkSha256']
    if args.offline:
        assert lower < restart['before']['startedAtMs'] < restart['after']['finishedAtMs'] < upper
    host['restartVerified'] = True
(out / 'host-verification.json').write_text(json.dumps(host, indent=2) + '\n')
displays = adb('shell', 'dumpsys', 'SurfaceFlinger', '--display-id').decode()
primary = re.search(r'Display (\d+) \(HWC display 0\)', displays)
screen_args = ['-d', primary.group(1)] if primary else []
screen = adb('exec-out', 'screencap', '-p', *screen_args)
# Some multi-display devices prepend a textual warning even to exec-out.
png_start = screen.find(b'\x89PNG\r\n\x1a\n')
assert png_start >= 0, 'No PNG returned by screencap'
(out / 'screen.png').write_bytes(screen[png_start:])
(out / 'logcat.txt').write_bytes(adb('logcat', '-d', '--pid=' + str(report['pid']), '-s', 'Tripoder:I', 'TripoderGuest:I', 'frida:I', 'AndroidRuntime:E', '*:S'))
print(json.dumps({'passed': True, 'checks': len(report['checks']), 'output': str(out), **host}, indent=2))
