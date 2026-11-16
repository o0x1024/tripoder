#!/usr/bin/env python3
"""把 inventory.md（全量盘点）与 coverage.jsonl（流水线实测记录）拼成覆盖矩阵。

为什么单独一个脚本：矩阵要能从原始数据重新生成，否则「覆盖了多少」会随人工编辑漂移。
判据全部来自数据文件，脚本自己不写任何结论。

用法：python3 android/scripts/coverage_matrix.py           # 打印摘要
      python3 android/scripts/coverage_matrix.py --write   # 覆写 docs/.../coverage-matrix.md
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EVID = os.path.join(ROOT, "docs/evidence/third-party-batch-advance-20261113")
INV = os.path.join(EVID, "inventory.md")
COV = os.path.join(EVID, "coverage.jsonl")
LABELS = os.path.join(EVID, "tile-labels.json")
SCOPE = os.path.join(EVID, "scope-targets.json")
OUT = os.path.join(EVID, "coverage-matrix.md")

SELF_PACKAGES = {"dev.tripoder.q6", "dev.tripoder.q7", "dev.tripoder.controltest"}

# 「没测到」的码：进了流水线但没有判决。它们不能计入分子，否则矩阵会比验收探针乐观。
# 判据与验收探针一致（probe 里同一组码），两边必须同源，否则报告和验收会给出两个数。
NON_MEASURED = {
    "TILE_NOT_FOUND",
    "NO_TILE_MAPPING",
    "TILE_NOT_CLAIMABLE",
    "TILE_MISMATCH",
    "ENGINE_DOWN",
    "PIPELINE_ERROR",
}

VERDICT_CN = {
    "LOGIN_ENTRY": "登录入口可达",
    "LOGIN_PROMPT": "页面有登录提示（无输入框）",
    "CONSENT_UNRESOLVED": "停在同意层",
    "HOME_NO_ACCOUNT_UI": "已到页面，无账号 UI",
    "PAGE_EMPTY_USE_SHOT": "dump 空树，需截图判读",
    "NOT_TOP": "未上顶层",
    "TILE_NOT_FOUND": "tile 未找到",
    "NO_TILE_MAPPING": "未建立 tile 映射（旧码；batch35 起改为试认领 TILE_NOT_CLAIMABLE）",
    "ENGINE_DOWN": "引擎未连接",
    "GUEST_KILLED_BY_DUMP": "dump 杀客体（旧口径，已作废：进程计数漏主进程）",
    "DUMP_UNAVAILABLE": "结构抓不到且进程消失（需截图判读）",
    "TILE_MISMATCH": "自动认领的 tile 启动出的不是该包 ⇒ 测度作废（batch34 首包踩到：被认成探针 StorageProbe）",
    "TILE_NOT_CLAIMABLE": "试认领全部候选都不中 ⇒ 该包在 Workbench 里没有可核对的 tile（batch35 起代替 NO_TILE_MAPPING）",
    "PIPELINE_ERROR": "流水线异常",
}


def inventory_rows() -> list[dict]:
    rows = []
    for line in open(INV, encoding="utf-8"):
        if not line.startswith("| ") or line.startswith("| # ") or set(line.strip()) <= {"|", "-", " "}:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 6 or not cells[0].isdigit():
            continue
        rows.append({
            "n": int(cells[0]),
            "pkg": cells[1].strip("`"),
            "bundled": cells[2] == "是",
            "in_space": cells[3],
            "note": cells[4],
            "evidence": cells[5],
        })
    return rows


def coverage_rows() -> dict:
    latest = {}
    if os.path.exists(COV):
        for line in open(COV, encoding="utf-8"):
            if line.strip():
                r = json.loads(line)
                latest[r["pkg"]] = r
    return latest


def build() -> tuple[list[str], dict]:
    inv = inventory_rows()
    cov = coverage_rows()
    labels = json.load(open(LABELS, encoding="utf-8")) if os.path.exists(LABELS) else {}
    # 分母只有一个来源：scope-targets.json。它由扫描产生（pm list packages -3 -f + 判据），
    # 不能用 inventory.md 的「捆绑」列代替——那一列是名字判断，与路径/厂商判据会给出不同数字。
    if not os.path.exists(SCOPE):
        raise SystemExit("缺少 %s：分母必须来自扫描产物，不允许回退到 inventory 的捆绑列" % SCOPE)
    scope = json.load(open(SCOPE, encoding="utf-8"))
    scope_third = set(scope["third_party"]) - SELF_PACKAGES
    third = [r for r in inv if r["pkg"] in scope_third]
    absent_from_inventory = sorted(scope_third - {r["pkg"] for r in inv})
    lines = [
        "# 覆盖矩阵（由 inventory.md + coverage.jsonl 生成）",
        "",
        "生成器：`python3 android/scripts/coverage_matrix.py --write`。本表不含人工结论，",
        "「实测」列只来自流水线记录，其余列来自盘点表。",
        "",
        "## 状态码",
        "",
        "| 码 | 含义 |",
        "|---|---|",
    ]
    for k, v in VERDICT_CN.items():
        lines.append("| `%s` | %s |" % (k, v))
    lines += [
        "",
        "## 覆盖总览",
        "",
        "| 口径 | 数量 |",
        "|---|---|",
    ]
    def verdict_of(pkg: str) -> str:
        row = cov.get(pkg)
        return row.get("verdict", "") if row else ""

    # 分子口径与验收探针同源：有记录 且 判决不是「没测到」的码。
    measured = [r for r in third if verdict_of(r["pkg"]) and verdict_of(r["pkg"]) not in NON_MEASURED]
    unmeasured_attempted = [r for r in third if r["pkg"] in cov and r["pkg"] not in {m["pkg"] for m in measured}]
    # Rows whose inventory note already carries a measured conclusion from an earlier batch. Reported
    # separately, never merged into the pipeline count: these are different kinds of evidence.
    concluded = [r for r in third if r["note"] and "未测" not in r["note"]]
    lines.append("| 分母来源 | `scope-targets.json`（%s） |" % scope.get("generated_by", "scan"))
    lines.append("| 分母：真第三方 | %d |" % len(third))
    lines.append("| 已进流水线（有记录） | %d |" % len([r for r in third if r["pkg"] in cov]))
    lines.append("| 有判决（分子，排除「没测到」码） | %d |" % len(measured))
    lines.append("| 进了流水线但没测到 | %d |" % len(unmeasured_attempted))
    lines.append("| 盘点表已有结论（历史批次证据，未进流水线记录） | %d |" % len(concluded))
    # Union, not sum of the two rows above: a package can be both in the pipeline and concluded.
    with_evidence = {r["pkg"] for r in measured} | {r["pkg"] for r in concluded}
    lines.append("| 有证据合计（两者并集） | %d |" % len(with_evidence))
    lines.append("| 尚未实测 | %d |" % (len(third) - len(measured)))
    lines.append("| 完成度（分子/分母） | %.0f%% |" % (100.0 * len(measured) / len(third) if third else 0.0))
    lines += ["", "## 逐项矩阵", "",
              "| # | 包名 | tile | 容器内 | 实测结论 | 落点 top | 客体重启不影响计数 | 备注 |",
              "|---|---|---|---|---|---|---|---|"]
    for r in inv:
        if r["pkg"] not in scope_third:
            continue
        c = cov.get(r["pkg"])
        tile = labels.get(r["pkg"], "")
        if c:
            page = c.get("page") or {}
            code = c.get("verdict", "")
            label = "%s（%s）" % (code, VERDICT_CN.get(code, "?"))
            # 没测到的码不能当作「已实测」呈现：否则逐项表会比总览乐观，读表的人会被误导。
            verdict = ("未测 · " + label) if code in NON_MEASURED else label
            top = (page.get("top") or c.get("launch") or "").replace("dev.tripoder.q6/dev.tripoder.runtime.generated.", "")
            cnt = c.get("counters_after") or {}
            cnt_s = "fatal=%s adm=%s anr=%s" % (cnt.get("fatal"), cnt.get("admission"), cnt.get("anr")) if cnt else ""
            note = r["note"][:60]
        else:
            verdict = "未测" if "未测" in r["note"] or not r["note"] else "盘点有结论·待补流水线记录"
            top = ""
            cnt_s = ""
            note = r["note"][:60]
        lines.append("| %d | `%s` | %s | %s | %s | %s | %s | %s |" % (
            r["n"], r["pkg"], tile, r["in_space"] or "—", verdict, top, cnt_s, note))
    # 待推进队列：把「还没实测的 N 个」拆成可执行的两档，避免每次都要人工重算哪些能直接跑。
    # 分档判据只有数据：tile-labels.json 里有映射 = Workbench 里已有格子 = 流水线能直接点；
    # 没有映射 = 还没导入该空间 = 必须先走 import_apps.sh。不按应用名/类别猜。
    measured_pkgs = {m["pkg"] for m in measured}
    pending_rows = [r for r in third if r["pkg"] not in measured_pkgs]
    runnable = [r for r in pending_rows if r["pkg"] in labels]
    # 导入面已到 117/118，所以「没有 tile 映射」现在意味着「还没认领出这个包对应哪个格子」，
    # 不是「还没导入」。分档文案必须跟着实际前置条件走，否则会把人送去重跑导入。
    needs_claim = [r for r in pending_rows if r["pkg"] not in labels]
    lines += ["", "## 待推进队列（按处置分档）", "",
              "分档只按数据判：有 tile 映射的直接可跑，没有映射的先做认领（导入面已达 117/118）。", "",
              "| 档 | 数量 | 跑法 |", "|---|---|---|",
              "| 可立即跑（tile 已建） | %d | `coverage_pipeline.py --no-import <pkgs>` |" % len(runnable),
              "| 需先认领 tile（无映射） | %d | 先跑认领，再 `--no-import` |" % len(needs_claim),
              "", "### 可立即跑", ""]
    lines += ["- `%s`（%s）" % (r["pkg"], labels.get(r["pkg"], "")) for r in runnable] or ["- （无）"]
    lines += ["", "### 需先认领 tile", ""]
    lines += ["- `%s`（%s）" % (r["pkg"], r["in_space"] or "未在容器内") for r in needs_claim] or ["- （无）"]
    if absent_from_inventory:
        lines += ["", "### 分母里有、盘点表里没有（需补盘点行）", ""]
        lines += ["- `%s`" % p for p in absent_from_inventory]
    summary = {"third_party": len(third), "measured": len(measured), "concluded": len(concluded),
               "pending": len(third) - len(measured), "records": len(cov),
               "unmeasured_attempted": len(unmeasured_attempted),
               "runnable": len(runnable), "needs_claim": len(needs_claim),
               "absent_from_inventory": len(absent_from_inventory)}
    return lines, summary


def main() -> None:
    lines, summary = build()
    print(json.dumps(summary, ensure_ascii=False))
    if "--write" in sys.argv:
        body = "\n".join(lines) + "\n"
        with open(OUT, "w", encoding="utf-8") as f:
            f.write(body)
        print("written", OUT, len(body), "bytes")


if __name__ == "__main__":
    main()
