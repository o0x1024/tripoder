# 当前六应用故障分析与零 Hook 基线整改方案

2026-10-27，依据连接真机的当前应用目录、持久进程历史、系统退出记录、logcat、Native 故障文件及工作区源码。整改方向采纳用户提出的顺序：**先取消所有 Hook，验证运行和性能，再按证据做加法。** 下文主体保留实施前诊断；当日首批实现与实测结果见文末「实施进度」。最新 Manager/Native/ART 改动、设备断开造成的验证缺口及恢复步骤见 [Level 1 实施状态](level-one-runtime-status-2026-10-27.md)。六应用 Level 1 尚未验收。

**结论**

目前已经证实：多个 Android 合法调用在承载层被拒绝或身份转换不完整；启动和运行期的 Hook 发布存在可观测开销；Native 信号/系统调用接管也引入了独立失败点。继续在这一整套修改之上逐个补应用，难以区分平台缺口、Hook 副作用和客体自身行为。应建立可重复的零 Hook 对照，再重建最小承载集合。

“去掉全部 Hook 后六应用应该正常”是需要验证的假设。现有应用运行在宿主 UID 和 manifest 组件之下，停用 Hook 不会自动获得真实安装的包身份、组件注册和数据权限。零 Hook 样本若先因承载条件不足失败，应记录第一个失败边界；尚未到达的页面不能被计为性能通过。任何固定 Hook 都需要重新证明必要性，不能因为名称叫“核心适配”就豁免。

**本次实际环境**

- 当前宿主为 `dev.tripoder.faultqa`，UID 10500，安装时间 17:18:41；设备为 `d861a4fa`，Android 17 / API 37。
- 已安装宿主 APK SHA-256：`cf2deeadf03d4e04fc6a2f7d65f54a570310f3ea01fe9286426c9380fdfe4b48`。
- 已安装 APK 内 Native core SHA-256：`9ee0d6d828cccad6b41959b88ff2a236f4eb4a52bb9b3e5dc13e0c3b80555155`。
- 检查的源码为 `36acf36951533d9b84b14cf7056b14d5501be7ba`。本地产品构建回执属于另一份较旧 APK，尚未建立这份 QA APK 与完整源码快照的一一对应；下文将设备证据与源码修复入口分别说明。
- MCP 与工作台共同确认：招商银行、芒果 TV、网易云音乐、淘宝、腾讯视频、拼多多，均安装在 `default` 空间。本次读取已有故障，未重新启动六个客体。
- 证据和安装/进程代次见 [summary.json](evidence/six-app-diagnosis-2026-10-27/summary.json)；原始全量日志保存在本机临时目录，仅将相关故障片段归档。

**逐应用：已确认什么，还有什么未知**

