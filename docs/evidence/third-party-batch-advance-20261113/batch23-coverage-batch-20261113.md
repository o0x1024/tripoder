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

## 7. 第四条：豆包的启动被 **provider authority 归属** 挡住（类级缺陷，不是应用问题）

豆包 `com.larus.nova` 导入成功（installs 45）、tile 可定位可点击，但启动失败，Workbench 弹：

```
应用没有启动
java.util.concurrent.ExecutionException:androidx.startup.StartupException:
java.lang.SecurityException: Permission Denial: opening provider
com.ss.android.lark.appcontext.applifecycle.AppLifecycle$LifecycleContentProvider
from ProcessRecord{3534f82 7091:dev.tripoder.q6:slot1/u0a542} (pid=7091, uid=10542)
that is not exported from UID 10320
```

三个数字把这件事说清楚了：

| 数字 | 含义 |
|---|---|
| `uid=10542` | 发起方 = 容器宿主 uid（客体就这个 uid 跑） |
| `UID 10320` | 该 authority 在**外层 PMS** 的属主 = 本机真实安装的 `com.larus.nova`（`cmd package list packages -U` 实测 `com.larus.nova uid:10320`） |
| `not exported` | 该 provider 在豆包 manifest 里 `android:exported="false"` |

于是语义变成：**豆包想打开"自己的"非导出 provider，系统认为是一个 uid 10542 的陌生进程来访问 uid 10320 的私有 provider，合法地拒绝。**
容器宿主 manifest 里 provider 数为 0（gotcha `host-has-no-provider`），所以没有任何东西能在宿主的 uid 下代理这个 authority。

⇒ 这是**类级**缺陷：凡是启动路径上触碰自身非导出 provider 的应用都会这样死（`androidx.startup`、`FirebaseInitProvider`、
`WorkManager`、`FileProvider` 都是这一族），失败形态固定为「应用没有启动 + SecurityException/provider」。
与本轮前面的 `NO_SELECTION` / `NOT_TOP` 不同，这一条**不是编排问题**，是运行时缺能力（provider 投影）。

修法方向（架构级，尚未实施）：

1. **authority 重写 + 宿主代理 provider**：导入时为每个客体的每个 provider 生成宿主侧代理条目
   `tripoder.proxy.<hash>.<原authority>`，客体侧把 `ContentResolver` 的 authority 参数重写成代理名；
   宿主侧 proxy 用 `android:exported="true"` + `grantUriPermissions`，转发给客体自己实现。
   代价：要在宿主 manifest 静态声明（条目数随应用增长），且 provider 是 Binder 契约，跨进程转发要处理 Cursor 跨进程窗口。
2. **PMS 面投影**（轻量但只治一半）：让客体看到的 `ProviderInfo` 把 `exported` 报成 true 并让 authority 解析到宿主；
   但真正的拒绝发生在 system_server 的 `AppOpsManager`/`ActivityManagerService` 侧（本次栈在 `androidx.startup` 里、
   抛出点是 system_server 的 `ContentProviderHelper`），本地投影改不到，所以**单靠 PM 投影不能解决**。
3. 短期绕过：对这类应用先判「manifest 里有非导出 provider 且启动期会 open」→ 直接标记 `PROVIDER_UNSUPPORTED`，
   不再花时间逐个试，等 provider 投影能力落地。

## 8. 第三条编排约束：tile 查找要覆盖整个列表

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


## 9. 导入侧的两个真断点（已定位并修好）——跨越三轮的 `NO_SELECTION` 假阳

前面 §6 把国产批的 1/6 归因为「导入不稳定」。继续往下挖，`NO_SELECTION` 其实是**两个不同的真断点**各自伪装成同一句话：

### 9.1 断点一：`已在此空间` —— 包早就在里面了

「添加应用」页对**已导入**的应用行会标注 `已在此空间`。这类行不被「全选」选中，所以提交文案`添加所选 N 个应用`
永远不会出现，旧判据就报 `NO_SELECTION (列表里没有这个包)`。

实测反证（`/tmp/probe_inspace.py`）：`com.phoenix.read` 番茄免费小说搜索结果行的文案是 `已在此空间`，
而**同一时刻** Workbench 列表里已经有它、`installs` 也已经包含它。也就是说：

