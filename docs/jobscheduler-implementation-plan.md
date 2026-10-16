# JobScheduler 落地计划（王者阻塞）

依据 docs/runtime-jobs-design.md。目标：真实平台调度 + 真实 JobService 生命周期 + 冷恢复，逐字段保留/拒绝，不自制定时器、不空列表假成功。

## H37 当前交接（2026-10-16）

Job的H35通过证据不变；后续GC root真实压力与跨安装旧入口/进程槽复用已验收。当前目标应用仍未正常运行，最新Context回归在前置失败历史断言停止；后续精确状态与命令以 [H37交接](runtime-handoff-2026-10-16-h37.md) 为准。当前P4/正式产品和王者PendingIntent/Alarm仍待完成。

## H35 当前状态（2026-10-16）

真实 Job 生命周期和 worker 冷恢复已获整轮证据：`jobs-04-no-dump` 15 项、同 UID 无 Hook 对照 2 项、独立调度与资源退休 69 项。详见 [H35 交接](jobscheduler-handoff-2026-10-16-h35.md)。

- 以 `jobFinished(true)` RPC 回执 + 精确 pending 版本 + 非 executing 状态为 kill 前置证据；同一 version 的两次真实 attempt 来自不同进程代次，第二次由系统实际派发。
- 固定设备 `dumpsys jobscheduler` 的 Ready 计算会执行 controller evaluation，改变 time constraint；不再在重试观察窗口调用它。保留此前失败，不将所有 H34 失败倒推为此原因。
- 资源退休仅覆盖已永久 fenced 的版本；系统 pending、AMS service/process 消失与账本无 LIVE instance/owner 同时成立，再复核版本快照后退休，不改 UNKNOWN。
- 微信/王者已实测，不能继续称 JobScheduler 为“唯一阻塞”。安装内绝对数据库路径已修复并完整 P3 358 通过；微信随后缺少的三类电源状态广播已接入，242项回归通过；最新Flutter同包Context已实现/构建但待真机。王者下一处是客体 targetSdk/进程兼容状态和真实 PendingIntent/Alarm 路由。
- 仍缺 authority 实际死亡恢复、取消 UNKNOWN 收口、最新源 P4/正式控制与最终微信登录/王者业务/正常动态 Hook 无崩溃验收。

## H34 历史状态（2026-10-16）

真实 JobService 与客体 API 已接线，尚未完成冷恢复验收。最新细节、精确制品、失败证据和命令见 [H34 交接](jobscheduler-handoff-2026-10-16-h34.md)。本节优先于下方 H33 历史状态。

- 已新增固定 JobFrameworkAccess、版本 bootstrap、attempt/callback Binder 账本、真实 start/stop/finish 及 RPC 回执，schema 28；26 项能力/2215 固定绑定。
- 修复默认图标接口和 Job start ANR。当前冷 start 1.169s，物理准备7.791s；后者在18s绑定预算阶段，前者在8s ACK预算阶段。
- jobs-05/06 未观察第二次系统回调，整轮失败；同期无意外 Java/Native fatal、ANR=false。MIUI 自启动已开启，05不再拒绝RestartService，剩余原因尚未确认。
- 下一步在 FINISH_RPC_RETURNED 之后、kill之前和0–30秒窗口捕获真实 pending/reasons，区分系统重试与恢复代码问题。不能用超时后夹具 cancelAll 导致的 unknown(job44) 推断根因。
- Job 资源退休、取消UNKNOWN收口、authority死亡恢复、当前P4/正式控制/第三方业务验收仍未完成。H33旧通过不能跨构建追认。

## 复用
- RuntimeServices / RuntimeServiceSlots（物理组件槽、AppComponentFactory 创建、BootstrapProvider PID/slot/peer 校验、claimComponent）。
- RuntimeStore / operations 持久账本；安装身份、authority、ProcessSupervisor。
- RuntimeJobInfo（已实现转换，15 项）。

## 新增（按职责）
1. **RuntimeJobGateway/Slots**：独立 manifest JobService gateway，声明 BIND_JOB_SERVICE；onCreate/onBind 仅物理引擎身份。
2. **JobLedger**：逻辑键=install+namespace+jobId；调度版本 nonce；执行 attempt nonce + 系统 callback Binder。PREPARED 落盘→平台 RESULT_SUCCESS 确认；ACK 丢失 UNKNOWN+查真实系统 JobInfo；墓碑防迟到。
3. **RuntimeJobBroker**：schedule/replace/cancel/cancelAll/cancelInAllNamespaces 持久 mutation owner；安装级互斥；竞争报处理中，不重放队列。
4. **RuntimeJobScheduler**：客体可见 API；namespace 由 space/install 派生，不透传客体字符串；查询返回自身逻辑 JobInfo。
5. **RuntimeJobs**：gateway 收到真实 onStartJob 的 JobParameters→按版本凭据向 authority 准入→有效版本进不可变 worker→创建逻辑 JobService 委托本次回调。onStopJob/onNetworkChanged 按版本+真实 callback Binder 归并 attempt，对象级 capability，旧视图不结束新 attempt。
6. **RuntimeSelfHookJobs / JobFrameworkAccess**：复用自研固定 Framework registry，提供参数视图与 JobService 必需入口；旧 `agent/jobs.ts` 草案不再作为实现后端。
7. **manifest 槽生成器**：两套 APK 共用 Job gateway 槽（扩展 runtime_activity_slots.py 同款机制）。

