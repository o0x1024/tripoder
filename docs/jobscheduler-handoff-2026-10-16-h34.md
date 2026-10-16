# H34 JobService / 客体 API 交接

更新时间：2026-10-16，Asia/Shanghai。用户要求在此处更新交接，准备由其它 agent 继续；本轮没有宣布最终目标完成。

## 最终目标与当前状态

微信、王者荣耀实际正常运行，正常 Java/Native Hook 安装、调用、卸载不引起 panic、崩溃或 ANR。**最终目标未达到。**

客体 JobScheduler API 与真实 JobService start/stop/finish 已接通，真实首个冷 start 约 1.15 秒，热 start 约 24–38 毫秒。**完整 jobs 阶段仍是失败，冷恢复第二次回调没有通过。** 当前源码未跑 P4、正式 Engine 控制验收或微信/王者启动。H33 的 P4 105/105 只能代表 H33，不能追认 H34。

用户本轮明确授权继续验证微信与王者，这覆盖旧 task 中“不运行第三方测试”的旧阶段限制；仍不使用 root/run-as，不清库、清缓存、删除 UNKNOWN、扩容绕过，也不操作宿主微信/王者的私有数据。

## 可直接续接的制品和运行状态

- 当前 Runtime APK：`android/runtime-probe/build/runtime-probe.apk`。
- SHA256：`bfa63a747f564cf2a3cf29e5ce3f52e89f0d202a31d6005a9e140be4594e6121`。
- 已安装至设备 `d861a4fa`；当前 UID **10409**（不是旧记录的 10407）。设备 APK SHA 与本地构建相符。
- `android/runtime-probe/build/build-receipt.json` 的 **458 个源输入全量重核，changedInputs=[]**。
- APK、receipt、主机报告、完整 receipt 输入源快照已归档于 `docs/evidence/jobscheduler-lifecycle-h34/artifacts/`。源码归档 `sources-at-bfa63a74.zip`，SHA `22a6f722b7b6dd94c01e0c8265bbc876a35cb11811f7b83f81576d5baede53a7`。
- 正式 `android/product/build` 仍是 H33，已过期，不能安装冒充当前源。独立 scheduling probe 当前构建 `0c59ddfd…` 也已过期（5 个输入变化），未跑新的 65 项。
- 当前没有构建、设备验证脚本或子代理仍在运行。设备只剩 Runtime 主 UI 进程 PID 6726；无 authority / slot worker。PID 是交接时快照，续接需重新核对。
- 没有 commit/push。保留整个既有大量 dirty/untracked 工作区；不要重放子代理补丁。子代理仅做只读架构/ANR 审计，未提交代码。

## 本轮源码实现

### 固定 Hook 与冷启动边界

- `android/self-hook/native/art_framework.c`：固定 boot roots 51→55；新增 owner 51 `JobInfo`、52 `JobParameters`、53 `JobService`、54 `IJobCallback$Stub$Proxy`。原索引保留。
- 固定字段 slot 39 callback Binder、40 extras、41 mJobNamespace；Java `FrameworkAccess` 和 `FixedFrameworkMembers` 同步。
- `JobFrameworkAccess`：JobInfo 早/晚约束存在位、独立 JobSchedulerImpl facade、真实参数 Parcel 复制后移除 callback 并恢复逻辑 extras/namespace，断言副本没有 cleaner；固定 Service.attach 传逻辑 Context/Application，token 和 activityManager 为 null。
- `RuntimeSelfHookJobs`：JobService 的 final jobFinished/onBind/setNotification/网络字节入口、参数工作项/cleaner/隐藏 callback 入口，以及三个真实 IJobCallback Proxy 回执方法。支持面之外明确拒绝。
- `RuntimeSelfHookBootstrap` 现在 **26** 个能力，实际完整固定安装 **2215** 个绑定（原 2177 + JobSchedulerImpl 22 + Job 必需入口 16）。三个 validator 的启动证据格式已更新为 `26/26` / `2215`。
- `RuntimeFixedPreparation` 在物理 JobGateway.onCreate 预备固定成员与 ManagedHook handles，**没有 enable relay，没有客体身份**；收到真实 onStart 参数并完成准入后，由原 RuntimeSelfHookProduct 安装实际 handlers，检查预备 handles 全部消费。
- `RuntimePhysicalManifest` 在进入解释器策略前采集一次真实宿主 APK 的不可变组件信息，后续 Engine 使用同一 host/package/sourceDir 绑定的快照。
- `RuntimeManagedInstaller` 仍是唯一安装后端；复用预备 handles，不新增第二 Hook 路径。

### 客体 API、系统回调与持久事实