> `NO_SELECTION` 至少有两种含义：①搜索真的没结果；②**这个包已经在空间里**（幂等成功）。
> 旧口径把 ② 记成 ①，于是「已推进」被当成「推进失败」。

### 9.2 断点二：选择状态跨屏留存 —— 不清空会导错包

同一轮里四个包**都**读到 `已选 4 个` 与 `添加所选 4 个应用`，即使刚进页面、搜索框是空的。
这是前面几轮「选了但没提交」的残留：选择集合是页面级状态，不随搜索/返回清掉。

后果比 ① 更严重：`全选` 是 toggle，一旦已有选中它就变成 `取消全选`，此时按文案点 `全选` 是**空操作**；
而 `添加所选 4 个应用` 一直存在 ⇒ 只要按这个按钮点下去，**进去的是那 4 个残留应用，不是本次搜索的包**。

`清空`（[1002,1043][1160,1187]）可以把它归零：实测 `已选 4 个 → 已选 0 个`，按钮文案回到 `全选` / `选择要添加的应用`。

### 9.3 修法（`android/scripts/import_apps.sh`）

1. 进添加页后先点 `清空`，并打印「清空后 已选 N 个」——保证后续判据在一个干净状态上。
2. 搜索 + ESC 之后先看结果行有没有 `已在此空间`：有就输出 `ALREADY_IN_SPACE`（幂等成功，不提交）。
3. 轮询 `已选 N 个`：`N=0` 才点 `全选`，`N>0` 只等提交文案（因为 toggle 语义）。
4. 兜底：**只允许提交 `添加所选 1 个应用`**；文案是 `添加所选 N 个应用`（N>1）时输出
   `SELECTION_NOT_UNIQUE` 并清空跳过——宁可跳过也不能导错包。
5. 前置：`wait_list()` 以列表页独有的 `N 个应用 · 轻点运行，长按管理` 为「引擎+数据就绪」判据，
   再点 `重新连接` 重试至 8 次；`添加应用` 点击后的归属判据改判 **`WorkbenchAddActivity`**
   （只判 `dev.tripoder.q6/` 会误把列表页当成功）。

### 9.4 效果（实测）

| 包 | 修前 | 修后 | installs |
|---|---|---|---|
| `cn.wps.moffice_eng` WPS Office | `NO_SELECTION` | `清空后已选 0 → 已选 1 → 添加所选 1 个应用` | 47 → **48** |
| `com.baidu.BaiduMap` 百度地图 | `ADD_SCREEN_NOT_FOUND` / `NO_SELECTION` | 同上 | 48 → **49** |
| `com.tencent.news` 腾讯新闻 | `NO_SELECTION`（三次） | 同上 | 49 → **50** |
| `com.phoenix.read` 番茄免费小说 | `NO_SELECTION`（假阳） | `ALREADY_IN_SPACE` | 不变（早已在其中） |

随后按同一配方做启动实测（`launch_tile.py`，40s 轮询）：

| tile | top | anr | adm | fatal | absent |
|---|---|---|---|---|---|
| WPS Office | `TaskP1$F1M0C0E0` | 0 | 0 | 0 | 0 |
| 腾讯新闻 | `TaskP1$F1M1C4` | 0 | 0 | 0 | 0 |
| 百度地图 | `TaskP1$F1M2C2` | 0 | 0 | 0 | 0 |

### 9.5 §8 的补充：回顶也要随列表增长

`launch_tile.py` 原来「先 4 次上滑回顶」在 44 个 tile（约 11 屏）时已经回不到顶，
于是**位于列表上方**的 `腾讯新闻` 被报成 `TILE_NOT_FOUND`（它的 tile 明明在）。
改成 12 次上滑回顶 + 找不到时从底再扫一遍（共两遍），同一条命令重跑即 `LAUNCHED`。

⇒ 规模效应的完整表述：**上下两个方向的滚动预算都必须随 catalog 增长**，
且 `TILE_NOT_FOUND` 在长列表上默认先怀疑编排，不要记成「这个应用不在空间里」。


## 10. batch24：四个新包（钉钉 / 千问 / 安兔兔评测 / Booking）— 导入 4/4、启动 4/4

承接 §9 的幂等流水线，batch24 是第一次**完全靠修好的脚本**跑完整批（没有任何手工补点）。

### 10.1 导入（`android/scripts/import_apps.sh`）

