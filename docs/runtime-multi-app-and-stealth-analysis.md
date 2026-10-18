# 多应用并发、最近任务卡片机制与反检测（反调试/容器/Hook）第一性原理分析

2026-10-18 整理。本文档从 Android 系统底层机制（AMS / ATMS / WMS / Bionic / ART / Linux VFS）第一性原理出发，分析以下两个核心问题：
1. **多应用同时运行与最近任务（Recents）卡片表现**：对比 `aslody/VirtualApp` 与 `Tripoder` 的多进程并发模型、多任务栈（Task）归属、中间残留卡片（`"正在启动空间中的应用…"`）的成因与消除方案，以及 Android 10+ / HyperOS 下任务卡片标题与图标的系统级限制。
2. **反调试、模拟器检测与容器/Hook 隐蔽性分析**：剖析三方应用（及商业加固/风控 SDK）在 Tripoder 空间内运行时，哪些检测完全不会触发、哪些容器/Hook 特征会被识别，以及按投入产出比排序的隐蔽性演进路线。

---

## 第一部分：多应用并发运行与最近任务（Recents）表现分析

### 1. VirtualApp 与 Tripoder 的多应用并发架构对比

不论是 `VirtualApp` 还是 `Tripoder`，要在**免 Root** 的条件下同时运行多个未安装的客体 APK，本质上都是在做**应用层系统虚拟化（App-level OS Virtualization）**：因为宿主无法向系统的 `PackageManagerService`（PMS）动态注册新组件，所以必须在宿主的 `AndroidManifest.xml` 中**预先静态声明一组占位的子进程（Stub Processes）与占位 Activity（Stub Activities）**，运行时通过接管进程入口和 Activity 生命周期将客体组件“装入”占位槽位中。

| 维度 | `aslody/VirtualApp` | `Tripoder`（当前架构） |
|---|---|---|
| **同时运行多个 App** | **支持**（预声明 `:p0` ~ `:p99` 等进程槽） | **支持**（[`AndroidManifest.xml`](../android/product/AndroidManifest.xml) 预声明 `:slot0` ~ `:slot7` 共 8 个隔离客体进程槽） |
| **多任务独立卡片（双开并行窗口）** | **支持**（按 `TaskAffinity` 分配独立 Task 栈） | **支持**（每个 `:slot0` ~ `:slot7` 拥有独立的 `affinity0` ~ `affinity3` 及 `M0`~`M3` 启动模式组合，共 $8 \times 4 \times 4 \times 3 = 384$ 个占位 Activity） |
| **同包多开（双开/多空间隔离）** | 通过 `userId`（`0, 1, 2...`）隔离数据目录与进程槽 | 通过 `space`（空间 ID）在 [`ProcessSupervisor.java`](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java) 与 [`RuntimeBroker.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeBroker.java) 中实现严格的目录、组件、进程槽与存储隔离 |
| **卡片标题/图标伪装机制** | 调用 `Activity.setTaskDescription(TaskDescription(label, icon))` | 在 [`RuntimeActivityWindow.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeActivityWindow.java) 的 `apply` 中调用 `activity.setTaskDescription(new ActivityManager.TaskDescription(label, icon))` |

---

### 2. 最近任务列表（Recents）三张卡片的成因与消除机制

在未修复空网关任务残留前，从 Tripoder 工作台启动一个 `singleTask` 客体应用（如 DeepSeek、微信、王者荣耀），划出系统最近任务列表（Recents）会看到三张卡片（从右到左）：

1. **右侧卡片：Tripoder 宿主工作台**
   - **对应组件**：`dev.tripoder.engine/dev.tripoder.workbench.WorkbenchActivity`（`taskAffinity="dev.tripoder.engine"`）。
   - **成因**：因为用户是从 Tripoder 工作台界面点击启动客体应用的，工作台本身是一个前台 Activity 任务栈（对比系统桌面 Launcher 因带有 `CATEGORY_HOME` 而不出现在 Recents 中，普通 App 作为工作台默认会保留在 Recents 中）。
