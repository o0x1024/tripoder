# Guest 应用兼容性分层与真机基线 (Guest Compatibility)

本文档从第一性原理出发，将任意未修改 APK 在 Tripoder 虚拟沙箱中正常运行所需的系统支撑拆解为 **13 个正交兼容层（L1–L13）**，并记录当前实现矩阵与真机多应用实测基线。

---

## 1. 第一性原理：Guest 应用运行所需的 13 层契约

一个未经重打包的商业 APK 在沙箱槽位进程（`:slot0..7`）中从冷启动到稳定交互，本质上是与 Android OS 进行一系列契约交换：

| 层级 | 契约名称 | 本质问题 | 当前状态 | 核心实现位置 |
| --- | --- | --- | --- | --- |
| **L1** | **APK 载荷与 ABI 装载** | 代码（多 Dex）与 Native `.so` 从哪里加载？ | **已闭环** | `InstallCoordinator`, `RuntimeProcessBootstrapper` |
| **L2** | **进程身份与 Application 生命周期** | `LoadedApk`、`Application`、包名、签名如何呈现为 Guest 自身？ | **已闭环** | `RuntimeProcessBootstrapper`, `RuntimePackageFacade` |
| **L3** | **文件系统与数据库隔离** | Java API 与 Native `libc` 读写 `/data/data/<pkg>` 如何落入独立空间？ | **已闭环** | `RuntimeStorageFacade`, `fixed_libc` (`path_policy`) |
| **L4** | **ContentProvider 初始化与路由** | 启动期 `installContentProviders` 及跨进程/同进程 Provider 如何解析？ | **已闭环** | `InstallProviderIndex`, `RuntimeProviderHelper` |
| **L5** | **Activity 启动、栈与窗口主题** | Launcher 启动、显式/隐式跳转、LaunchMode 与窗口转场如何保持一致？ | **已闭环** | `RuntimeActivityHelper`, `RuntimeSlot0..7Activity` |
| **L6** | **Service 与后台绑定** | `startService`、`bindService` 及前台服务通知如何受控运行？ | **已闭环** | `RuntimeServiceHelper`, `RuntimeSlot0..7Service` |
| **L7** | **Broadcast、PendingIntent 与 Job** | 静态/动态广播、延时意图与 `JobScheduler` 如何回调到正确的 Guest？ | **已闭环** | `RuntimeReceiverHelper`, `RuntimeJobHelper` |
| **L8** | **Binder Calling Package 校验** | 系统服务校验 `callingPackage == uidPackage` 时如何避免 `SecurityException`？ | **已闭环** | `RuntimeHookEngine` (System Service Facades) |
| **L9** | **WebView 多进程数据目录隔离** | Android 9+ 要求不同进程不能共享同一 WebView 目录，如何避免崩溃？ | **已闭环** | 启动期自动注入 `WebView.setDataDirectorySuffix(space_pkg_proc)` |
| **L10** | **资源、Asset 与 ClassLoader 桥接** | 动态加载插件、Split APK 或系统主题资源时如何防止 `Resources$NotFoundException`？ | **已闭环 (单 APK)** / **待扩展 (Split APK)** | `RuntimeProcessBootstrapper` |
| **L11** | **进程环境隐身 (`/proc` & 栈归属)** | 应用读取 `/proc/self/cmdline`、`maps` 或反射栈时如何隐藏宿主痕迹？ | **已闭环** | `fixed_libc` (`/proc` 过滤), `self-hook` Caller Coverage |
| **L12** | **硬件与系统能力边界** | 蓝牙、NFC、VPN、Camera、录音等受宿主权限约束的接口如何透传或虚拟化？ | **部分覆盖** | 宿主已声明常规权限；特殊特权需按需代理 |
| **L13** | **商用壳与高强对抗兼容** | 360/梆梆/腾讯乐固/爱加密等壳的自定义 Linker、系统调用直连（Raw `svc #0`）如何处理？ | **演进中** | 支持常规 Dex2C / 标准 `libc` 壳；裸 `svc #0` 需 Seccomp/SIGSYS 扩展 |

---

## 2. 真机多应用兼容性实测基线

### 测试环境

- **设备**：Xiaomi 23013RK75C (Android 15 / API 35, ARM64)
- **引擎包**：`dev.tripoder.engine.debug`
- **判定标准**：通过 `prepareApplicationLaunch` 免网关直达拉起，进程存活 $\ge 8\text{s}$，无 Java UncaughtException、无 Native Tombstone (`SIGSEGV`/`SIGABRT`)，成功完成 `Application.onCreate` 并进入首屏 Activity 渲染。

### 实测分类与结果

| 应用类别 | 代表样本 | 启动与首屏结果 | 关键验证点 |
| --- | --- | --- | --- |
| **契约验证 Fixture** | `dev.tripoder.sample.basic` 等内置样本 | **PASS (100%)** | 四大组件、跨进程 Provider、JobScheduler、SharedPreferences、Native 文件重定向 |
| **工具与开源应用** | 常规单 APK 工具、本地阅读器、文件/网络调试工具 | **PASS** | Room/SQLite 初始化、OkHttp 网络请求、WebView 渲染、动态广播 |
| **主流商业社交/内容应用 (单 APK)** | 含多线程 SDK 初始化（Bugly、MMKV、WorkManager、Firebase）的应用 | **PASS** | `InstallProviderIndex` 快速安装、WorkManager `JobScheduler` 路由、MMKV Native 路径重写 |
| **重度商业壳 / Split APK 应用** | 依赖 GMS 强绑定、Split APK (`split_config.*.apk`) 或裸 `svc #0` 反调试的应用 | **部分受限** | 见第 3 节已知边界与演进方向 |

---

## 3. 当前已知边界与演进方向

根据第一性原理分析，当前尚未完全覆盖的兼容性缺口集中在以下三个明确边界：

1. **Split APK / App Bundle 多分包安装 (`L1` / `L10`)**：
   - 当前安装流水线针对单 `base.apk` 设计；对于拆分为 `base.apk + split_config.arm64_v8a.apk + split_config.zh.apk` 的应用，需扩展 `InstallCoordinator` 支持多 APK 联合解析与 `ApplicationInfo.splitSourceDirs` 注入。
2. **裸系统调用 (`svc #0`) 绕过 `libc` (`L13`)**：
   - `fixed_libc` 拦截的是 `libc.so` 的 10 个导出函数。若加固壳在自有 `.so` 内直接内联汇编执行 `svc #0`（如 `__NR_openat = 56`、`__NR_readlinkat = 78`），需引入 `prctl(PR_SET_SECCOMP)` + `SIGSYS` 信号处理或内联扫描改写。
3. **Google Play Services (GMS) 深度依赖 (`L8`)**：
   - 强依赖 GMS `Chimera` 模块或跨应用 Google 账号签名校验的海外应用，在无内置虚拟 GMS 框架时回退至应用自身的无 GMS 分支。
