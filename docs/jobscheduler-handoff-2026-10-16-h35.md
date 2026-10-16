# H35：Job 冷恢复、资源退休和真实应用诊断

2026-10-16，工作目录 `/Users/like/code/tripoder`。最终目标仍未完成：微信和王者实际正常运行，正常 Java/Native Hook 不导致 panic、崩溃或 ANR。用户已授权继续和第三方目标验证，覆盖 H25 历史“不运行第三方”阶段限制。

## 已实施与证据

| 范围 | 目录（均在 docs/evidence/jobscheduler-recovery-h35） | run | 结果 / APK |
|---|---|---|---|
| 真实 Job start/stop/finish、冷重试 | jobs-04-no-dump | jobs-a8971528a4a54821 | 15，通过；33a276c1… |
| 同 UID 原生无 Hook 重试对照 | native-baseline | jobs-native-79119daee8a24a28 | 2，通过；33a276c1… |
| 调度与保守资源退休 | scheduling-retirement | jobs-7dfc82b1a63945dd | 69，通过；独立包366dbaef… |
| Java/Native/mixed 动态 Hook | p4-current | p4-c2668c57299a4305 | 105，通过；33a276c1… |
| 完整 P3，含安装内绝对数据库路径 | p3-absolute-db | p3-bd7a14ad3d03490a | 358，通过；cfff38b2… |
| 两目标真实启动 | target-06 | target-7d7c5dd3de0d4342 | 失败，cfff38b2… |
| 电源广播/Provider身份回归 | provider-identity-idle | provider-identity-32af78071e6844b8 | 242，通过；938cef09… |
| 微信独立连续观察 | wechat-07 / wechat-08-idle | target-0f2e35c94c904eb1 / target-3be31e2dd7434859 | 都失败；分别定位电源广播与同包Context缺口 |

Runtime 33a276c1 完整 SHA：`33a276c1d61575b79de6f1d432158c00000538ff7f10bd5ea4be6e0ee83afe47`。P3 APK：`cfff38b260e330e2b1579d21fdd95d6620033c689517e62ace9f70687b0cb3ef`。独立调度 APK：`366dbaef279139103b1c3681f7ad20adc33bc50bbb3f2e0958b73131eff655c7`。各目录 receipt 是对应源与结果的依据，不跨构建追认。

### 冷恢复

`RuntimeJobInspection` 是仅 probe 的观察入口：RPC 回执、真实 namespace/id/完整 JobInfo Parcel、pending reasons、fence 和 attempt 状态。系统查询在 broker monitor 外。kill 需要 `pendingRetryConfirmed`，不只依赖 RPC 返回。mutable 采样用 `ProbeReports.checkpoint`，首次误用 publish 的失败已保留。

成功轮同一 job44/version 的两次 attempt 来自不同 generation，约32.456秒内观察到第二次完成回执。物理准备8153/8312ms，冷 start1203/1153ms，热31/42/24ms。ANR=false，无意外 Java/Native fatal。

固定 API37 的 `dumpsys jobscheduler` 打印 Ready 时进入 `isReadyToBeExecutedLocked → evaluateControllerStatesLocked → TimeController.evaluateStateLocked`，会改变 time constraint 并移除追踪，不是无副作用观察；reason cache 也可能滞后。`[13,9]` 是 flexibility/timing，不是 quota。H35 jobs-03 有中途 dump，jobs-04 不 dump 后通过；**不能把 H34 所有失败都归因于 dump**。不增加自制定时器或强制 `cmd jobscheduler run`。

### 资源退休

`RuntimeJobRetirement` 在进程分配和 schedule 前锁外对账，只选所有版本已永久 fenced、无 open mutation owner、无 LIVE instance、无当前 slot/活 session 的组件。再确认真实 pending、AMS service/process 均缺席，锁内复核版本 count/max rowid 和退休状态后写 resourceProof。UNKNOWN/operations/历史行不删、不改成功。仍未解决未知取消持久 owner 和 authority 实际死亡场景。

### 真实数据库路径

`RuntimeDatabasePath` 接受安装根、canonical 根和客体 `/data/data/<pkg>`、`/data/user/0/<pkg>` 四个既有存储映射内的规范绝对路径。拒绝 traversal、外部根及非法叶名，复用既有父目录/DB/WAL/SHM/journal symlink 检查。`DatabasePaths` 公共 SDK fixture 覆盖 SQLiteOpenHelper no-backup 绝对路径、别名同字节、WAL、跨轮持久化及拒绝反例；完整 P3 358，宿主 decoy 不变。

## 真实应用当前阻塞

公开原 APK 未改写：微信 SHA `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`，18 DEX/190053436字节；王者 SHA `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`，6 DEX/40277916字节，targetSdk=30。

早期 target-current 先暴露诊断器非 canonical native library path、失败后复用 one-shot inspector；已修路径和 finally stop inspector。target-05 两应用进入初始化后 `RuntimeStorage.name → database → SQLiteOpenHelper` 抛 INVALID_FILE_NAME；已修并以 P3 验证。

