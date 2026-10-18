# Tripoder 文档索引

2026-10-16 整理。**先读 [../task.md](../task.md)**：它只描述当前状态和下一步。本目录文档分为“当前有效”“设计与规格”“历史快照”三类；历史快照里的构建 SHA、通过项只对应当时的构建，不代表当前制品。

## 1. 当前状态与交接

| 文档 | 内容 |
|---|---|
| [../task.md](../task.md) | 当前目标、阻塞、待办、续接命令、并行工作协调 |
| [self-hook-native-upgrade-2026-10-17.md](self-hook-native-upgrade-2026-10-17.md) | Native 入口生命周期、错误/资源账目、地址约束改造；本轮验收与性能限制 |
| [self-hook-native-followup-2026-10-17.md](self-hook-native-followup-2026-10-17.md) | CPU 5 受控性能对照、739 项 ART/GC 定向复现，以及仍未闭合的门槛 |
| [launch-performance-plan-2026-10-18.md](launch-performance-plan-2026-10-18.md) | 客体启动慢的真机根因（全局解释、点击才 fork、重复校验、串行启动）与分阶段方案、验收门槛（未实施） |
| [runtime-handoff-2026-10-16-h37.md](runtime-handoff-2026-10-16-h37.md) | 最近一次详细交接（H37：GC root、进程槽复用、Context 停止点） |
| [self-hook-acceptance-plan-2026-10-16.md](self-hook-acceptance-plan-2026-10-16.md) | 验收矩阵（旧快照，A1 已 236 通过，待更新） |
| [hok-compatibility.md](hok-compatibility.md) | 王者荣耀兼容性（第一阶段结论 + 后续阶段门槛） |
| [runtime-pending-intents-plan.md](runtime-pending-intents-plan.md) | 王者所需 PendingIntent/Alarm 实施边界 |
| [evidence/](evidence/) | 逐构建的原始证据；每个目录对应一次具体运行 |

## 2. 设计与规格（当前有效）

**总体架构**
- **[current-architecture.md](current-architecture.md)：现在的进程、包、控制链路、操作模型和构建。先读这一篇；其他文档与它冲突时以它为准。**
- 设计动机与边界：[tripoder-architecture.md](tripoder-architecture.md) · [execution-contract.md](execution-contract.md) · [tripoder-roadmap.md](tripoder-roadmap.md) · [sandbox-ai-transformation-plan.md](sandbox-ai-transformation-plan.md) · [device-baseline.md](device-baseline.md)
- TypeScript SDK / 模拟器契约（旧实验运行器时代，SDK 部分仍有效）：[architecture.md](architecture.md) · [ai-workflow.md](ai-workflow.md)

**自研 Hook 核心（`self-hook-*`）**
- 总览与启动：[self-hook-design.md](self-hook-design.md) · [self-hook-runtime-bootstrap.md](self-hook-runtime-bootstrap.md) · [self-hook-process-interpreter.md](self-hook-process-interpreter.md) · [self-hook-script-control.md](self-hook-script-control.md)
- ART/Java：[self-hook-art-design.md](self-hook-art-design.md) · [self-hook-managed-methods.md](self-hook-managed-methods.md) · [self-hook-framework-access.md](self-hook-framework-access.md) · [self-hook-reflection-view.md](self-hook-reflection-view.md) · [self-hook-caller-coverage.md](self-hook-caller-coverage.md) · [self-hook-caller-index.md](self-hook-caller-index.md)
- Native：[self-hook-native-export.md](self-hook-native-export.md) · [self-hook-native-h2a.md](self-hook-native-h2a.md) · [self-hook-native-thread-park.md](self-hook-native-thread-park.md) · [self-hook-page-publication.md](self-hook-page-publication.md) · [self-hook-noncooperative-publication.md](self-hook-noncooperative-publication.md)
- 固定 libc / 存储：[self-hook-fixed-libc.md](self-hook-fixed-libc.md) · [self-hook-managed-storage.md](self-hook-managed-storage.md)
- 适配与容量：[self-hook-adapters.md](self-hook-adapters.md) · [self-hook-application-adapter.md](self-hook-application-adapter.md) · [self-hook-entry-ownership.md](self-hook-entry-ownership.md) · [self-hook-product-capacity.md](self-hook-product-capacity.md)

