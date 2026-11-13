#!/usr/bin/env python3
"""Re-drive the Xiaohongshu login after the stuck-focus state: rebuild focus by force-stopping
the container, then walk privacy -> phone login -> code page using fixed coordinates taken from
screenshots (the phone/code pages contain EditText, so uiautomator dump must be avoided there)."""
import subprocess
import sys
import time

SERIAL = 'd861a4fa'
HOST = 'dev.tripoder.q6'


def adb(*argv, timeout=60):
    return subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)


def tap(x, y):
    print(f'tap ({x},{y})', flush=True)
    adb('shell', 'input', 'tap', str(x), str(y))


def shot(name):
    adb('shell', 'screencap', '-p', f'/data/local/tmp/{name}.png')
    adb('pull', f'/data/local/tmp/{name}.png', f'/tmp/{name}.png')
    print(f'shot {name}', flush=True)


def focus():
    out = adb('shell', 'dumpsys window | grep -m2 -E "mCurrentFocus|mFocusedApp"').stdout
    print('FOCUS:', ' | '.join(line.strip() for line in out.splitlines()), flush=True)


def step(label, seconds):
    print(f'-- {label} ({seconds}s)', flush=True)
    time.sleep(seconds)


def main():
    adb('shell', 'am', 'force-stop', HOST)
    adb('shell', 'am', 'force-stop', 'dev.tripoder.q7')
    step('slots cleared', 2)
    adb('shell', 'am', 'start', '-W', '-n', f'{HOST}/dev.tripoder.hooklab.WorkbenchActivity')
    step('workbench up', 3)
    for attempt in range(2):
        subprocess.run(['python3', 'android/scripts/guest_ui.py', 'tap', '重新连接'])
        step(f'reconnect {attempt + 1}', 3)
    subprocess.run(['python3', 'android/scripts/guest_ui.py', 'tap', '小红书', '--scroll'])
    step('xhs starting', 38)
    focus()
    shot('r0_start')
    tap(604, 1764)          # 个人信息保护提示 -> 同意
    step('privacy 1', 10)
    tap(602, 2413)          # 请阅读并同意以下条款 -> 同意并继续
    step('privacy 2', 7)
    shot('r1_after_privacy')
    tap(604, 2087)          # 手机号登录
    step('phone page', 5)
    tap(600, 570)           # 手机号输入框
    step('field focus', 1)
    for chunk in ('153', '2373', '8553'):
        adb('shell', 'input', 'text', chunk)
        time.sleep(0.6)
    step('number typed', 1)
    shot('r2_number')
    tap(602, 861)           # 验证并登录
    step('request code', 7)
    shot('r3_code1')
    tap(602, 2413)          # 条款弹窗（如出现）
    step('dialog dismissed', 6)
    shot('r4_code2')
    focus()
    print('DONE', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
