#!/usr/bin/env python3
"""门面抛出点审计：判据是「平台在这个调用点会失败吗」。

第 9 批（门面断言复核）用。纯静态、不需要设备：它把 android/runtime 里
`throw new X("CODE")` 一类抛出点按**入口家族**归组，再看每个 CODE 是否
落在「平台在该调用点也会失败」的白名单里。

三分类（沿用 facade-exception-contract 判据）：
  platform-throw   平台在该调用点自己会抛 → 保留（类型要贴近平台原话）
  must-degrade     平台在该调用点不失败 → 必须降级为返回值/丢弃，不得抛
  needs-review     未分类，审计时人工判

家族是按「这段代码跑在谁的栈上」划的，不是按文件：
  receiver-pointer-stack  RuntimeReceiverProxy.onReceive / RuntimeReceivers.before/after
                          跑在系统广播分发线程（=客体的 onReceive 栈），异常直接升级为 FATAL
  package-endpoint        RuntimePackages.dispatch / RuntimePackageBridge
                          跑在客体调 PackageManager 的 Binder 调用里，同属客体栈
  getsystemservice        GuestContext.getSystemService → Runtime*.facade()
                          跑在客体调 Context.getSystemService 的栈上
  host-internal           其余（运行时自身不变式，暂不参与判定）

用法:
  python3 android/scripts/facade_throw_audit.py            # 报告，exit 0
  python3 android/scripts/facade_throw_audit.py --strict   # 有 must-degrade / needs-review 时 exit 1
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "android/runtime/src/dev/tripoder/runtime"

THROW = re.compile(r'throw new ([A-Za-z_][A-Za-z0-9_.]*)\(\s*"([A-Z][A-Z0-9_]*)"')

# 只有明确判定过的 code 才写进来。
PLATFORM_THROW = {
    # 平台对「从未被派发的 receiver」调 getResultData/setResultData 会抛
    # IllegalStateException(checkSynchronousHint)，同一点，保留。
    "ANDROID_PENDING_RESULT_REQUIRED",
    # 平台参数通道：null / 形状不对，平台自己是 IAE/NPE
    "MEMORY_INFO_REQUIRED",
    "PROCESS_MEMORY_PIDS_REQUIRED",
}

MUST_DEGRADE = {
    # --- receiver-pointer-stack ---
    "BROADCAST_FINAL_RECEIVER_MISMATCH",
    "RECEIVER_NOT_OWNED",
    "RECEIVER_ACTIVATION_UNCONFIRMED",
    "SYSTEM_RECEIVER_NOT_REGISTERED",
    "BROADCAST_RECEIVER_MISMATCH",
    "BROADCAST_ORDERED_MISMATCH",
    "PENDING_RESULT_REUSED",
    "RECEIVER_CLAIM_FAILED",
    "RECEIVER_CALLBACK_STACK_MISMATCH",
    # --- package-endpoint ---
    "PACKAGE_ENDPOINT_NOT_READY",
    "PACKAGE_CACHED_ENDPOINT_CHANGED",
    # --- getsystemservice ---
    "ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID",
    "PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE",
    "CONNECTIVITY_FACADE_IDENTITY_INVALID",
    "TELEPHONY_FACADE_IDENTITY_INVALID",
    "AUDIO_FACADE_IDENTITY_INVALID",
    "PLATFORM_AUDIO_UNAVAILABLE",
    "AUDIO_CALLBACK_OWNER_INVALID",
    "AUDIO_PROPERTY_UNSUPPORTED",
    "BATTERY_FACADE_IDENTITY_INVALID",
    "LOCATION_FACADE_IDENTITY_INVALID",
    "POWER_FACADE_IDENTITY_INVALID",
    "USER_RESTRICTION_QUERY_SHAPE",
    "USER_RESTRICTION_QUERY_OUTSIDE_INSTALL",
    "ROLE_HELD_QUERY_SHAPE",
    "ROLE_HELD_QUERY_OUTSIDE_INSTALL",
    "NETWORK_STATS_FACADE_REQUIRED",
    "TRAFFIC_PHYSICAL_UID_NOT_GRANTED",
    "WIFI_P2P_FACADE_REQUIRED",
    "PLATFORM_WIFI_P2P_REQUIRED",
    "WIFI_P2P_CONTEXT_OWNER_INVALID",
}

RECEIVER_FILES = {"RuntimeReceiverProxy.java", "RuntimeReceivers.java"}
PACKAGE_FILES = {"RuntimePackages.java", "RuntimePackageBridge.java"}


def family(path, code):
    name = path.name
    if name in RECEIVER_FILES:
        return "receiver-pointer-stack"
    if name in PACKAGE_FILES:
        return "package-endpoint"
    if name == "GuestContext.java":
        return "getsystemservice"
    if code.endswith(("_FACADE_IDENTITY_INVALID", "_FACADE_REQUIRED", "_OWNER_INVALID")):
        return "getsystemservice"
    if code.endswith("_UNAVAILABLE") and code.startswith("PLATFORM_"):
        return "getsystemservice"
    return "host-internal"


def classify(code):
    if code in PLATFORM_THROW:
        return "platform-throw"
    if code in MUST_DEGRADE:
        return "must-degrade"
    return "needs-review"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    rows = []
    for path in sorted(RUNTIME.glob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for m in THROW.finditer(text):
            code = m.group(2)
            rows.append(
                {
                    "file": path.name,
                    "line": text.count("\n", 0, m.start()) + 1,
                    "code": code,
                    "family": family(path, code),
                    "class": classify(code),
                }
            )

    guest_facing = [r for r in rows if r["family"] != "host-internal"]
    violations = [r for r in guest_facing if r["class"] in ("must-degrade", "needs-review")]
    counts = {
        "total": len(rows),
        "guest_facing": len(guest_facing),
        "must_degrade": sum(1 for r in rows if r["class"] == "must-degrade"),
        "needs_review": sum(1 for r in rows if r["class"] == "needs-review"),
        "platform_throw": sum(1 for r in rows if r["class"] == "platform-throw"),
        "violations": len(violations),
    }

    by_family = {}
    for r in guest_facing:
        by_family.setdefault(r["family"], []).append(r)

    for fam in sorted(by_family):
        print("## " + fam)
        for r in sorted(by_family[fam], key=lambda r: (r["class"], r["file"], r["line"])):
            mark = {"platform-throw": "keep ", "must-degrade": "FAIL ", "needs-review": "?????"}[r["class"]]
            print("  %s %s:%s  %s" % (mark, r["file"], r["line"], r["code"]))
        print()

    print("total_throwing_sites=%d" % counts["total"])
    print(
        "guest_facing=%d must_degrade=%d needs_review=%d platform_throw=%d"
        % (counts["guest_facing"], counts["must_degrade"], counts["needs_review"], counts["platform_throw"])
    )
    print(
        json.dumps(
            {"metric": len(violations), "summary": "guest-facing throws to fix: %d" % len(violations)},
            ensure_ascii=False,
        )
    )

    if args.strict and violations:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
