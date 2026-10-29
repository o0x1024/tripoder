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
PKG = "com.xingin.xhs"
SHA256 = "8ddf9ae4e4cdbc70e051e2c02c55ea18ba0a02c3944a5a4e48d38ece5c92a13c"
APK_PATH = "/data/app/~~9L_UKoVGTcGUJi-Fo13Yzg==/com.xingin.xhs-h6TN0xhraU1JdsktKNvLDA==/base.apk"

def adb(*argv, check=True, timeout=30):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
adb('shell', 'input', 'keyevent', '224', check=False)
adb('shell', 'wm', 'dismiss-keyguard', check=False)
time.sleep(1)

# 清除 logcat
adb('shell', 'logcat', '-c', check=False)

# 启动 Workbench
adb('shell', 'am', 'start', '-W', '-n', f"{HOST}/dev.tripoder.hooklab.WorkbenchActivity", check=False)
time.sleep(0.5)

run_id = f"control-{uuid.uuid4().hex[:16]}"
remote = f"/sdcard/Android/data/{HOST}/files/"

cmd = [
    'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'runId', run_id,
    '-e', 'retryEngineOnce', 'true',
    '-e', 'customPackage', PKG,
    '-e', 'customSha256', SHA256,
    '-e', 'customSource', APK_PATH,
    '-e', 'stabilitySeconds', '300',
    '-e', 'capture', 'false',
    '-e', 'stopAfterTest', 'false',
    f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
]

print(f"Launching XHS via instrumentation (runId: {run_id})...")
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
deadline = time.monotonic() + 30
while proc.poll() is None:
    if time.monotonic() > deadline:
        break
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Received launch signal, waiting for target activity...")
            workbench_launched = True
            break
    time.sleep(0.3)

time.sleep(3)
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

# 查询 Worker PID
worker_pid = None
for _ in range(15):
    ps_res = adb('shell', f"ps -A | grep -E '{HOST}:slot|com.xingin.xhs'", check=False)
    for line in ps_res.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 9 and ('slot' in parts[8] or 'xingin' in parts[8]):
            worker_pid = parts[1]
            break
    if worker_pid:
        break
    time.sleep(0.5)

print(f"Detected Worker PID: {worker_pid}")
if not worker_pid:
    print("No worker PID found!")
    exit(1)

# 保存 maps
maps = adb('shell', f"cat /proc/{worker_pid}/maps", check=False).stdout
with open('/tmp/xhs_worker_maps.txt', 'w') as f:
    f.write(maps)
print(f"Saved {len(maps.splitlines())} lines of maps to /tmp/xhs_worker_maps.txt")

# 列出当前所有线程
task_res = adb('shell', f"ls /proc/{worker_pid}/task", check=False)
threads_before = task_res.stdout.split()
print(f"Worker {worker_pid} has {len(threads_before)} threads before consent.")

# 检查当前 UI，找到“同意”按钮（重试最多 15 次）
cx, cy = None, None
for attempt in range(15):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/consent.xml', check=False)
    xml_data = adb('shell', 'cat', '/data/local/tmp/consent.xml', check=False).stdout
    agree_match = re.search(r'text="同意"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml_data)
    if agree_match:
        x1, y1, x2, y2 = map(int, agree_match.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        print(f"Found '同意' button at ({cx}, {cy}) on attempt {attempt+1}")
        break
    # 尝试点进小红书应用卡片
    if '小红书' in xml_data:
        m = re.search(r'text="小红书"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml_data)
        if m:
            tx, ty = (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
            print(f"Tapping XHS card at ({tx}, {ty})")
            adb('shell', 'input', '-d', '0', 'tap', str(tx), str(ty), check=False)
    time.sleep(1)

if not cx:
    print("Could not find '同意' button after 15 attempts!")
    exit(1)

print(f"Clicking '同意' at ({cx}, {cy})...")
adb('shell', 'input', '-d', '0', 'tap', str(cx), str(cy), check=False)
print("Clicked '同意'! Monitoring PID survival...")

alive = True
for i in range(300):
    check_alive = adb('shell', f"kill -0 {worker_pid}", check=False)
    if check_alive.returncode != 0:
        print(f"Worker {worker_pid} died at +{i*100}ms!")
        alive = False
        break
    time.sleep(0.1)

if alive:
    print(f"Worker {worker_pid} is STILL ALIVE after 30 seconds! SUCCESS!")
    adb('shell', 'screencap', '-p', '/data/local/tmp/xhs_consent_stable.png', check=False)
    adb('pull', '/data/local/tmp/xhs_consent_stable.png', 'docs/evidence/diag-xhs-01-consent-stable.png', check=False)
    print("Saved screenshot to docs/evidence/diag-xhs-01-consent-stable.png")
else:
    print("Gathering crash details from logcat...")
    log_res = adb('shell', f"logcat -d | grep '{worker_pid}' | tail -n 80", check=False)
    print("Logcat:")
    print(log_res.stdout)
