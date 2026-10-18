# Tripoder 当前架构

2026-10-18。本文只描述**现在**的代码结构与运行形态，不记录进展或通过项（进展见 [../task.md](../task.md)，逐构建结果见 [evidence/](evidence/)）。与本文冲突的旧文档以本文为准；设计动机与不可越过的边界仍见 [tripoder-architecture.md](tripoder-architecture.md)。

## 一个 APK，三类进程

产品是单一 APK `dev.tripoder.engine`（2026-10-17 由原工作台与引擎两包合并）。所有进程同一 UID。

| 进程 | 内容 | 源码 |
|---|---|---|
| 主进程 | 工作台 UI（应用 / 项目 / 设置）、项目脚本与配对状态、外部 Agent 前台入口 `AgentControlService` | `android/workbench` |
| `:authority` | Broker 与唯一状态存储：空间、安装、进程会话、组件路由、执行契约（操作账本）；控制服务 `RuntimeControllerService`、PendingIntent 入口 | `android/engine` + `android/runtime` |
| `:slotN` | 客体进程：装载器、Application/组件、固定适配与用户 Hook（自研 Java/Native 核心） | `android/runtime` + `android/self-hook` |

**同 UID 的含义**：进程划分提供崩溃隔离，并把客体与权威状态分开。它**不是**安全边界：客体代码能读取工作台私有数据，用户已明确接受这一点。因此控制链路上不做身份证明；`ControllerTrust` 只按调用进程名防止客体代码误取控制租约。

## 控制链路

```
工作台 (主进程) ──Binder: ControlWire──▶ RuntimeControllerService (:authority) ──▶ RuntimeBroker
外部 Agent ──MCP/签名──▶ AgentControlService (主进程) ──同一控制服务──▶ RuntimeAgentSessions
```

- **连接与租约**：控制端以一个存活 Binder 建立 lease（`ControllerLease`），同一时间只有一个控制端。Binder 死亡即撤销租约。runtime 内多个 ledger 用 lease 隔离过期控制者的迟到请求。lease 是正确性机制，不是安全机制。
- **调用者**：同 UID 下不做身份证明，命令链不传 uid。`ControllerTrust.OPERATIONS` 是工作台操作的所有者（Agent 会话用自己的 subject，彼此隔离）；`ControllerTrust.WORKBENCH_CALLER` 表示工作台按外部调用者启动客体，只能打开导出的 Activity，是产品规则。
- **启动**：`prepareLaunch` / `prepareApplicationLaunch` 一次 Binder 调用完成：校验目标、清理已死入口、唤醒已在运行的应用（返回 `woken`），否则预留槽位、等客体 Application READY，在同一事务里检查"没有打开的根"并签发根 ticket，返回物理入口 `intent`（`:slotN` 的替身，`NEW_TASK|MULTIPLE_TASK`）。工作台直接 `startActivity`；不在前台或启动抛错时调用 `abandonLaunch` 交还入口（ticket 置 REVOKED，操作 FAILED `LAUNCH_NOT_DISPATCHED`），晚到的启动只会遇到被拒的 ticket。哪个操作创建了哪个根，由 `activity_launches`（`callerKind=CONTROLLER`）记录；2026-10-18 起没有 Gateway Activity、一次性 token 或 `controller_launches` 表（schema 33 删除）。
- **命令**：`RuntimeControllerCommands` 是生产命令的唯一分发点（安装、启动准备、停止、移除、删除应用、删除空间、项目绑定、Hook、查询等）。没有故障注入或原始 worker 命令。

## 空间、应用与项目

