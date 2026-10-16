#!/usr/bin/env python3
"""Collect current-build real scheduler evidence from the dedicated owned acceptance UID."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    build = repo / 'android/job-scheduling-probe/build'
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    receipt = json.loads((build / 'build-receipt.json').read_text())
    for name, digest in receipt['sourceSha256'].items():
        if hashlib.sha256((repo / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:' + name)
    apk = build / 'jobs-probe.apk'
    if hashlib.sha256(apk.read_bytes()).hexdigest() != receipt['hostApkSha256']:
        raise RuntimeError('ARTIFACT_CHANGED_SINCE_BUILD')
    package = 'dev.tripoder.jobsprobe'
    run = 'jobs-' + uuid.uuid4().hex[:16]
    commands = []
    observation = {'runId': run, 'package': package, 'rootUsed': False, 'dataCleared': False,
                   'commands': commands, 'passed': False}

    def adb(*command, check=True):
        result = subprocess.run(['adb', '-s', args.serial, *command], capture_output=True, text=True, timeout=90)
        if command[:2] != ('shell', 'cat'):
            commands.append({'command': list(command), 'exitCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        if check and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result

    uid = start = None
    result = None
    try:
        if args.install:
            adb('shell', 'am', 'force-stop', package)
            adb('install', '--no-incremental', '-r', str(apk))
        raw = adb('shell', 'cmd', 'package', 'list', 'packages', '-U', package).stdout
        found = re.fullmatch(r'package:dev\.tripoder\.jobsprobe uid:(\d+)\s*', raw)
        if not found:
            raise RuntimeError('OWNED_UID_UNCONFIRMED')
        uid = int(found[1])
        observation['ownedUid'] = uid
        adb('shell', 'am', 'force-stop', package)
        start = adb('shell', 'date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
        if not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}', start):
            raise RuntimeError('DEVICE_CLOCK_UNCONFIRMED')
        adb('shell', 'am', 'start', '-W', '-n', package + '/dev.tripoder.runtimeprobe.JobsProbeActivity', '--es', 'runId', run)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            fetched = adb('shell', 'cat', f'/sdcard/Android/data/{package}/files/result-{run}.json', check=False)
            if fetched.returncode == 0:
                result = json.loads(fetched.stdout)
                (evidence / 'device-result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
                break
            if 'No such file or directory' not in fetched.stderr:
                raise RuntimeError('REPORT_UNREADABLE:' + fetched.stderr)
            time.sleep(1)
        if result is None:
            raise RuntimeError('TERMINAL_RESULT_UNKNOWN')
        if not (result.get('passed') is True and result.get('runId') == run and result.get('hostApkSha256') == receipt['hostApkSha256']
                and result.get('hostUid') == uid and result.get('fixture') == receipt['fixture'] and result.get('checks')
                and all(c.get('passed') is True for c in result['checks']) and result.get('systemJobsSubmitted', 0) > 0
                and result.get('guestLifecycleVerified') is False):
            raise RuntimeError('DEVICE_ASSERTIONS_OR_BUILD_IDENTITY_FAILED')
        observation['passed'] = True
    except Exception as error:
        observation['error'] = str(error)
        raise
    finally:
        try:
            if uid is not None and start is not None:
                crash = adb('logcat', '-b', 'crash', '-d', '--uid=' + str(uid), '-T', start).stdout
                (evidence / 'owned-crash.txt').write_text(crash)
                observation['unexpectedFatalObserved'] = bool(re.search(r'FATAL EXCEPTION|Fatal signal \d+', crash))
                if observation['unexpectedFatalObserved']:
                    observation['passed'] = False
                    observation['error'] = 'UNEXPECTED_OWNED_CRASH'
        finally:
            shutil.copyfile(build / 'build-receipt.json', evidence / 'build-receipt.json')
            shutil.copyfile(apk, evidence / 'jobs-probe.apk')
            (evidence / 'device-observation.json').write_text(json.dumps(observation, indent=2, ensure_ascii=False))
    if not observation['passed']:
        raise RuntimeError(observation['error'])
    print(json.dumps({'passed': True, 'checks': len(result['checks']), 'runId': run, 'hostApkSha256': receipt['hostApkSha256'], 'evidence': str(evidence)}))


if __name__ == '__main__':
    main()
