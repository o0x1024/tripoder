# Tripoder 任务历史：H25–H36（原 task.md 归档）

2026-10-16 整理时从 task.md 原样迁出，内容未改写。**这些是历史快照，不代表当前状态**；当前状态以 [task.md](../task.md) 和 [H37 交接](runtime-handoff-2026-10-16-h37.md) 为准。更早的内容见 [H25 之前历史](task-history-before-h25-handoff-2026-10-16.md)。各段的构建 SHA 只对应当时的构建。

---

## H35 历史续接点：Job 冷恢复已通过，真实应用暴露下一层平台语义缺口

**最终目标未完成：微信/王者正常运行、正常 Hook 无 panic/崩溃/ANR。** 用户已恢复继续实施；本节和 [H35 交接](jobscheduler-handoff-2026-10-16-h35.md) 优先于所有历史段落。

- **Native最新修复**：用户诊断发现ApplicationStartInfo等可移动root整类方法被原始declaring-class比较误过滤。`art_framework.c` 的方法和字段现统一使用已固定的ART root read barrier；独立校验READ_ROOT/MARK_ROOT，不依赖Storage初始化。production resolver确定性回归、旧比较变异反例、10个固定函数体核验及ARM64/API37编译通过。详见 [Framework GC root修复](self-hook-framework-access.md)。**此修改在55609bb8构建之后，旧APK不包含修复；须重建并做首次枚举GC压力/微信/P4真机验收。** 本次没有操作设备。
- 真实 Job 生命周期/冷恢复 **15 项通过**；同 UID 无 Hook 原生重试对照 **2 项通过**。没有强制运行系统 job、模拟定时器或重放 UNKNOWN。证据 `docs/evidence/jobscheduler-recovery-h35/jobs-04-no-dump` / `native-baseline`。
- 已实现保守 `RuntimeJobRetirement`：仅永久 fenced、无 mutation owner、无 LIVE instance/活进程/真实 pending/物理 service 时退休组件资源；UNKNOWN 与历史行保留。独立调度 **69 项通过**（包括资源退休与 UNKNOWN 仍占用）。取消 UNKNOWN 收口和 authority 实际死亡恢复仍未验收。
- Job 所用 Runtime APK `33a276c1…` 同轮 **P4 105 项通过**，无意外 Java/Native fatal。不能把该旧构建结果追认为后续数据库改动的当前 P4。
- 真实微信/王者已进入 Application 初始化；两者先暴露 `INVALID_FILE_NAME`，已实现安装内绝对 SQLite 路径，保留目录、别名、WAL、symlink 和跨安装边界。含新反例的完整 **P3 358 项通过**，APK `cfff38b2…`，宿主 decoy 未改。
- `target-06` **失败**：微信主进程自行退出后出现未确认 Activity 恢复拒绝；王者 WorkManager 的 PendingIntent 检查崩溃。已核实王者固定 APK targetSdk=30，但平台读取宿主进程的 `Compatibility.isChangeEnabled(160794467)`，不能补 flag 或返回 null 假装成功；当前还缺自研 PendingIntent 广播/Alarm 的真实路由。
- 已修探针：独立选择包、阶段 checkpoint、连续同 UID 日志、实际 Activity CREATED/RECEIVED+真实 task 观察；不再以 RUNNING 代替 Activity 成功，也不在正常启动观察后直接 kill worker 制造恢复。`wechat-07` 精确定位首因：缺 DEVICE_IDLE_MODE_CHANGED 注册策略，微信异常处理器自行 exit(1)。已接入 idle/light-idle/power-save 三个真实受保护动作；电源广播公共 SDK 回归 **242 项通过**（provider-identity-32af78071e6844b8），无意外 Java/Native fatal。`wechat-08-idle` 越过该处，下一首因是 Flutter 调 `createPackageContext` 被拒绝。
- 最新已实现同包 Package Context：flags0/IGNORE_SECURITY 为无客体 DEX 的资源上下文、applicationContext=null；INCLUDE_CODE 复用当前客体 loader/Application；新 Context 独立 theme，拒绝其它 flags/跨包。新增26项公共SDK检查，**尚未真机验收**。探针支持显式复用已核验安装，不新建空间、不删数据或历史。
- 最新构建 **55609bb8934fa9d34f9ec7a41f4c39893777622c6225bce5e4181e60cbb61db9**，467输入一致，已归档 `artifacts/55609bb8`；**未安装**。设备仍是 `938cef09…`。下一步安装新包跑 provider-identity，再用 `--target-space target-3be31e2dd7434859-wechat` 复测微信，然后当前源P4。
- 主机最新 304/15/85/117/319/8/45/12 全过，SDK sources229。当前无构建、设备验收或子代理任务运行；设备残留进程不代表测试仍在跑。设备 `d861a4fa`，Runtime UID10409；保留所有旧业务数据、UNKNOWN、失败证据和 dirty/untracked，未 commit/push。

## H34 历史交接点：真实 JobService / 客体 API 已接线，冷恢复当时失败

**用户现在要求整理交接，由其它 agent 继续。最终目标未完成：微信/王者正常运行、正常 Hook 无 panic/崩溃/ANR。** 本节及 [H34 详细交接](jobscheduler-handoff-2026-10-16-h34.md) 优先于下方历史段落。

