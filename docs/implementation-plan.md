# Tripoder 架构梳理与分阶段实施计划

- **更新日期**：2026-10-28
- **核心架构范式**：**统一最小承载底座（Level 1 Skeleton Baseline） + 按需/分级策略扩展（Opt-in Capabilities & Profiles）**
- **关联规范**：[当前总体架构](current-architecture.md) · [单运行时收敛](single-runtime.md) · [Guest 兼容性基线](guest-compatibility.md) · [全场景缺口分析](scenario-gap-analysis.md)

---

## 1. 架构总纲：为什么采用「统一最小底座 + 按需策略扩展」

从第一性原理与真机实测出发，任何免 Root、免重打包、单宿主 UID 的 Android 虚拟化沙箱都面临三大客观规律：

1. **物理硬边界不可逾越**：当校验发生在**沙箱进程外部**（如系统 `system_server`、硬件 KeyMint/TEE 密钥认证、`com.google.android.gms` 跨进程校验调用方真实 Linux `UID` 与系统签名）或强耦合 OEM 私有系统库（如 MIUI `miuix`）时，进程内虚拟化无法欺骗内核态 `getCallingUid()`。
2. **重度加固对抗与高性能/高稳定存在天然互斥**：
   - 若默认只拦截 `libc.so` 导出函数，使用内联汇编裸 `svc #0` 读取 `/proc/self/maps` 或校验物理安装路径 `/data/app/~~.../base.apk` 的强对抗壳会发现宿主痕迹；
   - 若对所有应用默认开启全进程 `seccomp/SIGSYS`、信号链抢占、全线程 Park 及 1,800+ 个系统 Manager 固定 ART Hook，则会导致正常商业应用在多线程并发启动期发生线程 Park 失败（`userDeliveryStatus=5`）、UI 主线程 `toggleBatchNative` 卡顿/ANR，或干扰客体自有的 `SIGSEGV` 保护页处理器。
