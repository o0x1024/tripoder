# 空任务回收与永久关闭

## H37：进程执行占用与Activity资源保留（2026-10-16）

当前实现不再将两个责任混为一个pin：原会话确证DEAD且旧入口永久拒绝后，可以解除物理进程槽排他权；旧component/alias/affinity仍保留，业务UNKNOWN不变。未关闭的恢复入口、存活/未知实例、Service/Provider/Job/Broadcast等执行引用继续占槽。原owner仍绑定原processStub，不能因retainedStub相同绕过另一个owner的新占用。没有真实任务证明时仍不得退休/复用旧Activity资源。

新安装只使用其自己的组件/affinity；旧ticket在冷/热Factory与newIntent入口、客体元数据解析之前被拒绝。真实跨安装冷/热迟到投递14项已通过，新PID/身份保持，原记录快照不变。历史DEAD关闭只核验持久死亡事实，不重建ExecutionContract执行capability。`capacity.activity`描述执行占用，`activityResources`描述仍保留的资源。

详见 [H37交接](runtime-handoff-2026-10-16-h37.md) 和 `docs/evidence/process-slot-ownership-h37/reuse-04/`。下方关于“不能转槽”的历史结论仅适用于当时未区分执行/组件所有权的实现，物理任务退休证明要求仍有效。

2026-10-15。连续外部 Agent 验收发现生产引擎八个槽位被历史资源引用保留。`agent-capacity-inspection-01` 中没有存活分配；已结束计数器的空 recent task 仍保留 Gateway 根。诊断首版未正确序列化逐槽 flags，所以不能把八个槽位都归因为同一种 Activity 问题。

## 三个不同事实

- `stop(generation)`：终止准确进程，等待 Binder death；保留 Android 的既有冷恢复语义。
- `close(generation)`：先持久设置该代次永久关闭屏障，再终止或确认已死亡；旧 Activity/Service 不得恢复此代次。新显式启动可以建立新代次，但不继承 Hook 授权。
- 空任务回收：只有 Android task 本身移除并再次观察缺席，才退休物理引用。进程死亡、Activity FINISHED、Agent CLOSED 均不能单独证明槽位释放。

## 回收证明

只接受引擎自己的 `RuntimeGuestGateway` 空任务。系统报告 `numActivities=0`、base/top 为空后，还须验证 baseIntent 的 operation 和 capability 摘要与持久 controller launch 相符；原始 capability 不进入证据。

入口记录必须为 DISPATCHED，root ticket 必须有已确认的同一 taskId、instance 和 OWNED_TASK_ATTACHED 证据。新入口额外记录 Gateway taskId。Gateway taskId 不能替代客体 attach 证据，更不能覆盖重父化后的客体 taskId。未附着和跨任务的不确定启动继续保留。

入口身份是不可变的；冷恢复可以更新 ticket 当前资源代次。原入口与当前 ticket 必须属于同一 space/package/install/processName，当前资源会话已死亡，且没有同一逻辑进程的新存活代次。单进程回收还要求任务内所有 ticket 属于该授权代次；共享或混合代次任务保留。

接受操作、启动操作与所有 ticket 的资源关闭屏障在同一事务提交。屏障阻止 restore/claim/rebind。系统移除前再次验证相同 taskId、入口摘要、ticket 集与空态；调用 Android 时不持有 broker monitor。之后使用 recent/running/recent 观察确认缺席，才将资源标记 RETIRED。原业务 launch、delivery、UNKNOWN 结果不变。

恢复时，已经缺席的任务可根据持久屏障协调资源结果。仍存在的 UNKNOWN 移除操作只能在完整身份与屏障重新验证后继续幂等移除；不重复业务启动，也不改用新 operationId 掩盖未知结果。taskId 被其他任务复用时拒绝移除。

## 对外结果

Agent 会话回执分别包含 processClose 与 taskResources。CLOSED 表示授权关闭策略完成，不表示所有 task、组件或槽位都已回收。工作台的 `resources` 提供逐槽原因与有界引用；`reclaimEmptyTasks` 只触发上述生产回收协议，不接受任意 taskId、数据库路径或清空数据指令。

## 验收门槛

1. 已有空任务回收前后逐槽证据；其余 pin 必须保留并解释。
2. 连续创建和关闭超过八个独立空间，没有泄漏；旧业务操作仍可查询。
3. stop 后能冷恢复，close 后旧代次不能恢复；正常冷恢复后结束可回收。
4. 两个事务/系统调用中断窗口恢复；未知业务结果不被改写。
5. 共享任务、重父化、未附着、复用 taskId 不误删；关闭与新启动竞争不越过屏障。

