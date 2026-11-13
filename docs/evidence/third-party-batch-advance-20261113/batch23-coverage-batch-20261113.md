# 第 23 批：流水线修好后的批量覆盖（6 个轻量应用 + 国产主力批）

- 日期：2026-11-14
- 设备：d861a4fa（已解锁）
- 版本：build-nested-v92（未改代码；只改了 `android/scripts/import_apps.sh` 的一处等待逻辑）
- 关联：[batch22-coverage-pipeline-20261113.md](batch22-coverage-pipeline-20261113.md)（流水线设计与第一段实测）、`inventory.md`

## 1. batch22 流水线为什么全线 ENGINE_NOT_CONNECTED

batch22 的第一版脚本对每个包都报 `import=ENGINE_NOT_CONNECTED`，看起来像「引擎被批量流程打挂了」。逐个排除后根因是**脚本自己的 dump 判据写错**：

```python
if 'Bounds' in xml: return xml        # ← 错
if 'bounds=' in xml: return xml       # ← 对
```

`uiautomator dump` 产出的属性名是小写 `bounds=`。用大写当成功判据时 `dump()` 永远返回空串，
于是 `reconnect()` 的「列表已就绪」条件永远不成立，`run()` 直接落到 `ENGINE_NOT_CONNECTED` 分支——
**每个包都被判成引擎没连接，而引擎其实一直是好的**（同一时刻 `TripoderBoot: BROKER_READY ms=103 / CATALOG_READY installs=35` 正常打印）。

这条进入 gotcha：`uiautomator-bounds-is-lowercase`。

## 2. 「重新连接」必须点按钮，不能盲点坐标

`am force-stop dev.tripoder.q7` 会把引擎打进 `SUPERVISOR_INTERRUPTED`，而该状态只接受显式 retry
（`EngineRecoveryPolicy.interrupted`：只有 READY/STARTING/BACKOFF → BLOCKED）。此时 Workbench 首屏是：

```
运行引擎没有连接
SUPERVISOR_INTERRUPTED
[重新连接]  bounds=[488,834][720,978]
```

点这个按钮后引擎自愈（实测 `BROKER_READY` + `CATALOG_READY installs=35`，列表回到 30 个应用）。
旧脚本用的是硬编码坐标 `(604,906)`：在**错误屏**上它刚好落在按钮内，在**列表屏**上它落在一个应用格子里，
于是「重连」这一步会静默变成「启动某个应用」。现在按 dump 文本定位按钮，坐标只作兜底且不再用于列表屏。

⇒ 批量循环里**不要** force-stop q7（gotcha `dont-kill-q7-in-batch-loops`）；宿主 force-stop 两次就够清掉 guest 残留子进程。

## 3. 导入步骤交给 `android/scripts/import_apps.sh`，并在其中修了一个懒渲染竞态

脚本内自带的手写导入流程（搜索框 → `input text` → `keyevent 111` → 全选 → 提交）在 v92 上不稳定，
第一轮直接得到 `SEARCH_NOT_FOUND`，还留下一次「导入结果：番茄畅听 · 已添加」的脏状态。

改用仓库里已验证的 `import_apps.sh` 之后仍有 2/6 报 `NO_SELECTION`。用 `/tmp/import_debug.py`
逐步 dump 定位到根因：**搜索结果行是懒渲染的**。

```
[after_type]  texts=[..., 'org.wikipedia', '已选 0 个', '全选', '清空', '选择要添加的应用']
[after_esc]   texts=[..., 'org.wikipedia', '已选 0 个', '全选', '清空', '维基百科', '选择要添加的应用']
```

打完包名后 3 秒列表仍是空的（「选择要添加的应用」= 零结果），ESC 之后结果行才渲染出来。
原脚本在结果行渲染前就点「全选」⇒ 选了 0 个 ⇒ 提交按钮文案（`添加所选 N 个应用`）不存在 ⇒ `NO_SELECTION`。

修法（已提交到 `import_apps.sh`）：把「等固定秒数」换成**自我校验的轮询**——

```
循环最多 5 次：点「全选」→ 读「已选 N 个」→ N>0 且出现「添加所选 N 个应用」就 break；否则等 3s 重来
```

修复后 `com.deepseek.chat` 一次导入成功（installs 42→43），`org.wikipedia` 手工补提交也成功（41→42）。

## 4. 实测：6 个轻量应用全通过（v92）

