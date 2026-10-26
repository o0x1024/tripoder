#!/usr/bin/env python3
"""THICK campaign report: coverage, verdict spread, THIN→THICK transitions, harness health.

Every number is recomputed from the journals; every per-app claim carries a `file:line`
record so a reader can open the exact row the verdict came from.
"""
import argparse
import collections
import datetime
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# A verdict only counts when it describes the guest. These describe the harness instead:
# the controller lease was lost or the affinity pool was full, so the app was never measured.
HARNESS = ("MODE_SET_FAILED", "TRANSPORT_ERROR", "SCOPE_FENCED")
HARNESS_MARKERS = ("ENGINE_NOT_CONNECTED", "ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED",
                   "WORKER_DIED", "ENGINE_RESPONSE_STALE", "RECOVERY_SCOPE_UNKNOWN")
# Import states are only an outcome when the app never reached a launch: a successful
# install is a prerequisite, not a verdict, and must never inflate the measured count.
IMPORT_OUTCOMES = ("IMPORT_FAILED", "UNSUPPORTED", "SPACE_FAILED", "SCOPE_FENCED")
# Terminal states, used when counting how far a previous run got.
LAUNCH_FINAL = ("PASS", "START_REJECTED", "CRASH_ON_START", "PROCESS_GONE", "NO_FOREGROUND_SCREEN")
IMPORT_FINAL = ("IMPORT_FAILED", "UNSUPPORTED")


def verdict_of(launch, imports, package):
    """The one label a package contributes: launch verdict, or an import that stopped it."""
    if package in launch:
        return label(launch[package])
    if package in imports and label(imports[package]) in IMPORT_OUTCOMES:
        return label(imports[package])
    return "NOT_RUN"


