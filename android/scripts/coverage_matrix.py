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
OUT = os.path.join(EVID, "coverage-matrix.md")

SELF_PACKAGES = {"dev.tripoder.q6", "dev.tripoder.q7", "dev.tripoder.controltest"}

VERDICT_CN = {
    "LOGIN_ENTRY": "登录入口可达",
    "LOGIN_PROMPT": "页面有登录提示（无输入框）",
    "CONSENT_UNRESOLVED": "停在同意层",
    "HOME_NO_ACCOUNT_UI": "已到页面，无账号 UI",
    "PAGE_EMPTY_USE_SHOT": "dump 空树，需截图判读",
    "NOT_TOP": "未上顶层",
    "TILE_NOT_FOUND": "tile 未找到",
    "NO_TILE_MAPPING": "未建立 tile 映射",
    "ENGINE_DOWN": "引擎未连接",
    "GUEST_KILLED_BY_DUMP": "dump 杀客体（旧口径，已作废：进程计数漏主进程）",
    "DUMP_UNAVAILABLE": "结构抓不到且进程消失（需截图判读）",
    "TILE_MISMATCH": "自动认领的 tile 启动出的不是该包 ⇒ 测度作废（batch34 首包踩到：被认成探针 StorageProbe）",
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
    third = [r for r in inv if not r["bundled"] and r["pkg"] not in SELF_PACKAGES]
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
    measured = [r for r in third if r["pkg"] in cov]
    # Rows whose inventory note already carries a measured conclusion from an earlier batch. Reported
    # separately, never merged into the pipeline count: these are different kinds of evidence.
    concluded = [r for r in third if r["note"] and "未测" not in r["note"]]
    lines.append("| `pm list packages -3` 全量 | %d |" % len(inv))
    lines.append("| 扣除厂商/系统捆绑 | %d |" % len(third))
    lines.append("| 已进流水线（有实测记录） | %d |" % len(measured))
    lines.append("| 盘点表已有结论（历史批次证据，未进流水线记录） | %d |" % len(concluded))
    # Union, not sum of the two rows above: a package can be both in the pipeline and concluded.
    with_evidence = {r["pkg"] for r in measured} | {r["pkg"] for r in concluded}
    lines.append("| 有证据合计（两者并集） | %d |" % len(with_evidence))
    lines.append("| 尚未实测 | %d |" % (len(third) - len(measured)))
    lines += ["", "## 逐项矩阵", "",
              "| # | 包名 | tile | 容器内 | 实测结论 | 落点 top | 客体重启不影响计数 | 备注 |",
              "|---|---|---|---|---|---|---|---|"]
    for r in inv:
        if r["bundled"] or r["pkg"] in SELF_PACKAGES:
            continue
        c = cov.get(r["pkg"])
        tile = labels.get(r["pkg"], "")
        if c:
            page = c.get("page") or {}
            verdict = "%s（%s）" % (c.get("verdict", ""), VERDICT_CN.get(c.get("verdict", ""), "?"))
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
    pending_rows = [r for r in third if r["pkg"] not in cov]
    runnable = [r for r in pending_rows if r["pkg"] in labels]
    needs_import = [r for r in pending_rows if r["pkg"] not in labels]
    lines += ["", "## 待推进队列（按处置分档）", "",
              "分档只按数据判：有 tile 映射的直接可跑，没有映射的要先 import_apps.sh。", "",
              "| 档 | 数量 | 跑法 |", "|---|---|---|",
              "| 可立即跑（tile 已建） | %d | `coverage_pipeline.py --no-import <pkgs>` |" % len(runnable),
              "| 需先导入（无 tile） | %d | `coverage_pipeline.py <pkgs>`（含导入） |" % len(needs_import),
              "", "### 可立即跑", ""]
    lines += ["- `%s`（%s）" % (r["pkg"], labels.get(r["pkg"], "")) for r in runnable] or ["- （无）"]
    lines += ["", "### 需先导入", ""]
    lines += ["- `%s`（%s）" % (r["pkg"], r["in_space"] or "未在容器内") for r in needs_import] or ["- （无）"]
    summary = {"third_party": len(third), "measured": len(measured), "concluded": len(concluded),
               "pending": len(third) - len(measured), "records": len(cov),
               "runnable": len(runnable), "needs_import": len(needs_import)}
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
