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
PHONE_NUMBER = "15323738553"

PKG = "com.sina.weibo"
SHA256 = "3fff30c9c1f74c551466bb1ce31c6e761fbeb745fb249d98e849afdb28729a5a"
APK_PATH = "/data/app/~~jdkyGIsZziC2m7QN6h_a0g==/com.sina.weibo-0A5VAa6E4ZlXivTpaB98tQ==/base.apk"

def adb(*argv, check=True, timeout=30):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result

def ensure_workbench():
    adb('shell', 'am', 'start', '-W', '-n', f"{HOST}/dev.tripoder.hooklab.WorkbenchActivity", check=False)
    time.sleep(0.5)

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
                'cy': cy,
                'node': n
            })
    return nodes

def tap_node(node):
    print(f"Tapping: [{node.get('text') or node.get('desc') or node.get('res_id')}] at ({node['cx']}, {node['cy']})")
    adb('shell', 'input', 'tap', str(node['cx']), str(node['cy']))

def take_screenshot(local_path):
    remote = "/data/local/tmp/screen_step.png"
    adb('shell', 'screencap', '-p', remote)
    adb('pull', remote, local_path)
    print(f"Saved screenshot to {local_path}")

print("Ensuring Workbench...")
ensure_workbench()

run_id = f"control-{uuid.uuid4().hex[:16]}"
remote = f"/sdcard/Android/data/{HOST}/files/"

cmd = [
    'adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'runId', run_id,
    '-e', 'retryEngineOnce', 'true',
    '-e', 'customPackage', PKG,
    '-e', 'customSha256', SHA256,
    '-e', 'customSource', APK_PATH,
    '-e', 'stabilitySeconds', '180',
    '-e', 'capture', 'false',
    '-e', 'stopAfterTest', 'false',
    f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
]

print("Launching Weibo via instrumentation...")
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
            workbench_launched = True
            break
    time.sleep(0.3)

time.sleep(3)
print("Dumping current UI...")
nodes = dump_ui("weibo_init")
for n in nodes:
    if n['text'] or n['desc']:
        print(f"  {n['res_id']} -> '{n['text']}' / '{n['desc']}' @ ({n['cx']},{n['cy']})")

take_screenshot("/tmp/weibo_step1.png")
