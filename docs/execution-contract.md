# 执行契约 v1

此契约是新 runtime 的约束，旧工作台尚未整体迁移。实现范围和设备结果必须在验收报告中逐项列出。

## 状态与身份

安装：`staging → published → committed`；只有 committed 且制品摘要再次校验正确才可运行。丢失/损坏拒绝，不读取宿主已安装包作为替代。P1 安装层在设备上对完整 base/split 集合进行密码学验签；执行层仍只允许已验收的单 base 组件切片，未知组件和未声明能力拒绝。

进程：`allocated → attached → running → dead/failed`。身份包含 space、package、installGeneration、processName、processGeneration；attach 时绑定真实 PID/UID 与 Binder 连接。进程不能切换身份。死亡使所有相关 callback 失效，PID 复用不能恢复旧代次。

控制：同一运行实例同一时刻一个 owner + ownerGeneration。接管需明确终止旧租约；旧部署状态未知时拒绝新部署，不能将 UI 切换当成撤销确认。

## 操作

请求键为 `(operationId, callerIdentity, payloadDigest)`。同 ID 同完整身份/摘要返回已有操作；同 ID 不同内容冲突拒绝。`accepted → running → succeeded/failed`；链路超时可记录 `unknown`，unknown 不等于失败或未执行，只允许状态查询/有证据的协调，不能自动重放。

持久化日志先写 accepted 再做外部副作用；记录 terminal 后返回结果。Broker 重启时，未完成副作用进入 unknown。持久化失败不得执行后续副作用。数据库状态与 Activity 系统启动之间无跨系统原子事务，不能承诺 exactly-once；契约保证不因传输重试而盲目重放。

取消是独立操作，确认终止之前不能显示 canceled。查询不触发启动、部署或再次调用原函数。结果必须区分“Activity 已请求”和“客体 onResume 已报告”。

## 组件与回调

虚拟包解析仅查当前 space 的已提交安装。系统宿主包管理器可以用于解析 APK 制品，不能作为缺失虚拟目标的解析后备。

一次性 callback 随机能力绑定双方安装/进程代次、同一 space、原操作、到期时间；Broker 决定返回接收者，客体不能凭自报 `returnPackage` 指定任意应用。能力过期、重复使用、错误调用者、升级或任一进程死亡均拒绝。Intent 数据大小与类型限制；不得无约束转发 URI grants、ClipData、Binder 或反序列化对象。

`setResult` 只授权一次返回，不能宣告操作成功；必须等来源 Activity 的实际 `onActivityResult` 完成并由绑定来源确认。目标死亡后，尚未消费的能力作废；如果之前已授权并完成交付，操作先进入 UNKNOWN，再用已认证来源的收件证据协调到 SUCCEEDED/FAILED。这个协调不恢复旧能力、不重新启动组件、不重放副作用。

P0 仅验证显式标准 Activity 的 `startActivityForResult` 路由；不把本地 fixture 方法调用当作组件往返。Provider 启动顺序、Task/launchMode、Service、Receiver、PendingIntent、通知及账户必须另验收。任何未实现能力应带稳定错误码拒绝。

## Hook

| 阶段 | 成功 | 失败 |
|---|---|---|
| before | 校验新参数，再调用原函数一次 | 记录阶段并抛错，原函数零次 |
| replace | 校验并返回替代结果，原函数零次 | 记录阶段并抛错，原函数零次 |
| original | 调用一次 | 原异常传播，不重试，不调用 after |
| after | 校验并返回新结果 | 记录并抛错，原函数已发生一次，不返回原结果掩盖失败 |

Native 回调错误不能抛跨 C ABI：受控设备代理终止当前进程并记录原因，不返回默认值。显式 disable/rollback 是用户操作，不是错误 fallback。准备失败保持已提交版本，因为没有发生新版本提交；不可称为新部署成功。

一次调用捕获同版代码与参数；跨进程部署记录每个 session 的 prepare/activate/ack，任一未确认即不能显示整体成功。当前模拟器只证明单线程同步语义。

## 安全与可观测性

真实 UID 相同不构成恶意代码隔离。nonce/PID/代次防止路由混淆与迟到重放，不保护同 UID 进程中的秘密。Native 直读宿主私有文件的测试应如实记录可见性，不能把“读到了”转换为隔离通过。

