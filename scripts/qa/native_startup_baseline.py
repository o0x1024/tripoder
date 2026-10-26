#!/usr/bin/env python3
"""Cold native entry comparison using an exact public-APK receipt; saves code events only, no UI/account contents."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import threading
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--metadata', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
parser.add_argument('--seconds', type=int, default=20)
args = parser.parse_args()
if not 10 <= args.seconds <= 45:
    raise ValueError('BOUNDED_NATIVE_WINDOW_REQUIRED')
metadata = json.loads(args.metadata.read_text())
metadata = metadata.get('nativeCode', metadata)
package = metadata['package']
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', package) or package.startswith('dev.tripoder.'):
    raise ValueError('ORIGINAL_REPORTED_PACKAGE_REQUIRED')
if metadata.get('sameCompleteApkBytes') is not True or metadata['nativeUid'] < 10000:
    raise ValueError('SAME_APK_ORDINARY_APP_UID_REQUIRED')
entry = metadata['nativeEntry']
component = entry['component']
if not re.fullmatch(re.escape(package) + r'/[A-Za-z0-9_.$]+', component) or entry['action'] != 'android.intent.action.MAIN':
    raise ValueError('OBSERVED_NATIVE_MAIN_COMPONENT_REQUIRED')
categories = entry['categories']
if len(categories) != 1 or categories[0] not in ('android.intent.category.INFO', 'android.intent.category.LAUNCHER'):
    raise ValueError('OBSERVED_NATIVE_ENTRY_CATEGORY_REQUIRED')
adb = ['adb', '-s', args.serial]


def run(*command, timeout=45):
    argv = [*adb, 'shell', ' '.join(shlex.quote(value) for value in command[1:])] if command[0] == 'shell' else [*adb, *command]
    result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('NATIVE_COMMAND_FAILED:' + command[0])
    return result.stdout


if run('shell', 'am', 'get-current-user').strip() != '0':
    raise ValueError('RECEIPT_USER_ZERO_REQUIRED')
current_paths = [line.removeprefix('package:') for line in run('shell', 'pm', 'path', '--user', '0', package).splitlines() if line.startswith('package:')]
if sorted(current_paths) != sorted(part['path'] for part in metadata['nativeParts']):
    raise ValueError('NATIVE_CURRENT_INSTALL_PATHS_CHANGED')
uid_rows = re.findall(r'^package:' + re.escape(package) + r'\s+uid:(\d+)\s*$', run('shell', 'pm', 'list', 'packages', '-U', '--user', '0', package), re.M)
if len(uid_rows) != 1 or int(uid_rows[0]) != metadata['nativeUid'] or int(uid_rows[0]) < 10000:
    raise ValueError('NATIVE_CURRENT_UID_CHANGED')
for part in metadata['nativeParts']:
    path = part['path']
    if not re.fullmatch(r'/[A-Za-z0-9_./~+=-]+\.apk', path):
        raise ValueError('PUBLIC_APK_PATH_REQUIRED')
    if run('shell', 'sha256sum', path).split()[0] != part['sha256']:
        raise ValueError('NATIVE_CODE_CHANGED_SINCE_RECEIPT')
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
report = {'package': package, 'nativeUid': metadata['nativeUid'], 'nativeVersion': metadata['nativeVersion'],
          'nativeEntry': entry, 'sameCompleteApkBytes': True, 'qaInstallation': metadata['installation'],
          'sourceMetadataSha256': hashlib.sha256(args.metadata.read_bytes()).hexdigest(),
          'dataCleared': False, 'accountDataRead': False, 'authInputEntered': False,
          'consentButtonPressed': False, 'uiContentsCaptured': False, 'businessAccepted': False,
          'nativeDataState': 'EXISTING_DATA_PRESERVED_NOT_PROVEN_EQUIVALENT_TO_QA_SPACE',
          'nativeParts': metadata['nativeParts'], 'events': [], 'samples': []}
(out / 'input.json').write_text(json.dumps(report, indent=2) + '\n')
# Process stop establishes a cold constructor/factory boundary; it does not clear app storage.
run('shell', 'am', 'force-stop', '--user', '0', package)
report['coldProcessStopUsed'] = True
existing = subprocess.run([*adb, 'shell', 'pidof', package], capture_output=True, text=True).stdout.strip()
if existing:
    report['status'] = 'COLD_PRECONDITION_UNCONFIRMED'
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    raise RuntimeError('NATIVE_PROCESS_STILL_PRESENT_AFTER_STOP')
clock = run('shell', 'date', '+%s').strip()
if not re.fullmatch(r'\d{10,12}', clock):
    raise RuntimeError('DEVICE_CLOCK_UNCONFIRMED')
lower = int(clock)
pids = set()
crash_helpers = set()
stopping = threading.Event()
stream = {'earlyEof': False, 'errorType': None}
logger = subprocess.Popen([*adb, 'logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-v', 'epoch', '-T', '1'],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors='replace', bufsize=1)


def collect_lines():
    for line in logger.stdout:
        parsed = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+\w\s+([^:]+):\s*(.*)$', line)
        if not parsed or float(parsed[1]) < lower:
            continue
        body = parsed[5]
        tag = parsed[4].strip()
        start = re.search(r'Start proc (\d+):([^\s/]+)', body)
        if start and (start[2] == package or start[2].startswith(package + ':') or '{' + package + '/' in body):
            pids.add(int(start[1]))
        crash_owner = re.search(r'pid:\s*(\d+),\s*tid:', body)
        if crash_owner and int(crash_owner[1]) in pids and parsed[4].strip() == 'DEBUG':
            crash_helpers.add(int(parsed[2]))
        if int(parsed[2]) not in pids | crash_helpers or len(report['events']) >= 256:
            continue
        if tag not in ('libc', 'AndroidRuntime', 'Process', 'DEBUG'):
            continue
        event = {'epoch': parsed[1], 'pid': int(parsed[2]), 'tid': int(parsed[3]), 'tag': tag}
        fatal = re.search(r'(?:Fatal signal (\d+)|handling signal:\s*(\d+))', body)
        frame = re.search(r'\bat ([A-Za-z_$][A-Za-z0-9_.$<>]+)\((Unknown Source|Native Method|[A-Za-z0-9_.$<>-]+)(?::(\d+))?\)', body)
        native_frame = re.search(r'#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+([^\s]+\.so)', body)
        exception = re.match(r'(?:Caused by: )?([A-Za-z_$][A-Za-z0-9_.$]+)(?::|$)', body)
        signal = re.search(r'Sending signal\. PID: (\d+) SIG: (\d+)', body)
        if fatal and tag == 'libc':
            event.update(kind='native-fatal-signal', signal=int(fatal[1] or fatal[2]))
        elif 'FATAL EXCEPTION:' in body and tag == 'AndroidRuntime':
            event.update(kind='uncaught-exception')
        elif frame and parsed[4].strip() == 'AndroidRuntime':
            event.update(kind='java-code-frame', member=frame[1], source=frame[2], line=int(frame[3]) if frame[3] else None)
        elif exception and '.' in exception[1] and parsed[4].strip() == 'AndroidRuntime':
            event.update(kind='exception-type', type=exception[1])
        elif signal and tag == 'Process' and int(signal[1]) in pids:
            event.update(kind='self-signal', signal=int(signal[2]))
        elif native_frame and int(parsed[2]) in crash_helpers:
            event.update(kind='native-code-frame', index=int(native_frame[1]), pcOffset=native_frame[2], module=Path(native_frame[3]).name)
        else:
            continue
        report['events'].append(event)


def collect():
    try:
        collect_lines()
        stream['earlyEof'] = not stopping.is_set()
    except Exception as error:
        stream['errorType'] = type(error).__name__


reader = threading.Thread(target=collect, daemon=True)
reader.start()
started = time.monotonic()
complete = False
try:
    launch = run('shell', 'am', 'start', '-W', '--user', '0', '-a', entry['action'], '-c', categories[0], '-n', component)
    report['launch'] = {key: value.strip() for key, value in re.findall(r'^(Status|LaunchState|Activity|TotalTime|WaitTime):\s*(.*)$', launch, re.M)}
    report['launchConfirmed'] = report['launch'].get('Status') == 'ok'
    for milestone in (1, 5, 10, args.seconds):
        remaining = milestone - (time.monotonic() - started)
        if remaining > 0:
            time.sleep(remaining)
        result = subprocess.run([*adb, 'shell', 'pidof', package], capture_output=True, text=True)
        current = [int(value) for value in result.stdout.split() if value.isdigit()]
        pids.update(current)
        report['samples'].append({'elapsedMs': round((time.monotonic() - started) * 1000), 'mainPids': current})
    report['uiStateCollected'] = False
    complete = True
except Exception as error:
    report['probeErrorType'] = type(error).__name__
finally:
    stopping.set()
    logger.terminate()
    try:
        logger.wait(timeout=5)
    except subprocess.TimeoutExpired:
        logger.kill()
        logger.wait(timeout=5)
    reader.join(timeout=2)
    report['observedPids'] = sorted(pids)
    report['windowMs'] = round((time.monotonic() - started) * 1000)
    report['captureComplete'] = complete and not stream['earlyEof'] and stream['errorType'] is None and not reader.is_alive()
    report['stream'] = stream
    report['status'] = 'INCOMPLETE_CAPTURE' if not report['captureComplete'] else 'EXIT_OR_FATAL_OBSERVED' if any(e['kind'] in ('native-fatal-signal', 'uncaught-exception', 'self-signal') for e in report['events']) else 'INCOMPLETE_LAUNCH' if not report.get('launchConfirmed') else 'NO_CODE_FAILURE_OBSERVED_PROCESS_ALIVE' if report['samples'] and report['samples'][-1]['mainPids'] else 'INCOMPLETE_NO_LIVE_MAIN_PROCESS'
    (out / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
print(json.dumps({'package': package, 'status': report['status'], 'events': len(report['events']), 'result': str(out / 'result.json')}))
if report['status'].startswith('INCOMPLETE'):
    raise SystemExit(1)