3. **绝大多数商业应用崩溃源于容器底座缺陷或过度 Hook，而非需要多套分裂的运行时**：
   - 2026-10-28 的复测证明：修掉 [`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java) 中将虚拟 `applicationInfo.nativeLibraryDir` 误传给 linker 搜索路径的 1 行共性 Bug，并将启动固定绑定从 1,818 项裁剪至 220–264 项最小骨架后，招商银行、淘宝、腾讯视频、拼多多在同一套最小运行时下稳定通过 5 次独立冷启动与 5 轮前后台切换，芒果 TV 通过率达 19/20。

因此，Tripoder 摒弃早期的 `THIN`/`THICK` 双模式分叉，确立**双层解耦架构**：

```mermaid
flowchart TD
    subgraph Base["第一层：统一最小承载底座 (Level 1 最小骨架 — 默认开启 / 零冗余侵入)"]
        B1["L1-L2: 真实 APK/Split 装载 + 物理 linker 搜索路径 + Application/LoadedApk 身份"]
        B2["L3 & L11: fixed_libc 10 个核心路径入口重定向 + /proc/self/* 基础视图 (0 Seccomp)"]
        B3["L4-L9: 四大组件显式 Lease 路由 + RuntimeReceiverProxy + RuntimeGuestPayload"]
        B4["ART 策略: 仅 ~220-264 项最小容器骨架固定绑定，启动期一次发布，100% 保留 JIT"]
    end

    subgraph OptIn["第二层：按需/分级策略扩展层 (Opt-in Extensions — 显式请求或按应用 Profile 装配)"]
        E1["扩展包 A: 动态代码探针与业务 Hook\n(startupHook / ManagedHook / NativeHook / DEX 快照)"]
        E2["扩展包 B: 被动网络明文观测\n(SSL_read/write 有界捕获 / HTTP1 实时分帧 / HAR)"]
        E3["扩展包 C: 环境模拟与故障注入\n(persona_control / env_mock / time_warp / honeydata)"]
        E4["扩展包 D: 强对抗壳与恶意软件内核沙箱\n(Seccomp svc #0 重写 / sourceDir 伪装 / execve & Sinkhole 阻断)"]
    end

    Base -->|"默认零开销运行商业应用"| App["Guest 商业应用稳定运行"]
    Base -.->|"MCP / Profile 显式启用"| OptIn
```

---

## 2. 当前项目全量梳理（Codebase & Architecture Audit）

### 2.1 第一层：统一最小承载底座（Level 1 Baseline）现状盘点

| 模块维度 | 核心代码位置 | 当前状态 | 梳理结论与遗留问题 |
| --- | --- | --- | --- |
| **APK 与 Native 载荷装配** | [`ArtifactSet.java`](../android/runtime/src/dev/tripoder/runtime/ArtifactSet.java)<br>[`RuntimeExecutionCode.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeExecutionCode.java)<br>[`RuntimeSharedLibraries.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSharedLibraries.java)<br>[`RuntimeDexLoader.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeDexLoader.java) | **已闭环** | 已修复 `info.nativeLibraryDir` 物理搜索路径（`99b4770`）；使用标准 `PathClassLoader` 并保留 `oat/odex/vdex` 编译缓存。 |
| **Application 与壳委托生命周期** | [`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java)<br>[`RuntimeSelfHookApplication.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookApplication.java)<br>[`ApplicationBinding.java`](../android/self-hook/java/dev/tripoder/selfhook/ApplicationBinding.java) | **已闭环（Phase 0 完成）** | 已完成 `RuntimeEngine.updateActivityThreadApplication` 保留壳 `attachBaseContext` 发布的真实委托 `mInitialApplication`（解决网易易盾等壳无限递归 `StackOverflowError`），并已清理临时诊断日志 `diagnoseManagerIdentity`。 |
| **四大组件与 Context/Parcel 边界** | [`GuestContext.java`](../android/runtime/src/dev/tripoder/runtime/GuestContext.java)<br>[`RuntimeReceivers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceivers.java)<br>[`RuntimeReceiverProxy.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java)<br>[`RuntimeGuestPayload.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeGuestPayload.java)<br>[`RuntimeWebView.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebView.java)<br>[`RuntimePackageContexts.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePackageContexts.java) | **已闭环（Phase 0 & 1 完成）** | 已完成裸 `ContextImpl` 外部目录与 `bindServiceCommon` 收口、动态广播代理（0 次运行期 `toggleBatchNative`）、嵌套 `Bundle` `LazyValue` 类加载器绑定、意外私有粘性广播降级告警（`UNEXPECTED_STICKY_PRIVATE_BROADCAST`）、`notifyDexLoad` 客体数据目录/unlinked 临时 DEX 放行（淘宝兼容）以及 `createContextAsUser` 哨兵 `UserHandle`（`userId < 0`）本包回退（腾讯视频 `ToastPresenter` 兼容）。 |
| **ART 固定绑定与 Manager 裁剪** | [`RuntimeSelfHookProduct.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookProduct.java)<br>[`RuntimeFixedPreparation.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeFixedPreparation.java)<br>[`RuntimeSelfHookManagers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java) | **已闭环（Phase 2 完成）** | 默认启动固定绑定保持 220–264 项最小骨架（`installedManagerBindings = 0`）；同时支持通过 `RuntimeSelfHookProduct.installManagerFamily` 按需动态挂载单个 Manager 家族。 |
| **Native 路径重定向与信号链** | [`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c)<br>[`fixed_libc.c`](../android/self-hook/native/fixed_libc.c)<br>[`path_policy.c`](../android/runtime/native/path_policy.c) | **已收敛** | 默认仅安装 `tp_fixed_libc` 的 10 个文件入口，`addedSeccompFilters=0`，不拦截 debuggerd；对招行 `cmb.pb` 通过 `diagnose-six-app-startup.py` 的 `DATA_DIR_MAX_BYTES = 109` 守卫（如 `dev.tripoder.q6` 宿主下为 103 字节）规避 `libCmbShield.so` 127 字节栈缓冲区 `FORTIFY` 崩溃。 |

### 2.2 第二层：按需/分级策略扩展层（Opt-in Extensions）现状盘点

| 扩展包 | 核心代码位置 | 当前状态 | 梳理结论与进展 |
| --- | --- | --- | --- |
| **A. 动态代码探针与业务 Hook** | [`RuntimeCodeProbes.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeCodeProbes.java)<br>[`SelfHookControl.java`](../android/runtime/src/dev/tripoder/runtime/script/SelfHookControl.java)<br>[`ManagedHook.java`](../android/self-hook/java/dev/tripoder/selfhook/ManagedHook.java) | **可用但受限** | 支持 `startupHook` 与运行期 Java/Native Hook、对象字段内省（`readField`/`snapshotContainer`）及 `<init>` Hook；单进程多轨道会话解耦与 Selector 扩容纳入后续迭代。 |
| **B. 被动网络明文观测** | [`passive-network-sessions.md`](passive-network-sessions.md)<br>[`native_buffer.c`](../android/self-hook/native/native_buffer.c) | **已闭环（OpenSSL）** | 支持 `SSL_read/SSL_write` 有界缓冲区捕获、HTTP/1.1 实时分帧与 HAR 1.2 导出。 |
| **C. 环境伪装与故障注入** | [`RuntimeSandboxPersona.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSandboxPersona.java)<br>[`RuntimeEnvMock.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEnvMock.java)<br>[`RuntimePlatformPolicy.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePlatformPolicy.java)<br>[`RuntimeSelfHookManagers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java) | **已打通（Phase 2 完成）** | 默认启动 `installedManagerBindings = 0`（100% 平台原生透传）；当通过 `persona_control` 或 `env_mock` 显式启用 VPN 隐藏、网络/运营商/定位/电量伪装时，自动调用 `RuntimeSelfHookManagers.ensureInstalled(Family)` 按需挂载对应家族的 ART 绑定与 Facade。 |
| **D. 强对抗壳与恶意软件内核沙箱** | [`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c)<br>[`syscall_namespace.c`](../android/runtime/native/syscall_namespace.c)<br>[`RuntimeMalwareProbeState.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeMalwareProbeState.java) | **已打通（Phase 2 完成）** | 默认启动 `addedSeccompFilters = 0`；当通过 `malware_probe_control` 启用 `blockExecve`/`blockKill`/`networkSinkhole` 时，`setSyscallPolicyNative` 动态安装 `tp_syscall_namespace` `seccomp` 过滤器并同步导出内核拦截计数。 |