- 当前 Runtime 已构建并安装：`bfa63a747f564cf2a3cf29e5ce3f52e89f0d202a31d6005a9e140be4594e6121`；458 个源输入重核无变化。设备 `d861a4fa`，UID **10409**。APK/receipt/源快照已归档至 `docs/evidence/jobscheduler-lifecycle-h34/artifacts/`。
- 实现了 JobScheduler manager/Wire/客体 facade、JobFrameworkAccess、真实 gateway/逻辑 JobService、callback Binder + attempt 账本、参数对象级 finish capability。schema **28**，固定 boot roots **55**，能力 **26/26**，实际 **2215** 个绑定。
- 修复了缺省图标接口崩溃，以及解释器模式下重复解析宿主大 Manifest 造成的 **8秒 Job start ANR**。物理固定准备与 Manifest 快照前移；当前实测物理准备 **7.791s**，冷 onStart **1.169s**，热 **24–28ms**。
- 同步 start、异步 jobFinished、运行中 cancel/onStop 已有真实回调和真实 Binder 返回证据。**完整 jobs 阶段仍未通过**：jobs-05 直接杀死运行中 worker，jobs-06 等 finish(true) RPC 返回后再杀死，均未观察到第二次 start。05/06 无意外 Java/Native fatal，ANR=false；不能据此标整轮成功。
- 已通过 Android 系统 UI 为自有 `dev.tripoder.runtimeprobe` 开启 MIUI 自启动。05 不再出现 RestartService 拒绝，但冷恢复仍未解决；不能继续仅归因于自启动。其它应用/全局策略未改。
- **下一步关键证据**：在 finish(true) 回执之后、kill 前和后续0–30秒记录真实 pending JobInfo/namespace/version/reasons；RPC 返回不等于系统已接收重试。06 的 late unknown(job44) 在夹具超时 cancelAll 之后，不能当根因。
- 当前主机测试全过：304/15/85/117/319/8/45/12，SDK sources226。**当前源码 P4、核心矩阵、正式控制、微信/王者启动均未跑。** H33 P4不能追认。`--phase target` 启动诊断入口已添加但未运行，不是业务验收。
- Job 组件资源退休、取消 UNKNOWN 收口、authority 死亡恢复及旧 Activity UNKNOWN pin 仍缺。`RuntimeJobRetirement` 与系统强制运行测试触发器都只是讨论，**未实现**。不清库/删 UNKNOWN/扩容绕过。
- 正式 product/build 和独立 scheduling-probe/build 均已过期，不可冒充当前源。当前无构建、设备验收、子代理在运行；只剩 Runtime 主 UI PID6726（瞬时快照），无 authority/slot worker。未 commit/push，保留全部 dirty/untracked。

用户本轮已明确授权微信和王者验证，覆盖旧阶段“不运行第三方测试”的限制；其它安全/数据保留边界继续有效。

## H33 历史续接点：JobScheduler 调度内核

**最终目标：微信和王者荣耀实际正常运行，正常 Hook 安装/调用/卸载不引发应用崩溃或 ANR。当前仍未达到。** 此为 H33 历史快照，当前以 H34 为准。

已实现 `JobComponentLedger` / `JobLedger` / `RuntimeJobBroker`、schema 26→27、安装/进程引用、安装级持久互斥、版本墓碑、真实 schedule/cancel/query、正向 pending 证据恢复 UNKNOWN，以及 session/job 同事务的 Hook 恢复标记。两套正式构建共用 32 个 BIND_JOB_SERVICE 槽。

独立自有包 `dev.tripoder.jobsprobe` **65/65 真机通过**：`docs/evidence/jobscheduler-scheduling-h33/attempt-03`，run `jobs-be107bf9bf0d4eac`，APK `ece8ef1efc6c53988cb5ee349d0ec89d788adc99a742dd236a49f5d2d1ccb016`。实际提交 10 个系统 job，全部真实取消清理；测试数据库保留 UNKNOWN 与墓碑，不清业务数据。含磁盘重开，不含 authority/worker 实际死亡。首次 attempt-01 的事务内系统观察错误已修复，失败证据仍保留。

**未开放客体调度**：`RuntimeJobGateway` 生命周期尚未接通，当前内部验收只提交 24 小时后的任务并立即取消；无 Native Hook 参与。尚缺 manager facade/Wire、固定 JobParameters/JobService adapter、attempt Binder 身份、onStart/onStop/finish、冷恢复、取消 UNKNOWN 收口和组件退休。切勿把 65 项调度检查说成 WorkManager/王者可运行。

下一步先实测固定平台 Job 回调 deadline 并建立固定成员 adapter，再接真实执行/完成闭环和客体 API；之后冷恢复与 Hook 回归，最终验证微信/王者实际链路。item-3 历史未附着 pin 与 MIUI sticky 策略仍未解决。

主机 Self Hook 回归全过（293/15/85/117/295/8/45/12 项分组，SDK sources=218）。当前 Runtime APK 已构建：`9022bea17870371b9bf5f0f6ed8f3640841e7219d6cd136b98084abb3fee34d4`；**P4 当前构建 105/105 通过**（run `p4-d92bda5afc454a1d`，无意外 Java/Native fatal）。正式 Engine / Workbench / control-test 已构建归档、未安装；详情见 [H33 证据索引](evidence/jobscheduler-scheduling-h33/README.md)。当前没有构建、设备验收或子代理仍在运行。

## H31 本轮进展（当前源回归 12/13 绿，p2 卡 item-3）

