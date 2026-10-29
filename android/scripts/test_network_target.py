#!/usr/bin/env python3
import argparse
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

APPS = {
    "aweme": {
        "pkg": "com.ss.android.ugc.aweme",
        "sha256": "86ee431640c9fe5035135092079af2dcddf947feb7e02cb78aaf459c821f80c0",
        "path": "/data/app/~~UZI_96LXkcEkgzLC-cIoDw==/com.ss.android.ugc.aweme-I_ukds9p9gswwSKtruHn2g==/base.apk",
        "module": "auto"
    },
    "imgo": {
        "pkg": "com.hunantv.imgo.activity",
        "sha256": "bcb615587df4d3fbd8689ca7aa3972b8a6b8c7339bb9b0832284236ba3133df1",
        "path": "/data/app/~~dEoQVLeDUnfT2TX25_zkww==/com.hunantv.imgo.activity-YguAGKtbMbcMAzAxZFas6Q==/base.apk",
        "module": "auto"
    },
    "xhs": {
        "pkg": "com.xingin.xhs",
        "sha256": "8ddf9ae4e4cdbc70e051e2c02c55ea18ba0a02c3944a5a4e48d38ece5c92a13c",
        "path": "/data/app/~~9L_UKoVGTcGUJi-Fo13Yzg==/com.xingin.xhs-h6TN0xhraU1JdsktKNvLDA==/base.apk",
        "module": "auto"
    },
    "qiyi": {
        "pkg": "com.qiyi.video",
        "sha256": "86ed69c4f25df76be7407644060f02f3e9f8d7f9997b47808facab38adfaa393",
        "path": "/data/app/~~2ad3jvcQARDtYTS_xJwepQ==/com.qiyi.video-9lUo9x19V_bOAd4qpdi_MA==/base.apk",
        "module": "libmctocurl.so"
    },
    "idlefish": {
        "pkg": "com.taobao.idlefish",
        "sha256": "f8f307e3b9f1f7c54b1c8f89932be5170d21a39df2973c786c76e47eef27831a",
        "path": "/data/app/~~ZTkCaw7sJAlj7Uhcy3hV3g==/com.taobao.idlefish-UdV-Do4mKEup6enJZxoNbA==/base.apk",
        "module": "libopenssl.so"
    },
    "luna": {
        "pkg": "com.luna.music",
        "sha256": "197fefa94025df5b5ebb129d99a18ca2be3b5d04fe8d16ea8bbf237744b1f1c8",
        "path": "/data/app/~~sgNnfYI_89I2aGQ01gB3_g==/com.luna.music-ZaFSXyt4p-junfJ655qQHw==/base.apk",
        "module": "libttboringssl.so"
    },
    "wps": {
        "pkg": "cn.wps.moffice_eng",
        "sha256": "5d037e850f5d4ef8a6428672f0051bae8ae9a45abe81c1376dda1f1a0f99ba4d",
        "path": "/data/app/~~Dx9zVhUPyMcBWI0If7-qXA==/cn.wps.moffice_eng-PSIBbVGHZ4zVVsL_sXWl-A==/base.apk",
        "module": "libssl.so"
    }
}

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
    adb('shell', 'pm', 'grant', HOST, 'android.permission.READ_PHONE_STATE', check=False)
    adb('shell', 'appops', 'set', HOST, 'MANAGE_EXTERNAL_STORAGE', 'allow', check=False)
    adb('shell', 'am', 'start', '-W', '-n', HOST + '/dev.tripoder.hooklab.WorkbenchActivity', check=False)
    attempt_reconnect(HOST)
    time.sleep(0.5)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', default='aweme', choices=list(APPS.keys()))
    parser.add_argument('--module', default=None)
    args = parser.parse_args()

    app_info = APPS[args.app]
    pkg = app_info["pkg"]
    sha256 = app_info["sha256"]
    apk_path = app_info["path"]
    module = args.module or app_info["module"]

    print(f"Preparing device for network acceptance on {pkg} (module: {module})...")
    adb('shell', 'am', 'force-stop', pkg, check=False)
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
        '-e', 'customPackage', pkg,
        '-e', 'customSha256', sha256,
        '-e', 'customSource', apk_path,
        '-e', 'stabilitySeconds', '15',
        '-e', 'capture', 'true',
        '-e', 'stopAfterTest', 'true',
        '-e', 'networkChecks', 'true',
        '-e', 'networkModule', module,
        f"{TESTS}/dev.tripoder.controltest.TargetLaunchIntegration"
    ]

    print(f"Launching am instrument with networkChecks for {pkg} (runId: {run_id})...")
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
    res_content = adb('shell', 'cat', result_file, check=False)
    if res_content.returncode == 0:
        print("\n=== Result JSON ===")
        print(res_content.stdout)

    # Pull screenshot
    capture_file = f"{run_id}-target-stable.png"
    out_img = f"docs/evidence/diag-network-{args.app}-stable.png"
    os.makedirs("docs/evidence", exist_ok=True)
    adb('pull', f"{remote}{capture_file}", out_img, check=False)
    print(f"Evidence pulled to {out_img}")

if __name__ == '__main__':
    main()
