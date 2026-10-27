# Tripoder 文档索引 (Documentation Index)

本目录收敛了 Tripoder（免 Root 应用虚拟化沙箱与 AI 原生动态安全测试平台）的核心架构与工程规范文档。

## 当前架构与契约

| 文档 | 内容概述 |
| --- | --- |
| [current-architecture.md](current-architecture.md) | **总体架构与第一性原理边界**：单 APK (`dev.tripoder.engine`) 三类进程模型（主 UI / `:authority` / `:slot0..7`）、Space × App × Project 模型、免网关单跳启动链路及工程目录结构。 |
| [single-runtime.md](single-runtime.md) | **单运行时收敛**：THIN/THICK 双模式已移除，普通客体共用同一套组件/存储/设备服务/Hook 发布规则；环境模拟改由 persona/env_mock 显式配置。schema 迁移、已删除接口（`set_mode` 等）与验证入口的权威说明。 |
| [execution-contract.md](execution-contract.md) | **控制面与执行面状态机契约**：APK 安装状态机（`staging -> published -> committed`）、进程槽位与 `ProcessSession`、四大组件租约（Lease）、幂等异步操作（`Operation`）、一次性回调令牌（`CallbackCapability`）及退出排空围栏（`ShutdownFence`）。 |
| [runtime-design.md](runtime-design.md) | **Runtime 虚拟化核心设计**：Guest `LoadedApk` 与 `Application` 引导、Activity/Service/ContentProvider/BroadcastReceiver 四大组件路由、PendingIntent 与 `JobScheduler`、存储隔离（`dataId`）、系统服务门面、最近任务栏独立卡片及 `/proc/self/*` 隐身机制。 |
| [self-hook-design.md](self-hook-design.md) | **Self-Hook 与脚本引擎设计**：ART Java Hook（`ManagedHook`、`ExecuteNterpImpl` 汇编解释器加速、GC Root 读屏障、Caller-Sensitive 栈归属）、ARM64 Native Hook（指令重定位规划、信号线程停顿、`mremap` W^X 匿名页原子发布）、`fixed_libc` 双页 10 函数路径包装器，以及受限 TypeScript（`RestrictedAst`）编译器与解释器。 |
| [guest-compatibility.md](guest-compatibility.md) | **Guest 应用兼容性分层与基线**：从第一性原理拆解的 L1–L13 兼容性契约矩阵、真机多应用实测基线以及已知边界（Split APK、裸 `svc #0`、GMS 强依赖）。 |
| [launch-performance.md](launch-performance.md) | **Guest 启动性能架构与优化**：冷/温启动四阶段关键路径拆解、Nterp 汇编解释器替代 Switch 解释器（10x–30x 加速）、免网关直达启动、`InstallProviderIndex` 索引等六大核心优化与耗时基线。 |
| [hardening-and-runtime-performance.md](hardening-and-runtime-performance.md) | **加壳/加固兼容与运行期性能优化方案**：商业加壳/加固应用（一代/二代壳、Dex2C/VMP、`svc #0`、SSL Pinning）无差别运行与 Hook/抓包/透明代理测试的四层纵深方案，以及运行期严重卡顿（如网易云音乐）的六大根因定位与优化路线图。 |
| [runtime-crash-recovery.md](runtime-crash-recovery.md) | **客体崩溃后的恢复围栏**：客体异常退出后的 fence/恢复状态机与重启规则。 |
| [agent-mcp-design.md](agent-mcp-design.md) | **AI Agent 控制面与 MCP 规范**：免认证、免配对 stdio MCP 接入，当前工具目录（目标选择、内省、动态 Hook、主动调用、存储/网络审计、UI 驱动）及操作闭环。 |
| [mcp-tool-groups.md](mcp-tool-groups.md) | **MCP 工具分组与契约修正记录**：对外工具分组、字段契约与逐次验收/恢复修正（按日期追加，含对旧结论的撤回说明）。 |
| [task-capability-mcp-exposure.md](task-capability-mcp-exposure.md) | **能力缺口 → MCP 工具暴露任务实施文档**：对照 `scenario-gap-analysis.md` 的三阶段（P0–P2）任务拆解，含涉及文件、新增工具/action、验收标准、排期依赖与整体退出标准。 |
| [scenario-gap-analysis.md](scenario-gap-analysis.md) | **全场景能力评估与演进缺口分析**：面向 Bug Bounty/SRC 挖掘、隐私合规检测、功能测试、恶意软件分析、黑灰产对抗及移动端电子数据取证六大场景的现状匹配度、缺失能力清单、MCP 工具契约暴露设计（职责分层与风险护栏）与架构演进路线图。 |

## 网络与文件传输

| 文档 | 内容概述 |
| --- | --- |
| [mcp-network-implementation.md](mcp-network-implementation.md) | 网络 MCP 工具的实现与请求/响应契约。 |
| [passive-network-sessions.md](passive-network-sessions.md) | 被动网络会话采集的范围与边界。 |
| [native-tls-observation.md](native-tls-observation.md) | native TLS 缓冲区观测的验收记录与边界。 |
| [mtls-observation.md](mtls-observation.md) | 自有应用 mTLS 请求的被动观测最小闭环验收记录。 |
| [http1-keepalive.md](http1-keepalive.md) | HTTP/1.1 keep-alive 行为说明。 |
| [file-pull.md](file-pull.md) | 电脑侧 `file_pull`/`file_pull_status` 文件下载回执与恢复规则。 |
| [runtime-observation.md](runtime-observation.md) | 运行时可观测性事件/证据链路说明。 |

## 历史设计与验收记录（已被单运行时收敛取代）

以下文档描述的 THIN/THICK 双模式架构已不再是当前行为，权威现状见 [single-runtime.md](single-runtime.md)；保留仅作为历史设计背景与当时的测量/验收数据。

| 文档 | 内容概述 |
| --- | --- |
| [thin-shim-transformation.md](thin-shim-transformation.md) | 薄壳化改造方案（厚重 broker → VA 式透传）的根因量化与改造清单，曾提出的「默认薄壳透传 / 可选厚重」双模式架构已被单运行时取代。 |
| [thin-native-performance.md](thin-native-performance.md) | THIN 模式启动性能减法的实现与验收记录。 |
| [thick-startup-p0-p1.md](thick-startup-p0-p1.md) | THICK 模式启动减法 P0/P1 的实现、真机对比与验收记录。 |
| [Tripoder-兼容性测试报告.md](Tripoder-兼容性测试报告.md) | 2026-10-23 THIN 模式下 123 个第三方应用的迁移兼容性实测基线。 |
| [Tripoder-THICK兼容性报告.md](Tripoder-THICK兼容性报告.md) | THICK 全量模式兼容性复测，与上表 THIN 结果逐包对照。 |

## 证据与排障记录

| 文档 | 内容概述 |
| --- | --- |
| [evidence/README.md](evidence/README.md) | **测试证据输出目录说明**：供自动化验证脚本写入本地逐构建运行产物（不纳入长久版本堆积）。 |
| [evidence/*.md](evidence/) | 单次排障/复核记录（如特定客体启动失败分析），按文件名内日期定位，不保证随后续改动更新。 |