当前 runtime-probe：`f583941754becdde313ecd4b6533d82e005eb051e1fb58cd65d27c52fdef26e1`（h31，已安装）。单一构建完整回归 **12/13 通过**：p4 / p3 / creation / failure / service-startup / service-passive / activity-flags / entry / notification / p0 / packages / p1。证据 `docs/evidence/self-hook-product-2026-10-16/*-h31`。

本轮三处已验证修复（源码已改、host 测试全过）：
1. **P3 存储冷对等屏障**：`PreferenceProcesses.waitFor(ready)` 10s→35s（产品 30s 冷启动 deadline + 5s 观察；go 握手保持 10s）。实测对等冷安装 10.14s 曾触发 `PREFS_BARRIER_TIMEOUT`。p3 现 280/280。
2. **entry 服务端竞态**（真机日志实证，非回归）：hot 迟到拒绝旧设计返回 null 留下无 client record 的 token，服务端独立派发的 `TopResumedActivityChangeItem` 打挂 successor 进程。修复：hot 拒绝也物化宿主 `RuntimeRejectedActivity`（与 cold 路径统一），`ActivityEntryFences` 经 `ACTIVITY_CLAIM` 记录 `"factory"` 拒绝。改动在 `RuntimeActivityCreation.constructRejected` / `RuntimeSelfHookActivities`(performLaunchActivity+newActivity) / `RuntimeBroker`(ACTIVITY_CLAIM)。设计文档 `runtime-activity-construction.md` 已更新。entry 现 22/22、零 crash。
3. **p4 native 映射证据定值**（子代理加入的 `RuntimeNativeMappingEvidence`，p4 模块密集阶段暴露）：实测 `paths=436, joined=17376, foreign=0`。路径计数 256→1024（与 framework 成员预算一致），且 `nativePaths` 由 256 元素上限的 wire 数组改为**换行拼接字符串**（由 ROW_LIMIT 32000 约束），validator split 回来保留全部逐路径安全检查（唯一性/foreign 扫描/core alias）。改动在 `RuntimeNativeMappingEvidence` + `validate-agent-control.py` + `validate-workbench-control.py` + 对应 host fixture。foreign 32 上限保持严格。p4 现通过。

**p2 未通过 = task.md item-3，非本轮回归**：完整 p2 在 Chain fixture 处 `PROCESS_CAPACITY_EXHAUSTED`（slot7 FATAL，`unexpectedJavaFatalObserved`）。根因精确定位：`ProcessSupervisor.availableIndex`→`ActivityLedger.occupied(stub)` 对**未附着 UNKNOWN ticket**（PREPARED/CLAIMED、进程 attach 前死亡、无 taskId、即使 `entryFenceOperation` 已置位、state 仍非终态）恒返回 true → 永久占槽；`RuntimeEmptyTasks.reclaim` 够不着（无 task）。被本轮 ~6 次 `install -r`（保留数据）累积的历史 UNKNOWN ticket 加剧。按 `runtime-task-reclamation.md` 与 task.md 纪律：**未附着资源退休需真实 Android task 系统证据（当前不存在），不得清数据/扩容/改写 UNKNOWN 绕过**；这是跨多轮刻意保留的最深阻塞。

**干净 store 诊断（重要澄清）**：在全新安装的 store 上单独跑 p2（证据 `p2-h31-clean-diagnostic`，非官方验收、保留 `p2-h31` 失败原样），达到 **198/199**。这定性确认：污染 store 上的 `PROCESS_CAPACITY_EXHAUSTED` 是本轮 ~6 次 `install -r` **累积的历史 pin**,**当前源 P2 本身基本健全**;item-3 是独立的长程产品限制(每次完整 p2 自身也会留 pin,跨轮累积),不是当前源 P2 的缺陷。官方完整 p2 验收仍需每次干净 store + 解决 sticky（下）。
- **当前源 P2 第 199 项失败 = MIUI 设备策略，非代码缺陷（已带 logcat 确证）**：`service-fixture-completed` → `SERVICE_CONNECTION_TIMEOUT`（`Services.java:68` 第二个 `sticky.next()`，45s 无重连）。定向复现（`p2-sticky-repro`，干净 store）可稳定重现，捕获日志确证根因：`killProcess(Sticky 进程)` 后 Android AMS 调度 START_STICKY 重启，但小米 `AutoStartManagerService: MIUILOG- Reject RestartService service :ComponentInfo{.../RuntimeServiceSlots$S1_2} uid:10407` **拒绝了服务重启**（应用不在 MIUI 自启动白名单）→ Sticky 永不重启重连。这是设备自启动策略前置条件，**不是 Tripoder 当前源缺陷**。remedy：为 `dev.tripoder.runtimeprobe` 开启 MIUI 自启动（手动 Settings/安全中心，或 root；plain adb 的 `appops AUTO_START` 不是标准 op，未确认可授）。已二次验证：干净装 + `appops set ... 10008 allow` + `deviceidle whitelist +` 后再跑 p2（`p2-autostart-verify`，不带 --install 以保留授权），仍 198/199 同一 sticky 超时——**确认 MIUI 自启动无法经 plain adb 授予**，remedy 必须设备端手动开启（Settings/安全中心→自启动）或 root。
  - **结论：干净 store 上当前源 P2 = 198/199，两处非通过均非当前源代码缺陷**——capacity（item-3 累积历史 pin，长程产品限制）+ sticky（MIUI 自启动策略）。当前源 P2 行为基本健全。

