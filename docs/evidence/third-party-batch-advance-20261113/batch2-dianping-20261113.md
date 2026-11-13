# 第 2 批 · 大众点评（com.dianping.v1）：首次冷启动自锁超时，重试一次直达天翼一键登录

- 设备：`d861a4fa`（MIUI，Android 16）
- 容器构建：`build-facade-v66`，`tripoder-q6.apk` sha256 `b584e4f3…`
- 时间：2026-11-13 13:04–13:10
- 结论：**可用**。第二次启动到达天翼账号「本机号码一键登录」页（掩码 `153****8553`），
  两个进程存活、无 Java 崩溃、准入栅栏 0。

## 1. 事件序列（两次启动，同一份数据目录）

| # | 动作 | 结果 |
|---|---|---|
| 1 | 清槽位 → Workbench → 点「大众点评」 | `phase=APPLICATION` 卡住，120s 后 UI 弹「应用没有启动」 |
| 2 | 点「取消」→ 再点「大众点评」（**不清数据**） | 通过：`TaskP1$F0M0C2`，登录页可达 |

## 2. 第一次：超时记录

```
13:06:23.945  5028  5238 E TripoderSupervisor:
  ProcessSupervisor.start failed for default/com.dianping.v1/com.dianping.v1 phase=APPLICATION
```

`phase=APPLICATION` 的含义：客体已 attach、`Application.onCreate` 已进入，但「创建完成」始终没有回报。
UI 文案（120s 预算到期）：

> 应用没有启动 / 「大众点评」的启动在 2 分钟内没有完成。容器同时运行的应用越多，启动越慢；
> 先停掉其它正在运行的应用再重试，通常就能起来

本轮是**空槽位、单应用**，所以「应用越多越慢」不是本次原因（也一并更正了
`h22-start-request-hangs-without-timeout` 里对 dianping 的旧解释：不是容量、不是慢，而是下面的死锁）。

## 3. 根因：大众点评自己的两把锁 ABBA 死锁（不是门面缺陷，不是风控）

超时时刻 runtime 抓了 60 个线程的栈（`TripoderStartup`，JSON 一行一线程）。关键两条：

**main（BLOCKED）**

```
com.dianping.app.o.e                      ← 想要锁 A
com.dianping.app.o.g
com.dianping.app.o.f                      ← 已持锁 B
com.dianping.app.o.g
com.dianping.app.DPApplication.getService
com.dianping.mainapplication.task.g0.b
com.meituan.android.aurora.E.run
…
com.dianping.mainapplication.NovaMainApplication.whenOnCreate
com.meituan.android.aurora.AuroraApplication.onCreate
com.dianping.v1.NovaHydraApplication.onCreate
android.app.Instrumentation.callApplicationOnCreate
dev.tripoder.runtime.RuntimeEngine.lambda$new$2(RuntimeEngine.java:385)
```

**pool-4-thread-2（BLOCKED）**

```
com.dianping.app.o.f                      ← 想要锁 B
com.dianping.app.o.g
com.dianping.app.DPApplication.getService
com.dianping.app.DpIdManager.monitorDpid
com.dianping.app.DpIdManager.requestDpid
com.dianping.app.DpIdManager.getOneIdByDpid
com.dianping.nvnetwork.NVGlobal.unionid
com.dianping.monitor.impl.a.initRaptorSelfMonitor
com.dianping.nvnetwork.NVGlobal$d.<init>
com.dianping.nvnetwork.NVGlobal.monitorService
com.dianping.dataservice.mapi.impl.DefaultMApiService.<init>
com.dianping.app.o$b.<init>
com.dianping.app.o.e                      ← 已持锁 A
com.dianping.app.o.g
com.dianping.app.DPApplication.getService
…
com.dianping.codelog.Utils.f$c.run
```

即：main 持 B 求 A，`pool-4-thread-2` 持 A 求 B，**互相等，永久**。
另有 `dpid-query` / `parallel` / `pool-4-thread-1` 三个线程同样阻塞在 `o.e`/`o.f`，
说明这是应用的服务注册表在冷启动时被并发进入（日志线程 `codelog` 与 `DpIdManager` 在 main
还在 `onCreate` 里时就开始 `getService`），并非我们注入的线程。

判据口径：栈里 `dev.tripoder.runtime.*` 只出现在**调用 `Instrumentation.callApplicationOnCreate`
的那一帧**，其后整段都是 `com.dianping.*` / `com.meituan.android.aurora.*`；
没有 `Error receiving broadcast`、没有 `PROVIDER_*`、没有我方 facade 抛点。
所以这是**应用内部锁序竞态**，容器只是提供了让它发生的调度（冷启动 + 首次无 dpid）。

## 4. 第二次：为什么重试就好

第一次超时后 runtime 只杀进程、**不清客体数据目录**；`DpIdManager` 需要的一次性 dpid 拉取
在第一次里已经走完/落盘，第二次 `getService` 走的是缓存分支，不再递归构造
`DefaultMApiService` → 不再进入 A→B 嵌套，锁竞态窗口消失。

实测（第二次点击后 45s）：

```
topResumedActivity = TaskP1$F0M0C2
u0_a542  7035  com.dianping.v1               (R)
u0_a542  7455  com.dianping.v1:dppushservice (S)
crash buffer: 无 Java 栈
UI: login_back_button / 153****8553 / 认证服务由天翼账号提供 /
    「本机号码一键登录」/ 《美团用户服务协议》《隐私政策》… / 其他登录方式
```

## 5. 登录链路推进到哪里

1. 点「本机号码一键登录」→ 天翼账号协议弹窗（《美团用户服务协议》《隐私政策》《天翼账号认证服务条款》，拒绝/同意）
2. 点「同意」→ H5 滑块验证「请向右滑动滑块验证」
3. 自动化 swipe 通过滑块 → **图片字符验证码**（`请输入图片中的内容` + `yodaCodeInput` + `验证`）

停在第 3 步：图片验证码是**人机验证**，本轮不代答（与微博登录同一处置口径：这类一次性人机挑战交用户）。
容器侧的结论已经成立：**大众点评在容器内没有被来源/环境类风控拦**，
它和原生一样走到运营商一键登录，再往后的验证码是运营商的通用反自动化步骤。

## 6. 边界与未归因项

- crash 缓冲里有一行 `F libc: FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x781941d200)`（pid 7561，13:07:53）。
  该 pid 不属于两个存活进程，日志已被 `logcat -c` 覆盖，**无法归因**；没有 Java 栈，不影响本次结论。
- 第一次超时的 120s 预算是既有行为（`RuntimeStartupDeadline`），不是新缺陷；本轮**没有改代码**。
- 只在 `build-facade-v66` 上验证一次重试；「重试必好」还需要在清数据（新装）场景下再测一次才能升级为规律。

## 7. 复现

```bash
adb -s d861a4fa logcat -c; adb -s d861a4fa logcat -b crash -c
adb -s d861a4fa shell am force-stop dev.tripoder.q6
adb -s d861a4fa shell am force-stop dev.tripoder.q7
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
python3 android/scripts/guest_ui.py tap "重新连接"      # 可能需要两次
python3 android/scripts/guest_ui.py tap "大众点评" --scroll
sleep 130                                                # 等 120s 预算到期，读出 start failed
adb -s d861a4fa logcat -d -s TripoderSupervisor:V | grep dianping
# 点「取消」后原地重试（不清数据）
python3 android/scripts/guest_ui.py tap "取消"
python3 android/scripts/guest_ui.py tap "大众点评" --scroll
sleep 45; python3 android/scripts/guest_ui.py top; python3 android/scripts/guest_ui.py dump
```
