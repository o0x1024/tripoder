# 第二轮真机兼容性普查与架构修复（2026-10-18 晚，设备 d861a4fa，20 个宿主应用）

> 本文独立记录本轮普查。写作时另一会话正把 `docs/guest-compatibility-inventory-2026-10-18.md`
> 合并为 `docs/guest-compatibility.md`，为避免并发冲突，本轮内容单独放在证据目录。
> 证据数据见同目录 `round1-summary.json`（修复前 20 个）与 `round2-retest-after-fixes.json`（修复后重测）。

## 方法
`TargetLaunchIntegration` 的 `customPackage` 入口：逐个“安装宿主 base.apk 为客体 → 建项目 →
prepareLaunch → startActivity → 8s 稳定性 → 截图”，无论成败都记录首个失败与 `--uid=10400` 崩溃日志。
`/data/app/<pkg>/base.apk` 为 0644 世界可读，`customSource` 直指该路径，免 pull/push。
`ControlTestBase.onStart` 失败也写 `result-<run>.json`，故直接 `am instrument` 读该文件，不走会在首个失败即中断整批的 `validate-workbench-control.py`。未登录、只到首屏。排除微信。
驱动在会话 scratchpad（`compat_census.py`），非仓库。

## 结果矩阵（修复前）与架构根因

| 应用 | 首个失败 | 根因 | 处置 |
|---|---|---|---|
| 百度地图、滴滴 | —（到达界面） | — | 基线通过 |
| 高德 | `ACTIVITY_TASK_POLICY_UNSUPPORTED` | 建模期对任务呈现 flag 过度拒绝 | **已修→通过** |
| 微博 | `ACTIVITY_TASK_POLICY_UNSUPPORTED` | 同上 | **已修（越过门）**→`PROVIDER_URI_UNSUPPORTED` |
| 今日头条 | `NATIVE_ELF_NOT_ARM64_SHARED` | arm64 目录内外来 ABI ELF 被拒整次安装 | **已修（可装启）**→`target-first-activity-acknowledged` |
| 网易云音乐 | `NATIVE_ELF_NOT_ARM64_SHARED` | 同上 | **已修（可装启）**→`WORKER_DIED` |
| 小红书 | `APPLICATION_OWNERSHIP_VIOLATION` | onCreate 后改物理身份字段（native L1 `application_adapter.c:182`） | 延后（native 工作线） |
| 知乎 | `callingPackage does not match UID`→SIGABRT | SecNeo：native 加载回调 `Environment.getExternalStorageDirectory`→`StorageManager.getVolumeList`，用 `ActivityThread.currentOpPackageName()`（客体包名）作 callingPackage，与引擎 UID 不符 | 延后（需受管 Hook 固定注册表新增，触及 native） |
| 淘宝 | `InMemoryDexClassLoader cannot be cast to PathClassLoader` | 密封内存 DEX → 客体 ClassLoader 非 PathClassLoader（L2/P4） | 延后 |
| 腾讯新闻 | `ClassNotFoundException …loader.p.ProviderN1` | 类不在内存 DEX 路径 | 延后 |
| 腾讯视频 | `COMPONENT_COLLISION`（`ArtifactSet.java:372`） | 同名非 provider 组件声明不同 | 延后 |
| 抖音 | `MANIFEST_TYPED_VALUE_INVALID`（`BinXmlTypedValues`） | 二进制 XML 定值校验 | 延后 |
| 京东 | `target-first-activity-acknowledged`=false | 首个 Activity 未在窗口内确认 | 延后 |
| 支付宝 | SIGSEGV（SEGV_MAPERR 0x0） | 加固/反篡改 native 自杀（L13） | 延后 |
| 爱奇艺 | `WORKER_DIED` | 启动期 native 崩溃（疑 L13） | 延后 |
| Telegram/Twitter/Instagram/Wikipedia/Duolingo | `REQUIRED_SPLIT_TYPE_MISSING` | split APK 未支持（L2 ❌，仅推 base.apk） | 延后（大型能力） |

