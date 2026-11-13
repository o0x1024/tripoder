#!/bin/bash
# Import third-party apps into the container space through the Workbench "添加应用" screen.
#
# Why this exists: the screen's commit button label is dynamic. With nothing selected it reads
# 「选择要添加的应用」; once anything is selected it becomes 「添加所选 N 个应用」. Tapping the fixed label
# after selecting silently does nothing, which looks exactly like "import worked but nothing happened"
# (the list keeps showing the previous 「添加完成 · 成功 M 个」 banner). Selecting by tapping an app tile is
# also unreliable inside the container; the 全选 button is the dependable selector.
#
# Each app is imported in a fresh Workbench process so leftover selection state cannot leak between runs.
#
# Usage:  bash android/scripts/import_apps.sh com.eg.android.AlipayGphone com.taobao.taobao ...
# Prints one line per package with the commit label it used, then the final catalog install count.
set -u
cd "$(dirname "$0")/../.." || exit 1
D="adb -s ${DEVICE:-d861a4fa}"
ui="python3 android/scripts/guest_ui.py"

imports() { $D logcat -d | grep -o "CATALOG_READY installs=[0-9]*" | tail -1 | cut -d= -f2; }

before=$(imports)
echo "installs before: ${before:-unknown}"

for kw in "$@"; do
  $D shell am force-stop dev.tripoder.q6 >/dev/null 2>&1
  $D shell am force-stop dev.tripoder.q7 >/dev/null 2>&1
  $D shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity >/dev/null 2>&1
  sleep 4
  $ui tap "重新连接" >/dev/null 2>&1
  sleep 3
  $ui tap "重新连接" >/dev/null 2>&1
  sleep 2
  $ui tap "添加应用" --scroll >/dev/null 2>&1
  sleep 3
  $ui tap "搜索应用名称或包名" >/dev/null 2>&1
  sleep 2
  $D shell input text "$kw" >/dev/null 2>&1
  sleep 3
  $D shell input keyevent 111 >/dev/null 2>&1
  sleep 2
  $ui tap "全选" >/dev/null 2>&1
  sleep 2
  label=$($ui dump 2>&1 | grep -o "添加所选 [0-9]* 个应用" | head -1)
  if [ -z "$label" ]; then
    echo "$kw: NO_SELECTION (nothing matched, or the list is already fully selected)"
    continue
  fi
  $ui tap "$label" >/dev/null 2>&1
  sleep 14
  echo "$kw: $label -> installs $(imports)"
done
