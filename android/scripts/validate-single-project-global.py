#!/usr/bin/env python3
"""Run focused single-project/global-control instrumentation against an explicit installed APK pair."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='dev.tripoder.engine')
    parser.add_argument('--test-package', default='dev.tripoder.controltest')
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--phase', choices=['migration', 'control', 'hook-ui', 'global-ui', 'library-ui', 'app-actions-ui', 'multi-add', 'publication', 'execution-code', 'runtime-policy', 'catalog-seed', 'catalog-cold', 'task-presentation', 'all'], default='all')
    parser.add_argument('--retry-engine-once', action='store_true', help='After instrumentation interruption, explicitly click the owned workbench reconnect action once')
    options = parser.parse_args()
    for value in (options.package, options.test_package):
        if not re.fullmatch(r'[A-Za-z0-9_.]+', value):
            parser.error('invalid package')
    options.evidence.mkdir(parents=True, exist_ok=True)
    adb_path = Path.home() / 'Library/Android/sdk/platform-tools/adb'
    adb = [str(adb_path) if adb_path.exists() else 'adb', '-s', options.serial]

    def call(*args, check=True):
        return subprocess.run([*adb, *args], capture_output=True, text=True, check=check, timeout=30)

    uid_output = call('shell', 'cmd', 'package', 'list', 'packages', '-U', options.package).stdout
    match = re.search(re.escape('package:' + options.package) + r' uid:(\d+)', uid_output)
    if not match:
        raise RuntimeError('TARGET_UID_NOT_FOUND')
    uid = int(match[1])
    started = call('shell', 'date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
    report = {'passed': False, 'package': options.package, 'uid': uid, 'phases': [], 'deviceLogStart': started}
    phases = {'migration': 'SingleProjectMigrationIntegration', 'global-ui': 'GlobalControlUiIntegration', 'library-ui': 'LibraryUiIntegration', 'app-actions-ui': 'AppActionsUiIntegration', 'multi-add': 'MultiAddUiIntegration', 'publication': 'ApplicationPublicationIntegration', 'execution-code': 'ExecutionCodeIntegration', 'runtime-policy': 'RuntimePolicyIntegration'}
    phases.update({'control': 'ControlIntegration', 'hook-ui': 'HookUiIntegration'})
    phases.update({'catalog-seed': 'CatalogReadIntegration', 'catalog-cold': 'CatalogReadIntegration'})
    phases['task-presentation']='TaskPresentationIntegration'
    selected = ['migration', 'global-ui'] if options.phase == 'all' else [options.phase]
    remote = '/sdcard/Android/data/' + options.package + '/files/'
    try:
        for phase in selected:
            run = 'control-' + uuid.uuid4().hex[:16]
            command = [*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'runId', run, '-e', 'phase', phase, options.test_package + '/dev.tripoder.controltest.' + phases[phase]]
            output_file = options.evidence / (phase + '-instrumentation.txt')
            with output_file.open('w') as output:
                process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, text=True)
                launched = False
                retried = False
                deadline = time.monotonic() + 180
                while process.poll() is None:
                    if time.monotonic() > deadline:
                        process.terminate()
                        raise RuntimeError('INSTRUMENTATION_TIMEOUT:' + phase)
                    if not launched:
                        signal = call('shell', 'cat', remote + 'launch-workbench-' + run + '.json', check=False)
                        if signal.returncode == 0:
                            value = json.loads(signal.stdout)
                            expected = options.package + '/dev.tripoder.hooklab.WorkbenchActivity'
                            if value != {'runId': run, 'component': expected}:
                                raise RuntimeError('WORKBENCH_SIGNAL_MISMATCH')
                            call('shell', 'am', 'start', '-W', '-n', expected)
                            launched = True
                    if launched and options.retry_engine_once and not retried:
                        window = remote + 'acceptance-window-' + run + '.xml'
                        call('shell', 'uiautomator', 'dump', window)
                        nodes = list(ET.fromstring(call('shell', 'cat', window).stdout).iter('node'))
                        owned = [node for node in nodes if node.get('package') == options.package]
                        reason = any('SUPERVISOR_INTERRUPTED' in node.get('text', '') for node in owned)
                        button = next((node for node in owned if node.get('text') == '重新连接' and node.get('clickable') == 'true'), None)
                        if reason and button is not None:
                            bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
                            if not bounds:
                                raise RuntimeError('RECONNECT_BOUNDS_INVALID')
                            x1, y1, x2, y2 = map(int, bounds.groups())
                            call('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                            report.setdefault('explicitEngineRetries', []).append({'phase': phase, 'reason': 'SUPERVISOR_INTERRUPTED', 'action': 'workbench-reconnect-once'})
                            retried = True
                    time.sleep(0.15)
            raw = call('shell', 'cat', remote + 'result-' + run + '.json').stdout
            value = json.loads(raw)
            (options.evidence / (phase + '-result.json')).write_text(raw)
            digest = re.search(r'resultSha256=([0-9a-f]{64})', output_file.read_text())
            if not digest or hashlib.sha256(raw.encode()).hexdigest() != digest[1]:
                raise RuntimeError('RESULT_DIGEST_MISMATCH:' + phase)
            if value.get('runId') != run or value.get('uid') != uid or not value.get('passed') or not all(item.get('passed') for item in value.get('checks', [])):
                raise RuntimeError('INSTRUMENTATION_FAILED:' + phase + ':' + str(value.get('error')))
            for capture in value.get('captures', []):
                if not re.fullmatch(re.escape(run) + r'-[a-z-]+\.png', capture):
                    raise RuntimeError('CAPTURE_NAME_INVALID')
                call('pull', remote + capture, str(options.evidence / capture))
            report['phases'].append({'phase': phase, 'runId': run, 'checks': len(value['checks']), 'passed': True})
            print(json.dumps(report['phases'][-1]), flush=True)
        report['passed'] = True
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        crash = call('logcat', '-b', 'crash', '-d', '--uid=' + str(uid), '-T', started, check=False)
        (options.evidence / 'owned-crash.txt').write_text(crash.stdout)
        report['nativeFatalSignalObserved'] = bool(re.search(r'Fatal signal \d+', crash.stdout))
        report['javaFatalExceptionObserved'] = 'FATAL EXCEPTION:' in crash.stdout
        if crash.returncode or report['nativeFatalSignalObserved'] or report['javaFatalExceptionObserved']:
            report['passed'] = False
        (options.evidence / 'device-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if not report['passed']:
        raise RuntimeError('DEVICE_ACCEPTANCE_FAILED')


if __name__ == '__main__':
    main()