所有失败带稳定分类、操作/进程代次和有界错误信息。证据来源标签本身不认证发送方；控制端验证连接绑定。过期证据不能驱动新部署。生产日志不包含秘密，验收只使用自有合成数据。

## 本轮实现和验证范围

纯 Java 契约 103 项主机检查通过；共享 runtime 将 PID/UID、安装查询、一次性回调、实际收件和 SQLite 操作状态接入真实组件路径，57 项真机断言通过。P1 已增加真实控制进程死亡与 SQLite 重开、文件锁跨进程验证、关闭 Store 门禁，以及安装/升级/卸载/迁移事务和终态回执的联合提交。详细结果见 [P1 验收](evidence/runtime-p1-2026-10-14/README.md)。租约尚未接入旧工作台；全量 Binder 类型面、日志预算与整机断电仍是未覆盖门槛，不能据此声称所有契约在产品中已生效。原始结果和限制见 [P0 验收](evidence/runtime-p0-2026-10-13/README.md)。
# 2026-10-15 关闭与完成回执

关闭首先撤销新工作的准入，并不撤销一个仍存活进程报告既有工作完成的资格。Broker 必须先按真实 Binder PID/UID 和不可变 generation 认证，再按消息类别分流：启动、claim、新 Provider 调用和进程启动事件继续拒绝；Activity 实例生命周期、已有结果交付、Service ACK、Provider release/finish、广播完成和注销仅能消费各账本中已经存在的精确资源。

完成通道不接纳 DEAD generation，不创建新组件或新的业务操作，不清除关闭标记，不恢复进程可调度状态。永久关闭的持久标记也不阻断既有 Activity 的销毁回执。通用事件只承担 adapter/application 初始化；生命周期由带 ticket/instance 的回执唯一记录，不再追加无资源身份的重复通知。

验收增加真实 Activity 关闭屏障：Activity 创建后由自研测试 authority 分别设置瞬时和持久关闭，客体尝试新启动必须被拒绝，随后真实 finish/result/onDestroy 必须完成，同一 PID 仍活到测试显式 stop。故障注入只在验收 APK；产品控制协议不提供该操作。所有 runtime 真机验收同时检查本轮自有 UID 的 Java 致命异常，业务断言通过但进程崩溃不能算通过。


## 启动入口 capability 的先后（2026-10-18，P1-1）

`prepareLaunch` / `prepareApplicationLaunch` 在**槽位预留与 capability 签发后即返回**，不再等待客体 `application-ready`。返回值中的进程证据只证明槽位已预留，不证明 worker 已运行。

- capability 有效期自该次启动的共享启动截止时间起算再加 `ControllerLaunchLedger.VALIDITY_MS`（原为自签发时起 30 s），否则慢启动会在被消费前过期。
- `RuntimeGuestGateway` 先显示启动页，再在后台线程调用 `ProcessSupervisor.awaitReady`（沿用同一启动截止时间，不另起计时），确认 `application-ready` 后才在主线程 `consume` 并派发 stub Activity。`consume` 仍要求会话为 RUNNING，一次性语义不变。
- 等待失败、超时，或 Gateway 在派发前被销毁：`ControllerLaunchLedger.abandon` 将操作记为 UNKNOWN（`LAUNCH_PREPARATION_UNCONFIRMED` / `LAUNCH_GATEWAY_CLOSED_BEFORE_DISPATCH`）并使 capability 失效。进程可能已在启动，因此不记为 FAILED，也不自动重放。
- 启动超时的错误码带阶段：`PROCESS_START_TIMEOUT_UNKNOWN:phase=BIND|LOAD|APPLICATION`；总预算仍为 30 s。
- 空闲预热（P1-2）：authority 维持至多一个**无身份**的预热 worker（`ProcessSupervisor.Spare`），占用 `CAPACITY=8` 中的一个 index，`capacity()` 以 `spare` 标出且不计入 `available`。启动选中同 index 后补发 `ABORT_CONFIGURE`（`RuntimeWire` 码 50），中止凭证语义不变。预热 worker 被系统回收只会退化为冷 fork；其他分配无空槽时先淘汰它。
