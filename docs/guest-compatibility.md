# Guest 应用兼容性分层与真机基线 (Guest Compatibility)

- **更新日期**：2026-10-28
- **关联文档**：[当前总体架构](current-architecture.md) · [单运行时与最小底座收敛](single-runtime.md) · [架构梳理与实施计划](implementation-plan.md)

本文档从第一性原理出发，将未修改 APK 在 Tripoder 中运行所需的系统支撑拆解为 **13 个正交兼容层（L1–L13）**，明确区分**「第一层：统一最小底座（Level 1）默认闭环」**与**「第二层：按需扩展策略（Opt-in）」**，并汇总最新六大重度商业应用的真机实测基线与已知边界。

---

## 1. 第一性原理：Guest 应用兼容性的 13 层契约矩阵

| 层级 | 契约名称 | 本质问题 | 架构归属 | 当前状态 | 核心实现位置 |
| --- | --- | --- | --- | --- | --- |
| **L1** | **APK 载荷与真实 ABI 装载** | 多 Dex、Split APK 与 Native `.so` 的真实 linker 搜索路径如何建立？ | **最小底座** | **已闭环** | [`ArtifactSet`](../android/runtime/src/dev/tripoder/runtime/ArtifactSet.java), [`RuntimeExecutionCode`](../android/runtime/src/dev/tripoder/runtime/RuntimeExecutionCode.java), [`RuntimeSharedLibraries`](../android/runtime/src/dev/tripoder/runtime/RuntimeSharedLibraries.java), [`RuntimeDexLoader`](../android/runtime/src/dev/tripoder/runtime/RuntimeDexLoader.java) |
| **L2** | **进程身份与壳 Application 生命周期** | `LoadedApk`、壳代理 `mInitialApplication`、包名、签名如何呈现为 Guest 自身？ | **最小底座** | **已闭环** | [`RuntimeEngine`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java), [`RuntimePackages`](../android/runtime/src/dev/tripoder/runtime/RuntimePackages.java), [`RuntimeProcessIdentity`](../android/runtime/src/dev/tripoder/runtime/RuntimeProcessIdentity.java) |
| **L3** | **文件系统隔离与 `dataDir` 长度契约** | Java API、裸 `ContextImpl` 与 Native `libc` 读写私有目录如何落入独立空间且不溢出壳缓冲区？ | **最小底座** | **已闭环** | [`RuntimeStorage`](../android/runtime/src/dev/tripoder/runtime/RuntimeStorage.java), [`GuestStorageContext`](../android/runtime/src/dev/tripoder/runtime/GuestStorageContext.java), [`fixed_libc.c`](../android/self-hook/native/fixed_libc.c) (`path_policy.c`) |
| **L4** | **ContentProvider 初始化与路由** | 启动期 `installContentProviders` 及跨进程/同进程 Provider 如何解析？ | **最小底座** | **已闭环** | [`RuntimeProviderIndex`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviderIndex.java), [`RuntimeProviders`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviders.java) |
| **L5** | **Activity 启动、栈与 Parcel 类加载器** | 单跳启动、LaunchMode、Dialog 窗口 Token 归属及嵌套 `Bundle` `LazyValue` 解包如何保真？ | **最小底座** | **已闭环** | [`RuntimeActivitySlots`](../android/runtime/src/dev/tripoder/runtime/RuntimeActivitySlots.java), [`RuntimeGuestPayload`](../android/runtime/src/dev/tripoder/runtime/RuntimeGuestPayload.java) |
| **L6** | **Service 与 `bindServiceCommon` 绑定** | `startService`、裸 `ContextImpl` 的 `bindServiceCommon` 及 `BIND_EXTERNAL_SERVICE` 如何受控运行？ | **最小底座** | **已闭环** | [`RuntimeServices`](../android/runtime/src/dev/tripoder/runtime/RuntimeServices.java), [`RuntimeServiceSlots`](../android/runtime/src/dev/tripoder/runtime/RuntimeServiceSlots.java), [`ApplicationBinding`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationBinding.java) |
| **L7** | **Broadcast 显式代理、PendingIntent 与 Job** | 动态/静态广播、无 Action 过滤器、延时意图与 `JobScheduler` 如何零主线程重发布回调？ | **最小底座** | **已闭环** | [`RuntimeReceivers`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceivers.java), [`RuntimeReceiverProxy`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java), [`RuntimeJobScheduler`](../android/runtime/src/dev/tripoder/runtime/RuntimeJobScheduler.java) |
| **L8** | **Binder Calling Package 校验与系统服务** | 通知、快捷方式、生物识别等校验 `callingPackage == uidPackage` 的边界如何对齐？ | **最小底座** | **已闭环** | [`RuntimeNotifications`](../android/runtime/src/dev/tripoder/runtime/RuntimeNotifications.java), [`RuntimeBiometrics`](../android/runtime/src/dev/tripoder/runtime/RuntimeBiometrics.java), [`RuntimeShortcutCapability`](../android/runtime/src/dev/tripoder/runtime/RuntimeShortcutCapability.java) |
| **L9** | **WebView 多进程隔离与 DEX 加载通知** | 多进程 WebView 目录后缀、外部渲染服务绑定及 `notifyDexLoad` 校验如何兼容？ | **最小底座** | **已闭环** | [`RuntimeWebView`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebView.java), [`RuntimeWebViewServices`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebViewServices.java), [`RuntimePackageContexts`](../android/runtime/src/dev/tripoder/runtime/RuntimePackageContexts.java) |
| **L10** | **资源、Asset 与共享库桥接** | 动态加载插件、Split APK 资源或系统共享库如何防止 `Resources$NotFoundException`？ | **最小底座** | **已闭环** | [`RuntimeManifestIndex`](../android/runtime/src/dev/tripoder/runtime/RuntimeManifestIndex.java), [`RuntimeResourceLoader`](../android/runtime/src/dev/tripoder/runtime/RuntimeResourceLoader.java) |
| **L11** | **进程环境隐身 (`/proc` & 栈归属)** | 读取 `/proc/self/cmdline`、`maps` 或反射调用栈时如何隐藏宿主痕迹？ | **最小底座** | **已闭环** | [`path_policy.c`](../android/runtime/native/path_policy.c), [`art_reflection_view.c`](../android/self-hook/native/art_reflection_view.c) |
| **L12** | **设备画像伪装与环境故障注入** | 运营商/定位/电量/VPN 状态伪装与弱网注入如何在不拖慢默认启动的前提下工作？ | **按需扩展 (Opt-in)** | **已闭环 (按需动态挂载)** | [`RuntimeSandboxPersona`](../android/runtime/src/dev/tripoder/runtime/RuntimeSandboxPersona.java), [`RuntimeEnvMock`](../android/runtime/src/dev/tripoder/runtime/RuntimeEnvMock.java), [`RuntimeSelfHookManagers`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java) |
| **L13** | **高强商业壳与内核级对抗 (`svc #0`)** | 裸 `svc #0` 内联系统调用路径重写、`sourceDir` 物理路径校验及恶意行为阻断如何处理？ | **按需扩展 (Opt-in)** | **已打通按需 Seccomp** | [`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c), [`syscall_namespace.c`](../android/runtime/native/syscall_namespace.c), [`RuntimeMalwareProbeState`](../android/runtime/src/dev/tripoder/runtime/RuntimeMalwareProbeState.java) |

---

## 2. 真机商业应用实测基线（2026-10-28 Level 1 最小底座）

### 2.1 测试环境与基线参数
- **设备**：Xiaomi `d861a4fa`（HyperOS / Android 17 / API 37，ARM64）
- **运行时形态**：Level 1 最小底座（固定 ART 绑定约 220–264 项，`managerFixedBindings = 0`，运行期 `toggleBatchNative = 0`，主线程运行期发布 = 0，默认 `addedSeccompFilters = 0`，保留 ART JIT）。
- **已合入的关键共性修复**：
  1. **修复客体 Native 库 linker 搜索路径（`99b4770`）**：[`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java#L183) 构造 `RuntimeSharedLibraries` 时传入真实物理目录 `info.nativeLibraryDir` 而非虚拟 `applicationInfo.nativeLibraryDir`，彻底解决客体首次 `System.loadLibrary()` 时 `dlopen failed: library not found` 的系统性崩溃。
  2. **修复 4 项显式承载契约（`e38454c` & `aa0a075`）**：支持无 Action 含 `data`/`category` 的广播过滤器；动态 Receiver 改用 [`RuntimeReceiverProxy`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java)；裸 `ContextImpl` 外部目录与 `bindServiceCommon` 身份收口；[`RuntimeGuestPayload`](../android/runtime/src/dev/tripoder/runtime/RuntimeGuestPayload.java) 解决 `Parcel.LazyValue` 嵌套 `Bundle` 类加载器丢失；修复 `RuntimeWebView.notifyDexLoad` 的 `/data/data` 符号链接规范化与 `FixedFrameworkMembers` 反射回退。
  3. **规避招行 `libCmbShield.so` 的 `dataDir` 长度溢出**：确认旧 QA 宿主包名过长导致 `ApplicationInfo.dataDir` 达 ~117 字节时，触发 `libCmbShield.so` 内部 127 字节缓冲区的 `FORTIFY: vsprintf` 中止；控制 `dataDir` 长度 $\le 105\text{–}109$ 字节后完全消除。

### 2.2 六大代表性商业应用实测结果

| 应用名称 | 包名 / 版本 | 单次 30s 冷启动 | 5 次独立冷启动 | 前后台切换验证 | 当前已知剩余边界与跟进项 |
| --- | --- | :---: | :---: | :---: | --- |
| **招商银行** | `cmb.pb` / 1446 | **PASS** | **5/5 PASS** | **5/5 PASS** | 需在宿主与空间命名上保持 `dataDir <= 109` 字节上界，并补齐登录后只读业务页验证。 |
| **拼多多** | `com.xunmeng.pinduoduo` / 82600 | **PASS** | **5/5 PASS** | **5/5 PASS** | 启动与前后台切换全部稳定通过；待补 3 分钟商品列表/详情页交互压测。 |
| **腾讯视频** | `com.tencent.qqlive` / 32352 | **PASS** | **5/5 PASS** | **5/5 PASS** | 已修复私有广播非空 Sticky 返回导致的 `LoginDataStore` 异常及 `ToastPresenter` 哨兵 `UserHandle` 跨用户 Context 回退。 |
| **淘宝** | `com.taobao.taobao` / 862 | **PASS** | **5/5 PASS** | **5/5 PASS** (含最新 `22/22` 复测) | 已在 [`RuntimeWebView.notifyDexLoad`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebView.java) 放行客体私有数据区与执行产物目录下的动态/unlinked DEX，真机 22/22 检查全绿通过（0 次路径拒绝）。 |
| **芒果 TV** | `com.hunantv.imgo.activity` / 9403100 | **19/20 PASS** | — | — | 约 1/20 低频偶发 `libmsaoaidsec.so+0x20d10` `SIGSEGV`，待长稳压测归档。 |
| **网易云音乐** | `com.netease.cloudmusic` / 9006005 | **待专项适配** | — | — | 经 DEX/ELF 逆向确认，`com.netease.nis.wrapper.MyApplication` 的 `strAppName` 被编译期写死为自身类名，依赖 `libnesec.so` (`MyJni.load`/`ra`) 完成 Native 解包并覆写；当 `libnesec.so` 因沙箱环境检查提前返回时，`newApp` 被反射实例化为第二个 `MyApplication` 导致 `onCreate` 递归 `StackOverflowError`，归入扩展包 D（强对抗壳沙箱 Profile）专项推进。 |

---

## 3. 明确架构边界（什么能做，什么不承诺）

1. **默认底座可覆盖范围**：常规工具应用、主流商业社交/电商/内容/金融应用（含常规一代/二代壳、Dex2C、多线程 SDK、WebView 混合渲染、多进程 Service/Provider）。
2. **需开启按需策略（Opt-in Profile）的范围**：
   - 使用内联汇编裸 `svc #0` 直接发起系统调用校验 `/proc/self/*` 或数据路径的强对抗加固壳（需按应用启用 `syscall_namespace` `seccomp` 路径重写）；
   - 需要欺骗运营商/基站/GPS/VPN 状态或拦截恶意 `execve`/`kill` 的安全分析任务（需启用 `persona_control` / `malware_probe_control`）。
3. **免 Root 单 UID 物理硬边界（不承诺支持）**：
   - 依赖外部系统进程（如 `com.google.android.gms` Chimera 模块、硬件级 KeyMint Attestation、厂商系统级特权服务）直接通过内核 Binder `getCallingUid()` 强校验系统签名或独立 UID 的应用；
   - 强依赖宿主机不存在的 OEM 私有 framework 共享库（如特定 ROM 专属 `miuix` 系统级主题库）的预装系统插件。