| 应用 | 当前安装的故障证据 | 判断与边界 |
|---|---|---|
| 招商银行 `cmb.pb` / 1446 | PID 8229，17:21:00 SIGSEGV；本代次故障文件记录 stage=2，`libCmbShield.so+0x7af58` 的 `LDR W9,[X8]` 读取匿名 `---p` 页 | 已定位到 Application.attach 阶段的保护页访问。银行加固壳常通过 `PROT_NONE`（`---p`）守卫页配合自定义 `SIGSEGV` handler 实现动态解密或自校验；需重点核查 Tripoder 的 signal chain / `other_sigaction` / 故障捕获器是否抢先拦截或破坏了客体自定义 `SIGSEGV` 的派发与恢复，不能直接归结为反沙箱或吞掉信号。 |
| 芒果 TV `com.hunantv.imgo.activity` / 9403100 | PID 8797 在 stage=5、`libmsaoaidsec.so+0x20d10` SIGSEGV；下一次 PID 10129 的 Chrome_ProcessLauncherThread 抛 `BIND_EXTERNAL_SERVICE failed, calling package not owned by calling UID` | 至少两个独立故障。Native 指针异常待追踪；WebView 崩溃根因与腾讯视频 `mkdirs` 异常同源：调用绕过了外层 `GuestContext`，触达了 `mPackageInfo` 为客体包名但 `mOpPackageName` 为宿主包名的底层裸 `ContextImpl`，导致 AMS 校验物理包名/UID 失配。 |
| 网易云音乐 `com.netease.cloudmusic` / 9006005 | PID 11137，17:21:25，系统记为 `EXIT_SELF / status=28`，约启动后 6 秒退出 | 已确认主动退出，缺少退出调用栈；尚不能认定是保护检测、Hook 故障或某个业务判断。需要同版本原生对照和有界退出来源采集。 |
| 淘宝 `com.taobao.taobao` / 862 | PID 12316，商品详情页焦点事件等待 5 秒触发 ANR；有一次跳过 554 帧的记录；Scout 样本显示主线程执行资源读取、TextView 构造和页面测量/布局 | 已确认交互不可用。需要 CPU/调度与原生配对测量，才能区分执行放大、布局开销及后台竞争；现有样本不足以给所有耗时分摊比例。 |
| 腾讯视频 `com.tencent.qqlive` / 32352 | 主进程和 cache 进程均因 `ACTIONLESS_DATA_FILTER_UNSUPPORTED` 崩溃；主进程另有输入 ANR，ANR 窗口 CPU 为 567%；WebView 初始化时主线程停留在 `ManagedHook.toggleBatchNative`；反复出现 external files 目录与调用包名不一致的异常 | 广播注册规则、底层 `ContextImpl` 裸露（导致 `mkdirs` 用客体包名拼路径却以宿主 `mOpPackageName` 校验）与运行期动态 Hook 发布均需处理。CPU 567% 是多核累计使用（约 5.7 核），不等于全部消耗在 Hook。 |
| 拼多多 `com.xunmeng.pinduoduo` / 82600 | PID 26943 在页面逻辑读取 extras 时 `BadParcelableException / ClassNotFoundException: ForwardProps`；另一个服务进程 PID 29673 启动时 `NATIVE_SYSCALL_NAMESPACE_UNAVAILABLE:userDeliveryStatus=5` | `Parcel$LazyValue` 延迟解包时子 Bundle / 拷贝后的 Bundle 丢失客体 ClassLoader；服务进程启动时 `userDeliveryStatus=5` 对应线程暂停失败（PARK），暴露出多进程并发启动期强制全线程 Park 的脆弱性，需保留独立根因。 |

对应证据：[Java / Native 故障](evidence/six-app-diagnosis-2026-10-27/failures.txt)、[系统退出记录](evidence/six-app-diagnosis-2026-10-27/exit-info.txt)、[性能与身份异常](evidence/six-app-diagnosis-2026-10-27/performance.txt)。

**为什么先做减法（及已定位的架构冗余）**

1. **启动期 1809 个固定绑定的双重冗余：** 当前设备日志显示每个已观测客体进程启动发布 1809 个固定绑定。腾讯视频从 `RuntimeEngine` 启动计时到 `hooks` 为 6425ms，拼多多为 7895ms；其中固定安装分别约 3131ms、5724ms，批量发布约 2330ms、3724ms。源码中 `GuestContext.getSystemService` 已经显式拦截并返回了 12 个 Manager 家族的 `facade()`，但 `RuntimeSelfHookProduct` 仍在启动期无条件对 `FixedManagerInventory` 中 1700+ 个系统 Manager 方法做全量 ART 绑定（即便客体从未使用 `WifiP2pManager`、`ShortcutManager` 等）。
2. **运行期动态注册触发主线程 ART 重发布：** 腾讯视频在首次创建 WebView 时动态注册 receiver，主线程栈进入 `RuntimeReceivers.registerRegistered → RuntimeBridge.installReceiverAdapter → installLifecycle → enableEntryBatch → toggleBatchNative`。每次客体动态注册新的 `BroadcastReceiver` 子类都在当前线程（常为 UI 主线程）触发 JNI 锁与 ART 方法入口修改；而动态注册完全可通过向宿主注册一个显式代理 `BroadcastReceiver` Wrapper 转发给客体 `onReceive`，无需在运行期对客体子类逐个打 ART Hook。
3. **JIT 与采样器状态澄清：** 两个启动样本均报告 `jitEnabled=true / runtimeStateRestored=true / allowUnhookedCompilation=true`。旧文档中“运行期间一直关闭 JIT”“主线程采样器一直开启”的结论不能复用于当前构建。源码中主线程采样器已有显式诊断开关，普通客体 ClassLoader 也已走平台 namespace 创建路径。
4. **Native 接管与全线程 Park 的独立风险：** Native 接管包含 seccomp/SIGSYS、ART 用户信号投递以及线程暂停/发布。已经出现 `userDeliveryStatus=5`（线程 Park 失败直接导致 Service 进程崩溃），且现有代码还会拒绝系统 Native dump 请求，导致本次 ANR 未能生成完整 Native 堆栈。此层需要独立验证，不能视作零成本基础设施。
5. **正确性错误放大性能问题：** 腾讯视频因底层 `ContextImpl` 裸露导致外部存储路径 `SecurityException` 重复发生，随后应用、系统监控及故障处理继续执行。源码和栈表明存在这些路径，但还不能把异常日志数直接换算成 CPU 归因。

