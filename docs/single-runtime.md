# 单运行时与最小底座收敛规约 (Single Runtime & Level 1 Baseline)

- **更新日期**：2026-10-28
- **关联文档**：[当前总体架构](current-architecture.md) · [架构梳理与实施计划](implementation-plan.md) · [Guest 兼容性基线](guest-compatibility.md)

---

## 1. 核心决策：废除双模式，确立「最小底座 + 按需扩展」

Tripoder 已彻底移除早期的 `THIN` / `THICK` 双运行模式选择。所有客体应用统一运行在同一套运行时底座之上，遵循两条核心铁律：

1. **第一优先级是原版应用正常运行**：冷启动、真实用户认证、页面浏览、媒体播放及前后台切换恢复。业务 Java/Native Hook、代码快照、流量抓包、环境伪装与强对抗沙箱属于第二层的**按需能力（Opt-in）**。应用自身稳定运行、分析工具可用、分析工具开启后应用仍稳定，三者分层独立验收。
2. **默认启动路径做彻底减法（Level 1 最小骨架）**：
   - 统一运行时的默认启动路径只保留承载客体存活所必需的身份、四大组件路由、存储隔离、类加载、JNI 与基础信号语义。
   - 任何默认固定 Hook 都必须证明其承载必要性；能够通过显式 `GuestContext`、Wrapper、Binder 实例边界解决的职责，绝不在启动期对系统类打全量 ART Hook。

---

## 2. Level 1 最小底座执行规则（当前代码基线）

### 2.1 Java / ART 执行与发布规则
- **移除 12 个系统 Manager 家族与 Widget 的全量固定 ART Hook**：
  - [`RuntimeSelfHookProduct`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookProduct.java) 与 [`RuntimeFixedPreparation`](../android/runtime/src/dev/tripoder/runtime/RuntimeFixedPreparation.java) 不再安装 12 个 Manager 家族（`Connectivity`、`WifiP2p`、`NetworkStats`、`Telephony`、`Audio`、`Battery`、`Power`、`Shortcuts`、`Location`、`ActivityManager` 等）的 1,700+ 个固定方法绑定，固定绑定总数从 **1,818+ 降至 ~220–264 项**，`managerFixedBindings = 0`。
  - 普通设备服务默认直接从物理宿主 Context 获取平台原生 Manager，由真实 Android 系统执行权限与行为。
  - **显式边界替代**：通知在 `INotificationManager` 边界复用渠道/分组/tag 的空间映射；`JobScheduler` 使用实际 Java 子类 [`RuntimeJobScheduler`](../android/runtime/src/dev/tripoder/runtime/RuntimeJobScheduler.java) 经过 `:authority` 调度账本；`IPackageManager` 使用显式 endpoint，移除 `ApplicationPackageManager` 全量方法替换。
- **启动期一次发布、保留 JIT、运行期主线程零重发布**：
  - 最小骨架固定绑定在客体代码执行前完成准备与单次发布，保留普通 ART JIT 编译（`jitEnabled=true / runtimeStateRestored=true / allowUnhookedCompilation=true`），启动期不做无差别全进程调用者反优化。
  - 动态、Manifest 与有序最终 `BroadcastReceiver` 统一经过 [`RuntimeReceiverProxy`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java) 显式代理，彻底移除运行期对客体 `onReceive` 动态打 ART Hook 的入口，确保运行期 UI 主线程 `toggleBatchNative = 0`。
  - 仅当用户/Agent 显式请求业务分析 Hook 时，才按需执行调用者反优化与 JIT code cache 失效。

### 2.2 Native 路径、信号与线程停顿规则
- **仅安装 `fixed_libc` 10 个核心文件入口，默认零 Seccomp**：
  - [`RuntimeSelfHookStorage.installNative`](../android/runtime/native/self_hook_storage.c) 默认仅发布 `tp_fixed_libc` 的 10 个路径适配函数（`openat`、`fstatat`、`unlinkat`、`mkdirat`、`renameat2`、`faccessat`、`readlinkat`、`chdir`、`symlinkat`、`linkat`）。
  - 默认启动**不安装 `tp_syscall_namespace` seccomp 过滤器（`addedSeccompFilters = 0`）**，不调用 `tp_signal_user_delivery_reserve`，也不拦截 `debuggerd` 信号吞掉 Native Crash Dump。
  - 客体 Native 模块的普通 `sigaction` 仍接入 ART 信号链，保留客体自定义 `SIGSEGV` 保护页处理器语义。
