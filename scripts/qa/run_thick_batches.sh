#!/bin/bash
# THICK campaign driver: the engine's MCP controller dies every ~10-15 minutes, so one
# 123-app invocation can never finish. Each batch is short enough to survive inside one
# controller lease, and the controller is repaired between batches; packages whose verdict
# describes the harness (not the guest) are retried on later passes.
#
# Progress is always readable from artifacts/$OUT/journal.jsonl; the driver itself keeps
# no state, so it can be re-run and will simply skip whatever is already judged.
set -u
cd /Users/like/code/tripoder || exit 1

OUT="${OUT:-compat-thick-run2}"
BATCH="${BATCH:-10}"
PASSES="${PASSES:-3}"
WAIT_MS="${WAIT_MS:-25000}"
LOG="/tmp/${OUT}.log"
JOURNAL="artifacts/${OUT}/journal.jsonl"

exec >> "$LOG" 2>&1
echo "=== driver start $(date -Iseconds) out=$OUT batch=$BATCH passes=$PASSES ==="

adb -s d861a4fa shell cmd appops set dev.tripoder.engine RUN_IN_BACKGROUND allow >/dev/null 2>&1
adb -s d861a4fa shell cmd appops set dev.tripoder.engine RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
adb -s d861a4fa shell dumpsys deviceidle whitelist +dev.tripoder.engine >/dev/null 2>&1
adb -s d861a4fa shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
adb -s d861a4fa shell svc power stayon true >/dev/null 2>&1

judged() {  # packages that already carry a verdict about the guest
  python3 - "$JOURNAL" <<'PY'
import json, sys
final = set()
try:
    rows = [json.loads(line) for line in open(sys.argv[1], encoding='utf-8') if line.strip()]
except FileNotFoundError:
    rows = []
for row in rows:
    if row.get('phase') == 'launch' and row.get('verdict'):
        verdict = row['verdict']
        error = row.get('error') or ''
        if verdict in ('TRANSPORT_ERROR', 'MODE_SET_FAILED'):
            continue
        if any(m in error for m in ('ENGINE_NOT_CONNECTED', 'ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED', 'WORKER_DIED')):
            continue
        final.add(row['package'])
print('\n'.join(sorted(final)))
PY
}

todo() {  # every device package that is not FINAL yet, priority order preserved
  python3 - "$JOURNAL" <<'PY'
import json, sys
sys.path.insert(0, 'scripts/qa')
import device_campaign as campaign
final = set()
try:
    rows = [json.loads(line) for line in open(sys.argv[1], encoding='utf-8') if line.strip()]
except FileNotFoundError:
    rows = []
for row in rows:
    if row.get('phase') == 'launch' and row.get('verdict'):
        verdict, error = row['verdict'], row.get('error') or ''
        if verdict in ('TRANSPORT_ERROR', 'MODE_SET_FAILED'):
            continue
        if any(m in error for m in ('ENGINE_NOT_CONNECTED', 'ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED', 'WORKER_DIED')):
            continue
        final.add(row['package'])
known = [json.loads(line)['package'] for line in open('artifacts/compat-run1/journal.jsonl', encoding='utf-8')
         if line.strip() and json.loads(line).get('phase') == 'inventory' and json.loads(line).get('package')]
order = {package: index for index, package in enumerate(known)}
pending = [package for package in known if package not in final]
pending.sort(key=lambda package: order.get(package, 9999))
print('\n'.join(pending))
PY
}

for pass_index in $(seq 1 "$PASSES"); do
  PENDING=()
  while IFS= read -r line; do [ -n "$line" ] && PENDING+=("$line"); done < <(todo)
  echo "--- pass $pass_index: ${#PENDING[@]} packages left $(date -Iseconds)"
  [ "${#PENDING[@]}" -eq 0 ] && break
  for ((start = 0; start < ${#PENDING[@]}; start += BATCH)); do
    CHUNK=$(IFS=,; echo "${PENDING[*]:start:BATCH}")
    echo ">>> batch $((start / BATCH + 1)) [${CHUNK//,/ }] $(date -Iseconds)"
    python3 scripts/qa/engine_repair.py || python3 scripts/qa/engine_repair.py --restart
    python3 scripts/qa/device_campaign.py --phase all --mode thick --recycle \
      --only "$CHUNK" --out "$OUT" --wait-ms "$WAIT_MS" --settle-ms 1500
    echo "<<< batch done rc=$? $(date -Iseconds)"
  done
done

echo "=== driver end $(date -Iseconds) judged=$(judged | wc -l | tr -d ' ') ==="