target-06：微信 PID8140 18:03:45 EXIT_SELF/status1，随后 PREPARED/CLAIMED ticket 恢复抛 ACTIVITY_RESTORE_NOT_CONFIRMED。该轮主日志环缓冲已覆盖较早段，首次退出原因需新连续日志定位。只读 state-inspection 保留 LIVE/CLAIMED/PREPARED 与真实task2947记录，不改其 UNKNOWN。

王者 PID12464 的 WorkManager ForceStopRunnable 调 PendingIntent.getBroadcast 时抛 S+ 可变性检查。固定框架 `checkPendingIntent` 使用 `android.compat.Compatibility.isChangeEnabled(160794467)`，不是客体 Context 的 targetSdk；错误串客体包名不能证明它 target31+。当前自研 Runtime 尚无 PendingIntent 工厂/getIntentSender 广播路由，修检查后仍需解决宿主UID+客体包名出站。不能加 flag、返回 null、发往宿主应用或关掉全进程限制绕过。

## 诊断器修订与当前制品

`ArtifactProbeActivity` 新增单目标选择和阶段/check checkpoint；`TargetStartupObservation` 只读 DB 和真实 AppTask，要求 LIVE+RECEIVED 与实际任务 top 一致，避免只查 Application RUNNING。启动观察30秒，不主动 stop worker 触发恢复；保留 authority 绑定。validator 对 target 连续收集同UID main/system/crash日志，也用于 fatal 判定，避免环缓冲丢最早异常。

诊断修订 Runtime：`8964442a7e7b8a23bdb5436b6ff4c4fee89dc1434800c488856665be9f78efe8`，归档 `artifacts/8964442a/`。`wechat-07`（run `target-0f2e35c94c904eb1`）已失败，连续日志精确确认首因：`RuntimeBroadcastIntent.filter` 拒绝 `DEVICE_IDLE_MODE_CHANGED`，微信线程 `wc_srvinit_10` 未捕获异常后自行 exit(1)。后续 COMPONENT_PEER_MISMATCH 是死亡后的次生恢复错误。该轮没有正常诊断收尾 kill，也不是登录/业务验收。最新主机 `host-package-context.log` 304/15/85/117/319/8/45/12，全过，SDK229；crash classifier7通过。最新核心变更后尚无当前 P4；正式产品也须重建并重新验收。

## 续接顺序与纪律

1. 三个受保护电源动作已完整回归242通过。`wechat-08-idle` 首因变为 Flutter 的 `PACKAGE_CONTEXT_UNSUPPORTED`，由客体异常处理器自行 exit(1)。同包 Package Context 已实现、构建和主机检查通过，**新26项公共SDK检查与微信尚未真机验收**；先跑这两轮。新包 `55609bb8934fa9d34f9ec7a41f4c39893777622c6225bce5e4181e60cbb61db9`，467个源输入一致，归档 `artifacts/55609bb8/`，未安装；设备仍是938cef09。资源模式采用缓存空PathClassLoader/system parent，不暴露客体代码且applicationContext=null；INCLUDE_CODE复用既有loader/Application。新主题不继承Activity/宿主主题，不自动套application主题，按固定API37 SDK默认选择。其它flags/跨包明确拒绝；不声称支持Activity窗口token的配置继承。`RuntimePackageContexts` + `PackageContextChecks` 按职责独立。后续预期的旧入口拒绝与真正生命周期竞态仍需独立解决，不放宽 UNKNOWN 重放。
2. 接通真实 PendingIntent 广播与 Alarm 生命周期/持久身份，恢复客体 targetSdk 的真实语义；见 [下一阶段实施边界](runtime-pending-intents-plan.md)。给出明确已支持边界和公共SDK fixture后再复测王者。
3. 重跑最新 P4 与必要核心回归，最后微信 UI/登录、王者 UI/登录/实际业务及正常动态 Hook 安装/调用/卸载。进程存活、Activity创建、fixture通过均不代表最终业务通过。
4. 保留所有 dirty/untracked、业务数据、UNKNOWN、失败目录；无 root/run-as、清库/扩容绕过。单设备一个安装/启动负责人；测试期间冻结构建输入。未 commit/push。

用户请求总结时，所有构建/设备验收/子代理均已结束；当前没有测试仍在运行。设备残留进程不代表任务在跑。

设备 `d861a4fa`，Xiaomi Android17/API37/arm64/4KiB，Runtime UID10409。MIUI自启动仅给自有 runtimeprobe 开启。所有 shell 使用 rtk；CodeGraph MCP 不可用，CLI及已存在索引可用。

```sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-recovery-h35/<new-provider-dir> --phase provider-identity --install
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-recovery-h35/<new-wechat-dir> --phase target --target-package com.tencent.mm --target-space target-3be31e2dd7434859-wechat
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-recovery-h35/<new-dir> --phase target --target-package com.tencent.tmgp.sgame
```
