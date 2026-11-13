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
  # 前台归属断言：批量跑时 Workbench 有可能已经不在前台（实测顶到 com.miui.home/.launcher.Launcher），
  # 之后「点搜索框 / 点全选」全打在桌面上，结果恒为「已选 0 个」并被误读成「这个包不在选择列表里」。
  # 所以进选择页后必须先确认当前 Activity 还是我们的两个 Workbench 页面，否则重启一次再继续。
  for _ in 1 2; do
    if $D shell "dumpsys activity activities | grep -m1 topResumedActivity" | grep -q "dev.tripoder.q6/"; then
      break
    fi
    echo "$kw: (前台不在 Workbench，重启一次)"
    $D shell am force-stop dev.tripoder.q6 >/dev/null 2>&1
    $D shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity >/dev/null 2>&1
    sleep 4
    $ui tap "重新连接" >/dev/null 2>&1
    sleep 3
    $ui tap "重新连接" >/dev/null 2>&1
    sleep 2
    $ui tap "添加应用" --scroll >/dev/null 2>&1
    sleep 3
  done
  $ui tap "搜索应用名称或包名" >/dev/null 2>&1
  sleep 2
  $D shell input text "$kw" >/dev/null 2>&1
  sleep 3
  $D shell input keyevent 111 >/dev/null 2>&1
  sleep 2
  # 搜索结果行是懒渲染的：打完包名后列表可能仍是「选择要添加的应用」（空），此时点「全选」等于选了 0 个，
  # 提交按钮文案就永远不出现（表现为 NO_SELECTION）。所以要「点全选 → 读『已选 N 个』→ N=0 就等一会再点」，
  # 直到真的选中为止；这比按固定秒数等待稳，也比按应用名/包名匹配结果行更省事（结果行文案是中文名）。
  label=""
  for _ in 1 2 3 4 5; do
    $ui tap "全选" >/dev/null 2>&1
    sleep 2
    picked=$($ui dump 2>&1 | grep -o "已选 [0-9]* 个" | head -1 | grep -o "[0-9]*")
    if [ "${picked:-0}" != "0" ] && [ -n "${picked:-}" ]; then
      label=$($ui dump 2>&1 | grep -o "添加所选 [0-9]* 个应用" | head -1)
      [ -n "$label" ] && break
    fi
    sleep 3
  done
  if [ -z "$label" ]; then
    echo "$kw: NO_SELECTION (搜索无结果行，列表里没有这个包)"
    continue
  fi
  $ui tap "$label" >/dev/null 2>&1
  sleep 14
  echo "$kw: $label -> installs $(imports)"
done