2. **中间卡片：`"正在启动空间中的应用…"`（残留空壳卡片）**
   - **对应组件**：[`RuntimeGuestGateway.java`](../android/engine/src/dev/tripoder/runtime/RuntimeGuestGateway.java)（`taskAffinity="dev.tripoder.engine.guestentry"`）。
   - **第一性原理根因**：
     1. 工作台以 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK` 启动 `RuntimeGuestGateway`，系统为此创建了网关任务栈 $T_{\text{gateway}}$（`gatewayTaskId`），并在等待客体 `Application` 初始化期间渲染 `"正在启动空间中的应用…"`。
     2. 当客体主入口 Activity 声明为 `standard`（`M0`）或 `singleTop`（`M1`）且不带 `NEW_TASK` 时，`startActivity(physical)` 会将客体 Activity 直接压入同一个任务栈 $T_{\text{gateway}}$（此时 `root.taskId == entry.gatewayTaskId`），`RuntimeGuestGateway.finish()` 后客体界面直接接管该卡片，不会多出一张卡片。
        > **关键约束**：`RuntimeGuestGateway` **不能**在 `AndroidManifest.xml` 中静态设置 `android:excludeFromRecents="true"`，因为一旦设置，整个 $T_{\text{gateway}}$ 任务栈都会带有 `EXCLUDE_FROM_RECENTS` 标志，导致压入该任务栈的 `standard`（`M0`）客体应用也从最近任务列表中消失，并破坏空任务观察协议。同理，在 `M0` 场景下网关也不能无条件调用 `finishAndRemoveTask()`，否则会连同同一任务栈中的客体 Activity 一起销毁。
     3. 然而，当客体主入口 Activity 声明为 `singleTask`（`M2`）或 `singleInstance`（`M3`）（例如 DeepSeek 的 `com.deepseek.chat.MainActivity` 映射到 `TaskP7$F0M2C2`，其 `taskAffinity="dev.tripoder.engine.runtime.affinity0"` $\neq$ `guestentry`）时，`startActivity(physical)` 会根据 Android ActivityTaskManager 规则**立即开辟第二个独立任务栈 $T_{\text{guest}}$**（`root.taskId != entry.gatewayTaskId`）。
     4. 随后 `RuntimeGuestGateway` 调用 `finish()`，$T_{\text{gateway}}$ 中的唯一 Activity 销毁（`numActivities=0, baseActivity=null, topActivity=null`），但 `finish()` 仅弹出 Activity 而不移除 `RecentTasks` 中的历史记录。于是 $T_{\text{gateway}}$ 变成一张 `hasTask=false` 的死亡快照卡片留在中间。
   - **根治方案（已实施）**：
     - 在 [`RuntimeEmptyGateways.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeEmptyGateways.java) 中，只要观察到 `RuntimeGuestGateway` 对应的任务已空（`numActivities == 0 && baseActivity == null && topActivity == null`），且其 `ControllerLaunchLedger` 对应的 `root` 票据已确认附着到**另一个独立任务栈**（`root.taskId >= 0 && root.taskId != info.taskId` 且具备 `OWNED_TASK_ATTACHED` 或 `TASK_REMOVED_CONFIRMED` 持久证据），即可判定 $T_{\text{gateway}}$ 为已分离的纯 UI 空壳，无需等待客体会话 `RETIRED`，立即通过 `AppTask.finishAndRemoveTask()` 将其从系统 `RecentTasks` 中移除。
     - 在 [`RuntimeGuestGateway.java`](../android/engine/src/dev/tripoder/runtime/RuntimeGuestGateway.java) 的 `onDestroy()` 以及 [`RuntimeBroker.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeBroker.java) 的 `ACTIVITY_ATTACHED` 回调点异步触发 `RuntimeEmptyGateways.reclaimAsync(broker)`，从而在客体窗口附着且网关销毁后立即消除中间残留卡片。
3. **左侧卡片：客体应用自身的任务栈（为何在部分系统上仍显示 Tripoder 标题与图标）**
   - **代码现状**：[`RuntimeActivityWindow.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeActivityWindow.java) 已经在 `apply` 中读取客体 Activity/Application 的真实 `label` 和 `icon`，构造 `ActivityManager.TaskDescription` 并调用 `activity.setTaskDescription(...)`（这与 `VirtualApp` 的做法完全一致）。
   - **系统机制演进**：
     - **Android 5.0 ~ Android 9.0**：系统 SystemUI 的 Recents 面板完全信任 `TaskDescription(label, icon)`，因此 `VirtualApp` 在旧系统上能直接将后台卡片顶部的图标和文字改成客体 App 的名字和图标。
     - **Android 10+（API 29+）及国内定制 ROM（MIUI / HyperOS / ColorOS / OriginOS）**：Google 自 Android 10 起为防止恶意应用伪装后台卡片钓鱼，重构了 `TaskDescription` 与 `RecentTaskInfo` 的渲染逻辑。国内 ROM 的桌面/最近任务组件（如小米 `com.miui.home`）直接根据 `Task.realActivity.getPackageName()`（即宿主包名 `dev.tripoder.engine`）向系统 `PackageManagerService` 查询宿主的应用图标与名称作为卡片顶栏的主标题，而忽略应用通过 `setTaskDescription(String, Bitmap)` 传入的自定义图标与主包名文本。
   - **免 Root 下的突破边界**：在现代 Android 系统上，由于 `SystemUI` / `Launcher` 是跨进程直接向系统 `PMS` 查询 `realActivity` 的宿主包名，单 APK 容器在自身进程内做任何 Hook 都无法改变 `SystemUI` 进程查到的宿主包名；只有为每个槽位安装极小的独立插件包（Stub Pack APK）并在安装时定制名称图标，或者通过系统级模块（LSPosed）Hook `SystemUI` 才能改变顶栏包名显示。