- **有界线程停顿退避（Bounded Thread Park）**：
  - `tp_park_begin_bounded` 对 `TIMEOUT`/`INVENTORY` 快照支持最多 3 次有界重试（间隔 1–2ms），降低多线程并发初始化时的瞬时停顿冲突。
- **真实进程名与 `/proc` 视图**：
  - 进程身份通过 `Process.setArgV0` 在主线程建立并经内核 `cmdline` 验证，主线程 `comm` 随 Android 标准入口设置；`/proc/self/*` 使用真实内核 FD 与 `path_policy` 过滤，不维护过期的静态文本快照表（已删除旧 `tp_project_module` 死路径，缩减原生库 BSS 约 16.9 MB）。

### 2.3 按需能力（Opt-in）的显式边界与动态挂载
- 观测、伪装与内核阻断能力仅在显式请求时按需启用，**默认启动期保持 0 个 Manager ART Hook（`installedManagerBindings = 0`）与 0 个 `seccomp` 过滤器（`addedSeccompFilters = 0`）**：
  - **按需 Manager 门面动态挂载（`persona_control` / `env_mock`）**：当用户或 Agent 显式配置了 `persona_control`（VPN 隐藏、运营商/定位/电量伪装）或 `env_mock`（网络类型/漫游/定位/电量注入）时，[`RuntimeSelfHookManagers.ensureInstalled`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java) 与 [`RuntimeSelfHookProduct.installManagerFamily`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookProduct.java) 仅针对被请求的目标服务域（`CONNECTIVITY`、`TELEPHONY`、`LOCATION`、`BATTERY`）按需安装对应家族的 ART 绑定并激活 [`RuntimePlatformPolicy`](../android/runtime/src/dev/tripoder/runtime/RuntimePlatformPolicy.java) 中的 Guest Facade。
  - **按需内核系统调用策略（`malware_probe_control`）**：当显式启用 `blockExecve`、`blockKill` 或 `networkSinkhole` 时，[`RuntimeSelfHookStorage.setSyscallPolicyNative`](../android/runtime/native/self_hook_storage.c) 按需动态挂载 `tp_syscall_namespace` `seccomp` 过滤器并设置阻断策略（详见 [实施计划 Phase 2](implementation-plan.md#phase-2按需分级策略扩展层opt-in-capabilities解耦重构核心p1)）。

---

## 3. 持久化 Schema 与已废弃接口

- **Schema 41 迁移**：对 Schema 35–40 的 `project_refs` 执行 `DROP COLUMN mode`，保留其余列、索引和每条绑定的状态。安装/`dataId`、项目源码、操作回执、`UNKNOWN`、恢复围栏与 checkpoint 均保持不变。
- **已移除的旧模式接口**：`setProjectMode`、`setApplicationMode`、MCP `application_manage(action=set_mode)` 已彻底移除，旧请求在分派前明确拒绝，不执行隐式映射或重放。工作台 UI 不再显示 THIN/THICK 模式徽标或切换按钮。

---

## 4. 核心构建与验证入口

### 4.1 主机契约与回归测试
- `python3 android/runtime/test/ApplicationProjectsSchemaTest.py`：验证数据库 Schema 迁移与绑定身份。
- `python3 android/runtime/test/CatalogMetadataHostTest.py`：验证生产 SQL、绑定身份与并发目录读取。
- `python3 android/workbench/test/run-catalog-tests.py`：验证单应用项目及多空间目录行为。
- `node --test agent/schema.test.mjs`：验证公开 MCP Schema 与实际分派一致性。
- `python3 android/runtime/self-hook-tests/run.py`：验证 Hook/系统服务策略与 TypeScript SDK 编译。

### 4.2 真机构建与最小底座验证命令

```sh
# 1. 构建工作台与引擎 APK
rtk proxy python3 android/scripts/build-workbench.py \
  --build-dir android/product/build-level-one \
  --sdk /Users/len/Library/Android/sdk --platform android-37.0 --build-tools 37.0.0 \
  --java-home /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  --ndk /Users/len/Library/Android/sdk/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.engine

# 2. 运行真机最小底座入口与显式契约集成验证
rtk proxy python3 android/scripts/validate-runtime-ingress.py \
  --receipt android/product/build-level-one/build-receipt.json \
  --output docs/evidence/level-one-validation \
  --serial d861a4fa --retry-engine-once

# 3. 运行六大代表性商业应用冷启动与前后台切换冒烟验证
rtk proxy python3 android/scripts/diagnose-six-app-startup.py \
  --guest-only --bg-fg-cycles 5 --package <pkg>
```
