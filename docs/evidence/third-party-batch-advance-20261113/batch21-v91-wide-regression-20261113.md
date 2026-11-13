# 第 21 批：intent flags 判据的第二处 + v92 六应用宽回归

- 日期：2026-11-13 23:15–23:22
- 设备：d861a4fa（已解锁）
- 版本：v91（batch20 已装机）→ v92（本次：嵌套 intent flags 同判据）
- 关联：`batch20-qq-intent-flags-20261113.md`

## 1. 为什么要先回归再扩大改动面

batch20 把 `RuntimeActivityIntent.requireFlags()` 从「白名单外一律抛」改成「只拒契约性标志」。这个函数在**每一个**客体的 `startActivity` 上执行，但只在 QQ 一条冷启路径上验过 ⇒ 必须先做宽回归，再动同源的第二处；否则一旦放宽放进不该放的东西，会同时污染两个变量的读数。

## 2. batch21-①：嵌套 intent flags 改成同一判据

`RuntimeActivityValues` 对 extras 里的嵌套 `Intent` 用的是另一套白名单 + 独立码：

```java
// 前
int flags=intent.getFlags();
if((flags&~(RuntimeActivityIntent.FLAGS|Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT|Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))!=0)
    throw new IllegalArgumentException("ACTIVITY_NESTED_INTENT_FLAGS_UNSUPPORTED");
```

```java
// 后（与起点同判据）
int flags=intent.getFlags();
if((flags&RuntimeActivityIntent.EXCLUDED)!=0)
    throw new IllegalArgumentException("ACTIVITY_NESTED_INTENT_FLAGS_UNSUPPORTED:0x"+Integer.toHexString(flags));
```

判据链（第一性）：嵌套 `Intent` 是**数据**，不是启动许可 —— 真正的派发会在路由点重新按客体能力准入。因此它既不携带调用方身份，也不带结果通道，`FORWARD_RESULT` 那一族在这里同样无意义；其余任务位（含未知位）是平台自己的任务引擎提示，平台对未知位是忽略而不是拒绝。白名单形状唯一的作用是造出平台不会发生的失败。

码保留（`ACTIVITY_NESTED_INTENT_FLAGS_UNSUPPORTED`），并补了 `0x` 位掩码，便于下次从日志直接判断是哪个位。

## 3. batch21-②：v92 构建装机

```
python3 android/scripts/build-workbench.py --build-dir android/product/build-nested-v92 … --package dev.tripoder.q6
BUILD_EXIT=0 ；APK=2,327,795 B ；sha256 前 16 位 a131b24b12dd862a ；INSTALL_OK
```

## 4. batch21-③：六应用宽回归（`/tmp/v92wide.sh`）

每轮：双 force-stop q6 → force-stop q7 → `logcat -c` → 开 Workbench → 两次「重新连接」→ 点应用 → 最多 40s 轮询 top。

| 应用 | 用时 | top | 客体进程 | anr | admission-blocked | fatal | routed | absent |
|---|---|---|---|---|---|---|---|---|
| 微信 | 6s | TaskP0$F0M0C3 | 2 | 0 | 0 | 0 | 4 | 0 |
| 京东 | 10s | TaskP0$F0M1C0 | 2 | 0 | 0 | 0 | 0 | 0 |
| 美团 | 4s | TaskP0$F0M1C2 | 2 | 0 | 0 | 0 | 0 | 0 |
| 微博 | 4s | TaskP0$F0M0C0 | 2 | 0 | 0 | 0 | 0 | 0 |
| 拼多多 | 2s | TaskP0$F0M1C2 | 2 | 0 | 0 | 0 | 0 | 0 |
| 快手 | 4s | TaskP0$F0M1C2 | 2 | 0 | 0 | 0 | 0 | 0 |

判据：6/6 全部落到客体 `TaskP*`、`fatal=0`、`admission-blocked=0`、`ABSENT=0`。与 v88 同配方基线逐项一致（微信 6s / 京东 10s / 美团 4s / 微博 4s / 拼多多 2s），**放宽 flags 没有引入任何新降级或崩溃**。微博的 `facade=2` 是已知良性降级（v56 起就存在），不是本轮新变化。

## 5. batch21-④：QQ 复测（含嵌套 intent 路径）

```
#1 CALL   target=com.tencent.mobileqq/.activity.LoginActivity;caller=…SplashActivity;flags=0x40000
#2 ROUTED target=com.tencent.mobileqq/.activity.LoginActivity
ROUTING activity: caller=SplashActivity target=LoginActivity stub=TaskP0$F0M0C2 flags=0x40000
topResumedActivity = dev.tripoder.q6/…generated.TaskP0$F0M0C2
com.tencent.mobileqq/ records = 16 ；mobileqq 进程 4 ；TripoderStarts ABSENT = 0 ；FATAL = 0
```

与 v91 读数逐项相同 ⇒ 嵌套判据的改动没有回归，QQ 的修复保持。

## 6. 副产物：BROADCAST_ROUTE 仍是启动窗口最长单次事务

同轮真空读数（v92，客体 pid 2959 / authority pid 2506）：

```
SLOW LOAD 2055ms keys=[…]
SLOW START_APPLICATION 1078ms
SLOW BROADCAST_SYSTEM_CLAIM 540ms
SLOW BROADCAST_ROUTE 500ms  →  SLOW BROADCAST_ROUTE 6400ms keys=[ordered,payload,generation,receiverPermission,operation]
PerfMonitor binderTransact: time=6401ms interface=dev.tripoder.runtime.v1 code=27（客体工作线程 3333）
```

两侧数字相等（6400 vs 6401），仍判「慢在服务端且落在客体工作线程」。注意本轮出现了两条 BROADCAST_ROUTE：500ms（早）与 6400ms（QQ 启动后约 9s），说明该 6.4s 是**特定一次投递**，不是通道固有延迟 —— 拆解应针对后者的 operation/receiver，而不是把整个码当慢。

## 7. 边界

- 宽回归只读「应用是否落到客体 Activity」这一条判据（top + 进程 + 崩溃/栅栏/ABSENT 计数），**没有**逐个进入各应用的业务页；业务链路的可用性仍按各应用自己的证据文档判定。
- 完整六应用批量冷启都从 Workbench 出发，属「已知可启动」的 21+ 应用集合，不覆盖新导入应用。
- `RuntimeActivityValues` 的其他形状校验（`ACTIVITY_NESTED_INTENT_SHAPE_UNSUPPORTED` 等）本轮未动，仍属可疑的白名单形状。