## 记录三态（不命名为业务成功）
FINISH_REQUESTED / CALLBACK_RPC_RETURNED / SYSTEM_RETIREMENT_OBSERVED。资源退休靠 pending 消失+运行/物理服务退场+必要进程死亡，不把 UNKNOWN 改成功。

## 冷启动 deadline 风险
必须实测固定设备真实 start/stop deadline 与初始化耗时（冷安装约 8–12s，见 h25 样本）。adapter 15s/Application 10s 不能当 Job 支持证据。不满足则明确拒绝，不退自制定时器。

## 分批验收（自研夹具）
两空间同 namespace/jobId 隔离；同服务并行 job；系统真回调+jobFinished；取消执行中 job；替换后迟到回调；Worker/authority 死亡恢复；错误版本/跨空间/未知组件拒绝；平台拒绝/未知不写成功。通过后再复测王者固定 APK。

## 顺序
先持久调度+取消竞争+真实查询故障验收 → 再 start/stop/finish+冷恢复 → 复测王者。


## H33 调度内核进展（2026-10-16）

最终验收仍是微信、王者荣耀实际运行，以及正常 Java/Native Hook 不导致应用崩溃或 ANR。本批只完成前置调度内核，不代表客体 JobScheduler 或第三方应用已可用。

已实现：
- `JobComponentLedger` 纯组件/进程槽租约，不创建 worker session、bind/LOAD 或启动 watchdog；纳入 `retainedStub`、进程容量和安装升级/卸载引用检查。
- `JobLedger` 不可变版本、安装级 mutation owner、取消目标快照和永久准入墓碑；schema 26→27 显式事务升级，复用 operations。
- `RuntimeJobBroker` 在锁外调用真实 Android namespace JobScheduler，完整 JobInfo 保留；同安装 mutation 竞争立即拒绝，不排队重放；替换 RESULT_FAILURE 保留旧版本。
- 查询以真实系统 pending 为准，校验 namespace/id/version/component/完整 Parcel，恢复逻辑 JobInfo；没有以账本代替系统返回。
- 调度丢 ACK 保留 UNKNOWN，只有匹配的真实 pending 版本能对账；缺失不能判成功。取消的 RPC 返回、业务完成、物理资源退出分开；未知取消仍持有 mutation owner。
- Hook 激活在同一事务中记录 session 与待执行 job 的首次 Hook generation/operation；新进程激活 Hook 不覆盖旧版本的恢复契约。
- 两套 APK 共用 `runtime_job_slots.py` 生成 8×4 个 BIND_JOB_SERVICE gateway 声明。

验收：独立自有包 `dev.tripoder.jobsprobe` 当前 65/65，通过真实平台提交 10 个 job，覆盖双空间、namespace、同服务并行、取消竞争、替换、ACK 丢失、磁盘重开、schema 升级、Hook 标记事务失败回滚及错误版本拒绝。证据 `docs/evidence/jobscheduler-scheduling-h33/attempt-03`。该包不含 Native Hook；不能用它证明 Hook 或真实 Job 回调稳定性。

尚未实现：客体 facade / RuntimeWire 接线、固定 JobParameters/JobService adapter、真实 onStart/onStop/jobFinished、attempt Binder 身份、冷恢复及物理资源退休。`RuntimeJobGateway` 当前是明确未启用的生命周期入口，客体仍不能提交任务；内部验收只提交 24 小时后的任务并立即取消。已提交过的组件不会仅因 pending 消失而释放。取消 ACK 丢失后的持久 owner，也需要后续物理生命周期证据才能安全收口。

下一批按以下顺序：先做固定平台 JobInfo/JobParameters/JobService 成员准入与真实 callback deadline 观测，再接版本准入→不可变 worker→逻辑 JobService→对象级 finish capability，最后开放客体 API。随后跑冷恢复/替换迟到/运行中取消/Hook 回归，再复测微信和王者的实际链路；P2 历史 pin 和设备 sticky 重启策略仍需独立解决。