- **空间**是隔离单位：每个应用在每个空间里有一份安装 `(space, package, generation)` 和自己的数据（`dataId`，升级时保留）；跨应用交互限于同一空间。引擎只存空间 id，名称在工作台。
- **应用**是同一 package 在各空间安装的合称，只存在于 UI。
- **项目**的源码在工作台，绑定 `project_refs` 在 `:authority`，精确到一次安装。项目只能在同一应用内换空间或新版本（`PROJECT_PACKAGE_CHANGE_UNSUPPORTED`）；换应用要新建项目。
- 联动：升级或从某空间移除 → 绑定 STALE，项目保留；删除应用 → 所有空间的安装与绑定到它的项目；删除空间 → 空间内所有安装与绑定到该空间的项目，空间行最后删除。两种删除都按"已删的不再删"推进，部分失败（`*_PARTIAL`）后再次执行即可收尾；应用已不在任何空间时，删除应用只清理残留绑定（项目页提供入口）。
- 点击应用图标先定空间（当前筛选 → 记住的默认 → 唯一 → 询问），再看该空间里已绑定的项目。
- **Agent 与 UI 互斥**：外部 Agent 会话持有某项目或应用的控制权时，UI 对同一目标的变更会被 `requireUiControl` / `requireApplicationControl` 拒绝。

## 操作模型

1. 有副作用的命令都带客户端生成的操作 ID。引擎按"ID + 请求摘要 + 调用者"接纳，结果持久化在执行契约中（SUCCEEDED / FAILED / UNKNOWN）。同 ID 同内容只返回原回执，不会重复执行。规范见 [execution-contract.md](execution-contract.md)。
2. Binder 返回不等于结果。超时或断连进入"待确认"状态，之后只能 `query`，不自动重试副作用。
3. **引擎判断冲突**：同一目标上的并发请求由引擎按安装代次、租约和组件状态接受或拒绝。例如删除应用会先停止该应用的进程再卸载，所以运行中或启动中的应用可以直接删除。
4. **工作台日志**（`WorkbenchOperations`）：每个未确认操作一条持久记录（`op-<id>`），各自独立查询，互不阻塞。只有同一项目或应用的请求**仍在 Binder 上提交**时，才拒绝第二次提交（防止连点）。记录要持久化，是因为部分本地后续步骤依赖最终结果，例如删除应用成功后要删掉对应项目的脚本源码。
5. 启动入口关闭（`closeLaunchEntry`）和未知启动的核验（`launchRecovery`）是处理 UNKNOWN 启动的两条显式路径，都不会重放原操作。

## 源码与构建

| 目录 | 角色 | 进入哪个包 |
|---|---|---|
| `android/runtime` | 生产 runtime：契约、存储、装载、组件路由、系统服务语义 | 产品 |
| `android/self-hook` | 自研 Java/Native Hook 核心 | 产品（含 `libtripoderselfhook.so`） |
| `android/engine` | `:authority` 的控制面：控制服务、命令、租约、Agent 会话 | 产品 |
| `android/workbench` | 工作台 UI | 产品 |
| `android/control` | 控制线协议 `ControlWire` | 产品 + control-test |
| `android/control-test` | instrumentation 验收（targetPackage = 产品包） | 单独测试 APK |
| `android/runtime-probe`、`runtime-fixtures`、`guest-fixture` | 独立验收程序与自有测试 APK | 各自单独构建 |
| `src/`（仓库根） | TypeScript SDK、模拟器、草稿 AST 解释器 | 解释器相关部分进产品 |

产品由 `android/scripts/build-workbench.py` 构建，不走 Gradle。`settings.gradle` 只剩自有测试 APK `guest-fixture`。旧 0.5 实验应用 `android/app`（`dev.tripoder.hooklab`，Frida Gadget 后端）及其 Frida 代理工具已于 2026-10-18 退役，历史见 git。脚本生成组件槽位（activity/provider/receiver/job slot）与清单，编译 native 核心，输出 `product/build/tripoder.apk` 和 `tripoder-control-test.apk`。完整参数见 [../task.md](../task.md) 的重建命令。

## 文档怎么读

- 现在是什么：本文、[../task.md](../task.md)。
- 为什么这样设计、边界在哪：[tripoder-architecture.md](tripoder-architecture.md)、[execution-contract.md](execution-contract.md)、`runtime-*` / `self-hook-*` 设计文档。
- 过去发生了什么：带日期的 handoff / task-history / validation 文档，以及 [evidence/](evidence/)。它们只对应当时的构建。