- `RuntimeJobScheduler` / `GuestContext` / manager policy：schedule、cancel、cancelAll、cancelInAllNamespaces、pending 查询、forNamespace/getNamespace 已接线；facade 用 WeakHashMap 保存身份，默认 facade 单独保留。
- `RuntimeWire` 新增 45 JOB_ROUTE、46 JOB_BEGIN、47 JOB_EVENT。route 在 broker 锁外进行平台调用；completion 允许已准入 Job 回执穿过关闭 admission fence。
- `RuntimeJobBootstrap`：真实物理 PID/UID/slot、版本 nonce、jobId、stub、安装和 Hook generation 校验；版本墓碑拒绝早于任何客体构造；复用 ProcessSupervisor.claimComponent。gateway 保留真实 Android Service token/engine。
- `RuntimeJobs`：逻辑 JobService 由 guest factory 创建；同版本的各次执行按真实 callback Binder 区分；参数视图按对象关联，旧/重复 finish 拒绝；物理参数强引用保持到真实 callback 返回。
- `JobAttemptLedger`：schema **27→28**，新增 job_instances / job_attempts / job_attempt_events；持久记录 START/STOP/FINISH_REQUESTED 与实际 *_RPC_RETURNED，进程死亡保留 UNKNOWN；Binder 身份仅在本次 authority 内存中存在。
- `RuntimeJobBroker` 查询可协调正向 pending 证据；真实 callback admission 也能协调 schedule 的 UNKNOWN。失败 ACK 与已观察 callback 冲突时不写失败成功。`JobComponentLedger` **资源退休尚未实现**。
- `RuntimeSelfHookPlatformCallbacks` 单独拒绝逻辑 JobService stopSelf/foreground，未把它混进普通 Service token/账本。

## 两个已经定位并修复的问题

1. **缺省应用图标导致窗口启动崩溃**：新夹具没有图标，PhoneWindow 调用 `PackageManager.getDefaultActivityIcon()` 被拒绝。`RuntimeSelfHookPackages` 现在提供真实系统 `android.R.drawable.sym_def_app_icon`；没有通过修改夹具图标掩盖接口缺口。
2. **真实 Job start ANR**：设备 JobServiceContext start/stop ACK 为 **8000ms**，bind 为 18000ms 基数。首次把固定 Hook 预备放进 bind 后，onStart 仍超时。实际 ANR 栈显示 `ParsedActivityUtils.parseActivityAlias → getPackageArchiveInfo → RuntimePackages.captureInfrastructure → RuntimeEngine.<init>`：在全进程解释器模式下解析宿主巨大 manifest。移到 process policy 之前采集物理快照后，冷 onStart 返回约 1.15 秒。

精确证据：`jobs-01/anr-409.txt`，文件开头含 Timeout 8000、SvcCreate/SvcBind/JobStart，main 为 Runnable。只读审计确认 ManagedHook.prepareBatch 仅准备 backup/helper，target relay 到 enable 时才发布；没有未 enable relay 递归证据。

## 真实设备结果：不要跨轮相加

| 目录 | 当前结论 |
|---|---|
| `jobs-01` / run `jobs-276ef3abb31d45e8` | 失败：默认图标接口崩溃 + Job worker ANR；原始 Java crash、ANR trace 均保留。 |
| `jobs-04` / run `jobs-59bd7e8b44704433` | 冷 start 1146ms，热 36–38ms；运行中 worker 死亡后 MIUI 拒绝 RestartService；夹具把 Activity result 写成 `report` 而宿主期待 `result`，随后控制器崩溃，整轮 UNKNOWN。字段名已修复。 |
| `jobs-05` / run `jobs-623df5cce3284955` | 图标/ANR/回传已修；同步、异步 finish、取消的真实回调通过。直接终止运行中 job worker，120s 未观察第二次 start；整轮失败，无意外 Java/Native fatal、unexpectedAnrObserved=false。 |
| `jobs-06` / run `jobs-6fc91e193d5b4cae` | 当前 bfa63a74。改成真实 `jobFinished(..., true)`，等 FINISH_RPC_RETURNED=true 再终止 worker。仍未观察第二次 start；**整轮失败**。冷 start 1169ms，热 24–28ms，物理准备 7791ms。无意外 Java/Native fatal、unexpectedAnrObserved=false。 |

`jobs-06` jobId 44 的完整事实链：START_ENTERED → START_RETURNED(true) → START_RPC_RETURNED(true) → FINISH_REQUESTED(true) → FINISH_RPC_RETURNED(true)。这只证明 RPC 返回，**不证明系统接收重试或已经重新排入 pending**。

### 冷恢复的下一条关键证据

目前不能把剩余问题继续归咎于 MIUI 自启动。05 已无拒绝日志，06 是正常 finish(true) 后退出，仍无第二次回调。须在回执后、kill 前及后续 **0–30 秒** 内记录真实系统 JobInfo / pending reasons / earliest runtime / namespace / version；不要只看 120 秒超时后的状态。

