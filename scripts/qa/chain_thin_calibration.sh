#!/bin/bash
# Wait for the THICK campaign to finish, then re-run the packages whose verdict changed
# under THIN on the same fresh engine. That separates "THICK behaves differently" from
# "the THIN run was measured on an exhausted affinity pool".
set -u
cd /Users/like/code/tripoder || exit 1

while pgrep -f "device_campaign.py.*compat-thick-run1" >/dev/null 2>&1; do sleep 30; done
echo "[chain] thick run finished at $(date -Iseconds)" >> /tmp/thin-calibration.log

CHANGED=$(python3 - <<'PY'
import json
def load(path, phase):
    latest = {}
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        if row.get('phase') != phase or not row.get('package'):
            continue
        if row.get('verdict') or row.get('state'):
            latest[row['package']] = row
    return latest
thin, thin_import = load('artifacts/compat-run1/journal.jsonl', 'launch'), load('artifacts/compat-run1/journal.jsonl', 'import')
thick, thick_import = load('artifacts/compat-thick-run1/journal.jsonl', 'launch'), load('artifacts/compat-thick-run1/journal.jsonl', 'import')
def label(row):
    return (row or {}).get('verdict') or (row or {}).get('state') or 'NOT_RUN'
changed = []
for package in sorted(set(thin) | set(thin_import)):
    before = label(thin.get(package) or thin_import.get(package))
    after = label(thick.get(package) or thick_import.get(package))
    if before != after:
        changed.append(package)
print(','.join(changed))
PY
)
echo "[chain] changed packages: ${#CHANGED} chars" >> /tmp/thin-calibration.log
if [ -z "$CHANGED" ]; then
  echo "[chain] nothing to calibrate" >> /tmp/thin-calibration.log
  exit 0
fi

python3 scripts/qa/device_campaign.py --phase all --mode thin --recycle \
  --only "$CHANGED" --out compat-thin-calibration --wait-ms 25000 >> /tmp/thin-calibration.log 2>&1
echo "[chain] calibration finished at $(date -Iseconds)" >> /tmp/thin-calibration.log