**分层基线必须如何定义（避免“绝对零 Hook”假阴性死锁）**

由于客体运行在宿主 `dev.tripoder.faultqa` 的 UID 与预置槽位组件（`RuntimeActivitySlots` / `RuntimeServiceSlots`）之上，若将 `Application` 身份绑定、Activity 路由和基础路径重定向全部清零去跑六个商业应用，100% 会在首个页面跳转或首次 Native 文件打开时死在已知的容器骨架缺失上，无法到达业务页面测试性能与深层兼容性。因此基线拆分为两个明确层级：

- **Level 0（纯宿主 / 零 Hook 基础设施基线）：** Tripoder 在客体进程中新增的 Hook 与 Native 拦截严格为 0（非仅 `startupHook=false` 或用户脚本为 0）。仅使用**自研极简 Fixture APK** 对照验证 ART / Seccomp 基础设施本身的固定开销。
- **Level 1（商业六应用的最小骨架基线 / Skeleton-Only Baseline）：** 仅保留客体存活并进入页面所必需的**最小显式承载与组件生命周期骨架**（`Application` 视图绑定、Activity/Service/Provider/Receiver 最小路由、基础路径重定向），**一次性砍掉所有非必要 Hook**（12 个 Manager 家族的 1700+ 个固定方法绑定、运行期 `installReceiverAdapter` 动态 ART Hook、探测/分析/防逃逸策略 Hook）。

| 类别 | Level 0（纯零 Hook 对照） | Level 1（六应用最小骨架基线） | 主要审计入口 |
|---|---|---|---|
| 固定 Java / Framework Hook | 绑定数为 0；不创建、不发布任何方法替换 | 砍掉 12 个 Manager 家族全量方法绑定；仅保留组件生命周期与 `Application` 最小绑定 | `RuntimeSelfHookProduct`、`RuntimeFixedPreparation`、`RuntimeManagedInstaller` |
| 运行期动态组件 Hook | 绑定数为 0 | 废除动态 `registerReceiver` 的 `installReceiverAdapter` ART Hook，改为显式 Wrapper 转发 | `RuntimeReceivers`、`RuntimeBridge`、`RuntimeSelfHookComponents` |
| ART 修改 | 不执行调用者失效、不改方法入口/编译标志 | 仅在启动期一次性发布最小骨架绑定，运行期主线程 0 次 `toggleBatchNative` | `ManagedHook`、`art_managed_entry.c` |
| Native 拦截与信号 | 无 inline/GOT/PLT 拦截，无新增 seccomp 和信号接管 | 仅保留客体私有目录最小路径重定向；关闭非必要策略拦截，修复自定义 `SIGSEGV` 转发与 Park 脆弱性 | `RuntimeSelfHookStorage`、`self_hook_storage.c`、`syscall_namespace.c` |
| 启动与分析工具 | 业务脚本、启动探针、抓包、环境模拟、退出拦截均不装配 | 同左，全部不装配 | `RuntimeEngine`、`RuntimeHookBridge` |
| 显式 Java 承载代码 | 审计 Context 包装与组件路由耗时 | 预先修复已确认的 4 个显式承载层缺陷（见下文） | `GuestContext`、`ApplicationAdapter`、`RuntimeBroadcastIntent` |

**必须从全新进程启动。** 现有进程执行过的方法替换、JIT 失效和 seccomp 安装，无法用一次 `disableAll()` 恢复成从未发生过的对照。每个基线构建必须输出可核对的回执：实际固定/动态绑定数、运行期 `toggleBatchNative` 次数、signal claim 与 seccomp 状态，区分“真正裁剪 Hook”和“仅关闭统计”。

**执行顺序与每一步的退出条件**