---

## 3. 分阶段实施计划（Implementation Plan）

### Phase 0：工作区收口、代码树清理与短路径契约固化（已完成 ✅）

**目标**：将当前工作区已验证的修复干净合入，清除历史残留文件与诊断代码，固化宿主 `dataDir` 长度守卫。

1. **合入工作区核心兼容性修复并剥离临时诊断**：
   - **保留并增强壳委托 `Application` 同步**：[`RuntimeEngine.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java) 中 `updateActivityThreadApplication` 仅在 `mInitialApplication` 为空或为物理宿主时才写入；[`art_reflection_view.c`](../android/self-hook/native/art_reflection_view.c) 与 [`application_adapter.c`](../android/self-hook/native/application_adapter.c) 支持识别共享同一 `mBase` Context 根（`base_root`）的壳委托 `Application`，并在 `attach` 后自动同步 `LoadedApk.mApplication` 与 `GuestContext.attachedApplication`。
   - **保留私有广播非空 Sticky 容错**：[`RuntimeReceivers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceivers.java) 中对私有派发 Action 偶发返回非空 Sticky Intent 改为告警 `UNEXPECTED_STICKY_PRIVATE_BROADCAST` 忽略而非抛异常中止客体静态初始化。
   - **清理临时调试与残留文件**：移除 [`RuntimeSelfHookApplication.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookApplication.java) 中临时添加的 `diagnoseManagerIdentity` 调试日志，以及工作区历史 `misrc/`、`task.md` 及 19 份碎片化过程文档。
2. **阶段验证结果（已通过 ✅）**：
   - 404 个 Java 源文件 SDK 编译通过；主机 15/15 回归测试套件、`ApplicationProjectsSchemaTest.py`、`CatalogMetadataHostTest.py`、`run-catalog-tests.py`、`run-mcp-tests.py`、`run-recovery-tests.py` 及 `npm test` 全绿。

---

### Phase 1：Level 1 统一最小底座冻结与商业应用边界补齐（核心代码已完成 ✅，长稳复测推进中）

**目标**：在不开启任何分析工具与 `seccomp` 的纯 Level 1 最小底座上，补齐代表性重度商业应用的显式承载边界，并通过真机集成验证。

1. **已完成的具体承载边界修复**：
   - **淘宝 (`com.taobao.taobao`) — 已闭环并真机 22/22 验收通过 ✅**：针对阿里无线保镖 (`com.alibaba.wireless.security.framework`) 动态创建 `PathClassLoader` 加载沙箱数据区内插件及 unlinked 临时 DEX 触发 [`RuntimeWebView.notifyDexLoad`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebView.java) 拒绝的问题，将 `engine.applicationInfo.sourceDir`、客体数据根（`engine.data`、`engine.dataCanonicalDirectory`、`/data/user/0/<pkg>/`、`/data/data/<pkg>/`）及执行产物目录纳入白名单，并对客体私有数据区内的动态 DEX 免除 `file.isFile()` 强校验；真机 `diagnose-six-app-startup.py --package com.taobao.taobao --guest-only --bg-fg-cycles 2` **22/22 项检查全绿通过**（0 次 `DEX_LOAD_REPORT_PATH_DENIED`，`runtimeMainThreadToggleCalls = 0`）。
   - **腾讯视频 (`com.tencent.qqlive`) — 已完成代码修复 ✅**：在 [`RuntimeSelfHookPackages.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookPackages.java) 与 [`RuntimePackageContexts.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePackageContexts.java) 中支持哨兵 `UserHandle`（`userId < 0`，如 `UserHandle.CURRENT = -2`）对本包 `createPackageContextAsUser` / `createContextAsUser` 的身份回退，消除 `ToastPresenter.trySendAccessibilityEvent` 跨用户权限崩溃。
   - **网易云音乐 (`com.netease.cloudmusic`) — 根因已定位，待下一阶段按需解包适配**：通过逆向分析壳入口 `com.netease.nis.wrapper.MyApplication` 与 `libnesec.so` 确认，该版本 APK 的 `strAppName` 字段被编译期写死为 `com.netease.nis.wrapper.MyApplication`，其真实 Application 切换完全依赖 `libnesec.so` 在 `MyJni.load` / `MyJni.ra` 阶段完成 Native 解包并覆写静态字段。当 `libnesec.so` 因沙箱环境/路径校验提前返回时，`MyApplication.a(Context)` 用原值反射出第二个 `MyApplication` 实例赋给 `newApp`，导致 `onCreate` 无限递归。后续需针对 `libnesec.so` 的 Native 环境校验在扩展包 D（按需沙箱 Profile）中专项适配。
   - **芒果 TV (`com.hunantv.imgo.activity`)**：保持 19/20 启动通过率，1/20 低频 `libmsaoaidsec.so` `SIGSEGV` 待后续长稳压测归档。
2. **真机底座契约与集成验证结果（Xiaomi `d861a4fa` Android 17，已全部通过 ✅）**：
   - `validate-runtime-ingress.py`：原生样本 `INGRESS_GUEST_PASS`、`RuntimeIngressIntegration`（16/16）、`GuestIngressIntegration`（4/4，修正零售非 Root 设备 `debuggerd` 状态断言后全绿）全部通过。
   - `validate-native-policy.py --direct-syscalls`：18/18 项检查全部通过。
   - `RuntimePolicyIntegration`（`entryBootstrap=true`）：18/18 项检查全部通过（`fixedBindings = 257`，`managerFixedBindings = 0`，`addedSeccompFilters = 0`）。

---

### Phase 2：按需/分级策略扩展层（Opt-in Capabilities）解耦重构（核心动态挂载已完成 ✅）

**目标**：恢复在 Level 1 减法中被暂时切断的高级对抗与环境模拟能力，严格遵循**「默认零开销，显式请求时按需挂载」**原则。

#### 2.1 按需开启内核系统调用策略（`Opt-in Syscall Policy`）— 已完成 ✅
- **涉及文件**：[`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c)、[`syscall_namespace.c`](../android/runtime/native/syscall_namespace.c)。
- **已落地实现**：
  1. **默认模式（`default`）**：维持 Level 1 最小底座——仅启用 `tp_fixed_libc` 10 个导出入口，`addedSeccompFilters = 0`，`nativeDumpSuppressed = false`，零 `SIGSYS` 开销。
  2. **按需激活 `seccomp` 过滤器（`malware_probe_control`）**：当显式调用 `setSyscallPolicyNative(blockExecve, blockKill, networkSinkhole)` 且任一策略为 `true` 时，在运行期按需调用 `tp_syscall_namespace_install_with_signal_chain` 动态安装 `seccomp` 过滤器并同步导出拦截计数，消除 `SYSCALL_ANALYSIS_POLICY_NOT_INSTALLED` 断路。