**最终目标（微信/王者运行 + 通用 Hook）仍未达成**，门槛为：item-3 未附着资源稳妥退休（微信启动链反复 attach 前死亡积累同样 pin；需真实 Android task 系统证据，当前无 public API 可枚举所有 task 内全部 activity，属开放难题）、完整 P2 的 sticky 服务重启恢复、JobScheduler（王者唯一阻塞，greenfield，设计见 `runtime-jobs-design.md`）、微信登录链路服务/广播语义。P4 Hook 核心能力本身已验证（105 项 + p4 本轮绿）。

## 历史结论（H25，不作为当前续接顺序）

**完整任务尚未完成。** 用户已授权持续实施，要求先解决存储 Hook，再完成剩余接入和验收；最新又明确要求细分任务、使用子代理。此次按用户要求整理交接，供其它 agent 续接，不代表 Goal 完成。

**干净交接点：当前没有构建、真机验收或子代理仍在运行。** 最后 Runtime 验收会话 20380 已正常结束，provider-identity **236 项完整通过**。三个子代理均已完成交付，补丁已集成；不要重复应用 tmp 补丁。

下一步优先跑当前 Runtime 的 **P3 实际存储长流程**，然后按下方队列补齐组件、最终 P0/packages/P4/P1/完整 P2，以及正式工作台/Agent 控制。不要在任何单阶段通过后停止，也不要把更大范围的 fullP2Accepted/fullP5Accepted/P6 标志机械改为 true。

## 目标与边界

- 完整替换正式 Runtime 中的 Frida/Gadget Hook 后端，固定适配和用户 Java/Native 动态 Hook 共用自研核心。
- 用户明确选择：**guest factory、类初始化、构造器执行前建立进程策略、装好全部必需能力**；之后仍支持动态 Java/Native Hook。任意既有 AOT 在途帧迁移不属于选定边界。
- Engine 已实际接入 **25 项真实安装、2177 个 managed 固定绑定**：1523 manager + 652 其它固定入口 + 2 Application 入口；managed 容量4096，Native入站1024、JNI32参数。
- 固定存储十入口在首条 guest 代码前安装；不能将运行中已有四个连续 IO 调用者的固定 Storage 热安装进展保证，偷偷扩成当前目标。
- 仅自有 APK、已授权设备 `d861a4fa`、锁定 Xiaomi Android17/API37/arm64/4096页固件。不使用 root、run-as、第三方应用测试、清库/清缓存/删除 UNKNOWN 或旧业务数据。
- 正式 Engine/Workbench 使用独立 UID；guest 与 Engine 同 UID，不宣称内核安全隔离、通用 APK 或第三方业务/P6已完成。
- 所有 shell 命令以 `rtk` 开头。已读 `/Users/like/.codex/RTK.md`。
- 用户 AGENTS：超过2000行按功能拆分；第一性原理；不做兼容/fallback；给出下一步方案。
- CodeGraph 工具在此会话未暴露，`.codegraph/` 已存在，不要误报未初始化。
- 保留整个大量 dirty/untracked 工作区；用户未要求 commit/push。

## 历史制品：H25（当时来源已核对无变动）

| 制品 | SHA256 | 当前状态 |
|---|---|---|
| Runtime probe | c02d7dbcb9a50c98ad57694a9bc5b754ed32609f2e1bd17a8fa47ccd33d5704a | 已安装；provider-identity236通过 |
| 核心 probe | 407aca9d383437a90b54facb39eca76101b495e96d6b086b4fb0cdb8c6071f97 | 已安装；main完整独立核验通过 |
| 正式 Engine | 6809f7d18bf886441fd5d403de8fb2e83e24d9f90930093c6a3242ec2fdd704e | 已构建、归档，**尚未安装/正式验收** |
| 正式 Workbench | 2ac21f95b2fe3849880f8118517661ddc42ea85f02072ddfa4e4c775ee857cab | 同上 |
| 正式 control-test | 9a499ec1ca6aafcfd17a00c02295a3954379c60df3f1fa6b7841cf3089c7017d | 同上 |

- Runtime核心库 SHA：`fba2a4ae12e7fe17aff131a0abb66c918d0b8f003547b624e324a15cc2398ce1`。
- 正式Engine核心库 SHA：`21a9ecbb8054aca8710402a86efe880e7703de54fc8d5a222cb3c8d3182b82ab`。
- 核心probe库 SHA：`554137210d467b07eb2f6f7764cba73d5f866113c352e3f8016ce56f002064c3`。
- 不同构建目录的库 SHA 可能不同，必须与**该构建自己的** receipt/ZIP/运行映射核对，不可跨包混用。
- receipts：`android/{runtime-probe,product,self-hook-probe}/build/build-receipt.json`。
- 构建日志：Runtime `h25-integrated-build.log`；正式产品 `h25-integrated-product-build.log`；核心 `h25-admission-mappings-build.log`。
- 实物归档：product evidence `artifacts/c02d7dbc`、`artifacts/6809f7d1`；core H25 `artifacts/407aca9d`。正式归档含三APK及receipt。
- 当前所有对应 sourceSha256/sources 都已在本次交接前逐文件复核，changedInputs=[]。**验收和立即来源复核期间禁止改其构建输入。** 文档不在这些输入内。
- 构建包装只允许一份 `lib/arm64-v8a/libtripoderselfhook.so`；fridaInputs=[]、无旧adapter.js/独立tripoderio/探针JNI。旧 `android/runtime/agent` / hooklab 仅保留未包装的历史源码，不是恢复后端。

