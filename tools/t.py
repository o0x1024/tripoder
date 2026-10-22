#!/usr/bin/env python3
"""Guest UI test harness: dump/parse guest hierarchy, tap by text, screenshot, SMS read."""
import subprocess, sys, re, json, time, os, xml.etree.ElementTree as ET

ADB = "/opt/homebrew/bin/adb"

def sh(cmd, timeout=60):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr

def dump_xml():
    sh(f"{ADB} shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", 60)
    return sh(f"{ADB} shell cat /sdcard/ui.xml", 60)

def nodes(xml):
    out = []
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except Exception:
        return out
    for n in root.iter("node"):
        t = n.get("text", "") or ""
        d = n.get("content-desc", "") or ""
        b = n.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": t, "desc": d, "cls": (n.get("class", "") or "").split(".")[-1],
            "click": n.get("clickable") == "true", "focus": n.get("focusable") == "true",
            "scroll": n.get("scrollable") == "true", "sel": n.get("selected") == "true",
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "b": (x1, y1, x2, y2),
        })
    return out

def cmd_dump(args):
    full = "--all" in args
    ns = nodes(dump_xml())
    seen = set()
    i = 0
    print(f"# nodes={len(ns)}")
    for n in ns:
        label = (n["text"] or "").strip()
        desc = (n["desc"] or "").strip()
        if not full and not label and not desc:
            continue
        key = (label, desc, n["cx"], n["cy"])
        if key in seen:
            continue
        seen.add(key)
        flags = "".join([("C" if n["click"] else "-"), ("S" if n["scroll"] else "-"), ("F" if n["focus"] else "-")])
        print(f"{i:3d} {flags} {n['cls'][:22]:22s} {n['cx']:4d},{n['cy']:4d} t={label[:60]!r} d={desc[:40]!r}")
        i += 1

def find(ns, needle, exact=False):
    cands = []
    for n in ns:
        for field in (n["text"], n["desc"]):
            v = (field or "").strip()
            if not v:
                continue
            if (v == needle) if exact else (needle in v):
                cands.append(n)
                break
    return cands

def cmd_tap(args):
    needle = args[0]
    exact = "--exact" in args
    idx = None
    for a in args:
        if a.startswith("--idx="):
            idx = int(a.split("=")[1])
    ns = nodes(dump_xml())
    cands = find(ns, needle, exact)
    if not cands:
        print(f"NOTFOUND {needle!r}")
        return 2
    n = cands[idx if idx is not None else 0]
    sh(f"{ADB} shell input tap {n['cx']} {n['cy']}")
    print(f"TAP {needle!r} -> {n['cx']},{n['cy']} [{n['text'] or n['desc']}] n={len(cands)}")
    return 0

def cmd_tapxy(args):
    sh(f"{ADB} shell input tap {args[0]} {args[1]}")
    print(f"TAPXY {args[0]},{args[1]}")

def cmd_swipe(args):
    # x1 y1 x2 y2 [dur]
    d = args[4] if len(args) > 4 else "300"
    sh(f"{ADB} shell input swipe {args[0]} {args[1]} {args[2]} {args[3]} {d}")
    print("SWIPE ok")

def cmd_scroll(args):
    # scroll down inside vertical center
    n = int(args[0]) if args else 1
    for _ in range(n):
        sh(f"{ADB} shell input swipe 600 1900 600 700 250")
        time.sleep(0.5)
    print(f"SCROLL {n}")

def cmd_back(args):
    sh(f"{ADB} shell input keyevent 4")
    print("BACK")

def cmd_shot(args):
    path = args[0]
    sh(f"{ADB} exec-out screencap -p > {path}", 120)
    print(f"SHOT {path} {os.path.getsize(path) if os.path.exists(path) else 'ERR'}")

def cmd_text(args):
    s = args[0]
    esc = s.replace(" ", "%s")
    sh(f"{ADB} shell input text '{esc}'")
    print(f"TEXT {s}")

