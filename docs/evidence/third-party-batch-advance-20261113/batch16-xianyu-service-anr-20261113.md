# 第 16 批：闲鱼重入失败的真因前移——不是栅栏，是 `OrangeApiService` 服务执行 ANR

- 日期：2026-11-13 22:44–22:47
- 设备：d861a4fa（已解锁，isKeyguardShowing=false）
- 机上构建：build-fence-v87
- 关联：batch15（`batch15-xianyu-uninteractive-and-fence-scope-20261113.md`）

## 0. 结论摘要

1. 上一轮认定的「闲鱼被 admission 栅栏挡住」**只在特定条件下成立**。本轮清掉遗留客体子进程后重走，栅栏**没有点名**（无 `admission-blocked` 行、无栅栏弹窗），启动被正常受理。
2. 真实阻塞前移到**服务执行 ANR**：`RuntimeServiceSlots$S0_0` 执行客体的 `com.taobao.orange.service.OrangeApiService`（阿里 Orange 配置服务）时，slot0 主线程 20s 未返回 → AMS 判 `ANR in dev.tripoder.q6:slot0 -- executing service …S0_0, waited 20005ms` → slot0 被杀 → Workbench 报 `ACTIVITY_DELIVERY_PROCESS_DIED`。
3. ANR 抓到的 slot0 主线程栈**不在我们的门面里**，而在闲鱼自己的启动管线：`MainProcess.onCreate` → `FishNewBlink.prepare` → `BlinkNewEngine.execParallelSync` → `ParallelSyncRun.execRun`（`ConditionObject.await`）。即：**客体的 Application.onCreate 在 20s 窗口内没跑完**，主线程因此无法及时执行服务回调。
4. 同一份 ANR 的 CPU 采样里 **`:authority` 占 74%（55% user + 19% kernel）**，是全场最高。这是「容器拖慢客体启动」的量化线索，也是下一步最值得查的点。
5. 遗留客体子进程（`:channel` / `:windowChannel`，uid 10542）在 slot0 死后**存活**，它们把该包作用域的 `sessions` 钉在 RUNNING，于是**下一次尝试才会被 liveness 子句挡住**。两个缺陷互为因果：先 ANR 死主进程 → 留下子进程 → 栅栏封包。

## 1. 复现步骤（可重跑）

```bash
D="adb -s d861a4fa"
$D shell am force-stop dev.tripoder.q6; sleep 3
$D shell am force-stop dev.tripoder.q6; sleep 2      # 两次才收干净遗留子进程
$D shell am force-stop dev.tripoder.q7; sleep 2
$D shell logcat -c
$D shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
# Workbench 列表 → 滚动到「闲鱼」→ 点
# 等待 40s
```

观测点：

```bash
$D shell dumpsys activity activities | grep topResumedActivity
$D shell ps -A -o PID,UID,NAME | grep 10542
$D shell 'logcat -d' | grep -E 'ANR in dev.tripoder|admission-blocked|ACTIVITY_DELIVERY'
$D shell 'dumpsys dropbox --print data_app_anr'   # 完整 ANR 线程栈
```

## 2. 本轮实测记录

### 2.1 第一次（未清槽位，冷启闲鱼）

- Workbench 横幅：`启动应用「闲鱼」 · 结果未知：操作没有完成（ACTIVITY_DELIVERY_PROCESS_DIED）。请先核对实际状态，勿重复提交`
- 列表项：`闲鱼 ● 后台运行`
- 进程（uid 10542）：`com.taobao.idlefish:channel` 25163、`com.taobao.idlefish:windowChannel` 25367 —— **无主进程**
- `topResumedActivity = WorkbenchActivity`
- crash 缓冲：空
- 无 `admission-blocked` 行

### 2.2 第二次（double force-stop 清干净后重走）

清槽位后 `ps` 只剩原生（uid 10328）：`com.taobao.idlefish` 13079 + 其 `:channel/:windowChannel/:normal_render_proc0/:gpu_process`。

点「闲鱼」后：

- 22:46:10 slot2（28093）打 `TripoderBoot: PROCESS_IDENTITY_READY`
- 22:46:10 slot1（27844）在 `unbindService: com.taobao.idlefish/com.taobao.accs.ChannelService → RuntimeServiceSlots$S1_1`
- **22:46:21.470 AMS：`Timeout executing service: ServiceRecord{… RuntimeServiceSlots$S0_0}`**
- 22:46:25.599 `ActivityManager: ANR in dev.tripoder.q6:slot0`
- 22:46:35.729 `AnrEventHandler: ANR in dev.tripoder.q6:slot0 -- executing service dev.tripoder.q6/dev.tripoder.runtime.RuntimeServiceSlots$S0_0, waited 20005ms`
- 40s 后：top 仍为 Workbench；`ps` 又只剩 `:channel`(27844) 与 `:windowChannel`(28093)；crash 空

### 2.3 被执行的到底是哪个服务

```bash
$D shell dumpsys activity services dev.tripoder.q6 | grep -A3 'S0_0'
```

```
ServiceRecord{1709bf1 u0 dev.tripoder.q6/…RuntimeServiceSlots$S0_0 c:dev.tripoder.q6}
  intent={act=com.taobao.orange.service.OrangeApiService cat=[android.intent.category.DEFAULT]
          cmp=dev.tripoder.q6/…RuntimeServiceSlots$S0_0}
  infoAllowStartForeground=[callingPackage: dev.tripoder.q6; callingUid: 10542; uidState: TOP ; …]
  ConnectionRecord×3（三个绑定方）
```

