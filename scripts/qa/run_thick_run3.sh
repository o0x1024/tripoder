#!/usr/bin/env bash
# Full THICK pass over every app on the device, resumable and self-healing.
#
# Written after the run2 post-mortem: one engine drop used to strand every package behind it,
# because a refused launch fences its space and a fenced space can no longer be deleted, so
# its affinity lease is never returned. This runner keeps each batch small, verifies MCP
# before every batch, re-pairs the connection when the listener is gone, and lets the driver
# skip work that already has a verdict.
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT="compat-thick-run3"
DEVICE="d861a4fa"
BATCH=12
ROUNDS=14
LOG=/tmp/compat-thick-run3.log

mcp_alive() {
  timeout 45 python3 tools/tpmcp.py call runtime_query '{"action":"status"}' >/dev/null 2>&1
}

# The MCP listener dies when the controller stops, and the phone's LAN address moves between
# networks; either way the connection file goes stale. Re-enable the entry, export a fresh
# config, and pull the newest one back.
repair_connection() {
  echo "--- repair_connection $(date -Iseconds)" >>"$LOG"
  adb -s "$DEVICE" shell input keyevent 224 >/dev/null 2>&1
  adb -s "$DEVICE" shell monkey -p dev.tripoder.engine -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr.xml >/dev/null 2>&1
  adb -s "$DEVICE" shell cat /sdcard/_rr.xml 2>/dev/null | python3 -c '
import re,sys
xml=sys.stdin.read()
def centre(label):
    m=re.search(r"text=\""+re.escape(label)+r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",xml)
    return None if not m else ((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2)
for label in ("重新连接",):
    p=centre(label)
    if p: print("TAP %d %d" % p)
p=centre("设置")
if p: print("TAB %d %d" % p)
' > /tmp/_rr_plan.txt 2>/dev/null
  while read -r kind x y; do
    [ "${kind:-}" = "TAP" ] && adb -s "$DEVICE" shell input tap "$x" "$y" >/dev/null 2>&1 && sleep 4
    [ "${kind:-}" = "TAB" ] && adb -s "$DEVICE" shell input tap "$x" "$y" >/dev/null 2>&1 && sleep 3
  done < /tmp/_rr_plan.txt

  adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr2.xml >/dev/null 2>&1
  POS=$(adb -s "$DEVICE" shell cat /sdcard/_rr2.xml 2>/dev/null | python3 -c '
import re,sys
xml=sys.stdin.read()
for label in ("开启网络 MCP","导出连接配置"):
    m=re.search(r"text=\""+re.escape(label)+r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",xml)
    if m: print(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2))
')
  echo "$POS" | while read -r x y; do
    [ -n "${x:-}" ] && adb -s "$DEVICE" shell input tap "$x" "$y" >/dev/null 2>&1 && sleep 4
  done
  adb -s "$DEVICE" shell uiautomator dump /sdcard/_rr3.xml >/dev/null 2>&1
  SAVE=$(adb -s "$DEVICE" shell cat /sdcard/_rr3.xml 2>/dev/null | python3 -c '
import re,sys
xml=sys.stdin.read()
m=re.search(r"text=\"保存\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",xml)
print(((int(m.group(1))+int(m.group(3)))//2,(int(m.group(2))+int(m.group(4)))//2) if m else "")
')
  [ -n "$SAVE" ] && adb -s "$DEVICE" shell input tap $SAVE >/dev/null 2>&1 && sleep 4
  NEWEST=$(adb -s "$DEVICE" shell 'ls -t /storage/emulated/0/tripoder/ 2>/dev/null | head -1' | tr -d '\r')
  if [ -n "$NEWEST" ]; then
    adb -s "$DEVICE" pull "/storage/emulated/0/tripoder/$NEWEST" "$HOME/.tripoder/tripoder-connection.json" >/dev/null 2>&1
    chmod 600 "$HOME/.tripoder/tripoder-connection.json" 2>/dev/null
    echo "--- pulled $NEWEST" >>"$LOG"
  fi
}

judged() {
  python3 - <<'PY'
import json, os
path = "artifacts/compat-thick-run3/journal.jsonl"
if not os.path.exists(path):
    print(0); raise SystemExit
final = {"PASS", "START_REJECTED", "CRASH_ON_START", "PROCESS_GONE", "NO_FOREGROUND_SCREEN",
         "IMPORT_FAILED", "UNSUPPORTED", "SCOPE_FENCED"}
seen = set()
for line in open(path, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    row = json.loads(line)
    verdict = row.get("verdict") or (row.get("state") if row.get("phase") == "import" else None)
    if row.get("package") and verdict in final:
        seen.add(row["package"])
print(len(seen))
PY
}

echo "=== run3 start $(date -Iseconds) ===" >>"$LOG"
for round in $(seq 1 "$ROUNDS"); do
  have=$(judged)
  echo "[round $round] judged=$have" >>"$LOG"
  if [ "$have" -ge 123 ]; then break; fi
  if ! mcp_alive; then
    echo "[round $round] MCP down, repairing" >>"$LOG"
    repair_connection
    if ! mcp_alive; then echo "[round $round] still down, sleeping" >>"$LOG"; sleep 60; continue; fi
  fi
  before=$have
  timeout 2400 python3 scripts/qa/device_campaign.py \
      --out "$OUT" --phase all --mode thick --recycle --retry-failed \
      --wait-ms 15000 --settle-ms 1200 --limit "$BATCH" >>"$LOG" 2>&1
  echo "<<< round $round rc=$? $(date -Iseconds) judged=$(judged) (was $before)" >>"$LOG"
done
echo "=== run3 end $(date -Iseconds) judged=$(judged) ===" >>"$LOG"
