# 有序广播语义：微博「同意并继续」硬崩的判据与修复（v60）

日期：2026-11-13　设备：`d861a4fa`　构建：`android/product/build-facade-v60`（`tripoder-q6.apk` sha256 `0af4a9cd406f2b37652bf484004e7594ed4b0c75b55c86884d7f2c8ff2826d91`）

## 1. 现象与完整栈

容器内微博（`com.sina.weibo`，进程 uid=10542）在《用户协议和隐私条款》点「同意并继续」后进程猝死，两次独立复现（04:11:07 pid 11623、04:16:37 pid 16258），栈完全相同：

```
java.lang.IllegalStateException: Call while result is not pending
  at android.content.BroadcastReceiver.checkSynchronousHint(BroadcastReceiver.java:769)
  at android.content.BroadcastReceiver.setResultData(BroadcastReceiver.java:537)
  at com.sina.weibo.PushService.b(PushService.java:433)
  at com.sina.weibo.PushService$MyBroadcastReceiver.a(PushService.java:268)
  at com.sina.weibo.PushService$MyBroadcastReceiver$1.run(PushService.java:180)
  at com.sina.weibo.thread.j.run(PriorityRunnable.java:58)
  at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1248)
```

两条硬事实：

1. **栈里没有任何 `dev.tripoder.runtime` 帧**——客体是在**它自己的线程池**里调 `setResultData`，不是在我们调用 `onReceive` 的过程中。
2. `grep` 全 runtime：只有一处调用 `guest.onReceive`（`RuntimeReceiverProxy:30`），它在调用前把平台给的 pendingResult 装到客体上、在 `finally` 里**把客体那个字段置 null**。

所以判据不是「我们丢没丢 ordered 标志」，而是一个更窄的问题：**平台在投递返回之后，是否还让 receiver 保留 pendingResult？**

## 2. 判据：在这个设备上直接测平台

`ControlTestIntegration`（`BroadcastResolutionIntegration#pendingResultLifetime`）在真平台里注册一个 receiver，在 `onReceive` 内调一次 `setResultData`，投递返回 250ms 后再调一次，两次都记下平台的回答；ordered/非 ordered 各测一遍。

运行：`runId=control-b8c6edfb45981cd5`，`checks=36 passed=true`

```
PASS set-result-data-accepted-during-delivery                | accepted
PASS platform-accepts-set-result-data-after-delivery        | accepted
PASS ordered-set-result-data-accepted-during-delivery       | accepted
PASS platform-accepts-ordered-set-result-data-after-delivery| accepted
```

**平台在投递返回后并不清掉 receiver 的 pendingResult**（至少在本设备的 Android 16 框架上）。客体保存在字段里的那个 receiver，事后调 `setResultData` 仍然合法。这正是微博敢把 `setResultData` 丢进线程池的原因。

## 3. 结论：我们制造了平台不会制造的状态

`RuntimeReceiverProxy` 原先在 `finally` 里 `pendingResult(guest,null)`，等于**在客体的视角里抹掉了一个平台会保留的事实**。这与前几轮修的族是同一族，只是方向相反：

| 轮次 | 缺陷方向 | 表现 |
|---|---|---|
| v55/v56 | 抛了平台不会抛的异常 | BOSS直聘/京东「应用凭空崩溃」 |
| v60（本轮） | **收走了平台会给的对象** | 微博在同意条款瞬间死在推送线程 |

修复：`RuntimeReceiverProxy` 不再把客体的 pendingResult 置 null（`goAsync` 的清除由客体自己完成，`goAsync()` 本来就会把该字段取走）。一处 3 行改动，语义是「镜像平台」。

## 4. 端到端验证（真机）

v60 装机后，`TargetLaunchIntegration` 导入并启动微博，随后纯 UI 驱动：

