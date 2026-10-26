#!/usr/bin/env python3
"""Render a compatibility campaign (artifacts/<run>/journal.jsonl) into a Markdown report.

The journal is the only source of truth: every row was written while the guest was
running, and every failure keeps its logcat excerpt, screenshot path and the engine's
own diagnostics next to the verdict.
"""
import argparse
import json
import os
import re
from collections import Counter, OrderedDict, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
NAMES = {
    "app.personal.linktools": "LinkTools",
    "bbc.mobile.news.ww": "BBC News",
    "cmb.pb": "招商银行",
    "cn.cyberIdentity.certification": "网络身份认证",
    "cn.wps.moffice_eng": "WPS Office",
    "cn.wps.moffice_eng.xiaomi.lite": "WPS Office 小米版",
    "com.achievo.vipshop": "唯品会",
    "com.alibaba.android.rimet": "钉钉",
    "com.alicloud.databox": "阿里云盘",
    "com.aliyun.tongyi": "通义",
    "com.android.calendar": "日历",
    "com.android.chrome": "Chrome",
    "com.android.deskclock": "时钟",
    "com.android.email": "邮件",
    "com.android.providers.downloads.ui": "下载管理",
    "com.android.soundrecorder": "录音机",
    "com.anthropic.claude": "Claude",
    "com.antutu.ABenchMark": "安兔兔评测",
    "com.autonavi.minimap": "高德地图",
    "com.baidu.BaiduMap": "百度地图",
    "com.baidu.input_mi": "百度输入法小米版",
    "com.baidu.netdisk": "百度网盘",
    "com.baidu.searchbox": "百度",
    "com.bocconnect.prd": "中银 Connect",
    "com.bochk.bn.aos": "中银香港",
    "com.bochk.bocpay": "BoC Pay",
    "com.bochk.igtb": "中银香港企业版",
    "com.bochk.mortgage.android.hk": "中银香港按揭",
    "com.booking": "Booking.com",
    "com.deepseek.chat": "DeepSeek",
    "com.dianping.v1": "大众点评",
    "com.dragon.read": "番茄免费小说",
    "com.duokan.phone.remotecontroller": "小米遥控器",
    "com.duokan.reader": "多看阅读",
    "com.duolingo": "多邻国",
    "com.eg.android.AlipayGphone": "支付宝",
    "com.fenxiang.storyline": "Storyline",
    "com.ghostlock.app": "GhostLock",
    "com.github.android": "GitHub",
    "com.google.android.apps.authenticator2": "Google 身份验证器",
    "com.google.android.apps.bard": "Gemini",
    "com.google.android.gm": "Gmail",
    "com.google.android.googlequicksearchbox": "Google",
    "com.google.android.youtube": "YouTube",
    "com.hpbr.bosszhipin": "BOSS 直聘",
    "com.hunantv.imgo.activity": "芒果 TV",
    "com.hundsun.winner.pazq": "平安证券",
    "com.hyper.gallery.plugin": "相册插件",
    "com.iflytek.inputmethod.miui": "讯飞输入法小米版",
    "com.instagram.android": "Instagram",
    "com.jingdong.app.mall": "京东",
    "com.kingsoft.xiezuo": "WPS 协作",
    "com.larus.nova": "豆包",
    "com.luna.music": "汽水音乐",
    "com.mfashiongallery.emag": "小米画报",
    "com.mi.health": "小米健康",
    "com.miHoYo.Yuanshen": "原神",
    "com.microsoft.office.officehubrow": "Microsoft 365",
    "com.mihoyo.hyperion": "米游社",
    "com.mipay.wallet": "小米钱包",
    "com.miui.calculator": "计算器",
    "com.miui.cleanmaster": "垃圾清理",
    "com.miui.compass": "指南针",
    "com.miui.gallery": "相册",
    "com.miui.huanji": "小米换机",
    "com.miui.mediaeditor": "视频编辑",
    "com.miui.newhome": "内容中心",
    "com.miui.newmidrive": "小米云盘",
    "com.miui.notes": "笔记",
    "com.miui.player": "音乐",
    "com.miui.screenrecorder": "屏幕录制",
    "com.miui.securitymanager": "安全中心",
    "com.miui.themestore": "主题商店",
    "com.miui.video": "小米视频",
    "com.miui.virtualsim": "全球上网",
    "com.miui.voiceassistProxy": "小爱同学",
    "com.miui.weather2": "天气",
    "com.netease.cloudmusic": "网易云音乐",
    "com.openai.chatgpt": "ChatGPT",
    "com.pangsheng.pshuochepiao.m": "火车票抢票",
    "com.phoenix.read": "疯读小说",
    "com.pingan.paces.ccms": "平安口袋银行",
    "com.qiyi.video": "爱奇艺",
    "com.quark.browser": "夸克浏览器",
    "com.sankuai.meituan": "美团",
    "com.sankuai.meituan.takeoutnew": "美团外卖",
    "com.sdu.didi.psnger": "滴滴出行",
    "com.sina.weibo": "微博",
    "com.smile.gifmaker": "快手",
    "com.ss.android.article.lite": "今日头条极速版",
    "com.ss.android.article.news": "今日头条",
    "com.ss.android.ugc.aweme": "抖音",
    "com.taobao.idlefish": "闲鱼",
    "com.taobao.taobao": "淘宝",
    "com.taou.maimai": "脉脉",
    "com.tencent.mm": "微信",
    "com.tencent.mobileqq": "QQ",
    "com.tencent.news": "腾讯新闻",
    "com.tencent.qqlive": "腾讯视频",
    "com.tencent.tmgp.WePop": "元梦之星",
    "com.tencent.tmgp.osgame": "腾讯手游（osgame）",
    "com.tencent.tmgp.sgame": "王者荣耀",
    "com.tongcheng.android": "同程旅行",
    "com.twitter.android": "X（Twitter）",
    "com.x8bit.bitwarden": "Bitwarden",
    "com.xiaomi.bestphotos": "最美证件照",
    "com.xiaomi.gamecenter": "小米游戏中心",
    "com.xiaomi.mibrain.speech": "小爱语音",
    "com.xiaomi.scanner": "扫一扫",
    "com.xiaomi.shop": "小米商城",
    "com.xiaomi.smarthome": "米家",
    "com.xiaomi.tinygame": "小米小游戏",
    "com.xiaomi.vipaccount": "小米社区",
    "com.xiaomi.youpin": "小米有品",
    "com.xingin.xhs": "小红书",
    "com.xs.fm": "番茄畅听",
    "com.xunmeng.pinduoduo": "拼多多",
    "com.zhihu.android": "知乎",
    "ctrip.android.view": "携程旅行",
    "org.telegram.messenger": "Telegram",
    "org.videolan.vlc": "VLC",
    "org.wikipedia": "Wikipedia",
    "tv.danmaku.bili": "哔哩哔哩",
}