---

## 第二部分：反调试、模拟器检测与容器/Hook 隐蔽性分析

从安全对抗的第一性原理来看，三方 App（以及梆梆、爱加密、360、腾讯御安全/MTP、网易易盾、LIAPP、DexProtector 等商业加固/风控 SDK）的环境检测分为四个完全不同的技术维度：**① 硬件模拟器检测**、**② 调试器/Frida 检测**、**③ 虚拟容器（多开沙箱）检测**、**④ ART/Native Inline Hook 完整性检测**。

将 Tripoder 运行在**真实物理手机**上时，这四个维度的暴露面截然不同：

### 1. 哪些检测在 Tripoder 中默认【不会触发】？

#### （1）传统硬件/QEMU 模拟器检测：完全免疫
模拟器检测的本质是识别 `x86 -> ARM` 指令转译（houdini/libndk_translation）、QEMU 虚拟硬件设备、Goldfish/Cuttlefish 内核特征或缺失的物理传感器。
- Tripoder 是**应用层沙箱（App-level Runtime Container）**，客体代码直接跑在真机物理 ARM64 CPU、真实 Adreno/Mali GPU、真实传感器（陀螺仪/加速度计/磁力计）、真实基带和真实电池温度之上。
- [`RuntimePlatform.java`](../android/runtime/src/dev/tripoder/runtime/RuntimePlatform.java) 绑定的是真机的真实硬件与平台基线。因此针对雷电、夜神、MuMu、蓝叠等 PC 模拟器的检测手段在真机 Tripoder 中**天然不会触发**。

#### （2）常规 `ptrace` / JDWP 反调试检测：默认不触发
传统反调试检查的是：
- `/proc/self/status` 中的 `TracerPid` 是否非 `0`；
- `android.os.Debug.isDebuggerConnected()` 或 JDWP 线程状态；
- 宿主 ApplicationInfo 是否带有 `FLAG_DEBUGGABLE`；
- 本地 `27042` 端口或 `/proc/self/maps` 中是否存在 `frida-agent.so` / `gum-js-loop` / `linjector`。