修复前通过 2；两处修复后通过 3（+高德），另 3 个越过阻塞门推进到下一层；百度/滴滴无回归。

## 本轮架构修复（纯 Java runtime 层；native 未变，`.so` sha256 不变 `37fd084d…`）

1. **`RuntimeComponents.activity`** — 不再把 `finishOnTaskLaunch`/`clearTaskOnLaunch`/`alwaysRetainTaskState`/`noHistory`/`relinquishTaskIdentity`/`autoRemoveFromRecents` 六个任务呈现 flag 当 `ACTIVITY_TASK_POLICY_UNSUPPORTED` 拒绝。它们由平台任务引擎在物理 stub 上尽力执行，都不产生第二个任务根；拒绝只把装饰性差异变成硬启动失败（微博/高德 Splash 声明 `alwaysRetainTaskState`）。仍拒绝 `documentLaunchMode`（会生成单根票据账本未建模的多文档任务）。

2. **`ArtifactSetZip.entry`** — `lib/arm64-v8a/` 内的 ELF 不再强制 arm64 ET_DYN。真机确认今日头条 `libcvt.so`=32 位 ARM、网易云 `libnesec-x86.so`=x86_64（app 把外来 ABI 残留误放进 arm64 目录）。真实 Android 照常提取，64 位客体不会 map；若 `System.loadLibrary` 则与平台一样 `UnsatisfiedLinkError`（忠实）。改为按认证不透明文件保留，与既有 `native-directory-opaque-resource-preserved` 一致。`ArtifactSetTest` 的 `fake-elf` 断言同步改为保留。

## 延后项原因
- split APK：大型能力（安装事务/ClassLoader/资源/各 split native 提取）。
- PathClassLoader 强转：动密封内存 DEX 安全设计（P4）。
- SecNeo callingPackage≠UID（最有价值，加固 app 普遍）：受管 Hook 为固定注册表，新增 `StorageManager.getVolumeList(int,int)`/`Environment.getExternalStorageDirectory` 目标需改 native 注册表，属另一会话受保护区。建议以引擎真实包名代客体包名发起 UID 校验型系统调用。
- 加固/反篡改 native 自杀（支付宝/爱奇艺疑）：native 隐蔽性工作线（L13）。
- 小红书 `APPLICATION_OWNERSHIP_VIOLATION`：`application_adapter.c` 物理身份不变量（native L1）。
- 腾讯视频/抖音/腾讯新闻/京东：需逐 app 深入，非平凡。

## 附:A 档续修（2026-10-18 晚，用户指示“继续 A 档”）

对 A 档四项逐个深入诊断，只有一项是干净、安全、隔离的架构过度拒绝，已修并验证；其余三项经诊断确认为深层/并发/逐 app 问题，维持延后。

### 已修 ✅ 抖音 `MANIFEST_TYPED_VALUE_INVALID`
`BinXmlTypedValues`：二进制清单里 `Res_value.size` 是信息字段，AOSP 的 ResXMLParser 按固定偏移（value+3 dataType、value+4 data）读取，从不依赖 size；抖音在约 1/6 的属性上写 `size=0`（反解析，probe 实测 7643 个 8 + 1466 个 0，res0 恒 0，无非法 kind）。原来 `size!=8` 即 `MANIFEST_TYPED_VALUE_INVALID` 拒绝整次安装。改为接受 `size∈{0,8}`（kind 白名单与 chunk/stride 边界仍守住读取）。对其余 4 份真机清单（全 size=8）零影响。
- 结果：抖音从**安装被拒(2/2)** → **安装+启动+首个 Activity 确认+运行(6/7)**，仅在 30s 稳定期崩溃（见下）。高德/百度回归通过，无回归。

