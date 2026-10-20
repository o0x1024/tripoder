# Tripoder

Tripoder 是一个面向 Android 的**无需 Root 的免重打包应用虚拟化沙箱与 AI 原生动态安全测试平台**。

它允许在普通未 Root 的 Android 设备上直接导入并运行多开第三方 APK（多虚拟空间 Space 隔离），同时内置自研的 **ART Java Hook + ARM64 Native Hook + 受限 TypeScript 脚本引擎 + MCP (Model Context Protocol) 网关**，供安全工程师或外部 AI Agent 执行自动化动态分析、接口加解密逆向、隐私合规审计与组件安全测试。

---

## 核心特性

1. **单 APK 免 Root 虚拟化沙箱 (`dev.tripoder.engine`)**
   - **三类进程架构**：主进程（Compose 工作台 UI 与本地 MCP Server）、`:authority`（权威控制面与组件/槽位调度中心）、`:slot0..7`（8 个独立 Guest 容器进程槽位）。
   - **免网关单跳直达启动**：通过 `prepareApplicationLaunch` 直接签发 `ActivityLease` 并拉起目标槽位 Activity，冷启动耗时进入 350ms–1.5s 量级。
   - **完整四大组件与系统服务虚拟化**：支持 `Activity`、`Service`、`ContentProvider`（含 `InstallProviderIndex` 快速索引）、`BroadcastReceiver`、`PendingIntent`、`JobScheduler`、存储隔离（`dataId`）及最近任务栏独立卡片。
2. **自研高性能 `self-hook` 引擎（零 Frida / 零 Xposed 依赖）**
   - **ART Java Hook (`ManagedHook`)**：采用 `ExecuteNterpImpl` ARM64 汇编解释器策略（替代慢速 C++ Switch 解释器，提速 10–30 倍），兼顾 100% 防内联拦截、GC Root 读屏障安全与 Caller-Sensitive 栈归属隐藏。
   - **ARM64 Native Hook (`NativeHook` & `fixed_libc`)**：内置 ARM64 指令重定位规划器、信号线程安全停顿、基于 `mremap` 的 W^X 匿名页原子发布，以及接管 `libc.so` 10 个核心路径入口的双页导出包装器（支撑虚拟路径重定向与 `/proc/self/*` 隐身）。
3. **AI Agent 原生控制面 (MCP Server)**
   - 电脑通过 stdio MCP 和 ADB 直接连接，无需身份认证、配对或手机脚本审批。提供 **42 个 MCP 工具**，覆盖运行目标选择、运行时类/方法内省、Hook 管线、主动调用 Java 方法、存储/网络审计与 UI 自动化；断开连接不会结束客体应用。

---

## 核心文档导航

所有架构与设计规范均收敛于 [`docs/`](docs/README.md) 目录：

- **[文档总索引 (`docs/README.md`)](docs/README.md)**
- **[当前总体架构 (`docs/current-architecture.md`)](docs/current-architecture.md)**：第一性原理边界、单 APK 三进程模型、Space × App × Project 模型与工程结构。
- **[执行契约与状态机 (`docs/execution-contract.md`)](docs/execution-contract.md)**：Install、ProcessSession、Component Lease、Operation 与退出排空围栏。
- **[Runtime 虚拟化设计 (`docs/runtime-design.md`)](docs/runtime-design.md)**：Application 引导、四大组件、存储隔离、系统服务代理与 `/proc` 隐身机制。
- **[Self-Hook 与脚本引擎设计 (`docs/self-hook-design.md`)](docs/self-hook-design.md)**：ART Nterp Java Hook、ARM64 Native Hook、`fixed_libc` 双页事务与 `RestrictedAst` 编译器。
- **[Guest 兼容性分层与基线 (`docs/guest-compatibility.md`)](docs/guest-compatibility.md)**：L1–L13 兼容性矩阵、真机实测基线与已知边界。
- **[启动性能架构 (`docs/launch-performance.md`)](docs/launch-performance.md)**：启动关键路径与六大核心性能优化细节。
- **[AI Agent 控制面与 MCP 规范 (`docs/agent-mcp-design.md`)](docs/agent-mcp-design.md)**：免配对接入、42 个 MCP 工具与实际操作闭环。

---

## 快速构建与测试

### Android 引擎与虚拟化运行时

```bash
# 构建主引擎 Debug APK
./android/gradlew -p android :engine:assembleDebug

# 运行主机端单元测试 (Runtime / Self-Hook / Engine)
./android/gradlew -p android :runtime:testDebugUnitTest :self-hook:testDebugUnitTest :engine:testDebugUnitTest

# 运行真机 Instrumentation 验证
./android/gradlew -p android :self-hook:connectedDebugAndroidTest :engine:connectedDebugAndroidTest
```

### TypeScript Hook SDK (主机端模型与校验)

```bash
npm ci --ignore-scripts
npm test
```
