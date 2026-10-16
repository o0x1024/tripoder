# JobScheduler 的接入边界

**2026-10-16 H34 更新**：调度、客体 API 和真实 start/stop/finish 已接通；冷恢复第二次回调仍未通过。固定框架预备及物理 Manifest 快照已前移到绑定阶段，消除了已定位的8秒 start ANR。最新事实以 [H34 交接](jobscheduler-handoff-2026-10-16-h34.md) 为准；下方早期“未接入/未开放”段落为当时状态。

2026-10-15：JobInfo 参数转换已实现，15 项真机对象契约检查通过；调度账本与生命周期仍未接入，不向客体开放 JobScheduler。王者荣耀的 AndroidX Startup / WorkManager 在查询 JobScheduler 时明确失败，不能用空列表与假 schedule 成功推进初始化。

## 权威和执行

复用安装身份、authority、持久操作账本、ProcessSupervisor 和 AppComponentFactory。Android 负责约束判断、调度、真实 JobService Binder、onStartJob/onStopJob 与完成回执。Tripoder 负责逻辑 job 身份、物理 JobService 映射、不可变版本、取消及冷恢复。不得另写定时器冒充 Android JobScheduler。

物理 JobScheduler namespace 由 space/install 与客体 namespace 派生，不能把客体字符串直接当系统 namespace。对客查询返回其自身逻辑 JobInfo；取消全部也仅覆盖这个安装。平台调用成功、job 执行开始、业务完成是三种不同证据。

JobService 必须有独立的 manifest gateway 和持久组件租约，声明 BIND_JOB_SERVICE 权限；普通 Service 池不能直接复用，因为平台对调度服务的绑定权限和组件身份有独立要求。Android 创建并绑定真实 gateway，负责 JobServiceEngine、约束、调度和 Pending Job 生命周期。客体 JobService 作为逻辑组件，由同一 AppComponentFactory 创建，在实际平台回调中受控委托。不是另外编写定时器或制造 JobParameters。

### 审查修正：在参数到达后决定客体身份

原草案要求 Factory 直接返回客体 JobService，存在缺陷：instantiateService 的 Intent 可以为空，无法在创建 Application 前核验 job 版本。仅按物理 stub 最新租约选择客体，会让取消后的迟到创建误初始化新租约的应用。查询 pending jobs 为空也不能独自证明所有 Binder 创建消息已经排空。

因此固定 gateway 在 onCreate/onBind 阶段只具有物理引擎身份；收到真实 onStartJob 的 JobParameters 后，以其中的版本凭据向 authority 准入。退休/未知版本在任何客体构造前拒绝。有效版本才进入不可变 worker 身份，创建逻辑 JobService 并委托此次实际回调。已绑定其他身份的 gateway 不切换客体；组件槽仍受 pending job、运行实例及 AMS 存活引用保护。

物理 Service token、JobServiceEngine 和 Binder 始终属于 gateway。客体使用逻辑 Context/Application；onStartJob/onStopJob 的实际返回值回到平台，jobFinished 通过 exact 参数对象映射调用真实 gateway。onDestroy 由真实物理生命周期触发委托。普通 stopSelf/foreground/任意绑定不授予客体 JobService。此构成明确的组件代理模型，不能声称客体对象本身直接登记在 AMS 中。

## 版本与故障

逻辑键为安装 + namespace + jobId，调度版本另有 nonce。持久 PREPARED 在平台 schedule 前落盘；平台返回 RESULT_SUCCESS 才确认已接收。ACK 丢失保留 UNKNOWN，并查询真实系统 JobInfo 中的本次版本证明；不自动重复 schedule。替换同 ID 和 cancel 必须保留旧版本墓碑，防止迟到回调执行新 job。

真实 JobParameters 内的调度版本用于入口核验，但客体看到原始 extras、jobId 与逻辑 namespace。不能把私有路由凭据交给客体业务代码。JobService.jobFinished、工作项与网络更新必须绑定同一已接纳参数对象，不能仅凭 jobId 接受。

参数视图使用固定平台的必需 adapter；保留平台真实参数并与客体视图按对象身份关联。失败不伪造空参数。首批不支持工作项队列；JobServiceEngine 内部的同步/异步 ACK 与 guest 返回、finish 请求分开记录，没有确证的完成窗口保留 UNKNOWN。

