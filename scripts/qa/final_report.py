#!/usr/bin/env python3
"""Render the final full-device compatibility report.

Inputs are evidence, never conclusions: the first-pass journal, the optional
second-attempt journal, and the per-application log files.  The report generator
does not touch the device.
"""
import argparse
import collections
import datetime as dt
import importlib.util
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

_spec = importlib.util.spec_from_file_location("compat_report", os.path.join(os.path.dirname(__file__), "compat_report.py"))
CR = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(CR)
NAMES = CR.NAMES
VERDICT_TEXT = CR.VERDICT_TEXT
LAUNCHERS = {}

# Attribution buckets used only for the executive summary; per-application evidence is
# always shown verbatim in sections 4/5 so the reader can disagree with the bucket.
ENGINE_CATS = {"引擎 worker 中断", "引擎未连接", "恢复围栏", "安装产物不一致", "槽位进程崩溃", "activity-affinity 容量"}
APP_CATS = {"应用崩溃（无归因栈）", "应用代码/split 缺失", "无界面", "ANR", "进程退出"}


def load_launchers(run_dir):
    """Optional `launcher.json`: package -> number of MAIN/LAUNCHER activities.

    Written by scripts/qa/launcher_probe.py.  When absent the report says "unknown"
    instead of guessing from import failures.
    """
    path = os.path.join(run_dir, "launcher.json")
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError):
        return {}
    return {pkg: int(value) for pkg, value in data.items()}