Tripoder 的自研 Hook 引擎（`libtripoderselfhook.so`）运行在进程内部（In-process），**不使用 `ptrace` 附加**，也不注入 Frida。只要使用 Release 构建（`android:debuggable="false"`），常规 `ptrace`/JDWP/Frida 特征扫描均不会命中。

---

### 2. 核心暴露面分析与已实施的隐蔽性加固

商业加固与风控 SDK 中的**“多开/虚拟容器检测（VirtualApp Detection）”**和**“Native/ART Hook 完整性校验”**主要针对以下维度：

#### 维度 A：虚拟容器 `/proc/self/*` 与路径泄漏（已实施内核描述符级虚拟化）
1. **`/proc/self/maps`、`/proc/self/smaps`、`/proc/self/cmdline`、`/proc/self/comm`、`/proc/self/status`、`/proc/self/mountinfo` 虚拟化**：
   - **问题本质**：未虚拟化 `/proc/self/*` 时，客体在 C/C++ 或 Java 层调用 `fopen("/proc/self/maps", "r")` 或读取 `/proc/self/cmdline` 会直接看到 `dev.tripoder.engine:slotX`、`libtripoderselfhook.so`、`/memfd:tripoder-dex` 以及宿主沙箱路径 `/data/user/0/dev.tripoder.engine/files/runtime/...`。
   - **已实施加固（[`path_policy.c`](../android/runtime/native/path_policy.c)、[`io_adapter.c`](../android/runtime/native/io_adapter.c)、[`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c)）**：
     - **进程名与线程名伪装（`tp_configure_process`）**：在 [`RuntimeSelfHookStorage.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookStorage.java) 初始化时将客体逻辑 `processName` 传入 Native 层，通过 `prctl(PR_SET_NAME)` 将主线程 `comm` 设为客体逻辑名。
     - **`/proc` 伪文件 `memfd` 合成（`tp_open_proc`）**：在 `tp_openat` 中拦截对自身进程 `/proc/{self,thread-self,<pid>}/{cmdline,comm,status,maps,smaps,mountinfo,mounts}`（含 `task/<tid>/*`）的只读打开请求，使用 `memfd_create` 生成真实内核文件描述符返回：
       - `cmdline` / `comm`：返回客体逻辑 `processName` / `comm`；
       - `status`：重写 `Name:` 为客体 `comm`，并强制 `TracerPid:\t0`；
       - `maps` / `smaps` / `mountinfo`：将客体 `artifact_paths` 重写为标准安装路径 `/data/app/~~tripoder/<guest_pkg>`，将客体 `data_paths` 重写为 `/data/user/0/<guest_pkg>`，将 `/memfd:tripoder-dex` 重写为 `[anon:dalvik-classes.dex]`，并彻底剔除包含 `libtripoderselfhook.so`、`libtripoderio.so`、`frida`、`gadget`、`dev.tripoder.engine`、`dev.tripoder.runtimeprobe` 的宿主映射行；
     - **逻辑 APK 路径双向闭环（`tp_resolve` & `tp_unresolve`）**：`tp_unresolve` 在 `readlinkat` 时将物理 `artifact_paths` 反向重写为 `/data/app/~~tripoder/<guest_pkg>`；同时 `tp_resolve` 支持直接打开 `/data/app/~~tripoder/<guest_pkg>/base.apk` 并锚定到客体真实 `artifact_fd`，使客体 Native 签名校验器从 `/proc/self/maps` 提取 APK 路径后再调用 `open()` 验签时形成完整闭环。
     - **引擎内部自检物理直读隔离**：[`code_page.c`](../android/self-hook/native/code_page.c)、[`near_address.c`](../android/self-hook/native/near_address.c)、[`art_access.c`](../android/self-hook/native/art_access.c)、[`art_framework.c`](../android/self-hook/native/art_framework.c) 以及 [`RuntimeNativeMappingEvidence.java`](../android/runtime/src/dev/tripoder/runtime/RuntimeNativeMappingEvidence.java) 均通过直接 `syscall(SYS_openat, ...)` 读取物理 `/proc/self/maps`、`/proc/self/smaps` 与 `/proc/self/cmdline`，确保引擎自身地址空间校验与控制面证据验证不受客体视图虚拟化影响。