进程死亡不等于 job 被取消；平台可按自身规则重调度。authority 恢复需核对持久身份和真实系统调度，不能仅因 worker DEAD 就释放 JobService 槽。安装卸载/升级也必须先处理这些引用。

## 分批验收

第一批明确支持非持久、无 URI/ClipData/JobWorkItem 的普通 JobInfo，保留已实现的时间、电量、空闲和网络约束。其余入口明确拒绝，不能默默丢字段。所有 public API 都有范围与拒绝策略，不能继承返回默认成功或 undefined 的空实现。

自研夹具必须验证：两个空间同 namespace/jobId 互不影响；同服务并行 job；系统真正回调及 jobFinished；取消正在执行 job；替换旧 job 后迟到回调；Worker 和 authority 死亡后的系统恢复；错误版本、跨空间与未知组件拒绝；平台拒绝与未知结果不会写成成功。之后才重试王者固定 APK，仍需继续实现其下一项实际缺失能力。

参考 [Android JobScheduler 平台接口](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/apex/jobscheduler/framework/java/android/app/job/JobScheduler.java)。系统 namespace 和平台调度只解决部分身份及调度问题，不能替代上述执行账本。

## 追加结构审查：实施前必须满足的约束

调度槽引用独立于运行 session。不能调用现有 prepare/reserveBroadcast 为未来任务预留进程：二者会启动客体或设置 30 秒 Application watchdog。调度仅持久保留 space/install/process/processStub/serviceStub，真实任务回调准入后才 reserve session + claimComponent。retainedStub/reserve/capacity、Catalog.requireUnusedMetadata 和安装发布事务的二次引用检查都要纳入 pending、running、mutation-unknown 三类 job 引用。

身份分三层：逻辑键、调度版本 nonce、执行 attempt nonce + 系统 callback Binder。同一周期/重试任务会多次执行同一版本。onStopJob/onNetworkChanged 的参数是后续 Binder 反序列化对象，不能凭其 Java 对象身份查最初参数；先按版本和真实 callback Binder 归并 attempt，再给每个客体参数视图建立对象级 capability。旧视图不得结束新 attempt。

jobFinished 公共调用只把消息放入主线程；内部 Binder 返回也可能对应系统对过期 callback 的忽略。因此记录 FINISH_REQUESTED、CALLBACK_RPC_RETURNED、SYSTEM_RETIREMENT_OBSERVED，不把它们命名为业务成功。业务结果不确定与资源能否退休分别处理；永久拒绝旧版本加真实资源退出证明可回收槽位，不能靠把 UNKNOWN 改为成功回收。

同逻辑键 schedule/replace/cancel 采用持久 mutation owner。平台调用在 broker 锁外，竞争操作明确报处理中，不新增重放队列。替换失败保留旧版本；新版本真实回调可能先于 schedule ACK，它可证明平台已接收。cancelAll 仅当前 namespace，cancelInAllNamespaces 才覆盖此安装全部 namespace。所有键的批量取消要有安装级互斥边界，不能遗漏同时新增的 job。

取消、替换不应仅凭 getPendingJobs 为空就认为运行服务已退出。墓碑防迟到准入；实际 pending 消失、运行/物理服务退场、必要进程死亡证明支撑资源退休。Worker DEAD 不等于取消，authority 死亡后要重新核对真实系统。JobService 声明单独验证 BIND_JOB_SERVICE、enabled、同安装、非 isolated/external，不能套用当前拒绝所有 service permission 的普通授权函数。

完整 JobInfo 字段必须逐项保留或拒绝，覆盖 priority/backoff/period/flex/transientExtras/prefetch/expedited、网络字节估计、debug/trace tag 等。不能仅复制几种约束。逻辑服务不得持有 gateway 的物理 token；final jobFinished/onBind/setNotification 与 stopSelf/foreground 都有明确控制。配额、耗电与系统归因属于引擎 UID。

冷启动仍有平台回调 deadline 风险：当前 adapter 上限 15 秒、Application 上限 10 秒不能直接作为 Job 支持证据。必须测量固定设备的真实 start/stop deadline 与初始化耗时；不能提前返回 true 冒充客体仍执行。若不满足，明确拒绝该能力，不能退到自制定时器。

复用 RuntimeStore/operations、安装身份、BootstrapProvider 的物理 PID/slot/peer 校验和 claimComponent；按职责新增 JobLedger、RuntimeJobBroker、RuntimeJobInfo、RuntimeJobScheduler、RuntimeJobGateway/Slots、RuntimeJobs 与 agent/jobs.ts。两套 APK 构建共用 Job manifest 槽生成器。先完成持久调度、取消竞争和真实查询故障验收，再接实际 start/stop/finish 与冷恢复，最后复测王者。

