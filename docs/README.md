# Tripoder 文档索引 (Documentation Index)

本目录收敛了 Tripoder（免 Root 应用虚拟化沙箱与 AI 原生动态安全测试平台）的核心架构与工程规范文档。

## 核心文档列表

| 文档 | 内容概述 |
| --- | --- |
| [current-architecture.md](current-architecture.md) | **总体架构与第一性原理边界**：单 APK (`dev.tripoder.engine`) 三类进程模型（主 UI / `:authority` / `:slot0..7`）、Space × App × Project 模型、免网关单跳启动链路及工程目录结构。 |
| [execution-contract.md](execution-contract.md) | **控制面与执行面状态机契约**：APK 安装状态机（`staging -> published -> committed`）、进程槽位与 `ProcessSession`、四大组件租约（Lease）、幂等异步操作（`Operation`）、一次性回调令牌（`CallbackCapability`）及退出排空围栏（`ShutdownFence`）。 |
| [runtime-design.md](runtime-design.md) | **Runtime 虚拟化核心设计**：Guest `LoadedApk` 与 `Application` 引导、Activity/Service/ContentProvider/BroadcastReceiver 四大组件路由、PendingIntent 与 `JobScheduler`、存储隔离（`dataId`）、系统服务门面、最近任务栏独立卡片及 `/proc/self/*` 隐身机制。 |
| [self-hook-design.md](self-hook-design.md) | **Self-Hook 与脚本引擎设计**：ART Java Hook（`ManagedHook`、`ExecuteNterpImpl` 汇编解释器加速、GC Root 读屏障、Caller-Sensitive 栈归属）、ARM64 Native Hook（指令重定位规划、信号线程停顿、`mremap` W^X 匿名页原子发布）、`fixed_libc` 双页 10 函数路径包装器，以及受限 TypeScript（`RestrictedAst`）编译器与解释器。 |
| [guest-compatibility.md](guest-compatibility.md) | **Guest 应用兼容性分层与基线**：从第一性原理拆解的 L1–L13 兼容性契约矩阵、真机多应用实测基线以及已知边界（Split APK、裸 `svc #0`、GMS 强依赖）。 |
| [launch-performance.md](launch-performance.md) | **Guest 启动性能架构与优化**：冷/温启动四阶段关键路径拆解、Nterp 汇编解释器替代 Switch 解释器（10x–30x 加速）、免网关直达启动、`InstallProviderIndex` 索引等六大核心优化与耗时基线。 |
| [hardening-and-runtime-performance.md](hardening-and-runtime-performance.md) | **加壳/加固兼容与运行期性能优化方案**：商业加壳/加固应用（一代/二代壳、Dex2C/VMP、`svc #0`、SSL Pinning）无差别运行与 Hook/抓包/透明代理测试的四层纵深方案，以及运行期严重卡顿（如网易云音乐）的六大根因定位与优化路线图。 |
| [thin-shim-transformation.md](thin-shim-transformation.md) | **薄壳化改造方案（厚重 broker → VA 式透传）**：基于真机日志+代码审计量化 30s 冷启动与长尾兼容性的结构性根因（`:authority` 独立进程坐热路径、provider 单次访问 2~3 跳 IPC、286 处 hook、~42 个重写子系统），给出逐子系统「降级为透传」的改造清单、执行顺序，以及「默认薄壳透传 / 可选厚重」双模式架构（共享核心 + 子系统双实现 + 按客体启动时选定）与落地顺序。 |
| [agent-mcp-design.md](agent-mcp-design.md) | **AI Agent 控制面与 MCP 规范**：免认证、免配对 stdio MCP 接入，42 个当前工具（目标选择、内省、动态 Hook、主动调用、存储/网络审计、UI 驱动）及操作闭环。 |
| [evidence/README.md](evidence/README.md) | **测试证据输出目录说明**：供 `android/scripts/` 自动化验证脚本写入本地临时运行产物（不纳入长久版本堆积）。 |
