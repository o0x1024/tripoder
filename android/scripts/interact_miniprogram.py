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

def dump_ui(name="current"):
    remote_xml = f"/data/local/tmp/{name}.xml"
    adb('shell', 'uiautomator', 'dump', remote_xml, check=False)
    res = adb('shell', 'cat', remote_xml, check=False)
    if res.returncode != 0 or not res.stdout.strip():
        return []
    try:
        root = ET.fromstring(res.stdout)
    except Exception as e:
        print("XML parse error:", e)
        return []
    
    nodes = []
    for n in root.iter('node'):
        text = n.get('text', '').strip()
        desc = n.get('content-desc', '').strip()
        res_id = n.get('resource-id', '').strip()
        clickable = n.get('clickable', '') == 'true'
        bounds = n.get('bounds', '')
        b_match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
        cx, cy = 0, 0
        if b_match:
            x1, y1, x2, y2 = map(int, b_match.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        if text or desc or res_id:
            nodes.append({
                'text': text,
                'desc': desc,
                'res_id': res_id,
                'clickable': clickable,
                'bounds': bounds,
                'cx': cx,
                'cy': cy
            })
    return nodes

def take_screenshot(local_path):
    remote = "/data/local/tmp/screen_step.png"
    adb('shell', 'screencap', '-d', '4630946245184196243', '-p', remote)
    adb('pull', remote, local_path)
    subprocess.run(['cp', local_path, '/Users/like/.gemini/antigravity/brain/369708f0-ca91-47f6-9e13-5f5d03c9f748/'])
    print(f"Saved screenshot to {local_path}")

print("Preparing device...")
adb('shell', 'am', 'force-stop', PKG, check=False)
adb('shell', 'am', 'force-stop', HOST, check=False)
adb('shell', 'input', 'keyevent', '224', check=False)
adb('shell', 'wm', 'dismiss-keyguard', check=False)
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
    '-e', 'stabilitySeconds', '600',
    '-e', 'capture', 'false',
    '-e', 'stopAfterTest', 'false',
    f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
]

print(f"Launching WeChat via instrumentation (runId: {run_id})...")
adb('shell', 'logcat', '-c', check=False)
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

workbench_launched = False
deadline = time.monotonic() + 40
while proc.poll() is None:
    if time.monotonic() > deadline:
        break
    if not workbench_launched:
        sig = adb('shell', 'cat', f"{remote}launch-workbench-{run_id}.json", check=False)
        if sig.returncode == 0:
            print("Received launch signal, starting WorkbenchActivity...")
            adb('shell', 'am', 'start', '-W', '-n', f"{HOST}/dev.tripoder.hooklab.WorkbenchActivity", check=False)
            attempt_reconnect(HOST)
            workbench_launched = True
            break
    time.sleep(0.3)

print("Waiting for WeChat window to enter foreground...")
for i in range(15):
    focus = subprocess.run(['adb', '-s', SERIAL, 'shell', 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"'], capture_output=True, text=True).stdout
    if 'LauncherUI' in focus or 'TaskP0' in focus:
        print(f"WeChat window focused: {focus.strip()}")
        break
    # If Workbench is still showing, check if we need to tap WeChat tile
    if 'WorkbenchActivity' in focus:
        wb_nodes = dump_ui("wb_check")
        wx_tile = next((n for n in wb_nodes if n['text'] == '微信' or '微信' in n['desc']), None)
        if wx_tile and wx_tile['clickable']:
            print(f"Tapping WeChat tile on Workbench at ({wx_tile['cx']}, {wx_tile['cy']})...")
            adb('shell', 'input', 'tap', str(wx_tile['cx']), str(wx_tile['cy']))
    time.sleep(1)

time.sleep(3)
take_screenshot("docs/evidence/wechat_step_main.png")

# Now tap '发现' at (750, 2510)
print("Tapping '发现' at (750, 2510)...")
adb('shell', 'input', 'tap', '750', '2510')
time.sleep(2)

take_screenshot("docs/evidence/wechat_step_find.png")
nodes = dump_ui("find_page")
print("Find page elements:")
mp_node = None
for n in nodes:
    if n['text']:
        print(f"  '{n['text']}' @ ({n['cx']}, {n['cy']})")
    if '小程序' in n['text']:
        mp_node = n

if mp_node:
    print(f"Clicking discovered '小程序' at ({mp_node['cx']}, {mp_node['cy']})")
    adb('shell', 'input', 'tap', str(mp_node['cx']), str(mp_node['cy']))
else:
    print("Clicking '小程序' at default coordinates (400, 2050)...")
    adb('shell', 'input', 'tap', '400', '2050')

print("Waiting 8s for Mini Program page to load...")
time.sleep(8)

take_screenshot("docs/evidence/wechat_step_miniprogram_loaded.png")
nodes_mp = dump_ui("miniprogram_loaded")
print("Mini Program loaded elements:")
for n in nodes_mp:
    if n['text'] or n['desc']:
        print(f"  '{n['text']}' / '{n['desc']}' @ ({n['cx']}, {n['cy']})")

# Look for search or a mini program item to launch an actual mini program
mp_item = next((n for n in nodes_mp if any(k in n['text'] for k in ['拼多多', '京东', '美团', '滴滴', '健康码', '交通']) or any(k in n['desc'] for k in ['拼多多', '京东', '美团', '滴滴'])), None)
if mp_item:
    print(f"Launching mini program '{mp_item['text'] or mp_item['desc']}' at ({mp_item['cx']}, {mp_item['cy']})...")
    adb('shell', 'input', 'tap', str(mp_item['cx']), str(mp_item['cy']))
else:
    # If no named items, tap the first clickable item in the list or top search
    search_icon = next((n for n in nodes_mp if '搜索' in n['desc'] or '搜索' in n['text']), None)
    if search_icon:
        print(f"Tapping search at ({search_icon['cx']}, {search_icon['cy']})...")
        adb('shell', 'input', 'tap', str(search_icon['cx']), str(search_icon['cy']))
    else:
        print("Tapping middle of top section to trigger mini program search/explore...")
        adb('shell', 'input', 'tap', '500', '400')

print("Waiting 10s for Mini Program container/engine (appbrand/xweb/v8) to initialize...")
time.sleep(10)

take_screenshot("docs/evidence/wechat_miniprogram_active.png")

# Check running processes
ps_out = subprocess.run(['adb', '-s', SERIAL, 'shell', 'ps -ef | grep -E "tripoder|tencent|appbrand"'], capture_output=True, text=True, shell=True).stdout
print("\n=== Active Processes ===")
print(ps_out)

# Check logcat
logcat = subprocess.run(['adb', '-s', SERIAL, 'logcat', '-d'], capture_output=True, text=True).stdout
with open("docs/evidence/miniprogram_loaded_logcat.txt", 'w') as f:
    f.write(logcat)

print("\n=== Check for crashes/errors ===")
errors = [line for line in logcat.splitlines() if any(k in line for k in ['FATAL', 'CRASHING JAVA', 'RECEIVER_LOADER_MISMATCH', 'AndroidRuntime', 'PUBLIC_NATIVE_LIBRARY_SHAPE_REJECTED', 'SERVICE_INTENT_LEASE_MISMATCH'])]
if errors:
    print(f"Found {len(errors)} error lines:")
    for e in errors[:10]:
        print("  ", e)
else:
    print("ZERO CRASHES / ZERO ERRORS!")

# Keep running without killing
print("Mini program interactive verification complete.")
