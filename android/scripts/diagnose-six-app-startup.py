#!/usr/bin/env python3
"""One pinned native/guest startup per app; code evidence only, never a business Smoke Gate."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid


SYSTEM_ERR_FILTER_VERSION = 'system-err-code-v3'
SYSTEM_ERR_HEADER = re.compile(
    r'^\s*(\d{10}\.\d{3,9})\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+System\.err\s*:\s?(.*)$')
EXCEPTION_HEADER = re.compile(
    r'^(?:(?:Caused by|Suppressed):\s+)?'
    r'((?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*)(?=[:\s]|$)')
CODE_FRAME = re.compile(
    r'^\s*at\s+((?:[A-Za-z_$][A-Za-z0-9_$]*\.)+'
    r'[A-Za-z_$<>][A-Za-z0-9_$<>]*)\('
    r'((?:[A-Za-z_$][A-Za-z0-9_.$-]*\.(?:java|kt|kts|scala|groovy)|SourceFile)'
    r'(?::\d+)?|Unknown Source|Native Method)\)\s*$')


# Only cmb.pb is covered: its libCmbShield formats ApplicationInfo.dataDir into
# a 127-byte buffer. The FORTIFY abort was observed at 117 bytes; the static
# analysis puts the failure threshold at 110 bytes, so 109 is the largest safe
# length. Other applications' buffers are unmeasured and are not refused here.
DATA_DIR_MAX_BYTES = 109
DATA_ID_BYTES = 36
TARGET_LAUNCH_SOURCE = Path(__file__).resolve().parents[1] / 'control-test/src/dev/tripoder/controltest/TargetLaunchIntegration.java'


def guest_data_dir_bytes(host, space, package):
    """Bytes of the canonical guest data path, as built by RuntimeStorage."""
    path = '/data/user/0/' + host + '/files/runtime/data/' + space + '/' + package + '/' + 'x' * DATA_ID_BYTES
    return len(path.encode('utf-8'))


def pinned_space():
    match = re.search(r'SPACE="([^"]+)"', TARGET_LAUNCH_SOURCE.read_text())
    if not match:
        raise ValueError('TARGET_LAUNCH_SPACE_UNCONFIRMED')
    return match[1]


def system_err_code(raw, pid, start_ms, end_ms):
    """Parse UID-filtered epoch logcat in memory; never return message text."""
    entries = []
    runs = []
    previous = None
    active_run = None
    runs_truncated = False
    recognized = 0
    matching_lines = 0
    for line in raw.splitlines():
        header = SYSTEM_ERR_HEADER.fullmatch(line)
        if not header or int(header[2]) != pid:
            continue
        exact_epoch_ms = Decimal(header[1]) * 1000
        if not start_ms <= exact_epoch_ms <= end_ms:
            continue
        epoch_ms = int(exact_epoch_ms)
        matching_lines += 1
        body = header[4]
        exception = EXCEPTION_HEADER.match(body)
        frame = CODE_FRAME.fullmatch(body)
        value = None
        if exception and exception[1].endswith(('Exception', 'Error', 'Throwable')):
            value = {'exceptionType': exception[1]}
        elif frame:
            value = {'codeFrame': frame[1] + '(' + frame[2] + ')'}
        if value is not None:
            recognized += 1
            if len(entries) < 256:
                entries.append({'epochMs': epoch_ms, 'pid': pid, 'tid': int(header[3]), **value})
            key = (int(header[3]), tuple(value.items()))
            if key == previous and active_run is not None:
                active_run['repetitions'] += 1
                active_run['lastEpochMs'] = epoch_ms
            elif len(runs) < 128:
                active_run = {'firstEpochMs': epoch_ms, 'lastEpochMs': epoch_ms,
                              'pid': pid, 'tid': int(header[3]), 'repetitions': 1, **value}
                runs.append(active_run)
            else:
                active_run = None
                runs_truncated = True
            previous = key
    return {'entries': entries, 'matchingLines': matching_lines,
            'entryLimit': 256, 'entryLimitReached': len(entries) == 256,
            'recognizedCodeEntries': recognized, 'codeRuns': runs,
            'codeRunLimit': 128, 'codeRunsTruncated': runs_truncated}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--package', action='append', dest='selected')
    parser.add_argument('--guest-only', action='store_true', help='Explicitly omit new native controls when checking a changed QA build; prior controls remain separate evidence')
    parser.add_argument('--cmb-caught-exceptions', action='store_true',
                        help='For --package cmb.pb only: retain UID/PID/time-bounded System.err exception types and code frames; no raw messages')
    args = parser.parse_args()
    if args.cmb_caught_exceptions and args.selected != ['cmb.pb']:
        parser.error('--cmb-caught-exceptions requires exactly one --package cmb.pb')
    receipt = json.loads(args.receipt.read_text())
    inventory = json.loads(args.inventory.read_text())
    host = receipt['artifacts']['engine']['package']
    tests = receipt['artifacts']['controlTest']['package']
    if host == 'dev.tripoder.engine':
        raise ValueError('SEPARATE_QA_PACKAGE_REQUIRED')
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*argv, check=True, timeout=90):
        result = subprocess.run(['adb', '-s', args.serial, *argv], capture_output=True,
                                text=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError('ADB_COMMAND_FAILED:' + result.stderr.strip())
        return result

    def shell(*argv, **kwargs):
        return adb('shell', shlex.join(argv), **kwargs).stdout.strip()

    for kind in ('engine', 'controlTest'):
        item = receipt['artifacts'][kind]
        paths = shell('pm', 'path', item['package']).splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:'):
            raise RuntimeError('INSTALLED_QA_APK_SET_CHANGED')
        if shell('sha256sum', paths[0].removeprefix('package:')).split()[0] != item['sha256']:
            raise RuntimeError('INSTALLED_QA_APK_CHANGED')
    # pm lists by prefix, so a host name that prefixes its test package returns
    # both rows; select the exact host row only.
    uid_rows = [re.fullmatch(re.escape('package:' + host) + r' uid:(\d+)', row)
                for row in shell('pm', 'list', 'packages', '-U', host).splitlines()]
    matches = [match for match in uid_rows if match]
    if len(matches) != 1:
        raise RuntimeError('QA_UID_UNCONFIRMED')
    uid = matches[0][1]
    summary = {'schema': 1, 'scope': 'ONE_STARTUP_DIAGNOSTIC_PER_APP', 'package': host,
               'engineSha256': receipt['artifacts']['engine']['sha256'],
               'coreSha256': receipt['selfHookNative']['sha256'],
               'dataCleared': False, 'unknownOperationsReplayed': False,
               'accountActionsPerformed': False, 'uiContentsCaptured': False,
               'initialDataEquivalent': None, 'smokeGateAccepted': False, 'applications': []}
    summary['runnerSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    summary['nativeControlsPerformedThisRun']=not args.guest_only
    if args.cmb_caught_exceptions:
        summary['caughtExceptionCodeFilterVersion'] = SYSTEM_ERR_FILTER_VERSION

    def save():
        (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')

    def code_evidence(app_uid, since):
        raw = adb('logcat', '-d', '-v', 'brief', '--uid=' + str(app_uid), '-T', since,
                  'AndroidRuntime:E', 'libc:F', 'DEBUG:F', 'TripoderBoot:I', 'TripoderSelfHook:I', 'TripoderRuntime:E', 'TripoderStartup:E', '*:S').stdout
        # Keep code identities and measured counters; discard arbitrary app log
        # messages, UI, URLs, request bodies and exception payload strings.
        return {
            'uncaughtException': 'FATAL EXCEPTION:' in raw,
            'exceptionTypes': sorted(set(re.findall(r'\b((?:java|android|dalvik)\.[A-Za-z0-9_.$]+(?:Exception|Error))\b', raw))),
            'javaFrames': list(dict.fromkeys(re.findall(r'\bat ([A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$<> :\-]+\))', raw)))[:100],
            'nativeFatalSignals': sorted(set(re.findall(r'Fatal signal (\d+)', raw))),
            'nativeLibrariesInFailures': sorted(set(re.findall(r'\b(lib[A-Za-z0-9_.+\-]+\.so)\b', '\n'.join(line for line in raw.splitlines() if 'Error:' in line or 'dlopen failed:' in line)))),
            'boot': re.findall(r'I/TripoderBoot\s*\(\s*(\d+)\): ([A-Za-z]+) \+(\d+)ms', raw),
            'fixedPublication': re.findall(r'FIXED_PUBLICATION:bindings=(\d+):ns=(\d+)', raw),
            'skeletonInstall': re.findall(r'READY:READY:[^\n]*:bindings=(\d+):ns=(\d+)', raw),
            'publicationMetrics': [json.loads(value) for value in re.findall(r'PUBLICATION_METRICS:(\{[^\n]+\})', raw)],
        }

    def caught_exception_evidence(directory, result, authenticated, since):
        # The existing terminal receipt owns this run's worker identity, even
        # when its test failed. Never widen to all QA processes without it.
        evidence = {
            'schema': 1, 'filterVersion': SYSTEM_ERR_FILTER_VERSION,
            'runId': result.get('runId') if result else None,
            'rawStored': False, 'messagesStored': False, 'uiCaptured': False,
            'rules': ['logcat --uid equals the verified QA installation UID',
                      'host parser requires exact authenticated terminal workerPid',
                      'epoch is inside the authenticated run start/finish window',
                      'System.err exception class names and basename-only source frames',
                      'discard exception messages, arbitrary lines, paths and other tags'],
            'state': 'UNAVAILABLE_WITHOUT_AUTHENTICATED_WORKER_IDENTITY',
            'entries': [],
        }
        if (authenticated and type(result.get('workerPid')) is int and result['workerPid'] > 0
                and result.get('workerUid') == int(uid)
                and type(result.get('startedAt')) is int
                and type(result.get('finishedAt')) is int
                and result['startedAt'] <= result['finishedAt']):
            start_ms, end_ms = result['startedAt'], result['finishedAt']
            evidence.update(uid=int(uid), pid=result['workerPid'],
                            workerIdentitySource=result.get('workerIdentitySource', 'LIVE_WORKER_ACK'),
                            workerIdentityIsHistorical=result.get('workerIdentityIsHistorical', False),
                            processGeneration=result.get('processGeneration'),
                            terminalPassed=result.get('passed'),
                            startEpochMs=start_ms, endEpochMs=end_ms,
                            windowStartBasis='RUN_START_BEFORE_GUEST_ATTACH',
                            windowEndBasis='TERMINAL_AFTER_STABILITY_OR_FAILURE',
                            exactActivityAttachEpochKnown=False)
            # logcat filters the verified UID. The host also filters the PID and
            # both timestamps, rather than trusting a tag or a process name.
            logs = adb('logcat', '-d', '-v', 'epoch', '--uid=' + str(uid), '-T', since,
                       'System.err:W', '*:S', check=False)
            if logs.returncode:
                evidence['state'] = 'LOGCAT_COMMAND_FAILED_NO_RAW_OUTPUT'
                evidence['exitCode'] = logs.returncode
            else:
                evidence.update(system_err_code(logs.stdout, result['workerPid'], start_ms, end_ms))
                evidence['state'] = 'SANITIZED_CODE_EVIDENCE_COLLECTED'
        destination = directory / 'caught-exception-code.json'
        destination.write_text(json.dumps(evidence, indent=2) + '\n')
        return {'file': destination.name, 'filterVersion': SYSTEM_ERR_FILTER_VERSION,
                'state': evidence['state'], 'entries': len(evidence['entries'])}

    applications = inventory['applications']
    if args.selected and not set(args.selected).issubset({app['package'] for app in applications}):
        raise ValueError('PACKAGE_NOT_IN_PINNED_INVENTORY')
    space = pinned_space()
    for app in applications:
        if args.selected and app['package'] not in args.selected:
            continue
        if app['package'] == 'cmb.pb' and guest_data_dir_bytes(host, space, app['package']) > DATA_DIR_MAX_BYTES:
            raise ValueError('QA_DATA_DIR_TOO_LONG:' + app['package'])
    for app in applications:
        package = app['package']
        if args.selected and package not in args.selected:
            continue
        # This existing controller admits a single base APK only. Do not omit
        # splits or silently transform an unsupported original installation.
        if len(app['parts']) != 1 or app['parts'][0]['name'] != 'base.apk' or len(app['launcherComponents']) != 1:
            raise RuntimeError('EXACT_SINGLE_BASE_AND_LAUNCHER_REQUIRED:' + package)
        part = app['parts'][0]
        if shell('sha256sum', part['path']).split()[0] != part['sha256']:
            raise RuntimeError('ORIGINAL_APK_CHANGED:' + package)
        directory = args.output / package
        directory.mkdir()
        item = {'package': package, 'sha256': part['sha256'], 'businessAccepted': None}
        summary['applications'].append(item)
        save()
        if not args.guest_only:
            since = shell('date', '+%m-%d %H:%M:%S.000')
            shell('am', 'force-stop', '--user', '0', package)
            started = time.monotonic()
            launch = shell('am', 'start', '-W', '--user', '0', '-a', 'android.intent.action.MAIN',
                           '-c', 'android.intent.category.LAUNCHER', '-n', app['launcherComponents'][0])
            native = {'launch': dict(re.findall(r'^(Status|LaunchState|Activity|TotalTime|WaitTime):\s*(.*)$', launch, re.M)),
                      'initialData': 'EXISTING_NATIVE_DATA_PRESERVED', 'samples': [], 'pidAttribution': 'EXACT_NATIVE_UID_AND_NAME'}
            for milestone in (1, 5, 10, 20):
                delay = milestone - (time.monotonic() - started)
                if delay > 0:
                    time.sleep(delay)
                processes = [line.split(maxsplit=2) for line in shell('ps', '-A', '-n', '-o', 'PID,UID,NAME').splitlines()[1:]]
                pids = [row[0] for row in processes if len(row)==3 and row[1]==str(app['uid']) and row[2]==package]
                native['samples'].append({'elapsedMs': round((time.monotonic() - started) * 1000), 'mainPids': pids})
            native['code'] = code_evidence(app['uid'], since)
            item['native'] = native
            (directory / 'native.json').write_text(json.dumps(native, indent=2) + '\n')
            save()
            print(json.dumps({'package': package, 'stage': 'native-diagnostic-collected'}), flush=True)
        run = 'control-' + uuid.uuid4().hex[:16]
        remote = '/sdcard/Android/data/' + host + '/files/'
        command = ['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                   '-e', 'runId', run, '-e', 'retryEngineOnce', 'true',
                   '-e', 'customPackage', package, '-e', 'customSha256', part['sha256'],
                   '-e', 'customSource', part['path'], '-e', 'stabilitySeconds', '30',
                   '-e', 'capture', 'false', '-e', 'stopAfterTest', 'true',
                   tests + '/dev.tripoder.controltest.TargetLaunchIntegration']
        item['guest'] = {'runId': run, 'state': 'REQUESTED', 'operationReplayed': False}
        (directory / 'guest-run.json').write_text(json.dumps({'command': command, 'remoteResult': remote + 'result-' + run + '.json'}, indent=2) + '\n')
        save()
        since = shell('date', '+%m-%d %H:%M:%S.000')
        with (directory / 'guest-instrumentation.txt').open('w') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, text=True)
            launched = False
            deadline = time.monotonic() + 180
            while process.poll() is None:
                if time.monotonic() > deadline:
                    process.terminate()
                    item['guest']['state'] = 'UNKNOWN_INSTRUMENTATION_TIMEOUT'
                    if args.cmb_caught_exceptions:
                        item['guest']['caughtExceptionCode'] = caught_exception_evidence(directory, None, False, since)
                    save()
                    raise RuntimeError('NO_AUTOMATIC_REPLAY_AFTER_TIMEOUT:' + run)
                if not launched:
                    signal = adb('shell', 'cat', remote + 'launch-workbench-' + run + '.json', check=False)
                    if signal.returncode == 0:
                        expected = {'runId': run, 'component': host + '/dev.tripoder.hooklab.WorkbenchActivity'}
                        if json.loads(signal.stdout) != expected:
                            raise RuntimeError('WORKBENCH_SIGNAL_MISMATCH')
                        shell('am', 'start', '-W', '-n', expected['component'])
                        launched = True
                time.sleep(.15)
        raw = adb('shell', 'cat', remote + 'result-' + run + '.json', check=False)
        item['guest']['code'] = code_evidence(uid, since)
        if raw.returncode:
            item['guest']['state'] = 'UNKNOWN_NO_TERMINAL_RESULT'
            if args.cmb_caught_exceptions:
                item['guest']['caughtExceptionCode'] = caught_exception_evidence(directory, None, False, since)
            save()
            raise RuntimeError('NO_AUTOMATIC_REPLAY_WITHOUT_RESULT:' + run)
        (directory / 'guest-result.json').write_text(raw.stdout)
        result = json.loads(raw.stdout)
        digests = re.findall(r'resultSha256=([a-f0-9]{64})', (directory / 'guest-instrumentation.txt').read_text())
        authenticated = digests == [hashlib.sha256(raw.stdout.encode()).hexdigest()] and result['runId'] == run and result['uid'] == int(uid)
        if args.cmb_caught_exceptions:
            item['guest']['caughtExceptionCode'] = caught_exception_evidence(directory, result, authenticated, since)
        item['guest'].update(state='RESULT_RECEIVED' if authenticated else 'RESULT_WITHOUT_VALID_TERMINAL_RECEIPT',
                             passed=result.get('passed') if authenticated else None,
                             checks=len(result['checks']), receiptAuthenticated=authenticated)
        if 'runtimeStatus' in result:
            item['guest']['managedPublication']=result['runtimeStatus']['managedPublication']
            item['guest']['managerFixedBindings']=result['runtimeStatus']['executionPolicy']['managerFixedBindings']
        save()
        print(json.dumps({'package': package, 'stage': 'guest-diagnostic-collected',
                          'authenticated': authenticated, 'passed': item['guest']['passed']}), flush=True)
        if not authenticated:
            raise RuntimeError('INVALID_TERMINAL_RECEIPT_NO_REPLAY:' + run)
    summary['state'] = 'DIAGNOSTICS_COLLECTED_NOT_SMOKE_ACCEPTANCE'
    save()


if __name__ == '__main__':
    main()