补充平台语义依据：[JobServiceEngine](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/apex/jobscheduler/framework/java/android/app/job/JobServiceEngine.java)、[JobServiceContext](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/apex/jobscheduler/service/java/com/android/server/job/JobServiceContext.java)。本节为设计约束，尚无实现/设备验收结论。

### JobInfo 转换不能依赖 authority 里的隐藏反射

自研必需 Java adapter 只在 worker 加载。authority 的 JobInfo 转换使用公开 Builder，并由 worker 必需 adapter 提供原 JobInfo 的 hasEarlyConstraint/hasLateConstraint：仅用 minLatency/maxDelay 数值会丢失显式零 deadline 的语义。authority 用原逻辑 component/extras 先重建一次，并比较同一反序列化状态下的完整平台 Parcel；不相等就明确拒绝尚未覆盖的字段。只有验证相等后，再用相同字段集替换物理 component 和私有版本 extras。这样新增平台字段不会被默默丢弃，也无需在权威进程注入 Hook。

客体参数从真实 JobParameters 防御复制，恢复原 extras/namespace；物理 callback Binder 不交给客体视图。工作项等未支持操作显式拒绝；jobFinished 由登记的客体视图映射回原物理参数。执行 attempt 的 callback Binder 只在 runtime 内保存与比对。参数复制不构造虚假的系统事件。

实现准备补充：JobInfo.equals 对 Bundle 的比较受是否已反序列化影响；全字段重建门槛不能仅凭两个不同处理状态的对象 equals。应在相同平台 Parcel 状态下比较，必要时对完整已验证、无 FD/自定义 Parcelable 的 JobInfo 编码作逐字节比较，并用数组及嵌套 extras 验证这一门槛。无损证明失败就拒绝，不能删除某个约束后重试调度。

`jobs-contract-p1-01`（runtime APK `c3d17d2a1d4a6df34d9874cc1a8a7a05ddd74595dda806bbbff1362ee148423a`）P1 229 项通过，其中 JobInfo 子报告 15 项通过：显式零约束、deadline、网络/电量/idle、period/flex、priority/backoff/字节估计、debug/trace、嵌套原始类型 extras、原对象不变，以及未支持 kind/Parcelable 拒绝。该子报告实际 `systemJobsSubmitted=0`，不能视为真实调度通过。

### 生命周期审查补充

Job 冷恢复还必须遵守现有 Hook 恢复策略。仅检查 job 的最后一次 attempt 不够：一个尚未开始的 job 也可能属于已激活 Hook 的目标进程。Hook 激活前的持久标记必须同时约束该逻辑进程关联的 pending job 版本；新调度也读取此状态。进程死亡后不能自动用无 Hook 的 Application 执行旧版本；显式重新调度产生新版本才可建立新的执行契约。不能通过新建 gateway 绕过现有 Activity/Service 的恢复限制。

固定平台 JobParameters 的 abandoned-job cleaner 由真实 JobServiceEngine 管理。客体参数副本不自行启用 cleaner，物理参数保持强引用直到真实执行/停止/完成路径退出；实际 jobFinished 入队、Binder 调用返回和系统资源退休仍是分开的事实。不能因为客体副本被 GC，让同一个系统 job 被第二个 cleaner 提前结束。


## H33 实现状态

持久调度内核与真实平台 schedule/cancel/query 已实现并通过独立 UID 的 65 项真机检查；详见 [实施计划](jobscheduler-implementation-plan.md#h33-调度内核进展2026-10-16)。JobInfo 丢 ACK 对账使用 namespace/id/version/component 和完整平台 Parcel 匹配，缺失不推断成功；系统 pending 消失不释放组件，也不解除未知取消的 mutation owner。

Hook 恢复标记现在与 session 标记同事务持久化，首次 generation 不被新进程覆盖。该事实只是后续执行准入的输入，真实执行侧必须核验才能保证旧版本不以丢失 Hook 的方式恢复。

Gateway 仍未开放真实生命周期，客体 JobScheduler facade 尚未接线。本轮没有制造 JobParameters、提前返回执行中、伪造完成或接入定时器。普通 Service 与 JobService 的独立组件边界保留。
