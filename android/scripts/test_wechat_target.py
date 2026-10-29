#!/usr/bin/env python3
import json
import os
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
HOST = "dev.tripoder.q6"
TESTS = "dev.tripoder.controltest"
PKG = "com.tencent.mm"
SHA256 = "f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d"
APK_PATH = "/data/app/~~6QRuPXv_JpzXcVV-zfzZuw==/com.tencent.mm-tzNbk0MuvvhzZME8FjWZfg==/base.apk"

def adb(*argv, check=True, timeout=30):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

def attempt_reconnect(package):
    window = '/data/local/tmp/tripoder-batch-reconnect.xml'
    for _ in range(5):
        adb('shell', 'uiautomator', 'dump', window, check=False, timeout=10)
        snapshot = adb('shell', 'cat', window, check=False)
        if snapshot.returncode == 0:
            try:
                tree = ET.fromstring(snapshot.stdout)
                owned = [n for n in tree.iter('node') if n.get('package') == package]
                button = next((n for n in owned if n.get('text') == '重新连接' and n.get('clickable') == 'true'), None)
                if button is not None:
                    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
                    if bounds:
                        x1, y1, x2, y2 = map(int, bounds.groups())
                        print(f"Clicking 重新连接 at ({(x1+x2)//2}, {(y1+y2)//2})")
                        adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2), check=False)
                        return True
            except Exception:
                pass
        time.sleep(0.3)
    return False

def ensure_workbench():
    adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
    attempt_reconnect(HOST)
    time.sleep(0.5)

print("Preparing device...")
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
adb('shell', 'input', 'keyevent', '224', check=False)
adb('shell', 'wm', 'dismiss-keyguard', check=False)
time.sleep(1)

ensure_workbench()
adb('shell', 'logcat', '-c', check=False)

run_id = f"control-{uuid.uuid4().hex[:16]}"
remote = f"/sdcard/Android/data/{HOST}/files/"

cmd = [
    'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'runId', run_id,
    '-e', 'retryEngineOnce', 'true',
    '-e', 'customPackage', PKG,
    '-e', 'customSha256', SHA256,
    '-e', 'customSource', APK_PATH,
    '-e', 'stabilitySeconds', '30',
    '-e', 'capture', 'false',
    '-e', 'stopAfterTest', 'true',
    f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
]

print(f"Launching am instrument for {PKG} (runId: {run_id})...")
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
deadline = time.monotonic() + 120

while proc.poll() is None:
    if time.monotonic() > deadline:
        print("Timeout reached, terminating...")
        proc.terminate()
        break
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Launch signal found, ensuring Workbench...")
            adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
            attempt_reconnect(HOST)
            workbench_launched = True
    time.sleep(0.3)

stdout, _ = proc.communicate()
print("\n=== Instrumentation Output ===")
print(stdout)

result_file = f"{remote}result-{run_id}.json"
res = adb('shell', 'cat', result_file, check=False)
if res.returncode == 0:
    print("\n=== Result JSON ===")
    print(res.stdout)

# Also dump logcat
logcat_res = subprocess.run(['adb', '-s', SERIAL, 'logcat', '-d'], capture_output=True, text=True)
logcat_path = f"docs/evidence/diag-wechat-{run_id}-logcat.txt"
with open(logcat_path, 'w') as f:
    f.write(logcat_res.stdout)
print(f"Logcat saved to {logcat_path}")
