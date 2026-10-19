# 执行契约与状态机规范 (Execution Contract)

本文档定义 Tripoder 控制面（`:authority`）与执行面（`:slot0..7`）之间的核心状态机、租约（Lease）生命周期、幂等操作语义以及进程退出围栏（Shutdown Fence）。

---

## 1. 应用安装状态机 (Install State Machine)

安装记录在 `RuntimeControllerDatabase`（Schema 33+）中维护，`installGeneration` 单调递增，任何时刻每个 `(spaceId, packageName)` 最多只有一个活跃可见生成（Generation）。

### 状态流转

```
staging ──► published ──► committed
   │            │             │
   ▼            ▼             ▼
aborted      aborted     removing ──► removed
```

| 状态 | 含义与可见性 |
| --- | --- |
| `staging` | 正在解压 APK、提取 native 库（`lib/<abi>`）、解析 `AndroidManifest.xml` 并写入 `InstallProviderIndex`。对运行时查询不可见。 |
| `published` | 文件落盘与组件元数据索引完成，等待事务提交。 |
| `committed` | 唯一对 `resolveIntent`、`resolveContentProvider`、`prepareApplicationLaunch` 可见的活跃状态。 |
| `removing` | 卸载或覆盖更新进行中：拒绝新的组件调度与进程绑定，向已绑定的 `:slotN` 发送驱逐（Evict）通知并等待进程排空。 |
| `removed` / `aborted` | 终态，对应生成号下的虚拟应用代码缓存与组件索引已清理。 |

---

## 2. 进程会话与控制租约 (ProcessSession & ControlLease)

### 2.1 槽位分配不变量

- 8 个预声明槽位（`slot0` .. `slot7`）由 `:authority` 统一管理。
- 每个槽位在同一时刻最多绑定一个三元组：`(spaceId, packageName, targetProcessName, installGeneration)`。
- 分配优先级：
  1. 已绑定相同 `(spaceId, packageName, targetProcessName, installGeneration)` 的活跃槽位直接复用。
  2. 空闲（`unbound`）槽位直接分配。
  3. 若 8 个槽位全部占用，仅回收**无活跃前台 Activity 租约、无活跃 Service 租约、无未决 Provider 事务**的后台槽位；若全部处于活跃占用状态，则显式返回 `SLOT_EXHAUSTED` 错误，绝不静默混跑两个不同进程身份。

### 2.2 ProcessSession 状态机

```
allocated ──► bound ──► draining ──► terminated
    │           │
    ▼           ▼
 expired      evicted
```

- **`allocated`**：`:authority` 为启动请求预留槽位并签发 `sessionId` 与 `leaseToken`。
- **`bound`**：`:slotN` 进程启动后调用 `bindProcessSession` 完成握手，上报真实 Linux `pid` 并校验 `installGeneration`。
- **`draining` / `evicted` / `terminated`**：当进程死亡（`Binder.linkToDeath` 或 `pid` 变更）、应用卸载或槽位回收时，原子吊销该 `sessionId` 下挂载的所有 Activity / Service / Provider / Receiver 租约。

### 2.3 组件级租约 (Component Leases)

所有四大组件调度均以 `:authority` 签发的显式租约票据（Lease Ticket）为准：

1. **ActivityLease**：记录 `leaseId`、`slotId`、`taskId`、`intent`、目标 `ActivityInfo` 与状态（`issued -> active -> paused -> destroyed`）。宿主 `RuntimeSlotNActivity` 必须凭有效 `leaseId` 实例化真实 Guest Activity。
2. **ServiceLease**：区分主动生命周期（`startService` / `bindService`）与系统被动唤醒（`JobService` / `NotificationListenerService` 被动租约）。支持 `stopSelfResult(startId)` 精确匹配最后一次 `startId`。
3. **ProviderLease**：跨进程 ContentProvider 访问时，若目标 Provider 位于其他 Guest 进程，由 `:authority` 协调目标槽位拉起并维护引用计数。
4. **ReceiverLease**：有序广播（Ordered Broadcast）与静态 Receiver 派发时签发短期租约，跟踪 `finishReceiver` 完成状态。

---

## 3. 控制面异步操作状态机 (Operation Contract)

针对耗时控制面指令（如 APK 安装、卸载、大型 Project 编译下发、Agent 批量控制命令），统一采用幂等 `operationId` 状态机：

```
accepted ──► running ──► succeeded
                │
                ├──► failed (附带确定性错误码与阶段信息)
                └──► unknown (跨进程超时或宿主异常重启后的显式待对账态)
```

- 相同 `operationId` 的重复请求返回当前状态快照，不重复产生副作用。
- 禁止伪造成功状态：若底层执行超时或连接中断，状态必须标记为 `failed` 或 `unknown`。

---

## 4. 一次性回调能力票据 (One-Time CallbackCapability)

当 `:authority` 将异步任务委派给 `:slotN` 执行并等待完成回调（如 Receiver `goAsync().finish()`、JobService `jobFinished()`、Service `onDestroy` 确认）时，使用一次性 `CallbackCapability` 令牌：

- 每个回调令牌绑定唯一的 `(capabilityId, sessionId, leaseId, expectedAction)`。
- **单次消费语义（Consume-Once）**：首次合法回调将状态推进至 `completed`；后续重复回调、过期会话回调或伪造令牌一律被 `:authority` 拒绝并记录协议违规，防止旧进程残余回调污染新会话状态。

---

## 5. 并发与退出围栏 (Concurrency & Shutdown Fence)

### 5.1 控制面数据库并发规约

- `RuntimeControllerDatabase` 启用 SQLite WAL 模式，所有跨表状态变迁（如“分配槽位 + 签发 Activity 租约”）必须在同一个数据库写事务内完成。
- 高频只读热路径（如 `resolveContentProvider`、`getInstalledPackage`）使用内存索引（如 `InstallProviderIndex`）直接命中，避免在启动关键路径上进行全表扫描或重复反序列化 `AndroidManifest.xml`。

### 5.2 Guest 进程与 Hook 引擎退出围栏 (`ShutdownFence`)

在 `:slotN` 进程卸载 Hook、重置会话或退出时，必须通过双阶段排空围栏，杜绝 Use-After-Free：

1. **停止接纳新调用（Stop Admission）**：原子推进代数（Generation），使后续进入 Java/Native Hook 跳板的调用直接直通原函数（Bypass），不再进入动态脚本分发器。
2. **等待在途调用排空（In-Flight Drain）**：统计当前正在执行的 Hook 帧引用计数（`in_flight_count`），等待所有在途调用退出后再释放 AST 解释器上下文、JNI 全局引用与跳板内存。
