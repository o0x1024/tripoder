#!/usr/bin/env python3
"""Independent re-check of the THICK report against the campaign journal.

Nothing here trusts the report generator: every count is recomputed from
`journal.jsonl`, and every per-package row must point at the line that actually
carries that package's verdict. Exit code 1 means the report and the evidence disagree.
"""
import collections
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JOURNAL = os.path.join(ROOT, "artifacts/compat-thick-run3/journal.jsonl")
THIN = os.path.join(ROOT, "artifacts/compat-run1/journal.jsonl")
PREVIOUS = os.path.join(ROOT, "artifacts/compat-thick-run2/journal.jsonl")
REPORT = os.path.join(ROOT, "Tripoder-THICK兼容性报告.md")

HARNESS = ("MODE_SET_FAILED", "TRANSPORT_ERROR", "SCOPE_FENCED")
HARNESS_MARKERS = ("ENGINE_NOT_CONNECTED", "ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED",
                   "WORKER_DIED", "ENGINE_RESPONSE_STALE", "RECOVERY_SCOPE_UNKNOWN")
IMPORT_OUTCOMES = ("IMPORT_FAILED", "UNSUPPORTED", "SPACE_FAILED", "SCOPE_FENCED")


def verdict_of(launch, imports, package):
    if package in launch:
        return label(launch[package])
    if package in imports and label(imports[package]) in IMPORT_OUTCOMES:
        return label(imports[package])
    return "NOT_RUN"

checks, failures = [], []


def check(name, condition, detail=""):
    checks.append({"name": name, "ok": bool(condition), "detail": detail})
    if not condition:
        failures.append(name)


def load(path, phase):
    latest, lines = {}, {}
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


def is_harness(row):
    if not row:
        return False
    if row.get("verdict") in HARNESS:
        return True
    return any(marker in (row.get("error") or "") for marker in HARNESS_MARKERS)