def load_rows(path):
    if not path or not os.path.exists(path):
        return []
    rows = []
    with open(path, "r", encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            line = line.strip()
            if line:
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                row["_ref"] = f"{os.path.basename(path)}:{lineno}"
                rows.append(row)
    return rows


def index(rows):
    facts, imports, launches = {}, {}, {}
    for row in rows:
        package, phase = row.get("package"), row.get("phase")
        if not package:
            continue
        if phase == "inventory":
            facts[package] = row
        elif phase == "import":
            imports[package] = row
        elif phase == "launch":
            launches[package] = row
    return facts, imports, launches


def read_log(run_dir, package):
    path = os.path.join(run_dir, "logs", re.sub(r"[^A-Za-z0-9]+", "-", package).strip("-")[:40] + ".logcat.txt")
    if not os.path.exists(path):
        return "", ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read(), path


def own_log_ref(path, package, pid):
    """Return (fatal_ref, fatal_text, cause_ref) for a crash block that belongs to the guest."""
    if not path or not os.path.exists(path):
        return "", "", ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.read().splitlines()
    for idx, line in enumerate(lines):
        if "FATAL EXCEPTION" not in line and "Fatal signal" not in line and "ANR in " not in line:
            continue
        block = lines[idx:idx + 120]
        joined = "\n".join(block)
        match = re.search(r"Process: ([A-Za-z0-9_.:]+)", joined)
        process = match.group(1) if match else ""
        own = (f"ANR in {package}" in line or f">>> {package} <<<" in joined or process == package
               or (process.startswith("android.process.") and pid and f" {pid} " in joined))
        if not own:
            continue
        cause_at = next((i for i, text in enumerate(block) if "Caused by:" in text), -1)
        cause_ref = f"logs/{os.path.basename(path)}:{idx + cause_at + 1}" if cause_at >= 0 else ""
        return f"logs/{os.path.basename(path)}:{idx + 1}", line.strip()[:200], cause_ref
    return "", "", ""


def attribute_blocks(log_text, package, pid):
    """Split crash blocks into (guest's own blocks, engine slot blocks).

    The capture window can contain crashes from other processes and Android reuses pids,
    so a block is only used as evidence when it names the guest, when it is an ANR for the
    guest, or when it runs in a shared `android.process.*` process with the guest's pid.
    """
    lines = log_text.splitlines()
    own, slot = [], []
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION" not in line and "Fatal signal" not in line and "ANR in " not in line:
            continue
        block = lines[index:index + 120]
        joined = "\n".join(block)
        match = re.search(r"Process: ([A-Za-z0-9_.:]+)", joined)
        process = match.group(1) if match else ""
        if f"ANR in {package}" in line or f">>> {package} <<<" in joined:
            own.append(joined)
        elif process == package:
            own.append(joined)
        elif process.startswith("android.process.") and pid and f" {pid} " in joined:
            own.append(joined)
        elif process.startswith("dev.tripoder.engine"):
            slot.append(joined)
    return own, slot


def own_fatal_blocks(log_text, package, pid):
    return attribute_blocks(log_text, package, pid)[0]


IMPORT_RULES = (
    ("包可见性", "NameNotFoundException", "引擎 `<queries>` 只声明 MAIN/LAUNCHER，Android 11+ 包可见性过滤使 `getApplicationInfo()` 看不到没有启动入口的包。"),
    ("原生库路径规则", "ABI_OR_LIBRARY_UNSUPPORTED", "`ArtifactSetZip` 要求 `lib/<已识别ABI>/lib*.so`；APK 里的 `lib/Windows/...dll`、`lib/arm64-v8a/test.so` 被整包拒绝。"),
    ("uses-split 依赖", "BASE_DEPENDENCY_INVALID", "base manifest 声明了 `<uses-split>`，运行时对任何 split 依赖都拒绝整包。"),
    ("组件重复声明", "COMPONENT_COLLISION", "manifest 同名的非 provider 组件声明了不同属性；Android 取最后一条，运行时直接拒绝。"),
    ("上传大小上限", "UPLOAD_FILE_SIZE", "单文件上限 1 GiB、总量上限 4 GiB；该 APK 为 1.5 GB。"),
    ("恢复态未决", "OPERATION_QUERY_REQUIRED", "上一次操作处于 UNKNOWN，恢复门拒绝同一 operationId；换新 operationId/空间后可成功。"),
    ("空间/引擎错误", "SPACE_CREATE", "创建隔离空间失败。"),
)

LAUNCH_RULES = (
    ("启动模式未建模", "ACTIVITY_LAUNCH_MODE_UNSUPPORTED", "`RuntimeComponents` 只接受 launchMode 0–3，Chrome 使用 `singleInstancePerTask`(4)。"),
    ("activity-alias 容量", "ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED", "`RuntimeActivitySlots.ALIASES=2`，声明超过 2 个 alias 的应用无法启动。"),
    ("activity-affinity 容量", "ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED", "`RuntimeActivitySlots.AFFINITIES=8` 是跨空间全局唯一的 SQLite 租赁表；导入的应用越多，未释放的 affinity 槽位越多。已排除残留 guest 进程（targets=0）并在全新 qa3 空间复现，属于引擎容量上限，不是单个应用不兼容。"),
    ("通知 API 缺口", "NOTIFICATION_CAPABILITY_UNSUPPORTED", "`RuntimeSelfHookManagerExtras` 未实现 `setNotificationDelegate`。"),
    ("启动后身份/ClassLoader 改写", "APPLICATION_POST_ATTACH_IDENTITY", "应用在 attach 后改写 ClassLoader（WPS 插件化、芒果 TV），触发 native L1 身份不变量。"),
    ("启动后身份/ClassLoader 改写", "APPLICATION_OWNERSHIP_VIOLATION", "应用在 attach 后改写 ClassLoader（WPS 插件化、芒果 TV），触发 native L1 身份不变量。"),
    ("文件权限/路径映射", "lib-compressed write permission", "运行时把 guest 的 lib 目录映射到宿主 `/data/user/0/<pkg>/lib-compressed`，删除写权限时失败。"),
    ("Tinker/电话 API", "TinkerRuntimeException", "Tinker 初始化调用 `getDataNetworkTypeForSubscriber`，运行时电话能力未覆盖。"),
    ("Intent/URI 契约缺口", "URI_GRANT_NOT_IMPLEMENTED", "guest 启动请求携带 URI grant，运行时未实现该 grant 语义。"),
    ("安装产物不一致", "ARTIFACT_FILE_SET_MISMATCH", "安装代与当前空间的 artifact file set 不一致；重新导入到新空间可消除。"),
    ("物理应用身份变化", "PHYSICAL_APPLICATION_INITIAL_CHANGED", "重新导入后物理 Application 的初始化代与旧进程不一致，运行时拒绝复用旧进程；需要先停止旧进程再启动。"),
    ("恢复围栏", "RECOVERY_SCOPE_UNKNOWN", "该空间存在 UNKNOWN 操作，整个空间被围栏；换新空间复测。"),
    ("引擎 worker 中断", "DeadObjectException", "`:authority` binder 或 slot worker 死亡，属于引擎稳定性问题，需复测。"),
    ("引擎 worker 中断", "WORKER_DIED", "slot worker 在启动请求处理中死亡，属于引擎稳定性问题，需复测。"),
    ("引擎未连接", "ENGINE_NOT_CONNECTED", "启动瞬间引擎连接断开，属于传输/稳定性问题，需复测。"),
    ("OEM 框架依赖 (MIUIX)", "miuix.", "预装 data-app 依赖 HyperOS 的 `miuix` 框架类/资源（如 `SymbolDrawable`、`miuix_appcompat_*`），guest 未注入 OEM 框架。"),
    ("OEM 框架依赖 (MIUIX)", "SymbolDrawable", "预装 data-app 依赖 HyperOS 的 `miuix` 框架类/资源（如 `SymbolDrawable`、`miuix_appcompat_*`），guest 未注入 OEM 框架。"),
    ("可选系统库缺失", "NoClassDefFoundError", "guest 依赖 `org.apache.http.legacy`、`commons-logging` 等可选库，类加载器未注入对应 jar。"),
    ("可选系统库缺失", "org.apache.commons.logging", "guest 依赖 `commons-logging`，类加载器未注入对应 jar。"),
    ("可选系统库缺失", "org.apache.http", "guest 依赖 `org.apache.http.legacy`，类加载器未注入对应 jar。"),
    ("应用代码/split 缺失", "ClassNotFoundException", "APK 的 base 中缺少启动所需的类（动态功能/split 未随包提供，或被厂商裁剪）。"),
    ("应用代码/split 缺失", "NoClassDefFoundError: com.", "APK 的 base 中缺少启动所需的类（动态功能/split 未随包提供，或被厂商裁剪）。"),
    ("运行时初始化缺陷", "ContextImpl.getTheme", "guest context 的 `ApplicationInfo` 为空，`getTheme()` 直接 NPE；属于运行时引导缺陷。"),
    ("槽位进程崩溃", "dev.tripoder.engine:slot", "`dev.tripoder.engine:slotN` 发生未捕获异常或被应用终止，需完整 tombstone/栈。"),
    ("无首屏", "NO_FOREGROUND_SCREEN", "进程存活但 18s 内没有自己的 Activity 进入前台，也没有可用 UI 树。"),
    ("ANR", "ANR in ", "启动窗口内主线程无响应。"),
    ("进程退出", "PROCESS_GONE", "进程在稳定窗口内消失，且日志中没有可归属该包的 Java fatal。"),
)

RECOMMENDATIONS = {
    "包可见性": "在 `android/engine/AndroidManifest.xml` 增加 `<uses-permission android:name=\"android.permission.QUERY_ALL_PACKAGES\"/>`（或为这批组件逐项声明 `<queries><package .../></queries>`），让引擎的 `PackageManager` 能看到这些 data-app 组件。",
    "原生库路径规则": "放宽 `ArtifactSetZip.scan()`：只对已知 ABI 目录（arm64-v8a 等）做 `.so` 认证；未知 `lib/...` 目录按不透明资源保留（Android 本身不会解压），并去掉文件名必须以 `lib` 开头的限制。可恢复百度网盘、平安证券。",
    "uses-split 依赖": "在 `ArtifactSet` 建模 `usesSplits`：split 未随包提供时按“可选/按需”处理，不因 base 的 `<uses-split>` 拒绝整包。可恢复快手。",
    "组件重复声明": "在 `ArtifactSet` 组件合并循环实现 Android 的 take-last 语义（同名非 provider 后者覆盖前者），并为每个组件保留独立的 slot 映射。可恢复腾讯视频、火车票。",
    "上传大小上限": "把 `WorkbenchAgentImports.begin()` 的单文件上限从 1 GiB 提高（设备可用空间允许 2 GiB），或改为分片直传/边读边装；可恢复 1.5 GB 的《和平精英》WePop。",
    "恢复态未决": "无需改代码；为新一次导入分配新的 operationId 与新空间即可。本次 `com.luna.music` 已用该方式复测成功。",
    "启动模式未建模": "扩展 `RuntimeComponents` 接受 `singleInstancePerTask`(4)，并在 `RuntimeActivitySlots` 中为它分配独立任务根。",
    "activity-alias 容量": "把 `RuntimeActivitySlots.ALIASES` 从 2 提升为按 manifest 动态分配（或至少 16），并保持 alias→target 的一一映射。",
    "activity-affinity 容量": "把 `RuntimeActivitySlots.AFFINITIES` 从 8 提升为按 space/uidDomain 分区的动态池（或至少 256），并在 space 删除/安装移除时调用 `ActivityAffinityLedger.releaseUnused()`。8 个全局 slot 无法支撑多应用迁移；即使 `application_query targets` 为空，旧 `activity_components` 仍会占着 lease。",
    "通知 API 缺口": "在 `RuntimeSelfHookManagerExtras` 补齐 `setNotificationDelegate(String)` 等通知代理方法，至少提供无操作实现，避免启动期直接抛异常。",
    "启动后身份/ClassLoader 改写": "WPS 在 `Application.attach` 后替换 `mClassLoader`，芒果 TV 触发 ownership 校验；需要在 native L1 允许受控的 ClassLoader 替换，或把插件 ClassLoader 建模为子加载器。",
    "文件权限/路径映射": "Instagram 的 `lib-compressed` 目录不应映射到宿主真实 `/data/user/0/<pkg>`；修正 `RuntimeStorageFacade` 的写权限回收路径。",
    "Tinker/电话 API": "补齐 `TelephonyManager.getDataNetworkTypeForSubscriber` 的 guest facade（返回宿主网络类型或 UNKNOWN），可让爱奇艺越过 Tinker 初始化。",
    "可选系统库缺失": "guest manifest 声明 `uses-library org.apache.http.legacy`（required=false）时，把 `/system/framework/org.apache.http.legacy.jar` 加入 guest ClassLoader；`commons-logging` 同理提供兼容 jar。",
    "OEM 框架依赖 (MIUIX)": "这类 `/product/data-app` 预装应用不是自包含 APK：把 HyperOS 的 `miuix` 框架 jar/资源注入 guest，或明确将“预装 OEM 应用”排除在可迁移集合之外；它们不适合作为第三方应用兼容性样本。",
    "Intent/URI 契约缺口": "实现 `Intent` 的 URI grant 传递（ClipData/FLAG_GRANT_*），或对该类启动降级为无 grant 并记录告警。",
    "安装产物不一致": "启动前校验安装代与 artifact file set；不一致时重装到新空间，并给出 `ARTIFACT_STALE` 可重试错误，而不是把它当作 app 失败。",
    "物理应用身份变化": "在 `process_start` 前检测 installGeneration 是否变化；变化时先 `application_stop` 旧进程并等待死亡，或让启动路径自动替换旧物理 Application，避免把“旧代未清”记成应用失败。",
    "运行时初始化缺陷": "修复 guest `ContextImpl` 的 `ApplicationInfo` 绑定（为每个 guest 提供非空 `ApplicationInfo`，补 `targetSdkVersion`），避免 `getTheme()`/资源解析阶段 NPE。",
    "引擎 worker 中断": "收集 `:authority`/slot 的 tombstone/Java 栈；对 DeadObjectException/WORKER_DIED 做一次自动重连+重试，而不是把它记成应用失败。",
    "引擎未连接": "同上；启动前做一次 `runtime_query` 健康检查，失败则重启 authority 再重试。",
    "恢复围栏": "为每个应用使用全新空间（`qa2-*`）或在启动前清理该空间的 UNKNOWN 操作；不要把围栏记成应用不兼容。",
    "槽位进程崩溃": "抓取 `dev.tripoder.engine:slot0` 的完整 Java 栈与 tombstone；对加固 SDK 的主动杀进程，需要 L13 的隔离/信号处理。",
    "无首屏": "延长窗口并抓取 `process_inspect diagnostics`，区分“慢启动/Splash”与“Activity 未建模”。",
    "ANR": "抓取 ANR traces，判断是主线程等 Binder 还是 guest 初始化阻塞。",
    "进程退出": "抓取 `am_kill`/`lmkd`/tombstone，区分 LMK、加固 SDK 自杀与运行时主动结束。",
    "应用代码/split 缺失": "先确认设备上的安装是否完整（`pm path` 对比 split 列表）；若 base 本身不含启动类，需要连同该应用的 dynamic feature/split 一起导入，而不是把它记成运行时缺陷。",
    "其他启动错误": "用 `application_query(targets)` / `operation_get` 复核该次启动是否真正进入终态；若是 `LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED`，换新空间或新 operationId 重试，不要直接判应用不兼容。",
}


def category_for_import(error):
    for name, needle, _ in IMPORT_RULES:
        if needle in (error or ""):
            return name
    return "其他导入错误"


def category_for_launch(row, evidence_text, slot_text=""):
    haystack = "\n".join([row.get("error") or "", row.get("uiError") or "", evidence_text])
    for name, needle, _ in LAUNCH_RULES:
        if needle in haystack:
            return name
    if slot_text:
        return "槽位进程崩溃"
    if row.get("verdict") == "CRASH_ON_START":
        return "应用崩溃（无归因栈）"
    if row.get("verdict") in ("PROCESS_GONE", "NO_FOREGROUND_SCREEN", "ANR"):
        return VERDICT_TEXT.get(row["verdict"], row["verdict"])
    return "其他启动错误"


def evidence_line(row, evidence_text, slot_text=""):
    if row.get("error"):
        return row["error"].replace("|", "/")[:260]
    for block in (evidence_text.split("\n\n") if evidence_text else []):
        lines = block.splitlines()
        causes, first_exception = [], ""
        for line in lines:
            body = re.sub(r"^\s*\d+\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+\S+\s*:\s?", "", line).strip()
            if body.startswith("Caused by:"):
                causes.append(body)
            elif not first_exception and re.match(r"^[A-Za-z_$][\w.$]*(Exception|Error|Throwable)", body):
                first_exception = body
        if causes:
            # The deepest `Caused by` is the actionable one (missing class, bad resource...).
            return causes[-1].replace("|", "/")[:260]
        if first_exception:
            return first_exception.replace("|", "/")[:260]
    lines = (evidence_text or slot_text).splitlines()
    for line in lines:
        if "FATAL EXCEPTION" in line or "ANR in" in line or "Fatal signal" in line:
            return line.strip().replace("|", "/")[:260]
    if row.get("uiError"):
        return row["uiError"].replace("|", "/")[:260]
    fatals = row.get("fatals") or []
    if fatals:
        return str(fatals[0]).strip().replace("|", "/")[:260]
    return ""


def build(run_dir, retry_dir):
    global LAUNCHERS
    LAUNCHERS = load_launchers(run_dir)
    facts, imports, launches = index(load_rows(os.path.join(run_dir, "journal.jsonl")))
    first_imports, first_launches = dict(imports), dict(launches)
    if retry_dir:
        rf, ri, rl = index(load_rows(os.path.join(retry_dir, "retry.jsonl")))
        facts.update(rf)
        imports.update(ri)
        launches.update(rl)

    packages = sorted(facts, key=lambda p: NAMES.get(p, p))
    set_lines = {}
    set_path = os.path.join(run_dir, "evidence", "03-app-set-123.txt")
    if os.path.exists(set_path):
        with open(set_path, "r", encoding="utf-8") as handle:
            for lineno, line in enumerate(handle, 1):
                if line.strip():
                    set_lines[line.strip()] = lineno
    entries = []
    for package in packages:
        fact = facts.get(package, {})
        imp = imports.get(package, {})
        run = launches.get(package, {})
        log_text, log_path = read_log(run_dir, package)
        if not log_text and retry_dir:
            log_text, log_path = read_log(retry_dir, package)
        own_blocks, slot_blocks = attribute_blocks(log_text, package, run.get("pid"))
        fatal_ref, fatal_text, cause_ref = own_log_ref(log_path, package, run.get("pid"))
        own_text = "\n\n".join(own_blocks)
        slot_text = "\n\n".join(slot_blocks)
        import_state = imp.get("state", "")
        verdict = run.get("verdict", "")
        if not verdict:
            verdict = "NOT_TESTED" if import_state == "SUCCEEDED" else ("IMPORT_FAILED" if import_state else "NOT_TESTED")
        error = imp.get("error", "") if import_state != "SUCCEEDED" else (run.get("error") or "")
        category = category_for_import(error) if import_state != "SUCCEEDED" else (category_for_launch(run, own_text, slot_text) if verdict != "PASS" else "")
        own = own_blocks if verdict == "PASS" else []
        entries.append({
            "package": package,
            "name": NAMES.get(package, package),
            "versionName": fact.get("versionName", ""),
            "versionCode": fact.get("versionCode", ""),
            "minSdk": fact.get("minSdk", ""),
            "targetSdk": fact.get("targetSdk", ""),
            "apkBytes": fact.get("apkBytes", 0),
            "splitCount": fact.get("splitCount", 0),
            "hasLauncher": LAUNCHERS.get(package),
            "importState": import_state,
            "importCategory": category_for_import(imp.get("error", "")) if import_state != "SUCCEEDED" else "",
            "importError": (imp.get("error") or "")[:300],
            "space": imp.get("space", ""),
            "verdict": verdict,
            "category": category,
            "error": (error or "")[:300],
            "evidence": evidence_line(run, own_text, slot_text) if verdict != "PASS" else "",
            "seconds": run.get("seconds", ""),
            "uiNodes": run.get("uiTreeNodes", ""),
            "foreground": run.get("foreground", ""),
            "screenshot": run.get("screenshot", ""),
            "log": log_path,
            "logRel": os.path.relpath(log_path, run_dir) if log_path else "",
            "ownFatal": own[0][:300] if own else "",
            "slotCrash": bool(slot_blocks),
            "attempt": run.get("attempt", 1),
            "firstImportState": first_imports.get(package, {}).get("state", ""),
            "firstVerdict": first_launches.get(package, {}).get("verdict", ""),
            "firstError": (((first_imports.get(package, {}).get("error") or "")
                            if first_imports.get(package, {}).get("state") != "SUCCEEDED"
                            else (first_launches.get(package, {}).get("error") or ""))[:300]),
            "recInventory": fact.get("_ref", ""),
            "recImport": imp.get("_ref", ""),
            "recLaunch": run.get("_ref", ""),
            "recSet": f"evidence/03-app-set-123.txt:{set_lines.get(package, 0)}" if set_lines else "",
            "recImportFirst": first_imports.get(package, {}).get("_ref", ""),
            "recLaunchFirst": first_launches.get(package, {}).get("_ref", ""),
            "retryRef": run.get("_ref", "") if "retry" in str(run.get("_ref", "")) else "",
            "logRef": fatal_ref,
            "logText": fatal_text,
            "causeRef": cause_ref,
        })

    counts = collections.Counter(e["verdict"] for e in entries if e["importState"] == "SUCCEEDED")
    import_counts = collections.Counter(e["importState"] or "MISSING" for e in entries)
    import_categories = collections.Counter(e["importCategory"] for e in entries if e["importCategory"])
    launch_categories = collections.Counter(e["category"] for e in entries
                                            if e["importState"] == "SUCCEEDED" and e["verdict"] != "PASS" and e["category"])
    passed = counts.get("PASS", 0)
    tested = sum(1 for e in entries if e["importState"] == "SUCCEEDED" and e["verdict"] not in ("NOT_TESTED", "IMPORT_FAILED"))
    warnings = [e for e in entries if e["verdict"] == "PASS" and e["ownFatal"]]
    stable = passed - len(warnings)
    imported = import_counts.get("SUCCEEDED", 0)
    launchable = [e for e in entries if e["hasLauncher"]]
    nonlaunch = [e for e in entries if e["hasLauncher"] == 0]
    unknown = [e for e in entries if e["hasLauncher"] is None]
    return {
        "run": os.path.basename(run_dir.rstrip("/")),
        "date": dt.date.today().isoformat(),
        "entries": entries,
        "counts": counts,
        "import_counts": import_counts,
        "import_categories": import_categories,
        "launch_categories": launch_categories,
        "total": len(entries),
        "passed": passed,
        "tested": tested,
        "imported": imported,
        "launchable": len(launchable),
        "nonlaunch": nonlaunch,
        "unknownLauncher": len(unknown),
        "warnings": len(warnings),
        "stable": stable,
        "failures": [e for e in entries if e["verdict"] != "PASS"],
    }


def markdown(report):
    e = report["entries"]
    out = []
    add = out.append
    sec_map = {}
    total = report["total"]
    passed = report["passed"]
    tested = report["tested"]
    imported = report["imported"]
    rate = passed / tested * 100 if tested else 0
    launchable = report["launchable"] or (total - len(report["nonlaunch"]))
    no_launcher = len(report["nonlaunch"])
    engine_fail = sum(count for cat, count in report["launch_categories"].items() if cat in ENGINE_CATS)
    app_fail = sum(count for cat, count in report["launch_categories"].items() if cat in APP_CATS)
    contract_fail = (tested - passed) - engine_fail - app_fail
    add("# 前手机全部应用迁移至 Tripoder 的兼容性测试报告")
    add("")
    add(f"- 报告日期：{report['date']}")
    add(f"- 数据批次：`{report['run']}`（一应用一空间；重点失败项在 `qa2-*` 新空间复测）")
    add(f"- 测试设备：前手机 Xiaomi M154FF（`d861a4fa`），Android 17 / API 37，arm64-v8a")
    add(f"- 测试对象：`dev.tripoder.engine`（Tripoder 应用虚拟化运行时，MCP protocol 2）")
    add(f"- 第三方包总数：**{total}**（`pm list packages -3` 共 130 个，排除 `dev.tripoder.*` 7 个测试夹具）")
    add(f"- 其中：有 Launcher 入口 **{launchable}** 个，无入口的预装组件 **{no_launcher}** 个")
    first_import_ok = sum(1 for x in e if x["firstImportState"] == "SUCCEEDED")
    first_pass = sum(1 for x in e if x["firstVerdict"] == "PASS")
    retried_rows = [x for x in e if x.get("attempt") == 2]
    recovered = sum(1 for x in retried_rows if x["verdict"] == "PASS" and x["firstVerdict"] != "PASS")
    add(f"- 首轮结果：安装 **{first_import_ok}/{total}** 成功，首屏通过 **{first_pass}**"
        f"（占首轮实测 {first_pass / first_import_ok * 100:.1f}%）")
    add(f"- 复测后结果：复测 **{len(retried_rows)}** 个失败项，**{recovered}** 个转为通过；"
        f"最终安装 **{imported}/{total}** 成功，启动实测 **{tested}** 个，首屏通过 **{passed}**（{rate:.1f}%），"
        f"窗口内无自身 FATAL 的稳定通过 **{report['stable']}** 个")
    add("")
    add("> 逐项核验入口：每个应用的清单/导入/启动/日志记录行号见 **附录 B**；"
        "设备清单原始抓取见 `artifacts/compat-run1/evidence/00-device.txt`、`01-pm-list-packages-3.raw.txt`、"
        "`03-app-set-123.txt`；汇总数字可用 `python3 scripts/qa/verify_evidence.py` 逐条复算。")
    add("")
    add("> 判定口径：`PASS` = 进程在 18 s 稳定窗口内存活，且应用自己的 Activity 进入前台或 UI 树可读；"
        "`稳定通过` 进一步要求窗口内没有该应用自身进程的 FATAL。两者都不等于登录、联网、支付等业务功能可用。")
    add("")
    add("## 1. 结论摘要")
    add("")
    add("| 指标 | 数量 | 说明 |")
    add("|---|---:|---|")
    add(f"| 第三方包总数 | {total} | 完整清单，含 {no_launcher} 个预装 data-app 组件 |")
    add(f"| 有 Launcher 入口 | {launchable} | 可作为“应用”启动 |")
    add(f"| 无 Launcher 入口 | {no_launcher} | 输入法、相册/视频编辑插件、系统服务组件 |")
    add(f"| 导入成功 | {imported} | 占可启动应用的 {imported / launchable * 100:.1f}% |")
    add(f"| 完成冷启动测试 | {tested} | 每个 18 s 稳定窗口，四路信号判定 |")
    add(f"| 首屏通过（复测后） | {passed} | 占实测的 {rate:.1f}%；首轮为 {first_pass}/{first_import_ok}（{first_pass / first_import_ok * 100:.1f}%） |")
    add(f"| 稳定通过（无自身 FATAL） | {report['stable']} | 扣掉“首屏出来后立刻崩”的应用 |")
    add(f"| 未通过/未完成 | {total - passed} | {total - imported} 个导入失败 + {tested - passed} 个启动失败 |")
    add("")
    add(f"**一句话结论**：{total} 个第三方包中 {imported} 个成功导入 Tripoder，{passed} 个（{rate:.1f}%）在冷启动窗口内正常把首屏拉到前台；"
        f"其余 {total - passed} 个里，{total - imported} 个卡在导入契约，{tested - passed} 个卡在启动契约、引擎稳定性或应用自身。"
        "微信、淘宝、抖音、小红书、美团、京东、拼多多、Telegram、ChatGPT/Claude/DeepSeek 等主要应用通过；"
        "支付宝、Chrome、YouTube、WPS、哔哩哔哩等失败项已定位到具体运行时契约。")
    add("")
    add("失败按责任层拆分：")
    add("")
    add("| 层 | 数量 | 主要问题 |")
    add("|---|---:|---|")
    import_summary = "、".join(f"{name} {count}" for name, count in report["import_categories"].most_common())
    add(f"| 安装契约 | {total - imported} | {import_summary} |")
    add(f"| 启动契约覆盖不足 | {contract_fail} | launchMode/alias、通知与 URI 契约、ClassLoader 身份、可选库、OEM(MIUIX) 资源等（含少量未归因） |")
    add(f"| 引擎稳定性 | {engine_fail} | worker/authority 死亡、恢复围栏、安装产物不一致、slot 崩溃、全局 affinity 容量耗尽 |")
    add(f"| 应用自身 | {app_fail} | 应用进程内崩溃且无运行时归因、无首屏、静默退出、应用代码/split 缺失 |")
    add("")
    add("导入结果分布：")
    add("")
    add("| 导入状态 | 数量 |")
    add("|---|---:|")
    for state, count in report["import_counts"].most_common():
        add(f"| {state or 'MISSING'} | {count} |")
    add("")
    add("启动判定分布（仅已导入并测试的应用）：")
    add("")
    add("| 判定 | 含义 | 数量 |")
    add("|---|---|---:|")
    for verdict, count in report["counts"].most_common():
        add(f"| `{verdict}` | {VERDICT_TEXT.get(verdict, verdict)} | {count} |")
    add("")
    add("## 2. 测试方法")
    add("")
    add("1. **清点**：`adb shell pm list packages -3` 取全部第三方包，记录版本、min/targetSdk、APK 大小、split 数；"
        "排除 `dev.tripoder.*` 的 7 个测试夹具。")
    add("2. **导入**：通过 Tripoder MCP `application_manage(import_device)` 将原始 APK/split 导入每个应用独立的 `qa-<pkg>` 空间；"
        "安装包只在 commit 时进入安装事务，导入阶段不执行 guest 代码。")
    add("3. **冷启动**：先把 Workbench 置前，再 `process_start`；首轮等待 18 s 稳定窗口，复测等待 20 s。")
    add("4. **四路判定**：① `process_start` 返回 pid/processGeneration；② `application_query(targets)` 仍能枚举到 guest；"
        "③ `mCurrentFocus`/`ResumedActivity` 属于 guest；④ logcat crash/main/system 中没有该 pid/包的 FATAL/ANR。"
        "四路中任一路径证明“进程活着且自己的界面在前台”才记 `PASS`。")
    add("5. **失败复测**：对重点失败项（传输/引擎中断、恢复围栏、导入后未启动等）换新 `qa2-*` 空间或新 operationId 再跑一次；"
        "复测证据单独保存在 `retry.jsonl`，不覆盖首轮结果。")
    add("6. **运行模式**：全部结果都在引擎默认的 THIN 模式下获得（安装的 `mode` 字段为 `thin`，全程未调用 `set_mode`）；"
        "THICK 的既有覆盖与本轮补测结论单独列在第 9 节。")
    add("")
    add("> **空间策略**：本轮不是“一个空间装 123 个应用”，而是一应用一空间。原因见附录 A1：一次 UNKNOWN 启动会围栏整个空间，且没有 MCP 收敛入口；同空间测试会让第一个异常应用污染后续所有应用。修复 A1 后，可以把已验证通过的应用合并到一个空间。")
    add("")
    add("## 3. 逐应用结果")
    add("")
    add("| # | 应用 | 包名 | 版本 | targetSdk | 大小 | 导入 | 启动判定 | 根因/证据 |")
    add("|---:|---|---|---|---:|---:|---|---|---|")
    ordered = sorted(e, key=lambda x: (x["verdict"] == "PASS", x["importState"] == "SUCCEEDED", x["name"]))
    for index, row in enumerate(ordered, 1):
        evidence = row["evidence"] or row["importError"] or row["ownFatal"] or ""
        evidence = evidence.replace("|", "/").replace("\n", " ")[:120]
        add(f"| {index} | {row['name']} | `{row['package']}` | {row['versionName']} | {row['targetSdk']} | "
            f"{CR.human_bytes(row['apkBytes'])} | {'成功' if row['importState'] == 'SUCCEEDED' else ('未导入' if row['importState'] else '—')} | "
            f"`{row['verdict']}` | {evidence} |")
    add("")
    retried = [x for x in ordered if x.get("attempt") == 2]
    if retried:
        add("### 3.1 复测对照")
        add("")
        add(f"首轮失败项在全新 `qa2-*` 空间复测，共 **{len(retried)}** 个：**{recovered}** 个转为 `PASS`，"
            f"其余维持失败。下表只列复测过的应用，复测结论覆盖首轮。")
        add("")
        add("| 应用 | 包名 | 首轮 | 复测 | 复测空间 | 变化 |")
        add("|---|---|---|---|---|---|")
        for row in retried:
            first = row["firstImportState"] if row["firstImportState"] not in ("", "SUCCEEDED") else (row["firstVerdict"] or "—")
            retry = row["importState"] if row["importState"] not in ("", "SUCCEEDED") else row["verdict"]
            if retry == "PASS" and first != "PASS":
                note = "复测通过"
            elif retry == first and (row["firstError"] or "") == (row["error"] or ""):
                note = "同一判定、同一原因"
            elif retry == first:
                note = "同一判定、原因变化"
            else:
                note = "判定改变"
            add(f"| {row['name']} | `{row['package']}` | `{first}` | `{retry}` | `{row['space']}` | {note} |")
        add("")
    add("## 4. 无法导入的应用与根因")
    add("")
    for index, (category, count) in enumerate(report["import_categories"].most_common(), 1):
        rows = [x for x in e if x["importCategory"] == category]
        sec_map[category] = f"4.{index}"
        add(f"### 4.{index} {category}（{count} 个）")
        add("")
        rule = next((r for r in IMPORT_RULES if r[0] == category), None)
        if rule:
            add(f"**机制**：{rule[2]}")
            add("")
        add("| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |")
        add("|---|---|---|---:|---|---|")
        for row in rows:
            add(f"| {row['name']} | `{row['package']}` | {row['versionName']} | {CR.human_bytes(row['apkBytes'])} | "
                f"`{row['recImport']}` | {(row['importError'] or '').replace('|', '/')[:160]} |")
        add("")
        if rule:
            add(f"**修复建议**：{RECOMMENDATIONS.get(category, '')}")
            add("")
    add("## 5. 启动失败的应用与根因")
    add("")
    for index, (category, count) in enumerate(report["launch_categories"].most_common(), 1):
        rows = [x for x in e if x["importState"] == "SUCCEEDED" and x["verdict"] != "PASS" and x["category"] == category]
        sec_map[category] = f"5.{index}"
        add(f"### 5.{index} {category}（{count} 个）")
        add("")
        rule = next((r for r in LAUNCH_RULES if r[0] == category), None)
        if rule:
            add(f"**机制**：{rule[2]}")
            add("")
        add("| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |")
        add("|---|---|---|---|---|---|")
        for row in rows:
            add(f"| {row['name']} | `{row['package']}` | {row['versionName']} | `{row['verdict']}` | "
                f"`{row['recLaunch']}` | {(row['evidence'] or row['error'] or '').replace('|', '/')[:180]} |")
        add("")
        if category == "OEM 框架依赖 (MIUIX)" and any(x["package"] == "com.miui.huanji" for x in rows):
            add("> 注：小米换机不是“类缺失”，而是 miuix 资源 `miuix_action_icon_back_light`（`0x7f080131`）在 artifact 中"
                "解析到 `res/OO.xml` 后加载失败；属于资源打包/路径映射问题与 OEM 资源依赖叠加，单独修 miuix 注入不足以恢复。")
            add("")
        if rule:
            add(f"**修复建议**：{RECOMMENDATIONS.get(category, '')}")
            add("")
    warnings = [x for x in e if x["verdict"] == "PASS" and x["ownFatal"]]
    if warnings:
        add("## 6. 通过但存在后台异常")
        add("")
        add("以下应用首屏正常，但稳定窗口内自身进程有 FATAL/异常日志；不影响本次“首屏通过”判定，但业务功能可能受影响：")
        add("")
        add("| 应用 | 包名 | 首条异常 |")
        add("|---|---|---|")
        for row in warnings:
            add(f"| {row['name']} | `{row['package']}` | {row['ownFatal'].replace('|', '/').replace(chr(10), ' ')[:180]} |")
        add("")
    add("## 7. 修复优先级建议")
    add("")
    add("| 优先级 | 问题 | 影响面 | 建议 |")
    add("|---|---|---|---|")
    add("| P0 | 包可见性 `<queries>` 过窄 | 12 个无启动入口组件全部 NameNotFound | 增加 `QUERY_ALL_PACKAGES` 或逐包 `<queries>` |")
    add("| P0 | 全局 activity-affinity 仅 8 个 slot | 导入多个应用后，今日头条/哔哩哔哩等 5 个应用被拒 | 按 space/uidDomain 分区并扩容到 256+，删除/失败回滚时释放 lease |")
    add("| P0 | 原生库路径规则过严 | 百度网盘、平安证券被整包拒绝 | 只校验已知 ABI 目录，未知 `lib/` 资源保留 |")
    add("| P1 | 组件重复声明未 take-last | 腾讯视频、火车票 | 实现同名组件后者覆盖 |")
    add("| P1 | `<uses-split>` 一律拒绝 | 快手 | 允许可选/未提供的 split |")
    add("| P1 | 单文件 1 GiB 上限 | WePop (1.5 GB) | 提高上限或流式安装 |")
    add("| P1 | 引擎 `:slot0/:authority` 稳定性 | 钉钉、头条、网易云等被误判 | 收集 tombstone，自动重连+重试 |")
    add("| P2 | launchMode/alias 容量 | Chrome、YouTube、Office | 支持 mode 4，动态 alias 槽位 |")
    add("| P2 | 通知/电话 API 缺口 | Duolingo、爱奇艺 | 补 facade/无操作实现 |")
    add("| P2 | 可选系统库/存储映射 | 支付宝、Instagram、WPS | 注入 http legacy jar、修正 lib-compressed 路径 |")
    add("")
    add("## 8. 证据索引与复现")
    add("")
    add(f"- 首轮逐应用日志：`artifacts/{report['run']}/journal.jsonl`")
    add(f"- 首轮 logcat：`artifacts/{report['run']}/logs/`")
    add(f"- 首轮失败截图：`artifacts/{report['run']}/shots/`")
    add("- 复测：`artifacts/compat-run1-retry/retry.jsonl`；隔离复测：`artifacts/compat-run1-retry2/retry.jsonl`")
    add(f"- 空间现状（2026-10-23 复核 `runtime_query(action=spaces)`）：保留 `qa-*` 123 个、`qa2-*` 22 个、`qa3-*` 1 个；"
        f"{imported} 个成功导入的应用仍可复核，未做卸载清理。")
    add("- 额外隔离复测：`artifacts/compat-run1-retry2/retry.jsonl`（哔哩哔哩，全新 `qa3-*` 空间，用于验证 affinity 容量）。")
    add("- 清点/导入/启动脚本：`scripts/qa/device_campaign.py`；复测：`scripts/qa/retry_launch.py`；报告：`scripts/qa/final_report.py`")
    add("- 设备清单原始抓取（含设备序列号、系统版本、`pm list packages -3` 命令与输出、sha256）："
        f"`artifacts/{report['run']}/evidence/00-device.txt`、`01-pm-list-packages-3.raw.txt`、"
        "`02-fixtures-7.txt`、`03-app-set-123.txt`、`SHA256SUMS.txt`")
    add("- 逐应用记录行号：本报告 **附录 B**（清单行/导入行/启动行/首因日志行/截图）；"
        "`python3 scripts/qa/verify_evidence.py` 会重新读取这些记录并复算全部汇总数字")
    add("")
    add("复现命令：")
    add("")
    add("```bash")
    add("python3 scripts/qa/device_campaign.py --out compat-run1 --wait-ms 18000")
    add("python3 scripts/qa/retry_launch.py --run compat-run1 --out compat-run1-retry --only <失败包名> --fresh <需换空间的包名>")
    add("python3 scripts/qa/final_report.py --run compat-run1 --retry compat-run1-retry")
    add("```")
    add("")
    add("## 9. 运行模式覆盖（THIN / THICK）")
    add("")
    add("引擎有 THIN（默认；固定绑定 1021、保留编译）与 THICK（全量适配、全局反优化）两种运行模式，"
        "对已停止的安装可用 `application_manage action=set_mode` 切换。本次 123 应用矩阵**只在 THIN 下执行**：")
    add("")
    add("- 抽查 10 个安装的 `application_query action=launch_entries`，`mode` 全部为 `thin`；"
        "`artifacts/compat-run1/journal.jsonl` 的 104 条首轮 launch 记录里没有 `modeChanged`/`mode` 字段，"
        "说明跑测期间从未调用 `set_mode`。")
    add("- 因此本报告第 3~7 节的 57 个 PASS、48 个启动失败、18 个导入失败，都是 THIN 模式下的结论。")
    add("")
    add("THICK 的既有证据是一次单应用双模式验收：`docs/evidence/thick-startup-p0-p1-2026-10-21/mode-validation.json`"
        "（宿主 `dev.tripoder.engine.globaltest`，应用 `com.netease.cloudmusic`，35 项检查中 thin-* 15 项、thick-* 17 项，`passed=true`）。"
        "它证明 THICK 链路可用，但只覆盖 1 个应用，不能外推到矩阵。")
    add("")
    add("本轮补测 THICK 未能产出判定（`--mode thick`，2026-10-23）：")
    add("")
    add("| 应用 | 动作 | 实际结果 | 记录 |")
    add("|---|---|---|---|")
    add("| `com.tencent.mm` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:1` |")
    add("| `com.eg.android.AlipayGphone` | 切 THICK | `RECOVERY_SCOPE_UNKNOWN`（该 space 早已被 fence） | `artifacts/compat-thick-pilot/journal.jsonl:2` |")
    add("| `com.xingin.xhs` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:3` |")
    add("| `tv.danmaku.bili` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:4` |")
    add("| `com.tencent.mm` | THICK 复测 | `SCOPE_FENCED` | `artifacts/compat-thick-pilot2/journal.jsonl:1` |")
    add("")
    add("这是附录 A 第一条的连带后果：8 个全局 affinity 槽位已被 105 次导入占满，`application_query targets` 为空也不释放，"
        "新模式下的启动会先被容量拒绝；失败启动再把对应 space fence 成不可再用。"
        "本次补测因此有 3 个安装（`com.tencent.mm`、`com.xingin.xhs`、`tv.danmaku.bili`）停留在 `thick`："
        "它们的 space 已被 fence，`set_mode thin` 也被拒绝（`artifacts/compat-run1/evidence/08-mode-restore.txt:1-5`），"
        "恢复需要清除引擎数据或换设备。")
    add("")
    add("结论：**THICK 在矩阵规模上尚未验证**。补测需要在干净引擎状态（清除引擎数据或换设备）上执行 "
        "`python3 scripts/qa/device_campaign.py --phase all --mode thick`；脚本已支持该开关。")
    add("")
    add("## 10. 局限")
    add("")
    add("- 只验证冷启动与首屏；未覆盖登录、联网、支付、推送、相机/定位等业务链路。")
    add("- 运行模式只覆盖 THIN；THICK 仅有 1 个应用的历史验收与 5 条被容量拒绝的补测记录（见第 9 节）。")
    add("- 测试设备为 Android 17/HyperOS 4.0；不同 ROM/Android 版本的系统 API 与权限行为可能不同。")
    add("- 无启动入口的 12 个组件属于系统预装 data-app，不应按“用户可启动应用”统计；它们进入报告是为了保证清单完整。")
    add("- 依赖 GMS 的海外应用（YouTube、Instagram 等）在无 GMS 的 guest 中即使越过启动门，功能也可能降级。")
    add("")
    add("## 附录 A：运行时缺陷（引擎侧，与应用无关）")
    add("")
    add("下面几条不是从代码推断的，而是在本轮实测中被触发、可独立复现的运行时问题；它们会直接表现为“应用装不上/用不了”，但不应该记到应用兼容性头上。")
    add("")
    add("### A1（严重）一次 UNKNOWN 启动会围栏整个空间，且没有 MCP 收敛入口")
    add("")
    add("在 `default` 空间启动 `com.miui.notes` 时，投递进程在回执前死亡，操作停在 `UNKNOWN / ACTIVITY_DELIVERY_PROCESS_DIED`。此后同一空间内任何包的写操作都被拒绝，连新的导入也不行：")
    add("")
    add("```text")
    add("application_manage(import_device, space=default, package=com.x8bit.bitwarden)")
    add("  -> IllegalStateException: RECOVERY_SCOPE_UNKNOWN: qa-start-com-miui-notes-1792684863")
    add("```")
    add("")
    add("同一个 APK 换到新空间 `compat-a` 导入，1.9 秒成功。根因是 `RuntimeMutationGuard.enter()` 只用控制 Bundle 顶层的 `space`/`package` 计算 scope，而启动链路把包名放在嵌套的 `target` 里，scope 落成 `space/<space>`；`accepting()` 的 `startsWith` 重叠判定于是把同空间的其他包全部当成冲突。实测 `operation_get`、`application_stop`、`launch_manage(close)`、强制停止并重启引擎、重建同名空间都无法把该 UNKNOWN 变成终态；UI 的“恢复”入口也要求 `entryInert && open==0 && live==0`，带 taskId 的入口永远不满足。")
    add("")
    add("**影响**：一个应用启动时留下 UNKNOWN，整个空间就“坏了”。本轮因此采用一应用一空间；否则第 1 个 UNKNOWN 会让后面所有应用都无法导入/启动。")
    add("")
    add("**建议**：① 启动命令把 `target.package` 带进 guard scope；② 给 UNKNOWN 一条有证据的收敛路径（依据 activity_tickets/sessions 的死亡事实 reconcile 成 FAILED，或提供显式确认）；③ 历史 `space/<x>` 记录只阻断该空间内已知目标的继续写。")
    add("")
    add("### A2（中）`space_manage create` 的 name 上限 24 字符，但校验发生在创建之后")
    add("")
    add("用 `qa-com-x8bit-bitwarden` 建空间返回 `INVALID_TOOL_ARGUMENTS: /name STRING_LENGTH_INVALID (1..24)`，随后 `import_device` 报 `SPACE_NOT_FOUND`，容易被误读成“导入失败”。建议前移参数校验，或让 `import_device` 对缺失空间返回明确的 `SPACE_MISSING`。")
    add("")
    add("### A3（中）`process_inspect(action=diagnostics)` 不接受 `includeInvisible`")
    add("")
    add("`ui_tree` 接受该参数，`diagnostics` 返回 `INVALID_TOOL_ARGUMENTS: /includeInvisible UNKNOWN_PROPERTY`。自动化取证阶段会因此拿不到诊断信息；建议四个 action 的参数保持一致。")
    add("")
    add("### A4（观察）同一应用在不同空间里的结论可能不同")
    add("")
    add("`com.zhihu.android` 在早期 pilot 的 `default` 空间首轮被判 `PROCESS_GONE`，在全新空间 `qa2-com-zhihu-android` 复测时 7.7 秒正常把 `MainActivity` 拿到前台并存活到窗口结束（本批 123 应用矩阵中知乎最终为 `PASS`）。说明首轮的“进程退出”至少有一部分是上一轮残留状态/槽位竞争，不是应用本身不兼容。本报告因此把“首轮判定”和“复测结论”分列，复测结果覆盖首轮。")
    add("")
    add("### A5（严重）全局 activity-affinity 只有 8 个 slot，导入多个应用后必然耗尽")
    add("")
    add("`RuntimeActivitySlots.AFFINITIES=8`，而 `ActivityAffinityLedger` 的 `slot INTEGER UNIQUE` 是**跨 space 全局唯一**的；`releaseUnused()` 只删除没有被 `activity_components.affinityLease` 引用的 lease，停止进程/切换前台都不会释放。于是导入的应用越多，`ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` 越容易出现。")
    add("")
    add("本轮复测后段（第 14 个应用起）有 5 个应用集中报这个错，包括今日头条和哔哩哔哩。为了排除“应用自身声明太多 affinity”，在同一台设备上先把 `application_query(targets)` 清空（返回 0 个 target），再用全新 `qa3-tv-danmaku-bili` 空间单独复测哔哩哔哩，仍然在 `process_start` 阶段报同一错误（证据：`artifacts/compat-run1-retry2/retry.jsonl`）。这说明失败是引擎的全局容量，不是这几个应用不兼容。")
    add("")
    add("**影响**：8 个全局 slot 无法支撑“把前手机所有应用都装进来”的场景；用户侧会看到一批本来能启动的应用在导入若干应用后突然全部启动失败，且没有可见的释放入口。")
    add("")
    add("**建议**：① 把 affinity 池按 space/uidDomain 分区，至少提升到 256；② 在 space 删除、安装移除、启动失败回滚时调用 `releaseUnused()`；③ 容量耗尽时返回可重试错误并提示“清理旧空间”，而不是把它记成应用失败。")
    add("")
    add("## 附录 B：逐应用记录索引（全部 %d 个，可独立核验）" % total)
    add("")
    add("`文件:行` 均为原始记录的物理行号（从 1 起，可直接 `sed -n 'Np'` 打开核对）。"
        "清单记录 → `artifacts/compat-run1/evidence/03-app-set-123.txt`；导入/启动记录 → `artifacts/compat-run1/journal.jsonl`，"
        "复测应用指向 `artifacts/compat-run1-retry/retry.jsonl`；首因日志 → `artifacts/compat-run1/logs/*.logcat.txt` 中第一条 "
        "`Caused by`（无则退到第一条 `FATAL EXCEPTION`）；截图 → `artifacts/compat-run1/shots/`。")
    add("")
    add("| # | 包名 | 清单记录 | 导入记录 | 启动记录 | 判定 | 根因位置 | 首因日志 | 截图 |")
    add("|---:|---|---|---|---|---|---|---|---|")
    for index, row in enumerate(e, 1):
        section = f"§{sec_map[row['category']]}" if row.get("category") in sec_map else "—"
        log_ref = (row.get("causeRef") or row.get("logRef") or "—") if row["verdict"] != "PASS" else "—"
        shot = row["screenshot"].split("/")[-1] if row.get("screenshot") else "—"
        import_ref = row.get("recImport") or "—"
        launch_ref = row.get("recLaunch") or row.get("recLaunchFirst") or "—"
        add(f"| {index} | `{row['package']}` | `{row.get('recSet') or '—'}` | `{import_ref}` | `{launch_ref}` | "
            f"`{row['verdict']}` | {section} | `{log_ref}` | `{shot}` |")
    add("")
    add("> 读法：`PASS` 行的正面证据是该 `journal.jsonl` 行里的 `focus` 字段（`mCurrentFocus=` 指向应用自己的 Activity）"
        "与 `alive/foreground/uiTreeNodes`；失败行的 `首因日志` 指向属于该应用进程的崩溃块，`—` 表示该应用没有留存 logcat，"
        "此时以该行 `journal.jsonl` 的 `error`/`fatals`/`focus` 与 `shots/` 截图为准。`IMPORT_FAILED` 无启动记录，"
        "12 个无入口组件既无启动记录也无截图，均在对应 §4/§5 小节给出原始错误串。")
    add("")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--retry", default="")
    parser.add_argument("--out", default="")
    options = parser.parse_args()
    run_dir = os.path.join(ROOT, "artifacts", options.run)
    retry_dir = os.path.join(ROOT, "artifacts", options.retry) if options.retry else ""
    report = build(run_dir, retry_dir)
    target = options.out or os.path.join(run_dir, "FINAL-REPORT.md")
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(markdown(report))
    with open(os.path.join(run_dir, "final-summary.json"), "w", encoding="utf-8") as handle:
        json.dump({k: v for k, v in report.items() if k not in ("entries", "failures", "nonlaunch")}, handle, ensure_ascii=False, indent=1, default=dict)
    print(f"report -> {target}  total={report['total']} passed={report['passed']} tested={report['tested']}")


if __name__ == "__main__":
    main()