即：闲鱼启动期用 Orange（阿里配置中心 SDK）**绑定自己的 `OrangeApiService`**，容器把它映射到 `RuntimeServiceSlots$S0_0`。三条 `ConnectionRecord` 说明三方都在等这个服务。

## 3. ANR 线程栈（决定性）

`/data/anr/*` 为 root-only（`grep` 读不到），但 `dumpsys dropbox --print data_app_anr` 可读。

slot0 主线程：

```
"main" prio=5 tid=1 TimedWaiting
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:276)
  at java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await(…:1892)
  at com.taobao.idlefish.launcher.startup.blinknew.BlinkNewEngine$ParallelSyncRun.execRun(BlinkNewEngine.java:115)
  at …BlinkNewEngine.execParallelSync(BlinkNewEngine.java:167)
  at …FishNewBlink.now(FishNewBlink.java:17)
  at …FishNewBlink.prepare(FishNewBlink.java:88)
  at com.taobao.idlefish.startup.process.MainProcess.onCreate(MainProcess.java:175)
  at com.taobao.idlefish.TaoBaoApplication$4.run(TaoBaoApplication.java:14)
  at android.os.Handler.handleCallback / Looper.loopOnce
```

同一份 ANR 的 CPU 采样：

```
74% 24615/dev.tripoder.q6:authority: 55% user + 19% kernel / faults: 469683 minor 234 major
 8.7% 24826/com.taobao.idlefish: 6.7% user + 1.9% kernel
22% 3876/system_server
```

**判读**：主线程卡在闲鱼自己的并行启动同步点（等一个子任务），不是在等我们的门面；
但同一时刻我们的 `:authority` 在烧 74% CPU，是拖慢这批子任务的最显眼嫌疑。
AMS 的 20s「服务执行」预算从服务拉起时开始算，主线程被 Application.onCreate 占着就直接判 ANR。

## 4. 与上一轮结论的关系（重要修正）

| 上一轮说法 | 本轮修正 |
| --- | --- |
| 闲鱼被 admission 栅栏挡住，detail/command 都不是否决项，liveness 子句是 | 成立，但**只在「已有遗留子进程」时**。清干净后栅栏不点名，启动被受理 |
| 栅栏 liveness 被 force-stop 遗留子进程钉住 | 成立且更精确：钉住它的是**主进程 ANR 后存活的 `:channel`/`:windowChannel`**，即 ANR 是这个因果链的上游 |
| 「修 liveness 就解锁闲鱼」 | 不够。修完 liveness 只能让「重入」不被挡，重入后仍会撞 OrangeApiService 服务 ANR |

因果链（本轮实测）：

```
冷启闲鱼
  → 闲鱼启动期 bind com.taobao.orange.service.OrangeApiService（映射到 RuntimeServiceSlots$S0_0）
  → 主线程仍在 Application.onCreate（BlinkNewEngine.execParallelSync 等子任务）
  → 20s 内服务回调没机会执行 → AMS 判 service ANR
  → slot0 被杀（ACTIVITY_DELIVERY_PROCESS_DIED）
  → :channel / :windowChannel 存活
  → 该包 scope 的 sessions 停在 RUNNING
  → 下一次启动被 liveness 子句挡住（上一轮看到的现象）
```

## 5. 下一步（按性价比排序）

1. **查 `:authority` 的 74% CPU**：这是唯一可量化的「容器拖慢客体启动」因子，也是能动手的地方。取证口径：ANR 前 30s 的 `logcat -d | grep Tripoder`（看是否某个门面在自旋 / 反复 recover），必要时在 authority 内打一段有界的调用计数（复用 `RuntimeMutationGuard` 的诊断入口，不新增常驻日志）。
2. **给 `RuntimeServiceSlots` 的执行加时间证据**：现在只知道 AMS 判了 20s，不知道我们自己排队花了多久。在派发前后各打一行（含 intent action 与等待时长）即可把「我们慢」与「客体慢」分开。
3. **liveness 子句按会话主人判活**（上一轮方案，仍然要做但不是当前瓶颈）：会话的 owner 进程已死、仅其子进程存活时不应判活；否则每次 ANR 都会顺手封掉一个包。
4. 闲鱼登录闭环（U4 黑屏 / FishUserLoginActivity 不可交互）**暂不再投入**：入口层已证明不可交互（batch15 §2），且在应用都起不来的当下没有观测面。

## 6. 边界

- 本轮两个 ANR 之间相隔约 2 分钟，形状完全一致（同一 `S0_0`、同一 action、20s），不是偶发。
- 原生闲鱼（uid 10328）在同一设备上启动正常，其 `:channel/:windowChannel/:normal_render_proc0/:gpu_process` 子进程都在——所以「子进程多」不是异常，容器内的差异在速度与 ANR 预算。
- 未测：给 `tripoder.startup.timeoutMs` 或 AMS 的 service timeout 做对照（后者不可改）；也未测「先让闲鱼在容器内跑过一次成功冷启后再重试」是否还能过（v86/v87 曾通过，说明存在可通过的路径，本轮未复现）。
