#!/usr/bin/env python3
import json
import re
import subprocess
import time
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
RECEIPT_PATH = Path("artifacts/qa/q6-receipt.json")
INVENTORY_PATH = Path("artifacts/qa/batch-representative-inventory.json")
OUTPUT_BASE = Path("docs/evidence/batch-thirdparty-2026-10-28")

receipt = json.loads(RECEIPT_PATH.read_text())
inventory = json.loads(INVENTORY_PATH.read_text())
host = receipt['artifacts']['engine']['package']
tests = receipt['artifacts']['controlTest']['package']

def adb(*argv, check=True, timeout=60):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

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
                    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return True
        except Exception:
            pass
        time.sleep(0.2)
    return False

def ensure_workbench():
    adb('shell', 'am', 'start', '-W', '-n', host + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
    attempt_reconnect(host)
    time.sleep(0.5)

selected_apps = ['com.sina.weibo', 'com.deepseek.chat']
apps_to_test = [a for a in inventory['applications'] if a['package'] in selected_apps]

results = []

for app in apps_to_test:
    pkg = app['package']
    part = app['parts'][0]
    out_dir = OUTPUT_BASE / pkg
    out_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"\n=================== Testing {pkg} ===================", flush=True)
    ensure_workbench()
    
    run_id = f"control-{uuid.uuid4().hex[:16]}"
    remote = f"/sdcard/Android/data/{host}/files/"
    
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
        'checks': []
    }
    
    if raw.returncode == 0:
        (out_dir / "guest-result.json").write_text(raw.stdout)
        try:
            res_obj = json.loads(raw.stdout)
            app_result['passed'] = res_obj.get('passed', False)
            app_result['error'] = res_obj.get('error')
            app_result['checks'] = res_obj.get('checks', [])
        except Exception as e:
            app_result['error'] = str(e)
            
    if not app_result['passed']:
        logcat_res = adb('shell', f"logcat -d -t 200", check=False)
        (out_dir / "failure-logcat.txt").write_text(logcat_res.stdout)
        print(f"FAILED: {pkg} -> {app_result['error']}")
    else:
        print(f"PASSED: {pkg} -> All checks passed!")
        
    results.append(app_result)
    time.sleep(1)

print("\n\nFINAL RESULT:")
for r in results:
    print(f"  {r['package']}: {'PASS' if r['passed'] else 'FAIL'}")