# Ordered: the first pattern that matches the collected evidence names the root cause.
CATEGORIES = (
    ("ABI/Native", r"UnsatisfiedLinkError|dlopen failed|is 32-bit instead of 64-bit|has text relocations|library \"[^\"]+\" not found|CANNOT LINK EXECUTABLE"),
    ("类加载", r"ClassNotFoundException|NoClassDefFoundError|IncompatibleClassChangeError|IllegalAccessError|DexPathList|MultiDex"),
    ("系统 API", r"NoSuchMethodError|NoSuchFieldError|AbstractMethodError|VerifyError|UnsupportedOperationException:.*(API|SDK)|SecurityException:.*(hidden|non-SDK|blocked)"),
    ("权限/隔离", r"Permission Denial|requires (android\.)?permission|SecurityException|EPERM|EROFS|isolation"),
    ("签名/校验", r"PackageManager\$NameNotFoundException|INSTALL_FAILED|signature|INSTALL_PARSE_FAILED"),
    ("内存", r"OutOfMemoryError|Failed to allocate|lowmemorykiller|LMK"),
    ("Native 崩溃", r"Fatal signal|SIGSEGV|SIGABRT|SIGILL|backtrace"),
    ("ANR", r"ANR in |Application Not Responding"),
    ("进程退出", r"has died|Force finishing|Process .* has died|killProcess|SCHEDULED_CRASH"),
)

VERDICT_TEXT = {
    "PASS": "通过",
    "CRASH_ON_START": "启动崩溃",
    "PROCESS_GONE": "进程退出",
    "NO_FOREGROUND_SCREEN": "无界面",
    "ANR": "ANR",
    "START_REJECTED": "启动被拒",
    "SCOPE_FENCED": "运行时围栏",
    "TRANSPORT_ERROR": "通道错误",
}