2. **零环境变量泄漏与零 `rwxp` 页面**：
   - [`self_hook_storage.c`](../android/runtime/native/self_hook_storage.c) 直接通过 JNI 字节数组向 `tp_configure` 传递路径策略，不写入任何 `TRIPODER_*` 环境变量。
   - [`code_page.c`](../android/self-hook/native/code_page.c) 采用 `rw-p` 暂存页写入指令后通过 `mprotect(..., PROT_READ | PROT_EXEC | PROT_BTI)` + `mremap` 原子发布，进程内不存在任何 `rwxp` 页面。

#### 维度 B：深度对抗边界（L3/L4 商业壳的剩余检测面）
1. **`libc.so` 代码段（`.text`）完整性校验（CRC / Prologue Check）**：
   - [`fixed_libc.c`](../android/self-hook/native/fixed_libc.c) 对 `libc.so` 的 10 个内核入口包装函数（`__openat`, `fstatat`, `unlinkat`, `mkdirat`, `renameat2`, `__faccessat`, `readlinkat`, `chdir`, `symlinkat`, `linkat`）安装了 BTI 兼容的 ARM64 绝对跳转补丁。
   - 若高级商业壳直接从磁盘读取 `/apex/com.android.runtime/lib64/bionic/libc.so` 并对比内存中这 10 个函数入口的前 16 字节，仍可检测到 `libc.so` 入口指令变化。
2. **内联汇编直接系统调用（Raw Syscall `svc #0`）**：
   - Tripoder 明确将定位界定为同 UID 应用层运行时容器（`sameUidNativeIsolation: false`，见 [`ProbeActivity.java`](../android/runtime-probe/src/dev/tripoder/runtimeprobe/ProbeActivity.java)），不在默认配置中拦截 `svc #0` 直接系统调用。若顶级商业壳通过内联 `svc #0` 绕过 `libc.so` 直读内核，则会绕过 `tp_openat`。

---

### 3. 各等级三方应用在当前 Tripoder 下的检测风险矩阵

| 应用/防护等级 | 典型代表 | 检测手段 | 在加固后 Tripoder 中的表现 |
|---|---|---|---|
| **L1：普通应用 / 轻量 SDK** | 绝大多数工具、资讯、开源应用、未加壳商业应用 | 仅检查 `Build` 属性、`Debug.isDebuggerConnected()`、常规传感器 | **不会被检出**（正常运行） |
| **L2：常规风控 / 基础多开检测** | 普通社交/电商基础风控、常规广告反作弊 | 通过 `libc` / Java 检查 `/proc/self/maps`、`/proc/self/cmdline`、`/proc/self/status`、`TracerPid`、`readlink` | **已防护**（虚拟化 `/proc/self/*` 与逻辑 APK 路径闭环已消除宿主与 Hook 特征） |
| **L3：商业加固免费版/标准版** | 360加固、爱加密、梆梆基础版、腾讯乐固 | 扫描 `/proc/self/maps` 中的 Hook SO 与 `rwxp` 页、从 `maps` 提取 APK 路径验签 | **大幅收敛**（无 `rwxp` 页、`maps` 隐藏 `libtripoderselfhook.so` 且逻辑 APK 路径可读可验签） |
| **L4：金融级商业壳 / 顶级游戏反作弊** | 银行 App、梆梆/爱加密企业版、腾讯 TP (ACE)、网易易盾、DexProtector | `svc #0` 内联系统调用直读 `/proc/self/*`、对比磁盘与内存 `libc.so` `.text` CRC、校验 `ArtMethod` 底层结构 | **可被检出**（需额外引入可选的 `seccomp-bpf` 模式方可拦截 `svc #0`） |