#### 2.2 环境模拟与设备画像（`persona_control` / `env_mock`）按需动态挂载 — 已完成 ✅
- **涉及文件**：[`FixedManagerInventory.java`](../android/self-hook/java/dev/tripoder/selfhook/FixedManagerInventory.java)、[`RuntimeSelfHookProduct.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookProduct.java)、[`RuntimeSelfHookManagers.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java)、[`RuntimePlatformPolicy.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePlatformPolicy.java)、[`RuntimeSandboxPersona.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSandboxPersona.java)、[`RuntimeEnvMock.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEnvMock.java)。
- **已落地实现**：
  1. 默认启动 `installedManagerBindings = 0`，`GuestContext.getSystemService` 100% 透传平台原生 Manager。
  2. 当通过 `persona_control` 或 `env_mock` 显式配置 VPN 隐藏、弱网/断网、运营商、定位或电量伪装时，自动调用 `RuntimeSelfHookManagers.ensureInstalled(Family)` 对目标服务家族（`CONNECTIVITY`、`TELEPHONY`、`LOCATION`、`BATTERY`）执行按需方法准备与 ART Hook 发布，并将 `RuntimePlatformPolicy` 切至对应 Guest Facade，消除 `DEVICE_MANAGER_OBSERVATION_NOT_INSTALLED` 报错。

#### 2.3 解除单进程 Hook 会话独占互斥与 Selector 扩容（后续迭代）
- **涉及文件**：[`SelfHookControl.java`](../android/runtime/src/dev/tripoder/runtime/script/SelfHookControl.java)、[`RuntimeHookBridge.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeHookBridge.java)。
- **实施方案**：将单进程 Hook 通道拆分为被动抓包、内置探针与用户自定义脚本三条正交轨道，并将单会话 Selector 上限从 32 提升至 128。