| 包 | tile | top | anr | adm | fatal | absent | 判定 |
|---|---|---|---|---|---|---|---|
| `com.github.android` | GitHub | TaskP1$F1M0C2 | 0 | 0 | 0 | 0 | LAUNCHED |
| `org.telegram.messenger` | Telegram | TaskP1$F1M2C0E0 | 0 | 0 | 0 | 0 | LAUNCHED |
| `org.videolan.vlc` | VLC | TaskP1$F1M0C3 | 0 | 0 | 0 | 0 | LAUNCHED |
| `com.openai.chatgpt` | ChatGPT | TaskP1$F1M1C0 | 0 | 0 | 0 | 0 | LAUNCHED |
| `org.wikipedia` | 维基百科 | TaskP1$F1M0C2 | 0 | 0 | 0 | 0 | LAUNCHED（导入需二次提交） |
| `com.deepseek.chat` | DeepSeek | TaskP1$F1M2C2 | 0 | 0 | 0 | 0 | LAUNCHED（首击未起，二击成功） |

`installs` 37 → 43。六包全部落到客体 `TaskP*`，`fatal/admission-blocked/ABSENT` 全 0：
说明「扩大覆盖面」本身不会引入新的运行时缺陷，批次失败只来自编排（脚本）而不来自容器能力。

## 5. 两个可重跑的单点失败模式

1. **导入**：`NO_SELECTION` = 搜索无结果行（或结果行未渲染）。修好的脚本会自动重试；仍失败就单包重跑，
   不需要重跑整批（幂等键 = 包名）。
2. **启动**：`launch_tile.py` 第一次点 tile 可能落空（列表惯性滚动使坐标陈旧）。DeepSeek 首击 `NOT_TOP`、
   二击 `TaskP1$F1M2C2` 即成功。判据是 `topResumedActivity` 出现 `TaskP`，不是 Workbench 的「已完成」文案。

## 6. 国产主力批（第二批）：1 通过 / 5 导入未命中

| 包 | 导入 | tile | top | 判定 |
|---|---|---|---|---|
| `com.baidu.searchbox` 百度 | OK（installs 43→44） | 百度 | TaskP1$F1M2C1 | LAUNCHED，anr/adm/fatal/absent 全 0 |
| `cn.wps.moffice_eng` WPS | NO_SELECTION | — | — | 导入未命中（待诊断） |
| `com.tencent.news` 腾讯新闻 | NO_SELECTION | — | — | 同上 |
| `com.larus.nova` 豆包 | NO_SELECTION | — | — | 同上 |
| `com.phoenix.read` 番茄免费小说 | NO_SELECTION | — | — | 同上 |
| `com.baidu.BaiduMap` 百度地图 | 见下 | — | — | — |

同一条流水线在轻量批上 6/6，在国产批上 1/6，说明**差别不在容器能力，而在导入这一步对这批包不稳定**：
同一批内第 2 个包起连续失败，且失败时 `topResumedActivity` 会掉到 `com.miui.home/.launcher.Launcher`——
即 Workbench 已经不在前台，后续「点搜索框 / 点全选」全打在桌面上，于是永远是「已选 0 个」。

⇒ 判据修正：`NO_SELECTION` 不等于「这个包不在选择列表里」。必须先确认 `topResumedActivity` 仍是
`dev.tripoder.q6/…WorkbenchActivity` 或 `…WorkbenchAddActivity`，否则那是编排失败（页面丢了），不是应用不存在。

## 7. 第三条编排约束：tile 查找要覆盖整个列表

`launch_tile.py` 用「先滚到顶 → 再向下滚 6 屏找 tile」的方式定位。catalog 从 30 涨到 45 之后，
6 屏不足以覆盖整表，于是 `豆包` 明明已经导入（`添加应用 · 已完成`、`installs 45`），两次启动都返回 `NOT_TOP`——
tile 处于列表更下方，脚本从未滚到它。

⇒ 结论：**tile 查找的滚动次数必须随 catalog 增长**（当前取 15 次更稳妥），或者改用「添加应用」页的搜索框定位。
`NOT_TOP` 有两种含义，必须分开：①点了没反应（坐标陈旧，重跑即好）；②根本没点到（tile 不在视口内）。

## 8. 边界

- 本批只判「落点 + 无崩溃」，不等于登录/业务链路可用。深度结论仍按 BOSS直聘 的口径逐应用落在各自证据文档里。
- 覆盖面计数口径要记住：`pm list packages -3` = 122（含小米预装）；非捆绑约 81；Workbench tile 是推进入口，
  `CATALOG_READY installs=N` 是安装条目数，三者不相等（gotcha `workbench-shows-24-not-all`）。
- 剩余队列继续按 5–6 个一批推进；下一批之前先把「导入后前台归属」加进流水线断言，避免把编排失败记成应用缺失。

