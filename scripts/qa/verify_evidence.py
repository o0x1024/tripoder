#!/usr/bin/env python3
"""Re-read the raw records of compat-run1 and re-derive every number in the report.

The point of this script is that nothing here trusts the report or final-summary.json:
it starts from the captured device package list and the per-app journal lines, recomputes
the counts, and then checks that the report states the same numbers and that every
failing package is covered by a root-cause section.

Usage:
    python3 scripts/qa/verify_evidence.py [--run compat-run1] [--report <path>]

Exit code 0 = every check passed; 1 = at least one check failed (details printed).
"""

import argparse
import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

VERDICTS = {"PASS", "START_REJECTED", "CRASH_ON_START", "PROCESS_GONE", "SCOPE_FENCED",
            "NO_FOREGROUND_SCREEN", "NOT_TESTED", "IMPORT_FAILED"}

checks = []


def check(name, ok, detail, ref=""):
    checks.append((name, bool(ok), detail, ref))
    return ok


def load_jsonl(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, "r", encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            row["_ref"] = f"{os.path.basename(path)}:{lineno}"
            rows.append(row)
    return rows


def last_by_package(rows, phase):
    out = {}
    for row in rows:
        if row.get("phase") == phase and row.get("package"):
            out[row["package"]] = row
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="compat-run1")
    parser.add_argument("--report", default=os.path.join(ROOT, "Tripoder-兼容性测试报告.md"))
    options = parser.parse_args()

    run_dir = os.path.join(ROOT, "artifacts", options.run)
    evidence = os.path.join(run_dir, "evidence")
    journal_path = os.path.join(run_dir, "journal.jsonl")
    retry_path = os.path.join(ROOT, "artifacts", "compat-run1-retry", "retry.jsonl")
    retry2_path = os.path.join(ROOT, "artifacts", "compat-run1-retry2", "retry.jsonl")

    # ---------- 1. device inventory: 130 raw packages - 7 fixtures = 123 apps ----------
    raw_path = os.path.join(evidence, "01-pm-list-packages-3.raw.txt")
    raw_lines = [line.strip() for line in open(raw_path, encoding="utf-8") if line.strip()]
    raw_pkgs = [line.split(":", 1)[1] for line in raw_lines if line.startswith("package:")]
    fixtures = [p for p in raw_pkgs if p.startswith("dev.tripoder.")]
    app_set = sorted(p for p in raw_pkgs if not p.startswith("dev.tripoder."))
    check("设备清单行全部为 package: 前缀", len(raw_pkgs) == len(raw_lines),
          f"{len(raw_pkgs)}/{len(raw_lines)} 行", f"{raw_path}:1-{len(raw_lines)}")
    check("设备清单无重复包名", len(set(raw_pkgs)) == len(raw_pkgs), f"{len(set(raw_pkgs))} 个唯一包名",
          f"{raw_path}:1-{len(raw_lines)}")
    check("排除 7 个 dev.tripoder.* 夹具后得到 123 个应用", len(fixtures) == 7 and len(app_set) == 123,
          f"raw={len(raw_pkgs)} fixtures={len(fixtures)} apps={len(app_set)}",
          f"{evidence}/02-fixtures-7.txt:1-{len(fixtures)}; {evidence}/03-app-set-123.txt:1-{len(app_set)}")
    saved_set = [line.strip() for line in open(os.path.join(evidence, "03-app-set-123.txt"), encoding="utf-8")
                 if line.strip()]
    check("落盘的 123 应用清单与重新计算的集合一致", saved_set == app_set, f"{len(saved_set)} 行",
          f"{evidence}/03-app-set-123.txt:1-{len(saved_set)}")

    # ---------- 2. journal: every app identified and import attempted ----------
    journal = load_jsonl(journal_path)
    inv = last_by_package(journal, "inventory")
    imp = last_by_package(journal, "import")
    launch = last_by_package(journal, "launch")
    check("每个应用都有 inventory 记录", set(inv) == set(app_set), f"{len(inv)}/{len(app_set)}",
          f"{journal_path}:1-{len(journal)}")
    check("每个应用都有 import 记录", set(imp) == set(app_set), f"{len(imp)}/{len(app_set)}",
          f"{journal_path}:1-{len(journal)}")
    check("inventory 行带 codePath/versionCode（识别依据）",
          all(r.get("codePath") and r.get("versionCode") for r in inv.values()),
          "123/123 行含 codePath+versionCode", f"{journal_path}:1-123")

    # ---------- 3. retry overlay, then per-app verdict ----------
    retry = load_jsonl(retry_path)
    retry2 = load_jsonl(retry2_path)
    retry_imp = last_by_package(retry, "import")
    retry_launch = last_by_package(retry, "launch")
    imp.update(retry_imp)
    launch.update(retry_launch)

    import_counts = collections.Counter(imp[p].get("state", "MISSING") for p in app_set)
    imported = [p for p in app_set if imp[p].get("state") == "SUCCEEDED"]
    check("导入结果只取 SUCCEEDED/IMPORT_FAILED 两态",
          set(import_counts) <= {"SUCCEEDED", "IMPORT_FAILED"}, str(dict(import_counts)),
          f"{journal_path} + {retry_path}:1-{len(retry)}")
    check("导入成功的应用数量与逐应用记录一致", import_counts["SUCCEEDED"] == len(imported),
          f"{import_counts['SUCCEEDED']} 个", f"{journal_path}")

    missing_launch = [p for p in imported if p not in launch]
    check("每个导入成功的应用都有启动测试记录", not missing_launch, f"{len(imported) - len(missing_launch)}/{len(imported)}",
          "; ".join(x["_ref"] for x in list(launch.values())[:1]))
    bad_verdict = [p for p in imported if launch.get(p, {}).get("verdict") not in VERDICTS]
    check("启动判定都是已知枚举值", not bad_verdict, f"{len(imported)} 条判定", f"{journal_path}")

    verdicts = collections.Counter(launch[p].get("verdict") for p in imported)
    passed = verdicts.get("PASS", 0)
    failed = [p for p in imported if launch[p].get("verdict") != "PASS"]

    # ---------- 4. every failure carries a machine-readable error record ----------
    imp_fail_no_error = [p for p in app_set if imp[p].get("state") == "IMPORT_FAILED" and not imp[p].get("error")]
    launch_fail_no_evidence = [p for p in failed
                               if not (launch[p].get("error") or launch[p].get("fatals")
                                       or launch[p].get("errorCode") or launch[p].get("diagnostics")
                                       or launch[p].get("screenshot"))]
    check("每个导入失败项都有 error 字段", not imp_fail_no_error,
          f"{sum(1 for p in app_set if imp[p].get('state') == 'IMPORT_FAILED')} 条失败记录",
          f"{journal_path}")
    check("每个启动失败项都有 error/fatals/errorCode/diagnostics/截图之一", not launch_fail_no_evidence,
          f"{len(failed)} 条失败记录", ", ".join(launch_fail_no_evidence[:3]))

    # ---------- 5. report: numbers, root-cause sections, appendix ----------
    report = open(options.report, encoding="utf-8").read()
    rate = passed / len(imported) * 100 if imported else 0
    expected = [
        f"第三方包总数：**{len(app_set)}**",
        f"最终安装 **{import_counts['SUCCEEDED']}/{len(app_set)}** 成功",
        f"启动实测 **{len(imported)}** 个，首屏通过 **{passed}**（{rate:.1f}%）",
        f"| 导入成功 | {import_counts['SUCCEEDED']} |",
        f"| 首屏通过（复测后） | {passed} |",
    ]
    for text in expected:
        check(f"报告包含数字：{text}", text in report, "命中" if text in report else "缺失", options.report)

    summary_path = os.path.join(run_dir, "final-summary.json")
    summary = json.load(open(summary_path, encoding="utf-8"))
    check("final-summary 的 passed 与复算一致", summary.get("passed") == passed,
          f"summary={summary.get('passed')} recomputed={passed}", summary_path)
    check("final-summary 的 imported 与复算一致", summary.get("imported") == import_counts["SUCCEEDED"],
          f"summary={summary.get('imported')} recomputed={import_counts['SUCCEEDED']}", summary_path)
    check("final-summary 的 verdict 分布与复算一致", dict(summary.get("counts", {})) == dict(verdicts),
          f"{dict(verdicts)}", summary_path)
    check("final-summary 的 import 分布与复算一致",
          dict(summary.get("import_counts", {})) == dict(import_counts), f"{dict(import_counts)}", summary_path)

    # root-cause sections: every failing package must appear under a ### 4.x/### 5.x table
    section_of = {}
    current = ""
    for line in report.splitlines():
        match = re.match(r"^### ([45]\.\d+) (.*?)（(\d+) 个）", line)
        if match:
            current = match.group(1)
            continue
        if line.startswith("## "):
            current = ""
        if current:
            found = re.findall(r"`([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)`", line)
            for pkg in found:
                if pkg in app_set:
                    section_of[pkg] = current
    failures_all = [p for p in app_set if imp[p].get("state") == "IMPORT_FAILED"] + failed
    uncovered = [p for p in failures_all if p not in section_of]
    check("每个失败应用都在 §4/§5 有根因小节", not uncovered, f"{len(failures_all) - len(uncovered)}/{len(failures_all)}",
          ", ".join(uncovered[:5]))

    appendix_rows = [line for line in report.splitlines() if re.match(r"^\| \d+ \| `[^`]+` \|", line)]
    check("附录 B 覆盖全部应用", len(appendix_rows) == len(app_set), f"{len(appendix_rows)}/{len(app_set)} 行",
          options.report)
    bad_refs = []
    for line in appendix_rows:
        match = re.search(r"^\| \d+ \| `([^`]+)`", line)
        pkg = match.group(1) if match else ""
        refs = len(re.findall(r"(?:journal|retry)\.jsonl:\d+", line))
        need = 1 if imp.get(pkg, {}).get("state") == "IMPORT_FAILED" else 2
        if not re.search(r"evidence/03-app-set-123\.txt:\d+", line) or refs < need:
            bad_refs.append(line)
    check("附录 B 每行都给出清单行 + 导入行（+ 导入成功者的启动行）", not bad_refs,
          f"{len(appendix_rows) - len(bad_refs)}/{len(appendix_rows)} 行",
          (bad_refs[0][:120] if bad_refs else options.report))

    # 逐行打开被引用的物理行，确认行号确实指向同一个包名（不是“看起来像”的引用）
    cache = {}

    def read_line(rel):
        path = os.path.join(ROOT, "artifacts", options.run, rel) if not rel.startswith("artifacts") else os.path.join(ROOT, rel)
        if path not in cache:
            cache[path] = open(path, encoding="utf-8").read().splitlines()
        return cache[path]

    def resolve(ref):
        match = re.match(r"(.+?):(\d+)$", ref)
        if not match:
            return ""
        name, lineno = match.group(1), int(match.group(2))
        candidates = [os.path.join(ROOT, "artifacts", options.run, name),
                      os.path.join(ROOT, "artifacts", "compat-run1-retry", os.path.basename(name)),
                      os.path.join(ROOT, "artifacts", "compat-run1-retry2", os.path.basename(name))]
        path = next((c for c in candidates if os.path.exists(c)), "")
        if not path:
            return "<missing file>"
        lines = cache.setdefault(path, open(path, encoding="utf-8").read().splitlines())
        return lines[lineno - 1] if 0 < lineno <= len(lines) else "<out of range>"

    mismatched = []
    refs_total = 0
    for line in appendix_rows:
        pkg = re.match(r"^\| \d+ \| `([^`]+)`", line).group(1)
        refs = re.findall(r"`((?:evidence/03-app-set-123\.txt|journal\.jsonl|retry\.jsonl|retry2\.jsonl):\d+)`", line)
        refs_total += len(refs)
        for ref in refs:
            content = resolve(ref)
            ok = (content.strip() == pkg) if ref.startswith("evidence/03-app-set") else (f'"package": "{pkg}"' in content)
            if not ok:
                mismatched.append(f"{pkg} -> {ref} => {content[:60]}")
    check("附录 B 引用的行号确实指向同一包名（逐行打开核对）", not mismatched and refs_total >= len(appendix_rows) * 2,
          f"{len(appendix_rows)} 行 / {refs_total} 个引用",
          (mismatched[0] if mismatched else "all refs resolve"))

    # ---------- 6. runtime-mode coverage ----------
    mode_rows = [row for row in journal if row.get("phase") == "launch"]
    switched = [row for row in mode_rows if row.get("modeChanged") or row.get("mode")]
    check("矩阵运行模式：journal 中没有 set_mode 记录（全部为默认 THIN）", not switched,
          f"{len(mode_rows)} 条 launch / {len(switched)} 条含模式切换", journal_path)

    thick_dir = os.path.join(ROOT, "artifacts", "compat-thick-pilot")
    thick2_dir = os.path.join(ROOT, "artifacts", "compat-thick-pilot2")
    thick_rows, thick2_rows = [], []
    if os.path.exists(os.path.join(thick_dir, "journal.jsonl")):
        thick_rows = [json.loads(line) for line in open(os.path.join(thick_dir, "journal.jsonl"), encoding="utf-8") if line.strip()]
    if os.path.exists(os.path.join(thick2_dir, "journal.jsonl")):
        thick2_rows = [json.loads(line) for line in open(os.path.join(thick2_dir, "journal.jsonl"), encoding="utf-8") if line.strip()]
    thick_ok = ([row.get("verdict") for row in thick_rows] == ["START_REJECTED", "MODE_SET_FAILED", "START_REJECTED", "START_REJECTED"]
                and len(thick2_rows) == 1 and thick2_rows[0].get("verdict") == "SCOPE_FENCED")
    check("THICK 补测记录存在且与报告第 9 节一致（4 + 1 条，全部被容量/fence 拒绝）", thick_ok,
          f"pilot={len(thick_rows)} 条 pilot2={len(thick2_rows)} 条", "artifacts/compat-thick-pilot/EVIDENCE.md:1-53")

    # ---------- 7. print + persist ----------
    width = max(len(name) for name, *_ in checks)
    lines = ["兼容性测试记录复核（verify_evidence.py）",
             f"run={options.run} report={options.report}",
             f"device raw packages={len(raw_pkgs)} fixtures={len(fixtures)} apps={len(app_set)}",
             ""]
    failures = 0
    for name, ok, detail, ref in checks:
        lines.append(f"[{'PASS' if ok else 'FAIL'}] {name.ljust(width)}  {detail}  {ref}")
        failures += 0 if ok else 1
    lines += ["",
              f"导入：{import_counts['SUCCEEDED']} 成功 / {import_counts.get('IMPORT_FAILED', 0)} 失败（共 {len(app_set)}）",
              f"启动：{len(imported)} 个实测，PASS {passed}（{rate:.1f}%），失败 {len(failed)}",
              f"逐应用记录：journal.jsonl 共 {len(journal)} 行；复测 retry.jsonl {len(retry)} 行；隔离复测 retry2 {len(retry2)} 行",
              f"结果：{'全部通过' if failures == 0 else f'{failures} 项失败'}",
              "",
              "sha256："]
    import hashlib
    for path in [options.report, journal_path, os.path.join(evidence, "03-app-set-123.txt"),
                 os.path.join(evidence, "01-pm-list-packages-3.raw.txt")]:
        digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
        lines.append(f"  {digest}  {os.path.relpath(path, ROOT)}")
    output = "\n".join(lines)
    print(output)
    os.makedirs(evidence, exist_ok=True)
    with open(os.path.join(evidence, "06-verification.txt"), "w", encoding="utf-8") as handle:
        handle.write(output + "\n")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