| 包 | 打印序列 | installs |
|---|---|---|
| `com.alibaba.android.rimet` 钉钉 | 清空后已选 0 → 已选 1 → 添加所选 1 个应用 | 50 → **51** |
| `com.aliyun.tongyi` 千问 | 同上 | 51 → **52** |
| `com.antutu.ABenchMark` 安兔兔评测 | 同上 | 52 → **53** |
| `com.booking` Booking.com缤客 | 同上 | 53 → **54** |

四包连续跑，`清空 → 搜索 → ESC → 已选 1 → 提交` 每一步都一次成功，无 `NO_SELECTION` / `ADD_SCREEN_NOT_FOUND`。
⇒ §9 的两处修复（进页先清空、`已在此空间` 幂等）在连续批上稳定，导入侧不再是瓶颈。

### 10.2 启动实测（`launch_tile.py`，40s 轮询）

| tile | top | anr | adm | fatal | absent | 判定 |
|---|---|---|---|---|---|---|
| 钉钉 | `TaskP2$F1M1C2` | 0 | 0 | 0 | 0 | LAUNCHED |
| 千问 | `TaskP1$F1M1C0E0` | 0 | 0 | 0 | 0 | LAUNCHED |
| 安兔兔评测 | `TaskP1$F1M0C2` | 0 | 0 | 0 | 0 | LAUNCHED |
| Booking.com缤客 | `TaskP1$F1M0C3` | 0 | 0 | 0 | 0 | LAUNCHED |

### 10.3 `TILE_NOT_FOUND` 的第三种含义：tile 文案 ≠ 应用名

第一次跑本批时 `通义` 与 `安兔兔` 都报 `TILE_NOT_FOUND`，但它们的包**明明刚导入成功**（installs 52/53）。
把 Workbench 列表全量 dump 出来（`/tmp/inv2.py`，输出 `/tmp/tiles24.json`，共 50 条文案）后原因很清楚：

- `com.aliyun.tongyi` 的 tile 文案是 **`千问`**，不含「通义」两字；
- `com.antutu.ABenchMark` 的 tile 文案是 **`安兔兔评测`**，不是「安兔兔」。

按正确文案重跑，两条立刻 `LAUNCHED`。所以 `TILE_NOT_FOUND` 现在有三种互不相同的含义：

1. 编排问题（滚动预算不够 / 点空一次）——§9.5；
2. 应用真的不在空间里（未导入）；
3. **名称口径错**：tile 显示的是应用的市场名，不是包名或常识简称。

⇒ 批量启动前先跑一次全量 tile 盘点建映射（gotcha `tile-label-not-app-name`），不要用「包名后半段」猜 tile 文案。

### 10.4 落点页与同意层（batch24-②）

四个包**全部**在首屏给出原生同意层（dump 可读，`EditText` 计数 0，无需截图判页面）：

| tile | 落点文案 | 同意入口 | 拒绝入口 |
|---|---|---|---|
| 钉钉 | 温馨提示 | `同意` | `不同意` |
| 千问 | 欢迎使用 千问 | `同意` | `不同意` |
| 安兔兔评测 | 欢迎您使用 安兔兔评测! | `同意` | `不同意` |
| Booking.com缤客 | 需您同意（含 `全选`） | `同意并继续` | `不同意并关闭` |

### 10.5 同意层之后的落点（batch24-② 实测）

同意层不是终点：四个应用点完同意后的页面形状差异很大，必须逐个 dump 判定（`/tmp/b24c.py`，每应用冷启 → 连点同意至消失 → dump + 截图）。

| tile | 落点 Activity | 节点数 | EditText | 登录入口 | 判定 |
|---|---|---|---|---|---|
| 钉钉 | `TaskP1$F1M1C2` | 20 | 1 | 「请输入号码注册或登录」`[297,796][1136,1000]` + 「下一步」；备选「邮箱登录」`[652,2308][808,2432]` | 手机号登录页可达 |
| 千问 | `TaskP1$F1M1C0E0` | 39 | 0 | 「立即登录，解锁更多功能」`[376,345][783,396]` | 已过同意层进首页，登录是**可选**项（聊天可用） |
| 安兔兔评测 | `TaskP1$F1M0C3` | 70 | 0 | 无 | 已进「设备信息」页，无账号体系，无需登录 |
| Booking.com缤客 | `TaskP1$F1M0C3` → 退回 Workbench | 28 | 0 | 被 5 个 CheckBox 挡住 | 未勾选即点「同意并继续」＝静默退出 |

