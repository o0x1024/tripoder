# batch26：BOSS直聘 业务链路第一步就被容器拖死（BROADCAST_ROUTE 10.85s → Input ANR）

## 一、结论

1. **BOSS直聘在容器内的业务链路第一个动作（首页 → `我要找工作` → `ChangeIdentityActivity`）就以 ANR 结束**：
   系统在 `03:20:07.444` 打出 `am_kill [0,29806,dev.tripoder.q6:slot1,0,user request after error,704728]`，
   随后重启进程，Activity 再也没拿到窗口焦点，`topResumedActivity` 回落到 Workbench。
2. **ANR 的两个侧面读数完全对齐，指向我方 authority，而不是 BOSS 兼容性或风控**：

   | 侧面 | 读数 |
   |---|---|
   | ANR 记录 `Subject` | `Input dispatching timed out (com.hpbr.bosszhipin/…WelcomeActivity is not responding. Waited 5000ms for FocusEvent(hasFocus=false))` |
   | ANR `Binder Transaction Info` | `outgoing transaction from 29806(com.hpbr.bosszhipin) to 29522(dev.tripoder.q6:authority) elapsed: 5.022000 s code: 27`；`Pid(29522) have D state thread(tid:29538 name:binder:29522_3)` |
   | 客体侧 | `PerfMonitor binderTransact: time=10852ms interface=dev.tripoder.runtime.v1 code=27`（在主线程 `29806/29806`） |
   | authority 侧 | `TripoderWire: SLOW BROADCAST_ROUTE 10852ms pid=29806 uid=10542 keys=[ordered, payload, generation, receiverPermission, operation]` |
   | BOSS 自身的代价 | `MessageMonitor: Slow Operation: Activity com.hpbr.bosszhipin/.module.main.activity.MainActivity onCreate took 10883ms` |

   两侧数字完全相等（10852ms）⇒ 慢在 authority 处理 `BROADCAST_ROUTE`，不在客体。
3. **这不是 BOSS 的新问题，而是既有 `gap-authority-broadcast-route-slow` 的复现与放大**：同一码此前在 QQ 冷启测得
   6.1–6.4s（batch19），本次 10.85s。区别是这次它不是拖慢启动，而是直接造成 `InputDispatchingTimedOut` 并被系统杀进程。
4. **语义层面的定位**（本批新增）：平台的 `sendOrderedBroadcast()` 对调用方**立即返回**，投递与结果回传发生在之后；
   客体把这条广播发在主线程的 `onCreate` 里，意味着我方 `BROADCAST_ROUTE` 一旦同步做完全部工作，就等于
   「在平台不阻塞的调用点阻塞」——与历史上「在平台不失败的调用点抛异常」是同一族缺陷的另一半。

## 二、现场（原始行）

```
11-14 03:20:00.127 29522 29538 W TripoderWire: SLOW BROADCAST_ROUTE 10852ms pid=29806 uid=10542 keys=[ordered, payload, generation, receiverPermission, operation]
11-14 03:20:00.127 29806 29806 W libbinder.BpBinder: PerfMonitor binderTransact: time=10852ms interface=dev.tripoder.runtime.v1 code=27
11-14 03:20:00.138 29806 29806 W MessageMonitor: PerfMonitor: Slow Operation: Activity com.hpbr.bosszhipin/.module.main.activity.MainActivity onCreate took 10883ms
11-14 03:20:00.412 29806 29806 I wm_on_create_called: [0,58757199,com.hpbr.bosszhipin.module.common.identity.ChangeIdentityActivity,performCreate,12]
11-14 03:20:07.444 29806 29806 I wm_on_top_resumed_lost_called: [58757199,…,topStateChangedWhenResumed]
11-14 03:20:07.444  3261  3489 I am_kill : [0,29806,dev.tripoder.q6:slot1,0,user request after error,704728]
11-14 03:20:07.548  3261  6258 I am_proc_died: [0,29806,dev.tripoder.q6:slot1,0,2]
11-14 03:20:07.564  3261  3743 I am_proc_start: [0,28904,10542,dev.tripoder.q6:slot1,top-activity,{…TaskP1$F1M2C2}]
```

ANR 期间的 CPU 采样：`56% 29522/dev.tripoder.q6:authority`（315,185 minor faults）、`41% system_server`、
`5.4% 29806/com.hpbr.bosszhipin` —— 客体几乎没有 CPU，全部时间在等 binder。

## 三、已排除的方向

| 方向 | 排除理由 |
|---|---|
| BOSS 兼容性 / 风控 | 启动、首页、`ChangeIdentityActivity` 都由 BOSS 自己正常创建（`wm_on_create_called` 有记录），没有 FATAL、没有风控文案 |
| 崩溃 | `crash` 缓冲为空，`am_crash` 无记录；进程是被 `am_kill`（user request after error）结束的 |
| 输入焦点丢失 | 焦点丢失是 ANR 之后的结果（`00:07.461 ignore set focus … because NO_WINDOW`），不是原因 |
| 磁盘 fsync | `RuntimeStore.onConfigure` 已开 WAL + `synchronous=NORMAL`，并非逐目标两次 fsync 的默认组合 |
| 我方队列容量 | 本批无 `JOB_*`、无 `PROCESS_CAPACITY_EXHAUSTED`、无 `admission-blocked` |

## 四、下一步（按代价排序）

1. **给 `BROADCAST_ROUTE` 加分阶段界标**（targets 数、resolve 耗时、每目标 prepare 耗时、digest 耗时），
   先定位这 10.8s 落在哪个阶段；这是唯一能把「加日志」变成结论的一步。
2. 若耗时随 targets 线性增长 ⇒ 把每目标的 `prepare` 合成一个事务（当前是每目标一次 begin/commit）。
3. 若耗时在 resolve/digest ⇒ 缓存 `RuntimeBroadcastIntent.digest(payload)`，并按 payload+target 复用。
4. **语义层修法（更大但更正确）**：`sendOrderedBroadcast` 的调用方立即返回，投递与结果回传异步化；
   只有 `goAsync`/`getResultCode` 类用法才需要等结果。做完之后「主线程发广播被拖死」这一整族 ANR 才会消失。

## 五、可复用脚本

`android/scripts/boss_business_chain.py`：从 Workbench 冷启 BOSS直聘 → 取落点 → 走在线简历入口，
每步留 top/截图/崩溃归属。本批已用它证明落点为 `TaskP1$F1M2C2`，且 `crash={}`。
