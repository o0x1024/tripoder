#!/usr/bin/env python3
"""BOSS直聘业务链路实测：在线简历保存 → 搜索职位并投递 → 与 BOSS 沟通。

为什么单独写：登录闭环早已成立（v54，见 docs/evidence/bosszhipin-container-login-20261113/），
但目标要求「按 BOSS直聘 这样做」意味着业务链路本身要跑通，而业务链路每一步都可能撞到新的
容器门面缺陷（历史上登录那一刻就崩在广播门面）。所以每一步都要留 top/截图/崩溃归属，
而不是只记「点了、没报错」。

用法：
  python3 android/scripts/boss_business_chain.py            # 全链路
  python3 android/scripts/boss_business_chain.py --step cv  # 只跑简历
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time

DEVICE = os.environ.get("DEVICE", "d861a4fa")
PKG = "dev.tripoder.q6"
ACT = "dev.tripoder.hooklab.WorkbenchActivity"
TILE = "BOSS直聘"
OUT = "/tmp/boss_chain.json"


def adb(args: str, wait: float = 0.0) -> str:
    r = subprocess.run(["adb", "-s", DEVICE, "shell", args], capture_output=True, text=True)
    if wait:
        time.sleep(wait)
    return r.stdout


def dump() -> str:
    for _ in range(3):
        adb("uiautomator dump /sdcard/w.xml >/dev/null 2>&1")
        x = adb("cat /sdcard/w.xml 2>/dev/null")
        if "bounds=" in x:
            return x
        time.sleep(2)
    return ""


def nodes(x: str) -> list[dict]:
    return [dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1))) for m in re.finditer(r"<node ([^>]*)/>", x)]


def tap(n: dict, wait: float = 2.0) -> None:
    b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
    if len(b) == 4:
        adb("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2), wait)


def find(ns: list[dict], *wants: str):
    for w in wants:
        for a in ns:
            t = (a.get("text") or "").strip()
            d = (a.get("content-desc") or "").strip()
            if t == w or d == w:
                return a
    return None


def top() -> str:
    t = re.findall(r"topResumedActivity.*?(\S+/\S+)", adb("dumpsys activity activities"))
    return t[0] if t else "NONE"


def shot(tag: str) -> str:
    p = "/tmp/boss_%s.png" % tag
    adb("screencap -p /sdcard/boss_%s.png" % tag, 1)
    subprocess.run(["adb", "-s", DEVICE, "pull", "/sdcard/boss_%s.png" % tag, p], capture_output=True)
    return p


def texts(x: str, limit: int = 40) -> list[str]:
    out = []
    for a in nodes(x):
        t = (a.get("text") or "").strip()
        if t and len(t) < 30 and t not in out:
            out.append(t)
    return out[:limit]


def crash_owner() -> dict:
    buf = adb("logcat -b crash -d")
    if "FATAL EXCEPTION" not in buf:
        return {}
    frames = re.findall(r"at ([A-Za-z0-9_.$]+)", buf)
    return {"ours": [f for f in frames[:40] if f.startswith("dev.tripoder.")][:5],
            "frames": frames[:10],
            "exception": (re.findall(r"(java\.lang\.[A-Za-z]+Exception[^\n]*)", buf) or [""])[0][:160]}


def launch() -> dict:
    adb("am force-stop " + PKG, 3)
    adb("am start -n %s/%s" % (PKG, ACT), 6)
    for _ in range(6):
        x = dump()
        rl = find(nodes(x), "重新连接") if x else None
        if rl:
            tap(rl, 4)
            continue
        if x and ("个应用" in x or "添加应用" in x):
            break
        time.sleep(4)
    for _ in range(12):
        adb("input swipe 540 800 540 2000 250", 0.4)
    hit = None
    for _ in range(24):
        x = dump()
        hit = find(nodes(x), TILE)
        if hit:
            break
        adb("input swipe 540 2000 540 1200 400", 1.0)
    if not hit:
        return {"verdict": "TILE_NOT_FOUND"}
    adb("logcat -c")
    tap(hit, 2)
    for _ in range(12):
        time.sleep(4)
        if "TaskP" in top():
            break
    x = dump()
    return {"top": top(), "texts": texts(x), "png": shot("launch"),
            "crash": crash_owner(), "verdict": "LAUNCHED" if "TaskP" in top() else "NOT_TOP"}


def step_cv(rec: dict) -> None:
    """在线简历：我的 → 在线简历 → 进入编辑 → 保存。"""
    x = dump()
    mine = find(nodes(x), "我的", "我的简历", "我的 ")
    if not mine:
        a = find(nodes(x), "我的")
        mine = a
    if mine:
        tap(mine, 4)
    else:
        # 兜底：底栏第四格（BOSS直聘底栏固定 职位/公司/消息/我的）
        adb("input tap 1020 2520", 4)
    x = dump()
    cv = find(nodes(x), "在线简历", "我的简历", "简历")
    rec["cv_entry_found"] = bool(cv)
    if cv:
        tap(cv, 5)
    rec["cv_page"] = {"top": top(), "texts": texts(dump()), "png": shot("cv")}
    # 编辑并保存：找「编辑」进入后立刻保存返回，验证写路径通
    x = dump()
    e = find(nodes(x), "编辑", "编辑简历", "修改")
    if e:
        tap(e, 5)
        x2 = dump()
        rec["cv_editor"] = {"texts": texts(x2), "edit": sum(1 for a in nodes(x2) if "EditText" in (a.get("class") or "")), "png": shot("cv_edit")}
        s = find(nodes(x2), "保存", "完成", "提交")
        if s:
            tap(s, 5)
            rec["cv_saved"] = {"texts": texts(dump()), "png": shot("cv_saved")}
    rec["crash"] = crash_owner()


def main() -> None:
    steps = sys.argv[1:]
    rec: dict = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S")}
    rec["launch"] = launch()
    if rec["launch"].get("verdict") != "LAUNCHED":
        print(json.dumps(rec, ensure_ascii=False))
        return
    if not steps or "--step" not in steps:
        pass
    if "cv" in steps or not steps:
        step_cv(rec)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(rec, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: (v if k != "launch" else {kk: v[kk] for kk in ("top", "verdict")}) for k, v in rec.items()},
                     ensure_ascii=False)[:1500])


if __name__ == "__main__":
    main()
