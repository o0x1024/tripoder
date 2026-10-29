# Guest 应用兼容性分层与真机基线 (Guest Compatibility)

- **更新日期**：2026-10-29
- **关联文档**：[当前总体架构](current-architecture.md) · [单运行时与最小底座收敛](single-runtime.md) · [架构梳理与实施计划](implementation-plan.md)

本文档从第一性原理出发，将未修改 APK 在 Tripoder 中运行所需的系统支撑拆解为 **13 个正交兼容层（L1–L13）**，明确区分**「第一层：统一最小底座（Level 1）默认闭环」**与**「第二层：按需扩展策略（Opt-in）」**，并汇总核心商业应用端到端业务闭环与 27 款主流第三方应用的真机实测大盘基线。

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

## 2. 真机商业应用实测基线（2026-10-29 Level 1 最小底座）

### 2.1 测试环境与基线参数
- **设备**：Xiaomi 17 Pro `d861a4fa`（HyperOS / Android 16–17，ARM64）
- **运行时形态**：Level 1 最小底座（固定 ART 绑定约 220–264 项，`managerFixedBindings = 0`，运行期 `toggleBatchNative = 0`，主线程运行期发布 = 0，默认 `addedSeccompFilters = 0`，保留 ART JIT）。
- **已合入的关键第一性原理共性修复**：
  1. **修复客体 Native 库 linker 搜索路径（`99b4770`）**：[`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java#L183) 构造 `RuntimeSharedLibraries` 时传入真实物理目录 `info.nativeLibraryDir` 而非虚拟 `applicationInfo.nativeLibraryDir`，彻底解决客体首次 `System.loadLibrary()` 时 `dlopen failed: library not found` 的系统性崩溃。
  2. **修复 4 项显式承载契约（`e38454c` & `aa0a075`）**：支持无 Action 含 `data`/`category` 的广播过滤器；动态 Receiver 改用 [`RuntimeReceiverProxy`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java)；裸 `ContextImpl` 外部目录与 `bindServiceCommon` 身份收口；[`RuntimeGuestPayload`](../android/runtime/src/dev/tripoder/runtime/RuntimeGuestPayload.java) 解决 `Parcel.LazyValue` 嵌套 `Bundle` 类加载器丢失；修复 `RuntimeWebView.notifyDexLoad` 的 `/data/data` 符号链接规范化与 `FixedFrameworkMembers` 反射回退。
  3. **规避招行 `libCmbShield.so` 的 `dataDir` 长度溢出**：确认旧 QA 宿主包名过长导致 `ApplicationInfo.dataDir` 达 ~117 字节时，触发 `libCmbShield.so` 内部 127 字节缓冲区的 `FORTIFY: vsprintf` 中止；控制 `dataDir` 长度 $\le 105\text{–}109$ 字节后完全消除。
  4. **根治客体插件目录动态库 Linker64 `dlopen` 失败（`GuestStorageContext` 物理路径与虚拟路径解耦）**：
     Android 动态链接器（`/apex/com.android.runtime/bin/linker64`）直接发起内核系统调用打开并映射动态库，不经过 libc hook 层虚拟路径重定向。当支付宝（UC WebView `mywebview_sdk` / 阿里安全 SDK）动态 `dlopen` 位于插件目录下的 SO（如 `libalipaywebview_*.so`）时，若 `Context.getDir("plugins_lib", 0)` 返回虚拟路径 `/data/user/0/...`，内核返回 `ENOENT` 导致加载失败。重构 [`GuestStorageContext`](../android/runtime/src/dev/tripoder/runtime/GuestStorageContext.java)：
     - `getDataDir()` 保持返回虚拟路径 `/data/user/0/<pkg>`（满足安全 SDK 包名与沙箱隔离校验）；
     - `getDir()`、`getFilesDir()`、`getCacheDir()`、`getCodeCacheDir()` 等返回 Linux 内核可直接寻址的物理绝对路径 `storage.dir(...)`，确保 Linker64 零故障加载。
  5. **底层通用 `/proc/self/maps` 虚拟化（攻克梆梆等企业级加固壳）**：
     新浪微博等应用的加固层（`libslib.so` / `libsoshell.so`）在 `JNI_OnLoad` 中通过 `openat("/proc/self/maps")` 扫描当前进程内存映射以反查主 APK 路径，发现沙箱私有目录后跳过脱壳解密导致 `UnsatisfiedLinkError`。在 [`path_policy.c`](../android/runtime/native/path_policy.c) 与 [`io_adapter.c`](../android/runtime/native/io_adapter.c) 中通过 `memfd_create` 构建虚拟内存文件描述符，利用 `tp_unresolve` 将沙箱内 Guest 载荷路径动态还原为标准 `/data/app/~~.../<pkg>/base.apk` 格式并剔除宿主组件（`libtripoderselfhook.so`），使加固壳 100% 正常脱壳。
  6. **补齐 `fixed_libc` 第 11 个入口 `__fchmodat`（攻克 Android 14+ ART W^X 动态 DEX 拒载）**：
     Android 14+（API 34+）ART 强制检查动态加载 DEX 的不可写性（`Attempt to load writable dex file`）。支付宝 Quinox 插件框架调用 `File.setReadOnly()` 最终经 Bionic `chmod` 跳转至 `__fchmodat`（`SYS_fchmodat`）；因原先仅挂钩 10 个入口遗漏 `__fchmodat`，导致虚拟路径穿透进内核返回 `ENOENT`，文件保持可写并被 ART 拦截。在 [`tripoder_fixed_libc.h`](../android/self-hook/include/tripoder_fixed_libc.h)、[`libc_fixed_contract.h`](../android/self-hook/native/libc_fixed_contract.h)、[`fixed_libc.c`](../android/self-hook/native/fixed_libc.c) 与 [`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c) 中将 `TP_FIXED_LIBC_ENTRIES` 扩展至 **11 项**，纳入 `__fchmodat` 双页原子补丁，彻底消除动态 DEX 权限异常。
  7. **`ActivityManager` Facade 属主代理与进程退出查询隔离**：
     客体调用 `ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 0)` 时若直通宿主系统服务，系统 AMS 因调用方 UID 与客体包名不匹配抛出 `SecurityException: requires android.permission.DUMP`。在 [`RuntimePlatformPolicy.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePlatformPolicy.java)、[`RuntimeSelfHookManagers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java) 与 [`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java) 中接管 `Family.ACTIVITY_MANAGER` Facade，由沙箱 Broker 代理查询，消除权限穿透崩溃。

### 2.2 核心商业应用深度验证与真实业务闭环矩阵

| 应用名称 | 包名 / 版本 | 单次 30s 冷启动 | 5 次独立冷启动 | 前后台切换验证 | 真实业务与登录闭环验证结果 |
| --- | --- | :---: | :---: | :---: | --- |
| **招商银行** | `cmb.pb` / 1446 | **PASS** | **5/5 PASS** | **5/5 PASS** | 保持 `dataDir <= 109` 字节上界，启动与前后台切换 100% 稳定。 |
| **拼多多** | `com.xunmeng.pinduoduo` / 82600 | **PASS** | **5/5 PASS** | **5/5 PASS** | 启动与前后台切换全部稳定通过。 |
| **腾讯视频** | `com.tencent.qqlive` / 32352 | **PASS** | **5/5 PASS** | **5/5 PASS** | 已修复私有广播非空 Sticky 返回导致的 `LoginDataStore` 异常及 `ToastPresenter` 哨兵 `UserHandle` 跨用户 Context 回退。 |
| **淘宝** | `com.taobao.taobao` / 862 | **PASS** | **5/5 PASS** | **5/5 PASS** (含 `22/22` 复测) | 放行客体私有数据区动态/unlinked DEX，真机 22/22 检查全绿通过（0 次路径拒绝）；**已完成中国移动网关一键免密秒级登录进入首页**。 |
| **支付宝** | `com.eg.android.AlipayGphone` / 10.6.80 | **PASS** | **PASS** | **PASS** | 补齐 `__fchmodat` 与解耦 `GuestStorageContext` 后，`facex` 人脸插件与 UC WebView 零报错加载；**已突破金融风控下发真实短信验证码，打通淘宝 SSO 联合授权与首页/收付款码渲染**。 |
| **新浪微博** | `com.sina.weibo` | **PASS** | **PASS** | **PASS** | `/proc/self/maps` 虚拟化攻克梆梆加固壳，10 项契约全过；**已完成真实手机号获取短信验证码、端到端登录及个人中心完整渲染**。 |
| **京东** | `com.jingdong.app.mall` | **PASS** | **PASS** | **PASS** | Workbench 独立导入启动，Aura 插件框架、首页商品流、购物车与搜索流畅运行。 |
| **美团** | `com.sankuai.meituan` | **PASS** | **PASS** | **PASS** | 多进程架构、Dynoparts 动态 DEX、QuickJS/MSC 容器、定位切城与本机号码一键登录弹窗完整跑通。 |
| **美团外卖** | `com.sankuai.meituan.takeoutnew` | **PASS** | **PASS** | **PASS** | 3D 闪屏、定位授权、附近商家列表信息流、登录抽屉及密码自动填充全部正常。 |
| **抖音** | `com.ss.android.ugc.aweme` / 32.0.0 | **PASS** | **5/5 PASS** | **5/5 PASS** (9/9 验收全绿) | 攻克 `APPLICATION_FACTORY_ORDER`，类加载器状态保护与属主对齐，真机 9/9 项全绿通过。证据：[`diag-aweme-01-target-stable.png`](evidence/diag-aweme-01-target-stable.png)。 |
| **小红书** | `com.xingin.xhs` / 9.49.1 | **PASS** | **5/5 PASS** | **5/5 PASS** (9/9 验收全绿) | 攻克 Post-Consent ByteHook GNU_RELRO 内存探测、Sentry 双层信号劫持及 MSA OAID Linker 符号扫描崩溃；首屏仅 144ms，30s 稳定存活。证据：[`diag-xhs-01-consent-stable.png`](evidence/diag-xhs-01-consent-stable.png)。 |
| **芒果 TV** | `com.hunantv.imgo.activity` / 9403100 | **PASS** | **5/5 PASS** | **5/5 PASS** (10/10 验收全绿) | 攻克 `libmsaoaidsec.so` 符号扫描崩溃，`com.mgtv.config.l$c` 标志位防御中立化；首屏延迟仅 43ms，30s 持续存活无崩溃。**已完成中国移动网关本机号码一键登录**。证据：[`diag-imgo-01-target-stable.png`](evidence/diag-imgo-01-target-stable.png)。 |
| **网易云音乐** | `com.netease.cloudmusic` / 9006005 | **PASS** | **5/5 PASS** | **5/5 PASS** (10/10 验收全绿) | 经 DEX/ELF 第一性原理逆向，攻克网易易盾壳默认 `strAppName` 指向自身引发无限递归与 8188KB `StackOverflowError` 崩溃；直接打通真实 `CloudMusicApplication` 初始化；首屏仅 40ms，30s 稳定存活。证据：[`diag-cloudmusic-01-target-stable.png`](evidence/diag-cloudmusic-01-target-stable.png)。 |
| **爱奇艺** | `com.qiyi.video` / 800170955 | **PASS** | **5/5 PASS** | **5/5 PASS** (20/20 验收全绿) | 攻克 Tinker 启动网络权限异常、`libmsaoaidsec.so` Linker 符号扫描崩溃与 `ContentProvider` 媒体流 `Router.openAssetFile` 路由缺失；首屏仅 33ms，30s 稳定存活。证据：[`diag-network-qiyi-stable.png`](evidence/diag-network-qiyi-stable.png)。 |
| **WPS Office** | `cn.wps.moffice_eng` | **PASS** | **PASS** | **PASS** (20/20 验收全绿) | 攻克 R8 混淆类下的 OkHttp Pinning 动态签名匹配；首屏秒开，20/20 验收全绿通过。证据：[`diag-network-wps-stable.png`](evidence/diag-network-wps-stable.png)。 |
| **闲鱼** | `com.taobao.idlefish` | **PASS** | **PASS** | **PASS** (18/18 验收全绿) | 18/18 验收项全绿通过，网络监控与证书锁定动态旁路生效。证据：[`diag-network-idlefish-stable.png`](evidence/diag-network-idlefish-stable.png)。 |
| **汽水音乐** | `com.luna.music` | **PASS** | **PASS** | **PASS** (19/19 验收全绿) | 19/19 验收项全绿通过，网络抓包与双向证书固定旁路生效。证据：[`diag-network-luna-stable.png`](evidence/diag-network-luna-stable.png)。 |

### 2.3 27 款主流第三方商业应用批量回归大盘（2026-10-29 刷新）

在合入 `/proc/self/maps` 虚拟化、`__fchmodat` 11 项契约、`ActivityManager` Facade、抖音 `APPLICATION_FACTORY_ORDER` 保护、小红书 ByteHook/Sentry/OAID 专项攻坚、芒果 TV `libmsaoaidsec.so` 符号扫描崩溃根除，以及网易云音乐易盾壳真实 Application 绑定后，大盘关键突破如下：
- **一枪通过（24/27，88.9%）**：WPS Office (`cn.wps.moffice_eng`)、唯品会 (`com.achievo.vipshop`)、阿里云盘 (`com.alicloud.databox`)、通义千问 (`com.aliyun.tongyi`)、高德地图 (`com.autonavi.minimap`)、百度地图 (`com.baidu.BaiduMap`)、百度网盘 (`com.baidu.netdisk`)、DeepSeek (`com.deepseek.chat`)、大众点评 (`com.dianping.v1`)、支付宝 (`com.eg.android.AlipayGphone`)、Boss直聘 (`com.hpbr.bosszhipin`)、汽水音乐 (`com.luna.music`)、原神 (`com.miHoYo.Yuanshen`)、米游社 (`com.mihoyo.hyperion`)、平安口袋银行 (`com.pingan.paces.ccms`)、夸克浏览器 (`com.quark.browser`)、滴滴出行 (`com.sdu.didi.psnger`)、闲鱼 (`com.taobao.idlefish`)、QQ (`com.tencent.mobileqq`)、**抖音 (`com.ss.android.ugc.aweme`)**、**小红书 (`com.xingin.xhs`)**、快手 (`com.smile.gifmaker`)、**芒果 TV (`com.hunantv.imgo.activity`)**、**网易云音乐 (`com.netease.cloudmusic`)**。
- **实际业务可用率达 96.3%（26/27）**：新浪微博 (`com.sina.weibo`) 单测与真实短信登录 100% 闭环；淘宝 (`com.taobao.taobao`) 免密登录与 22/22 深度检查全绿。
- **专项突破详解**：
  1. **抖音 (`com.ss.android.ugc.aweme`) `APPLICATION_FACTORY_ORDER` 攻克**：
     - **第一性原理剖析**：字节自定义 `AppComponentFactory`（ByteDance APM / 插件框架）在 evaluation 阶段通过 `createAppFactory(ApplicationInfo, ClassLoader)` 动态注入与包装类加载器，导致 `LoadedApk.mClassLoader` 与 `mDefaultClassLoader` 在工厂评估期间被临时重置或置换，触发沙箱严格校验抛出 `APPLICATION_FACTORY_PREPARED_INPUTS_REQUIRED`。
     - **架构解法**：在 [`ApplicationAdapter.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java) 的 `createFactory` 中增加上下文保护与类加载器状态恢复：若 evaluation 期间 `mClassLoader` 或 `mDefaultClassLoader` 被外部代码清理为 `null`，立即还原初始加载器，保证工厂属主契约与 LoadedApk 状态一致。
     - **真机实测**：Xiaomi 17 Pro（`d861a4fa`，Android 17 preview，kernel 6.18.21）9/9 项检查全绿通过，包括应用初始化、首个 Activity 确认（`firstActivityAckMs = 126ms`）、30 秒持续存活无重启、原 APK 完整性校验。证据归档：[`diag-aweme-01-target-stable.png`](evidence/diag-aweme-01-target-stable.png)。
  2. **小红书 (`com.xingin.xhs`) Post-Consent ByteHook、Sentry 与 MSA OAID 冲突攻克**：
     - **第一性原理深度剖析**：
       - *冲突 1（ByteHook 与 GNU_RELRO 内存探测冲突）*：小红书内置字节跳动 `libbytehook.so` 与 `libshadowhook.so`（用于 `openat`/`mkdir` 的 GOT 挂钩监控），在 ARM64 架构下通过试探性内存写入指令（`mov x1, sp; mov w0, w25; stlr x23, [x20]`）探测目标内存是否为只读 GNU_RELRO。
       - *冲突 2（Sentry 双层崩溃捕获劫持信号链）*：Layer 1 (`SentryNCrashProxy` via ByteHook) 与 Layer 2 (`io.sentry.core.NativeHandler` via `libsentry.so`) 原生崩溃引擎注册了全局 `sigaction` 崩溃处理程序，抢占沙箱与 ART 的 `libsigchain.so`，导致内存探测产生的可恢复异常无法通过 `siglongjmp` 正常返回，而是直接唤起 `libsentry_dumper.so` 产生 dump 并杀进程（退出码 113）。
       - *冲突 3（MSA OAID `libmsaoaidsec.so` Linker 符号扫描崩溃）*：MSA OAID SDK 在 `.init_array` ELF 构造函数阶段执行强反逆向检测，通过扫描 `/proc/self/maps` 并解析 Android 动态链接器（`/apex/com.android.runtime/bin/linker64`）的 ELF 符号表以读取 `solist` 变量。在 Android 17 / 内核 6.18（Xiaomi HyperOS 2）上，因 Linker 的 GNU_RELRO 保护或地址空间布局调整，符号表中的偏移导致 `ldr x0, [x19, x8]` 访问受保护内存页，产生 `SEGV_ACCERR`（code 2）崩溃。
     - **架构解法（多层防御，零 Fallback）**：
       - *引擎层中和 ByteHook & ShadowHook*：在 [`ApplicationAdapter.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java) `configureGuestCompatibility` 中，于客体代码执行前通过反射将 `ByteHook.a = true; ByteHook.b = 0;`（`ERRNO_OK`）以及 `ShadowHook.a = true; ShadowHook.b = 0;` 置位。引擎层直接标记初始化成功并返回 0，无需加载 native 探测库，零冲突开销。
       - *中和 Sentry 核心与原生崩溃报告*：置位 `t3e.e.b = true`（Sentry 业务初始化的总开关，使 `t3e.e.a` 立即 return）；置位 `io.sentry.core.k0.a = true`（阻止 Java 与 NativeCrashHandler 注册）；标记 `NativeHandler.instance.initNativeLibOk = true`，并将 `SentryCoreConfig` 中的 `enable`、`enableNativeHook`、`enableCrashRecord`、`enableXdlHook` 全部置为 `false`；置位 `SentryRecordImpl.a = true; b = true;`。
       - *PatchProxy 动态路由拦截*：利用小红书自身的 Robust 热修复 `PatchProxy` 机制，注册 `ChangeQuickRedirect` 拦截器：
         - `XhsDiskManager`：拦截黑名单校验 `isDeviceInBlackList`（"290459" -> true）与 `initByteHook`（"290461" -> void）；
         - `FileHook`：拦截 `hookOpen`（"293037"）与 `unhookOpen`（"293039"）返回 void；
         - `MsaAllianceManager`：拦截 `initMdidSdk`（"270810"）与 `initAdMdidSdk`（"270813"），自动提取入参中的 Kotlin 回调 `Function0.invoke()` 触发完成，彻底避免加载导致 `SEGV_ACCERR` 的 `libmsaoaidsec.so`；
         - `SentryNCrashProxy`、`SentryRecordImpl`、`t3e.e`、`bc3.d`（CapaNativeHook）、`LogHook`、`LogHookApi30`、`MaliOptimizer`：统一重定向为空操作（`voidNoOpRedirect`）。
     - **真机实测**：
       Xiaomi 17 Pro（`d861a4fa`，Android 17 preview，kernel 6.18.21）执行 `python3 android/scripts/test_xhs_target.py`，**9/9 项检查全绿通过**（首屏确认延迟仅 144ms，30 秒持续存活无崩溃重启，30 个稳定性采样点连续健康）。证据归档：[`diag-xhs-01-consent-stable.png`](evidence/diag-xhs-01-consent-stable.png) 与 [`diag-xhs-01/summary.json`](evidence/diag-xhs-01/summary.json)。
  3. **芒果 TV (`com.hunantv.imgo.activity`) MSA OAID 低频崩溃根除**：
     - **第一性原理剖析**：芒果 TV 内置 MSA SDK（`com.bun.miitmdid` 与包装类 `com.mgtv.config.l`）。在 Android 17 / HyperOS 2 / Kernel 6.18 环境下，`libmsaoaidsec.so` 于子线程中解析动态链接器符号表（`solist`）时，因地址段保护变动低频触发 `pc=libmsaoaidsec.so+0x20d10` `SEGV_ACCERR`（signal 11, code 1/2）。
     - **架构解法**：在 [`ApplicationAdapter.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java) `configureGuestCompatibility` 中，于客体业务代码运行前反射获取 `com.mgtv.config.l$c.a` 单例，预置 `b = true`（已初始化）、`c = "00000000-0000-0000-0000-000000000000"`（合规 OAID 字符串）及 `d = true`（证书已装载）。芒果 TV 内部 `MiitHelper.k(Context)` 与 `g(...)` 直接检测到已就绪标志，跳过 `InitCert` 与 native 库加载，彻底根除崩溃隐患。
     - **真机实测**：Xiaomi 17 Pro（`d861a4fa`）执行 `python3 android/scripts/test_imgo_target.py`，**10/10 项检查全绿通过**（首屏延迟 43ms，30 秒 30 个采样点连续健康存活）。证据归档：[`diag-imgo-01-target-stable.png`](evidence/diag-imgo-01-target-stable.png)。
  4. **网易云音乐 (`com.netease.cloudmusic`) 易盾壳无限递归与 StackOverflow 攻克**：
     - **第一性原理逆向剖析**：网易云音乐外壳采用网易易盾加固代理 `com.netease.nis.wrapper.MyApplication`。逆向分析发现其静态字段 `strAppName` 默认初始化为自身类名（Base64 XOR 字符串解密后为 `"com.netease.nis.wrapper.MyApplication"`）。当沙箱或非预期环境导致 `libnesec.so` Native 动态解包未覆写 `strAppName` 时，`MyApplication.a(Context)` 通过反射使用 `strAppName` 实例化真实应用，产生了一个新的 `MyApplication` 实例作为 `newApp`。在 `onCreate()` 中，`MyApplication` 调用 `newApp.onCreate()`，进而形成无限递归调用链，耗尽 8188KB 栈空间抛出 `java.lang.StackOverflowError`。通过反编译云音乐完整 20 个 DEX 发现，其内部所有核心业务代码（`classes2.dex` ~ `classes20.dex`）均为标准未加密 DEX，真实应用入口为公共类 `com.netease.cloudmusic.CloudMusicApplication`（继承自 `PatchApplication` / `TinkerApplication`）。
     - **架构解法**：在 [`ApplicationAdapter.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java) `configureGuestCompatibility` 中，通过反射将 `com.netease.nis.wrapper.MyApplication.strAppName` 准确置为真实主应用类名 `"com.netease.cloudmusic.CloudMusicApplication"`。外壳代理在 `attachBaseContext` 与 `onCreate` 中直接加载并代理真实的网易云音乐主程序，彻底消除自身递归死锁。
     - **真机实测**：Xiaomi 17 Pro（`d861a4fa`）执行 `python3 android/scripts/test_cloudmusic_target.py`，**10/10 项检查全绿通过**（首屏确认延迟仅 40ms，总启动耗时 6606ms，30 秒持续存活无崩溃）。证据归档：[`diag-cloudmusic-01-target-stable.png`](evidence/diag-cloudmusic-01-target-stable.png)。
   5. **爱奇艺 (`com.qiyi.video`) Tinker、MSA OAID 崩溃与 ContentProvider 媒体流路由攻克**：
      - **第一性原理逆向剖析**：
        - *崩溃 1（Tinker 网络类型检查权限缺失）*：Tinker 在 `attachBaseContext` 阶段调用 `TelephonyManager.getDataNetworkTypeForSubscriber`。在 Android 17 / HyperOS 2 上，该方法强制校验 `READ_PHONE_STATE` 权限并抛出 `SecurityException`，导致 Tinker 初始化中断。在沙箱端接管 TelephonyManager 返回 `NETWORK_TYPE_UNKNOWN` 并授予权限彻底根除。
        - *崩溃 2（MSA OAID Linker 符号扫描段保护崩溃）*：爱奇艺集成的 MSA SDK（`classes7.dex` 中的 `yn3.o`）在 `.init_array` 加载 `libmsaoaidsec.so` 并调用 `InitSdk`，通过扫描 `/proc/self/maps` 与 `linker64` 符号表发生 `SEGV_ACCERR`。逆向分析其状态机字段 `yn3.o.f`、`d`、`c` 与 `e`，在 [`ApplicationAdapter.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java) 中预先将其置位为就绪状态并置入有效空 `OaidInfo`，中和 ByteHook/ShadowHook，完全跳过有缺陷的 native 库。
        - *崩溃 3（ContentProvider 媒体流 `Router.openAssetFile` 缺失）*：开屏广告视频引擎（`ImageMaxAdVideoView`）通过 `MediaPlayer.attemptDataSource` 经由 `ContentResolver.openAssetFileDescriptor(uri, "r")` 读取缓存视频，沙箱 `RuntimeProviders.Router` 未覆写 `openFile`、`openAssetFile` 与 `openTypedAssetFile`，由 `RuntimeProviderBase` 抛出 `UnsupportedOperationException: PROVIDER_OPERATION_NOT_IMPLEMENTED` 导致主线程崩溃。
      - **架构解法**：在 [`RuntimeProviders.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviders.java) 的 `Router` 中，完整打通 `openFile`、`openAssetFile` 与 `openTypedAssetFile`（含 `CancellationSignal` 重载）：本地 ContentProvider（如 `com.mcto.sspsdk.QyFileProvider`、`FileProvider`）在 `RuntimeProviderIdentity.enter` 作用域内直接执行；跨进程 Provider 经 `RuntimeProviderFacade` 代理，外部 Provider 经宿主 ContentResolver 透传，全面消除流媒体/广告/文件读取断路。
      - **真机实测**：Xiaomi 17 Pro（`d861a4fa`）执行 `python3 android/scripts/test_network_target.py --app qiyi`，**20/20 项检查全绿通过**（首屏确认延迟仅 33ms，总启动 10080ms，30 秒持续稳定运行无崩溃，OkHttp 证书绑定旁路与 `libmctocurl.so` 旁路全部生效）。证据归档：[`diag-network-qiyi-stable.png`](evidence/diag-network-qiyi-stable.png)。
- **剩余待攻克边界（扩展包 D）**：
  - **微信 (`com.tencent.mm`) / 王者荣耀 (`com.tencent.tmgp.sgame`) / 携程 (`ctrip.android.view`)**：涉及多进程强反虚拟化或私有媒体/游戏安全加固对抗，归入扩展包 D 推进。

---

### 2.4 端到端商业应用网络抓包与 SSL Pinning 旁路实体验收矩阵（7 款全量通过）

基于 Phase 2 抓包会话解耦与 Phase 3 动态反射挂钩/Native 缓冲注入能力，我们在物理机（Xiaomi 17 Pro `d861a4fa`，HyperOS 2 / Android 17 / API 37，Kernel 6.18.21）对 7 款典型主流商业应用实施了端到端全量实体验收：

| 商业应用 | 核心网络组件 / 动态库 | 专项技术攻坚点 | 检查项通过数 | 稳定性与旁路状态 | 证据截图 / 产物 |
| --- | --- | --- | :---: | :---: | --- |
| **闲鱼** (`com.taobao.idlefish`) | Java OkHttp / Agoo Push | 证书锁定动态旁路与网络数据包流向监控 | **18/18 PASS ✅** | 首屏 87ms，30s 稳定存活，`certPinnerCheck` 成功旁路 | [`diag-network-idlefish-stable.png`](evidence/diag-network-idlefish-stable.png) |
| **汽水音乐** (`com.luna.music`) | Java OkHttp + TTNet | 双向证书固定拦截与多进程网络会话隔离 | **19/19 PASS ✅** | 首屏 112ms，30s 稳定存活，证书校验成功旁路 | [`diag-network-luna-stable.png`](evidence/diag-network-luna-stable.png) |
| **芒果 TV** (`com.hunantv.imgo.activity`) | Java OkHttp / 自研网络库 | `libmsaoaidsec.so` 崩溃中和与证书检验旁路 | **13/13 PASS ✅** | 首屏 45ms，30s 稳定存活，OkHttp Pinner 成功旁路 | [`diag-network-imgo-stable.png`](evidence/diag-network-imgo-stable.png) |
| **WPS Office** (`cn.wps.moffice_eng`) | Java OkHttp (R8 混淆) | 攻克 R8 混淆类名与混淆方法（`b`/`d`）动态反射特征匹配 | **20/20 PASS ✅** | 首屏 154ms，30s 稳定存活，混淆 OkHttp Pinner 成功旁路 | [`diag-network-wps-stable.png`](evidence/diag-network-wps-stable.png) |
| **小红书** (`com.xingin.xhs`) | Java OkHttp + `libnet_engine.so` | ByteHook/ShadowHook/Sentry 信号劫持中和与网络观察器注入 | **20/20 PASS ✅** | 首屏 144ms，30s 稳定存活，OkHttp Pinner 成功旁路 | [`diag-network-xhs-stable.png`](evidence/diag-network-xhs-stable.png) |
| **抖音** (`com.ss.android.ugc.aweme`) | Java OkHttp + `libsscronet.so` | 攻克 200MB 超大 DEX 检索超时，优化为直接 DEX 检查与 25s IPC 超时 | **14/14 PASS ✅** | 首屏 126ms，30s 稳定存活，Cronet/OkHttp Pinner 成功旁路 | [`diag-network-aweme-stable.png`](evidence/diag-network-aweme-stable.png) |
| **爱奇艺** (`com.qiyi.video`) | Java OkHttp + `libmctocurl.so` | 攻克 Tinker 权限、`libmsaoaidsec.so` 崩溃与 `Router.openAssetFile` 数据源路由 | **20/20 PASS ✅** | 首屏 33ms，30s 稳定存活，`libmctocurl.so` / OkHttp Pinner 成功旁路 | [`diag-network-qiyi-stable.png`](evidence/diag-network-qiyi-stable.png) |

**实测结论**：
1. **未修改原版 APK 保真度 100%**：7 款商业应用均基于官方原始 APK 签名文件运行，零 Smali 重打包，零 Dex 修改。
2. **启动性能卓越**：首个 Activity 确认延迟均在 33ms ~ 154ms 之间，远优于传统重量级沙箱。
3. **SSL Pinning 旁路与网络抓包完全生效**：所有应用的 OkHttp 证书固定与 Native 网络库成功挂钩并生效旁路，30s 运行窗口无一崩溃。

---

## 3. 明确架构边界（什么能做，什么不承诺）

1. **默认底座可覆盖范围**：常规工具应用、主流商业社交/电商/内容/金融应用（含常规一代/二代壳、Dex2C、多线程 SDK、WebView 混合渲染、多进程 Service/Provider）。
2. **需开启按需策略（Opt-in Profile）的范围**：
   - 使用内联汇编裸 `svc #0` 直接发起系统调用校验 `/proc/self/*` 或数据路径的强对抗加固壳（需按应用启用 `syscall_namespace` `seccomp` 路径重写）；
   - 需要欺骗运营商/基站/GPS/VPN 状态或拦截恶意 `execve`/`kill` 的安全分析任务（需启用 `persona_control` / `malware_probe_control`）。
3. **免 Root 单 UID 物理硬边界（不承诺支持）**：
   - 依赖外部系统进程（如 `com.google.android.gms` Chimera 模块、硬件级 KeyMint Attestation、厂商系统级特权服务）直接通过内核 Binder `getCallingUid()` 强校验系统签名或独立 UID 的应用；
   - 强依赖宿主机不存在的 OEM 私有 framework 共享库（如特定 ROM 专属 `miuix` 系统级主题库）的预装系统插件。
