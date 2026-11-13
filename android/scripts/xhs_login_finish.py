#!/usr/bin/env python3
"""Drive Xiaohongshu from a cold container start to the SMS-code page with a focus gate.

Why the gate: on this device `mCurrentFocus` and `FocusedApplications` disagree. `dumpsys input`
can list the guest ActivityRecord while `mCurrentFocus` is still null, and in that state
`adb shell input text` is silently dropped (that is exactly how the first code attempt was lost).
So every text write is preceded by an assertion that window focus belongs to the container; if it
does not, the screen is toggled (off/on), which is the only recovery that has been observed to work
-- force-stop does not restore it.

Phone/code pages contain an EditText, so uiautomator dump is forbidden there (accessibility prefetch
walks into ClipboardManager and the system kills the guest). Progress is checked with screenshots.

    python3 android/scripts/xhs_login_finish.py            # walk to the code page, leave code untyped
    python3 android/scripts/xhs_login_finish.py --type-code # also read the SMS code and type it
"""
import re
import subprocess
import sys
import time

SERIAL = 'd861a4fa'
HOST = 'dev.tripoder.q6'
SMS_URI = 'content://sms/inbox'
CODE_RE = re.compile(r'小红书.*?(\d{6})')


def adb(*argv, timeout=60):
    return subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)


def tap(x, y):
    print(f'tap ({x},{y})', flush=True)
    adb('shell', 'input', 'tap', str(x), str(y))


def shot(name):
    adb('shell', 'screencap', '-p', f'/data/local/tmp/{name}.png')
    adb('pull', f'/data/local/tmp/{name}.png', f'/tmp/{name}.png')
    print(f'shot /tmp/{name}.png', flush=True)


def current_focus():
    out = adb('shell', 'dumpsys window | grep -m1 mCurrentFocus').stdout.strip()
    return out


def guest_has_focus():
    line = current_focus()
    print('FOCUS:', line, flush=True)
    return 'tripoder' in line.lower() or 'TaskP' in line


def ensure_focus(label, attempts=2):
    for attempt in range(attempts):
        if guest_has_focus():
            print(f'focus ok before {label}', flush=True)
            return True
        print(f'focus missing before {label}; toggling screen (attempt {attempt + 1})', flush=True)
        adb('shell', 'input', 'keyevent', '26')
        time.sleep(2)
        adb('shell', 'input', 'keyevent', '26')
        time.sleep(3)
        adb('shell', 'input', 'keyevent', '82')
        time.sleep(1)
    ok = guest_has_focus()
    print(f'focus gate before {label}: {"ok" if ok else "FAILED"}', flush=True)
    return ok


def type_chunked(value, size=4):
    for start in range(0, len(value), size):
        adb('shell', 'input', 'text', value[start:start + size])
        time.sleep(0.6)


def newest_code():
    out = adb('shell', 'content', 'query', '--uri', SMS_URI,
              '--projection', 'body:date', '--sort', '"date DESC"', timeout=40).stdout
    for line in out.splitlines():
        match = CODE_RE.search(line)
        if match:
            print('SMS:', line.strip()[:120], flush=True)
            return match.group(1)
    return None


def step(label, seconds):
    print(f'-- {label} ({seconds}s)', flush=True)
    time.sleep(seconds)


def main():
    want_code = '--type-code' in sys.argv
    adb('shell', 'am', 'force-stop', HOST)
    adb('shell', 'am', 'force-stop', 'dev.tripoder.q7')
    step('slots cleared', 2)
    adb('shell', 'am', 'start', '-W', '-n', f'{HOST}/dev.tripoder.hooklab.WorkbenchActivity')
    step('workbench up', 3)
    for attempt in range(2):
        subprocess.run(['python3', 'android/scripts/guest_ui.py', 'tap', '重新连接'],
                       capture_output=True, text=True)
        step(f'reconnect {attempt + 1}', 3)
    subprocess.run(['python3', 'android/scripts/guest_ui.py', 'tap', '小红书', '--scroll'],
                   capture_output=True, text=True)
    step('xhs starting', 38)
    shot('f0_start')
    tap(604, 1764)   # 个人信息保护提示 -> 同意
    step('privacy 1', 10)
    tap(602, 2413)   # 请阅读并同意以下条款 -> 同意并继续
    step('privacy 2', 7)
    shot('f1_after_privacy')
    tap(604, 2087)   # 手机号登录
    step('phone page', 5)
    tap(600, 570)    # 手机号输入框
    step('field tap', 1)
    if not ensure_focus('phone number'):
        print('ABORT: no guest focus, phone number cannot be written', flush=True)
        return 2
    type_chunked('15323738553')
    step('number typed', 1)
    shot('f2_number')
    tap(602, 861)    # 验证并登录
    step('request code', 7)
    shot('f3_code1')
    tap(602, 2413)   # 条款弹窗（如出现）
    step('dialog dismissed', 6)
    shot('f4_code_page')
    if not want_code:
        print('DONE (code page reached, code not typed)', flush=True)
        return 0
    code = newest_code()
    if not code:
        print('ABORT: no Xiaohongshu code in inbox', flush=True)
        return 3
    tap(384, 689)    # 输入验证码
    step('code field tap', 1)
    if not ensure_focus('sms code'):
        print('ABORT: no guest focus, code cannot be written', flush=True)
        return 4
    type_chunked(code, size=2)
    step('code typed', 1)
    shot('f5_code_typed')
    print(f'DONE (code {code} typed)', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