1. **建立系统原生对照（可与步骤 2 并行）。** 对同一设备、同一 APK/Split 摘要、相同初始状态和相同操作场景，记录原生启动到第一帧/可交互、滚动、播放、前后台切换及退出。原生也失败或触发风控的场景先识别出来。固定刷新率、网络条件、热状态，分别记录首次与后续冷启动。
2. **建立 Level 0 极简样本对照 + 前置修复 4 个已证实的显式承载层缺陷。**
   - 用自研极简样本在 Level 0（0 绑定）下测量纯容器启动与帧率基线。
   - 针对商业六应用，**不等实验失败直接前置修复已在源码中 100% 确认的 4 个显式承载层/架构缺陷**：① `RuntimeBroadcastIntent.filter()` 拒绝无 action 过滤器；② `RuntimeReceivers` 动态注册触发主线程 `toggleBatchNative`；③ 底层裸 `ContextImpl` 暴露导致 WebView `BIND_EXTERNAL_SERVICE` 与 `mkdirs` 包名校验崩溃；④ 嵌套/拷贝 `Bundle` 在 `LazyValue` 解包时丢失客体 `ClassLoader`。
3. **运行 Level 1（最小骨架基线）六应用实验。** 在砍掉 1700+ 个 Manager 固定 Hook 及分析工具、且修完上述 4 个确定性缺陷的 Level 1 构建上，每次使用全新进程逐个运行六个原版应用。验证启动耗时下降幅度（目标：将原 6~8 秒的 `hooks` 阶段压缩至百毫秒级），并记录每个应用在最小骨架下的真实到达阶段与剩余边界。
4. **按证据最小化补齐承载缺口，达到六应用无分析工具可用基线。** 每遇到一个真实缺口，优先用明确的 `GuestContext`、Wrapper、资源依赖及 IPC 边界实现；无法由显式边界承担时，才为一个最小职责加入 Hook。每项写出：具体失败样本、平台期望行为、为何需要修改、受影响入口、耗时预算。对招商银行和网易云音乐，重点验证信号链转发契约与退出来源。
5. **按需加入分析 Hook。** Java 单方法 Hook、Native 单函数 Hook、网络观察、启动期探针依次独立验收。启动期工具仍需在对应业务代码之前正确安装，但只由明确请求触发。每加入一组后通过迭代冒烟门槛（Smoke Gate）验证真实命中、原行为恢复和应用稳定性，定位第一个引入回退的组。

这些是实验阶段，最终产品继续维护一套最小运行时；不恢复 THIN/THICK 模式分叉或失败后自动换路径。分析工具能力独立于“应用可以正常运行”。

**当前明确错误的根因修正与解决路径**

- **广播（前置必修）：** `RuntimeBroadcastIntent.filter()`（第 47 行）直接拒绝没有 action 但含 data/category 的过滤器，导致腾讯视频主进程和 cache 进程崩溃。应通过平台 fixture 对齐注册、匹配和空匹配语义，保留权限、导出标志和生命周期；同时在 `RuntimeReceivers.registerRegistered()` 中用显式代理 `BroadcastReceiver` 替代 `RuntimeBridge.installReceiverAdapter(receiver)`，彻底消除运行期主线程 `toggleBatchNative`。
- **底层 `ContextImpl` 裸露导致的 WebView 与外部存储身份失配（前置必修，同源问题）：**
  - **真因：** `GuestStorageContext.getExternalFilesDir` 本身返回宿主私有目录下的子目录，不会触发系统 `/storage/emulated/0/Android/data/<guest_pkg>/files` 校验；真正触发腾讯视频 `Invalid mkdirs path` 和芒果 TV `BIND_EXTERNAL_SERVICE failed` 的，是 `application_adapter.c` 与 `ApplicationBinding.java` 创建并发布的**底层裸 `ContextImpl`**（其 `mPackageInfo` 为客体 `LoadedApk` 即客体包名，而 `mOpPackageName` 为宿主包名 `dev.tripoder.faultqa`）。当客体、壳或 WebView 通过 `getBaseContext()` 拆包或通过 `LoadedApk` 绕过外层 `GuestContext` 直接调用该 `ContextImpl` 时，`mkdirs` 与 `bindServiceCommon` 均因 `mPackageInfo.getPackageName()` 与宿主 UID/OpPackageName 冲突而被系统拒绝。
  - **解决：** 全面核查 `ContextWrapper.getBaseContext()` 暴露链、`ApplicationAdapter.createNative` 生成的 `ContextImpl` 以及 Activity/Service base context，确保任何可触达系统 Binder 校验（AMS `bindServiceInstance`、StorageManager `mkdirs`）的 `ContextImpl` 入口在物理调用包名、UID、attribution 上保持一致，不因外层 `GuestContext` 被剥离而裸露分裂身份。
