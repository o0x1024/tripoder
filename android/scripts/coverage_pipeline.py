#!/usr/bin/env python3
"""幂等的「导入 → 启动 → 入口取证 → 落盘」批量推进流水线。

为什么需要它：覆盖目标是「当前手机里所有非系统自带的第三方应用」，逐个手点既不可复现也不可续跑。
本脚本把每个包压成一条 JSONL 记录，失败可按包名单包重跑；状态判定只写跑出来的事实。

用到的既有结论（改动这些行为前先读对应 gotcha）：
  * Workbench 的 tile 文案 ≠ 应用名（com.aliyun.tongyi → 「千问」），所以包名到 tile 的映射要落盘复用；
  * 新导入的包排在列表末尾，因此未知包可以用「列表里尚未映射的那个 tile」自动认领；
  * 「重新连接」按钮是 SUPERVISOR_INTERRUPTED 的唯一恢复手段，且批量循环里不能 force-stop q7；
  * 列表要 12 屏回顶再往下找，否则明明在上面的 tile 会报 TILE_NOT_FOUND；
  * uiautomator dump 在少数含 EditText 的页面会通过无障碍→剪贴板→包名/uid 校验杀死客体，
    所以 dump 前后都按包名数客体进程；但「被杀」的判定必须同时满足「dump 抓不到结构」与
    「进程确实消失」——只凭进程数变化会误报（旧口径只数 :slot，多进程应用的主进程叫包名本身）。

用法：
  python3 android/scripts/coverage_pipeline.py com.autonavi.minimap com.instagram.android ...
  python3 android/scripts/coverage_pipeline.py --no-import com.zhihu.android      # 只跑启动+入口
  python3 android/scripts/coverage_pipeline.py --report                            # 只打印汇总
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
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs/evidence/third-party-batch-advance-20261113/coverage.jsonl")
LABELS = os.path.join(ROOT, "docs/evidence/third-party-batch-advance-20261113/tile-labels.json")

CONSENT_CANDIDATES = ["同意并继续", "同意", "接受", "我知道了", "全部同意", "跳过", "开始使用", "进入应用"]
LOGIN_HINT = re.compile(r"登录|登陆|手机号|验证码|一键|密码|注册|账号|帐号")


def adb(args: str, wait: float = 0.0) -> str:
    r = subprocess.run(["adb", "-s", DEVICE, "shell", args], capture_output=True, text=True)
    if wait:
        time.sleep(wait)
    return r.stdout


def pull(remote: str, local: str) -> bool:
    r = subprocess.run(["adb", "-s", DEVICE, "pull", remote, local], capture_output=True, text=True)
    return r.returncode == 0


def dump() -> str:
    """返回 uiautomator XML；判据必须是小写 bounds=（大写会让 dump 永远「失败」）。"""
    for _ in range(3):
        adb("uiautomator dump /sdcard/w.xml >/dev/null 2>&1")
        x = adb("cat /sdcard/w.xml 2>/dev/null")
        if "bounds=" in x:
            return x
        time.sleep(2)
    return ""


def nodes(x: str) -> list[dict]:
    return [dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1))) for m in re.finditer(r"<node ([^>]*)/>", x)]


def tap(node: dict, wait: float = 2.0) -> bool:
    n = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(n) != 4:
        return False
    adb("input tap %d %d" % ((n[0] + n[2]) // 2, (n[1] + n[3]) // 2), wait)
    return True


def find(ns: list[dict], pred):
    for a in ns:
        if pred((a.get("text") or "").strip(), (a.get("content-desc") or "").strip()):
            return a
    return None


def top_activity() -> str:
    out = adb("dumpsys activity activities")
    t = re.findall(r"topResumedActivity.*?(\S+/\S+)", out)
    return t[0] if t else "NONE"


def guest_procs(pkg: str | None = None) -> int:
    """数客体进程；给了 pkg 就按该包名数。

    为什么必须能按包名数：容器里客体进程名有两种形状——应用包名本身（实测 com.sankuai.meituan、
    com.sankuai.meituan:PinProcess、com.sankuai.meituan:dppushservice）与槽位名
    dev.tripoder.q6:slotN。只数 :slot 会把「多进程应用的真正宿主进程」漏掉，于是 dump 前后两个数
    （前 = 上一包残留的 slot，后 = 0）会被误读成「dump 把客体打死了」——美团/支付宝两条
    GUEST_KILLED_BY_DUMP 假阴性正是这个测量口径错误，与 dump 无关（两者的 top 仍是 TaskP*、
    dump 也真的拿到了页面文本）。
    """
    out = adb("ps -A")
    if pkg:
        return len([l for l in out.splitlines() if pkg in l])
    return len([l for l in out.splitlines() if PKG + ":slot" in l])


def counters() -> dict:
    log = adb("logcat -d")
    crash = adb("logcat -b crash -d")
    return {
        "anr": len(re.findall(r"ANR in dev\.tripoder", log)),
        "admission": len(re.findall(r"admission-blocked", log)),
        "fatal": len(re.findall(r"FATAL EXCEPTION", crash)),
        "absent": len(re.findall(r"TripoderStarts ABSENT", log)),
        "job_err": len(re.findall(r"JOB_MUTATION_IN_PROGRESS|JOB_CAPACITY", log)),
    }


def reconnect() -> bool:
    """重开 Workbench 直到应用列表真的渲染（判据：出现「个应用」或「添加应用」）。不 force-stop q7，
    否则引擎进入 SUPERVISOR_INTERRUPTED 且只接受显式 retry。"""
    adb("am force-stop " + PKG, 3)
    adb("am force-stop " + PKG, 3)
    adb("am start -n %s/%s" % (PKG, ACT), 6)
    for _ in range(8):
        x = dump()
        rl = find(nodes(x), lambda t, d: "重新连接" in t) if x else None
        if rl:
            tap(rl, 4)
            continue
        if x and ("个应用" in x or "添加应用" in x):
            return True
        adb("am start -n %s/%s" % (PKG, ACT))
        time.sleep(5)
    return False


def to_top():
    for _ in range(12):
        adb("input swipe 540 800 540 2000 250", 0.5)


def tile_texts() -> list[str]:
    """回顶后逐屏收集 tile 文案（按屏幕顺序，末尾是最新导入的）。"""
    to_top()
    seen: list[str] = []
    for _ in range(24):
        x = dump()
        if not x:
            break
        if "运行引擎没有连接" in x:
            reconnect()
            to_top()
            continue
        for a in nodes(x):
            t = (a.get("text") or "").strip()
            if not t or len(t) > 18:
                continue
            if t in ("应用", "设置", "添加应用", "多选", "更多", "清空", "全选", "重新连接", "运行引擎没有连接"):
                continue
            if t in seen:
                continue
            seen.append(t)
        adb("input swipe 540 2000 540 1200 400", 1.2)
    return seen


CHROME_LABELS = {
    "应用", "设置", "添加应用", "多选", "全部空间 ▾", "Tripoder", "更多", "清空", "全选",
    "重新连接", "运行引擎没有连接", "选择要添加的应用", "添加所选 1 个应用", "搜索",
    "取消", "完成", "已在此空间", "批量选择应用",
}


def is_tile_label(t: str) -> bool:
    if not t or len(t) > 12 or t in CHROME_LABELS:
        return False
    if re.search(r"个应用|已选|添加所选|轻点运行|长按管理|处理中|结果未知", t):
        return False
    return True


def tile_texts_bottom() -> list[str]:
    """新导入的包排在列表末尾，所以只翻到底部三屏收集文案即可认领，避免全表扫描（24 屏）的代价。"""
    for _ in range(16):
        adb("input swipe 540 2000 540 1200 250", 0.45)
    seen: list[str] = []
    for _ in range(3):
        x = dump()
        if not x:
            break
        for a in nodes(x):
            t = (a.get("text") or "").strip()
            if t and t not in seen and is_tile_label(t):
                seen.append(t)
        adb("input swipe 540 2000 540 1200 350", 1.0)
    return seen


def load_labels() -> dict:
    if os.path.exists(LABELS):
        with open(LABELS, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_labels(m: dict) -> None:
    with open(LABELS, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1, sort_keys=True)


def ensure_import(pkgs: list[str]) -> dict:
    """走已验证的 import_apps.sh（含清空/已在此空间/只提交 1 个小节）。返回 pkg->stdout。"""
    out = {}
    for p in pkgs:
        r = subprocess.run(["bash", "android/scripts/import_apps.sh", p], cwd=ROOT,
                           capture_output=True, text=True)
        out[p] = (r.stdout + r.stderr).strip().splitlines()[-3:]
    return out


def snapshot(label: str, pkg: str = "") -> dict:
    """截图 + 结构。dump 在少数含 EditText 的页面确实会杀客体，所以 dump 前后都按包名数进程。

    判据修正（batch30）：「被杀」必须同时满足「dump 抓不到结构」与「进程确实消失」两个条件。
    只凭进程数变化判会误报——旧口径数的是 dev.tripoder.q6:slot，而多进程应用的主进程叫包名本身，
    于是上一包残留的 slot 归零就被记成「本包被 dump 打死」（美团/支付宝各一条假阴性）。
    """
    before = guest_procs(pkg or None)
    adb("screencap -p /sdcard/%s.png" % label, 1)
    pull("/sdcard/%s.png" % label, "/tmp/%s.png" % label)
    x = dump()
    after = guest_procs(pkg or None)
    ns = nodes(x)
    texts, entries = [], []
    for a in ns:
        t = (a.get("text") or "").strip()
        if not t or len(t) > 30 or t in texts:
            continue
        texts.append(t)
    for a in ns:
        t = (a.get("text") or "").strip()
        if t and LOGIN_HINT.search(t) and len(t) <= 30:
            entries.append({"t": t, "b": a.get("bounds")})
    return {
        "png": "/tmp/%s.png" % label,
        "top": top_activity(),
        "nodes": len(ns),
        "edit": sum(1 for a in ns if "EditText" in (a.get("class") or "")),
        "webview": sum(1 for a in ns if "WebView" in (a.get("class") or "")),
        "texts": texts[:40],
        "entries": entries[:10],
        "guest_procs": [before, after],
        # 只有「结构没抓到」+「进程没了」同时成立，才有资格说 dump 把客体打死了。
        "dump_unavailable": (not x) and before > 0 and after == 0,
        "dump_empty": not x,
    }


CONSENT_WALL_WORDS = ("同意并继续", "全部同意", "接受并继续", "我已阅读并同意", "不同意")


def crash_attribution() -> dict:
    """取本包崩溃栈的前几帧并判定归属。

    为什么必须记：百度首次跑出 fatal=1 且 top 回落 Workbench，但「已到页面」和「崩了」在
    我的第一版记录里长得一样（entries 空、无同意文案）。归属判据沿用既有口径：
    栈里出现 dev.tripoder.runtime.* 帧 = 容器门面缺陷；没有 = 客体自身问题。
    """
    buf = adb("logcat -b crash -d")
    if "FATAL EXCEPTION" not in buf:
        return {}
    frames = re.findall(r"at ([A-Za-z0-9_.$]+)", buf)
    ours = [f for f in frames[:40] if f.startswith("dev.tripoder.")]
    head = re.findall(r"(FATAL EXCEPTION.*|java\.lang\.[A-Za-z]+Exception.*|Caused by: .*)", buf)
    return {
        "fatal_lines": len(buf.splitlines()),
        "exception": (head[0].strip() if head else "")[:200],
        "ours_frames": ours[:5],
        "owner": "CONTAINER" if ours else "GUEST",
        "frames": frames[:12],
    }


def classify(rec: dict) -> str:
    """判据顺序即事实优先级：先看崩没崩，再看「有没有登录入口」，最后才看同意层。

    反例驱动的写法：腾讯视频首页正文自带「同意《用户服务协议》及《基本功能隐私保护指引》」，
    按「出现『同意』就算同意墙」判，会把已经到首页的应用记成 CONSENT_UNRESOLVED。
    """
    s = rec.get("page") or {}
    if rec.get("launch") in ("ENGINE_DOWN", "TILE_NOT_FOUND", "NO_TILE_MAPPING"):
        return rec["launch"]
    crash = rec.get("crash") or {}
    if crash:
        return "CRASH_CONTAINER" if crash.get("owner") == "CONTAINER" else "CRASH_GUEST"
    # 启动成功拿到了 TaskP*，但取证时 top 已不再是 TaskP*：应用在启动之后自己退了（或被杀），
    # 这类记录早期会被写成 HOME_NO_ACCOUNT_UI（腾讯新闻 TaskP1$F1M1C4 → 取证时 Workbench 就是）。
    if "TaskP" in str(rec.get("launch", "")) and "TaskP" not in str(s.get("top", "")):
        return "EXITED_AFTER_LAUNCH"
    if s.get("dump_unavailable"):
        return "DUMP_UNAVAILABLE"
    if not str(s.get("top", "")).startswith("TaskP") and "TaskP" not in str(rec.get("launch", "")):
        return "NOT_TOP"
    texts = s.get("texts") or []
    txt = " ".join(texts)
    if s.get("entries"):
        return "LOGIN_ENTRY"
    if any(w in txt for w in CONSENT_WALL_WORDS):
        return "CONSENT_UNRESOLVED"
    if s.get("nodes", 0) == 0:
        return "PAGE_EMPTY_USE_SHOT"
    if LOGIN_HINT.search(txt):
        return "LOGIN_PROMPT"
    return "HOME_NO_ACCOUNT_UI"


def run_one(pkg: str, labels: dict, do_import: bool) -> dict:
    rec = {"pkg": pkg, "ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "build": os.environ.get("BUILD", "")}
    tile = labels.get(pkg)
    if do_import:
        rec["import"] = ensure_import([pkg])[pkg]
        # import_apps.sh 结束时仍停在「添加应用」页，那里的按钮文案（「选择要添加的应用」）会被误认成
        # tile —— 首轮 com.tencent.qqlive 就这么写错了映射。所以先回列表页，再扫底部。
        reconnect()
        tiles = tile_texts_bottom()
        if not tile and tiles:
            mapped = set(labels.values())
            fresh = [t for t in tiles if t not in mapped]
            if fresh:
                tile = fresh[-1]          # 新导入的排在列表末尾
                labels[pkg] = tile
                save_labels(labels)
                rec["tile_discovered"] = True
    if not tile:
        rec["verdict"] = "NO_TILE_MAPPING"
        return rec
    rec["tile"] = tile

    if not reconnect():
        rec["verdict"] = "ENGINE_DOWN"
        return rec
    to_top()
    hit = None
    for _ in range(24):
        x = dump()
        if x and "运行引擎没有连接" in x:
            reconnect()
            to_top()
            continue
        hit = find(nodes(x), lambda t, d: t == tile)
        if hit:
            break
        adb("input swipe 540 2000 540 1200 400", 1.0)
    if not hit:
        rec["verdict"] = "TILE_NOT_FOUND"
        return rec

    adb("logcat -c")
    tap(hit, 2)
    launched = "NONE"
    for _ in range(12):
        time.sleep(4)
        launched = top_activity()
        if "TaskP" in launched:
            break
    # 「应用没有启动 / 操作没有完成（APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED）」是 UI 拒绘：
    # 该包还挂着一条未确认的 Activity 记录。恢复配方是同时 force-stop 两个宿主进程再重连两次，
    # 否则这个包会被永久拦住（爱奇艺连跑两次 NOT_TOP 就是这么来的）。
    if "TaskP" not in launched:
        x = dump()
        if x and "应用没有启动" in x:
            dlg = find(nodes(x), lambda t, d: t == "知道了")
            if dlg:
                tap(dlg, 2)
            rec["recovered_from"] = "ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED"
            adb("am force-stop " + PKG, 3)
            adb("am force-stop dev.tripoder.q7", 3)
            if reconnect():
                to_top()
                hit = None
                for _ in range(24):
                    x2 = dump()
                    hit = find(nodes(x2), lambda t, d: t == tile)
                    if hit:
                        break
                    adb("input swipe 540 2000 540 1200 400", 1.0)
                if hit:
                    adb("logcat -c")
                    tap(hit, 2)
                    for _ in range(12):
                        time.sleep(4)
                        launched = top_activity()
                        if "TaskP" in launched:
                            break
    rec["launch"] = launched
    rec["counters"] = counters()

    # 同意层：按候选优先级点，最多 4 轮；每轮都重新 dump 拿到当前轴
    taps = 0
    for _ in range(4):
        x = dump()
        if not x:
            break
        a = None
        for c in CONSENT_CANDIDATES:
            a = find(nodes(x), lambda t, d, c=c: t == c)
            if a:
                break
        if not a:
            break
        tap(a, 6)
        taps += 1
    rec["consent_taps"] = taps

    label = re.sub(r"[^0-9A-Za-z\u4e00-\u9fa5]", "_", pkg) + "_" + time.strftime("%H%M%S")
    rec["page"] = snapshot(label, pkg)
    rec["counters_after"] = counters()
    rec["crash"] = crash_attribution()
    rec["verdict"] = classify(rec)
    return rec


def report() -> None:
    rows = []
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    latest = {}
    for r in rows:
        latest[r["pkg"]] = r
    from collections import Counter
    c = Counter(r["verdict"] for r in latest.values())
    print(json.dumps({"packages": len(latest), "verdicts": dict(c)}, ensure_ascii=False, indent=1))
    for p, r in sorted(latest.items()):
        print("%-38s %-22s %s" % (p, r["verdict"], (r.get("page") or {}).get("top", r.get("launch", ""))))


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if "--report" in flags:
        report()
        return
    labels = load_labels()
    do_import = "--no-import" not in flags
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    for pkg in args:
        try:
            rec = run_one(pkg, labels, do_import)
        except Exception as e:  # 单包失败不中断整批
            rec = {"pkg": pkg, "ts": time.strftime("%Y-%m-%dT%H:%M:%S"), "verdict": "PIPELINE_ERROR", "error": repr(e)}
        with open(OUT, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(json.dumps({k: rec.get(k) for k in ("pkg", "tile", "launch", "verdict", "consent_taps")},
                         ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
