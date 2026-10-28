# Tripoder

Tripoder 是一个面向 Android 的**无需 Root 的免重打包应用虚拟化沙箱与 AI 原生动态安全测试平台**。

它采用**「统一最小承载底座（Level 1 Skeleton Baseline） + 按需/分级策略扩展（Opt-in Capabilities）」**双层解耦架构：默认以零冗余侵入的最小容器骨架保障未修改的第三方商业 APK 在多虚拟空间（Space）中稳定、流畅运行；同时支持安全工程师与外部 AI Agent 通过全局 MCP 控制面按需挂载 **ART Java Hook、ARM64 Native Hook、受限 TypeScript 脚本引擎、被动 TLS 明文抓包、环境伪装与强对抗沙箱策略**。

---

## 核心特性

1. **第一层：统一最小承载底座 (`dev.tripoder.engine`，默认零冗余侵入)**
   - **单 APK 四类进程架构**：主进程（原生工作台 UI 与本地 HTTP MCP Server）、`:authority`（权威控制面与组件/槽位调度中心）、`:recovery`（独立崩溃有界恢复门禁）、`:slot0..7`（8 个独立 Guest 容器进程槽位）。
   - **免网关单跳直达启动与真实 Linker 路径**：直接签发 `ActivityLease` 单跳拉起目标槽位 Activity；使用物理提取目录建立客体 `PathClassLoader` 的真实 linker 搜索路径，保留 `oat/odex` 编译缓存与 ART JIT 编译。
   - **最小骨架固定绑定（~220–264 项）与显式边界**：启动期移除 12 个系统 Manager 家族的 1,700+ 项冗余 Hook，仅保留四大组件、`Application`/`LoadedApk`（含加壳应用 `mInitialApplication` 委托保留）、`RuntimeReceiverProxy`（零运行期主线程 `toggleBatchNative`）、`RuntimeGuestPayload`（跨组件 `Parcel.LazyValue` 类加载器保真）及 `fixed_libc` 10 个核心路径入口重定向（默认 0 `seccomp` 过滤器）。
2. **第二层：自研按需扩展与 `self-hook` 动态分析引擎（零 Frida / 零 Xposed 依赖）**
   - **按需 ART Java Hook (`ManagedHook`) & 对象内省**：支持普通方法与 `<init>` 构造函数的按需局部反优化拦截、GC Root 读屏障安全、Caller-Sensitive 栈归属隐藏，以及非虚反射字段读取（`readField`）与有界容器快照（`snapshotContainer`）。
   - **按需 ARM64 Native Hook (`NativeHook`) & TLS 明文捕获**：内置 ARM64 指令重定位规划器、有界信号线程停顿、基于 `mremap` 的 W^X 匿名页原子发布，以及基于 `process_vm_readv` 的 `SSL_read/SSL_write` 有界缓冲区捕获与 HTTP/1.1 实时分帧 / HAR 导出。
   - **按需环境模拟与强对抗沙箱策略**：支持通过 `persona_control`、`env_mock`、`malware_probe_control` 按需启用设备画像伪装、网络故障注入与内核级 `seccomp` 系统调用拦截。
3. **AI Agent 原生全局控制面 (HTTP / stdio MCP v3)**
   - 支持电脑经局域网 Streamable HTTP `/mcp` 或本地 stdio 适配器直连手机，无需 ADB；提供覆盖空间/应用管理、APK 导入、项目源码 CAS 管理、进程与按需 Hook、DEX/Heap 快照、多窗口/Compose UI 自动化、被动网络审计与通用文件断点续传的 60 个标准化工具组。

---

## 核心文档导航

所有架构规范与实施计划均收敛于 [`docs/`](docs/README.md) 目录：

- **[文档总索引 (`docs/README.md`)](docs/README.md)**
- **[架构梳理与分阶段实施计划 (`docs/implementation-plan.md`)](docs/implementation-plan.md)**：双层解耦架构总纲、当前代码全量盘点与 Phase 0–3 实施路线图。
- **[当前总体架构 (`docs/current-architecture.md`)](docs/current-architecture.md)**：第一性原理边界、单 APK 四类进程模型、应用/空间模型与单跳启动链路。
- **[单运行时与最小底座收敛 (`docs/single-runtime.md`)](docs/single-runtime.md)**：Level 1 最小底座执行规则、Manager/Seccomp 减法、Schema 41 与验证入口。
- **[执行契约与状态机 (`docs/execution-contract.md`)](docs/execution-contract.md)**：Install、`ProcessSession`、Component Lease、`Operation` 与退出排空围栏。
- **[Runtime 虚拟化设计 (`docs/runtime-design.md`)](docs/runtime-design.md)**：Application 引导、四大组件、存储隔离、系统服务边界与 `/proc` 视图机制。
- **[Self-Hook 与脚本引擎设计 (`docs/self-hook-design.md`)](docs/self-hook-design.md)**：ART Java Hook、ARM64 Native Hook、`fixed_libc` 双页事务与 `RestrictedAst` 编译器。
- **[Guest 兼容性分层与基线 (`docs/guest-compatibility.md`)](docs/guest-compatibility.md)**：L1–L13 兼容性矩阵、六大商业应用真机实测基线与已知物理边界。
- **[AI Agent 控制面与 MCP 规范 (`docs/agent-mcp-design.md`)](docs/agent-mcp-design.md)**：HTTP/stdio 接入、[MCP 工具分组 v3](docs/mcp-tool-groups.md)、[被动网络采集](docs/passive-network-sessions.md)与[文件导出](docs/file-pull.md)。
- **[全场景能力评估与演进缺口分析 (`docs/scenario-gap-analysis.md`)](docs/scenario-gap-analysis.md)**：六大安全与测试业务场景的能力匹配度矩阵与治理护栏。

---

## 快速构建与验证

### 1. 主机端契约、Schema 与自研核心测试

```bash
# TypeScript Hook SDK 与 MCP Schema 测试
npm test
node --test agent/schema.test.mjs

# Runtime / Self-Hook / Workbench 主机回归测试
python3 android/runtime/self-hook-tests/run.py
python3 android/workbench/test/run-mcp-tests.py
python3 android/workbench/test/run-catalog-tests.py
```

### 2. 真机工作台构建与最小底座验收

```bash
# 构建工作台与引擎 APK
python3 android/scripts/build-workbench.py \
  --build-dir android/product/build-level-one \
  --sdk /Users/len/Library/Android/sdk --platform android-37.0 --build-tools 37.0.0 \
  --java-home /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  --ndk /Users/len/Library/Android/sdk/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.engine

# 运行六大商业应用冷启动与前后台切换验证
python3 android/scripts/diagnose-six-app-startup.py --guest-only --bg-fg-cycles 5 --package <pkg>
```