- **Parcelable 与嵌套 Bundle 类加载器（前置必修）：** 拼多多在 `BaseBundle.getSerializable → Parcel$LazyValue.apply` 解包 `ForwardProps` 时抛 `ClassNotFoundException`（`Parcel.java:5411` 的 `Class.forName` 使用了 null 或非客体 loader）。原因在于 Android 13+ `LazyValue` 延迟解包时，顶层 `intent.setExtrasClassLoader()` 不会自动传播到已被拷贝的 `Bundle` 副本或内部嵌套的子 `Bundle`。需检查 `RuntimeActivityIntent.visible()`、`RuntimeBroadcastIntent` 及 Activity `getIntent/newIntent/savedInstanceState` 边界，确保所有层级的 `Bundle` 在首次触发 `unparcel` / `LazyValue` 解包前均关联 `engine.guestLoader()`，且不在 authority 进程解码客体专有 Parcelable。
- **Native 信号链与线程暂停（招行 SIGSEGV & 拼多多 `userDeliveryStatus=5`）：**
  - **招行 `libCmbShield.so` 保护页访问：** 不能通过强行改页权限或吞掉 `SIGSEGV` 处理。需优先排查 `libCmbShield.so` 是否注册了自定义 `SIGSEGV` handler（用于 `---p` 保护页自解密/反调试），并验证 `syscall_namespace.c`（`other_sigaction`）、`signal_chain.c` 及 `TP_FAULT_V1` 故障记录器是否在 `SIGSEGV` 触发时抢先终止了进程或未正确将 `ucontext` 转发给客体 handler。
  - **拼多多 `userDeliveryStatus=5`（PARK 失败）：** 首先评估最小承载是否必须在每个组件进程启动时执行全线程暂停（thread park）；若仍需要，需将安装时机前移至单线程最早阶段，并为 `tp_park` 增加有界退避重试（Bounded Retry with Backoff），避免多进程并发启动时因后台线程瞬时不可 Park 而直接抛 `IllegalStateException` 杀死 Service 进程；同时修复拒绝 debuggerd Native dump 请求的问题，恢复 ANR Native 堆栈采集能力。
- **生命周期与资源：** 当前快照为 8 槽、0 allocated、6 available、2 retained，存在已死亡进程的 Activity/Job 引用和 9 条 UNKNOWN 操作。证据不足以把当前主因归结为槽位耗尽。应按 generation 和平台对象存续证据回收；不能仅因为进程已死就删账本，也不能将 slot 错配直接重绑定到可能属于其他代次的进程。一应用一空间可辅助隔离实验，但不会凭空增加 Android manifest 中的物理进程槽。

**分级验收标准（建议门槛，尚未达成）**

为兼顾日常迭代效率与最终交付质量，并规避商业应用（招行、淘宝、拼多多）因高频连续重启触发服务端滑块/设备风控干扰测试结论，采用**两级验收门槛**：

| 维度 | 迭代准入门槛（Smoke Gate：每次增量修改 / 加回单组 Hook 必过） | 基线冻结门槛（Release Gate：Level 1 基线达成及最终交付必过） |
|---|---|---|
| 诊断真实性 | 每次构建保存 APK/core/source 摘要、固定/动态绑定数及运行期 `toggleBatchNative` 计数。 | 原生、Level 0、Level 1 及最终构建均归档完整回执与配对数据，同一场景可重复。 |
| 启动 | 每应用连续 **5 次**独立冷启动，100% 到达可交互首页；记录真实失败，禁止自动重试覆盖。 | 每应用至少 **30 次**独立冷启动（设置合理冷却间隔以规避服务端风控拦截），全部到达可交互页面；补测热启动和后台恢复。 |
| 稳定性 | 每应用 **3 分钟**核心路径交互 + **5 轮**前后台切换，0 崩溃 / 0 ANR / 0 主动退出。 | 每应用连续 **60 分钟**固定场景、至少 **20 轮**前后台切换，0 未解释的崩溃/ANR/主动退出；最后追加六应用混合使用，检查串扰与槽位释放。 |
| 性能 | 启动 `hooks` 阶段耗时 ≤ 500ms；运行期 UI 主线程 `toggleBatchNative` = 0 次；无新增 >30 帧的卡顿。 | 配对重复实验比较 P50/P95：启动 P95 增量 ≤ max(300ms, 原生 P95 的 15%)；同场景 CPU/稳态内存增量 ≤ 10%；deadline jank 增量 ≤ 2 个百分点。 |
| 业务 | 媒体应用验证首页加载与起播；电商验证列表进入详情页；银行验证启动至登录/首页无闪退。 | 媒体应用验证页面、播放/暂停及后台恢复；电商验证列表、详情和返回；银行由用户完成真实认证后验证只读页面。仅有 `onResume` 或短暂存活不算通过。 |
| 加回 Hook | 证明单组必要性、Smoke Gate 全部通过且性能在预算内；不通过立即撤回。 | 所有保留 Hook 均具备独立必要性证明，且整体通过 Release Gate。 |

