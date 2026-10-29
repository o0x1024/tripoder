#!/usr/bin/env python3
import json
import re
import subprocess
import time
from pathlib import Path

import uuid

SERIAL = "d861a4fa"
RECEIPT_PATH = Path("artifacts/qa/q6-receipt.json")
INVENTORY_PATH = Path("artifacts/qa/batch-representative-inventory.json")
OUTPUT_BASE = Path("docs/evidence/batch-thirdparty-2026-10-28")
OUTPUT_BASE.mkdir(parents=True, exist_ok=True)

receipt = json.loads(RECEIPT_PATH.read_text())
inventory = json.loads(INVENTORY_PATH.read_text())
host = receipt['artifacts']['engine']['package']
tests = receipt['artifacts']['controlTest']['package']

def adb(*argv, check=True, timeout=60):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

import xml.etree.ElementTree as ET

def attempt_reconnect(package):
    window = '/data/local/tmp/tripoder-batch-reconnect.xml'
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', window, check=False, timeout=15)
        snapshot = adb('shell', 'cat', window, check=False)
        if snapshot.returncode: return False
        try:
            tree = ET.fromstring(snapshot.stdout)
            owned = [n for n in tree.iter('node') if n.get('package') == package]
            button = next((n for n in owned if n.get('text') == '重新连接' and n.get('clickable') == 'true'), None)
            if any('SUPERVISOR_INTERRUPTED' in n.get('text', '') for n in owned) and button is not None:
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    adb('shell', 'input', '-d', '0', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return True
        except Exception:
            pass
        time.sleep(0.2)
    return False

def ensure_workbench():
    adb('shell', 'am', 'start', '-W', '-n', host + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
    attempt_reconnect(host)
    time.sleep(0.5)

results = []

for app in inventory['applications']:
    pkg = app['package']
    part = app['parts'][0]
    out_dir = OUTPUT_BASE / pkg
    out_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"\n=================== Testing {pkg} ===================", flush=True)
    ensure_workbench()
    
    run_id = f"control-{uuid.uuid4().hex[:16]}"
    remote = f"/sdcard/Android/data/{host}/files/"
    
    # Run instrumentation with 10s stability observation
    cmd = [
        'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'runId', run_id,
        '-e', 'retryEngineOnce', 'true',
        '-e', 'customPackage', pkg,
        '-e', 'customSha256', part['sha256'],
        '-e', 'customSource', part['path'],
        '-e', 'stabilitySeconds', '10',
        '-e', 'capture', 'false',
        '-e', 'stopAfterTest', 'true',
        f"{tests}/dev.tripoder.controltest.TargetLaunchIntegration"
    ]
    
    since = adb('shell', "date +'%m-%d %H:%M:%S.000'").stdout.strip()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    
    # Wait and launch workbench when requested
    workbench_launched = False
    deadline = time.monotonic() + 90
    while proc.poll() is None:
        if time.monotonic() > deadline:
            proc.terminate()
            break
        if not workbench_launched:
            sig = adb('shell', 'cat', f"{remote}launch-workbench-{run_id}.json", check=False)
            if sig.returncode == 0:
                adb('shell', 'am', 'start', '-W', '-n', host + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
                attempt_reconnect(host)
                workbench_launched = True
        time.sleep(0.3)
        
    stdout, _ = proc.communicate()
    (out_dir / "instrumentation.txt").write_text(stdout)
    
    raw = adb('shell', 'cat', f"{remote}result-{run_id}.json", check=False)
    app_result = {
        'package': pkg,
        'passed': False,
        'error': None,
        'failureCause': None,
        'checks': []
    }
    
    if raw.returncode == 0:
        (out_dir / "guest-result.json").write_text(raw.stdout)
        try:
            res_obj = json.loads(raw.stdout)
            app_result['passed'] = res_obj.get('passed', False)
            app_result['error'] = res_obj.get('error')
            app_result['checks'] = [
                {'name': c['name'], 'passed': c['passed'], 'observed': str(c.get('observed'))[:120]}
                for c in res_obj.get('checks', [])
            ]
        except Exception as e:
            app_result['error'] = f"JSON_PARSE_ERROR: {e}"
    else:
        app_result['error'] = "NO_REMOTE_RESULT"
        
    # If failed, collect logcat evidence for root cause analysis
    if not app_result['passed']:
        logs = adb('shell', 'logcat', '-d', '-t', '300', check=False).stdout
        (out_dir / "failure-logcat.txt").write_text(logs)
        
        # Classify failure cause
        if "Fatal signal 11" in logs or "SIGSEGV" in logs:
            app_result['failureCause'] = "NATIVE_CRASH_SIGSEGV"
        elif "StackOverflowError" in (app_result.get('error') or '') or "StackOverflowError" in logs:
            app_result['failureCause'] = "STACK_OVERFLOW_RECURSION"
        elif "System.exit" in logs or "reason: 1" in str(app_result.get('error') or ''):
            app_result['failureCause'] = "APP_INTERNAL_SAFETY_EXIT"
        elif "DeadObjectException" in (app_result.get('error') or ''):
            app_result['failureCause'] = "DEAD_OBJECT_UNEXPECTED_EXIT"
        elif "target-original-worker-remains-running" in (app_result.get('error') or ''):
            app_result['failureCause'] = "EARLY_WORKER_TERMINATION"
        else:
            app_result['failureCause'] = "RUNTIME_CHECK_FAILED"
            
    print(f"Result for {pkg}: PASSED={app_result['passed']}, Cause={app_result.get('failureCause')}", flush=True)
    results.append(app_result)
    (OUTPUT_BASE / "batch-results.json").write_text(json.dumps(results, indent=2))
    
    # Clean up any leftover guest process before next app
    adb('shell', 'am', 'force-stop', pkg, check=False)
    time.sleep(1)

print("\nBATCH TEST COMPLETE. Results saved to batch-results.json", flush=True)
