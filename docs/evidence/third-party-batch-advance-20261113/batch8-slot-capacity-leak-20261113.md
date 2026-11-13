# 第 8 批（续三）：槽位容量泄漏——8 个槽位被账本残留占死，活进程数为 0

这是本轮最重要的发现，而且它**挡住了后续所有应用测试**：在设备上没有任何客体进程存活的情况下，Workbench 拒绝启动新应用并声称「容器同时运行的应用进程已经达到上限」。

## 1. 观测事实（可复现）

复现脚本 `/tmp/bili_crash2.sh`：`am force-stop dev.tripoder.q6` + `q7` → 起 Workbench → 两次「重新连接」→ 点「哔哩哔哩」。

- `+5s / +10s / +15s / +20s / +25s / +30s / +35s` 七次采样，`topResumedActivity` **始终是 `WorkbenchActivity`**，`ps -A | grep tv.danmaku.bili` **始终为空**（bili 进程从未出现，连崩溃都没有：本轮 crash 缓冲全量读出为空）。
- Workbench 弹框原文：**「容器同时运行的应用进程已经达到上限，「哔哩哔哩」需要其中一个。现在没有可以停止的应用，请稍后再试。」**（`[78,1250][1130,1422]`，按钮「知道了」`[902,1434][1094,1596]`）
- 同一时刻的槽位进程：只有 `dev.tripoder.q6`（主）、`dev.tripoder.q6:authority`、`dev.tripoder.q6:recovery` —— **`dev.tripoder.q6:slotN` 一个都没有**。

即：**活进程 0 个，但容量判定为满**。

## 2. 关键判别：这不是「上一轮残留的活进程」

脚本第一步就 `force-stop q6/q7` 并重新拉起 authority，容量仍然报满。所以它不是「忘了停应用」，而是**账本里占位的行在 authority 重启后依然存在**。

这一条把上一轮的两种猜测都排除了：

- 不是「bili 起来后立即死亡」（进程从未出现，crash 缓冲为空）；
- 不是「bili 起来了但没拿到 resumed」（`topResumedActivity` 在七次采样里从未变成客体，且 dialog 明确给出了容量原因）。

上一轮我写的是「两种可能与实录一致，本轮未分辨」——现在分辨完了，**两者都不是**。

## 3. 为什么这次请求说「已完成」而什么都没跑

上一轮（`/tmp/bili_crash.sh`）Workbench 状态栏显示 `启动应用「哔哩哔哩」 · 已完成`，而 top 停在 Workbench。本轮在同一路径上拿到的是显式的容量拒绝框。两者是同一根因的两种呈现：启动请求在**预留槽位**这一步就失败了，产品文案与状态行没有把它表达成「没启动成功」。

## 4. 代码层：容量判定被 8 个条件同时否决

`ProcessSupervisor.availableIndex()` → `fits(i,…)`，一个槽位可用必须同时满足：

```
!broker.stubUsed(stub)                       // 有活会话占着
!occupied(i)                                 // 本进程的 launch 表占着
!broker.providers.occupied(stub)
!broker.broadcasts.occupied(stub)
retained==null || retained.equals(stub)
!broker.activities.processPinnedByOther(...)
!broker.services.occupiedByOther(...)
!broker.jobs.components.occupiedByOther(...)
```

即「空闲」不是「没有活进程」，而是**八个账本都说这块 stub 没人**。只要某个账本留了一行，这块槽位就永久不可用。8 块全被留痕 → `PROCESS_CAPACITY_EXHAUSTED:requiredSlot=any`，而 UI 只能翻译成「进程已达上限」。

## 5. 静态检查：两处账本没有存活条件