`jobs-06/retry-state.txt` 的 unknown(job44) 是在测试约 2分16秒后取的；夹具 120 秒超时后 finally 会 cancelAll。因此这条 late unknown **不能证明系统最初丢掉了 retry**。保留它，不把它当根因。05 的系统片段也需结合采样时间分析。

当前 P2 fixture 的 kill gate 只有 `retryConfirmed`（RPC返回）+ 当前 worker PID，相比设计的系统确认还少真实 pending 版本证据。下一 agent 应先补这个观察边界，区分：真实重试未提交、平台等待/配额/OEM策略、或恢复代码拒绝入口。不要写 timer 重放 schedule，不要把等不到回调改成通过。

## 设备设置实际变更

通过 Android 应用信息页面，已为 **自有 `dev.tripoder.runtimeprobe`** 打开 MIUI「自启动」。只改该应用开关，未更改全局策略、其他应用、root 或 appops。

- 前后 UI XML：`autostart/settings.xml`（false）、`autostart/enabled.xml`（true）。
- 05 系统日志从 Reject RestartService 变为 Scheduling restart of crashed service。
- 尚未调整省电策略；不推断其它设备策略已放行。
- H31 P2 sticky 重启尚未在新设置下重测，不能标通过。

## 主机测试及尚未验收的内容

`host-final.log` / 归档 host-report：bootstrap304、loader15、component85、boundary117、manager policy319（supported117）、deadline8、typed manager45、native mapping12 全过；SDK sources226。Runtime build-06 成功，当前源匹配其 receipt。

尚未完成：完整冷恢复、authority 实际死亡恢复、同版本多 attempt 的完整验收、替换迟到/错误 callback/跨空间实际入口反例、Hook 恢复阻断的真实设备验证、SYSTEM_RETIREMENT_OBSERVED 与组件回收、未知取消的 owner 收口、当前 P4/核心矩阵/正式控制、微信王者实际启动与登录链路。

已有纯调度 65/65 是 H33；本轮修改 schema/fixture 后未重新运行，不能算 H34 通过。新 Native registry 和预备路径需要当前 P4/核心回归。

## 资源退休：仅讨论过，尚未实现

本轮多次 jobs 测试使用新 space，会留下 job_components 引用。不能删除行或清库解容量。下一 agent 可设计：仅对版本已永久 fenced、无 mutation owner、实例均 DESTROYED/PROCESS_DEAD 的候选，观测真实 pending 缺失、AMS service 缺失和必要物理 process 退出，在再次核对候选未变后写 retired/resourceProof；UNKNOWN 业务状态仍保留。不得仅凭 pending 空释放，也不能回收等待有效 retry 的未 fenced 版本。

`RuntimeJobRetirement` **没有创建**。本轮没有实现自动清理旧测试 jobs，也没有实现 `cmd jobscheduler run` 验收触发器。不要把对这些方案的讨论当现有功能。

H31 的未附着 UNKNOWN Activity ticket 长程 pin 是另一条未完成问题；不能与 Job 组件回收混为一谈。

## 第三方启动入口已加，但从未运行

`validate-runtime-probe.py --phase target` → `ArtifactProbeActivity` 的 launchTargets。使用设备已安装公开 APK，核验固定 hash 后走 guest 安装、DEX inspection、prepareActivity，观察 guest process 15 秒，再停止。此仅是启动诊断，不是登录/正常业务/用户自定义 Hook 验收。

固定源：微信 `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`；王者 `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`。如设备源已变，先重新核验，不改 hash 放行。

## 续接命令

所有 shell 以 rtk 开头。CodeGraph MCP 未暴露，CLI `/usr/local/bin/codegraph` 可用；`.codegraph/` 存在但索引可能落后。不要误报未初始化。设备只允许一个安装/启动负责人。

当前已安装 bfa63a74，源未改时可直接运行：

```sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-lifecycle-h34/jobs-next --phase jobs
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-lifecycle-h34/p4-current --phase p4
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/jobscheduler-lifecycle-h34/target-current --phase target
```

第一条现有冷恢复检查已知未通过；应先补齐上述早期系统状态证据，不盲目重复同一 120 秒等待。每轮使用新证据目录，失败不覆盖。不要同时运行这些设备命令。

重建 Runtime：

```sh
rtk proxy python3 android/scripts/build-runtime-probe.py --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --ndk android/vm-probe/build/native/tools/android-ndk-r30 --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so --platform-linker android/self-hook-probe/build/device-linker64
```

正式产品用相同参数改脚本为 build-workbench.py，必须在 Runtime fixtures 最新构建之后。当前正式包过期，不应直接验收。
