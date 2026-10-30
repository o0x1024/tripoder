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
PKG = "com.tencent.tmgp.sgame"
SHA256 = "6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384"
APK_PATH = "/data/app/~~R29ZW34P0Yfx2rjk9QINew==/com.tencent.tmgp.sgame-ddCgQNTlU1m4ZJVMuZio_A==/base.apk"
DISPLAY_ID = "4630946245184196243"

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

def capture_screen(filename):
    remote = f"/data/local/tmp/{filename}"
    adb('shell', 'screencap', '-d', DISPLAY_ID, '-p', remote)
    local = f"/Users/like/.gemini/antigravity/brain/369708f0-ca91-47f6-9e13-5f5d03c9f748/{filename}"
    subprocess.run(['adb', '-s', SERIAL, 'pull', remote, local], capture_output=True, check=True)
    print(f"Captured screen -> {local}")
    return local

def find_node(text_pattern=None, resource_id=None, clickable=None):
    window = '/data/local/tmp/ui_dump.xml'
    adb('shell', 'uiautomator', 'dump', window, check=False, timeout=10)
    snapshot = adb('shell', 'cat', window, check=False)
    if snapshot.returncode != 0:
        return None
    try:
        tree = ET.fromstring(snapshot.stdout)
        for n in tree.iter('node'):
            text = n.get('text', '')
            rid = n.get('resource-id', '')
            c = n.get('clickable', 'false')
            if clickable is not None and (c == 'true') != clickable:
                continue
            match_t = text_pattern is None or re.search(text_pattern, text)
            match_r = resource_id is None or (rid and resource_id in rid)
            if match_t and match_r:
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    return ((x1 + x2) // 2, (y1 + y2) // 2), (x1, y1, x2, y2), text, rid
    except Exception as e:
        print(f"XML parse error: {e}")
    return None

print("1. Preparing device...")
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
adb('shell', 'input', 'keyevent', '224', check=False)
adb('shell', 'wm', 'dismiss-keyguard', check=False)
for perm in [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.RECORD_AUDIO',
    'android.permission.CAMERA',
    'android.permission.READ_CALENDAR',
    'android.permission.WRITE_CALENDAR',
    'android.permission.READ_PHONE_STATE',
]:
    adb('shell', 'pm', 'grant', HOST, perm, check=False)
time.sleep(1)

ensure_workbench()
adb('shell', 'logcat', '-c', check=False)

run_id = f"control-{uuid.uuid4().hex[:16]}"
remote_files = f"/sdcard/Android/data/{HOST}/files/"

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

print(f"2. Launching sgame concurrently with 300s window (runId: {run_id})...")
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
sgame_running = False
deadline = time.monotonic() + 60

while time.monotonic() < deadline:
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote_files}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Launch signal found, ensuring Workbench...")
            adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
            attempt_reconnect(HOST)
            workbench_launched = True

    # Check if sgame process is alive and TaskP0/SGameActivity has taken focus
    ps = adb('shell', 'ps', '-ef', check=False).stdout
    if PKG in ps:
        focus = adb('shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"', check=False).stdout
        if ('SGameActivity' in focus or 'TaskP0' in focus) and 'WorkbenchActivity' not in focus:
            print(f"3. Target activity is focused: {focus.strip()}")
            sgame_running = True
            break
    time.sleep(0.5)

if not sgame_running:
    print("Failed to reach target activity within 60s")
    proc.terminate()
    exit(1)

# Allow dialog rendering to settle
time.sleep(3)

# Step 1: User Agreement
print("4. Waiting for User Agreement dialog...")
node1 = None
for _ in range(3):
    node1 = find_node(text_pattern=r"^同意$", clickable=True)
    if not node1:
        node1 = find_node(text_pattern=r"同意", clickable=True)
    if node1:
        break
    time.sleep(0.5)

capture_screen("sgame_dialog1_agreement.png")
if node1:
    coords, bounds, text, rid = node1
    print(f"Found agreement button '{text}' ({rid}) at center {coords}, bounds {bounds}")
    print(f"Clicking at {coords}...")
    adb('shell', 'input', 'tap', str(coords[0]), str(coords[1]))
else:
    print("Agreement button not found via UI dump, tapping at known coords (785, 1615)...")
    adb('shell', 'input', 'tap', '785', '1615')

# Allow second dialog to settle
time.sleep(2.5)

# Step 2: Permission List ("权限列表")
print("5. Waiting for Permission List dialog...")
node2 = None
for _ in range(3):
    node2 = find_node(text_pattern=r"^确定$", clickable=True)
    if not node2:
        node2 = find_node(text_pattern=r"确定", clickable=True)
    if node2:
        break
    time.sleep(0.5)

capture_screen("sgame_dialog2_permissions.png")
if node2:
    coords, bounds, text, rid = node2
    print(f"Found permissions button '{text}' ({rid}) at center {coords}, bounds {bounds}")
    print(f"Clicking at {coords}...")
    adb('shell', 'input', 'tap', str(coords[0]), str(coords[1]))
else:
    print("Permissions button not found via UI dump, tapping at known coords (604, 1660)...")
    adb('shell', 'input', 'tap', '604', '1660')

# Step 3: Game loading and engine transition observation
print("6. Observing game engine transition into full game...")
for step in range(1, 9):
    time.sleep(3)
    elapsed = step * 3
    fn = f"sgame_loading_{elapsed}s.png"
    capture_screen(fn)
    
    focus = adb('shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"', check=False).stdout
    ps = adb('shell', 'ps', '-ef', check=False).stdout
    procs = [l for l in ps.splitlines() if PKG in l]
    print(f"  [+{elapsed}s] Focus: {focus.strip()} | Procs ({len(procs)}): {[p.split()[-1] for p in procs]}")

print("7. Capturing final diagnostic logcat...")
logcat = adb('shell', 'logcat', '-d', check=False).stdout
log_path = f"docs/evidence/sgame_interactive_deep_{run_id}.txt"
with open(log_path, 'w') as f:
    f.write(logcat)
print(f"Logcat saved to {log_path}")

print("8. Terminating test run cleanly...")
proc.terminate()
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
print("Done!")