零故障样本只能支持这批版本、设备和场景的验收结论，不能推出所有第三方应用永远兼容。当前最有把握的工程路径是先把“可正常运行的最小修改集合”测出来，再把测试能力建立在这条基线上。

**实施进度（2026-10-27，第一批）**

已落实 Level 0 自研场景对照、四处已知入口的前置修复，以及实际 ART 发布计数。独立 QA 包为 `dev.tripoder.ingressqa`，没有覆盖安装原产品或清除六应用数据。[构建、来源快照与阶段汇总](evidence/six-app-implementation-2026-10-27/summary.json)。

1. **广播过滤器与显式交付。** 无 action 且含 data/category 的 filter 可以注册，保持无隐式 action 匹配语义。动态、manifest 与 ordered-final receiver 均改为 `RuntimeReceiverProxy`；移除了 `installReceiverAdapter` / 客体 `onReceive` 动态 ART 安装入口。代理转交同一个平台 PendingResult，并将 `goAsync` 后的 null 状态交还给平台，保留异步完成、有序结果、Handler 调度、注销和原 authority 账本。固定 `goAsync` / `PendingResult.finish` 账本适配仍在，不能将这项变更表述成整个广播栈零 Hook。
2. **裸 Context 的存储与服务入口。** 保留客体 LoadedApk 的身份与资源契约，将七个裸 ContextImpl 外部目录 getter 接回同一 `GuestStorageContext` 存储 owner；将原来只覆盖两个 `bindServiceAsUser` 重载的适配收敛到 `bindServiceCommon`，覆盖 Handler/Executor、int/long flags、isolated instance，并接通 unbind/rebind/updateGroup。调用 AMS 时使用物理宿主 Context，保留 BIND_EXTERNAL_SERVICE 的长标志映射。新增的最小固定入口有明确失败样本；这不等于所有裸 Context API 的身份审计已完成。
3. **延迟解包。** 新增 `RuntimeGuestPayload`，在 Activity 可见 Intent、Activity result、保存状态、Service/Broadcast 交付边界重新封装平台 Parcel。它保留未解包数据和 Binder，不提前运行 guest Parcelable creator；重新读取时为嵌套 Bundle 选择 guest loader。源码核对发现 `Bundle` 的普通复制实际会复制 loader，真正的问题是已经形成的 `Parcel.LazyValue` 固定捕获旧 loader，之后 `setClassLoader` 不会更新它。本次没有修改 LazyValue 私有字段或全局 Hook Parcel。客体交付后自行使用错误 loader 重新解包的数据不在此修复范围。[AOSP BaseBundle](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BaseBundle.java)、[AOSP Parcel](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Parcel.java)。
4. **真实发布回执。** `ManagedHook.publicationMetrics()` 记录实际进入 `toggleBatchNative` 的调用次数、成功次数、bootstrap 后次数和主线程次数，随现有 status 返回。计数包含 enable/disable，不能通过仅关闭日志将发布成本隐藏。

**已验证的范围**