### 抖音下一层（新发现，未改）：广播层两处**刻意**拒绝
崩溃栈（bytedance `ReceiverRegisterCrashOptimizer` → `SplashAdEntryServiceImpl.onStart`）：
- `RECEIVER_PERMISSION_NOT_OWNED:android.permission.INTERNET`（`RuntimeSystemBroadcasts.permission`：接收器注册权限须本应用签名级声明，否则拒）
- `PLATFORM_BROADCAST_NOT_PROTECTED:android.intent.action.CLOSE_SYSTEM_DIALOGS`（`RuntimeReceivers:57`：已知系统 action 非 protectedBroadcast 则拒监听）
这两处有配套夹具断言（`SystemBroadcastChecks`、`BroadcastPermissionChecks`），是**既定设计契约**，非意外过严；按真实 Android，接收器注册权限只是对发送方的限制、注册方无需持有，监听 CLOSE_SYSTEM_DIALOGS 也应允许。**建议**（需 owner 拍板，未擅改）：注册侧接受任意权限字符串作发送限制、允许监听已知系统 action，而把限制留在投递侧。

### 其余三项维持延后（诊断已确认根因）
- 腾讯新闻 `ClassNotFound ProviderN1`：用 **RePlugin** 插件框架，`attachBaseContext` 中 `com.qihoo360.replugin.utils.e` 反射某字段得 null 抛 NPE（被吞），插件 dex 未注入 → RePlugin 的 provider stub 类找不到。需 RePlugin 反射忠实度，深。
- 京东 first-activity：首个 Activity 弹隐私对话框时**主线程 ANR**（MIUIScout 报 ~5s 阻塞），15s 内未确认；其间 `TELEPHONY_CAPABILITY_UNSUPPORTED:getCallState()` 被吞。慢启动/ANR，非单一门。
- 腾讯视频 `COMPONENT_COLLISION`：代码（`ArtifactSet.java` 组件合并循环）正被另一会话在制编辑（dexFiles/P4），且需 take-last 语义 + 槽位一致性，非平凡。

## 附二:广播层两处放宽（2026-10-18 晚，用户同意后实施）

抖音经 BinXml 修复后，下一层撞到广播层两处**刻意的过度拒绝**。按真实 Android 语义放宽（投递侧仍是唯一强制点），抖音 **6/7 → 9/9 完全通过**。

### 改动（3 处生产 + 2 处夹具，纯 Java，`.so` sha256 不变）
1. `RuntimeSystemBroadcasts.permission()` → `ownsSignaturePermission()` + 重写 `holds()`：注册/发送不再因“权限非本应用签名级自有”而预拒；自定义签名权限行为**完全保留**（需同签名+声明），平台/普通权限（如 `INTERNET`）按“manifest 是否声明”判定、无签名耦合。删除 `RuntimeBroadcastBroker` 注册侧与发送侧两处预检。
2. `RuntimeReceivers`：删除 `PLATFORM_BROADCAST_NOT_PROTECTED` 门——已知系统 action 无论是否 protected 都订阅真实平台广播（仅监听；客体发送系统 action 仍在发送侧被拒）。
3. 夹具 `SystemBroadcastChecks`/`BroadcastPermissionChecks`：外来权限改为“注册成功，限制在投递侧生效”、未持有权限发送改为“不再抛异常（只是不投递）”。

### 关键：百度地图不是本改动的回归（对照实验已证）
重测中百度从通过变 5/6 失败。**逆转全部广播改动重建后，百度仍 5/6 失败、抖音也退回失败**，证明百度与广播改动**无因果**。百度崩溃是后台 `DeviceId` 线程池反复命中**既有**未实现功能 `URI_PERSISTABLE_GRANT_NOT_IMPLEMENTED`（`RuntimeSelfHookPlatformCallbacks.persistablePermission`，与广播无关），其完整 main 日志中无任何广播拒绝行。它从 retest2 通过变为现在失败，是共享设备时序让该后台崩溃落入 30s 窗口所致，非代码回归（见 [[shared-tree-native-work]] 的共享设备告警）。**新发现延后项**：persistable URI 授权未实现，百度（及可能他者）需要。

### 最终真机（本日第 4 次重建，广播修复已装机）
抖音 9/9 ✅、高德 9/9 ✅、滴滴 9/9 ✅。本日累计 5 处纯 Java 架构修复：启动器 task-policy、外来 ABI ELF、BinXml size、广播接收器权限、系统广播监听。每次 `.so` sha256 恒为 `37fd084d…`。