实现和审查不是验收结论，具体运行结果按证据目录记录。

## 未附着入口关闭（schema 25，正在验收）

微信第十五次进程死亡后，生产资源检查 `notifications-resources-02` 观察到 ticket 仍为 PREPARED、taskId=-1、原 generation=DEAD；Gateway task 1755 虽为空，也不能证明客体实际放置在哪个 task。现有回收规则正确保留了 pin，但把“不能退休资源”同时当成“不能新显式启动”，导致后续启动持续被拒绝。

新增显式 `closeLaunchEntry`，精确绑定 project / binding / install / 原 launch operation。仅接受 DISPATCHED、未附着、结果 UNKNOWN、旧代次已死亡且没有替代存活代次的入口。关闭 operation 的 accept/start、ticket 的 entryFenceOperation 和成功回执在同一 SQLite 事务提交；结果为 ENTRY_FENCED_RESOURCES_RETAINED。原业务 operation、ticket 状态、resourceProof、组件租约和资源 pin 均不改写。

控制台的新显式启动可以越过已经永久关闭的旧入口，仍复用同一不可变安装/进程/组件租约，创建新的 process generation、ticket 和 delivery。其他开放 ticket 仍会阻止新入口。冷、热 Factory 都先向 authority 核验关闭屏障，旧入口返回仅调用 finish() 的 RuntimeRejectedActivity，不能在拒绝前初始化 guest Application 或采用其 loader。claim 处再次检查；热 onNewIntent 在客体 Intent 转换之前核验，闭合的旧投递不会调用 guest，也不写成功 ACK。

工作台提供“处理未确认启动”，关闭前显示历史 UNKNOWN 与保留资源的含义。关闭请求的本地 ID 持久化，连接中断后只查询该操作；收到关闭成功并重新核对原 UNKNOWN 后才解除界面的待确认限制。

此操作只修复显式重试的准入边界，**不宣称未附着资源已经退休**。因此旧 pin 仍可能阻止安装升级/卸载和槽位转给其他安装；最终回收仍需要额外的真实系统证据，不能把空 Gateway taskId 回填成客体 taskId。本批先验证冷/热旧入口拒绝、正常新 Intent、authority 重启、原 UNKNOWN 与同一 lease 保持，再继续微信。实际平台 Gateway 发出启动、但客体未 attach 的场景已有生产失败证据；自研 probe 的准备后死亡用例也明确保留其测试范围。

2026-10-15 06:29：Activity创建修复后，微信24的root已具有OWNED_TASK_ATTACHED与task1976，但onCreate失败，留下合法可回收空任务。25的preflight在ProcessSupervisor既有回收前拒绝，资源命令随后按原协议成功移除此任务，26可以继续。控制端prepare现先执行相同凭证回收再检查开放root，防止把已经能证明结束的资源误作新的启动阻塞。无法证明退休的未附着旧票据仍保留；此顺序修正不解决历史六个probe pin。

## 应用内部跳转的未附着入口（2026-10-15，实施中）

微信32已从LauncherUI发起WelcomeActivity，但进程在onStart广播处死亡；Welcome的独立candidate未attach。33因此在preflight被拒绝。该candidate并非controller_launches根入口，原关闭API无法处理，不能通过关闭另一个root、清库或删除ticket绕开。

`launchEntries`新增ACTIVITY_ROUTE类型，精确关联activity_launches的operation/candidate/sourceTicket、目标space/package/install和当前project binding。仅列出有封存payload摘要、真实ACTIVITY来源、delivery与operation均UNKNOWN、未attach且旧generation已DEAD的条目。`closeLaunchEntry`复用同一操作编码与事务协议：根入口仍检查原DISPATCHED gateway；内部路由检查持久源ticket/space/package身份。两者共有未附着、UNKNOWN、进程死亡且无替代活跃进程的约束。

关闭只写entryFenceOperation，重复同一operation可查询得到同一回执。跨项目/绑定/安装拒绝。原投递结果、状态与资源租约保留。已有cold/warm creation和newIntent关闭屏障适用于该candidate；本批新增入口列表和关闭权限不等于已经证明旧组件/affinity可以再分配。验收使用此前实际观察到的Welcome ticket，不选择或批量关闭不相关条目。


2026-10-15 07:36：内部route关闭已在wechat-first-launch-35通过5项断言。`entryClosureReceipt`仅接收已成功的工作台关闭operation，并核验其结果票据和entryFenceOperation；返回该票据原UNKNOWN事实，不能用任意guest operation查询。通用query所有者边界保持。证明的是精确入口永久拒绝及历史保留，不证明组件槽退休或完整UI待确认操作恢复。
