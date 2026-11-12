# 准入栅栏（RECOVERY_SCOPE_UNKNOWN）：微博登录被自己挡死的原因与两处已修的门面逸出

日期：2026-11-13　设备：`d861a4fa`　构建：`android/product/build-facade-v60` / `v61`（`tripoder-q6.apk` sha256 `f31ac2a18b455d7b7eb78ad72aba18ff0bc4c2cec25513b1b02e43ad4005aece`）

## 1. 从崩溃栈能把因果链钉死

微博在容器内点「同意并登录」后，**两个进程同时死**，而且是同一条栅栏：

```
:remote  SinaPushService.onStart(134) → ChannelManager(161/186) → MPSChannel(400/755)
         → PushTaskManager(90) → SocketPushTask(295/595/245) → PushAlarmManager(375)
         → [hook] RuntimePendingIntents.install(18) → RuntimeAlarms.invoke(34)
         → RuntimeEngine.brokerCall(449) → RuntimeWire.call(69)
         Caused by: IllegalStateException: RECOVERY_SCOPE_UNKNOWN:9f62d003-…-12

主进程   ScreenOrientationBaseActivity.startActivityForResult(1293) → BaseActivity(2077)
         → [hook] RuntimeSelfHookActivities.routing(211) → RuntimeBridge.route(151)
         → RuntimeEngine.route(669) → RuntimeWire.call(69)
         同样 RECOVERY_SCOPE_UNKNOWN
```

一句话：**客体做了一个平台完全允许的动作（设个闹钟、开个 Activity），我们的运行时用自己的一条不变式把它拒了，并且以未检查异常的形式投进客体线程。**

## 2. 两处门面逸出已修（v60/v61）

| 轮次 | 修复点 | 修复前 | 修复后 |
|---|---|---|---|
| v60 | `RuntimeReceiverProxy` 的 `finally` | 把客体的 pendingResult 置 null（平台并不清） | 镜像平台，不清；微博「同意并继续」不再崩 |
| v61 | `RuntimeAlarms.invoke` | 直接 `brokerCall`，异常逸出到 `handleServiceArgs` | 平台对「排不上的闹钟」本来就只返回 void，故 `ALARM_DROPPED` 记一条并返回良性答案 |
| v61 | `RuntimeEngine.route` | 只把 `PACKAGE_NOT_IN_SPACE` 转 `ActivityNotFoundException`，其余原样抛 | 走 `routeRefusal()`：平台答案原样抛，内部拒绝记 `ACTIVITY_ROUTE_REFUSED` 后降级 |

v61 实测（`04:31:57`）：

```
W TripoderFacade: ALARM_DROPPED dropped cause=…RECOVERY_SCOPE_UNKNOWN:51145061-…-11   ← :remote 不再死
W TripoderFacade: ACTIVITY_ROUTE_REFUSED dropped cause=…RECOVERY_SCOPE_UNKNOWN:51145061-…-11
FATAL EXCEPTION  com.sina.weibo  android.content.ActivityNotFoundException: ACTIVITY_ROUTE_REFUSED:…
```

**这一条必须如实说清楚：修复并没有让登录成功。** `ALARM_DROPPED` 确实把 `:remote` 救下来了（改之前它是 FATAL，改之后只是一行 W），但把活动路由降级成 `ActivityNotFoundException` **仍然崩主进程**——因为真实平台上「目标可解析的 startActivity」永远不会抛 ANFE，微博在这个调用点当然没有 catch。这是本轮最重要的负结果：**「换成平台形状的异常」还不够，只要平台在该调用点不会失败，我们就一个异常都不能给。**

## 3. 真正的根因：栅栏是**全局**的，而它被我们自己的降级触发

`ExecutionContract:456` 把 admission 拒绝翻译成 `RECOVERY_SCOPE_UNKNOWN`：**只要本进程还有一条「结果未确认」的先前操作，之后每一次 accept() 都被拒。** 结合上面那条时间线：

- 我们的广播门面**本来就会**主动丢弃广播（`BROADCAST_DROPPED … BROADCAST_PREPARATION_UNCONFIRMED` 在 04:31:57.651/.993/.59 连续出现）；
- 一次失败的 prepare 在账本里留下「未确认」；
- 于是这个进程后面的每个框架调用——闹钟、startActivity、provider——全部被拒。

也就是说：**「可观测的降级」这条我们前几轮定下的原则，被准入栅栏反噬成了「降级一次，进程废掉」。** 越是对不兼容应用做优雅降级，越容易触发这条栅栏；微博这种推送/分享/支付全挂的应用，几百毫秒内就能把栅栏点燃。

这不是某个调用点的错，是两个设计互相打架：

| 设计 | 本意 | 冲突 |
|---|---|---|
| 门面降级（v55–v61） | 内部限制不杀客体，只记一条 | 降级留下的半成品操作在账本里「未确认」 |
| 准入栅栏（`ExecutionContract`） | 效果可能重复前，不许开新操作 | 只按进程判，不按操作判 → 一次未确认 = 全局停摆 |

## 4. 下一步（按代价排序）

1. **给栅栏补 abort 出口（推荐先做）**：`prepare` 失败、`BROADCAST_PREPARATION_UNCONFIRMED`、`ALARM_DROPPED` 这些路径都要向 broker 提交一条「本操作已放弃/无副作用」的终态记录，让栅栏能被解开，而不是把一个进程永久钉死。判据是：同一条栅栏 ID 不该在放弃后继续拒绝后续 accept。
2. **把栅栏的作用域从「进程」收到「操作域」**：未被确认的 effect 只该挡「同一操作域」的新请求（例如同一条 provider 写），不该挡闹钟、不该挡 startActivity。
3. 上面两条都做完之前，**不要再在调用点加 try/catch 兜底**：本轮已经证明那只是把「ISE」换成「ANFE」，客体照样死。

## 5. 边界

- 修复只在微博一个应用上验证过；闹钟路径的 `ALARM_DROPPED` 是否在其它推送 SDK（小米 push、OPPO push）上同样有效**未测**。
- `RECOVERY_SCOPE_UNKNOWN` 的触发条件（哪一条操作留下的未确认）**尚未定位到具体账本行**：只知道它在本进程内必然出现一次，且 ID 每次尝试都不同（`9f62d003-…-12`、`51145061-…-11`）。
- 微博登录**仍未成功**：`同意并登录` 之后主进程被 `ACTIVITY_ROUTE_REFUSED` 崩掉，桌面回到前台。首页浏览、登录页、天翼授权框三步是通的。
- 本轮 `:remote` 的 `SinaPushService` 崩溃已消失（`ALARM_DROPPED`），但 `:remote` 是否完整可用（长连接/推送）**未测**。

## 6. 复现

```bash
# v61
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v61 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 --package dev.tripoder.q6
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v61/tripoder-q6.apk

adb -s d861a4fa logcat -b crash -c
python3 android/scripts/guest_ui.py tap "微博" --scroll      # 微博已同意过条款，直接进首页
python3 android/scripts/guest_ui.py tap "登录"
python3 android/scripts/guest_ui.py tap "本机号码一键登录"
python3 android/scripts/guest_ui.py tap "同意并登录"
python3 android/scripts/guest_ui.py crash
adb -s d861a4fa logcat -d -s TripoderFacade:W | tail
```