## 最新真实通过证据

### H25 实际 Runtime：provider-identity236

- 目录：`docs/evidence/self-hook-product-2026-10-16/provider-identity-integrated`。
- run：`provider-identity-b181415dff3c491d`；APK c02d7dbc；**236检查，整轮passed=true**。
- 真正覆盖：重复Provider类/authority身份、私有delegate/元数据/权限；multiprocess Provider双PID/独立实例/本地一次初始化/引用确认；包资源与系统服务、真实网络/电话/音频/电池/电源/通知/shortcut观察、真实分钟广播及并发注册/广播/注销、启动历史与失败归因、原Throwable与反射访问。
- 无Native fatal、无未预期Java fatal。预期 `owned-uncaught-handler` 仅按完整passed fixture、同UID/PID、精确线程/Throwable/调用栈接受；不是忽略一般崩溃。
- 大报告17972字节已通过nonce/SHA/size/PID/UID/file receipt，不扩大产品16KiB单字符串结果上限。
- 这轮设备证实宿主目录别名：reportDirectory 为 `/data/user/0/...`，canonical 为 `/data/data/...`。
- 六个实际2177绑定安装样本：8.150 / 9.945 / 9.774 / 9.800 / 9.746 / 12.074秒。只对应该构建与工作负载，不是通用p95/SLA或无性能退化证明。

### H25 核心：main完整独立核验

- `docs/evidence/self-hook-h25-2026-10-16/72a9e12f-f2e2-4539-939d-a76c12fa3279`。
- build `09672813-2a4b-4545-b77e-d1e14731d0b1`；source `0a1cbff03b2ef0d8765ba2eee3bdb302166368f45b34d8f8fbd0df940f0dcd7c`。
- 当前APK407aca9d：24固定libc安装/恢复 +24真实IO，412868并发调用、0错误，40精确修改字/两页完整恢复；实际累计7次危险PC拒绝后提交。一次新运行即通过，没有反复抽样追幸运。
- 同轮Native页/线程暂停/非协作提交/ART真实访问、Field.get、managed原调用、反射视图均通过。
- 独立 `verification.json` 已生成；verifier无 `--expected` 参数。
- H20两次热Storage IN_FLIGHT失败继续保留，不能被H25覆盖成“保证热安装进展”。

### 较早完整通过（最终当前源仍需回归）

| 阶段 | run / 目录 | 完整通过 |
|---|---|---|
| P0 | p0-b7f3c9c46b2345b8 / p0-single-observation | 60 |
| packages | packages-6bd9279812954cd3 / packages-policy-codes | 159 |
| P4 | p4-9dac402cbf73431f / p4-live-admission | 105 |
| sources | sources-e744de383d664768 / sources-component-reservation | 17 |

- 上表位于 `docs/evidence/self-hook-product-2026-10-16`。
- **packages旧成功APK缺归档**，不要补造或否认旧run；最终当前源重跑并归档。
- 完整P2先后在136/218检查、providers206等处失败，**都不是整轮通过**，不能相加冒充完成。
- H20完整4096容量独立通过：run905da96e-b5ea-4780-8d67-f71027aab088，8192enable/4096disable/8192original、132writer、全部4096owner移动，57.038秒（包含GC/调用/报告，非启动耗时）。
- H19/H20构造26、JNI31/32参数/396native/384并发、manager1523/15、同名双loader/动态caller/GC等分项已独立通过，精确构建见交接历史，不跨构建追认。

## 已完成的关键修复：不要倒退

1. **存储近地址卡点**：固定libc保留BTI c，后4字绝对 LDR x16/BR x16/literal；不再为存储分配near relay。完整平台SHA、157336指令/9294符号/1609RELA与独立assembler审核；两页同一闭合线程集合提交。
2. 旧syscall恢复PC可能落新literal，故安装/恢复都拒绝 entry+8..+20；不改PC、不重放。预算64尝试/1秒，未知结果fail-stop。
3. **启动成本**：单次Method观测复用；Java完整Executable身份HashMap/固定manager Set消除平方扫描；实时maps先筛地址范围，命中行仍做原完整权限/inode/device/offset核验，无映射缓存/弱化准入。
4. **启动计时**：Slot分配时产生唯一单调时钟30秒截止点，随认证bootstrap传Engine；Supervisor、构造、Application、Provider只用剩余时间，不在层内重置或提前8/10秒超时。
5. **Activity来源ANR**：路由复用不可变目标 reserveComponent，不在来源UI同步等远端2177安装；Android实际入口再启动目标。ALLOCATED回调capability尚不可消费，实际附着PID/UID/代次仍严格验证。
6. **所有权**：pending currentApplication=null与逻辑processName先于factory/clinit/constructor，随后一次提升guest。只有已验证Manifest使用精确默认Application时接受平台Application.class，其它boot类/外来loader/错误解析仍拒绝。
7. ProcessSupervisor是唯一死亡观察者，已修重复death回调关闭Store后写库；不吞掉权威错误。
8. Native普通加载与Hook准入分开：无GNU BuildID的普通JNI库可加载，但不能作为Native Hook目标；无合成ID/fallback。真实模块活性、代次/BuildID/文件/符号仍严格验证。
9. **动态Receiver注销竞态**：权威明确返回rejectedClosedRegistration；Adapter不进guest、不建立Invocation/after，Android自己完成PendingResult。注销事务把未claim的PREPARED投递保留UNKNOWN，已RUNNING按真实回执完成；未知身份/票据/损坏payload不吞异常。
10. **报告通道**：PackageResourcesActivity大报告私有文件fsync+rename，小结果nonce/hash/bytes/pid；P1Authority可信host canonical锚点+精确data后缀逐级lstat/UID，leaf O_NOFOLLOW/fstat/inode/size/SHA/PID/UID/installGeneration，再复用ProbeReports.handoff。
11. Runtime测试物理名SelfHookTaskP与正式TaskP区分；旧名字永久拒绝，只按死亡/票据退役/当前Manifest不存在且永久fence证据释放旧物理租约；保留operations/tickets/data。
12. Native失败退出用真实ApplicationExitInfo的本次PID/UID/process/time/reason/status确认；不补旧Gadget日志、不豁免意外SIGSEGV等。