def cmd_sms(args):
    n = int(args[0]) if args else 3
    kw = args[1] if len(args) > 1 else ""
    out = sh(f"{ADB} shell content query --uri content://sms/inbox --projection address:body:date 2>/dev/null", 120)
    parsed = []
    for r in out.split("\n"):
        m = re.match(r"Row: \d+ address=(.*?), body=(.*), date=(\d+)", r)
        if m:
            parsed.append((int(m.group(3)), m.group(1), m.group(2)))
    parsed.sort(reverse=True)
    print(f"# sms rows={len(parsed)}")
    import datetime
    c = 0
    for date, addr, body in parsed:
        if kw and kw.lower() not in body.lower():
            continue
        ts = datetime.datetime.fromtimestamp(date / 1000).strftime("%m-%d %H:%M:%S")
        print(f"{ts} {addr}: {body[:200]}")
        c += 1
        if c >= n:
            break

def cmd_alive(args):
    out = sh(f"{ADB} shell pidof dev.tripoder.engine")
    print(f"engine pids: {out.strip() or 'NONE'}")

def cmd_wait(args):
    t = float(args[0]) if args else 2
    time.sleep(t)
    print(f"WAIT {t}s")

DISMISS = ["同意并继续", "同意并登录", "同意", "仅在使用中允许", "始终允许", "允许", "我知道了",
           "我知道了", "跳过", "以后再说", "稍后再说", "暂不", "取消", "关闭", "开始使用", "进入应用", "确认"]

def cmd_dialog(args):
    """Auto-dismiss consent/permission dialogs. Prints what was tapped."""
    rounds = int(args[0]) if args else 5
    tapped = []
    for _ in range(rounds):
        ns = nodes(dump_xml())
        hit = None
        for word in DISMISS:
            for n in ns:
                v = (n["text"] or n["desc"] or "").strip()
                if v and (v == word or (len(v) <= 12 and word in v)) and (n["click"] or n["focus"]):
                    hit = (word, n)
                    break
            if hit:
                break
        if not hit:
            break
        sh(f"{ADB} shell input tap {hit[1]['cx']} {hit[1]['cy']}")
        tapped.append(hit[0])
        time.sleep(1.6)
    print(f"DIALOG tapped={tapped}")
    return 0

def cmd_crash(args):
    """Check guest crash evidence from logcat and resumed activity."""
    n = int(args[0]) if args else 400
    out = sh(f"{ADB} logcat -d -t {n} 2>/dev/null", 120)
    bad = [l for l in out.split("\n") if re.search(
        r"FATAL EXCEPTION|AndroidRuntime.*(E |Exception)|begin of crash|guest crash|DeadSystemException|ANR in", l)]
    print(f"CRASH_LINES={len(bad)}")
    for l in bad[:12]:
        print("  " + l[:200])
    out2 = sh(f"{ADB} shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -2")
    print("TOP: " + " | ".join(x.strip() for x in out2.strip().split("\n")))

def cmd_tabs(args):
    """Tap each bottom navigation node, dumping a compact summary per tab."""
    ns = nodes(dump_xml())
    h = max((n["b"][3] for n in ns), default=2624)
    bottom = {}
    for n in ns:
        if n["click"] and n["b"][1] > h - 260 and n["b"][3] - n["b"][1] < 260:
            label = (n["text"] or n["desc"] or "").strip()
            cx = n["cx"]
            if label and cx not in bottom:
                bottom[cx] = label
    targets = sorted(bottom.items())
    print(f"TABS={[t[1] for t in targets]}")
    for cx, label in targets:
        cy = h - 120
        sh(f"{ADB} shell input tap {cx} {cy}")
        time.sleep(3.2)
        ns2 = nodes(dump_xml())
        texts = []
        for n in ns2:
            v = (n["text"] or n["desc"] or "").strip()
            if v and v not in texts and len(v) < 40:
                texts.append(v)
        print(f"--[{label}] " + " / ".join(texts[:14]))
    out = sh(f"{ADB} shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -1")
    print("TOP: " + out.strip())

cmds = {"dump": cmd_dump, "tap": cmd_tap, "tapxy": cmd_tapxy, "swipe": cmd_swipe,
        "dialog": cmd_dialog, "crash": cmd_crash, "tabs": cmd_tabs,
        "scroll": cmd_scroll, "back": cmd_back, "shot": cmd_shot, "text": cmd_text,
        "sms": cmd_sms, "alive": cmd_alive, "wait": cmd_wait}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: t.py <" + "|".join(cmds) + ">")
        sys.exit(1)
    c = cmds[sys.argv[1]]
    sys.exit(c(sys.argv[2:]) or 0)
