#!/usr/bin/env python3
"""Drive the guest UI on the container device: dump, tap by text, report top activity and crash buffer.

Reusable across the per-application batch: every step re-dumps because Workbench coordinates drift
per round (a tap on a stale coordinate hits the wrong row or nothing at all).

    python3 android/scripts/guest_ui.py dump
    python3 android/scripts/guest_ui.py tap "重新连接"
    python3 android/scripts/guest_ui.py tap "微博" --scroll
    python3 android/scripts/guest_ui.py top
    python3 android/scripts/guest_ui.py crash
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = "d861a4fa"
HOST = "dev.tripoder.q6"


def adb(*argv, check=False, timeout=40):
    result = subprocess.run(['adb', '-s', SERIAL, *argv], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB_FAILED: ' + result.stderr.strip())
    return result


def nodes(attempts=3):
    """Dump the current screen and return its nodes.

    A dump can transiently fail ("ERROR: null root node" / an emtpy hierarchy), and when it
    does uiautomator leaves the previous file behind — an empty tree that looks exactly like
    "this screen has no controls". That is how the import screen's 「已选 N 个」 read back
    empty while the same screen was dumpable by hand milliseconds later, which then
    cascaded into a false NO_SELECTION for that package. So retry, and only trust a body
    that actually carries a `bounds=` attribute (also lowercase — `Bounds` never matches).

    The path is /sdcard/w.xml rather than /data/local/tmp because that is the path the
    hand-driven probes use; keeping one path makes the two comparable.
    """
    remote = '/sdcard/w.xml'
    body = ''
    for _ in range(attempts):
        adb('shell', 'uiautomator', 'dump', remote)
        xml = adb('shell', 'cat', remote).stdout
        body = xml[xml.find('<?xml'):] if '<?xml' in xml else ''
        if 'bounds=' in body:
            break
        body = ''
        time.sleep(1.5)
    if not body:
        return []
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return []
    found = []
    for node in root.iter('node'):
        found.append({
            'text': node.get('text') or '',
            'desc': node.get('content-desc') or '',
            'rid': node.get('resource-id') or '',
            'cls': node.get('class') or '',
            'bounds': node.get('bounds') or '',
        })
    return found


def center(bounds):
    numbers = [int(value) for value in re.findall(r'\d+', bounds)]
    if len(numbers) != 4:
        return None
    x1, y1, x2, y2 = numbers
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(wanted):
    for node in nodes():
        if node['text'] == wanted or node['desc'] == wanted:
            return node
    return None


def tap(wanted, scroll=False):
    for attempt in range(16 if scroll else 1):
        node = find(wanted)
        if node:
            point = center(node['bounds'])
            # A row clipped by the viewport reports a sliver (e.g. 6px tall) whose center
            # sits on the gesture bar, so the tap lands on nothing. Scroll and retry.
            height = center(node['bounds']) and (int(re.findall(r'\d+', node['bounds'])[3]) - int(re.findall(r'\d+', node['bounds'])[1]))
            if point and (not scroll or (height or 0) >= 60):
                print(f"tap [{wanted}] at {point} ({node['bounds']})")
                adb('shell', 'input', 'tap', str(point[0]), str(point[1]))
                return 0
            if scroll:
                print(f"[{wanted}] clipped ({height}px), scrolling the list")
        if scroll:
            if not node:
                print(f"[{wanted}] not visible, scrolling the list")
            adb('shell', 'input', 'swipe', '540', '2000', '540', '1200', '300')
            time.sleep(0.8)
    print(f"NOT_FOUND: [{wanted}]")
    return 1


def tap_contains(wanted):
    """Tap the best node whose text/desc CONTAINS wanted.

    Taking the first hit is wrong on consent pages: the descriptive paragraph
    also contains "同意" and precedes the button in dump order, so a naive
    contains-match taps the paragraph. Rank button-ish classes first, then the
    shortest label (the button says "同意"/"同 意", the paragraph is a sentence).
    Exact matching is wrong too: dumps truncate labels and some apps space out
    characters ("同 意").
    """
    best = None
    best_rank = None
    wanted_compact = ''.join(wanted.split())
    for node in nodes():
        point = center(node['bounds'])
        if not point:
            continue
        cls = node['cls'].lower()
        for field in ('text', 'desc'):
            label = node[field]
            if not label:
                continue
            # Some consent buttons are laid out with spacing inside the label
            # ("同 意"), so compare without whitespace at all.
            compact = ''.join(label.split())
            if wanted_compact not in compact:
                continue
            # "同意" is a substring of "不同意": the negated control is the one
            # that must never be tapped, so reject any label that negates the wanted word.
            if ('不' + wanted_compact) in compact:
                continue
            buttonish = 0 if 'button' in cls else (1 if len(compact) <= 6 else 2)
            rank = (buttonish, len(compact))
            if best_rank is None or rank < best_rank:
                best, best_rank = (node, field, label, point), rank
    if best:
        node, field, label, point = best
        print(f"tap~ [{wanted}] at {point} ({node['bounds']}) cls={node['cls'].split('.')[-1]} label={label[:40]!r}")
        adb('shell', 'input', 'tap', str(point[0]), str(point[1]))
        return 0
    print(f"NOT_FOUND~ [{wanted}]")
    return 1


def tap_xy(x, y):
    print(f"tapxy {x},{y}")
    adb('shell', 'input', 'tap', str(x), str(y))
    return 0


def dump():
    for node in nodes():
        label = node['text'] or node['desc'] or node['rid']
        if label:
            print(f"{node['cls'].split('.')[-1]:14s} {label[:60]:60s} {node['bounds']}")
    return 0


def top():
    out = adb('shell', 'dumpsys', 'activity', 'activities').stdout
    for line in out.splitlines():
        if 'topResumedActivity' in line or 'ResumedActivity:' in line:
            print(line.strip())
    return 0


def crash():
    out = adb('logcat', '-b', 'crash', '-d').stdout
    print(out.strip()[-4000:] or 'crash buffer empty')
    return 0


def settext(text):
    """Replace the contents of the first EditText on screen with `text`.

    Needed because the import screen remembers the previous query: reopening it shows the
    last package typed, so a blind `input text` appends to it ("com.tencent.news com.larus.nova")
    and the search finds nothing. Clear first — MOVE_END then enough DEL key events — then type.
    `input text` is a per-character injection, so long package names are written in pieces with
    a short pause, the same reason the SMS-code writer segments its digits.
    """
    field = None
    for node in nodes():
        if 'edittext' in node['cls'].lower():
            field = node
            break
    if not field:
        print('NOT_FOUND: EditText')
        return 1
    point = center(field['bounds'])
    if not point:
        print(f"NOT_FOUND: EditText bounds {field['bounds']!r}")
        return 1
    print(f"edit [{field['text']!r}] at {point} ({field['bounds']})")
    adb('shell', 'input', 'tap', str(point[0]), str(point[1]))
    time.sleep(1.0)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    for _ in range(len(field['text']) + 8):
        adb('shell', 'input', 'keyevent', 'KEYCODE_DEL')
    time.sleep(0.6)
    for start in range(0, len(text), 12):
        adb('shell', 'input', 'text', text[start:start + 12])
        time.sleep(0.5)
    return 0


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    command, rest = argv[0], argv[1:]
    if command == 'tap':
        return tap(rest[0], '--scroll' in rest)
    if command == 'tap~':
        return tap_contains(rest[0])
    if command == 'tapxy':
        return tap_xy(int(rest[0]), int(rest[1]))
    if command == 'settext':
        return settext(rest[0])
    if command == 'dump':
        return dump()
    if command == 'top':
        return top()
    if command == 'crash':
        return crash()
    print(f"UNKNOWN_COMMAND: {command}")
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