## 并行子代理交付（均已集成，当前无子代理运行）

- `/root/storage_quiescence`：精确admission诊断及真实C函数471主机检查。CONTEXT失效独立报告，不误作PREIMAGE。tmp备份 `/private/tmp/tripoder-storage-parallel/HANDOFF.md` / patch / host结果。
- `/root/formal_control`：两正式validator三UID崩溃核验、安装前停止旧进程、实际worker25/25/2177启动证据；status进程内runtimeNativeMappings及正式独立核验。两套各24反例、MCP关联反例、SDK编译通过。
- `/root/acceptance_plan`：任务矩阵/证据索引；报告路径别名准入修复及5反例、API37独立编译。
- Source已集成文件：`RuntimeNativeMappingEvidence.java`、`RuntimeHookBridge.java`、fixed_libc及header/probe/product桥、P1Authority/PackageResourcesActivity、ControlHookChecks、两个正式validator等。**不要再次应用tmp补丁。**
- 主机当前：bootstrap293、loader15、component85、boundary117、manager policy295（108支持）、startupDeadline8、typed45、mapping12；SDK212源；crash classifier7；Storage admission471。
- runtimeNativeMappings通过既有status追加，不新增command/第二协议/Hook准入；实际/proc/self/maps、core路径/inode/device/rows、文件SHA/size、PID/UID/physicalProcess/generation、foreignBackendRows；正式validator必须与**相应Engine receipt**及真实worker绑定，缺失即失败。

## 剩余实施与验收队列

详细矩阵：[docs/self-hook-acceptance-plan-2026-10-16.md](self-hook-acceptance-plan-2026-10-16.md)。该矩阵旧快照A1仍写H24待运行；**以本文件H25/A1已236通过为准**，接手时更新矩阵与证据索引即可，不必无故重跑A1。

1. **P3实际存储**：两空间、重启/升级、Java/JNI同字节、FD/路径/symlink/只读制品、prefs/SQLite WAL/并发及宿主decoy；必须拿完整终态。
2. 独立组件：`creation`、`failure`、`service-startup`、`service-passive`、`activity-flags`、`entry`、`notification`（加 `--allow-notification-test-grant`，确认恢复）。
3. 当前源整体回归：`p0`、`packages`、`p4`、`p1`、**完整 `p2`**。
   - 完整P2包含providers/provider-identity/receiver/sources；不包含上面7个独立入口。
   - P4真实Java/Native/mixed、参数快照、DRAINING/停用、卸载和两次系统退出必须保持。
   - p2 collector1200秒，单进程30秒和单操作时限不改。
4. **正式包当前已构建且源一致**，不改输入可直接 `control --install`，再 `hook-ui`，再Agent `normal`、`disconnect`。若源码/fixture摘要再变，重建正式三包。
5. 正式映射证据：要求本次worker实际PID/UID/:slot/代次，coreSha/size=APK内SO=receipt，唯一准确安装路径、core映射身份、foreignBackendRows=[]；原始日志和 `runtime-native-mapping-verification.json`。
6. 收口：所有必要终态/意外fatal/ANR/UNKNOWN边界核对；当前制品归档哈希；启动样本与真实工作负载性能；task/README/架构/证据索引更新。没有数字化稳态阈值，不虚构“无退化”。
7. Agent normal/disconnect证明自有MCP与手机审批/断联关闭，不证明Sentinel/model客户端；真实丢ACK UNKNOWN不能只用断联关闭代替。真实UNKNOWN还需P0/P3/故障回执。
8. 不运行 `artifact/target` 第三方应用或 Agent `sentinel`；`library-ui/app-actions-ui` 仅相关改动/必需缺口触发，不重复无关昂贵测试。
9. 一个设备只一个安装/force-stop/启动负责人。子代理可并行隔离主机修复/证据审计；冻结正在验收制品输入；失败目录不覆盖，source变更后不追认旧包。

## 续接命令

当前不必重建，先直接使用已安装Runtime：

~~~sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/p3-integrated --phase p3
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/creation-integrated --phase creation
~~~

其余阶段同模板，证据目录务必新建且唯一。正式控制：

~~~sh
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/control-integrated --phase control --install
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/hook-ui-integrated --phase hook-ui
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/agent-normal-integrated --phase normal
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/agent-disconnect-integrated --phase disconnect
~~~

如需重建，两个脚本使用同一组显式参数：

~~~sh
rtk proxy python3 android/scripts/build-runtime-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64
~~~