| 步骤 | 结果 |
|---|---|
| 启动 | `topResumedActivity=TaskP0$F0M0C0`，《用户协议和隐私条款》渲染 |
| 点「同意并继续」 | **crash 缓冲清空后仍为空**；activity 前进到 `TaskP0$F0M2C2` |
| 首页 | `com.sina.weibo:id/home_view_pager`、推荐/关注/热门 tabbar、右上「登录」——**微博首页可浏览** |
| 点「登录」 | 登录页：`本机号码一键登录` / `其他手机号码登录` / 天翼账号认证服务协议 |
| 点「本机号码一键登录」 | 天翼授权框（不同意 / 同意并登录） |
| 点「同意并登录」 | **:remote 进程 FATAL**（见 §5），主进程存活但被带到后台，桌面回到前台 |

`:remote` 存活、主进程未重启，`TripoderFacade` 只有两类记录：
`SERVICE_START_FAILED … PACKAGE_NOT_IN_SPACE:com.xiaomi.xmsf`（真缺包，降级）与 `RECEIVER_REGISTRATION_IN_PROGRESS`（注册进行中，降级）。

## 5. 新墙：登录动作里的 `RECOVERY_SCOPE_UNKNOWN`

```
FATAL EXCEPTION: main   Process: com.sina.weibo:remote
java.lang.RuntimeException: Unable to start service com.sina.push.service.SinaPushService …
  at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:6515)
Caused by: java.lang.IllegalStateException: RECOVERY_SCOPE_UNKNOWN:9f62d003-6efd-4162-aee9-58c4f7800dfb-12
  at android.os.Parcel.readException(Parcel.java:3323)
  at dev.tripoder.runtime.RuntimeWire.call(RuntimeWire.java:69)
  at dev.tripoder.runtime.RuntimeEngine.brokerCall(RuntimeEngine.java:449)
```

`RECOVERY_SCOPE_UNKNOWN` 来自 `ExecutionContract:456`：**有一个更早的操作没有确认结果，admission 因此挡住这次新操作**。它是运行时自己的不变式，却顺着 `RuntimeServiceSlots$S1_2` 的服务启动路径抛进了平台的 `handleServiceArgs`，于是「服务起不来」变成了「进程崩溃」——与 v55/v56 同一判据的复发点（门面不得把运行时不变量当异常投给客体）。

两条独立的后续工作：

1. **服务启动路径按判据降级**：admission 拒绝 → 记 `SERVICE_START_FAILED` 并返回「没启动」，绝不逸出到 `handleServiceArgs`。
2. **把 fence 本身处理掉**：`9f62d003…-12` 那条未确认的操作需要 recovery（`Workbench` 侧已有 `AgentRecovery` 的入口），否则容器会一直带着这道 fence。

## 6. 边界

- 判据**只在本设备（Android 16 / MIUI）**上测过；换框架版本要重跑 `pendingResultLifetime`。
- 「平台保留 pendingResult」是**行为判据**（事后调 `setResultData` 是否被接受），不是 AOSP 源码引用。
- 微博只推进到**登录页 + 天翼授权**，`同意并登录` 之后被 §5 的崩溃挡住，**登录未成功**。
- 本轮只跑了微博一个应用；`RECOVERY_SCOPE_UNKNOWN` 是否也在别的应用上出现**未测**。
- 第 1 批「微博启动失败」的旧结论已在上一轮修正为并发/槽位问题，本轮再次佐证：空槽位下启动稳定。

## 7. 复现

```bash
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v60 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.q6
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v60/tripoder-q6.apk
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v60/tripoder-control-test.apk
adb -s d861a4fa shell am instrument -w -r -e runId control-<16 hex> \
  dev.tripoder.controltest/dev.tripoder.controltest.BroadcastResolutionIntegration   # 36 项，含 4 项 pendingResult 判据

# UI 驱动（本轮新增，逐应用批量化用）
python3 android/scripts/guest_ui.py tap "同意并继续"
python3 android/scripts/guest_ui.py top
python3 android/scripts/guest_ui.py crash
```