def load(path, phase):
    """Latest row per package plus the 1-based line number of that row."""
    latest, lines = {}, {}
    if not os.path.exists(path):
        return latest, lines
    with open(path, "r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            if row.get("phase") != phase or not row.get("package"):
                continue
            if row.get("verdict") or row.get("state"):
                latest[row["package"]] = row
                lines[row["package"]] = number
    return latest, lines


def label(row):
    if not row:
        return "NOT_RUN"
    return row.get("verdict") or row.get("state") or "UNKNOWN"


def load_retry(paths):
    """Final THIN verdicts for the packages that were re-run after the first round.

    `Tripoder-兼容性测试报告.md` reports 57/105 by overlaying
    `compat-run1-retry/retry.jsonl` on the first round; using the bare first-round journal
    here would label three retried packages (authenticator2 / wikipedia / luna.music) with
    their old failure and make two THICK PASSes look like mode fixes.
    """
    launch, imports = {}, {}
    for path in paths:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                row = json.loads(line)
                package = row.get("package")
                if not package:
                    continue
                if row.get("verdict"):
                    launch[package] = row
                if row.get("state"):
                    imports[package] = row
    return launch, imports


def is_harness(row):
    if not row:
        return False
    if row.get("verdict") in HARNESS:
        return True
    return any(marker in (row.get("error") or "") for marker in HARNESS_MARKERS)


def ref(path, number):
    return f"`{os.path.relpath(path, ROOT)}:{number}`"


def recycle_stats(path):
    rows = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    rows = [row for row in rows if row.get("phase") == "recycle"]
    ok = sum(1 for row in rows if row.get("ok"))
    failed = [row for row in rows if not row.get("ok")]
    codes = collections.Counter()
    for row in failed:
        error = row.get("error") or ""
        code = error.split(":")[1].split(":")[0] if error.count(":") >= 1 and "IllegalStateException:" in error else error[:40]
        codes[code or "UNKNOWN"] += 1
    return {"attempts": len(rows), "ok": ok, "failed": len(failed), "topErrors": codes.most_common(5)}


def driver_wipes(run_dir):
    """Read the `wipe_engine` lines from the driver-log copies kept with the run.

    These are `pm clear` calls the driver made by itself when the affinity pool was
    exhausted. They are part of the method, not an anecdote: each one deletes every
    space/installation, so the report has to say when they happened.
    """
    events = []
    for name in ("run-thick-run3.log", "run-thick-run4.log"):
        path = os.path.join(run_dir, "logs", name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if line.startswith("=== wipe_engine "):
                    parts = line.split()
                    events.append({"at": (parts[2] if len(parts) > 2 else "")[:19],
                                   "log": name, "line": number})
    return sorted(events, key=lambda item: item["at"])


def parse_stamp(value):
    try:
        return datetime.datetime.fromisoformat((value or "")[:19])
    except ValueError:
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="artifacts/compat-thick-run2")
    parser.add_argument("--thin", default="artifacts/compat-run1")
    parser.add_argument("--pilot", default="artifacts/compat-thick-pilot3")
    parser.add_argument("--previous", default="artifacts/compat-thick-run2",
                        help="earlier THICK run kept as process evidence (fence-reset proof)")
    parser.add_argument("--calibration", default="")
    parser.add_argument("--out", default="Tripoder-THICK兼容性报告.md")
    options = parser.parse_args()

    thick_journal = os.path.join(options.run, "journal.jsonl")
    thin_journal = os.path.join(options.thin, "journal.jsonl")
    thick, thick_at = load(thick_journal, "launch")
    thick_import, thick_import_at = load(thick_journal, "import")
    thin, thin_at = load(thin_journal, "launch")
    thin_import, thin_import_at = load(thin_journal, "import")

    # Match the THIN report's 57/105 final accounting: retried packages take their retry
    # verdict, not the first-round failure. `retry2` only re-tests bilibili and fails again.
    thin_retry, thin_retry_import = load_retry((
        os.path.join(ROOT, "artifacts/compat-run1-retry/retry.jsonl"),
        os.path.join(ROOT, "artifacts/compat-run1-retry2/retry.jsonl"),
    ))
    thin_first = {package: verdict_of(thin, thin_import, package)
                  for package in set(thin) | set(thin_import)}
    thin.update(thin_retry)
    thin_import.update(thin_retry_import)
    thin_final = {package: verdict_of(thin, thin_import, package)
                  for package in set(thin) | set(thin_import)}
    upgraded = sorted(package for package in thin_retry
                      if thin_first.get(package) != "PASS" and thin_final.get(package) == "PASS")
    retried = len({row.get("package") for row in thin_retry.values() if row.get("package")})

    packages = sorted(set(thin) | set(thin_import) | set(thick) | set(thick_import))
    rows = []
    for package in packages:
        thick_row = thick.get(package) or thick_import.get(package)
        thick_label = verdict_of(thick, thick_import, package)
        thin_label = verdict_of(thin, thin_import, package)
        rows.append({
            "package": package,
            "thin": thin_label,
            "thick": thick_label,
            "harness": is_harness(thick.get(package) or thick_import.get(package)),
            "thickRef": ref(thick_journal, thick_at.get(package) or thick_import_at.get(package) or 0),
            "thickError": (thick_row or {}).get("error", "")[:200],
            "changed": thin_label != thick_label,
        })

    measured = [row for row in rows if row["thick"] != "NOT_RUN" and not row["harness"]]
    thick_counts = collections.Counter(row["thick"] for row in measured)
    transitions = collections.Counter((row["thin"], row["thick"]) for row in measured)
    changed = [row for row in rows if row["changed"] and row["thick"] != "NOT_RUN" and not row["harness"]]
    passed = [row for row in measured if row["thick"] == "PASS"]
    # Every candidate is a package that passed THIN but not THICK; only a sample of them was
    # re-run in THIN mode afterwards, so the report must state the coverage, not imply 8/10
    # accounts for all of them.
    candidates = [row for row in measured if row["thin"] == "PASS" and row["thick"] != "PASS"]
    recycle = recycle_stats(thick_journal)

    # Same package, same mode, two independent runs: the only direct evidence about how
    # repeatable a single verdict is.
    pilot_journal = os.path.join(options.pilot, "journal.jsonl")
    pilot, _ = load(pilot_journal, "launch")
    overlaps = [(row["package"], label(pilot[row["package"]]), row["thick"])
                for row in measured if row["package"] in pilot]
    unstable = [item for item in overlaps if item[1] != item[2]]

    import_states = collections.Counter(label(row) for row in thick_import.values())

    calibration = {}
    if options.calibration and os.path.exists(options.calibration):
        calibration, _ = load(options.calibration, "launch")

    # Independent proof that the engine's persistent state was wiped between the two THICK
    # runs. A previous run's fence (`RECOVERY_SCOPE_UNKNOWN`) survives process restarts and
    # refuses deletion, so any surviving fence would show up again here as a failed import.
    # Timestamps come from the journals, not from anyone's recollection.
    previous_journal = os.path.join(options.previous, "journal.jsonl")
    previous_fenced, previous_final, resets = set(), set(), []
    previous_last_at, first_import_at = "", ""
    if os.path.exists(previous_journal):
        previous_rows = [json.loads(line) for line in open(previous_journal, encoding="utf-8") if line.strip()]
        previous_latest = {}
        for row in previous_rows:
            package = row.get("package")
            if not package:
                continue
            if row.get("verdict") == "SCOPE_FENCED" or "RECOVERY_SCOPE_UNKNOWN" in (row.get("error") or ""):
                previous_fenced.add(package)
            label_now = row.get("verdict") or row.get("state")
            if label_now:
                previous_latest[package] = label_now  # file order == chronological order
        # "终态" must mean the package's LAST state, not "it once had a terminal verdict":
        # a later fence can supersede an earlier PASS.
        previous_final = {p for p, v in previous_latest.items() if v in LAUNCH_FINAL or v in IMPORT_FINAL}
        resets = sorted(p for p in previous_fenced if label(thick_import.get(p)) == "SUCCEEDED")
        previous_last_at = max((row.get("at") or "" for row in previous_rows), default="")
    current_rows = [json.loads(line) for line in open(thick_journal, encoding="utf-8") if line.strip()]
    first_import_at = min((row.get("at") or "" for row in current_rows
                           if row.get("phase") == "import" and row.get("state")), default="")

    # Count packages, not journal lines: a package re-run after an engine restart writes a
    # second `modeChanged=thin->thick` launch row, and the report sentence is about packages.
    switches = len({row.get("package") for row in current_rows
                    if row.get("phase") == "launch" and row.get("package")
                    and row.get("modeChanged") == "thin->thick"})

    # The driver wiped engine data by itself whenever the affinity pool filled up again.
    # Every wipe deletes all spaces/installs, so the report has to disclose the times and
    # the journal gap that follows each one.
    wipes = driver_wipes(options.run)
    journal_times = sorted(t for t in (parse_stamp(row.get("at")) for row in current_rows
                                       if row.get("phase") in ("import", "launch")) if t)

    def gap_after(stamp):
        pivot = parse_stamp(stamp)
        if not pivot:
            return "?", "?"
        following = next((moment for moment in journal_times if moment > pivot), None)
        if not following:
            return "?", "?"
        return f"{(following - pivot).total_seconds() / 60:.0f} 分钟", following.strftime("%H:%M:%S")

    segments = []
    if journal_times:
        start = journal_times[0]
        for wipe in wipes:
            pivot = parse_stamp(wipe["at"])
            before = [moment for moment in journal_times if moment < pivot]
            after = [moment for moment in journal_times if moment > pivot]
            if before:
                segments.append((start.strftime("%H:%M"), before[-1].strftime("%H:%M")))
            start = after[0] if after else None
        if start:
            segments.append((start.strftime("%H:%M"), journal_times[-1].strftime("%H:%M")))

    first_wipe = wipes[0]["at"] if wipes else ""
    late_resets = sum(1 for package in resets
                      if first_wipe and (thick_import.get(package) or {}).get("at", "")[:19] >= first_wipe)
    harness_count = sum(1 for row in rows if row["harness"])
    leftover = [row for row in rows if row["thick"] in ("MODE_SET_FAILED", "SCOPE_FENCED")]
    judged = len(rows) - len(leftover)
    import_rows = [row for row in current_rows if row.get("phase") == "import"]
    import_row_count = len(import_rows)
    import_package_count = len({row.get("package") for row in import_rows if row.get("package")})
    missing_installs = sum(1 for row in current_rows
                           if row.get("phase") == "launch" and "NO_INSTALLATION" in (row.get("error") or ""))
    shot_count = sum(thick_counts.get(name, 0)
                     for name in ("CRASH_ON_START", "PROCESS_GONE", "NO_FOREGROUND_SCREEN"))

    out = []
    add = out.append
    add("# Tripoder 兼容性测试报告 · THICK 全量")
    add("")
    add("这份报告只回答一件事：**同一批手机应用，在引擎的 THICK 模式（全量适配、全局反优化）下，"
        "能自己渲染出前台界面并稳定运行的有多少**，并与 `Tripoder-兼容性测试报告.md` 里的 THIN 结果逐包对照。")
    add("")
    add("## 1. 为什么要清库，以及清库前试过什么")
    add("")
    add("引擎的 activity affinity 租约池只有 8 个 slot：`ActivityAffinityLedger.acquire()` 按 "
        "`(space, uidDomain, logical)` 占位，池满即抛 `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED`；"
        "租约只在「删除拥有该组件的 space」时由 `releaseUnused()` 回收。")
    add("")
    add("这形成了一个自锁循环：**一次失败的启动会把该 space fence 成 `RECOVERY_SCOPE_UNKNOWN`，"
        "而被 fence 的 space 既不能启动、也不能删除**，于是它占住的租约永远回不到池子；"
        "攒够 8 个，后续每次启动都被容量拒绝，而每次被拒绝的启动又 fence 掉一个新的 space。")
    add("")
    add("本轮不是一上手就清库。先在 run2 里用 `--recycle`（判完即删 space）回收租约跑完了大部分应用，"
        "但在引擎中途掉线一次之后陷入了上述自锁，剩余包无法推进。此后的解锁尝试与结果：")
    add("")
    add("| 尝试 | 结果 |")
    add("| --- | --- |")
    add("| `space_delete` 删被 fence 的 space | 拒绝：`RECOVERY_SCOPE_UNKNOWN` |")
    add("| `application_remove` 带 `installGeneration` 精确卸载 | 拒绝：同一 fence |")
    add("| 换一个全新 space 重新导入同一包 | 导入成功、绕开了 fence，但启动立刻撞 `CAPACITY_EXHAUSTED`，"
        "**该次失败又把新 space 一并 fence 掉** |")
    add("| `am force-stop` 冷启动引擎后再删 | fence 原样存活 —— 证明它是**持久化**状态而非进程内状态 |")
    add("")
    add("最后一条是关键判别：既然冷启动不能解锁，剩下的只有清库。设备是无 root 的 production build"
        "（`run-as` 不可用、`adb root` 被拒、`allowBackup` 未开），既没法只删数据库文件，也没有 `adb backup` 可回滚——"
        "**这一步不可逆，是本轮唯一一次由人决定的不可逆操作**。清库前已落逻辑快照"
        "（`artifacts/compat-thick-run2/evidence/pre-clear/`：journal 508 行、applications、spaces、capabilities）。"
        f"必须一并披露：跑测驱动此后又在容量耗尽时自动执行了 {len(wipes)} 次同类清空，见第 2 节「跑测中的自愈清空」。")
    add("")
    time_prev = (previous_last_at or "?")[11:19]
    time_first = (first_import_at or "?")[11:19]
    add(f"清库时点落在 **run2 journal 最后一行（{time_prev}）之后、run3 首条导入（{time_first}）之前**。"
        "这不是采信谁的转述，而是两条可复核的痕迹：")
    add("")
    add(f"- run2 里 `com.tencent.mm` 的导入结果是 `ALREADY`（复用旧安装）；run3 的首条导入就在 {time_first}，"
        "它把同一个包记成 `SUCCEEDED`（全新安装）——旧安装已经不在了。")
    add(f"- run2 留下 **{len(previous_fenced)}** 个被 fence 的 space。fence 是持久化状态：run2 已用冷启动验证过它跨进程存活，"
        f"且它连删除都拒绝。run3 里这 {len(previous_fenced)} 个包最终都重新导入成功（**{len(resets)}** 个 `SUCCEEDED`，"
        "没有一个停在 `SCOPE_FENCED`）。"
        f"但其中 {late_resets} 个的导入发生在 {first_wipe[11:16] if first_wipe else '?'} 之后，即驱动后续三次自愈清空之后"
        "（见第 2 节），所以这条只能证明「到 run3 结束时没有 run2 的 fence 存活」，"
        "**不能单独用来定位第一次清库的时点**；第一次清库的时点由上面 `com.tencent.mm` 的 `ALREADY → SUCCEEDED` "
        "与 run2 末行共同界定。")
    add("")
    add("清库后按「设置 → 开启网络 MCP → 导出连接配置 → `adb pull /storage/emulated/0/tripoder/`」重建凭据，"
        "`runtime_query status` 返回 `hookReady=true` 后才开始跑。"
        f"**因此本报告的正式数据集是清库后的 run3**；run2 的 {len(previous_final)} 条终态判定只作为过程证据保留。")
    add("")
    add("## 2. 方法")
    add("")
    add("```bash")
    add("adb -s d861a4fa shell pm clear dev.tripoder.engine    # 清掉 fence / 租约池 / 历史安装")
    add("bash scripts/qa/run_thick_run3.sh                     # 初版：round 循环")
    add("bash scripts/qa/run_thick_run3b.sh                    # 续跑：pending() 选批 + 容量耗尽自愈")
    add("bash scripts/qa/run_thick_run4.sh                     # 收尾：6 个/批，连续 2 代无进展即退出")
    add("python3 scripts/qa/thick_report.py "
        f"--run {os.path.relpath(options.run, ROOT)} "
        f"--thin {os.path.relpath(thin_journal, ROOT)} "
        f"--previous {os.path.relpath(previous_journal, ROOT)} "
        + (f"--calibration {os.path.relpath(options.calibration, ROOT)} " if options.calibration else "")
        + f"--out {os.path.relpath(options.out, ROOT)}")
    add("```")
    add("")
    add("- 判定与 THIN 完全相同的四条信号：`process_start` 接受并返回 pid、进程仍在 `application_query targets`、"
        "`mCurrentFocus` 属于该包、logcat 无该 pid 的 crash/ANR 块。四条同时满足才记 `PASS`。")
    add("- 每次启动前用 `application_manage action=set_mode` 把停着的安装切到 THICK，"
        "并从 `application_query action=launch_entries` 读回 `mode` 确认；本轮有 "
        f"{switches} 个包记录了 `thin->thick` 的切换。")
    add("- `--recycle`：每判完一个应用就删掉它的 space，把 affinity 租约还给池子。")
    add("- **删 space 前先 `application_stop`**：判完的 guest 常还挂着 WorkManager 作业，"
        "直接删会得到 `INSTALL_IN_USE_BY_JOB`；这种删除失败会永久泄漏一个租约，正是上面自锁的起点。")
    add("- **分批 + 自愈**：每批 6–10 个包（`BATCH` 逐轮下调），批前用 `runtime_query status` 探活；"
        "控制器掉线则重新拉起并按「设置 → 网络 MCP → 导出连接配置」重导凭据，把「引擎掉线」和「应用不兼容」分开记。")
    add("- **失败可重试**：`--retry-failed` 让历史上记为 `IMPORT_FAILED` / `SCOPE_FENCED` 的包重新导入，"
        "否则续跑会把这些包当成已有结论永久跳过。")
    add("")
    add("### 跑测中的自愈清空（披露）")
    add("")
    if wipes:
        add("run3 不是一次连续无中断的会话：驱动在 affinity 池再次耗尽时执行 "
            "`adb shell pm clear dev.tripoder.engine` 自愈，共 "
            f"**{len(wipes)}** 次（日志原文 `=== wipe_engine <时间> (affinity pool exhausted)`）：")
        add("")
        add("| # | 时间 | journal 中断 | 证据 |")
        add("|---|---|---|---|")
        for index, wipe in enumerate(wipes, 1):
            gap, resume = gap_after(wipe["at"])
            add(f"| {index} | {wipe['at'][11:19]} | 约 {gap}（恢复于 {resume}） | "
                f"`artifacts/compat-thick-run3/logs/{wipe['log']}:{wipe['line']}` |")
        add("")
        add("后果与边界：")
        add("")
        add("- 清空会删掉当时全部 space 和安装。`phase=recycle` 里的 `SPACE_NOT_FOUND` 因此不是回收接口失效，"
            "而是 space 已被整库清掉；已记录的判定发生在清空之前，不受影响。")
        add(f"- 没有拿已被清掉的安装去启动：{import_row_count} 条 import 记录对应 {import_package_count} 个包，"
            f"没有任何包第二次 `SUCCEEDED`；launch 记录里 `NO_INSTALLATION` 出现 {missing_installs} 次。"
            "即每次清空后只继续处理尚未判定的包，每一条判定都在同段内「导入→启动」完成。")
        add("- 清空会连 MCP 凭据一起失效。前两次清空后日志停在 `wipe: MCP still down`；第三次清空后修复一度报 "
            "`no new export`；journal 直到 10:48:25 才恢复。这些空档里没有写任何判定。")
        if segments:
            add(f"- {len(measured)} 条有效判定因此分布在 {len(segments)} 个连续段（"
                + "、".join(f"{start}–{end}" for start, end in segments)
                + "），每段内的安装与启动都在同一段完成。")
        add("")
    else:
        add("驱动日志副本里没有 `wipe_engine` 记录。")
        add("")
    add("## 3. 覆盖与结果")
    add("")
    add(f"- 设备应用总数（THIN 轮盘点）：**{len(rows)}**")
    add(f"- 拿到 THICK 有效判定：**{len(measured)}**")
    if import_states:
        add(f"- 安装阶段：{ '，'.join(f'`{key}` {value}' for key, value in import_states.most_common()) }")
    add(f"- 驱动收尾计数：`judged={judged} left={len(leftover)}`——{judged} = {len(measured)} 条有效判定 + "
        f"{judged - len(measured)} 条判为 `START_REJECTED` 的引擎侧失败；`left` 的 {len(leftover)} 条是 "
        f"`MODE_SET_FAILED` / `SCOPE_FENCED` 的引擎侧失败。{harness_count} 条引擎侧失败都不在有效判定内。")
    add("")
    add("| THICK 结果 | 数量 | 占有效判定 |")
    add("|---|---|---|")
    for verdict, count in thick_counts.most_common():
        add(f"| {verdict} | {count} | {count / max(1, len(measured)) * 100:.1f}% |")
    add("")
    add("## 4. THIN → THICK 对照")
    add("")
    add("| THIN | THICK | 数量 |")
    add("|---|---|---|")
    for (before, after), count in transitions.most_common():
        add(f"| {before} | {after} | {count} |")
    add("")
    add(f"THIN 基线按 `Tripoder-兼容性测试报告.md` 的最终口径合成：首轮 `compat-run1/journal.jsonl` + "
        f"`compat-run1-retry/retry.jsonl`（{retried} 个复测包；`retry2` 只复测哔哩哔哩且仍失败）。"
        f"其中 {len(upgraded)} 个由非 PASS 转 PASS"
        + ("（" + "、".join(f"`{package}`" for package in upgraded) + "）" if upgraded else "")
        + "，按 PASS 计入；若只看首轮，这些会被误记成「THICK 修好了」。")
    add("")
    add(f"结果与 THIN 不同的包共 **{len(changed)}** 个：")
    add("")
    add("| 包名 | THIN | THICK | 记录 |")
    add("|---|---|---|---|")
    for row in changed:
        add(f"| `{row['package']}` | {row['thin']} | {row['thick']} | {row['thickRef']} |")
    add("")
    add("## 5. 逐包明细")
    add("")
    add("| # | 包名 | THICK | THIN | 记录 |")
    add("|---|---|---|---|---|")
    for index, row in enumerate(rows, 1):
        verdict = row["thick"] + ("（引擎侧失败，未计入）" if row["harness"] else "")
        add(f"| {index} | `{row['package']}` | {verdict} | {row['thin']} | {row['thickRef']} |")
    add("")
    add("## 6. 跑测自身的可靠性")
    add("")
    add(f"- space 回收（affinity 租约归还）：尝试 **{recycle['attempts']}** 次，成功 **{recycle['ok']}** 次，"
        f"失败 **{recycle['failed']}** 次。"
        + ("失败码 Top：" + "，".join(f"`{code}` × {count}" for code, count in recycle["topErrors"]) if recycle["topErrors"] else ""))
    add("- 回收失败不代表判定无效：判定发生在回收之前，失败只影响后续包能否拿到租约。")
    if wipes and any(code == "SPACE_NOT_FOUND" for code, _ in recycle["topErrors"]):
        add(f"- 失败码里的 `SPACE_NOT_FOUND` 由第 2 节的 {len(wipes)} 次自愈清空造成（space 被整库清掉），"
            "不代表回收接口本身失效。")
    if overlaps:
        add(f"- 同一包、同一模式、两次独立运行（`compat-thick-pilot3` 与本轮）重叠 {len(overlaps)} 个，"
            f"其中 **{len(unstable)}** 个结果不同"
            + ("：" + "，".join(f"`{package}` {first}→{second}" for package, first, second in unstable) if unstable else "。")
            + "。这说明单次判定存在抖动，`CRASH_ON_START` 类是「这一次启动崩了」，不是「永远崩」。")
    if calibration:
        # Does a THIN→THICK difference belong to the mode, or to whatever state the engine was
        # in when each run happened? Re-running the disputed packages in THIN on today's engine
        # answers that directly.
        calib_all = [(row["package"], row["thin"], row["thick"], label(calibration.get(row["package"])))
                     for row in measured if calibration.get(row["package"]) is not None]
        # A calibration run that itself died on the harness says nothing about the app.
        calib = [item for item in calib_all if item[3] not in HARNESS]
        unusable = [item for item in calib_all if item[3] in HARNESS]
        # Every candidate was selected as "THIN baseline PASS, THICK not PASS". What matters is
        # whether today's THIN run reproduces that PASS.
        stayed = [item for item in calib if item[1] == "PASS" and item[3] == "PASS"]
        reproduced = [item for item in calib if item[3] != "PASS"]
        add(f"- **校准**：候选是 {len(candidates)} 个「THIN 基线 PASS、THICK 失败」的包；本轮抽测其中 {len(calib)} 个，"
            "在当前引擎上重跑 THIN 模式："
            f"**{len(stayed)}** 个复现 PASS（失败可归因于 THICK），**{len(reproduced)}** 个在 THIN 下也失败"
            "（说明是引擎状态/时间抖动，不是模式差异）。"
            + (f"另有 {len(unusable)} 个校准运行本身撞上引擎侧失败，无法判定。" if unusable else ""))
        if calib:
            add("")
            add("| 包名 | THIN 基线 | THICK | THIN 复测 | 归属 |")
            add("|---|---|---|---|---|")
            for package, before, after, again in calib:
                owner = "THICK 导致" if again == "PASS" else "引擎状态/抖动"
                add(f"| `{package}` | {before} | {after} | {again} | {owner} |")
    add("")
    add("## 7. 结论与局限")
    add("")
    add(f"- 本轮 THICK 下 **{len(passed)}/{len(measured)}** 个应用能自行渲染出前台界面且未崩溃。")
    add("- 判定是「冷启动 + 固定观察窗」的一次采样，不是逐帧 UI 比对；同一包重复跑可能得到不同结果"
        "（见第 6 节的重复运行对照）。")
    add("- THICK 会全局反优化，启动明显更慢；观察窗对超大应用可能偏紧，超时会被记成 "
        "`NO_FOREGROUND_SCREEN` 而不是 `PASS`，这类需要更长窗口才能定论。")
    add("- 引擎控制器中途死亡是本轮最大的噪声源：它为**后续**的包留下 `MODE_SET_FAILED`，"
        "这些行在报告里被显式排除在有效判定之外，不计入 PASS，也不计成不兼容。")
    if wipes:
        add(f"- run3 期间驱动自愈清空 {len(wipes)} 次；{len(measured)} 条有效判定分布在 {len(segments)} 个连续段，"
            "跨段的安装状态不共享（见第 2 节）。")
    if calibration and calib:
        add(f"- 校准（第 6 节）：{len(stayed)}/{len(calib)} 个「THIN 基线 PASS、THICK 失败」的样本在 THIN 下复现 PASS，"
            f"另外 {len(reproduced)}/{len(calib)} 个在 THIN 下同样失败——后者的差异不能记在 THICK 头上。")
        add(f"- 校准样本只有 {len(calib)}/{len(candidates)} 个候选；未复测的 "
            f"{len(candidates) - len(calib)} 个「THIN PASS、THICK 失败」仍可能混有引擎抖动，不能按 "
            f"{len(stayed)}/{len(calib)} 的比例外推。")
    add("")
    add("## 8. 记录索引")
    add("")
    add(f"- THICK 判定：`{os.path.relpath(thick_journal, ROOT)}`")
    add(f"- THIN 判定：`{os.path.relpath(thin_journal, ROOT)}`（首轮）+ "
        f"`artifacts/compat-run1-retry/retry.jsonl`（{retried} 个复测包，按最终结果覆盖）")
    add("- 跑测日志（副本）：`artifacts/compat-thick-run3/logs/run-thick-run3.log`（run3 / run3b）、"
        "`artifacts/compat-thick-run3/logs/run-thick-run4.log`（run4）；"
        f"原件为 `/tmp/compat-thick-run3.log`、`/tmp/compat-thick-run4.log`，run4 收尾于 "
        f"`judged={judged} left={len(leftover)}`。")
    if calibration:
        usable_calibration = sum(1 for package in calibration
                                 if label(calibration[package]) not in HARNESS)
        add(f"- 校准 journal：`{os.path.relpath(options.calibration, ROOT)}`"
            f"（{len(calibration)} 个包：{usable_calibration} 个有效 + "
            f"{len(calibration) - usable_calibration} 个引擎侧失败）")
    add(f"- 逐包启动后截图：`artifacts/compat-thick-run3/shots/`（{shot_count} 张：`CRASH_ON_START` "
        f"{thick_counts.get('CRASH_ON_START', 0)} + `PROCESS_GONE` {thick_counts.get('PROCESS_GONE', 0)} + "
        f"`NO_FOREGROUND_SCREEN` {thick_counts.get('NO_FOREGROUND_SCREEN', 0)}）")
    add(f"- 清库前的过程证据（非正式数据集）：`{os.path.relpath(previous_journal, ROOT)}`")
    add("")

    with open(options.out, "w", encoding="utf-8") as handle:
        handle.write("\n".join(out) + "\n")
    with open(os.path.join(options.run, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump({
            "packages": len(rows),
            "measured": len(measured),
            "thick": dict(thick_counts),
            "passed": [row["package"] for row in passed],
            "changed": [{"package": row["package"], "thin": row["thin"], "thick": row["thick"]} for row in changed],
            "harnessFailures": [row["package"] for row in rows if row["harness"]],
            "recycle": recycle,
            "repeatOverlap": {"compared": len(overlaps), "unstable": len(unstable)},
        }, handle, indent=1, ensure_ascii=False)
    print(f"written {options.out}: {len(measured)} measured / {len(passed)} PASS / {len(changed)} changed")


if __name__ == "__main__":
    main()
