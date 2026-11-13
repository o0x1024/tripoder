# 第 20 批：QQ「Splash 消失、任务栈为空」根因是我方的 intent flags 白名单——修后 LoginActivity 正常置顶

- 日期：2026-11-13 23:11–23:14
- 设备：d861a4fa（已解锁）
- 版本：v90（补 ABSENT 码，仅观测）→ v91（修 flags 策略）
- 关联：batch19（`batch19-qq-splash-start-trace-20261113.md`）

## 1. 先复核远端（batch19 遗留的推送疑点）

```
LOCAL=46e0e45e309ed2de3d348743f0064cd84208223b
git ls-remote origin refs/heads/merge-workbench-engine
46e0e45e309ed2de3d348743f0064cd84208223b
```

上轮 `PUSH_OK` 之后的 `ls-remote` 只是 SSL 抖动，远端确实已更新。batch19 的推送**已验证**。

## 2. v90：把「既无 ROUTED 也无 ABSENT」的最后一条静默通道关掉

`RuntimeSelfHookActivities.routing()` 里原本只有两条出口会留痕（成功的 `ROUTED`、`PACKAGE_NOT_IN_SPACE` 分支）。`RuntimeBridge.route()/routeFromContext()` 抛的任何其他异常都不留痕，于是 batch19 只能说到「没交给平台」。

v90 补了三处只读观测（有界、可按 `-Dtripoder.trace.starts=false` 关）：

| 位置 | 码 |
|---|---|
| `route()/routeFromContext()` 外层 `catch(Exception)` | `TripoderStarts ABSENT <异常类名: 消息>`（≤160 字符） |
| `catch(ActivityNotFoundException)` 的 `PACKAGE_NOT_IN_SPACE` 分支 | `ABSENT PACKAGE_NOT_IN_SPACE` |
| `RuntimeBridge.startsHost()` 拒绝 | `ABSENT HOST_COMPONENT_DENIED` |

同时给 authority 的 `TripoderWire SLOW` 行加了 `keys=[…]`，用于拆事务内容。

构建 `build-abs-v90`（2,327,795 B，sha256 前 16 位 `cfbc6db8dbe9187a`），`--no-incremental` 安装 Success。

## 3. v90 实测：QQ 的唯一一次起步被**我方**拒了

```
#1 CALL  target=com.tencent.mobileqq/.activity.LoginActivity;caller=…SplashActivity;flags=0x40000
#2 ABSENT java.lang.IllegalArgumentException: INTENT_FLAGS_UNSUPPORTED:0x40000 target=…LoginActivity
-- crash 计数 --  0
-- top --        dev.tripoder.q6/…WorkbenchActivity
-- qq activity records --  0
```

`0x40000` 是 `FLAG_ACTIVITY_NO_USER_ACTION`：它只表示「这次起跳不是用户直接触发，别回调 `onUserLeaveHint`」，**平台在任何起点都接受它**。而 `RuntimeActivityIntent.requireFlags()` 用的是白名单——不在 12 个已知任务位里就抛 `IllegalArgumentException`。这正好命中本项目的既有判据（h27/h30：门面不得在平台不会失败处抛异常），并且被 QQ 自己的 try/catch 吞掉（`crash=0`、无 FATAL），所以之前的读数是「Splash 无痕消失、任务栈 0 条」。

## 4. v91：把「未知任务位」从逃逸点改成直通

`requireFlags()` 从「白名单外一律抛」改为「只拒三类**契约性**标志」：

```java
static final int EXCLUDED=Intent.FLAG_ACTIVITY_FORWARD_RESULT
    |Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET;
static void requireFlags(int flags){if((flags&EXCLUDED)!=0)throw new IllegalArgumentException("INTENT_FLAGS_UNSUPPORTED:0x"+Integer.toHexString(flags));}
```

判据：`FORWARD_RESULT` 依赖客体的 result 通道（路由起点不带）；`NEW_DOCUMENT`/`CLEAR_WHEN_TASK_RESET` 改的是文档任务语义而非 stub 的任务语义。其余位（含未知位）平台自身会忽略或按任务引擎处理，容器不该替平台否决。

构建 `build-flags-v91`（2,327,795 B，sha256 前 16 位 `47b71b180e3b9921`），安装 Success。

## 5. v91 同配方复测：QQ 到登录页

```
#1 CALL  target=com.tencent.mobileqq/.activity.LoginActivity;caller=…SplashActivity;flags=0x40000
#2 ROUTED target=com.tencent.mobileqq/.activity.LoginActivity
ROUTING activity: caller=com.tencent.mobileqq.activity.SplashActivity target=com.tencent.mobileqq.activity.LoginActivity stub=dev.tripoder.runtime.generated.TaskP0$F0M0C2 flags=0x40000
topResumedActivity=ActivityRecord{… dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP0$F0M0C2 t16671}
-- qq activity records -- 16     （v90：0）
-- ABSENT 行 -- 无
-- crash 计数 -- 0
```

A/B 干净：同一脚本、同一冷启路径、同一 flags，只差 `requireFlags()` 的判据，结果从「0 条 ActivityRecord、top 停在 Workbench」变成「16 条、LoginActivity 置顶」。

## 6. 顺带：BROADCAST_ROUTE 6.1s 的形状

v90/v91 两次都读到同一条（`keys=[ordered,payload,generation,receiverPermission,operation]`）：

```
SLOW BROADCAST_ROUTE 5866ms pid=25106 uid=10542 keys=[ordered,payload,generation,receiverPermission,operation]
PerfMonitor binderTransact: time=5867ms interface=dev.tripoder.runtime.v1 code=27   （客体 :slot 线程，非主线程）
```

两侧仍严丝合缝，且这次落在**客体工作线程**上（pid 25106 的 25365 线程），不在主线程，因此不阻塞首帧；它仍是启动窗口里最长的单次事务，但已可判定为「延迟而非阻塞」。拆解（排队/账本/等客体回调）留作独立任务，`keys` 已就位。

## 7. 边界

- 只验证了 QQ 一条冷启路径（Workbench → QQ → 45s）。QQ 自己的 `SplashActivity.onCreate` 仍在 `BaseApplicationImpl.getRuntime`/eldermode 链上抛（应用自己吞掉），这是 QQ 侧行为，与本次修复正交。
- 未跑宽回归：`requireFlags()` 放宽影响所有客体的 startActivity，必须补一轮多应用冷启（下次第一批）。
- `RuntimeActivityValues:80` 的嵌套 intent flags 校验仍是白名单形状，同类风险未修（未实测触发）。