---

### Phase 3：纵深安全测试与长尾兼容性扩展（P2）

**目标**：面向高阶逆向工程与复杂应用形态补齐长尾能力（对应 [scenario-gap-analysis.md](scenario-gap-analysis.md) 剩余边界）。

1. **Native Hook 增强**：
   - 在 [`NativeBufferScope.java`](../android/runtime/src/dev/tripoder/runtime/script/NativeBufferScope.java) 与 [`native_buffer.c`](../android/self-hook/native/native_buffer.c) 中支持 `before` 阶段读取入参指针缓冲区及以 `\0` 结尾的 C 字符串（`ctx.native.readCString`）；
   - 支持按 `moduleName + relativeOffset` 对去符号表（stripped）商业 `.so` 的未导出内部函数打 Inline Hook。
2. **多进程槽位弹性与死进程即时对账**：
   - 在 [`ProcessSupervisor.java`](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java) 中完善进程异常退出时的活动态 `Launch` 账本即时回收与 `reconcile` 入口，避免单应用崩溃残留 `PROCESS_ALREADY_RUNNING` 阻塞同空间重连。

---

## 4. 里程碑与验证矩阵汇总

| 阶段 | 核心交付物 | 关键验证命令 / 入口 | 当前状态 |
| --- | --- | --- | --- |
| **Phase 0** | 工作区壳委托与广播修复合入、死代码与历史碎片文档清理 | `python3 android/runtime/self-hook-tests/run.py` | **已完成 ✅**（404 源文件编译 + 15/15 测试套件全过） |
| **Phase 1** | Level 1 最小底座冻结、淘宝动态 DEX 白名单、腾讯视频跨用户 Context 修复、底座契约真机验证 | `validate-runtime-ingress.py` + `diagnose-six-app-startup.py` | **核心边界修复与底座验收完成 ✅**（Ingress 16/16 + 4/4、Native Policy 18/18、RuntimePolicy 18/18、淘宝 22/22 全过；网易云壳 Native 解包归入按需沙箱迭代） |
| **Phase 2** | Opt-in `seccomp` 策略动态挂载、按需 Persona/EnvMock Manager 动态挂载 | `RuntimePolicyIntegration` + `validate-runtime-ingress.py` | **核心动态挂载已完成 ✅**（默认 0 `seccomp` / 0 Manager Hook；显式开启时按需挂载生效） |
| **Phase 3** | 多轨道 Hook 会话解耦、Native `before` 缓冲/偏移 Hook、多进程槽位对账 | `python3 android/self-hook/tests/check_host.py` + 真机 Fixture 验收 | **待排期推进** |
