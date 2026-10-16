# H33 JobScheduler 调度内核证据（2026-10-16）

最终目标仍是微信、王者荣耀实际正常运行，正常 Java/Native Hook 不引起崩溃或 ANR。当前仅完成调度内核这一批，不代表最终目标达到。

## 当前结果

| 验收 | 当前构建 | 结果 | 证据 |
|---|---|---|---|
| 真实系统调度、取消、查询、故障对账 | jobs-probe `ece8ef1e…` | 65/65；10 个实际系统提交 | [attempt-03](attempt-03/device-result.json) |
| Runtime Java/Native Hook P4 | runtime-probe `9022bea1…` | 105/105；无意外 Java fatal / Native fatal | [p4-current](p4-current/device-observation.json) |
| 主机 Self Hook + SDK | 当前源码 | 293/15/85/117/295/8/45/12；218 SDK sources | [host-report](host-report.json) |
| Runtime / 正式 Engine manifest | 当前源码 | 各 32 个 JobService 槽，权限与 process 全核验 | [verification](verification.json) |
| 正式 Engine / Workbench / control-test | 当前源码 | 构建成功，已归档；未安装/正式控制验收 | [product receipt](artifacts/product-build-receipt.json) |

独立调度包 `dev.tripoder.jobsprobe` 使用独立 UID 10414 与每轮独立 fixture 数据库，不打开或清理现有 Runtime 数据库。所有本轮系统 job 均已取消并观察为空；账本 UNKNOWN 与墓碑仍保留，取消清理不被当作原操作完成或组件退休。其 APK 不包含 Native Hook，不承担 Hook 稳定性证明。

当前完整 SHA256：
- 调度探针：`ece8ef1efc6c53988cb5ee349d0ec89d788adc99a742dd236a49f5d2d1ccb016`
- Runtime：`9022bea17870371b9bf5f0f6ed8f3640841e7219d6cd136b98084abb3fee34d4`
- Engine：`7d50c862ee0d0cfdbf95ef34457050a5893bd09eb0c1e18ed277408c2d64d289`
- Workbench：`f932eaba693adee5e291e57237b691862ef3a23a06cb151aac7a7ad9ca9e2188`
- control-test：`50ddae5f8a5ab3ccc23abb71dda29848be0fcba331c98ca7d068c90c0e528e27`

构建 receipts 的全部源输入已重核：jobs 213、Runtime 449、正式产品 377，changedInputs 均为空。APK 与 receipts 见 `artifacts/`；独立调度 APK 归档在 `attempt-03/`。当前已安装 Runtime 是上述 9022bea1，未安装正式产品包。

## 已验证的核心边界

真实 pending 查询恢复逻辑 JobInfo，并检验物理 namespace/id/version/component/完整 Parcel；双空间同 ID 与 namespace 不串；默认 namespace 和命名 namespace 隔离；同服务多 job 共用不可变组件，其他服务可共用逻辑进程。

纯调度没有产生 worker session / Application。持久安装级互斥覆盖 schedule 与批量 cancel，RPC 在 broker 锁外执行；另一安装仍能进展。替换 RESULT_FAILURE 保留旧版；正常替换封闭旧准入。丢 ACK 保留 UNKNOWN，正向真实系统版本可恢复 schedule；系统缺失不推断成功，部分取消仍如实显示剩余 pending。

schema 26→27 在新建合成旧库上保留历史 UNKNOWN；Hook 标记写入失败会同时回滚 session 标记，成功后 pending job 与 session 共享首次 Hook generation，磁盘重开保留；新进程 Hook 不覆盖旧 job 契约。

P4 在当前完整 Runtime 上覆盖既有 Java/Native Hook 行为。其故障注入包含预期受控终止，validator 按原有严格退出证据确认；正常路径未观察意外崩溃。这仍不证明微信/王者的业务链路。

## 保留的失败与边界

- `attempt-01`：7 项准入检查后发现 `ACTIVITY_OBSERVATION_DURING_TRANSACTION`，原因是选槽调用了 Android 组件观察。已修为：先在 broker 锁内做系统观察，再以同一 owner 原子提交纯元数据；未取消原事务边界断言。该失败目录和旧制品保留。
- `attempt-02`：修复后 56/56，通过；被当前 65 项版本补充，不跨构建相加。
- 65 项中故障结果/ACK 丢失明确来自注入，真实 schedule/cancel/query 在注入边界内执行。磁盘重开不等同于 authority/worker 实际死亡。
- 客体 facade/Wire、固定 JobParameters/JobService adapter、真实 start/stop/finish、attempt Binder 身份、冷恢复和组件资源退休尚未完成。Gateway 明确未启用，客体 API 没有开放；内部测试仅提交 24 小时后的任务并立即取消。
- 未知取消仍保留 mutation owner；已提交组件不因 pending 消失而退休。下批必须实现物理服务/attempt/必要进程的退出证据，不能放宽为 pending 空即释放。
- 微信历史未附着任务 pin、MIUI sticky 重启策略以及最终登录链路仍待解决。

## 下一批

固定平台成员准入与 callback deadline 实测 → 版本/attempt 准入 → 不可变 worker 与逻辑 JobService → 对象级 finish capability → 冷恢复/替换迟到/运行中取消 → 开放客体 API → Hook 回归 → 微信/王者实际业务链路。