三点可直接复用的结论：

1. **同意层可能要连点两次**：钉钉、安兔兔点一次「同意」后仍会再弹一层（`consent_taps` 分别为 2/2），千问只需 1 次。
   批量脚本必须写成「循环点到文案消失为止」，不能点一次就 dump。
2. **同意层之后的页面不一定有登录**：安兔兔评测根本没有账号体系（70 个节点全是设备/CPU/屏幕参数），
   「登录入口」这一列对它是空集，不是「没找到」。⇒ 覆盖矩阵的口径要允许 `NO_ACCOUNT_SYSTEM`。
3. **CheckBox 型同意墙不能用「文本点」通过**：Booking 的 5 个 `android.widget.CheckBox` 的 bounds 是整行宽
   （如 `[96,1227][1112,1359]`），点行中心不会勾上，点「同意并继续」则直接结束 Activity 回到 Workbench
   （没有崩溃、没有提示）。这一族（`全选` + N 条逐项同意）必须先勾选再提交，且勾选热区在行右端。

## 11. Booking 为什么「点完同意就退出」：两次容器侧拒绝（batch24-③）

§10.5 记录的 Booking 静默退出，实际是**两个不同的容器缺陷**先后发生。两者都属于既有判据族
「平台在这个调用点不会失败，运行时就不能抛」。

### 11.1 第一次：JobScheduler 组件池耗尽（v92→v93）

现场（`logcat -b events`，进程 `dev.tripoder.q6:slot1`）：

```
02:31:30  wm_on_create_called [com.booking.startup.HomeActivity, performCreate, 9]
02:31:30  wm_on_resume_called [com.booking.startup.HomeActivity, RESUME_ACTIVITY, 4]
02:31:35  am_crash [17904,0,dev.tripoder.q6:slot1,949501508,
                    java.lang.IllegalStateException, JOB_COMPONENT_CAPACITY_EXHAUSTED, Parcel.java,3422,0]
02:31:35  am_proc_died [0,17904,dev.tripoder.q6:slot1,0,2]
```

HomeActivity 已经正常 resume 并拿到焦点，5 秒后死于我方异常。链路：

- `JobComponentLedger.reserve()` 在每个进程内只找 `RuntimeJobSlots.PER_PROCESS` 条 lane，满则
  `throw new IllegalStateException("JOB_COMPONENT_CAPACITY_EXHAUSTED")`；
- 该异常经 `RuntimeJobBroker.submit` 的 `catch(Exception){unknown(); throw error;}` 原样返回给客体，
  在客体线程上由 `Parcel.readException` 重抛（`Parcel.java:3422`）⇒ FATAL。

平台的 `JobScheduler.schedule()` 在无法受理时只返回 `RESULT_FAILURE`，从不抛异常。
Booking 的 `UpdateAppService` 在自己的线程池里 `safeSchedule`，没有 catch ⇒ 直接杀进程。

修法（v93）：

1. `RuntimeJobSlots.PER_PROCESS` 4 → 8（`android/scripts/runtime_job_slots.py` 同步），
   manifest 生成出 `JobP{n}$J0..J7`（实测 v93 装机后 8 条 lane 全在）；
2. `RuntimeJobBroker.submit` 在选资源**之前**先判容量，满则
   `RuntimeFacadeContract.drop("JOB_COMPONENT_CAPACITY_DEGRADED")` + `return JobScheduler.RESULT_FAILURE`
   —— 既不开事务、也不抛。

### 11.2 第二次：遗留 `job_mutations` 行永久拒绝该应用的所有 schedule（v93→v94）

v93 复测：同意层的 5 个 CheckBox 全部勾上、Activity 未被打死，「同意并继续」之后进程**仍然**被杀。
这次栈换成了客体自己的业务代码：

```
java.lang.IllegalStateException: JOB_MUTATION_IN_PROGRESS:bb8a8b34-…
  at Parcel.createExceptionOrNull(Parcel.java:3422)
  at RuntimeWire.call(RuntimeWire.java:75)
  at RuntimeEngine.brokerCall(RuntimeEngine.java:464)
  at RuntimeJobScheduler.dispatch(RuntimeJobScheduler.java:42)
  at RuntimeJobScheduler$Facade.schedule(RuntimeJobScheduler.java:18)
  at com.booking.squeaks.SchedulingExtensionsKt.safeSchedule(SchedulingExtensions.kt:44)
  at com.booking.service.UpdateAppService$Companion$$ExternalSyntheticLambda0.run
```