正式产品改脚本名为 `build-workbench.py`。Runtime重建会重编fixture导致时间戳/SHA变化，正式包也要跟着绑定新fixture，不安装旧包冒充当前源。

主机关键检查：

~~~sh
rtk proxy python3 android/self-hook/tests/check_fixed_libc_admission.py
rtk proxy python3 android/runtime/self-hook-tests/run.py --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --android-jar /opt/homebrew/share/android-commandlinetools/platforms/android-37.0/android.jar --apksig-jar /opt/homebrew/share/android-commandlinetools/build-tools/37.0.0/lib/apksigner.jar
rtk proxy node android/runtime/script-tests/run.mjs
~~~

SDK型测试、脚本合同或核心case已过且无相关改动，不为凑数量反复重跑。设备故障先读终态result/error/原始owned-crash/bootstrap-log，再修根因，不放宽断言。

## 资料入口

- [当前产品证据索引](evidence/self-hook-product-2026-10-16/README.md)（需补本次H25成功）
- [详细历史交接](self-hook-handoff-2026-10-15.md)（顶部H25，尾部H3h为明确历史）
- [启动链](self-hook-runtime-bootstrap.md)、[存储绝对入口](self-hook-fixed-libc.md)、[迁移清单](self-hook-adapters.md)
- [共享Runtime构建说明](../android/runtime/README.md)
- 本次总结前的旧task完整内容保存在 `docs/task-history-before-h25-handoff-2026-10-16.md`，不要按旧“仍用Frida/存储未解”状态倒退。

## H32 JobScheduler 实现启动（已授权多轮工程）

用户授权多轮 JobScheduler 实现（native 核心家族新增 + manifest 槽 + 账本/网关，每批设备验收）。

**batch-1 已完成并设备验收通过（H4 inventory 11 族 passed=True）**：JobScheduler manager 家族注册。关键修正：owner 用**具体实现类 `android.app.JobSchedulerImpl`**，不是抽象基类 `android.app.job.JobScheduler`（设备实测后者 abs=7 个抽象方法、无可 Hook 方法体；impl 才有 schedule/cancel 真实实现）。6 处一致修改：
- native `art_framework.c`：`names[]` 追加 `android/app/JobSchedulerImpl`（index 50），`registry.classes[50]`→`[51]`。
- Java `FixedFrameworkMembers.NAMES` 追加 `android.app.JobSchedulerImpl`（index 50）。
- Java `FrameworkAccess`：`declarationCache[50]`→`[51]`，`managerMembers` 族数组追加 `50`（family 10），边界 `family>=10`→`>=11`。
- Java `FixedManagerInventory`：`OWNERS` 追加 JobSchedulerImpl，`EXPECTED[10]` = **设备实测** `4e5c0e344d62d9e7de2a10354546d287bd9d5960f3404a4a11d9f94adb60617c`（inst=22, ctor=3, static=2, abs=0, nat=0）。
- `verify-self-manager-inventory.py` OWNERS 追加；`manager-inventory.json` 追加 JobSchedulerImpl 族条目。
- 设备验收：self-hook-probe（key `scratchpad/selfhook-p0.jks`，alias p0/pass tripoder-test-only）→ `run-self-hook-case.py --activity inventory --expected native` → **passed=True，11 族，totalInstanceMethods=1545**，证据 `docs/evidence/jobscheduler-inventory-h32f`。诊断用的异常附加字段已还原。
- 遗留：native 核心 .so 变更（names[]/classes[51]）是附加式（现有索引不变），建议补跑完整核心矩阵（manager-all/capacity/main/JNI/construction/coverage）确认无回归——附加 class root 不改既有行为，但按纪律应验。

**后续批次（未开始）**：
- RuntimeJobScheduler：拦截 guest `JobScheduler.schedule/cancel/cancelAll/cancelInAllNamespaces/getPendingJobs/getAllPendingJobs`（复用新 manager 家族 + FixedManagerInventory.permitsHook），namespace 由 space/install 派生，经 `RuntimeJobInfo.physical()` 映射到网关组件 + version，调用真实平台 JobScheduler。
- RuntimeJobGateway/Slots：manifest JobService stub（声明 BIND_JOB_SERVICE），manifest 槽生成器（仿 runtime_activity_slots.py）。
- JobLedger + RuntimeJobBroker：持久调度账本（逻辑键 install+namespace+jobId、调度版本 nonce、墓碑）、安装级互斥、wire 命令。
- RuntimeJobs：网关 onStartJob→版本准入→逻辑 JobService 委托。
- agent/jobs.ts、fixture、分批设备验收。详见 docs/jobscheduler-implementation-plan.md 与 docs/runtime-jobs-design.md。

## batch-2 精确接线数据（已捕获，待实现+设备验收）

RuntimeSelfHookManagers/Policy 接线点已勘定：
- `RuntimeSelfHookManagerPolicy.Family` 枚举加 `JOB_SCHEDULER`；`required()` 返回支持签名集。
- `RuntimeSelfHookManagers.plan()`：`case JOB_SCHEDULER: owner=Class.forName("android.app.JobSchedulerImpl",false,loader)`（非 public，无 .class 字面量）；Route.owns→`RuntimeBridge.ownsJobScheduler`；Route.execute→`RuntimeJobScheduler.dispatch`。
- getSystemService 路由返回 guest JobSchedulerImpl（仿 notificationManager(host)）；`RuntimeBridge.ownsJobScheduler` + framework 访问器。
- 安装 wiring 在 RuntimeSelfHookInstallers（仿现有家族）。