def load(run_dir):
    rows = []
    with open(os.path.join(run_dir, "journal.jsonl"), "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def index(rows):
    facts, imported, launched = {}, {}, {}
    for row in rows:
        package = row.get("package")
        phase = row.get("phase")
        if phase == "inventory":
            facts[package] = row
        elif phase == "import":
            imported[package] = row
        elif phase == "launch":
            launched[package] = row
    return facts, imported, launched


def read_log(run_dir, package):
    path = os.path.join(run_dir, "logs", re.sub(r"[^A-Za-z0-9]+", "-", package).strip("-")[:40] + ".logcat.txt")
    if not os.path.exists(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def classify(row, log_text):
    haystack = "\n".join(["\n".join(row.get("fatals") or []), log_text, row.get("uiError") or "", (row.get("diagnostics") or "")[:2000]])
    for name, pattern in CATEGORIES:
        if re.search(pattern, haystack, re.IGNORECASE):
            return name
    return "未归类"


def first_signal(log_text, fatals):
    for line in (fatals or []):
        if any(key in line for key in ("FATAL EXCEPTION", "Fatal signal", "ANR in")):
            return line.strip()
    for line in log_text.splitlines():
        if any(key in line for key in ("Exception", "Error", "Fatal", "ANR", "died")):
            return line.strip()
    return ""


def human_bytes(count):
    return f"{count / 1048576:.0f} MB" if count else "—"


def build(run_dir, title):
    rows = load(run_dir)
    facts, imported, launched = index(rows)
    packages = sorted(set(facts) | set(imported) | set(launched), key=lambda p: NAMES.get(p, p))

    verdicts = Counter()
    failures = []
    table = []
    for package in packages:
        fact = facts.get(package, {})
        imp = imported.get(package, {})
        run = launched.get(package, {})
        verdict = run.get("verdict") or ("IMPORT_FAILED" if imp.get("state") not in (None, "SUCCEEDED") else "NOT_TESTED")
        verdicts[verdict] += 1
        log_text = read_log(run_dir, package) if verdict not in ("PASS",) else ""
        category = classify(run, log_text) if verdict not in ("PASS", "NOT_TESTED") else ""
        entry = {
            "package": package,
            "name": NAMES.get(package, package),
            "versionName": fact.get("versionName", ""),
            "versionCode": fact.get("versionCode", ""),
            "minSdk": fact.get("minSdk", ""),
            "targetSdk": fact.get("targetSdk", ""),
            "apkBytes": fact.get("apkBytes", 0),
            "splitCount": fact.get("splitCount", 0),
            "space": imp.get("space", ""),
            "importState": imp.get("state", ""),
            "generation": imp.get("generation", ""),
            "verdict": verdict,
            "category": category,
            "signal": first_signal(log_text, run.get("fatals")) if verdict not in ("PASS", "NOT_TESTED") else "",
            "seconds": run.get("seconds", ""),
            "uiTreeNodes": run.get("uiTreeNodes", ""),
            "foreground": run.get("foreground", ""),
            "screenshot": run.get("screenshot", ""),
            "importSeconds": imp.get("seconds", ""),
            "error": (imp.get("error") or run.get("error") or "")[:300],
            "uiError": (run.get("uiError") or "")[:200],
        }
        table.append(entry)
        if verdict != "PASS":
            failures.append(entry)

    total = len(table)
    passed = verdicts.get("PASS", 0)
    return {
        "title": title,
        "run": os.path.basename(run_dir.rstrip("/")),
        "total": total,
        "passed": passed,
        "verdicts": verdicts,
        "failures": failures,
        "table": table,
        "packages": packages,
    }


def markdown(report):
    lines = []
    add = lines.append
    total, passed = report["total"], report["passed"]
    rate = (passed / total * 100) if total else 0
    add(f"# {report['title']}")
    add("")
    add(f"- 数据集：`{report['run']}`")
    add(f"- 应用总数：**{total}**，首次启动可正常显示界面：**{passed}**（{rate:.1f}%）")
    add("")
    add("## 1. 结论")
    add("")
    add("| 判定 | 含义 | 数量 | 占比 |")
    add("|---|---|---:|---:|")
    for verdict, count in report["verdicts"].most_common():
        add(f"| `{verdict}` | {VERDICT_TEXT.get(verdict, verdict)} | {count} | {count / total * 100:.1f}% |")
    add("")
    add("## 2. 逐应用结果")
    add("")
    add("| 应用 | 包名 | 版本 | targetSdk | 安装包 | 导入 | 启动判定 | 证据 |")
    add("|---|---|---|---:|---:|---|---|---|")
    for row in sorted(report["table"], key=lambda r: (r["verdict"] != "PASS", r["name"])):
        evidence = row["signal"] or row["error"] or row["uiError"] or ""
        evidence = evidence.replace("|", "/")[:110]
        add(f"| {row['name']} | `{row['package']}` | {row['versionName']} | {row['targetSdk']} | {human_bytes(row['apkBytes'])} | "
            f"{'成功' if row['importState'] == 'SUCCEEDED' else (row['importState'] or '—')} | {row['verdict']} | {evidence} |")
    add("")
    add("## 3. 无法正常运行的应用")
    add("")
    by_category = defaultdict(list)
    for row in report["failures"]:
        by_category[row["category"] or "未归类"].append(row)
    for category in sorted(by_category):
        add(f"### 3.x {category}（{len(by_category[category])} 个）")
        add("")
        for row in by_category[category]:
            add(f"- **{row['name']}**（`{row['package']}`，{row['versionName']}）：{VERDICT_TEXT.get(row['verdict'], row['verdict'])}"
                + (f"，{row['signal']}" if row["signal"] else ""))
        add("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--out", default="")
    parser.add_argument("--title", default="Tripoder 全量应用兼容性测试报告")
    options = parser.parse_args()
    run_dir = os.path.join(ROOT, "artifacts", options.run)
    report = build(run_dir, options.title)
    target = options.out or os.path.join(run_dir, "report.md")
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(markdown(report))
    with open(os.path.join(run_dir, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump({k: (v if k not in ("verdicts",) else dict(v)) for k, v in report.items() if k != "table"}, handle, ensure_ascii=False, indent=1)
    print(f"report -> {target}  total={report['total']} passed={report['passed']}")


if __name__ == "__main__":
    main()