def main():
    thick, thick_at = load(JOURNAL, "launch")
    thick_import, thick_import_at = load(JOURNAL, "import")
    thin, _ = load(THIN, "launch")
    thin_import, _ = load(THIN, "import")

    # The THIN report's final accounting overlays the retry journals on the first round.
    # Without this the three retried PASSes look like THICK fixes (2 of them) or like a
    # SCOPE_FENCED→CRASH change (wikipedia).
    thin_first = {package: verdict_of(thin, thin_import, package)
                  for package in set(thin) | set(thin_import)}
    retry_launch, retry_import = {}, {}
    for retry_path in (os.path.join(ROOT, "artifacts/compat-run1-retry/retry.jsonl"),
                       os.path.join(ROOT, "artifacts/compat-run1-retry2/retry.jsonl")):
        if not os.path.exists(retry_path):
            continue
        for line in open(retry_path, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            package = row.get("package")
            if not package:
                continue
            if row.get("verdict"):
                retry_launch[package] = row
            if row.get("state"):
                retry_import[package] = row
    thin.update(retry_launch)
    thin_import.update(retry_import)
    upgraded = sorted(package for package in retry_launch
                      if thin_first.get(package) != "PASS"
                      and verdict_of(thin, thin_import, package) == "PASS")
    retried = len(retry_launch)
    report = open(REPORT, "r", encoding="utf-8").read()

    measured = {package: verdict_of(thick, thick_import, package)
                for package in set(thick) | set(thick_import)
                if not is_harness(thick.get(package) or thick_import.get(package))}
    measured = {package: value for package, value in measured.items() if value != "NOT_RUN"}
    trashed = {package: (thick.get(package) or thick_import.get(package))
               for package in set(thick) | set(thick_import)
               if is_harness(thick.get(package) or thick_import.get(package))}
    counts = collections.Counter(measured.values())
    passes = [package for package, row in thick.items()
              if row.get("verdict") == "PASS" and not is_harness(row)]

    changed = []
    for package in measured:
        if verdict_of(thin, thin_import, package) != measured[package]:
            changed.append(package)

    check("有效判定覆盖 ≥ 100 个应用", len(measured) >= 100, f"{len(measured)} 个有效判定")
    for verdict, count in counts.items():
        check(f"报告里的 {verdict} 计数与 journal 一致", f"| {verdict} | {count} |" in report, f"journal={count}")
    check("报告里的 PASS 总数与 journal 一致",
          f"**{len(passes)}/{len(measured)}**" in report, f"journal PASS={len(passes)}")
    check("报告里的「结果不同的包」总数与 journal 一致",
          f"共 **{len(changed)}** 个" in report, f"journal changed={len(changed)}")
    check("报告把 THIN 复测结果计入基线",
          f"{retried} 个复测包" in report and all(f"`{package}`" in report for package in upgraded),
          f"retry={retried} upgraded={upgraded}")

    candidates = [package for package, value in measured.items()
                  if verdict_of(thin, thin_import, package) == "PASS" and value != "PASS"]
    check("报告里的校准候选数与 journal 一致", f"候选是 {len(candidates)} 个" in report,
          f"journal 候选={len(candidates)}")

    missing = [package for package in changed if f"`{package}`" not in report]
    check("所有结果变化的包都出现在对照表", not missing, f"缺 {missing[:3]}")

    bad_refs = []
    for package, row in measured.items():
        number = thick_at.get(package) or thick_import_at.get(package)
        if not number:
            bad_refs.append((package, "no line"))
            continue
        if f"journal.jsonl:{number}`" not in report:
            bad_refs.append((package, number))
    check("每个有效判定都带正确的 journal 行号", not bad_refs, f"{bad_refs[:3]}")

    # The two checks that used to sit here asserted "本轮没有清库" — that was true of the run2
    # report, written before the engine data was cleared. The official dataset is now run3,
    # which is only meaningful if the persistent state really was emptied in between. That is
    # verified from the journals instead of from prose: a fence survives process restarts and
    # refuses deletion, so any run2 fence that survived would come back as a failed import.
    previous_rows = [json.loads(line) for line in open(PREVIOUS, encoding="utf-8") if line.strip()]
    fenced = {row["package"] for row in previous_rows
              if row.get("package") and (row.get("verdict") == "SCOPE_FENCED"
                                         or "RECOVERY_SCOPE_UNKNOWN" in (row.get("error") or ""))}
    reset = sorted(package for package in fenced if label(thick_import.get(package)) == "SUCCEEDED")
    previous_last = max((row.get("at") or "" for row in previous_rows), default="")
    first_import = min((row.get("at") or "" for row in
                        [json.loads(line) for line in open(JOURNAL, encoding="utf-8") if line.strip()]
                        if row.get("phase") == "import" and row.get("state")), default="")
    check("报告写明了清库，并给出可复核的时点边界",
          previous_last[11:19] in report and first_import[11:19] in report,
          f"run2 末行 {previous_last[11:19]} / run3 首导入 {first_import[11:19]}")
    check("报告里的 fence 复位证据与 journal 一致",
          f"**{len(fenced)}**" in report and f"**{len(reset)}**" in report,
          f"journal fenced={len(fenced)} reset={len(reset)}")
    check("报告写明了 THICK 模式与 space 回收机制", "set_mode" in report and "--recycle" in report)
    check("报告写明了分批与引擎自愈", "run_thick_run3b.sh" in report and "runtime_query status" in report)
    check("引擎侧失败没有被算成不兼容", not any(value in HARNESS for value in measured.values()),
          f"measured 中的 harness 值：{sorted({value for value in measured.values() if value in HARNESS})}")

    recycle_rows = [json.loads(line) for line in open(JOURNAL, encoding="utf-8")
                    if line.strip() and json.loads(line).get("phase") == "recycle"]
    ok = sum(1 for row in recycle_rows if row.get("ok"))
    check("报告里的 space 回收成功数一致", f"成功 **{ok}** 次" in report, f"journal ok={ok}")

    if trashed:
        check("引擎侧失败被显式排除且未冒充不兼容",
              "引擎侧失败，未计入" in report or "不计入 PASS" in report,
              f"{len(trashed)} 个引擎侧失败")

    # The driver cleared engine data by itself during run3 (`wipe_engine`), which deletes
    # spaces/installs. A report that presents run3 as one uninterrupted session would be
    # hiding the main source of recycle errors (`SPACE_NOT_FOUND`), so the disclosure is
    # checked against the log copies rather than trusted as prose.
    wipes = []
    for name in ("run-thick-run3.log", "run-thick-run4.log"):
        log_path = os.path.join(ROOT, "artifacts/compat-thick-run3/logs", name)
        if not os.path.exists(log_path):
            continue
        with open(log_path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if line.startswith("=== wipe_engine "):
                    parts = line.split()
                    wipes.append(((parts[2] if len(parts) > 2 else "")[:19], name, number))
    check("报告披露了驱动自愈清空", len(wipes) > 0 and f"**{len(wipes)}** 次" in report
          and "自愈清空" in report, f"日志 wipe_engine={len(wipes)}")
    missing_wipes = [stamp[11:19] for stamp, _, _ in wipes if stamp[11:19] not in report]
    check("每次 wipe_engine 的时间都出现在报告", not missing_wipes,
          f"日志时间 {[stamp[11:19] for stamp, _, _ in wipes]} / 缺 {missing_wipes}")

    # `pending()` in the runner counts MODE_SET_FAILED / SCOPE_FENCED as unfinished, hence
    # `judged=121 left=2`; the two leftovers are the last two harness failures.
    leftovers = [package for package in set(thick) | set(thick_import)
                 if verdict_of(thick, thick_import, package) in ("MODE_SET_FAILED", "SCOPE_FENCED")]
    judged = len(set(thick) | set(thick_import)) - len(leftovers)
    check("报告里的驱动收尾计数与 journal 一致", f"judged={judged} left={len(leftovers)}" in report,
          f"journal judged={judged} left={len(leftovers)}")

    late_resets = sum(1 for package in fenced
                      if wipes and (thick_import.get(package) or {}).get("at", "")[:19] >= wipes[0][0])
    check("报告说明了 fence 证据的时点边界", f"其中 {late_resets} 个" in report,
          f"journal 在首 wipe 后重置的 fence={late_resets}")

    shots_dir = os.path.join(ROOT, "artifacts/compat-thick-run3/shots")
    shots = [name for name in os.listdir(shots_dir) if name.endswith(".png")] if os.path.isdir(shots_dir) else []
    check("报告里的截图数量与目录一致", f"{len(shots)} 张" in report, f"shots={len(shots)}")

    print(f"有效判定 {len(measured)} | PASS {len(passes)} | 结果变化 {len(changed)} | 引擎侧失败 {len(trashed)}")
    for row in checks:
        print(f"{'PASS' if row['ok'] else 'FAIL'}  {row['name']}" + (f"  [{row['detail']}]" if row["detail"] else ""))
    print(f"\n结果：{'全部通过' if not failures else 'FAILED: ' + ', '.join(failures)}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