| 账本 | 查询 | 是否会随会话死亡自愈 |
|---|---|---|
| `ProviderLedger.occupied` | `JOIN sessions s … WHERE s.activityStub=? AND s.state!='DEAD' AND (…)` | 会 |
| `ActivityLedger.occupied` | `LEFT JOIN sessions s … AND NOT (… COALESCE(s.state,'UNKNOWN')='DEAD')` | 大体会 |
| `BroadcastLedger.occupied` | `JOIN sessions s ON b.target=s.generation WHERE s.activityStub=? AND b.state!='COMPLETED' AND b.resourceRetired=0` | **只 join 不用 s.state**，投递行停在 UNKNOWN 就永久占位 |
| `ServiceLedger.occupied` / `occupiedByOther` | `SELECT … FROM service_leases WHERE processStub=? AND state!='RELEASED'` | **完全不 join 会话**，进程死了租约还在就永久占位 |

`ServiceLedger` 的释放路径在 `recover()`：

```sql
SELECT l.id,l.stub FROM service_leases l JOIN sessions s ON l.generation=s.generation
WHERE s.state='DEAD' AND l.state!='RELEASED'
  AND NOT EXISTS (SELECT 1 FROM service_calls o
                  WHERE o.lease=l.id AND (o.state='PREPARED' OR (o.kind='bind' AND o.state='APPLIED')))
```

**一个停在 `PREPARED` 的 service_call 会让这条租约永远不释放**——和广播栅栏 `BROADCAST_PREPARATION_UNCONFIRMED`（h28）是同一个形状：我们自己的「未确认」中间态把资源钉死。`ServiceLedger` 里没有任何逻辑把「源会话已 DEAD 的 PREPARED 调用」推进到终态。

## 6. 还没证明的部分（本轮不做推断）

以上是**静态检查**，它说明「这两处账本有能力泄漏」，但**没有证明**当前这 8 块槽位具体被哪个账本占住。要判定必须看到占位行本身，而：

- 宿主 `dev.tripoder.q6` 不是 debuggable（`run-as` 报 `package not debuggable`），拿不到 `databases/`；
- 设备上没有 `sqlite3`。

因此我加了一条**只记录不改行为**的诊断：把 `fits()` 重构成 `blocker(i,…)`，容量耗尽时按槽位打印第一个否决者，形如

```
TripoderSupervisor: PROCESS_CAPACITY_EXHAUSTED pkg=tv.danmaku.bili process=tv.danmaku.bili free=none blocked=[F0M0C0:service F0M1C0:broadcast …]
```

构建产物：`android/product/build-slotdiag-v78`。用它复现一次即可知道是 `service` 还是 `broadcast`（或其它）在占位，再把修复落在对应账本上，而不是两个地方一起盲改。

## 7. 边界

- 这是**容器承载力/账本生命周期缺陷**，与 bilibili 是否兼容、是否有风控**无关**：本轮 bili 的代码一行都没跑起来。
- 上一轮「bili 同意隐私后 FATAL」仍是一个独立事实（真崩过，PID 28536），本次没有推翻它；本次只是证明**现在无法重放**，所以完整栈仍未取到。
- 泄漏是**跨重启持久**的，意味着设备状态会随测试轮次恶化；这与 `recover-converts-all-open-rows-to-unknown` 的既有观察一致（每次重启把非终态行转 UNKNOWN，而 UNKNOWN 恰好是占位条件）。

## 8. 归因结果：8/8 槽位全被**广播账本**占死

用 v78 诊断重放同一条路径（`/tmp/slotdiag.sh`，安装 → 冷启 → 点「哔哩哔哩」），拿到逐槽位否决者：

```
W TripoderSupervisor: PROCESS_CAPACITY_EXHAUSTED pkg=tv.danmaku.bili process=tv.danmaku.bili free=none
  blocked=[RuntimeSlot$A:broadcast RuntimeSlot$B:broadcast RuntimeSlot$C:broadcast RuntimeSlot$D:broadcast
           RuntimeSlot$E:broadcast RuntimeSlot$F:broadcast RuntimeSlot$G:broadcast RuntimeSlot$H:broadcast]
```