**JobSchedulerImpl 设备实测方法声明（Android17/API37，sha 4e5c0e34…，22 实例方法）**，guest API 支持面（batch-2 首批）：
- `schedule(android.app.job.JobInfo)` → ledger PREPARED 落盘 + `RuntimeJobInfo.physical()` 映射到网关组件+version → 真实平台 JobScheduler.schedule，RESULT_SUCCESS 确认。
- `cancel(int)` / `cancelAll()` / `cancelInAllNamespaces()` → 墓碑 + 平台 cancel（cancelAll 仅当前 namespace，cancelInAllNamespaces 覆盖本安装全部）。
- `getAllPendingJobs()` / `getPendingJob(int)` / `getPendingJobsInAllNamespaces()` → 从 ledger 返回逻辑 JobInfo 视图。
- `forNamespace(java.lang.String)` / `getNamespace()` → namespace 由 space/install 派生，不透传客体字符串。
- 拒绝：`enqueue(JobInfo,JobWorkItem)`（无 JobWorkItem）、`scheduleAsPackage`、`getPendingJobReason*`、`getStartedJobs`、`canRunUserInitiatedJobs`、`*UserVisibleJobObserver`、`notePendingUserRequestedAppStop`、`getAllJobSnapshots`、`hasRunUserInitiatedJobsPermission`。
- 依赖：RuntimeJobGateway/Slots（manifest JobService + BIND_JOB_SERVICE + 槽生成器）、JobLedger（逻辑键 install+namespace+jobId、调度版本 nonce、墓碑）、RuntimeJobBroker（安装级互斥 + wire）、RuntimeJobs（onStartJob→版本准入→逻辑委托）、Jobs fixture + 设备验收。
- self-hook-probe 签名 key：`scratchpad/selfhook-p0.jks`（alias p0 / pass tripoder-test-only）；inventory 验收命令见上文。

## H36 续接点：微信越过 Package Context + telephony，卡在 BindServiceFlags.getValue

设备 `d861a4fa`。基于 H35 的 55609bb8 继续。最终目标未完成。

- **provider-identity-h36/h36b 失败 = item-3 累积 pin 容量耗尽，非 55609bb8 代码回归**：logcat 实证 `TripoderP2: PROCESS_CAPACITY_EXHAUSTED`，`ensureProcess :historyfail`（testPackageResources 第 4 进程）拿不到槽。18/19 过。设备经 H33–H35 大量 run 累积未退休 UNKNOWN pin（item-3，开放难题）占满 8 槽；清库会销毁 H35 要复用的 `target-3be31e2dd7434859-wechat` 业务 space（交接明令保留），故不清。
- **微信越过 Package Context 缺口**（55609bb8 同包 Context 修复生效）：`wechat-09-pkgctx` 进入真实 UI `LauncherUI`/`WeChatSplashActivity`（CREATED+LIVE+RECEIVED，taskId 2963），5/7。随后崩于 **`TELEPHONY_CAPABILITY_UNSUPPORTED:getNetworkCountryIso()`**。
- **已修 telephony locale/运营商只读查询群**（本会话）：`RuntimeSelfHookManagerPolicy.required(TELEPHONY)` + `RuntimeSelfHookManagers.telephony()` 加 `getNetworkCountryIso/getSimCountryIso/getNetworkOperator/getNetworkOperatorName/getSimOperator/getSimOperatorName`（均路由真实平台 TelephonyManager 只读）。host 全过（policy 331/supported 123）。重建 Runtime `db88c4ebf1ce7bb07aef61c6197cbf4a892c9c762b48fe4f220b69cf298edbae`，已 `install -r`（保留 wechat space）。
- `wechat-10-telephony`：**telephony 崩溃已消除**（修复生效），新首因 **`PRODUCT_MEMBER_MISSING:android.content.Context$BindServiceFlags.getValue()J`**（`ComponentFrameworkAccess.java:24` `FixedFrameworkMembers.method(17,"getValue","()J",false)`）。微信 init 调 bindService(BindServiceFlags)（API34 long flags），runtime 查 owner 17 的 `getValue()J` 失败。
  - **已排除索引漂移**：native names[] 55 / Java NAMES 55 完全对齐，index 17 均为 `android.content.Context$BindServiceFlags`，classes[55]/declarationCache[55]。
  - **根因**：`getValue()` 在本设备 BindServiceFlags 类上**不是声明方法**（android.jar stub 也仅 `of(long)`+ctor，无 getValue）。pre-existing bug：ComponentFrameworkAccess 假设了不存在的方法，微信是首个触发 bindService(BindServiceFlags) 的 guest（P2 service fixture 疑只测 int flags）。
  - **下一步**：枚举设备 owner 17（BindServiceFlags）实际成员/字段，找提取 long 的真实途径（很可能私有 long 字段如 `mValue`），改 `ComponentFrameworkAccess` 用字段而非 getValue()；加对应固定成员注册 + 公共 SDK fixture 覆盖 bindService(BindServiceFlags)；重建复测微信。不加 flag/不返回 null/不发往宿主。
- 之后仍有：微信后续层、王者 PendingIntent（`runtime-pending-intents-plan.md`）、item-3 稳妥退休、当前源 P4/核心矩阵回归、正式包重建验收。
