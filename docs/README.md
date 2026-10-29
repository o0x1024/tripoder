# Tripoder 文档索引 (Documentation Index)

本目录收敛了 Tripoder（免 Root 应用虚拟化沙箱与 AI 原生动态安全测试平台）的核心架构、契约规范与实施计划文档。所有过时的双模式（`THIN`/`THICK`）历史文档与过程性排障碎片均已裁剪合并。

---

## 1. 核心架构与第一层统一最小底座 (Level 1 Baseline)

| 文档 | 内容概述 |
| --- | --- |
| **[implementation-plan.md](implementation-plan.md)** | **【核心】架构梳理与分阶段实施计划**：「统一最小承载底座（Level 1）+ 按需策略扩展（Opt-in）」双层架构总纲、全量代码模块盘点、断路缺口分析与 Phase 0–3 实施路线图。 |
| [current-architecture.md](current-architecture.md) | **总体架构与第一性原理边界**：单 APK (`dev.tripoder.engine`) 四类进程模型（主进程 UI / `:authority` / `:recovery` / `:slot0..7`）、Space × App × Project 模型、免网关单跳启动链路及工程目录结构。 |
| [single-runtime.md](single-runtime.md) | **单运行时与最小底座收敛规约**：废除 THIN/THICK 双模式，启动期移除 12 个 Manager 家族的 1,700+ 项固定 Hook 与默认 Seccomp，收敛至 ~220–264 项最小容器骨架绑定、保留 ART JIT 的执行规则与验证入口。 |
| [execution-contract.md](execution-contract.md) | **控制面与执行面状态机契约**：APK 安装状态机（`staging -> published -> committed`）、进程槽位与 `ProcessSession`、四大组件租约（Lease）、幂等异步操作（`Operation`）、一次性回调令牌（`CallbackCapability`）及退出排空围栏（`ShutdownFence`）。 |
| [runtime-design.md](runtime-design.md) | **Runtime 虚拟化核心设计**：真实 linker 搜索路径与 `LoadedApk`/`Application` 引导、四大组件路由、`RuntimeReceiverProxy` 显式代理、`RuntimeGuestPayload` 延迟解包、存储隔离（`dataDir <= 109B` 契约）及 `/proc/self/*` 隐身机制。 |
| [self-hook-design.md](self-hook-design.md) | **Self-Hook 与脚本引擎设计**：ART Java Hook（最小底座一次发布保留 JIT vs. 按需业务 Hook 局部反优化、GC Root 读屏障、栈归属隐藏）、ARM64 Native Hook（指令重定位、有界线程停顿、W^X `mremap` 原子发布）、`fixed_libc` 双页 11 入口包装器（含 `__fchmodat`）及 `RestrictedAst` 编译器。 |
| [runtime-crash-recovery.md](runtime-crash-recovery.md) | **引擎崩溃后的有界恢复门禁**：独立 `:recovery` 进程、恢复预算与 `UNKNOWN` 范围门禁。 |
| [guest-compatibility.md](guest-compatibility.md) | **Guest 兼容性分层与真机基线**：L1–L13 兼容性契约矩阵、核心商业应用真实登录与业务闭环（支付宝、淘宝、微博、京东、美团、美团外卖、招行、拼多多、腾讯视频、芒果 TV）及 27 款主流第三方应用真机大盘基线（实际可用率 81.5%）。 |

---

## 2. 第二层按需扩展能力与 AI Agent 控制面 (Opt-in Capabilities & MCP)

| 文档 | 内容概述 |
| --- | --- |
| [agent-mcp-design.md](agent-mcp-design.md) | **AI Agent 全局控制面与 MCP 规范**：stdio 与 Streamable HTTP `/mcp` 双入口、显式目标作用域（`installGeneration` / `processGeneration`）、CAS 并发保护与 `UNKNOWN` 对账契约。 |
| [mcp-tool-groups.md](mcp-tool-groups.md) | **MCP 工具分组 v3 与契约边界**：公开工具分组、Action 分支校验、对象内省与错误契约。 |
| [mcp-network-implementation.md](mcp-network-implementation.md) | **stdio / HTTP MCP 网络接入规约**：局域网 HTTP `/mcp` 端点、执行预算與并发槽位规则。 |
| [runtime-observation.md](runtime-observation.md) | **按需运行时观察与代码快照**：`probes_control`、`startupHook`、动态 ClassLoader/DEX 发现与内存快照导出。 |
| [passive-network-sessions.md](passive-network-sessions.md) | **按需被动 TLS 采集、Native 缓冲区捕获与 HTTP/1 分帧**：`ctx.native.captureBuffer` 有界内核读取、OpenSSL `SSL_read/SSL_write` 会话采集、HTTP/1.1 Keep-Alive 实时分帧与 HAR 1.2 导出。 |
| [file-pull.md](file-pull.md) | **通用文件导出与断点续传（File Pull）**：手机端 `file_export`/`file_query` 与电脑侧 `file_pull` 断点续传、SHA-256 校验规约。 |
| [scenario-gap-analysis.md](scenario-gap-analysis.md) | **六大业务场景能力评估与演进缺口分析**：面向 Bug Bounty/SRC、隐私合规、功能测试、恶意软件分析、黑灰产情报与电子数据取证的能力矩阵与治理护栏。 |

---

## 3. 证据与构建产物说明

| 目录 | 内容概述 |
| --- | --- |
| [evidence/README.md](evidence/README.md) | **测试证据输出目录说明**：供自动化验证脚本写入本地逐构建运行回执与验证摘要。 |
