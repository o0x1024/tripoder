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

# force-stop 之后引擎常停在 SUPERVISOR_INTERRUPTED：屏幕上是「运行引擎没有连接 / 重新连接」，
# 应用列表根本没渲染，所以「添加应用」格子并不存在。盲目点会一路 NOT_FOUND，最后被误读成
# 「这个包不在选择列表里」（本轮 com.baidu.BaiduMap 三次未进添加页就是这么来的）。
# 判据用列表页独有的「N 个应用 · 轻点运行，长按管理」，它只在数据真的加载完时出现。
list_ready() { $ui dump 2>/dev/null | grep -q "个应用 · 轻点运行"; }
wait_list() {
  for _ in 1 2 3 4 5 6 7 8; do
    list_ready && return 0
    $ui tap "重新连接" >/dev/null 2>&1
    sleep 4
  done
  return 1
}

before=$(imports)
echo "installs before: ${before:-unknown}"

for kw in "$@"; do
  $D shell am force-stop dev.tripoder.q6 >/dev/null 2>&1
  $D shell am force-stop dev.tripoder.q7 >/dev/null 2>&1
  $D shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity >/dev/null 2>&1
  sleep 4
  if ! wait_list; then
    echo "$kw: ENGINE_DOWN (列表没加载出来，重新连接 8 次仍失败)"
    continue
  fi
  $ui tap "添加应用" --scroll >/dev/null 2>&1
  sleep 3
  # 前台归属断言：批量跑时「添加应用」常常没真的跳页（列表会被惯性滚回顶部，仍停在
  # WorkbenchActivity），之后「点搜索框 / 点全选」全落空，结果恒为「已选 0 个」并被误读成
  # 「这个包不在选择列表里」。所以判据必须是 topResumedActivity 落在 **WorkbenchAddActivity**，
  # 只判 dev.tripoder.q6/ 不够——列表页也满足它。
  on_add=0
  for _ in 1 2 3; do
    if $D shell "dumpsys activity activities | grep -m1 topResumedActivity" | grep -q "WorkbenchAddActivity"; then
      on_add=1; break
    fi
    echo "  $kw: (未进入添加页，重试一次)"
    wait_list || break
    $ui tap "添加应用" --scroll >/dev/null 2>&1
    sleep 4
  done
  if [ "$on_add" != "1" ]; then
    echo "$kw: ADD_SCREEN_NOT_FOUND (添加应用页没有打开)"
    continue
  fi
  # 选择状态会跨屏留存：实测进页面时「已选 4 个」一直挂着（之前几轮选过但没提交的残留），
  # 此时「全选」已经变成「取消全选」，再按文案点「全选」是空操作，而「添加所选 4 个应用」又一直存在
  # ⇒ 一旦点提交，进去的是那 4 个残留应用，不是本次搜索的包。所以进页先「清空」，并断言已选=0。
  $ui tap "清空" >/dev/null 2>&1
  sleep 2
  reselect=$($ui dump 2>&1 | grep -o "已选 [0-9]* 个" | head -1)
  echo "  $kw: 清空后 $reselect"
  # 搜索框会保留上次查询：直接 input text 会把它拼成「com.tencent.news com.larus.nova」，
  # 搜索必然无结果。settext 会先 MOVE_END + 按字符数 DEL 清空，再分段写入。
  $ui settext "$kw" >/dev/null 2>&1
  sleep 3
  $D shell input keyevent 111 >/dev/null 2>&1
  sleep 3
  # 已导入的包在结果行里标注「已在此空间」，此时全选选不中它、提交文案也不会出现。
  # 以前把这种情况报成 NO_SELECTION（「列表里没有这个包」）是错的判据：番茄小说就是先被导入、
  # 再被报成「没有这个包」。先认这个标记，命中就当作幂等成功。
  in_space=$($ui dump 2>&1 | grep -c "已在此空间")
  if [ "${in_space:-0}" != "0" ]; then
    echo "$kw: ALREADY_IN_SPACE (已在空间里，跳过提交) -> installs $(imports)"
    continue
  fi
  # 搜索结果行是懒渲染的：打完包名后列表可能仍是「选择要添加的应用」（空），此时点「全选」等于选了 0 个，
  # 提交按钮文案就永远不出现（表现为 NO_SELECTION）。所以要「点全选 → 读『已选 N 个』→ N=0 就等一会再点」，
  # 直到真的选中为止；这比按固定秒数等待稳，也比按应用名/包名匹配结果行更省事（结果行文案是中文名）。
  # 注意：不要每轮都点「全选」——它是 toggle，选中后按钮会变成「取消全选」，再点就取消。所以：
  #   已选>0 但还没看到提交文案 → 只等不点；已选=0 → 说明结果行还没渲染，等一下再点一次。
  label=""
  for _ in 1 2 3 4 5 6; do
    state=$($ui dump 2>&1 | grep -o "已选 [0-9]* 个" | head -1)
    picked=$(echo "$state" | grep -o "[0-9]*")
    echo "  $kw: probe $state"
    if [ -z "${picked:-}" ]; then
      sleep 3; continue
    fi
    if [ "$picked" = "0" ]; then
      $ui tap "全选" >/dev/null 2>&1
      sleep 3; continue
    fi
    label=$($ui dump 2>&1 | grep -o "添加所选 [0-9]* 个应用" | head -1)
    [ -n "$label" ] && break
    sleep 3
  done
  if [ -z "$label" ]; then
    echo "$kw: NO_SELECTION (搜索无结果行，列表里没有这个包)"
    continue
  fi
  # 兜底：只允许提交恰好选 1 个（本次搜索的目标）。选了多个说明又有残留，宁可跳过也不要导错包。
  if [ "$label" != "添加所选 1 个应用" ]; then
    echo "$kw: SELECTION_NOT_UNIQUE ($label，跳过以免导错包)"
    $ui tap "清空" >/dev/null 2>&1
    continue
  fi
  $ui tap "$label" >/dev/null 2>&1
  sleep 14
  echo "$kw: $label -> installs $(imports)"
done
