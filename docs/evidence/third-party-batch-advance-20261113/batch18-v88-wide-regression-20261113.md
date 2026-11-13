# 第 18 批：v88 宽回归（6 应用）——5/6 直通，QQ 是唯一异常且现场已定位到 Splash

- 日期：2026-11-13 22:58–23:05
- 设备：d861a4fa（已解锁），构建：build-probe-gate-v88
- 脚本：`/tmp/v88wide.sh`（每应用：双次 force-stop q6 → force-stop q7 → `logcat -c` → Workbench → 两次「重新连接」→ 滚动定位并点击应用 → 每 2s 轮询 top 至多 40s → 读 ps / ANR / admission / crash）
- 上游：batch17（探针门控的 A/B 与 2 应用回归）

## 1. 结果

| 应用 | 点击→top | top | 客体进程 | ANR | admission | fatal |
| --- | --- | --- | --- | --- | --- | --- |
| 微信 | 6s | `TaskP0$F0M0C3` | 2 | 0 | 0 | 0 |
| QQ | **42s 未达成** | `WorkbenchActivity` | 3 | 0 | 0 | 0 |
| 京东 | 10s | `TaskP0$F0M1C0` | 2 | 0 | 0 | 0 |
| 美团 | 4s | `TaskP0$F0M1C2` | 2 | 0 | 0 | 0 |
| 微博 | 4s | `TaskP0$F0M0C0` | 2 | 0 | 0 | 0 |
| 拼多多 | 2s | `TaskP0$F0M1C2` | 2 | 0 | 0 | 0 |

**结论一（门控安全）：** 6/6 都没有 ANR、没有 admission-blocked、crash 缓冲为空。探针关闭没有引入任何"启动失败"倒退——上一轮闲鱼那种 `S0_0` 服务 ANR 在多进程/自绑定服务形状的应用上也没再出现。
**结论二（启动变快）：** 通过的应用点击→top 落在 2–10s（微信 6s、京东 10s、美团 4s、微博 4s、拼多多 2s）。probe 关闭前同一套脚本在同形状应用上的观测口径是数十秒级（见 batch16 闲鱼 40s 预算耗尽），这一批拿到了可对照的量级。

## 2. QQ 的异常：Splash 起来了又消失

`/tmp/qqprobe.sh` 复测（45s）+ `/tmp/qq2.sh` / `/tmp/qq3.sh` / `/tmp/qq4.sh` 取证：

```
23:00:13.634 15398 TripoderSelfHook: INSTALLED:ACTIVITY_DELIVERY:ns=31719
23:00:15.976 15398 BufferQueueConsumer: [VRI[SplashActivity]#0 ...] disconnect: caller 15398
23:00:16.007  3876 CoreBackPreview: Window{520e958 u0 com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity}
23:00:18.343 15398 wlogin_sdk: init ok os ver:17 cur_guid:00a568f82979af7f6011c584c8a67e7c
23:00:23.630 15398 libbinder.BpBinder: PerfMonitor binderTransact: time=6220ms interface=dev.tripoder.runtime.v1 code=27
23:01:14.820 15398 ipoder.q6:slot0: Unable to set the name of current thread to 'DefaultDispatch': Permission denied
```

现状读数：

- `ps`：`com.tencent.mobileqq` 15398 与 `com.tencent.mobileqq:MSF` 15695 **都活着**（uid 10542）；
- `dumpsys activity activities | grep -i mobileqq`：**零命中**——任务栈里没有 QQ 的 ActivityRecord；
- `dumpsys activity activities` 的 task 列表末三项是 `A=10346:app.personal.linktools`、`A=10328:com.taobao.idlefish`、`A=10542:dev.tripoder.q6`，QQ 的任务不存在；
- Workbench 文案：`启动应用「QQ」 · 已完成`（不是失败，不是容量，不是超时）；
- `logcat -b crash -d`：空；无 `ANR in dev.tripoder`、无 `admission-blocked`。

即：**QQ 的 SplashActivity 确实被创建过（有窗口、有 BufferQueue、有 `SplashActivity` VRI 层），随后整个 task 不在了，而我们这边报告"已完成"。** 这不是"没启动"，是"启动了又消失且无人记账"。

同时抓到一条同族证据：客体主进程里 `dev.tripoder.runtime.v1 code=27` 的 binder 事务耗时 **6220ms**。这属于 `slow-authority-binder` 族（已知会让系统给 input.app 打 Slow Binder ANR），也是"客体在等服务回复时把 Splash 结束掉"的合理嫌疑。

## 3. QQ 的三条待辨假设（下一轮按序判）

1. **H-a：Splash 自己结束了。** QQ 的 `SplashActivity` 在等 wtlogin/配置，若它在某条路径上 `finish()` 而后续 `startActivity(MainActivity)` 被我们丢在某个 UNKNOWN 出口（`gap-fence-other-exits`：`ActivityLaunchLedger:76` / `RuntimeBroker:307` / `ProviderLedger:74`），就会出现"窗口在、任务没了、无异常"。判法：在客体运行期对 `startActivity` 加一条有界日志（component + result），看 Splash 之后有没有发起 MainActivity。
2. **H-b：慢 authority 把 Splash 的超时打穿。** 6220ms 的事务若发生在 Splash 的启动判定上，QQ 可能按"启动失败"自退。判法：把 code=27 的事务名与调用方打出来（接口表里已有），并量同一路径在 authority 空闲时的耗时。
3. **H-c：`DefaultDispatch` 线程命名失败（`Permission denied`）导致后续调度线程建不起来。** 这条在 v88 下新出现，需要确认是不是每一轮都有、以及是否只在 QQ 这种强依赖线程池的应用上出现。

## 4. 边界

- 本轮只覆盖 6 个应用（微信/QQ/京东/美团/微博/拼多多），不是 catalog 里的全部 26 个。
- 只验证"启动与落页"，没有验证各应用的登录入口（那部分按既有 gap 清单单独推进）。
- QQ 的 3 个客体进程 vs 通过应用的 2 个：多出的一个是 `:MSF`，与 batch1 一致，不是异常。
- 时间口径是"点击→top 出现 `TaskP*`"，包含滚动定位后的等待，未扣除 Workbench 自身刷新时间，只用于同批次横向比较。
