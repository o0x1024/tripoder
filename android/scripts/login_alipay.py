#!/usr/bin/env python3
import json
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
HOST = "dev.tripoder.q6"
TESTS = "dev.tripoder.controltest"
PKG = "com.eg.android.AlipayGphone"
SHA256 = "7274a03d16a223fb8d99a05e6e65071be1000e98586defb9b00a959b00e33122"
APK_PATH = "/data/app/~~d5BMNT7JL-DX12dALIi_fQ==/com.eg.android.AlipayGphone-9TADx4VeYD2oFIB71VBkIw==/base.apk"
PHONE_NUMBER = "15323738553"

def adb(*argv, check=True, timeout=30):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

print("Cleaning up previous state...")
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
adb('shell', 'am', 'force-stop', 'dev.tripoder.engine', check=False)
# adb('shell', 'pm', 'clear', HOST, check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.READ_PHONE_STATE', check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.READ_CONTACTS', check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.READ_SMS', check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.RECEIVE_SMS', check=False)
adb('shell', 'input', 'keyevent', '224', check=False)
adb('shell', 'wm', 'dismiss-keyguard', check=False)
time.sleep(1)

# 清除 logcat
adb('shell', 'logcat', '-c', check=False)

# 启动 Workbench
print("Starting WorkbenchActivity...")
adb('shell', 'am', 'start', '-W', '-n', f"{HOST}/dev.tripoder.hooklab.WorkbenchActivity", check=False)
time.sleep(1)

run_id = f"control-{uuid.uuid4().hex[:16]}"
remote = f"/sdcard/Android/data/{HOST}/files/"

cmd = [
    'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'runId', run_id,
    '-e', 'retryEngineOnce', 'true',
    '-e', 'customPackage', PKG,
    '-e', 'customSha256', SHA256,
    '-e', 'customSource', APK_PATH,
    '-e', 'stabilitySeconds', '1800',
    '-e', 'capture', 'false',
    '-e', 'stopAfterTest', 'false',
    f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
]

print(f"Launching Alipay via instrumentation (runId: {run_id})...")
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
deadline = time.monotonic() + 30
while proc.poll() is None:
    if time.monotonic() > deadline:
        break
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Received launch signal, starting WorkbenchActivity...")
            adb('shell', 'am', 'start', '-W', '-n', f"{HOST}/dev.tripoder.hooklab.WorkbenchActivity", check=False)
            workbench_launched = True
            break
    time.sleep(0.3)

time.sleep(2)
# 检查是否需要点击“重新连接”
for _ in range(5):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/chk.xml', check=False)
    xml_data = adb('shell', 'cat', '/data/local/tmp/chk.xml', check=False).stdout
    if '重新连接' in xml_data:
        print("Clicking reconnect...")
        adb('shell', 'input', '-d', '0', 'tap', '604', '906', check=False)
        time.sleep(2)
        break
    time.sleep(0.5)

print("Alipay launched in sandbox. Waiting for UI ready...")
time.sleep(3)

# 调出前台
adb('shell', 'input', '-d', '0', 'tap', '219', '2128', check=False)
time.sleep(2)
print("Alipay brought to front!")