八个槽位、同一个理由：`BroadcastLedger.occupied()`。同轮 `ps` 只有 `dev.tripoder.q6:authority`（11547）与 `:recovery`（11238），**零槽位进程**——与「活进程 0 个」完全一致。

## 9. 根因：`REJECTED` 既占位又永不退休

`BroadcastLedger` 里有三处共享同一个状态机，只有两处认 `REJECTED`：

| 位置 | 原判据 | 后果 |
|---|---|---|
| `recover()` 行 195-198 | 非终态行 → 已 claim 的写 `UNKNOWN`，**未 claim 的写 `REJECTED`** | 这是为修「未 claim 的投递不应算未知」而加的（h28） |
| `occupied()` | `b.state!='COMPLETED' AND b.resourceRetired=0` | **`REJECTED` 不是 `COMPLETED` ⇒ 占位** |
| 资源退休 UPDATE（`dead()` 与 `recover()` 两处） | `state IN ('UNKNOWN','COMPLETED')` | **不含 `REJECTED` ⇒ 永不退休** |

于是形成一个闭环：投递从未被 claim（客体根本没起来或已死）→ `recover()` 判 `REJECTED`（这个判断本身是对的）→ 但 `REJECTED` 行①占着槽位、②永远进不了退休语句 → **每死一次就钉死一块槽位，8 次之后容器彻底不能再启动任何应用**，而 UI 只能翻译成「进程已达上限，现在没有可以停止的应用」。

这是一个纯粹的**共享状态机没跟着改**缺陷：终态集合被写成枚举常量式字面量，而不是一处定义、多处引用。它与 h28 是同一族的第二次踩坑（第一次是「未 claim 被记成 UNKNOWN 污染栅栏」，这次是「改判成 REJECTED 没同步给容量与退休」）。

## 10. 修复与验证

三处同步（`android/runtime/src/dev/tripoder/runtime/BroadcastLedger.java`）：

1. `occupied()`：`b.state NOT IN ('COMPLETED','REJECTED')` —— 从未送达的投递不能占槽位。
2. `dead(generation)` 的退休 UPDATE：`state IN ('UNKNOWN','COMPLETED','REJECTED')`。
3. `recover()` 的退休 UPDATE：同上。

语义依据：`REJECTED` 的含义是「这次投递从未进入客体代码」，它既不可能产生效果，也不可能再被送达，因此既不该占位、也不该等待「注册已关闭」的退休证明。

**A/B（v79，`/tmp/slotfix.sh`）**：安装 v79 → 冷启 → 点「哔哩哔哩」。

```
[+8s]  procs=13664 tv.danmaku.bili:download
[+16s] procs=13664 tv.danmaku.bili:download 14418 tv.danmaku.bili
[+24s] procs=13664 tv.danmaku.bili:download 14418 tv.danmaku.bili
[+32s] procs=13664 tv.danmaku.bili:download 14418 tv.danmaku.bili
日志：无 PROCESS_CAPACITY_EXHAUSTED、无弹框
```

对照 v78（同一脚本、同一路径）：七次采样 `procs=[]`，每次都弹「已达上限」。⇒ **容量墙已解除，客体进程真正起来了**。

## 11. 边界

- 本次只证明了**广播账本**这一条占位路径。`ServiceLedger.occupied()` 连会话都不 join（`state!='RELEASED'` 即占位），是同一形状的第二处，**本轮未修也未验**：它今天不是否决者，不能因为「猜它也会漏」就一起改。要动它，先让它成为 `blocked=[…:service]`。
- `+32s` 时 `topResumedActivity` 仍是 Workbench：bili 的 Activity 没有前移（见下一篇文档，客体在起来后崩于 Toast）。容量问题解决了，不等于这个应用可用。
- 修复没有清理历史脏行：老库里那些 `REJECTED` 行仍在，只是不再占位；`occupied()` 与退休语句都是读时判据，所以不需要迁移脚本。
