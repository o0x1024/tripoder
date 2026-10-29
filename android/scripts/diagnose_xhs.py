#!/usr/bin/env python3
import json
import re
import subprocess
import time
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
HOST = "dev.tripoder.q6"
TESTS = "dev.tripoder.controltest"
PKG = "com.xingin.xhs"
SHA256 = "8ddf9ae4e4cdbc70e051e2c02c55ea18ba0a02c3944a5a4e48d38ece5c92a13c"
PATH = "/data/app/~~9L_UKoVGTcGUJi-Fo13Yzg==/com.xingin.xhs-h6TN0xhraU1JdsktKNvLDA==/base.apk"

def adb(*argv, check=True, timeout=60):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

def attempt_reconnect(package):
    window = '/data/local/tmp/tripoder-batch-reconnect.xml'
    for _ in range(5):
        adb('shell', 'uiautomator', 'dump', window, check=False, timeout=15)
        snapshot = adb('shell', 'cat', window, check=False)
        if snapshot.returncode: 
            time.sleep(0.5)
            continue
        try:
            tree = ET.fromstring(snapshot.stdout)
            owned = [n for n in tree.iter('node') if n.get('package') == package]
            button = next((n for n in owned if n.get('text') == '重新连接' and n.get('clickable') == 'true'), None)
            if any('SUPERVISOR_INTERRUPTED' in n.get('text', '') for n in owned) and button is not None:
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(1)
                    return True
        except Exception:
            pass
        time.sleep(0.3)
    return False

def ensure_workbench():
    adb('shell', 'wm', 'dismiss-keyguard', check=False)
    adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
    attempt_reconnect(HOST)
    time.sleep(0.5)

def main():
    print(f"=== Testing {PKG} ===")
    ensure_workbench()
    
    run_id = f"control-{uuid.uuid4().hex[:16]}"
    remote = f"/sdcard/Android/data/{HOST}/files/"
    
    adb('shell', 'logcat', '-c', check=False)
    
    # 25 seconds stability observation to observe post-consent behavior
    cmd = [
        'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'runId', run_id,
        '-e', 'retryEngineOnce', 'true',
        '-e', 'customPackage', PKG,
        '-e', 'customSha256', SHA256,
        '-e', 'customSource', PATH,
        '-e', 'stabilitySeconds', '25',
        '-e', 'capture', 'false',
        '-e', 'stopAfterTest', 'false',
        f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
    ]
    
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
                print("Launch signal detected, launching workbench...")
                adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
                attempt_reconnect(HOST)
                workbench_launched = True
        time.sleep(0.3)
        
    stdout, _ = proc.communicate()
    print("STDOUT:\n", stdout)
    
    raw = adb('shell', 'cat', f"{remote}result-{run_id}.json", check=False)
    if raw.returncode == 0:
        print("RESULT:\n", raw.stdout)
    else:
        print("NO RESULT FILE FOUND")

if __name__ == '__main__':
    main()
