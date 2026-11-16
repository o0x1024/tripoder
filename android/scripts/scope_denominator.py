#!/usr/bin/env python3
"""从设备事实重建 scope-targets.json（分母的唯一来源）。

语义（2026-11-16 用户裁定）：分母 = 用户自己装的第三方应用。
判据不用包名前缀、也不用 APK 路径（两条都自相矛盾）：
  预装(出厂) = firstInstallTime 落在出厂映像写入窗口（1970-01-17 16:09:4x，连续 7 秒）。
               被商店更新过的一方应用会搬到 /data/app，但时间戳仍是出厂那一刻，所以它们
               照样判为预装（com.miui.gallery/player/video、com.xiaomi.shop…共 19 个）。
  预装(OTA)  = 路径在系统映像但时间戳很晚（本机 1 个：com.xiaomi.bestphotos 2026-09-24）。
  其余       = 用户自装。其中有一簇全是 2000-01-01 00:00:00（时间未记录的哨兵值，
               成员是 WPS/通义/高德/大众点评等），语义上属用户自装，脚本单独标注不并入预装。

输入（两次只读扫描，均可复现）：
  adb shell 'for p in $(pm list packages -3 | sed s/package://); do ...firstInstallTime...' > install_times.txt
  adb shell pm list packages -f -3                                              > pkg_paths.txt
输出：
  docs/evidence/third-party-batch-advance-20261113/scope-targets.json
  stdout：分类表 + 双向矛盾（矛盾才是需要人看的部分）

用法：
  python3 android/scripts/scope_denominator.py [--times F] [--paths F] [--write]
不带 --write 只打印，不改文件。
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re

IMAGE_PREFIX = ("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/cust/")
FACTORY_WINDOW = re.compile(r"^1970-01-17 16:09:4[0-9]$")
SENTINEL = "2000-01-01 00:00:00"
EVID = os.path.join("docs", "evidence", "third-party-batch-advance-20261113")
OUT = os.path.join(EVID, "scope-targets.json")


def load_times(path: str) -> dict[str, str]:
    """取证行形如 `pkg|1970-01-1716:09:41`：日期与时间之间没有空格，必须归一化。

    不归一化会让所有簇判定落空，并安静地给出「预装=0 / 用户自装=120」的假结论。
    """
    times: dict[str, str] = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if "|" not in line:
            continue
        pkg, _, raw = line.partition("|")
        pkg, raw = pkg.strip(), raw.strip()
        m = re.match(r"^(\d{4}-\d{2}-\d{2})\s*(\d{2}:\d{2}:\d{2})$", raw)
        if pkg:
            times[pkg] = "%s %s" % (m.group(1), m.group(2)) if m else raw
    return times


def load_paths(path: str) -> dict[str, str]:
    paths: dict[str, str] = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if "=" in line:
            p, _, pkg = line.rpartition("=")
            paths[pkg.strip()] = p.strip()
    return paths


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--times", default="/tmp/install_times.txt")
    ap.add_argument("--paths", default="/tmp/pkg_paths.txt")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    times, paths = load_times(a.times), load_paths(a.paths)
    if not times:
        raise SystemExit("解析不到 firstInstallTime：%s" % a.times)
    if not paths:
        raise SystemExit("解析不到 APK 路径：%s" % a.paths)

    def in_image(pkg: str) -> bool:
        return paths.get(pkg, "").startswith(IMAGE_PREFIX)

    groups: dict[str, list[str]] = collections.defaultdict(list)
    for pkg, stamp in times.items():
        groups[stamp].append(pkg)

    factory = sorted(p for s, ps in groups.items() if FACTORY_WINDOW.match(s) for p in ps)
    # 路径在映像但时间戳不在出厂窗口：OTA 加装的预装。数量必须少且可解释，多了就是判据本身有问题。
    ota = sorted(p for p in times if in_image(p) and p not in factory)
    sentinel = sorted(groups.get(SENTINEL, []))
    preload = sorted(set(factory) | set(ota))
    user = sorted(set(times) - set(preload))

    print("pm -3 全量=%d  出厂窗口=%d  OTA 预装=%d  预装合计=%d  用户自装=%d"
          % (len(times), len(factory), len(ota), len(preload), len(user)))
    print("出厂窗口内路径分布：image=%d data=%d（data 是被商店更新过的一方应用）"
          % (sum(1 for p in factory if in_image(p)), sum(1 for p in factory if not in_image(p))))
    print("哨兵 %s：%d 包（时间未记录；按包名语义归用户自装）%s"
          % (SENTINEL, len(sentinel), "" if sentinel else " — 无"))
    print("OTA 预装（路径在映像、时间戳很晚）：%s" % (", ".join(
        "%s@%s" % (p, times[p]) for p in ota) or "无"))
    if len(ota) > 5:
        print("⚠ OTA 预装数量异常（>5），判据需复核，不写入")

    scope = {
        "generated_by": "firstInstallTime 聚类（出厂窗口 1970-01-17 16:09:4x + OTA 加装），语义=用户自装",
        "semantics": "user_installed",
        "semantics_note": "2026-11-16 用户裁定：分母=用户自己装的第三方应用",
        "pm3_total": len(times),
        "factory_window": factory,
        "ota_preload": ota,
        "preload": preload,
        "preload_count": len(preload),
        "unrecorded_install_time": sentinel,
        "third_party": user,
        "third_party_count": len(user),
    }
    if a.write and len(ota) <= 5:
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(scope, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("wrote %s" % OUT)
    else:
        print("dry-run（加 --write 落盘）")


if __name__ == "__main__":
    main()
