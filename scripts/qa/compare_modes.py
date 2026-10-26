#!/usr/bin/env python3
"""THIN vs THICK verdict comparison for the same device package set.

Reads two campaign journals (latest row per package) and reports per-app transitions plus the
category totals, so a mode regression is visible without re-reading either run by hand.
"""
import argparse
import collections
import json
import os


def load(path, phase):
    latest = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            if row.get("phase") != phase or not row.get("package"):
                continue
            if row.get("verdict") or row.get("state"):
                latest[row["package"]] = row
    return latest


def label(row):
    if not row:
        return "MISSING"
    return row.get("verdict") or row.get("state") or "UNKNOWN"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--thin", default="artifacts/compat-run1/journal.jsonl")
    parser.add_argument("--thick", default="artifacts/compat-thick-run1/journal.jsonl")
    parser.add_argument("--out", default="artifacts/compat-thick-run1/comparison")
    options = parser.parse_args()

    thin = load(options.thin, "launch")
    thick = load(options.thick, "launch")
    thick_import = load(options.thick, "import")
    packages = sorted(set(thin) | set(thick) | set(thick_import))

    rows, transitions = [], collections.Counter()
    for package in packages:
        a, b = label(thin.get(package)), label(thick.get(package))
        if b == "MISSING" and thick_import.get(package):
            b = label(thick_import[package])
        transitions[(a, b)] += 1
        rows.append({"package": package, "thin": a, "thick": b,
                     "thinError": (thin.get(package, {}).get("error") or "")[:160],
                     "thickError": (thick.get(package, {}).get("error") or "")[:160],
                     "changed": a != b})

    summary = {
        "packages": len(packages),
        "thin": dict(collections.Counter(r["thin"] for r in rows)),
        "thick": dict(collections.Counter(r["thick"] for r in rows)),
        "changed": sum(1 for r in rows if r["changed"]),
        "transitions": [{"thin": a, "thick": b, "count": n}
                        for (a, b), n in transitions.most_common()],
    }
    os.makedirs(os.path.dirname(options.out) or ".", exist_ok=True)
    with open(options.out + ".json", "w", encoding="utf-8") as handle:
        json.dump({"summary": summary, "rows": rows}, handle, indent=1, ensure_ascii=False)

    lines = ["| # | 包名 | THIN | THICK | 变化 |", "|---|---|---|---|---|"]
    for index, row in enumerate(rows, 1):
        mark = "" if not row["changed"] else ("✅ 修复" if row["thick"] == "PASS" else "⚠️ 退化")
        lines.append(f"| {index} | `{row['package']}` | {row['thin']} | {row['thick']} | {mark} |")
    with open(options.out + ".md", "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print(f"written: {options.out}.json / {options.out}.md")


if __name__ == "__main__":
    main()
