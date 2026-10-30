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
PKG = "ctrip.android.view"
SHA256 = "d0ad3e5b40cafa0e020d8bee45bd9d76473ef7c236f7ff39b58809ac72e886b7"
APK_PATH = "/data/app/~~_bK3KgYZ5T2hKvlOg8FZgg==/ctrip.android.view-G0EPwOutkCFfgD_Y7ag1rw==/base.apk"
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
adb('shell', 'pm', 'grant', HOST, 'android.permission.ACCESS_FINE_LOCATION', check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.ACCESS_COARSE_LOCATION', check=False)
adb('shell', 'pm', 'grant', HOST, 'android.permission.READ_PHONE_STATE', check=False)
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

print(f"2. Launching Ctrip concurrently with 300s window (runId: {run_id})...")
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
ctrip_running = False
deadline = time.monotonic() + 60

while time.monotonic() < deadline:
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote_files}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Launch signal found, ensuring Workbench...")
            adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
            attempt_reconnect(HOST)
            workbench_launched = True

    ps = adb('shell', 'ps', '-ef', check=False).stdout
    if PKG in ps:
        focus = adb('shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"', check=False).stdout
        if 'ctrip' in focus.lower() or 'TaskP' in focus:
            print(f"3. Ctrip activity is focused: {focus.strip()}")
            ctrip_running = True
            break
    time.sleep(0.5)

if not ctrip_running:
    print("Failed to reach Ctrip activity within 60s")
    proc.terminate()
    exit(1)

# Step 1: Wait for initial privacy guidelines dialog
time.sleep(2)
capture_screen("ctrip_dialog1_privacy.png")

# Inspect UI dump for "同意并继续" or "同意"
print("4. Inspecting UI for agreement button...")
node = find_node(text_pattern=r"^同意并继续$", clickable=True)
if not node:
    node = find_node(text_pattern=r"同意并继续", clickable=True)
if node:
    coords, bounds, text, rid = node
    print(f"Found clickable button: '{text}' ({rid}) at center {coords}, bounds {bounds}")
    print(f"Clicking at {coords}...")
    adb('shell', 'input', 'tap', str(coords[0]), str(coords[1]))
else:
    print("Could not find '同意并继续' clickable button!")

# Step 2: Observe home feed transition and multi-process behavior
print("5. Observing Ctrip home feed rendering and engine transition...")
for step in range(1, 11):
    time.sleep(3)
    elapsed = step * 3

    perm_btn = find_node(text_pattern=r"^(仅在使用中允许|允许|同意|知道了)$", clickable=True)
    if perm_btn:
        btn_coords, _, btn_text, _ = perm_btn
        print(f"  Dismissing dialog button '{btn_text}' at {btn_coords}")
        adb('shell', 'input', 'tap', str(btn_coords[0]), str(btn_coords[1]))
        time.sleep(1)

    fn = f"ctrip_step_{elapsed}s.png"
    capture_screen(fn)
    
    focus = adb('shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"', check=False).stdout
    ps = adb('shell', 'ps', '-ef', check=False).stdout
    procs = [l for l in ps.splitlines() if PKG in l]
    proc_names = [p.split()[-1] for p in procs]
    print(f"  [+{elapsed}s] Focus: {focus.strip()} | Procs ({len(procs)}): {proc_names}")

print("6. Capturing diagnostic logcat...")
logcat = adb('shell', 'logcat', '-d', check=False).stdout
log_path = f"docs/evidence/ctrip_interactive_deep_{run_id}.txt"
with open(log_path, 'w') as f:
    f.write(logcat)
print(f"Logcat saved to {log_path}")

print("7. Terminating test run cleanly...")
proc.terminate()
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
print("Done!")
