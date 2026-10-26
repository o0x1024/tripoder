#!/usr/bin/env bash
# Drive the whole 123-app universe to a THICK verdict, one generation at a time.
#
# Two hard facts learned the hard way, both encoded here:
#   1. `--limit N` is not a cursor: the driver sorts packages and takes the head, so passing
#      `--limit` every round would re-examine the same first N forever. The next batch is
#      therefore selected explicitly with `--only`.
#   2. A failed launch fences its space (`RECOVERY_SCOPE_UNKNOWN`), a fenced space refuses
#      deletion, and its affinity lease is never returned. The pool holds 8, so roughly eight
#      non-PASS apps exhaust it and every later launch is refused. There is no repair path
#      short of wiping engine data, so this runner wipes, re-pairs and resumes remainder-wise.
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT="compat-thick-run3"
UNIVERSE="artifacts/compat-thick-run2/journal.jsonl"   # 123-app inventory taken in run2
DEVICE="d861a4fa"
BATCH=6
MAX_GENERATIONS=10
LOG=/tmp/compat-thick-run4.log

say() { echo "$*" >>"$LOG"; }

mcp_alive() {
  timeout 45 python3 tools/tpmcp.py call runtime_query '{"action":"status"}' >/dev/null 2>&1
}

pending() {
  python3 - "$UNIVERSE" "artifacts/$OUT/journal.jsonl" "$BATCH" <<'PY'
import json, sys, os
universe, journal, batch = sys.argv[1], sys.argv[2], int(sys.argv[3])
# An import verdict lives in `state`, a launch verdict in `verdict`; counting only `verdict`
# makes every import failure look un-judged, so the same dead batch is retried forever and the
# packages behind it are starved.
LAUNCH_FINAL = {"PASS", "START_REJECTED", "CRASH_ON_START", "PROCESS_GONE", "NO_FOREGROUND_SCREEN"}
IMPORT_FINAL = {"IMPORT_FAILED", "UNSUPPORTED"}
allpkgs = []
seen = set()
for line in open(universe, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    row = json.loads(line)
    if row.get("phase") == "inventory" and row.get("package"):
        if row["package"] not in seen:
            seen.add(row["package"])
            allpkgs.append(row["package"])
imp, ver = {}, {}
if os.path.exists(journal):
    for line in open(journal, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        p = row.get("package")
        if not p:
            continue
        if row.get("phase") == "import" and row.get("state"):
            imp[p] = row["state"]
        if row.get("phase") == "launch" and row.get("verdict"):
            ver[p] = row["verdict"]
done = set()
for p in allpkgs:
    if ver.get(p) in LAUNCH_FINAL or imp.get(p) in IMPORT_FINAL:
        done.add(p)
left = [p for p in allpkgs if p not in done]
print(len(allpkgs), len(done), len(left))
print(",".join(left[:batch]))
PY
}

repair_connection() {
  say "--- repair_connection $(date -Iseconds)"
  # A dark screen makes `uiautomator dump` return an empty tree, so every later tap silently
  # misses and the export never happens. Wake first, and keep the screen on for the whole run.
  adb -s "$DEVICE" shell svc power stayon true >/dev/null 2>&1
  adb -s "$DEVICE" shell input keyevent 224 >/dev/null 2>&1
  adb -s "$DEVICE" shell wm dismiss-keyguard >/dev/null 2>&1
  sleep 2
  adb -s "$DEVICE" shell monkey -p dev.tripoder.engine -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  local attempt newest_before newest_after
  for attempt in 1 2 3; do
    # The settings tab is a fixed bottom anchor; tapping it by coordinate is more reliable than
    # text matching, because the page also carries a "设置" heading that is not the tab.
    adb -s "$DEVICE" shell input tap 906 2524 >/dev/null 2>&1
    sleep 3
    adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr2.xml >/dev/null 2>&1
    adb -s "$DEVICE" shell cat /sdcard/_rr2.xml 2>/dev/null > /tmp/_rr2.xml
    if ! grep -q "导出连接配置" /tmp/_rr2.xml 2>/dev/null; then
      # Not on the settings page: try the controller-reconnect button, then the tab again.
      adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr.xml >/dev/null 2>&1
      adb -s "$DEVICE" shell cat /sdcard/_rr.xml 2>/dev/null > /tmp/_rr.xml
      RC=$(python3 - <<'PY'
import re
try:
    xml = open("/tmp/_rr.xml", encoding="utf-8", errors="ignore").read()
except OSError:
    raise SystemExit
m = re.search(r'text="重新连接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
PY
)
      if [ -n "$RC" ]; then
        adb -s "$DEVICE" shell input tap $RC >/dev/null 2>&1
        sleep 5
        adb -s "$DEVICE" shell input tap 906 2524 >/dev/null 2>&1
        sleep 3
        adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr2.xml >/dev/null 2>&1
        adb -s "$DEVICE" shell cat /sdcard/_rr2.xml 2>/dev/null > /tmp/_rr2.xml
      fi
    fi
    POS=$(python3 - <<'PY'
import re
xml = open("/tmp/_rr2.xml", encoding="utf-8", errors="ignore").read()
for label in ("开启网络 MCP", "导出连接配置"):
    m = re.search(r'text="' + re.escape(label) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
PY
)
    if [ -z "$POS" ]; then
      say "--- repair attempt $attempt: settings controls not found"
      sleep 3
      continue
    fi
    newest_before=$(adb -s "$DEVICE" shell 'ls -t /storage/emulated/0/tripoder/ 2>/dev/null | head -1' | tr -d '\r')
    echo "$POS" | while read -r x y; do
      [ -n "${x:-}" ] && adb -s "$DEVICE" shell input tap "$x" "$y" >/dev/null 2>&1 && sleep 5
    done
    adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr3.xml >/dev/null 2>&1
    SAVE=$(adb -s "$DEVICE" shell cat /sdcard/_rr3.xml 2>/dev/null | python3 -c '
import re, sys
m = re.search(r"text=\"保存\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read())
print(((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else "")
')
    [ -n "$SAVE" ] && adb -s "$DEVICE" shell input tap $SAVE >/dev/null 2>&1 && sleep 5
    newest_after=$(adb -s "$DEVICE" shell 'ls -t /storage/emulated/0/tripoder/ 2>/dev/null | head -1' | tr -d '\r')
    if [ -z "$newest_after" ] || [ "$newest_after" = "$newest_before" ]; then
      # Pulling the previous generation's file would silently keep a dead token; insist on a
      # fresh export instead.
      say "--- repair attempt $attempt: no new export ($newest_after)"
      adb -s "$DEVICE" shell input keyevent 4 >/dev/null 2>&1
      sleep 2
      continue
    fi
    adb -s "$DEVICE" pull "/storage/emulated/0/tripoder/$newest_after" "$HOME/.tripoder/tripoder-connection.json" >/dev/null 2>&1
    chmod 600 "$HOME/.tripoder/tripoder-connection.json" 2>/dev/null
    say "--- pulled $newest_after"
    if mcp_alive; then return 0; fi
    sleep 3
  done
  return 1
}


say "=== run4 start $(date -Iseconds) (finishing tail)"
last_judged=-1
stalled=0
generation=0
while [ "$generation" -lt "$MAX_GENERATIONS" ]; do
  read -r total done_n left_n <<<"$(pending | head -1)"
  batch=$(pending | tail -1)
  say "[gen $generation] total=$total judged=$done_n left=$left_n"
  if [ "$done_n" -eq "$last_judged" ]; then stalled=$((stalled + 1)); else stalled=0; fi
  last_judged=$done_n
  if [ "$stalled" -ge 2 ]; then say "=== wedged: judged unchanged for $stalled generations"; break; fi
  if [ "$left_n" -eq 0 ]; then say "=== all judged"; break; fi
  if [ -z "${batch:-}" ]; then say "=== no batch selected"; break; fi

  if ! mcp_alive; then
    repair_connection
    mcp_alive || { say "[gen $generation] MCP down after repair"; sleep 45; continue; }
  fi

  mark=$(wc -c <"$LOG")
  adb -s "$DEVICE" shell svc power stayon true >/dev/null 2>&1
  adb -s "$DEVICE" shell input keyevent 224 >/dev/null 2>&1
  adb -s "$DEVICE" shell wm dismiss-keyguard >/dev/null 2>&1
  timeout 2400 python3 scripts/qa/device_campaign.py \
      --out "$OUT" --phase all --mode thick --recycle --retry-failed \
      --wait-ms 15000 --settle-ms 1200 --only "$batch" >>"$LOG" 2>&1
  rc=$?
  chunk=$(tail -c +$((mark + 1)) "$LOG")
  say "<<< gen $generation rc=$rc $(date -Iseconds)"

  if printf '%s' "$chunk" | grep -qE 'CAPACITY_EXHAUSTED|ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED'; then
    say "=== capacity exhausted; stopped with engine data and UNKNOWN receipts preserved"
    exit 2
  fi
  # No capacity error but the batch made no progress at all: treat as wedged too.
  after=$(pending | head -1 | cut -d' ' -f3)
  if [ "${after:-0}" -eq "${left_n:-0}" ] && [ "$rc" -ne 0 ]; then
    say "[gen $generation] no progress and rc=$rc; repairing"
    repair_connection
  fi
  generation=$((generation + 1))
done
read -r total done_n left_n <<<"$(pending | head -1)"
say "=== run4 end $(date -Iseconds) judged=$done_n left=$left_n"
