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
    adb('shell', 'screencap', '-p', remote)
    adb('pull', remote, local_path)
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
    '-e', 'stabilitySeconds', '300',
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

print("Waiting 6s for WeChat main UI...")
time.sleep(6)

nodes = dump_ui("wechat_home")
print("WeChat Home UI elements:")
for n in nodes:
    if n['text'] in ['微信', '通讯录', '发现', '我', '文件传输助手']:
        print(f"  {n['text']} @ ({n['cx']}, {n['cy']})")

take_screenshot("docs/evidence/wechat_home.png")

# Tap '发现'
find_node = next((n for n in nodes if n['text'] == '发现'), None)
if find_node:
    print(f"Tapping 发现 at ({find_node['cx']}, {find_node['cy']})")
    adb('shell', 'input', 'tap', str(find_node['cx']), str(find_node['cy']))
else:
    print("Fallback tap 发现 at (670, 2520)")
    adb('shell', 'input', 'tap', '670', '2520')

time.sleep(2)
nodes = dump_ui("wechat_find")
print("WeChat '发现' UI elements:")
mp_node = None
for n in nodes:
    if n['text']:
        print(f"  {n['text']} @ ({n['cx']}, {n['cy']})")
    if '小程序' in n['text']:
        mp_node = n

take_screenshot("docs/evidence/wechat_find.png")

if mp_node:
    print(f"Tapping 小程序 at ({mp_node['cx']}, {mp_node['cy']})")
    adb('shell', 'input', 'tap', str(mp_node['cx']), str(mp_node['cy']))
else:
    print("Tapping 小程序 at (400, 2050)")
    adb('shell', 'input', 'tap', '400', '2050')

print("Waiting 5s after clicking 小程序...")
time.sleep(5)

take_screenshot("docs/evidence/wechat_miniprogram.png")
nodes = dump_ui("wechat_miniprogram")
print("Mini Program UI elements:")
for n in nodes:
    if n['text']:
        print(f"  {n['text']} @ ({n['cx']}, {n['cy']})")

# Dump logcat
logcat = subprocess.run(['adb', '-s', SERIAL, 'logcat', '-d'], capture_output=True, text=True).stdout
with open("docs/evidence/miniprogram_logcat.txt", 'w') as f:
    f.write(logcat)

print("\n=== Logcat Snippets ===")
for line in logcat.splitlines():
    if any(k in line for k in ['TripoderAssertion', 'TripoderUncaught', 'CRASHING JAVA', 'Suppressed', 'libxwebcore']):
        print("  ", line)

proc.terminate()