**Runtime（`runtime-*`）**
- 生命周期与组件：[runtime-launch-design.md](runtime-launch-design.md) · [runtime-application-design.md](runtime-application-design.md) · [runtime-activity-design.md](runtime-activity-design.md) · [runtime-activity-construction.md](runtime-activity-construction.md) · [runtime-activity-flags.md](runtime-activity-flags.md) · [runtime-service-startup.md](runtime-service-startup.md) · [runtime-provider-design.md](runtime-provider-design.md) · [runtime-receiver-design.md](runtime-receiver-design.md) · [runtime-start-history-design.md](runtime-start-history-design.md) · [runtime-task-reclamation.md](runtime-task-reclamation.md) · [runtime-multi-app-and-stealth-analysis.md](runtime-multi-app-and-stealth-analysis.md)
- 任务与调度：[runtime-jobs-design.md](runtime-jobs-design.md) · [jobscheduler-implementation-plan.md](jobscheduler-implementation-plan.md) · [runtime-pending-intents-plan.md](runtime-pending-intents-plan.md)
- 系统服务：[runtime-system-services.md](runtime-system-services.md) · [runtime-package-design.md](runtime-package-design.md) · [runtime-storage-design.md](runtime-storage-design.md) · [runtime-network-design.md](runtime-network-design.md) · [runtime-telephony-design.md](runtime-telephony-design.md) · [runtime-audio-design.md](runtime-audio-design.md) · [runtime-notifications-design.md](runtime-notifications-design.md) · [runtime-device-observation-design.md](runtime-device-observation-design.md) · [runtime-system-broadcast-design.md](runtime-system-broadcast-design.md) · [runtime-shortcut-capability.md](runtime-shortcut-capability.md)
- 工作台与控制：[runtime-workbench-design.md](runtime-workbench-design.md) · [runtime-hook-design.md](runtime-hook-design.md) · [external-agent-control-design.md](external-agent-control-design.md)
- 阶段设计：[runtime-p1-design.md](runtime-p1-design.md) · [runtime-p2-plus-plan.md](runtime-p2-plus-plan.md)

## 3. 历史快照（只读，不代表当前状态）

| 文档 | 说明 |
|---|---|
| [task-history-h25-h36-2026-10-16.md](task-history-h25-h36-2026-10-16.md) | 原 task.md 的 H25–H36 内容（2026-10-16 迁出） |
| [task-history-before-h25-handoff-2026-10-16.md](task-history-before-h25-handoff-2026-10-16.md) | H25 之前的 task.md |
| [self-hook-handoff-2026-10-15.md](self-hook-handoff-2026-10-15.md) | 早期详细交接（顶部 H25，尾部 H3h 为明确历史） |
| [jobscheduler-handoff-2026-10-16-h34.md](jobscheduler-handoff-2026-10-16-h34.md) · [h35](jobscheduler-handoff-2026-10-16-h35.md) | Job 阶段交接 |
| [implementation-status-2026-10-15.md](implementation-status-2026-10-15.md) | 2026-10-15 实施状态 |
| [android-validation.md](android-validation.md) · [guest-validation.md](guest-validation.md) · [workbench-validation.md](workbench-validation.md) · [ui-validation.md](ui-validation.md) · [project-ui-validation.md](project-ui-validation.md) · [ui-runtime-preview.md](ui-runtime-preview.md) | 0.1.0–0.5.0 旧 SDK/运行器验收记录 |
| [isolation-backend-feasibility.md](isolation-backend-feasibility.md) | QEMU/完整 Android 后端探索，**路线已冻结**，不计入主线完成度 |
| [guest-runtime.md](guest-runtime.md) · [../android/README.md](../android/README.md) | 旧 `dev.tripoder.hooklab` 实验应用的 `:guest` 运行器与构建方法（已冻结，不进入产品） |

## 4. 维护约定

- `task.md` 只保留当前状态；每次交接把旧段落迁到对应历史文件，不改写、不删除。
- 历史文档的结论必须标注所绑定的构建或日期，不得跨构建追认。
- 新增证据目录不覆盖失败目录；源码变化后，旧证据只作历史记录。
- 单个文档超过 2000 行时按职责拆分。