`TripoderExitDossier: bucket=ENGINE_DEFECT class=ENGINE_REJECTION signature=961a9ffaca99` —— 容器自认这是引擎缺陷。

机制：`job_mutations` 有唯一索引 `job_mutation_owner(installGeneration) WHERE closed=0`，
`JobLedger.requireIdle()` 见任何未关闭行就直接抛。上一轮崩溃的进程把行留在 open 状态，
而它**永远不可能再被观测**（进程已死、`operation` 也不会有人来 close）⇒ 该安装此后**每一次**
`schedule()` 都被同一个异常打死。这与 §12 里广播账本「UNKNOWN 行只增不减」是同一形状的问题：
**把『我们看不清的结果』当成『客户端的过错』**。

修法（v94）：

1. `JobLedger.openMutations(install)` + `JobLedger.abandon(operation)`：把该 operation 的
   `PREPARED` 版本转 `UNKNOWN`、mutation 行 `closed=1`；
2. `RuntimeJobBroker.submit` 在 `requireIdle` 之前调 `abandonUnobservable(generation)`：
   只放弃「不在 inFlightMutations 且 contract 状态不是 RUNNING」的行 —— 真在飞的调用不受影响；
3. 仍然忙（有真在飞的行）时返回 `RESULT_FAILURE` 而不是抛（`JOB_MUTATION_IN_PROGRESS_DEGRADED`）。

两条修复共同的口径：**`JobScheduler` 这一族门面只有「受理 / 不受理」两种答复，没有异常出口。**

### 11.3 v94 实测：两道同意都过了，Booking 自己重启进程

v94 复测（冷启 → 单点「全选」行右端 → 点「同意并继续」）：

- 5 个 CheckBox 全部变为勾选（截图确认），点击后 **进程没有再崩**：`job_errors=[]`、`ENGINE_DEFECT=0`、`FATAL EXCEPTION=0`；
- 104 秒后进程优雅退出，退出栈是 Booking 自己的代码：

```
TripoderStarts: #3 CALL target=com.booking/.privacy.china.ChinaConsentWallRestartActivity
                caller=com.booking.privacy.china.ChinaConsentWallActivity flags=0x10000000
TripoderStarts: #4 ROUTED target=…ChinaConsentWallRestartActivity
Process : at android.os.Process.killProcess(Process.java:1635)
Process : at com.booking.privacy.china.ChinaConsentWallRestartActivity$Companion.restartApplication(…:53)
ActivityTaskManager: Process dev.tripoder.q6:slot1 restarted
TripoderExitDossier: bucket=APP_REFUSAL class=SELF_KILL
```

⇒ **「点同意后应用自己消失」是 Booking 的设计**：同意落盘后它主动 `killProcess` 重启自己（Phoenix 式）。
容器这边把重启 Activity 正常路由（`#4 ROUTED`）了，只是新进程没有把用户带回前台，于是看起来像「退出」。
这一条不是缺陷，记入判据：`bucket=APP_REFUSAL class=SELF_KILL` 且栈里有 `restartApplication/killProcess` 时，
不要记成崩溃。

再次从 Workbench 启动（v94，`/tmp/b95.py`）证明同意已持久化：

```
t0..t13 = TaskP1$F1M0C3（Cookie 设置页，200s 内稳定）
starts  = CALL com.booking/.identity.privacy.ui.PrivacyAppActivity → ROUTED
texts   = 接受 / 管理设置
```

点「接受」后（`/tmp/b96.py`）落 `TaskP1$F1M0C2` = `com.booking.startup.HomeActivity`
（`wm_on_create_called …HomeActivity → wm_on_resume_called …HomeActivity`），进程健在、无异常。

**剩下的是内容层**：HomeActivity 在顶层但截图全黑（`/tmp/b96.png`，只有状态栏与底部手势条），
`uiautomator dump` 返回空树。与闲鱼 U4 黑屏同族（窗口有、内容层不出帧），是下一个要单独攻的点，
不是「启动失败」。本批的口径到此为：Booking **两道同意 + 隐私页全部可通过**，首页黑屏另记。
