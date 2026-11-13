# 第 19 批：QQ Splash 消失——两侧探针定位到「客体自己抛了、我们没把 LoginActivity 交出去」

- 日期：2026-11-13 23:04–23:06
- 设备：d861a4fa（已解锁），构建：build-traces-v89（APK 2,327,795 B，sha256 前 16 位 55acb68079cca454），安装 Success
- 上游：batch18（v88 宽回归，QQ 是唯一异常）
- 脚本：`/tmp/qq89.sh`（双 force-stop → `logcat -c` → Workbench → 两次重连 → 点 QQ → 45s → 读 top / dumpsys / ps / 新探针 / crash）

## 1. 本批新增的两处探针（v89）

| 探针 | 位置 | 作用 |
| --- | --- | --- |
| `RuntimeStartTrace`（tag `TripoderStarts`，上限 200 行/进程，`-Dtripoder.trace.starts=false` 可关） | `RuntimeSelfHookActivities.routing()` 的 `execStartActivity*` 钩子入口与路由成功点 | 客体自己每次 startActivity 的 component / caller / flags，以及它有没有被交给平台 |
| `TripoderWire SLOW`（阈值 `-Dtripoder.wire.slowMs`，默认 500ms） | `RuntimeWire.Endpoint.onTransact` 的 finally | authority 侧每个事务的真实耗时 + 事务名（`RuntimeWire.name(code)` 常量表） |

顺带把 `code=27` 判明：`RuntimeWire` 常量表里 **27 = `BROADCAST_ROUTE`**（不是 Activity 相关事务）。

## 2. 同一轮里两侧读数对得上

```
-- authority 侧（新探针）
23:04:57.960 W TripoderWire: SLOW LOAD              2130ms pid=18564 uid=10542
23:04:58.787 W TripoderWire: SLOW START_APPLICATION  827ms pid=18564 uid=10542
23:05:06.649 W TripoderWire: SLOW BROADCAST_ROUTE   6154ms pid=18747 uid=10542
-- 客体侧（PerfMonitor，batch18 抓到的那一族）
23:05:06.650 W libbinder.BpBinder: PerfMonitor binderTransact: time=6154ms interface=dev.tripoder.runtime.v1 code=27
```

两侧数字一模一样（6154ms / 6154ms），**慢在 authority 服务端，不在客体的 binder 封装**。同一窗口还看到 `LOAD 2130ms`、`START_APPLICATION 827ms`。这批第一次让"慢 authority"从怀疑变成一个带事务名和毫秒数的账。

## 3. QQ 的 Splash 到底做了什么（决定性）

```
23:04:58.812 I TripoderActivityDelivery: constructionTarget=com.tencent.mobileqq.activity.SplashActivity;logicalAlias=false
23:04:58.817 I TripoderActivities: performLaunchActivity: physical=ComponentInfo{dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP0$F0M1C0}
23:04:58.933 I TripoderActivities: RETURNED from original.call(args) in performLaunchActivity, result=SplashActivity@67abffc
23:04:58.908 W System.err: ... BaseApplicationImpl.getRuntime(P:22) ← eldermode.c.g ← splashad... ← NtStartupDispatcher
23:04:59.430 I TripoderStarts: #1 CALL target=com.tencent.mobileqq/.activity.LoginActivity;caller=com.tencent.mobileqq.activity.SplashActivity;flags=0x40000
23:05:00.069 D VRI[SplashActivity]: visibilityChanged oldVisibility=true newVisibility=false
23:05:00.078 I WindowManager: wms.hideSurface ... com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity
```

三条结论：

1. **Splash 是我们正常创建并渲染出来的**（`constructionTarget`、`performLaunchActivity`、窗口、BufferQueue 都齐），不是被漏投递。
2. **Splash 的 onCreate 在 QQ 自己的代码里抛了**：`SplashActivity.onCreate → mqq.app.AppActivity.onCreate → BaseApplicationImpl.onActivityCreate → startup.director → NtStartup → NtStartupDispatcher → splashad/t.c → splashad/config/d.d → eldermode.c.i/f/g → BaseApplicationImpl.getRuntime`。堆栈从 `D8$$SyntheticClass` 一路穿到 `ZygoteInit.main`，说明它是 QQ 自己 catch + `printStackTrace` 到 System.err 的，所以 `logcat -b crash` 是 0 条。
3. **Splash 之后确实发起了 LoginActivity**，只有一次，`caller=SplashActivity`、`flags=0x40000`（`FLAG_ACTIVITY_NO_USER_ACTION`，**没有 NEW_TASK**）。而 `TripoderStarts` 里**没有对应的 `ROUTED` 也没有 `ABSENT` 行**——说明这次 start 既没走到「交给平台」的成功点，也没走到我们记码的失败点：它在 `RuntimeBridge.route()` / `routeFromContext()` 里就抛了，或者落进了 `catch(ActivityNotFoundException)` 的 `PACKAGE_NOT_IN_SPACE` 分支（该分支目前不打 ours trace）。

之后 0.6 秒 Splash 隐藏窗口并结束，任务栈里 `dumpsys activity activities | grep -c "com.tencent.mobileqq/"` = **0**，两个客体进程仍活着（`com.tencent.mobileqq` 10542），crash 计数 **0**。

## 4. 因此 QQ 这条不是"引擎谎报已完成"

batch18 的判断要修正为更准确的一句：**客体确实启动了、Splash 也确实渲染了，失败点在"Splash 决定去 LoginActivity 之后"**——QQ 侧因为自己的 eldermode/splashad 初始化抛异常而走进降级分支（去登录页），我方则没有把这次 LoginActivity 起步交出去，于是既没有新任务、也没有失败弹窗、也没有崩溃。

## 5. 剩余待判（下一轮）

1. **补两处 ours trace**：在 `catch(ActivityNotFoundException)` 的 `PACKAGE_NOT_IN_SPACE` 分支打 `TripoderStarts ABSENT PACKAGE_NOT_IN_SPACE`，并在 `RuntimeBridge.route/routeFromContext` 抛点外包一层 `ABSENT <code>`。有了这一行就能区分"被我们拒了"与"被我们改写后平台拒了"。
2. **BROADCAST_ROUTE 6.1s**：这条现在有名字了（不是 Activity 事务），需要单独一轮量它在 authority 侧花在哪（投递排队 / 账本写入 / 客体回调等待），因为它已经两次出现在启动窗口里。
3. QQ 的 `BaseApplicationImpl.getRuntime` 抛出值得单独看一眼是不是与我们的门面有关（例如 QQNT 初始化里读某个系统服务/属性）；本轮只记到栈，没有判定。

## 6. 边界

- 只测了 QQ 一个应用的一轮冷启；没有对其他应用重跑 v89（新增探针是"观测型"，但每次 startActivity 都会多一行日志）。
- `TripoderStarts` 的 200 行上限在本轮远未触顶（QQ 只有 1 行）。
- `PerfMonitor` 的 6154ms 是客体内核层读数（`libbinder.BpBinder`），我们自己的 `TripoderWire` 是服务端读数；两者相等只说明"服务端确实耗时那么久"，不说明为什么慢。
