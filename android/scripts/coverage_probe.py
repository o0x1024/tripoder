#!/usr/bin/env python3
"""覆盖验收探针：把「完成」变成一个可退出码判定的问题，而不是一句结论。

用户已裁定语义（分母=用户自己装的第三方应用），因此判据不再有歧义：

  分母 = scope-targets.json 的 third_party（firstInstallTime 聚类得出）减去容器自身包
  分子 = coverage.jsonl 里存在「非编排失败」判决的包
         （排除 TILE_NOT_FOUND / NO_TILE_MAPPING / TILE_NOT_CLAIMABLE / TILE_MISMATCH /
           ENGINE_DOWN / PIPELINE_ERROR —— 这些是「流水线跑过但没测到」，不是结论）

  全部覆盖 → exit 0；否则 exit 1 并打印缺失包名样本。
  metric 恒为完成百分比，供 CI/复算对比。

复用 coverage_matrix 的常量与加载函数而不另写一份：分子定义若出现第二份实现，两边一定会漂移，
而「两个数字打架」正是本项目反复踩到的坑。

用法：
  python3 android/scripts/coverage_probe.py            # 退出码即结论
  python3 android/scripts/coverage_probe.py --json     # 机器可读
"""
from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import coverage_matrix as M  # noqa: E402  同一个分子定义


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--sample", type=int, default=10)
    a = ap.parse_args()

    if not os.path.exists(M.SCOPE):
        print("缺少 %s：分母必须来自扫描产物" % M.SCOPE, file=sys.stderr)
        return 2
    scope = json.load(open(M.SCOPE, encoding="utf-8"))
    cov = M.coverage_rows()
    third = sorted(set(scope["third_party"]) - M.SELF_PACKAGES)

    def verdict(pkg: str) -> str:
        row = cov.get(pkg) or {}
        return row.get("verdict") or ""

    measured = [p for p in third if verdict(p) and verdict(p) not in M.NON_MEASURED]
    attempted = [p for p in third if p in cov]
    no_verdict = [p for p in attempted if p not in measured]      # 跑过但被判为「没测到」
    never = [p for p in third if p not in cov]                    # 还没进流水线

    metric = round(100.0 * len(measured) / len(third), 1) if third else 0.0
    ok = len(measured) == len(third)

    if a.json:
        print(json.dumps({
            "denominator": len(third),
            "measured": len(measured),
            "missing": len(third) - len(measured),
            "attempted_without_verdict": no_verdict,
            "never_attempted": never,
            "metric_percent": metric,
            "pass": ok,
            "semantics": scope.get("semantics", "unknown"),
            "scope_source": scope.get("generated_by", ""),
        }, ensure_ascii=False, indent=1))
        return 0 if ok else 1

    print("分母（用户自装，已排除容器自身包）= %d" % len(third))
    print("分子（非编排失败判决）              = %d" % len(measured))
    print("完成度                              = %.1f%%" % metric)
    if ok:
        print("PASS：全部覆盖")
        return 0

    print("FAIL：缺 %d 个" % (len(third) - len(measured)))
    if no_verdict:
        print("  跑过但没测到（%d）：" % len(no_verdict))
        for p in no_verdict[: a.sample]:
            print("    %-40s %s" % (p, verdict(p)))
    if never:
        print("  未进流水线（%d）：" % len(never))
        for p in never[: a.sample]:
            print("    %s" % p)
    return 1


if __name__ == "__main__":
    sys.exit(main())
