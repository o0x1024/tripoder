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
    "GUEST_KILLED_BY_DUMP": "dump 杀客体（剪贴板族）",
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
    summary = {"third_party": len(third), "measured": len(measured), "concluded": len(concluded),
               "pending": len(third) - len(measured), "records": len(cov)}
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