- 主机策略/组件/manager 回归及 **403 个 Java 源文件的 SDK 编译通过**；完整 QA Java/Native 构建通过。[主机报告](evidence/six-app-implementation-2026-10-27/host-regression.json)。
- 真机 **16 项定向检查 + 3 项完整客体检查通过**。同一未改写的自研 APK 在系统原生安装及宿主内均显示 `INGRESS_GUEST_PASS`。覆盖裸/派生 Context 外部目录、无 action 注册、动态异步广播、有序最终结果、manifest receiver、嵌套 Serializable、Activity result、Activity recreate 后保存状态，以及**直接使用剥离包装后的派生 ContextImpl 创建 WebView并加载本地页面**。[原生样本](evidence/six-app-implementation-2026-10-27/guest-ingress-final/native-fixture.json)、[定向检查](evidence/six-app-implementation-2026-10-27/guest-ingress-final/RuntimeIngressIntegration-result.json)、[完整客体回执](evidence/six-app-implementation-2026-10-27/guest-ingress-final/GuestIngressIntegration-result.json)。
- 最终客体 PID 15971：固定绑定 **1818**，额外动态准备绑定 **0**，bootstrap 一次发布，运行期 `toggleBatchNative` **0**，主线程运行期发布 **0**；仍有 **2** 个 Native signal claim。当前样本 `hooks +2441ms` 是累计启动阶段标记，不能与拆分安装/发布耗时混用，且未达到方案 ≤500ms 门槛。[按最终 PID 提取的运行记录](evidence/six-app-implementation-2026-10-27/guest-ingress-final/guest-runtime.txt)。
- Level 0 两个 APK 都不包含 Tripoder runtime/selfhook DEX 或 Native core；宿主加载与系统原生安装完全相同的自研 Fixture APK。原生/宿主各 **5 次独立冷启动、900 帧**，全部到达绘制。两边继承的平台 Seccomp filter 均为 1，新增为 0，signal mask 相同。窗口请求 90Hz 后设备实际报告两边均为 **120Hz**；原生/宿主从 Activity 构造至首次绘制的 P50 分别为 **47.44 / 49.75ms**。这不是完整进程启动耗时，也不是商业应用或 Release Gate 性能结论。[配对数据](evidence/six-app-implementation-2026-10-27/level-zero-window-rate-request/summary.json)。首轮刷新率漂移的数据另行保留，不混入此配对结果。

失败记录没有覆盖：第一次完整客体因样本遗漏 WebView 使用的 ACCESS_NETWORK_STATE 权限失败；补齐 manifest 后，第二次因测试结束留下的 `SUPERVISOR_INTERRUPTED` 停在引擎连接。验证脚本的显式 `--retry-engine-once` 仅执行一次工作台重新连接，不重放客体操作。第三次核心断言通过，但收尾按钮被 Android 转成大写，测试匹配失败；修正 fixture 显示属性后最终整轮通过。各次独立回执保存在同一证据目录。

**Level 1 仍需完成的工作（第一批时点）**

本节保留第一批交接状态；后续已实施的 Manager/Native 修改与尚未完成的验证见 [Level 1 实施状态](level-one-runtime-status-2026-10-27.md)。

源码审计推翻了「已有 facade，所以 Manager 全量 Hook 都是重复实现」这一前提：`ManagerFrameworkAccess` 创建的多数 facade 仍是普通 Android Manager，有些还持有 null Binder；其分派依赖 `RuntimeSelfHookManagers` 的 ART 绑定。直接删除绑定并继续返回这些对象，会丢失通知/Job/权限/环境模拟语义，甚至产生空服务调用。本批没有以这种方式伪造最小骨架已完成。

下一阶段需要先将这些 Manager 的职责移至明确的实例/IPC 边界，或证明某一服务可直接使用物理平台实现，再同步删除准备、helper、安装和发布集合。当前固定绑定比旧样本增加 9 个，来自此次补齐的裸 Context 入口，因此 **当前产品路径仍不是 Level 1**。Native signal chain、全线程 Park、debuggerd dump、六应用原生配对与五次启动/三分钟业务 Smoke Gate、最终 Release Gate 均未完成；没有运行或宣称六个商业应用已经恢复正常。

复现入口：`android/scripts/build-level-zero.py` / `validate-level-zero.py`；`android/scripts/build-workbench.py --package dev.tripoder.ingressqa --build-dir android/product/build-six-app-ingress ...`；`android/scripts/validate-runtime-ingress.py --receipt android/product/build-six-app-ingress/build-receipt.json --output <新的证据目录> --serial d861a4fa --retry-engine-once`。构建回执保存完整源文件摘要、APK/core 摘要；最终来源快照在本机构建目录，路径与摘要见阶段汇总。
