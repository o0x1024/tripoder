# P2–P6 实施中的真机证据

本目录是实施记录，不是 P2–P6 完成证明。P6 范围为微信与王者荣耀；尚未进入登录验收。

## 客体 Java Hook（2026-10-14）

`p4-java-03` **40 / 40**，runId `p4-1ba35ba049b143a9`，Runtime APK `a1ba887b30bbd66d28923b1464b83d58a594aea570eaf3b798a4fec75e9d6892`。实际记录 211 次 Hook 命中、2 次预期脚本类型错误、0 个剩余调用。自研客体覆盖静态/实例重载、String/null、long `9007199254740993 → 9007199254740994`、8 线程 200 次调用、递归、原 Throwable 对象身份、before 错误时原函数 0 次、after 错误时原函数 1 次、停用恢复原方法、重启拒绝旧准备结果。

`p4-lifecycle-02` **62 / 62**，runId `p4-7649821e38b448f1`，Runtime APK `d5f82880e9e01e4b602bd8d9b85a94c780a49fb93953d416e02791bb812ce391`。在上述基础上，准备 token 不进入持久回执；取消后 token 无效；真实原方法被闩锁暂停时，参数从版本 1/100 更新至版本 2/200，旧调用最终返回 107，新调用返回 207。停用在实际 inFlight=1 时返回 DRAINING，释放原方法后仍返回 207，再查询才确认 DISABLED；越界/旧版本参数被拒绝。

`p4-java-01` 保留进度记录误用一次性最终报告接口的失败；`p4-java-02` 保留 SystemClock 静态 Frida 方法丢失接收者的失败。`p4-lifecycle-01` 中参数越界已正确拒绝，但回执未带 SDK 错误码，导致精确断言失败；修复错误码序列化后取得上述 62 项通过。这些版本尚未包含 Native Hook，也不证明生产工作台部署或 P6 通过。

## 一次性启动与工作台死亡（2026-10-14）

`workbench-launch-atomic-01` **50 / 50**，同构建 `workbench-launch-ui-02` **6 / 6**。工作台 APK `f951ffeee682a2fa2650dc9eb96627f32db769f3a5bdd48d093222095dab02b9`，引擎 `374ca311e1614c96dbe3d354d0b1992ef2936fb2545e07730c928e4b2ea54646`，控制测试 `caf3cf6fd944e6291efd5a18c993530d4f3e98424b5484d4ca85f2dea9036c29`。

控制检查包含凭据一次返回、防重放、安装/绑定代次、30 秒未派发过期，以及真实终止 Authority 后根据 READY / ticket=null 的持久证据协调旧操作为 FAILED。原生 UI 从真实项目启动原始自研计数器；Android 系统未安装该客体。强制停止工作台 PID 6404 后，引擎客体 UID 10400 / PID 13244 未变，仍可递增到 2、重建界面一次并保留数据。工作台冷启动后查询原操作，显示启动已完成。截图、UI XML、实际进程观测及逐项判定均保留。该构建没有包含后续 ART 修复。

`workbench-launch-ui-01` 保留 Gateway 事务嵌套导致 `ACTIVITY_OBSERVATION_DURING_TRANSACTION` 的失败。修复为事务外任务观察、ActivityLedger 在同一次提交内关联启动凭据与票据，之后才取得上述入口证据。

## ART GC 修复与回归

`launch-atomic-p2-01` 在 153 项断言后失败：第 18 个 Churn 子 Activity 附近，客体进程 PID 570 在 `CodeInfo::DecodeGcMasksOnly` / `ReferenceMapVisitor` 栈扫描中 SIGSEGV。`owned-native-crash.txt` 保留原始崩溃；旧按 worker PID 过滤的 crash 文件遗漏了 debuggerd 进程，不能以空文件判定无崩溃。

核对当前 Frida Java Bridge 7.0.13 缺少官方已合并的 Android 17 修复 `302a588881b40cba300bcf3acfd35deeb3888ee5`。构建器按固定源摘要应用该修复，并增加当前 ArtMethod 内字段布局检查及真机布局证据；固定 ART 库摘要防止 APEX 更新后继续沿用旧验证。未采用仍在评审的 GC 符号查找补丁。Churn 夹具增加独立线程持续 GC，并要求至少 24 次真实 GC。`art17-gc-p2-01` **439 / 439 通过**，runId `p2-52b3c780a56d45f4`，Runtime APK `3c9af9937bec667d16ad578c3565c4f3603b0109537222b4015e7e5897ebdf88`。实际连续发生 **761 次 GC**，24 个子组件完成销毁、一次重建，客体 PID 17421；未观察到 Native 内存崩溃。布局记录直接证明旧算法偏移 36、新算法偏移 4，当前 ArtMethod 大小仅 32 字节、JNI 入口偏移 16、quick 入口偏移 24。该证据支持修复真实内存布局错误，不能推广为长期稳定性或 P2 全能力通过。

同一修复构建另通过 P0 **60 / 60**（`p0-4d16679f84a44159`）、P1 **223 / 223**（`p1-b523b29a4b29404d`）、P3 **274 / 274**（`p3-49119653146a46a1`），目录分别为 `art17-p0-01`、`art17-p1-01`、`art17-p3-01`。产品构建 `art17-workbench-01` 控制测试 90 秒超时，未产出终态报告；其失败不可用 Runtime 通过结果替代。

加入逐步原子检查点后，`art17-workbench-02` **50 / 50** 通过，设备内 44 项控制检查用时 31.238 秒，其余为外部身份/构建检查。工作台 `af3765a64c1a5ca9ea5386b8eedec505a18ca8a719a273eadd8f448d076a5898`，引擎 `d0d137efa36292e4ba8c70e0368f556f70522fa215d10fee3b8a8fea57306138`，测试包 `dc7f7fe2565ccf91ad24528a06b60692a8fd68d9fdf31483d83cc1c32920a55f`。上一轮超时原因仍未确定；一次重新通过不能关闭该稳定性问题。

验证器现按本次设备时间和自研包 UID 收集 crash buffer，覆盖不同 reporter PID，另存 `TripoderART` 布局记录；本次任何 SIGSEGV/SIGBUS 均不能判为通过。

## 冷恢复与独立工作台（2026-10-14）

`workbench-ui-binding-03` **38 / 38** 通过；`workbench-native-ui-01` **9 / 9** 原生 UI 检查通过。对应工作台 APK `a1941998c9d20773a2bb7d8ef09a0d222b4df39ae025666ef3df538aa87d9b9f`，引擎 `db8836483f3141735302edf3eb54f5fd8cda4e86ebb7c0d18ced3afddaa40964`，测试包 `9b4442e7fc94af736a7a352cec7e5eb75f00bf3f9fed70beeed69a8defcc77fe`。增加未接收操作的取消记录与迟到请求拒绝。`01` 保留缺少 RUNNING 状态转换的失败；`02` 保留 Binder 未携带执行契约错误码导致精确断言失败；修正后才取得 `03` 结果。

UI 验收经真实系统文件选择器导入自研 A APK、创建项目、选择安装并显式绑定，触屏键盘输入脚本标记 `42`，未保存退出提醒、保存再打开均通过。真实终止工作台后 PID 从 32570 变为 32707，引擎 Authority PID 32613 不变；源码与安装代次 `32426998-a948-4465-97c4-2ca12a10f0be` 保留。截图/XML、输入动作和逐项判定保留在 `workbench-native-ui-01`。adb 文本注入未产生可见输入，因此改用已观察到的触屏数字键完成 UI 测试；产品代码没有注入专用入口。此结果尚未包含运行中的客体、Hook、微信/王者登录。

`project-binding-p1-01` **223 / 223**、`project-binding-p2-01` **438 / 438** 通过，分别为 runId `p1-e9edac161d414d98` / `p2-199dc1685755437e`，同一 Runtime APK `fd6613812b0c6e4fcdee567981deec38e5d7e1e6820a16efdc985ba163ff1ba4`。包含 schema 17 的安装目录、并发安装与已有组件回归。

`workbench-install-binding-01` **35 / 35** 通过：独立 UID 之间只读 FD 安装原始自研 APK；拒绝可写 FD、摘要错误和错误安装代次；相同操作去重；显式绑定、升级使旧绑定失效、按旧 bindingOperation 重绑且保留 dataId；真实终止引擎后安装与绑定仍可解析。工作台 APK `38fd5237b099a3333cb528c344ac41ce364de1478777d729e945f6354ee33ae8`、引擎 `e0037c0f894d2119e486e655a73b03a4c4887af4b4768873c56e19ed66cbee66`、控制测试 `796ac8c560f8dce99f787771f554127e510e6ab3ab8be3b4330b3cc86afb80b3`。该快照不包含后续项目编辑器和文件选择器的 UI 验收。

`install-concurrency-p1-01` **223 / 223 通过**，runId `p1-1bf40803e5cd4c1f`，Runtime APK `5b116f97ad159d16785a8f289e1772fbdc7bbab4822b38d7fb4ec3d64e2f8a4a`。在 AFTER_COPY 和 AFTER_PUBLISH 两个真实安装阶段暂停线程时，操作查询均记录为 0 ms（毫秒计时精度），恢复清理保留未提交的活跃路径；另一自研应用分别在 PID 21678 / 21738 完成注册与 Application ready，随后停止，原安装释放后各提交一次。此结果证明这些安装阶段不再占住 Broker/缓存全局锁，不能据此宣称所有冷启动和所有元数据 I/O 都满足产品性能预算。

独立工作台控制入口在 `workbench-control-08` **20 / 20 通过**：实际工作台 UID 10401、引擎 UID 10400；拒绝同证书错误包、转借第三进程存活 Binder、重复所有者、旧连接、引擎自身 UID、原始 worker/probe 命令。空间创建操作去重并有持久回执；真实终止引擎后，新 authority 能查询原操作和空间。`fullP5Accepted=false`，仍未接入安装、项目、通用 Hook 和目标业务链路。

工作台 APK `b2b0fdf871914fee95dd1daecb3860b017721379cc83ebe7afee5ff6f28fc450`；引擎 APK `5392e931869a64fde2b17d0f1201d2178bdb1b5bbe1355f8dd36a4d17c2cf006`；单独控制测试 APK `c39c02a7932eb1c903cb704b972cd903cb430fffdd77717bf1231850fb93cc3b`。`workbench-connected.png` 为实际产品画面。

`workbench-control-03` 保留自启动设置后仍被 WakePathChecker 拒绝的记录；`04` 是 instrumentation 后台启动 Activity 被系统拒绝；`05`/`06` 在首次关联启动确认期间未连接；`07` 产品前台入口已成功，负向测试包仍因后台启动被拒绝。最终验证由外部 Launcher 只打开工作台与测试夹具页面，引擎由工作台真实产品入口启动；没有 shell 启动或授予引擎控制权限。平台的工作台→引擎关联启动确认已明确允许。

以下各行是单独绑定构建的结果，不能相加为一次全产品验收。

| 证据目录 | Runtime APK SHA-256 | 结果 |
| --- | --- | --- |
| `provider-identity-p1-01` | `6180651ce7d2da9b0241d113133266805a5df44a57f3c1979d42e1a20d0de52d` | P1 204 / 204 |
| `provider-identity-p3-01` | `6180651ce7d2da9b0241d113133266805a5df44a57f3c1979d42e1a20d0de52d` | P3 274 / 274 |
| `lazy-artifact-recovery-p1-01` | `f5aa1eba8c5ec857df59088b4853971d325be68ffb662c55346e5c8ee579a0b7` | P1 205 / 205；11.134 秒 |
| `provider-identity-p0-02` | `aa665379911d3e89db0123f53978beb2bb985969d567708002e4a60e83de78d8` | P0 60 / 60 |
| `cold-integrity-p1-01` | `90079418f239e4fdd8df742c42d02f13629ef409e3be4d79218f2a6238ea3a20` | P1 211 / 211；runId `p1-3ce5329dd6884dfd` |
| `cold-integrity-p2-01` | `90079418f239e4fdd8df742c42d02f13629ef409e3be4d79218f2a6238ea3a20` | P2 438 / 438；runId `p2-db16ee80ccb54017` |

P1 211 项包含先篡改自研 Native 制品、再终止 Authority 的场景：冷恢复不会立即扫描全部历史 APK，首次使用仍完整校验并拒绝损坏文件，随后该安装保持 CORRUPT。无关历史制品不再拖慢冷恢复；这不代表安装长锁与大 APK 冷组件启动耗时已经解决。

`provider-identity-p0-01` 保留失败：夹具仍捕获旧包装异常，实际已按契约透传 `IllegalArgumentException`；修正夹具的精确异常预期后才取得 P0 60 项通过。

`workbench-control-01` 是手机拒绝首次安装的记录。用户配合后 `workbench-control-02` 的三个 APK 均安装成功，但测试在绑定引擎时失败；系统 `WakePathChecker` 明确拒绝 `dev.tripoder.workbench → dev.tripoder.engine` 的后台唤起，尚未进入控制协议断言。保留构建摘要、原始 instrumentation 输出和限包名诊断；未通过 shell 启动引擎来伪造产品连接。

## Provider 身份批次（2026-10-14）

`provider-identity-runtime-01` **438 / 438 通过**；runId `p2-d316906b6e294d52`；Runtime APK `6180651ce7d2da9b0241d113133266805a5df44a57f3c1979d42e1a20d0de52d`。包含全部既有 P2 回归和 9 项重复 Provider 类新断言。运行实例 PID 2297 / UID 10398；自研客体 APK `12a081d10c3e13dfcc75067dad00974b557e3bd8a2e6982b61f5c34fb81a4473` 未改写。

同一客体 APK 的原生对照在 `provider-identity-baseline-04`，UID 10399，两个冷进程 PID 25943 / 27697；它们没有参与 Runtime 调用。原生包元数据保留两条声明，组件实例只初始化一次、使用第一条 authority；分别按 authority 查询仍得到各自 metadata。此固件访问第二 authority 时还暴露本地引用计数的 `connection is null` 异常，完整异常栈保留，不将其误记为应用回调失败。

Runtime 新断言确认第一实例只创建一次，第二 authority 抛 `SecurityException`，随后第一 authority 仍可正常调用；回调计数精确为 1、2、3，拒绝请求没有进入业务回调。PM metadata 按 flags 返回并复制，已关闭全部 acquisition，没有新增 UNKNOWN 业务效果。跨进程/enablement 不一致的同类 Provider 声明仍明确拒绝。

`provider-identity-baseline-01` 为手机拒绝安装的历史记录；用户同意后 `baseline-02`/`03` 为逐步补全异常栈的观察版本，只有 `baseline-04` 与本次 Runtime 夹具哈希相同。P1/P3 回归另行记录；新工作台控制代码仍在实施，不能引用本次 P2 结果声称 P5 完成。

## 2026-10-14 Activity 批次已验证快照

APK SHA-256：`1f85c77299e3a8901b5ca7b28988864835ed1f7c461b5640139f3a586e0e6d00`。

| 验证 | 证据目录 | 结果 | runId |
| --- | --- | --- | --- |
| P2 进程、嵌套 Activity、并发启动、结果覆盖与 recreate、冷任务恢复 | `process-activity-07` | 74 / 74 | `p2-f414d868959f40a8` |
| P0 回归 | `p0-regression-04` | 57 / 57 | `p0-826d7153e57c4a3f` |
| P1 安装、空间、迁移与 authority 崩溃恢复 | `p1-regression-02` | 179 / 179 | `p1-a8d417934eca4c31` |

每个目录保留构建源码摘要、签名、设备结果及验证器输出。以上是同一个构建的结果；后续源码修改须重新构建验证，不能沿用此结论。

此前失败尝试保留：`process-activity-02` 暴露结果回传先于销毁的状态乱序；`process-activity-03` 暴露系统后台拉起限制；`process-activity-04` 暴露测试入口任务根不一致；`p0-regression-02` 暴露控制端未确认已收到的根 Activity 结果。修正后才取得本节快照。外部 Launcher 恢复是测试刺激，不是 runtime 的 adb 依赖。

## 待验收

Service/Provider/Receiver、任务模式与跨进程死亡后的在途结果、URI/PendingIntent、拆分包执行、启动超时和迟到回调故障、Java/Native 文件与系统语义、Hook 部署生命周期、独立 UID 工作台整合、真实微信和王者荣耀核心链路均未完成验收。`fullP2Accepted`、`genericApkExecutionVerified`、`strongIsolationVerified` 均为 false。

同 UID 客体运行器不是恶意 Native 代码的强隔离边界。不存在 VirtualApp 代码路径，也不以宿主目标应用执行充当虚拟空间结果。

## Service 首轮结果（未通过全部门槛）

`service-01`，runId `p2-7a2508dbc9ad4257`，APK `17650914b3a7735a9d5e2f5636240eb06b503024a7c838674082377aba993133`。

11 项新增 Service 断言实际通过：Application.onCreate 同进程绑定无循环等待、同进程两个独立实例、客体 Context/Application/loader、跨进程真实 Binder、多个系统 startId、onUnbind/onRebind、旧 startId 不能停止、最新 startId 可停止、已绑定服务继续存活、最后解绑后真实 onDestroy，以及 onNullBinding。

接着杀死自有服务进程，45 秒内未收到重连接，整轮结果为 **false**。`system-restart-rejection.txt` 保存了系统在 09:57:09.629 的 `AutoStartManagerService: MIUILOG- Reject RestartService`，对象是自研测试包的 `RuntimeServiceSlots$S1_2`。未见新的服务进程进入 Factory，不能据此宣布冷恢复代码通过或失败。已请用户只为测试包开启后台自启动；runtime 没有通过重新 start/bind、shell 或修改系统设置绕过该策略。

本轮不是最新版源码的完成证据；后续修改仍须重新构建、验收及 P0/P1 回归。

## Service 第二轮快照

用户只为自研测试包开启后台自启动后，`service-02` **90 / 90 通过**；runId `p2-78f4f28bd99b4186`，APK `3c6b4fdc14296079c8e47b79cceca26dd76dfac1e50cc783e447d0642c039ef6`。

sticky 服务真实 PID 从 `25251` 变为 `25391`，实例 nonce 改变、逻辑进程及数据路径不变，Android 自动重新连接原 ServiceConnection，并交付 `startId=2 / nullIntent=true`。没有主动补发 start/bind。新增“stop 不存在的 Service 不启动 Application”断言通过；包含 BindServiceFlags 与 Executor 入口。前台服务、完整 Binder 身份政策与 Provider/Receiver 仍未验收；此快照之后继续进行组件工厂改造。

## 组件工厂快照

`component-factory-01` **102 / 102 通过**；runId `p2-d51c8c1978624f4e`，APK `e0c4bcbdb383686ee703e18d3de31c93979d4886196abbc9f96959d3c0bef405`。

自有 APK 声明的 factory 返回自定义 ClassLoader、创建 Application，并把一个 Activity 替换成未直接声明的实现类。真机结果确认逻辑组件名仍属于原声明、ActivityInfo/主题与创建票据使用原组件身份，替换实现来自客体 loader。工厂得到的 Intent 不含 runtime 私有 extras；Service 与 Activity 冷恢复一并回归。该快照仍不包含 Provider/Receiver 或实际目标应用验收。

同一构建的 P0 `p0-regression-05` 为 **57 / 57**（`p0-b440c954d9f44e5c`），P1 `p1-regression-03` 为 **179 / 179**（`p1-1a386920ef15483d`）。这是组件工厂批次的回归证据，不覆盖后续 Provider 改动。

## Provider 首批闭环快照

`provider-07` **156 / 156 通过**；runId `p2-9e9f01734ea749bf`，APK `882d08ff9fa64b10a6577772755edfec04108f89fa5f208fe8a609274fc5b414`。

两个空间的真实 Provider 初始化顺序、Application 前发布、声明 factory、authority alias、跨进程 Cursor/call/getType、逻辑身份与实际 Binder PID/UID、stable/unstable 客户端、目标死亡与新代次获取、本进程及跨进程初始化环检测均通过。宿主同 authority 诱饵零命中；客户端释放后 OPEN 引用为零，未决调用为零，调用账本与执行契约状态无冲突。写入、文件描述符、取消、观察者、URI 授权及完整权限政策仍待实现，不代表 Provider 或 P2 全量验收。

保留失败轮次：`provider-01` 暴露 attachInfo 内部 AppOps 初始化；`provider-02` 146 项通过但未覆盖最终引用审计；`provider-03/04` 审计发现两个显式客户端引用泄漏；`provider-05` 调试暴露 JavaScript 异常被桥接为默认返回；`provider-06` 将该错误显式传回 Java。最终原因是 Frida 释放 Hook 返回的包装对象，缓存的 Settings interface 包装在第一次返回后失效。修复为每次取得新包装对象，句柄身份与释放由 Java 持有；未删除或放宽 OPEN=0 门槛。回归结果随后单独记录，不能把旧构建结果覆盖到此构建。

`p0-regression-06` 失败于旧测试期待全部其他 authority 返回 `PROVIDER_CALL_UNSUPPORTED`。新实现明确拒绝空间内不存在的 authority，实际错误链是 `PROVIDER_ACQUIRE_FAILED` → `PROVIDER_NOT_IN_SPACE`。只更新该拒绝断言后，`p0-regression-07` **57 / 57**，runId `p0-e743cae442984c06`，APK `f238781ae175d9d78a4d07f64a050ea03c1947ea9197757f2a71d91c5c7d9e28`。该构建尚无 P1 结果；后续 Receiver 编辑开始后，验证器拒绝以旧构建摘要运行 P1，未绕过源码一致性检查。

## Receiver 首批闭环快照

`receiver-02` **189 / 189 通过**；runId `p2-50d995d39ae94f0e`，APK `4b5ce621970f3e2c7b527cb7e9af8c7becab86a19209eb7ba22d3c7e1d789474`。

两个空间分别确认：目标未预启动，Android 广播导致新 PID；客体 factory 和 Application 初始化先于 onReceive；真实有序结果等待 goAsync/finish；同一动态实例两个 PendingResult 按 2→1 顺序完成，指定 Handler 线程正确；注销后不再投递。每个空间三次投递均终态成功，动态注册全部关闭，无未决调用、无账本冲突；宿主同 action 诱饵零命中。该批仍没有证明多目标有序广播、manifest 隐式过滤、冷启动竞争、注销竞争及全部故障门槛。

`receiver-01` 保留失败结果及 `receiver-native-crash.txt`：Hook 安装触发设备 JVMTI 字段枚举原生崩溃。通过显式方法模型修正后才获得第二轮通过结果；没有删掉匿名类测试或改用手动 onReceive。当前构建包含确定性的 Frida bridge 方法模型扩展及摘要，见构建凭据 `javaBridge`。

同一 receiver-02 构建的 `p1-regression-05` 179 / 179（`p1-ba608b5dc44a4f56`），`p0-regression-08` 57 / 57（`p0-2da0ee09317b4ab6`）。

## Receiver 与 Worker 并发冷启动

`receiver-03` **193 / 193**，runId `p2-1a74d4a69ec44714`，APK `bade18ed6721d7ee57da5821597e5a958d2885926291c95966c787fd60ecea89`。探针阻塞 authority 主线程的 bind，确认 Worker 已预约 ALLOCATED/PID=-1，再触发真实 Android 广播。Receiver 接管原预约并完成 Application 启动，ensureProcess 在 bind 放行前返回，同代次同 PID，应用及 factory 各一次。修正了组件冷启动与 Worker 重复初始化/主线程互等的结构问题。

同构建 `p1-regression-06` **179 / 179**，runId `p1-1265b9faaead4224`。后续 P3a 存储改造是新快照，不能沿用这些结果宣称通过。

## P3a Context 存储首批快照

`storage-01` **172 / 172**，runId `p3-0b6d64ab91d2489d`，APK `49c3184764623535cab1fda5cff76845883cf7ab6772853519e456d8f9bbd005`。两个空间 × 首次/重启/同签名升级三轮实际执行。文件 append/删除/静态 symlink 拒绝；prefs 全类型、集合副本、主线程监听、apply→commit 顺序与生命周期持久化、损坏拒绝；SQLite 真实 WAL、事务回滚、跨进程读写、损坏保留均通过。宿主同名文件/prefs/数据库诱饵保持原值。

首批 prefs 采用单进程缓存所有权，第二进程打开明确失败，不宣称跨进程 SharedPreferences 一致性。直接 Java/Native 路径、FD/mmap、Provider 文件能力、故障写盘及完整系统服务策略仍待实现；`fullP3Accepted=false`。同构建回归后续单独记录。

首轮同构建回归：`p0-regression-09` 57 / 57（`p0-98884e1606a74530`），`p1-regression-07` 179 / 179（`p1-c8796737f4f94452`）。

## P3a 共享写入队列快照

APK `bc6d53679cad476b535d72598a9cdf73bfeb0178328d2c668a777a3974f037e1`。每进程一个有序偏好写入线程；删除等待先前 apply，文件不存在时删除也成功。新增多文件仅一个 writer、删除顺序及已删除文件再次删除三项门槛，六轮执行。

| 验收 | 结果 | runId |
| --- | --- | --- |
| storage-02 | 190 / 190 | p3-b0113326aa2243cb |
| p2-storage-regression-01 | 193 / 193 | p2-96ef0ed06bfb4154 |
| p0-regression-10 | 57 / 57 | p0-4a20ca8fe8ec4a9a |

以上仍为限定存储功能快照，Native 文件策略尚未接入。各自目录保留完整源码/构建/设备摘要与断言；P1 同构建结果另外补录。

`p1-regression-08` 的采集器失败，不能计为验收通过：首次看见 final 文件时其内容仍为空，JSON 解析报错。随后只读保存的 `device-result-after-validator-failure.json` 显示同 APK 的 179 项实际执行通过，但该文件只用于诊断，不覆盖原 `device-observation.json` 的失败结论。根因是探针直接创建 final 文件后才序列化/写入内容。修复为统一 `ProbeReports`：同目录独占创建 pending 文件，完整写入、fsync、关闭，然后 rename 发布 final；冷任务 handoff 同样修正。采集器仍严格解析，不通过忽略空 JSON 或重试解析掩盖协议错误。

`p1-regression-09` 再次失败于报告权限：新发布器的 0600 拒绝 adb 采集。保留诊断与原始观察，显式终止已无进展的本任务验证器。仅自研报告改用 0660；验证器将非“文件尚未发布”的读取错误直接报告。Native C 路径策略此时仅编译检查通过，未加载或进入任何设备验收，不能算 Native 文件能力通过。

报告发布修正后，APK `64754cec4d6c183d78f585ad49a6b2c57c8fb3e1df5dc21a99350a96c5a4ef57` 的 `p1-regression-10` **179 / 179**（`p1-8e3b3f31c8084435`）。

同构建 `storage-03` **190 / 190**（`p3-dcd7643ae3a04bc3`），`p2-storage-regression-02` **193 / 193**（`p2-b45c1592749147f0`）。冷任务 handoff 和最终报告均经新的原子发布器采集。后续接入 Native 适配属于新的构建，以上不能作为 Native Hook 证据。

## Native 策略独立内核测试

`native-policy-01` **25 / 25**，runId `21d2c430ddfc41a4b5e1f25c51efd56b`。这是 shell UID 2000 运行的自研 C 程序，临时目录前缀 `/data/local/tmp/tripoder-path-`；不访问用户应用数据。验证目录 FD 锚定、别名、越界/符号链接拒绝、只读制品，以及真实 dup/rename/unlink/mmap 行为。`hookInstalled=false`、`guestExecutionVerified=false`；不能记为运行器内 Native Hook 已通过。构建参数、二进制摘要、源码摘要、adb 命令和各断言全部保留。

## Native 文件策略首个客体快照

`storage-native-01` **274 / 274**，runId `p3-478190c00bed4220`，APK `277c94c3d9a0a9779ac3f715cbebee1c4d5e1095f9d4dd31c74900b19780fd6d`。Native 库作为 Runtime 必需适配器，在 Application 前安装。guest JNI 自行构造逻辑私有路径；两种别名、真实 openat/目录 FD、只读制品拒绝、未知私有路径拒绝、rename/unlink 后 FD、mmap/close/msync 与 Java 同字节验证均通过。两个空间 × 首次/重启/升级六轮，原有 190 项存储断言继续通过。

这只是已实现入口集合的行为证据。跨进程文件能力、URI grant、完整 FD 跟踪、外部路径、系统服务和任意 Native 代码仍未验收，P3 总状态保持未完成。P0 将另行区分 libc 宿主路径拒绝与直接 syscall 的同 UID 边界；不会把 Hook 通过称为强隔离。

同构建 `p0-native-regression-01` **60 / 60**，runId `p0-fdde7f5ece284c35`。三次 A 启动均确认 libc 读取自研宿主 canary 失败、JNI 直接 syscall 仍可读取它。原 Application/Activity/资源/JNI 与 A→B→A、空间/代次边界继续通过。`strongIsolationVerified=false` 与 `sameUidNativeIsolation=false` 保持不变。

同构建 `p2-native-regression-01` **193 / 193**（`p2-a5744da8c0bd48fa`），`p1-native-regression-01` **179 / 179**（`p1-641f6226792e4201`）。这是首次将 Native 文件策略接入后的完整限定回归组；P2/P3 余项及 P4–P6 仍未验收。

## Provider 写入与继承入口边界

`provider-writes-01` **283 / 283**，runId `p2-d84a41572d9544dc`，APK `75ad123650ab6aa8522d726983bcf17e35fa426944873d758e86f0b4200da8be`。现代/legacy insert、update、delete 和 bulkInsert 使用同一 capability 与单次调用契约；有界 ContentValues 保留全部 SDK 值类型、复制 blob，真实 SQLite 内容与调用次数验证通过。独立客体包 `dev.tripoder.peer` 证明只写不要求读权限、只读不要求写权限、声明/路径权限拒绝发生在目标副作用之前。同 authority 宿主诱饵零命中。

15 个 Context URI grant/check/enforce 入口、4 个 Resolver 持久授权入口明确拒绝；Settings 观察者作为已有 UI 能力显式登记/注销，未知观察者拒绝。拒绝检查不代表 URI grant 或通用 observer 已实现。两个 Provider 空间逐个运行并释放进程，以容纳新增跨包夹具；此前并存空间的证据保持历史记录。

这个快照尚未验证 lazy Cursor 身份、提前关闭显式 client、批处理、写后异常、交付丢失、FD 和 URI grant；后续 Cursor 修改必须使用新构建验收。

`provider-cursor-01` 保留失败：测试错误地要求内部读取次数超过 2000，设备实际为 81；身份错位为 0、close 为 1、显式 client 提前关闭和延迟读取通过。第二轮加入实际 fillWindow 计数，`provider-cursor-02` 记录 4 次填充、85 次访问、0 次身份错位；但整轮仍失败，因为 ACK 断言把明确失败的初始化环握手也计入“成功响应未收到”。修正统计为仅要求执行契约 SUCCEEDED 的调用收到源端 ACK，不把失败握手改成成功。

`provider-batch-01` 在进入批处理夹具之前失败于 Service 回归：cached Binder 的 onServiceConnected 不保证异步 onRebind 已执行。原探针立即取快照，观察到 unbind=1/rebind=0。测试已改为有界等待真实 onUnbind 完成，再发 bind，并等待真实 onRebind；未增加重绑、重试 Service 或手动回调。该轮不算批处理通过。

`provider-batch-02` 的正常批处理、back-reference、路径权限和稀疏索引拒绝通过，但写后异常被传输层统一包装，跨 Binder 原因丢失，整轮失败。修复为保留 RuntimeException，记录目标 failureClass/failureMessage；没有重放写入或把异常包装成成功。

`provider-batch-03` **333 / 333**，runId `p2-ccdaf195f4de46d1`，APK `5ac182c7d25f0e5d8197c8d13f2a984adeff8a73a9d6fcff3ebb1c6adf70b0c9`。真实 lazy Cursor 跨窗口填充、caller 身份、client 先关闭、Cursor 单次关闭；源适配器响应 ACK；实际批处理/back-reference；逐项权限；稀疏超大索引拒绝；写后抛错、事务回滚与非事务部分写入均通过。每空间三项不确定业务结果保持 UNKNOWN、数据安装代次继续受保护；确认死进程且所有引用关闭后物理槽位可复用。未把业务结果未知等同于进程资源仍存活，也未自动重放。

此快照不包含随后加入的控制 FD、Bootstrap 和控制器 Activity result 校验；它们需要新的构建和验收。通用 Provider FD、完整取消/观察者/URI 授权仍未完成。

## 控制协议与返回结果边界

APK `0f5a0d9110b4b8a92a7dfc1501fef38f19a9f6c9c6ced9956aef230c290644d0`：`protocol-boundary-p1-01` **199 / 199**（`p1-8d3fe8f25b4244b8`），`protocol-boundary-p2-01` **334 / 334**（`p2-a36322d1dec5400f`）。控制协议只有已绑定控制器的 install 接受精确 PFD 字段；names/sha256 的元素逐个验型，畸形嵌套 FD 和 fd0 解析失败后的后续 fd1 均关闭，8 轮双负例后 authority 描述符数量不增长，合法 APK 导入继续通过。Bootstrap 拒绝额外字段；Settings call extras 使用有界 primitive copy。

控制器启动的客体 Activity 也必须经过 result 校验：FD/ClipData 结果在真实 finish 前被拒绝，Activity 仍存活，可按 Android setResult 语义设置新的合法结果并正常结束。原组件及 Service 冷恢复、Provider、Receiver 回归全部通过。以上不宣称所有宿主 UI manager、真实 Service Binder 或 Native SCM_RIGHTS 出口已封闭；通用 FD lease 仍不放开。

后续 Activity 组件租约及 alias 改造开始，属于新快照。

## Activity 组件身份与 alias

`activity-components-01` **340 / 340**（`p2-adcc503d23e04fae`），APK `e685cb7fbed9c2cd5e476174914690ada9658d69413cd703d2d2cedc1bdbcb2d`。组件索引在 authority 与 Worker 共用；保留 disabled 声明并在准入时拒绝，alias 实例化 target，客体看到 alias 的逻辑 ComponentName。自研 APK 同名 Activity/alias 确认选择后声明 alias，其继承的独立 process、真实 PID、target class 均正确。

schema 8 将物理 processStub 与 Activity stub 分开。每进程 16 个 standard 组件租约，同一进程不同组件各自占用物理 Android 组件；同一组件重复启动保持租约。源端在真实 onActivityResult 进入前释放对应 requestCode，回调正常结束后才 ACK，因此回调内部可再次使用相同 requestCode；未提前标记业务成功。既有多进程、recreate、冷任务恢复及 Service/Provider/Receiver 全部回归。

这批仍只支持 standard；singleTop/singleTask、affinity、隐式解析、PendingIntent 与在途结果冷恢复尚未通过。

同构建 `activity-components-p1-01` **199 / 199**（`p1-a17beb5ea0ca45a5`），覆盖 schema 8 安装/FD 控制与崩溃恢复。后续 singleTop/schema 9 是新的验收快照。

## singleTop 首轮交付证据

`activity-single-top-01` **356 / 356**（`p2-08ac1e7263464edd`），APK `9ac3a7e8d464008e2066b856b9c0a58c66c20f7a1988755ae7d7d57dacdec0ab`。设备具备精确 performNewIntent 双参适配签名；Android 真实顶部复用、非顶部新建、不同组件不误复用、回调内再次启动、单/双参客体回调均通过。每个实际 NewIntent 仅一条 ENTERED→RECEIVED 记录，候选 ticket 与实际 receiver ticket 分离，原实例身份不变。

真实 ComponentCaller 保留、逻辑 package/referrer 与实际共享 UID 均核对；旧 getIntent 未被运行器自动替换，单参 setIntent 清 caller、双参保存 caller、回调外 getCurrentCaller 拒绝符合 Android 行为。客体启动的 primitive payload 与准入摘要一致。原组件回归继续通过。

随后增加客体 equals/hashCode 覆写及回调写入后进程死亡验证，属于新构建；本轮不代表异常/ACK 丢失已验收，也不代表 singleTask、affinity、通用隐式 Intent 或 P2 全量完成。

`activity-single-top-02` **360 / 360**（`p2-9b6c9012fad0426a`），APK `e7f9261fa7bd6dacf458480b5d92f290eb959c8855598a3f396f9e219d5cf2be`。新增客体 equals/hashCode 合法覆写，实际不同 Activity 仍按对象身份独立记账；生命周期清理确定移除实例。故障夹具在真实 onNewIntent 内将一个字节 fsync 后杀死自身进程，Android 向控制器返回 RESULT_CANCELED。实际文件只有一次写入；原进程确认 DEAD，新 Intent 的执行契约及 delivery 均 UNKNOWN，没有重放；安装数据继续被未知交付保护。未创建的候选实例预约不再被误当作真实 task 实例。

这是“回调执行中写后死亡”的证据，还不能替代“回调已返回但 ACK 请求/回复丢失”、全部迟到消息、singleTask/affinity 与 PendingIntent 验收。同构建 P0/P1/P3 回归随后记录。

同构建回归：`activity-single-top-p0-01` **60 / 60**（`p0-1d68a04d2c514c7d`），`activity-single-top-p1-01` **199 / 199**（`p1-5ff30d739c4c4317`），`activity-single-top-p3-01` **274 / 274**（`p3-8019f3584eb642db`）。均绑定上述 `e7f9261…` APK；随后 schema 10 的生命周期租约回收属于新构建。

## Activity 生命周期回收迭代

`activity-leases-01` 在启动前因自研 B 测试 APK 摘要仍是上一构建而拒绝；同步测试 B 后 `activity-leases-02` 在 64 项已通过断言后返回 PROCESS_CAPACITY_EXHAUSTED，整轮失败。APK `1032afd314f5d99a359aa903bde4b03a7e9cb12b9d35d38a93519a49f7dfb0b4`。没有清库或删除未知记录。

后续 `activity-lease-diagnostic-01.json` 是诊断，非验收通过：列出三个旧 RuntimeSlot$A 的 UNKNOWN 历史引用（task 852/855/858，物理名称已从 manifest 移除）与上一轮 P3 两个 FINISHED 的组件租约（task 980）。schema 10 只采集最近任务，无法证明历史 task 已无可恢复引用，所以保留槽位。诊断 APK `7474e3a49e2574ded1994461774b5e5996f614d1bc38cb4025cca42e123a23d7`。

下一构建 schema 11 将增加永久退役名称的显式 Factory 拒绝及有证据的资源退役；未知业务结果和数据保护不变。对当前封闭的自有 UID 任务，联合 recent/running/recent 观察；新增真实 document task 夹具验证 hidden-recents 仍能由 running task 观察到，再明确 finishAndRemoveTask 验证消失。待验收，不因设计完成标记通过。

`activity-leases-03` 在安装阶段被 Android 拒绝：测试 document Activity 的 taskAffinity 含非法连字符，INSTALL_PARSE_FAILED_MANIFEST_MALFORMED。该失败未运行组件验收；随后修正为合法 affinity 名并重新完整构建。

`activity-leases-04` **364 / 364**（`p2-b028253bd5e64dca`），APK `873e8641e7adc3a8d17ca52e36fd3a0caf7e746a3116fcb0f410b8dada20ecd2`。真实 document task 991 被 maxRecents=1 移出最近列表：recent=[992,990]，running=[992,990,991]；两个根 Activity 实际 finishAndRemoveTask 后 recent/running 均只剩 990。因此不会仅因最近列表缺席释放仍活任务。

24 个不同子组件在同一 live process、同一任务运行，全部复用物理 `ActivityP0$S1`，但获得 24 个不同 lease UUID；一个组件真实 recreate，合计 25 次 create，24 个最终 finishing 销毁确认。结果完成不再屏蔽 postDestroy；旧实例/历史租约没有覆盖新占用。Activity create 成功确认已移至 onActivityPostCreated，preCreate 验证自有任务根。

三条旧 RuntimeSlot$A UNKNOWN 记录依 PM 无声明、Factory 明确拒绝和进程 DEAD 被写为 RETIRED，并保留 resourceProof；不删除历史，也未把未知业务结果改成功。上一轮 P3 的 FINISHED+DEAD 引用经联合观察回收。原单/双参 singleTop、写后死亡 UNKNOWN、alias、跨进程组件、Service 冷恢复、Provider、Receiver 和 Activity 冷恢复仍通过。根任务全部生命周期、外部 UID 任务、singleTask/affinity 和迟到/ACK 丢失尚未验收；P2 总状态仍未完成。

`activity-leases-p1-01` 在 159 项通过后整轮失败：项目迁移在 SQLite 事务内部调用 requireUnused，新增系统任务观察被 ACTIVITY_OBSERVATION_DURING_TRANSACTION 明确拒绝。已拆分事务外的资源观察/回收与事务内的持久引用重检；保留检查，未允许 Binder 查询进入数据库事务，也未跳过引用验证。修复后的新构建需要重跑相关回归。

事务边界修复构建 APK `2217c81d7dea9ecd50c9b7880b62e742169a31ece299a537f6559271ddb89ba5`：`activity-leases-p1-02` **199 / 199**（`p1-b5cbc962cf1847c1`）。项目迁移、安装代次/FD 协议及真实 authority 崩溃恢复全部通过。同构建 `activity-leases-p3-01` **274 / 274**（`p3-d465c16a377a439f`）、`activity-leases-p0-01` **60 / 60**（`p0-5ec9f054870b4dd4`）已通过。此前 364 项 P2 证据属于 `873e8641…`，不混写为当前 APK 的实测。

## Activity 来源与返回身份（schema 12）

`activity-source-01` 和 `activity-source-02` 为失败证据：新增 getCallingPackage 在另一个 getter hook 上再次做逻辑映射，导致已变换的 `dev.tripoder.p2.Chain$Main` 被误当作物理组件。第二轮具体进程 17304 的错误记录已保存；修复为单一 getCallingActivity 映射入口，没有放宽身份检查。

修复构建 APK `737402d42df7ffd1c082f26407609efc1374e47d56ee3eb064304ae6ceebb37a`：

| 证据目录 | runId | 实际断言 |
|---|---|---|
| `activity-source-03` | `p2-4ca5b568e8f34e77` | **400 / 400** |
| `activity-source-p0-01` | `p0-e36200ece174491c` | **60 / 60** |
| `activity-source-p3-01` | `p3-9057e7a0c3c84860` | **274 / 274** |
| `activity-source-p1-01` | `p1-6bb387701546465b` | **199 / 199** |

新增真实夹具：缺少 source ticket、随机 instance、不存在 ticket、真实 recreate 后旧 instance 均被拒绝；这些拒绝没有创建 launch。来源 alias `dev.tripoder.p2.SourceAlias` 经跨进程结果接收方可见，目标真实 recreate 后保留相同调用方；非结果启动两个 calling API 均为空。实际结果回调的 ComponentCaller 为逻辑目标包，UID 不伪造。四层调用链及三轮重启/空间场景逐级校验 controller/guest callingComponent。

样例持久来源：ticket `36dad0dc-0cc9-4f00-9554-7ad45823cf1f` 重建后 instance `67f9419c-ca3e-4e51-ac68-80c4d2d5e13d`；remote PID 20624，两条 launch 均绑定这个来源并最终 SUCCEEDED。没有把控制器普通 prepare 的 callerKind 错标为客体。

历史资源回收：原 UI 失败空间的 task 1000、两轮失败的 task 1010/1015，经既有 OWNED_TASK_ATTACHED + generation DEAD + 联合任务观察缺席写入带时间的 RETIRED/resourceProof。未知操作、交付和 UI 记录不改成功，数据不删除。P2 容量重新达到 8 个真实 supervised PID，cold Activity/Service 及 Provider/Receiver 原限定用例仍通过。

P2–P6 总体仍未完成；本批不涵盖 singleTask/singleInstance、affinity、reparenting、结果跨进程死亡恢复、通用 Intent/URI/PendingIntent 和商业目标应用。P1 同构建安装、项目迁移、authority 故障恢复也已通过。

## Task 模式实施中的失败记录与工作台决定

`activity-tasks-01` 在安装阶段因 6912 个 activity-alias 超过平台上限而失败；尚未进入组件验证。稀疏池改为只给声明有 alias 的目标分配专用组件族（每 profile 两族，每族两个 alias），构建器检查整个 manifest 的组件预算。AOSP [Element.java](https://android.googlesource.com/platform/frameworks/base/+/798f9062a7e4fbd59fa041d9d8a1306112a0351c/core/java/android/content/res/Element.java) 的 application 限额是 4000 alias、30000 Activity。

`activity-tasks-02` 安装成功，但历史票据 inventory 的 Binder 回复达到 1,570,684 字节，采集失败。原数据库和历史票据未删除；探针改为原子报告文件 + 长度 / SHA-256 收据，session 查询限定当前 run。下一轮须重新验证。

用户确认旧工作台可直接删除后，已卸载手机上的 `dev.tripoder.hooklab` 并确认安装列表为空；应用本地数据随卸载删除。公开 APK / 历史证据 / 仓库源码保留。新工作台不再需要旧签名覆盖或旧私有项目迁移；仍须与客体引擎使用不同 UID，且不得继续使用 legacy 运行入口。见 `old-workbench-removal.json`。

## Android 任务模式首批快照（schema 13）

APK `562e5522ecd06e0c7b171f8232719e1cfa3454f9a30d4f06c8c1b83b360bde9e`：`activity-tasks-04` **411/411**（`p2-8ecedf25f28044c9`），同构建 `activity-tasks-p0-01` **60/60**（`p0-28d7f50458274432`）。singleTask 从 C 经 alias 清除真实 B/C 并复用根实例，再经 target 再次 newIntent；singleInstance 根启动的子页面进入另一个实际 task，alias 返回仍复用原实例。全部交付经真实回调后 ACK，原始 getIntent 未自动替换。

`activity-tasks-03` 已通过 singleTask，但后续 alias 来源结果失败：Android getCallingActivity 返回 alias 物理入口，账本错误记录成 canonical 目标；修正为精确保存 source ticket.stub 后才通过第四轮。错误日志仅采集 Runtime UID。大 inventory 已通过文件 + 长度/摘要交付，历史记录没有删减。

上述快照尚不包含 schema 14 的 reparenting/NEW_TASK/RESET_TASK_IF_NEEDED 改造，也不代表完整 P2 或 P6。

## 任务重归属快照（schema 14）

APK `6ed6bdae32e78a9fc877c6b777d7cccb06bf0217a6c1ddb377d162e30e70a8ae`：`activity-reparent-01` **416/416**（`p2-6cc8b2d12dd5480e`）；同构建 P1 `activity-reparent-p1-01` **199/199**（`p1-d6b8e5f60c044139`）。实际移动实例 taskId 从 1055 变为 1054，两个 alias 各创建一次，第一实例未被错误销毁；结果来源保持 ReparentAliasTwo，账本亦更新到 task 1054。

审查完整 inventory 发现另一个缺口：NEW_TASK / RESET 只把已有 task 带到前台时，Android 可以不触发 create/newIntent，当前候选票据仍为 PREPARED。416 项证明移动行为，但未覆盖这个系统启动结果的终态收据。必须补 START_TASK_TO_FRONT 的独立状态和验收，不能把它记成客体回调 RECEIVED。

同构建 schema 14 回归补录：`activity-reparent-p3-01` **274/274**（`p3-96f46e18340d44f8`）；`activity-reparent-p0-01` **60/60**（`p0-67ff5e2073814dee`）。

## 系统启动回执与回调收件（schema 16）

`activity-start-receipt-01` 为容量失败：历史未确认票据仍占槽，探针错误假设总共 8 槽都空闲。`activity-start-receipt-02` 暴露更重要的实现错误：START_TASK_TO_FRONT（2）本身不能证明不会有 onNewIntent，singleInstance 被提前标记成功，随后真实回调被拒绝。错误进程日志保留在该目录。schema 16 针对 schema 15 的错误非 standard SYSTEM_CONFIRMED 记录撤回成功，保留旧结果及原因并转 UNKNOWN；没有重放业务。ticket `e1548148-f8cb-4fe3-8646-8da1a4be9448` 的未知资源引用仍保留。

`activity-start-receipt-03` 通过 singleInstance 后遇到真实 affinity 容量耗尽：已结束的夹具任务仍留在最近任务列表。探针增加针对本轮已完成、归属明确的任务调用真实 finishAndRemoveTask，并重新观察消失；没有清空运行时账本。

修复后 APK `263043408402ea735b1ac0ffbd83918889f26d629db14b76ce6d1b0ac26a04c0`：

| 目录 | runId | 结果 |
|---|---|---|
| `activity-start-receipt-04` | `p2-a6700e0430134ce0` | **429/429** |
| `activity-start-receipt-p0-01` | `p0-d8a20ee35b52465b` | **60/60** |

singleInstance 的 startResult=2 后仍等待真实 NEW_INTENT/RECEIVED；standard reparent 的 startResult=2 联合唯一 LIVE 根、真实 task/baseIntent/ticket 观察完成 SYSTEM_CONFIRMED/TASK_TO_FRONT，不冒充客体回调。容量收据为 4 已分配、2 可用、2 历史保留，共 8 槽。P0 改为通过生产 ProcessSupervisor 分配/停止，避免固定测试槽假设。P1/P3 尚未在该 APK 重跑；整体 P2–P6 仍未验收。

## 大制品安装模型迭代

`large-artifact-host-01`：微信失败于把 lib/arm64-v8a 下每个 .so 都当作 ELF；实际含压缩数据和元数据。所有字节仍须签名/哈希校验，只有 ELF 格式的内容才按 ELF header 检查，未引入解包执行或替代 loader。

`large-artifact-host-02`：两目标失败于 Manifest 浮点属性。`large-artifact-host-03`：保留类型和原始 bits 后，失败于重复组件声明。`large-artifact-host-04`：完整保留 Provider 的重复类名/不同 authority 后，失败于 enabled 的资源引用。以上全部为失败证据，不代表真实 APK 可安装或运行。

主机自研签名夹具 `artifact-host-typed-02.json` **45/45**：混合 ABI 仅选择 ARM64，合法美元符号 Native 文件名、非 ELF 安装资源、Manifest 浮点/颜色值、原始重复声明、签名和内容篡改拒绝、ZIP 边界、发布/复验等。范围只到制品模型；资源配置解释和重复 Provider 的运行时解析尚未完成。

`large-artifact-host-05` 两个原始商业目标制品通过，源文件前后摘要一致：微信 APK `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`，18 DEX/190,053,436 字节、209 个 ARM64 资源文件/370,905,764 字节；王者 APK `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`，6 DEX/40,277,916 字节、134 个 ARM64 文件/566,324,600 字节。包含原始 APK v2/v3 签名验证、所有 ZIP 内容摘要、提取及完整独立复验。runtimeExecutionVerified=false；尚未在 Android 客体中启动。自研主机夹具 `artifact-host-flags.json` **47/47**，新增资源布尔引用保留及描述复验。

`large-artifact-p1-01` APK `83c94ad8b4feaef159465b3f932103233e3d9a594ec0eb0e9ee2e4142cc2bb1b` 为失败证据：此前安装/升级/卸载故障验证通过，但 Worker LOAD 中匿名 memfd 的 fchmod 被系统拒绝 EACCES。文件 mode 不是匿名映射的不可变依据；后续移除该 syscall，仍要求内核 WRITE/GROW/SHRINK/SEAL 封印回读确认及只读映射，待下一构建真机验证。

`large-artifact-p1-02` **204/204**（`p1-e54751c4e17f41c0`），APK `82534ffa3e71028829708c557dc68c16bbf987bffa57083835ffdb4a6fd11c0d`。真实 Worker PID 14038 完成新 DEX 路径的 LOAD/Application 后随 authority 死亡退出；设备资源配置将原始布尔引用解析为 Allowed.enabled=true、Allowed.exported=false、Disabled.enabled=false。缓存命中不增加 fullVerifications；真实改写 Native 文件最后一字节、fsync、恢复原 mtime/大小后，ctime 变化导致完整复验并拒绝 ARTIFACT_FILE_CORRUPT。历史故障恢复、原子升级、卸载、项目迁移与未知效果不重放仍通过。此 APK 的 P1 通过不代表两款商业目标已运行。

`large-artifact-device-01` 为探针失败，APK `8834751611a7170110f33f8b2f90a040a352f243c886b597c7a169bec1e00863`：手机公开微信 APK 摘要已匹配，但探针把含点的包名拼入 execution operationId，被格式校验拒绝。目标制品尚未写入。修正为独立短标签 ID 后重新构建；没有放宽执行契约。

## 原始目标制品真机安装与非初始化类加载

`large-artifact-device-02` **10/10**（`artifact-32447bae98714c36`），APK `1aba0ed9b98a50a2cf14a77b97bb2ad8260201479909700d2b26abe06e061786`。公开原始 APK 经控制端只读 FD 进入生产 ApkStore，签名、ZIP 内容、提取、封存、发布与独立复验后提交。设备 PackageInfo 只解析安装副本；原始 resource enabled 不再被猜测为 true。原始 APK 前后 SHA 一致。

| 目标 | 观察 |
|---|---|
| 微信 8.0.79 | 安装 14,598 ms；2296 条有效声明（2288 启用/8 禁用）；独立 PID 18872/UID 10398；18 DEX/190,053,436 字节；复验 5,412 ms，加载 1,887 ms；Application 类由该 InMemoryDexClassLoader 定义、initialize=false；采样 Java heap 19,971,712 字节，总 PSS 305,856 KiB。 |
| 王者荣耀 12.1.1.1 | 安装 22,004 ms；185 条有效声明（177 启用/8 禁用）；独立 PID 19611/UID 10398；6 DEX/40,277,916 字节；复验 8,884 ms，加载 438 ms；Application 类 initialize=false；采样 Java heap 9,620,976 字节，总 PSS 123,937 KiB。 |

观测到的 tripoder-dex 映射均无写权限（微信 12 段、王者 6 段；ART 可以复制/释放原映射，因此不把映射段数当 DEX 数）。所有 DEX 在进入 ART 前均经大小/SHA 校验、memfd 内核封印回读确认和只读映射。两次探针进程均真实终止后再处理下一目标。heap/PSS 是单次采样，不是峰值或产品内存验收。

边界：Application 构造/初始化、Provider 初始化、原生游戏加载、图形、联网、账号登录及微信授权闭环均未执行。两个制品分别导入独立测试空间，不能用于宣称同空间跨应用链路通过。证据明确 applicationInitialized=false / businessScenarioVerified=false / strongIsolationVerified=false。P2–P6 仍未完成。

同 APK `1aba0ed9…` 的 `large-artifact-p2-01` **429/429**（`p2-9aafa6bf71284a57`）：当前有效 Manifest 解析与 memfd DEX 路径没有破坏已覆盖的 Activity、任务复用/重归属、Service、Provider、Receiver 与冷恢复用例。仍不包含重复 Provider 类的实际运行初始化和 P2 未完成的通用 URI/PendingIntent、结果冷恢复。

## 重复 Provider 类的独立系统基线

已新增完全自研 `provider-identity` APK，分别声明同类名、不同 authority/metadata 的 Provider，并记录真实实例/初始化次数及 call 的实际返回或异常。`provider-identity-baseline-01` 首次安装被手机拒绝：INSTALL_FAILED_USER_RESTRICTED，尚未得到 Android 原生行为结果。已请求用户配合手机安装；不猜测行为、不以预期值替代基线。该失败不影响已有两个目标的制品验证或 429 项组件回归。

`large-artifact-p3-01` **274/274**（`p3-71b911aec020485a`），APK `cc4346bd0ba0787fbdb2956ea356b3422bc1d1b84cde8aacc37c0e15410f3c84`。该构建只增加独立 Provider identity 夹具及资产，production runtime 源码与上一 `1aba0ed9…` 相同；既有 Java/Native 文件、prefs、SQLite/WAL、跨空间重启升级和宿主诱饵断言通过。重复 Provider 的新基线尚未运行，P3 的完整 FD/URI/系统服务能力仍未验收。

## P4 Native 生命周期与 ART 稳定性（本轮持续验证）

- `p4-native-04` 混合 Java/Native 实际行为已达到预期，但卸载后的准备状态未失效。
- `p4-native-05`（APK `6ad44f6a4f3c9a28192b402f223fbbf05948f28bc2da14c658398fe5f500effc`）与 `06`（`993d1860db23030820426af1b6e16c29e77b93d55fc8ca18722953d31e4f1d4e`）都失败于同一断言。独立 dl_iterate_phdr 证明映射已经消失，Frida observer 仍延迟移除；保留真实失败状态。
- `p4-native-07`（`d2044aee2461332a6be3e48cde91018a86a41da6f78f3a55c20dbf58485649a6`）使用独立 link_map 生命周期和制品动态符号表。卸载失效、重载新代次、旧 token 拒绝及 Native 类型错误的持久原函数计数 0 已通过；Native 活跃卸载的正确 PID 也记录了明确终止日志。整体失败于 Android 冷恢复旧 Intent/测试回调关联，不能记为通过。
- schema 19 `p4-native-08`（`a160b9112c92b94344dbe5edb6f95a66f6ee7addecc40b056b5a4aed14aa5d62`，run `p4-1956abab2e2e4983`）**98/98**。启用 Hook 的旧会话不能自动恢复成无 Hook 运行；旧 Activity 入口明确取消，未调用客体。宿主观察匹配两个终止 PID 与原因，crash buffer 为空。
- **同 APK 随后的 `p4-native-stability-01/02` 均失败**：激活前完成 19 项，ART `Instrumentation::Deoptimize → InstrumentAllThreadStacks → StackVisitor` 中发生 SIGSEGV。这推翻了该构建的稳定性门槛，不能用前一次 98/98 掩盖。去优化前后顺序及 once-per-method 修正不足以解决此问题。
- 新调度把 Java 控制入口改为单次提交与请求 ID 完成通知，在代理线程执行命令，Java Binder 线程只等待对应回执。同 APK `3224516296f0b8d0c2a94dd5359c92585664bdbeed99c4aa24ce3658ac0cab03` 的 `p4-agent-dispatch-01/02/03` 各 **98/98**，run 分别为 `p4-17c9483393314176`、`p4-c5f6e926cf814b0b`、`p4-c07a2652a6c2490c`。这是三次有界回归，不能替代长时间压力验收。
- 后续增加项目 Hook 所有权、OBSERVED/discard 状态及旧入口防护的构建 `5f618178e434c2be0b89e5e20e957bf5921435f1721c0b248f6ae98af48645b5`，`p4-owned-state-01`（`p4-3c7f88a368d94385`）**98/98**。该构建的完整 P1/P2/P3 回归尚待执行。

Native 正向结果由客体真实 C 函数返回：add=30/原函数一次，uint64 `18446744073709551614 → 18446744073709551613`/原函数零次，double=5.5/原函数一次；混合 Java 返回 70/原函数零次。停用后全部恢复正常原始行为。Native 错误时没有伪造返回值或执行原函数。完整 P4 的故障注入/长压力与 P5 接入、P6 真实应用仍未完成。

## 项目 Hook 控制接入

`workbench-hooks-control-01` **62/62**，工作台 UID 10401 / 引擎 UID 10400，构建逐文件摘要见该目录 `build-receipt.json`。控制测试 APK SHA `af1fc5acc3cd2236e40a1a59d6f6b7c4e0baf9dc9d5825c4a6b1a79e2179634c`。覆盖生产 API 的进程列举、实际 PID/安装代次核对、观察/准备/激活/停用、项目所有权、持有 Hook 时重绑拒绝、控制连接更换后的旧准备令牌拒绝及显式放弃。这里只检查部署生命周期，未声称该控制测试已观察实际业务调用效果。

原生工作台已接入项目部署面板；真实编辑/保存/部署/计数行为改变/停用恢复另做 UI 门槛，结果单独记录。完整 P5 与微信、王者荣耀 P6 尚未验收。

`workbench-hook-ui-01` 实际部署、两次命中和停用成功，但验收脚本误用不存在的 launcher 标签，最后重新启动检查失败，截图还捕捉到切换动画；保留为失败。

`workbench-hook-ui-02`（run `control-8714ae7913964909`）**11/11**。实际原生编辑/保存、观察、检查、确认激活、客体 `Hook 0 → Hook 1`、停用与再启动恢复 `1` 均通过。PID 31514，工作台/客体 UID 10401/10400；counter 原始 APK SHA `b7fdc665b6fa05b9ede223d83074c0cec9187477c7be1d345c047f8ba3385a54`。真实 worker 返回 hits=2/errors=0；停用后 IDLE。四张原始 PNG 与完整构建摘要在该目录，已视觉核对。此为自研应用行为验收，不能替代微信/王者验收。

`hook-owned-state-p2-01`（run `p2-bd6b2567bafc4a40`）**439/439**，Runtime APK `5f618178e434c2be0b89e5e20e957bf5921435f1721c0b248f6ae98af48645b5`。schema 19 Hook 状态与禁止无 Hook 自动恢复策略加入后，原有组件回归通过；并发 GC 实测 762 次，25 次创建/1 次重建/24 次销毁，无 Native fatal signal。覆盖范围仍受原组件契约限制。

## 生产引擎中的两个原始应用：首次启动失败证据

`wechat-first-launch-01` / `honor-first-launch-01` 均为 **未通过**。原始 APK 的摘要、只读 FD 导入、生产安装提交与真实 launcher 声明校验通过；两者导入同一空间 `p6-20261014`，分别绑定真实工作台项目。首次启动均在 `RuntimeProviders` 构造时明确拒绝 `MULTIPROCESS_PROVIDER_UNSUPPORTED`，尚未进入客体 Application 初始化或登录。

微信项目 `7703bdbe-88a3-4ad9-9f17-71aecb68d6ff`，安装代次 `a776a2fe-6b4d-42bd-9155-f25ed20b02e8`；王者项目 `be87df1a-ea8c-45c0-8834-507eaa7d87cb`，安装代次 `5e3947bf-973d-4126-8fea-8f4a5e3a026e`。两个启动操作均保留 UNKNOWN / LAUNCH_PREPARATION_UNCONFIRMED，未改写为成功，未调用宿主同包应用，未操作账号。

`provider-multiprocess-baseline-01` **4/4 原生对照**：同一自研正常安装 APK 在主进程 PID 13886 与服务进程 PID 15646 中，首次访问前创建数均为 0；首次访问在各调用线程中创建自己的 Provider，后续重复访问复用本进程实例。实例 ID 不同，创建数均为 1。这是 Android 原生基线，不能当作 Tripoder 实现已通过。

`provider-multiprocess-runtime-01` **446/446**（run `p2-cb6132dcda3c4d47`，Runtime APK `78390433306127470653777244be554fbee8b8a44450e1cd403cc6de5395afd3`）。新增 7 项证明 Runtime 中主进程 PID 23074 与服务进程 PID 23213 的惰性创建、各自唯一实例、调用者包身份、重复获取、既有服务进程复用及全部 Provider 回执/引用关闭，与原生对照一致。既有组件门槛同时回归通过。尚未覆盖该新能力的并发首次获取、初始化异常和所有跨包变体，不扩大为完整 Provider 验收。

`wechat-first-launch-02`（run `control-7d8b7fe1fdde4473`）与 `honor-first-launch-02`（`control-b34c380c1edb43be`）仍 **未通过**，使用同一安装代次和项目，没有清除数据或重建安装以绕过错误。微信已经进入 `TinkerApplication → MMApplicationLike.onBaseContextAttached`，失败于 `GuestPackageManager.getApplicationInfo` 不支持 flags；王者进入 Provider 初始化，失败于 `FileProvider.parsePathStrategy → PackageItemInfo.loadXmlMetaData → getXml` 尚未实现。两个启动回执仍为 UNKNOWN / LAUNCH_PREPARATION_UNCONFIRMED。没有到达登录，没有执行账号操作。

`package-resources-baseline-01` 是 **对照测试失败**：原生系统前四项元数据断言通过，第五项错误地要求每次 PackageManager 查询返回独立可修改副本；这台 Android 的缓存对象不满足该假设。修改夹具，原生对照只验证 flags、Manifest 和 XML 语义；保护进程内权威记录的防御性副本要求单独作为 Tripoder 约束验证，不宣称它来自该设备的原生行为。保留原失败和 APK 摘要。

`package-resources-baseline-02`（`resources-8cec7b38df764e54`）**10/10 原生对照**。`package-resources-runtime-01`（`p2-de707ddb010a48e2`）**464/464**，Runtime APK `ca16c39a4ec5fd3511eb1c823aedd45919ac46fb01e0c938556bfca30a152e64`：原有 446 项回归、新增 16 项包/资源行为与 2 项实际进程及完成检查均通过；实际客体 PID 6874 / UID 10398，无 Native fatal signal。应用与 Provider 的 XML 由安装包读取，调用者路径不能选源，跨包/错身份/未知高位 flags 明确拒绝，修改返回元数据不影响权威记录。该结果不包含两款商业目标的启动成功。

`wechat-first-launch-03`（`control-5c17395371244c26`）与 `honor-first-launch-03`（`control-72998089fd8241c7`）仍 **未通过**：微信不再报 ApplicationInfo flags，王者不再报 XML 读取。两者都进一步到达 `SYSTEM_SERVICE_UNSUPPORTED:activity`，王者调用来自 `RoomDatabase.JournalMode → WorkManagerInitializer`。微信另有 Cronet `getServiceInfo`、系统广播/注册策略、电话服务未实现的 Java 异常，以及框架 `handleReceiver` 把 GuestContext 转为 ContextImpl 的类型冲突。后者只确认现象，尚未确认哪段初始化改变了框架 Application 关联，不能将推测写成已定位根因。两者均保持原安装与项目、UNKNOWN 启动回执，尚未进入登录和业务验证。

控制验收器现将引擎 UID 内的 Java fatal exception 和 Native fatal signal 都计为失败，并延后到日志核验后才打印通过。`java-crash-gate-host.json` 用上述真实失败/对照日志验证分类；只代表主机日志门槛验证，没有对旧设备报告做改写，也不是新的真机通过结果。


## 2026-10-15 外部 Agent 与生产任务回收

- `external-agent-03`：独立 stdio MCP 18 项、真实手机 UI 10 项通过；自研计数器 Hook 0→Hook 1→停用后原始 2。同一次运行中的两个 UID 分离。手机审批由测试 Instrumentation 执行。
- `external-agent-disconnect-01`：16 项协议、7 项设备检查通过；无客户端协助的 45 秒联系超时撤销、准确进程终止、撤销后只读对账与修改拒绝。
- `external-agent-04`：容量耗尽失败，保留原证据。
- `agent-capacity-inspection-01`：allocated=0、available=0、retained=8。首版逐槽 Bundle 未正确序列化，不能据此确定所有保留原因。
- `agent-capacity-reclaim-01`：Control Test 安装被手机拒绝，没有执行回收。
- `agent-capacity-reclaim-02`：逐槽证实七个 Activity pin、一个 Broadcast pin；SQLite null 参数绑定失败，尚未移除任务。
- `agent-capacity-reclaim-03`：九个经过入口身份与 ticket 校验的空任务移除，available=7、retained=1（Broadcast）；两项设备检查加 `reclamation-verdict.json` 的两项结果核验。没有清空项目数据或改写业务 UNKNOWN。共享/未附着/故障窗口和超过八轮连续关闭尚未验收。
- `external-agent-05`：回收修复后的独立 MCP 18 项、手机 10 项再次通过。仍不是实际 Sentinel 调用证据。
- `sentinel-agent-01`：真实 Sentinel 已调用但首次连接前授权被 CONTACT_EXPIRED 撤销，闭环失败。保留 failure-analysis.json 与审计记录。
- `sentinel-agent-02`：**真实 Sentinel AI 助手闭环通过**，8 次工具调用与手机 12 项检查。PID 29289，2 次 Hook 命中、0 错误，停用后同 Activity 原始输出恢复为 2，入口已撤销。`sentinel-ai-verdict.json` 核对同一 AI execution 的 assistant/tool 记录、模型请求用量与逐字节相同的 MCP 回执。device-observation 中 modelToolUseVerified=false 是 AI 最终记录落库前的独立设备报告；后续 AI 取证结论以该 verdict 为准。
- 首次连接等待修复：8 分钟首次连接窗口、连接后 45 秒断联、10 分钟绝对授权；真机在首次连接前等待 55 秒仍 OPEN。纯 JVM 期限检查 7 项、AI 证据校验器 5 项通过。手机源码审批由自研 Instrumentation 执行，不是人工审批验收。

上述结果不改变 P2–P6 尚未完成、微信和王者尚未到登录的结论。设计及边界见 [空任务回收](../../runtime-task-reclamation.md)、[系统服务切片](../../runtime-system-services.md) 和 [外部控制](../../external-agent-control-design.md)。


### 2026-10-15 MCP 自动回归与后续测试方式

用户明确后续 MCP 测试由本任务直接接管，不再要求在 Sentinel 中测试。已完成的真实 Sentinel AI 证据保留。

`external-agent-disconnect-02` 在入口启动前超时，旧代码未保存具体启动错误，不能据此确定唯一根因。发现并修复了 `SO_REUSEADDR=false` 导致已关闭 TCP 连接处于 TIME_WAIT 时不能快速重绑定的风险；服务销毁会关闭排队/在途客户端，启动异常保留并记录。

`external-agent-disconnect-03`（产品构建 16）：16 项 MCP 检查、9 项手机检查通过。先建立真实 TCP 连接并关闭入口，再重新开启同一固定 loopback 端口；随后完成 Hook 命中、45 秒断联撤销、准确进程终止、撤销后只读对账和拒绝修改。此结果没有回写或掩盖 02 的失败。

### 系统查询与关闭回执审查

- `system-services-android-baseline-01`：自研原始 APK 在 Android 上通过 21 项内存/进程及自身 Service 元数据检查。
- `system-services-p2-01`：483 项业务断言通过，但本轮自有 UID 存在两次 `ACTIVITY_DESTROY_ACK_FAILED / PROCESS_CLOSING` Java 致命异常。原采集器漏检 Java fatal；`acceptance-review.json` 明确撤销总体通过结论，原始文件保留。
- `completion-fence-p2-01`：新增定向屏障证实普通关闭回执可完成；永久关闭被重复的通用生命周期通知打断，失败。
- `completion-fence-p2-02`：普通及永久关闭各两项定向检查全部通过；完整回归随后在 Service 重复通知处失败。共完成 180 项检查，不能作为整轮通过。生命周期的权威回执已统一到精确 ticket/instance/lease，修复后继续重跑。
- `completion-fence-p2-03`：**485 项通过**，run `p2-a45b91608c834669`，Runtime APK `3e728e4f9af18963e8686f7f6fe47923f66377bec90a6d70088b194073450045`。包括普通/永久关闭、新工作拒绝及精确实例销毁四项、系统内存/真实进程范围/Service 元数据和既有组件回归；自有 UID 的 Java/Native fatal 均未观察到。检查总数随当轮可用进程槽数量变化，不表示删减固定业务门槛。

runtime 采集器现将本轮自有 UID 的 Java 致命异常作为失败条件，与源码/APK 摘要、业务断言及 Native 崩溃检测共同判定。关闭回执边界见 [执行契约](../../execution-contract.md)。

`system-services-agent-01`：新系统查询/关闭回执内核上的 MCP 自动闭环通过，18 项协议检查、12 项设备检查，run `control-6d7cbf64fcb44c30`，客体 PID 7839。包括固定端口重开、精确源码审批、真实两次计数命中、停用后原始输出恢复、草稿不覆盖编辑器、UID 分离、撤销终止与入口停止；没有要求 Sentinel 操作。

固定目标第四次启动：`wechat-first-launch-04` 与 `honor-first-launch-04` 均未通过，仍未到登录。微信当前初始化阻塞为 `getHistoricalProcessStartReasons(int)`；后台另有 ServiceInfo 查询 flags、系统广播、电话服务和框架 Context 类型异常。王者已越过 ActivityManager 阻塞，当前停在 `SYSTEM_SERVICE_UNSUPPORTED:jobscheduler`。这些失败保留完整摘要和日志，不算 P6 通过。

### 死亡广播的资源退休

`receiver-retirement-01` 在测试延迟标记读取处失败，已有冷启动/并发门槛 40 项通过不代表新资源门槛通过。标记误放于引擎私有目录，被 Native 文件策略拒绝；修复仅移动测试标记至该客体空间。

`receiver-retirement-02`：54 项定向门槛通过，run `receiver-2f27cde6ddb24de4`，Runtime APK `fa5e3989abc7f6964e1bc0c45ef7e21cb6db4879553cda81b7a1f8932e173076`。同槽新客体 PID 18655 未收到旧 Receiver 回调；真实 source 最终回调确认物理结束而业务保持 UNKNOWN。authority 重启后的两个冷拒绝分别在 PID 18771/18823，`receiver-ingress.txt` 与自研同进程检查器均证明没有客体 Runtime。未知/错槽/篡改输入拒绝；完成过的旧投递也不重复执行。Java/Native fatal 均未观察到。
## 2026-10-15 广播退休完整回归与产品验证

- `receiver-retirement-p2-01`：现有 P2 组件套件 **501 项通过**，run `p2-d68098fda63e4b1c`，Runtime APK `fa5e3989abc7f6964e1bc0c45ef7e21cb6db4879553cda81b7a1f8932e173076`；Java/Native fatal 均为 false。
- `receiver-retirement-resources-01`：产品 schema 21，8 个物理槽 available、0 retained，历史广播资源不再永久占槽。保留空 Gateway task 的观测记录，没有把任务列表为空当成必要条件。
- `receiver-retirement-agent-01`：自主 MCP 18 项协议 / 12 项设备检查通过；引擎 APK `5eb62d475e61599136736542c5eba7582530d98c801cae72bccde97ba7486bf3`。此轮没有使用 Sentinel，也不重新声称 Sentinel 客户端验证。

## 2026-10-15 Application 所有权与元数据

- `wechat-ownership-01` / `wechat-ownership-02`：失败诊断，证实 attach 阶段客体通过 Context 解包修改了物理 LoadedApk.mApplication 和 ActivityThread.mInitialApplication；准确 ServiceInfo flags 为 `0xc0280`。不得标成微信启动通过。
- `logical-application-receiver-01`：失败。仅拆分 LoadedApk 时，物理初始 Application 仍被改写，Autofill 内部 DeviceConfig 查询误入客体 Settings 策略；未扩大 Settings 权限。
- `logical-application-receiver-02`：补齐逻辑初始 Application 视图后 **54 项通过**，run `receiver-0cd76780bd83448a`。
- `logical-application-p2-01`：同构建完整现有组件套件 **509 项通过**，run `p2-75bfe8dd045e4b42`。Runtime APK `bb58046d0d36b47c126a8858f3fc63f6a19ecc3853eee0b71fc28275da0673c4`。
- `direct-boot-android-baseline-01`：原生自研对照 **29 项通过**，run `resources-7257df0c7e0545fb`。与 Runtime 使用同一个 Provider Identity fixture，涵盖 aware/unaware/disabled 组合、元数据和默认声明进程。

## 微信第五次启动

`wechat-first-launch-05` 仍失败，未到登录。物理 Application / LoadedApk 保持 RuntimeApplication；一个远程微信进程实际完成 attach/onCreate，逻辑 LoadedApk 与物理对象不同。原 ClassCastException 和 `SERVICE_INFO_FLAGS_UNSUPPORTED` 未再观察到。主进程仍因 `getHistoricalProcessStartReasons(int)` 未实现而退出；后台缺失项包括 connectivity、phone 及系统广播策略。不能把这次定位性复测标为微信通过。

## 启动历史及回归

schema 22 / Runtime APK `3c95a866740c60947f69d6be33f765c5238d0ecae7aeb719daad353ff501522e`：`start-history-p2-01` **521 项通过**（`p2-e04f40c2f13b489c`），`start-history-p3-01` **274 项通过**（`p3-539a489a73eb4b79`），`start-history-p4-01` **98 项通过**（`p4-eaa8e52861064459`）。启动历史覆盖实际 onCreate 可见性、其他空间排除、真实失败 ERROR、返回值不可变及 authority 重启时间保持；P3/P4 为现有存储和 Hook 套件回归，不扩大为完整阶段验收。

`wechat-first-launch-06` 仍失败，主进程已越过历史查询，下一处为 `PREFERENCES_MODE_UNSUPPORTED`。后台仍见 connectivity、phone、MEDIA_SHARED 系统广播及带 `com.tencent.mm.permission.MM_MESSAGE` 的混合系统/自身动作 Receiver 策略缺口。静态 Receiver 此次表现为相同的实际 Application 初始化失败，未再见此前物理 Context 类型冲突。尚未到登录。

## 偏好多进程

`preferences-multiprocess-01`：**280 项通过**，run `p3-88f5850fa5894b75`，Runtime APK `c87dfefa241efc49d3312c0e24bbd785ae2e2280680ee25fe8d80553fb09e142`。六轮两个真实 PID 的写入时间均重叠，50+50 个不同键及基准值全部保留；显式多进程重新获取刷新原缓存对象，原 SQLite/Native/空间/重启/升级门槛仍通过。

`wechat-first-launch-07` 仍失败，但已越过偏好错误。主线程下一处是 `SCREEN_OFF` 系统 Receiver 注册（flags=0）；后台仍缺 connectivity、phone、混合系统/自身动作及 MEDIA_SHARED 支持。未到登录。

网络首轮编译因 SDK 未公开 LinkProperties 拷贝构造器失败，改为 Parcelable 副本。`network-android-baseline-01` 在制品摘要门槛以 FIXTURE_CHANGED 拒绝，未执行设备测试；不能算基线通过。后续验收使用新的完整构建。

`network-android-baseline-02` 在网络检查前因启动历史尚未观察到自身而失败。原始设备报告保留，不能断言网络 API 失败。

`network-runtime-p2-01`：**531 项通过**，run `p2-3400b1b26c1845c6`，Runtime APK `ee18ce3912816b4f9ace65f7c9bb1617cfec7a0708aadddcff47b261424a1a91`。新增 10 项真实网络查询/默认网络回调/取消和能力拒绝门槛通过，既有组件套件仍通过；自研 Application 在没有 ACCESS_NETWORK_STATE 声明时查询被拒绝。该结果不是互联网请求或目标应用通过。

`network-agent-01`：同一网络内核产品构建的自主 MCP 闭环 **18 项协议 / 12 项设备检查通过**，run `control-3261e01843af4283`。完成真实界面两次命中、停用恢复、准确源码审批、撤销和关闭，不依赖 Sentinel 操作。

`network-android-baseline-03` 仍失败，但新增观测证实原生历史多数条目 PID=0、没有当前 PID；不能归因于首帧时序。原生对照改为核验平台实际返回的自身历史范围，Runtime 的当前启动可见性门槛保留不变。

`network-android-baseline-04`：**34 项原生检查通过**，run `resources-402103fadf934576`。PID 24319，查询与实际 callback 的 network handle 同为 553161510925，ConnectivityThread 交付，设备观测到 3 个网络。此为独立原生基线，与 Runtime 531 项及 MCP 18/12 分别保存。

## 受保护系统广播

`system-broadcast-android-baseline-01`：39 项原生检查通过，run `resources-6c1dd6887d7b48d4`；自研包真实 TIME_TICK、自己的签名 permission、私有动作及 goAsync/finish 通过。

`system-broadcast-runtime-p2-01`：539 项通过，run `p2-5b1d251597cd40d6`，Runtime APK `171e3dfb33cb863631603884400fb7e7af940cbf3dcd6cb3a8a731937f2205b1`；新增系统广播门槛与原组件套件通过。只对 SCREEN_OFF 登记/取消，未切换锁屏，不能声称实际屏幕事件已验收。MEDIA_SHARED、URI 和未知系统广播仍明确拒绝。


## 2026-10-15 电话/媒体状态与并发登记扩展（在验收）

微信第八次原始目标启动 `wechat-first-launch-08` 仍失败，后台实际缺口为 `phone` 与 `MEDIA_SHARED`，未到登录。`device-services-build-01` 编译失败于 SDK 未公开的 MEDIA_UNSHARED 常量；已移除该未承诺动作，失败不计通过。下一构建 Runtime APK `198b46d87b8ec9b018e46c9b11a19cda75e1e64f70e163f5b98726fdc74785f6`。

`device-services-android-baseline-01` 原生 43 项通过（`resources-2cf510a567eb464b`），包括真实电话硬件/卡槽状态、媒体 filter 登记/取消和真实 TIME_TICK。媒体实际挂载/卸载事件未触发，不声称已验证。Runtime 全组件、并发锁顺序及产品目标复测另记结果。

`device-services-runtime-p2-01` 同构建通过 **547 项**（`p2-83e436a1c64c4931`），Java/Native fatal 均为 false。覆盖真实电话能力、两卡槽状态、拒绝设备标识和任意订阅、媒体登记/取消、并发 Receiver 登记及回调（40 次注册/取消，两个线程区间重叠，采样收到 26 次回调）。运行期间 02:24:17–02:27:21 CST。没有真实媒体插拔或持续压力证据。

同一 `198b46d8…` Runtime 构建随后 P3 `device-services-runtime-p3-01` **280 项**（`p3-82f0917f97db44a3`）与 P4 `device-services-runtime-p4-01` **98 项**（`p4-50a4515ced064a06`）分别通过。微信第九次仍失败：`MEDIA_RECEIVER_FILTER_UNSUPPORTED` 和 `registerTelephonyCallback`，已不再停在 phone 服务获取；未进入登录。下一步修复原生 filter 组合语义，并实现有限的真实信号强度订阅，不返回假登记成功。

`device-services-agent-01` 自主 MCP（`control-070bae9777d84769`）再次通过 18 项协议 / 12 项设备检查，真实 2 次命中、原 UI 行为恢复、撤销确认完成。未使用 Sentinel，也不声称新一次 Sentinel 客户端验收。

信号/filter 修正构建 Runtime APK `02f8f54b1eba25be00687aaea57fc7363a286ab3c8fb3ae7b8c3dd162bf961a1`；`signal-filter-android-baseline-01` 原生 **46 项**通过（`resources-17a8b93113324e48`），包含实际 SignalStrengthsListener 回调、指定 executor、媒体混合动作无 scheme 登记及 IntentFilter 的真实 URI 匹配规则。对应 Runtime 回归在执行，不能据原生通过推定运行器通过。

`signal-filter-runtime-p2-01` Runtime **556 项**通过（`p2-a2b005b9d9ca4691`，APK `02f8f54b1eba25be00687aaea57fc7363a286ab3c8fb3ae7b8c3dd162bf961a1`），真实信号回调、指定 executor、重复登记/取消与未授权接口拒绝、取消排队回调、媒体组合 filter 及完整组件回归通过。无 Java/Native fatal。微信第十次目标复测另记录，不能据此宣称目标应用通过。

微信第十次 `wechat-first-launch-10` 仍失败，错误变为 Application 的 10 秒 Future 超时。主进程仅记录 before-attach，另一个微信声明进程完成 attach/onCreate；本轮 crash 日志未出现此前 phone/media 异常。不能据此断言主进程初始化正确，更不能改超时为成功。下一诊断构建只在真实超时记录有界线程栈，保留原失败结果与 10 秒门槛。

### 2026-10-15 自身包信息、安装历史与广播权限（进行中）

- `wechat-startup-stacks-11`：主进程 attach 约 2.14 秒，未重现上次超时，因此没有产生超时线程快照。Provider 初始化明确失败在自身 PackageInfo 查询；后台初始化另报 Receiver 权限未拥有。未到登录，未证明此前偶发超时已修复。
- `package-permission-baseline-01`：原生基线失败于测试对普通 VersionedPackage 的错误假设，原始证据保留。
- `package-permission-baseline-02`：修正后 56 项原生检查通过，run `resources-1ae1264f3ca041d2`。不代表 Tripoder 已通过。
- `package-permission-runtime-p1-01`：runtime APK `74cd6409d3f6b412e3e73564960d941f2c7e502a3ddc439e76cdf4553a319df6`；前 160 项通过，包括新增的提交丢回执与三个升级断点的安装历史检查。整轮在 P1 旧夹具写死 0 号槽的 `testLiveOwnerDeath` 失败（PROCESS_SLOT_RETAINED_BY_TASK），清理也记录 WORKER_CALLER_MISMATCH，不能计 P1 通过；下一步改为按实际容量选择未占用槽，不清除历史未决任务。

schema 24 的安装历史、PackageInfo 和逻辑广播权限仍待完整 runtime 验收；P2–P6 与真实目标验收状态继续为未完成。

后续 `package-permission-runtime-p2-01` 同一 APK 的完整组件回归 572 项通过，run `p2-b674e4934f114784`，包含原始签名、flag 投影、独立复制、另包缺权限不产生投递记录；该轮 P1 整体仍未通过。`wechat-first-launch-12` 已越过自身 PackageInfo，失败变为 Provider attach 中 appops 未支持；后台 Receiver 同时暴露 `com.tencent.mm.permission.MOVE_XLOG` 为已声明、无 uses-permission 的 signatureOrSystem 限制。注册行为与发送者获权是不同语义，正在补原生对照；没有登录或首页成功证据。

`package-permission-runtime-p1-02` 保留第二次失败：夹具已正确选到空闲槽 3，但仍调用旧 P0 仅支持 0/1 的 allocate 入口，尚未执行死亡用例。改为 P1Authority 的专用受控 probeAllocate 后，`provider-delegate-runtime-p1-01` 整轮 **228 项通过**，run `p1-3dcaaf6bc3054740`，runtime APK `bba65485c32511a8d444ac07ec67400a856866fc3659cfede122fd8830ff1a4e`。新增安装历史已通过该轮恢复验收；没有扩展旧生产 allocate 入口，也没有清除历史任务引用。

`provider-delegate-baseline-01` 原生对照 59 项通过；`provider-delegate-runtime-p2-01` 同一 bba65485… runtime APK 完整 P2 回归 **577 项通过**，run `p2-68502fed2deb4ab3`。私有委托一次初始化、逻辑 Context、AppOps 不向客体开放、publication 范围外不获能力，以及定义但未请求的 Receiver 签名权限注册均已覆盖。P1/P2 的本批限定门槛通过不改变 P2–P6 全路线尚未完成。

`provider-delegate-agent-01` 自主 MCP 18 项协议 / 12 项设备检查通过，Sentinel 不参与。`wechat-first-launch-13` 运行日志确认登记 Provider owned=true/scoped=true，内部 androidx FileProvider owned=false/scoped=true，随后进入 Application.onCreate。失败变为 SYSTEM_SERVICE_UNSUPPORTED:audio，后台 Service 另报 SERVICE_EXTRA_UNSUPPORTED；未到登录。音频与 Service 标量参数为下一实现批次，尚未验收。

`audio-scalar-baseline-01` 原生 64 项通过（`resources-7562af55b4b04974`），真实音频初始回调含 10 个设备，交付到指定线程。Runtime APK `2a608e5c7bf4f44844633e5ac0d4395d072acddaac8f50781212664b9521baf0` 的 `audio-scalar-runtime-p2-01` **587 项通过**（`p2-80989a981e32408e`）；包含音频有界查询/真实初次回调、重复与取消、未授予写接口，以及 Service null/long/double/float/short/byte/char 的真实跨进程类型和值。未验证热插拔、录放音或目标登录。

同构建 `audio-scalar-runtime-p4-01` 在激活 Hook 的 ART 线程栈遍历出现 SIGSEGV，整轮失败；`audio-scalar-runtime-p4-02` 原样重跑 **98 项通过**（`p4-c50d1ecf3e694bf8`），不视为崩溃已解决。源码发现反优化调用未建立平台要求的独占 mutator 边界，已修订 native SuspendAll/Deoptimize/ResumeAll 路径并加入激活期间并发栈/分配/GC 夹具，等待新构建设备验证。

修订构建 `5b44a94a6d2c60a4a67476775fdca605343a7f8a7e63cba98d26652f8dce8dc7`：`deopt-suspend-p4-01` 至 `04` **连续四轮各 102 项通过**，run 分别 `p4-3c55b8d19b6e4304`、`p4-d53ce14610694d08`、`p4-2660a872a7aa4fb9`、`p4-5b6ede9d584c45fd`。激活期间四个线程均保持存活，栈/分配循环合计分别 87491、99808、77260、95661 次，随后正常停止。保留此前失败，有限并发回归不替代长期压力或 P6。

`wechat-first-launch-14` 仍未通过（`control-7edb2857e3444b2a`）：Provider 初始化结束后 worker 16443 退出。Android exit-info 记录 `EXIT_SELF/status=1`，同 UID 日志为 `System.exit called, status: 1`，没有此次 Java fatal 或 Native fatal。不能据此断言目标检测或主动安全拒绝；可能是自有异常处理器退出，需要下一构建观察未捕获异常派发和 exit 的调用栈。诊断只记录，不吞异常、不阻止退出。`engine-exit-info.txt` 是第一次误用展示名后缀 `.dev` 的空查询，准确包名结果在 `engine-exit-info-correct-package.txt`。尚未到登录。

`jobs-contract-p1-01`（runtime APK `c3d17d2a1d4a6df34d9874cc1a8a7a05ddd74595dda806bbbff1362ee148423a`）**229 项 P1 检查通过**，run `p1-2be7bd0074974041`。新增 JobInfo 子报告 15 项为真实 SDK 对象的无损转换/拒绝门槛；实际系统提交数为 0，调度和 JobService 生命周期尚未开放，也未声称王者通过。

`wechat-first-launch-15`（`control-dce9450dfaec4258`）首次取得主 Application RUNNING 回执，PID 21470；首页投递仍为 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED。新增观测记录 initThread 的真实 `SYSTEM_SERVICE_UNSUPPORTED:notification` 以及目标自身 uncaughtException 路径的 System.exit(1)，原退出行为未被阻止。此轮同时有 Java fatal；不是完整启动、登录或 P6 通过。通知渠道/分组的有界真实实现与原生对照正在准备。

通知原生基线 `notifications-baseline-01` 未产生本轮结果，保留 BASELINE_RESULT_UNKNOWN，不计通过。启动日志只把旧任务带到前台。验收脚本改为 NEW_TASK + CLEAR_TASK 后，`notifications-baseline-02` **73 项通过**（`resources-6273b8253a664734`），包含真实通知渠道/分组 CRUD、平台 importance 保持及原始异常传递到自定义处理器。自研夹具 SHA `7b92e78822bb81fb9ad0badc4a329c0c4f3a44a98e8254de469116e0240c6361`；仅是原生对照，Runtime 验收另记。

`notifications-runtime-p1-01` **237 项通过**（`p1-2d39fd5e8ce14de7`），runtime APK `27b625f69481878cb0dc15e6e11f698776d79b6303db2e4c80768e36571cfac1`。同名通道/分组按独立 data incarnation 映射；三个升级断点保持资源，卸载提交前保留，提交并恢复后删除。P2 客体通知行为及微信复测另记。

`notifications-runtime-p2-01` 设备 **598 项断言通过**，但脚本整体失败：自研异常分发夹具故意抛出的异常被 Android 的预处理器写入 crash buffer，触发原“一切 Java fatal 都失败”的判定。该异常确实交给了应用自己的 handler，Thread/Throwable 身份相同，进程继续执行并完成全套测试；原失败报告不改写。后续分类器保留 `javaFatalObserved`，仅在整个 P2 成功回执、UID/PID、指定线程、精确异常与夹具调用栈全部匹配时单独记录该一条预期事件；任何第二条、其他栈、未知格式、失败/缺失回执仍失败。分类器六个测试组包含负向篡改用例，已通过。

`wechat-first-launch-16`（`control-00c84f536b05493c`）被上次未知 Activity 引用以 `APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED` 拒绝，未执行新 Application。不能计为通知运行成功或失败。首次资源检查在源摘要门槛拒绝（runtime 重建生成了新的夹具 APK），未操作设备；需用匹配的新 product 构建继续。

`notifications-runtime-p2-02` **598 项通过**（`p2-7fac59730a2f409c`），Runtime APK `138bd5978aa902e21c12480f3b54c9207aecc53a98318f45de4c908d6b2660d6`。原始 Java fatal 观测为 true，其中唯一一条为已核验的故意异常分发事件（PID 19842/TID 20260）；unexpected Java fatal 为 false，Native fatal 为 false。通知实际 CRUD、逻辑 ID 副本与跨空间隔离、未支持操作拒绝及完整组件回归通过。

`notifications-resources-02` 生产资源检查通过，run `control-093b4932fca74ad1`：8 槽中 7 可用、0 allocated、1 retained。微信 ticket `4b56691d-5e39-49e6-974e-57e5ce06cc01` 为 PREPARED/taskId=-1/旧 generation DEAD，空 Gateway task 1755 仍保留；reclaim 返回空数组，没有伪造附着或清理证据。正在新增独立的永久入口关闭协议。

`notifications-agent-01` 最新产品内核的自主 MCP 闭环通过 **18 项协议 / 12 项设备检查**，Sentinel 不参与。原通知有限能力通过不等于 P2–P6 全阶段完成，微信/王者仍未到登录。

入口关闭定向测试的前两次失败均保留：`entry-fence-runtime-01` 在测试编排持有 broker 锁时等待进程启动，得到 PROCESS_START_TIMEOUT_UNKNOWN；改用既有锁外 ensureProcess 后，`entry-fence-runtime-02` 已验证持久关闭、冷入口拒绝、原 UNKNOWN 与资源引用保留，以及新启动复用同一不可变组件租约。第二轮失败于测试过早等待返回给尚未恢复的调用 Activity 的取消结果；按 Android 实际生命周期把结果检查移到活跃子界面退出后，继续第三轮。两次都不计整体通过。

`entry-fence-runtime-03` 定向 **22 项通过**（`entry-f0fc14be57e34128`），Runtime APK `0b94d19c40e5c6e65afa79771b13b1eb8731edab94fb1caeeb5783c9648f8862`。实际 Standard 与 singleTop 夹具覆盖冷 Factory、热 Factory、真实 onNewIntent 的旧入口拒绝，authority 死亡后持久关闭，原操作 UNKNOWN 与资源 pin 保留，新明确启动复用同一不可变 lease/slot，正常新 Intent 与结束仍有效。此测试调用生产状态/租约原语，但 Gateway task 用自研调用 Activity 的 taskId 提供；完整产品 Gateway 与微信实测另记，不把定向测试等同产品验收。

`entry-fence-runtime-p2-01` 整体失败：40 项检查后，双空间四进程场景在第四个进程得到 PROCESS_CAPACITY_EXHAUSTED，夹具真实 Java fatal。运行前三个历史未知 lease 加上本次两份独立入口关闭夹具共保留五槽，只剩三槽；没有清除账本、扩容或把 UNKNOWN 改成功。定向 22 项入口门槛不等同完整回归通过；未附着资源最终回收仍是明确缺口。

`wechat-first-launch-17`（`control-682a2bb768a64493`）生产入口关闭 3 项检查通过：旧 launch `d37664c4-5731-4e9a-8585-c79b73574c88` 的 closure `399ae83d-7ac6-4e4a-b0d1-36e5b848a82b` SUCCEEDED，重复返回相同 ticket，原 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED 和 pin 均保留。新的 launch `b75adb38-beef-444c-9036-4f3771ec438f` 在 prepare 阶段因 worker DeadObjectException 变为 UNKNOWN/LAUNCH_PREPARATION_UNCONFIRMED，整体未通过。真实日志显示 Provider 初始化已结束，协程异常通过 ThreadGroup.uncaughtException 导致自有 handler System.exit(1)；现有 Thread.dispatchUncaughtException 观察无法覆盖直接调用 handler 的协程路径，仅捕获退出栈，不能据此猜测具体缺失服务或认定通知已修复。下一步补保留原调用的框架 ThreadGroup 诊断。

`entry-fence-agent-01` 自主 MCP **18 项协议 / 12 项设备检查通过**，未使用 Sentinel。`failure-group-runtime-01` 定向 **4 项通过**（`failure-9cf9cf103d7a4315`），Runtime APK `b2a5f2c6789caea06c18ea9b99d54db8e70cb78e06568555dcf1d978b59af2d6`，验证真实未捕获异常和直接 ThreadGroup 路径均保持原 handler、Throwable、Thread，结束后恢复原默认 handler。有限定向检查不替代仍被历史资源容量阻塞的完整 P2 回归。

`wechat-first-launch-18` 仍未到登录。新增 ThreadGroup 诊断明确捕获 IllegalAccessException：Field.get 的调用者被识别为 java.lang.reflect.Field，读取 GuestPackageManager.engine 私有字段失败，随后原异常处理器退出。launch `bd6fa1a0-e787-43e3-b861-1521eb18d39b` 保持 UNKNOWN/LAUNCH_PREPARATION_UNCONFIRMED。开始用自研原生/运行器反射访问夹具区分通用 caller-sensitive 拦截缺陷与目标不受支持的反射模式，不放开所有私有字段、不抑制异常。

`reflection-runtime-before-01` 自研类内 private 字段访问真实失败，复现 managed Field replacement 改变 caller-sensitive 调用帧。原生 `reflection-baseline-01` 先因新增 Activity 后旧计数断言未更新失败；修正计数后的 `reflection-baseline-02` 78 项通过。native-entry 修复后的 `reflection-native-runtime-01` 8 项通过，APK `ad03088038414d7c855c869676e7c5b1db9b1f5992956d8b7c1c77d56929fb75`。

扩展原生 `reflection-baseline-03` **91 项通过**（`resources-4ad830fd4e4146c5`）；运行器 `reflection-native-runtime-02` **23 项通过**（`failure-93aab5eda50b4467`），APK `f9dc7defc1dff942a802f5a2268d1c6dc252b7f9c38f2c99e31fe1c3e96a3ee5`。覆盖权限/类型/引用/primitive、四线程 GC 普通反射 4000 轮，以及 P2 Application 初始引用的访问检查、非法写拒绝与四线程 2000 次读取。没有把并发有限验证称为长期压力通过。

`wechat-first-launch-19`（`control-7940051ae07d4476`）Application RUNNING、PID 21349，launch `19d388f1-a1ed-48fc-b905-d478121ffb7b` 最终 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED；仍未到登录。原生反射入口已恢复真实调用者 lh5.h，目标仍因访问 GuestPackageManager.engine 私有字段而退出。原 APK 的本机只读诊断确认，其先寻找平台 mPM，失败后进入字段扫描；运行器需要完整的平台 facade 与代理链，不能放开内部字段或吞掉代理替换。正在实施独立 worker 包域，尚未验收。

包域第一轮 `package-world-runtime-01`（APK `e2ea7eb772d68e318d041f6d6fb57f55263591e6a5e2aeabbeeb4a20be5382c4`）未通过：Application 运行后，Android ActivityThread 在创建 Activity 前真实调用 getSdkSandboxPackageName，被能力边界拒绝并产生 Java fatal，随后测试超时。没有把新增基础设施能力当成默认透传；仅添加原始 physical IPM 的真实只读沙箱包名查询。此轮未知任务引用保留。

后续拆出 Application-only 自研包域夹具以避免每个诊断失败新增任务引用；它不能替代 Activity/组件回归。`package-world-application-01` 被夹具身份校验拒绝：生成 manifest 没有替换相对名称 .MetadataApplication，因此并未执行预期 PackageWorldApplication。原失败保留，生成器已增加精确声明数量检查，等待新构建。

包域连续修复的失败证据保留：`application-02` 的 PM 返回空值，原因为包装 Context 未按声明类型转换，且 JavaScript 错误未转为 Java 错误；`03` 在请求完整引擎组件清单时出现小请求的 FAILED BINDER TRANSACTION / DeadSystemRuntimeException，改为切换前本地解析引擎自身 APK；`04` 查到物理 IPM，表明切换没有完整完成；`05`、`06` 捕获切换期间的 TypeError，精确栈位于 IInterface.asBinder 的接口调用；`07` 进一步捕获静态 getter 返回接口对象上直接调用 Object.equals 的类型问题；`08` 已通过真实实例/全局代理链，但普通客体的 getServiceOrThrow 反射查找被平台隐藏 API 策略拒绝，未到该路由。各目录完整前缀均为 `package-world-application-`，失败结果未改写。

`package-world-application-09` **50 项通过**（`packages-b81106afa9d74b29`），Runtime APK `8cfdfef0cf49037b668ceeac2252505506791e39ee8afb399a7066597353269a`。真实 ApplicationPackageManager / mPM、实例与后台代理命中、sPM 和新配置 Context 代理、停用恢复、可访问 ServiceManager getter、本地 Binder 拒绝实际调用均验证；公开物理 Bridge 的错误包/用户/未声明组件和伪造 Context 被拒绝。32 组临时 Context/PM 的 64 个弱引用全部回收，Java/Native fatal 均为 false。

该 50 项包含五项**平台禁止查找**观察：getServiceOrThrow、waitForService、waitForDeclaredService、PackageManager.getHoldLockToken、rawGetService。它们不算这些运行器路由已经被实际调用验证；适配器只另确认固定签名存在。测试没有放开隐藏 API、没有增加运行时 fallback。此轮不创建 Activity，不能代替后续组件/MCP/真实目标验收。

`package-world-application-10` **78 项通过**（`packages-1b005c9be7444a31`），Runtime APK `4099beb73183de988e8b99b64e960ee34ab806246a745035614d03893bf9db74`。在同一 Application-only 闭环增加原有 PackageInfo 与 XML 检查，共享检查从 Activity 提取复用；原始签名、完整 flags、组件集合、防御副本、XML 来源与路径伪造拒绝均通过。未把引擎基础设施域误记为“宿主包完全不可见”；其它包查询仍明确拒绝。后续仍需真实 Activity 路径。

同构建夹具的原生 Android 对照 `package-world-baseline-01` **91 项通过**（`resources-32af4e6c66b046fa`）。这证明提取后的共享包信息/XML 检查保持原生对照；此结果不是 Tripoder Activity 路径验收。

`package-world-agent-01` 产品 MCP **失败**（`control-5e61f356c16b4f47`）：配对、观察、审批和激活已完成，真实 UI 未出现 Hook 0。engine slot1/PID 21891 在 ActivityThread.performLaunchActivity 的 SDK 沙箱检查中，因框架 mSystemContext 不属于应用包而触发 PACKAGE_CONTEXT_IDENTITY_DENIED，Java fatal 已保留。未生成真实命中证据，不能把 ACTIVE 回执视作闭环成功。修复限定为切换时捕获的 ActivityThread 系统 Context 身份，仍返回逻辑包域；不按任意 android 包名放行。

`package-world-application-11` **81 项通过**（`packages-0e99a33edf024301`），Runtime APK `8bc800179e543ecb151ca508430c00684c2405e9f9dade50a28616cb74ef9e18`。新增重复 activate 在修改任何 mPM 前被拒绝、无标签时采用 ApplicationInfo.name、nonLocalizedLabel 保持原文本的实际检查。精确系统 Context 准入的 Activity 路径仍由下一产品 MCP 验收，未用 Application-only 结果代替。

`package-world-agent-02` 产品自主 MCP **18 项协议 / 12 项设备检查通过**（`control-d313ab556f214bac`），engine APK `71a979c053c04b5f9b8f4bdbf6d8d364ce5253b4da8fe2d9ab0ef693fcd80992`。真实 Activity PID 25942 完成观察、审批、激活、2 次命中、停用恢复原界面与会话撤销。此前系统 Context 准入缺口在此实际路径验证；未使用 Sentinel，也不代表全部组件路径通过。

`wechat-first-launch-20`（`control-1ebe6ea2e35a433f`）Application RUNNING，PID 27382，但 launch `0812912e-7ac6-43ee-b555-2b079d4fe270` 最终 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED。实际未捕获异常指向 netstats、batterymanager、wifip2p，以及通知 cancel(String,int) 尚未支持；原处理器 System.exit(1)，没有抑制退出、登录或首页成功证据。上次 UNKNOWN 的入口关闭 3 项检查通过，原历史及资源引用仍保留。新的服务能力正在按明确契约实现，不能开放整个宿主服务来替代。

设备观察与取消批次 Runtime APK `3f90d3c1d8c38e6c1987987fdd8c413def66559993bbb522f5bc4e7c7fc0fd18`。`device-observation-baseline-01` 原生 Android **119 项通过**（`resources-0603af2eb33f4a6c`），包含真实电池/物理 UID 与设备流量观察、19 次真实 P2P initialize 和关闭；没有主动发现/组网，也未观察到断连 callback，不把生命周期故障分支称为通过。运行器与实际通知取消另行验证。

`device-observation-runtime-01` 失败（`packages-7b0e63c79f144f45`）：worker PID 3588 在 WifiP2pManager.Channel.close 路径触发 JNI GetStringChars 的 null string 错误，随后的诊断栈发生 SIGSEGV。原始 crash 与限定 UID 日志保留，不能把原生通过代替运行器通过。正在简化关闭适配的调用边界并增加自研夹具阶段观测；不屏蔽崩溃。

同构建 `notification-cancel-runtime-01` **8 项通过**（`notification-ddceb7315a2e4834`）：真实投递五条通知，生产取消逻辑实际移除当前 dataId 三条，另一 dataId 与引擎自己的两条保留。null/空 tag 不冲突、缺失与重复取消、非法 UTF-8/超长 tag 拒绝均通过。测试包 POST_NOTIFICATIONS 原为 denied，本轮临时授予，完成后恢复 denied 已确认。该定向检查不经过客体 P2P 路径，不证明前述崩溃已解决。

`device-observation-runtime-02` **124 项通过**（`packages-63afb3b151d74db8`），Runtime APK `245f648b53ec3a6816f15d0640f9b73f55cfa939a507abcafd46559c48d45386`。关闭路径改为单一 adapter 调用内执行原方法，Java 层只做前/后状态确认；外来回调夹具的 Object 方法也修正为有效语义。真实 19 个 P2P channel 的初始化与关闭、硬件支持查询、Context/回调归属拒绝、null Looper 不替换、未授权无线操作拒绝通过。电池、通知缺失 ID 取消、共享物理 UID 计数与无适配 authority 前后夹取、历史统计明确拒绝也通过。未观察到 Java/Native fatal；保留首轮失败，不将单轮有限检查宣称长期稳定或P6完成。

`device-observation-agent-01` 自主产品 MCP **18 项协议 / 12 项设备检查通过**（`control-699744da763e433d`）。真实 Hook 激活、2 次命中、停用恢复与撤销均有设备回执，Sentinel 不参与。

`wechat-first-launch-21` 仍失败（`control-cdbcb6310cf34856`，worker PID 11583）：Application 已运行，launch `eaba4c07-c4e0-4bea-96a8-bf210da289ee` 最终 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED。未捕获异常变为性能模块读取 power 服务；原 handler 随后 System.exit(1)。没有登录和业务证据，不将此前四项初始化缺口未再出现解释成完整能力通过。下一批核对电源/温度观察契约。

电源批次原生 `power-baseline-01` **126 项通过**（resources-3196acd4cb864515）；运行器 `power-runtime-01` **133 项通过**（packages-40a87e7498bc485f），Runtime APK `977e8bbdc905b3c809af3fe76d6ce59f488a29b183b061a8ab301f048aa6f48d`。真实温度/thermal headroom 初始回调、指定 executor、重复注册拒绝、删除后排队回调拒绝、电源控制拒绝已验证。无 Java/Native fatal。默认主线程回调只在原生基线验证，Application-only 运行器用例不宣称覆盖这一点。

同批产品 `power-agent-01` 自主 MCP **18 项协议 / 12 项设备检查通过**（control-3a4d991edaab4369），engine APK `71286dc85d2a87ac9e89f66ebfde664348a47f4e0365fb986b96ce0e13275b05`。

`wechat-first-launch-22`（control-16ce3bd4bb414f1c，PID 19751）进入 Activity 创建但未到登录：自定义 SplashHackInstrumentation 路径调用 execStartActivity 被 ACTIVITY_START_UNSUPPORTED 拒绝；launch `f8ef923c-97c8-44ba-b771-e08cbf9a3063` 保持 UNKNOWN。随后系统恢复还出现 COMPONENT_PEER_MISMATCH，须区分首次启动失败和死亡观察竞争。本地只读原 APK 诊断确认自定义 Instrumentation 可以直接创建 Activity 并绕过 Factory；正在重审统一创建准入边界，不复制目标实现。

Activity创建首构建 Runtime `2e632d35d1f3e1733ab99ba505659ba9972a653fc391735367e9649be778dd3d`：`creation-adapter-01` 的包域回归失败，因夹具新增两个Activity但数量断言仍为4；不计通过。新自研原生 `creation-baseline-direct-01`、`creation-baseline-super-01`、`creation-baseline-super-replace-01` 各17项通过。对应产品 `creation-agent-01`（engine `624d4beb2e532527fb600c97aa98f45dc421ad5a00d1b11b872123f34d0ce06a`）MCP18/12通过。

同构建 `creation-runtime-01` 失败：替代子Activity已执行onCreate，在finish携带整数结果时被旧RESULT_EXTRAS_UNSUPPORTED拒绝；不是构造闭环通过。主进程收到取消且没有pid字段，后续报告校验失败；Java fatal原文保留。第二构建补充有界标量结果、稳定Intent修改保留、私有主题、精确拒绝对象attach和无歧义摘要的验收，等待新设备证据。

Activity 创建修正构建 Runtime `f591f6d1efb6f906f7fce0fc5eb1c0a75dfac3b9511f74cce780139161b72e8a`：`creation-baseline-direct-02`、`creation-baseline-super-02`、`creation-baseline-super-replace-02` 原生各 **20 项通过**。`creation-adapter-02` 包域/系统服务 **133 项通过**（packages-35513cd045104817）；`creation-runtime-02` **75 项通过**（creation-7726e68093f74967，PID2255），含三种实际构造方式、子对象替换、构造 Intent 修改保留、attachBaseContext 逻辑身份、生命周期前私有主题/资源、默认主线程真实温度回调、整数结果码与整数结果，以及摘要/FD/Binder/ClipData 边界。该批没有验证系统冷重建和单次 launch 中途替换 Instrumentation，不代表完整 P2/P6 通过。

`creation-agent-02` 自主产品 MCP **18 项协议 / 12 项设备检查通过**（control-b4bce3c9fc8d4733，engine `93a36c4dfa4bbfe457fd0d15d0e1f73db05960eab34f3c856746ba378d92aaa4`）。`wechat-first-launch-23` launch `1e05dfea-94af-4015-a043-79e9b0a9997d` 在 Application attach 阶段超时，保留 UNKNOWN/LAUNCH_PREPARATION_UNCONFIRMED。真实栈显示主进程 PID6587 的 startService 同步等待第二进程适配器加载；远端 PID6847 的加载用时约8.49秒，主进程10秒窗口先到期。不是 Activity 构造缺口再次出现，也不是登录成功。正在将 Service 路由改为持久预留后由 Android 启动实际组件，避免把请求接受和目标初始化混为一体。

Service 异步请求批次保留两次失败：`service-startup-baseline-01` 原生证实无 BIND_AUTO_CREATE 的绑定并非必然 false，错误假设已删除，该模式在运行器明确拒绝，等待独立持久绑定契约。`service-startup-baseline-02` 原生9项及平台绑定观察1项通过，startService接受耗时约0.83ms。`service-startup-runtime-01` Runtime `ca5c97576b4b13db84653eeda172240e1a56802c711aaf9e02ac05e5e009fd6c` 仅确认主Application attach里的startService在约139.81ms返回；后续显式不支持错误被GuestContext包装改变类型导致失败，不能计为完整Service通过。已修改公开服务入口保留原RuntimeException，等待新构建验收。

`service-startup-runtime-02` **13 项通过**（service-startup-d95b69d46bd043f9，Runtime `46749b61e696906b76317d69142175039fb009bfe66f8d22cd01b9c5f30897e6`）。实际 Application.attachBaseContext 跨进程 startService约149.47ms返回；Android驱动的远端Service、Binder、startId、逻辑身份、解绑与停止均验证。无AUTO_CREATE的绑定为明确拒绝，非功能支持；未用不真实false绕过平台语义。没有Java/Native fatal；完整Service竞争/未知ACK/冷恢复仍单独验收。

`service-startup-agent-01` 自主产品MCP **18项协议/12项设备检查通过**，engine `3878c490fc353bb290b1375a81241a3884d937cc6d6df9c4648e8e8b61e49af1`。`wechat-first-launch-24`（control-592326cc36a04153，PID17349）Application运行并进入真实LauncherUI.onCreate，最终因包域getActivityInfo未支持而退出。launch `7d380694-b1a1-47d3-a621-018cccdbd373` 保留UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED；随后系统重建因ACTIVITY_RESTORE_NOT_CONFIRMED被拒绝。未到登录，不能将已进入onCreate称为首屏通过。正在补已验证APK自身ActivityInfo查询，并以别名/禁用/跨声明进程/查询flags/防御复制对照验收。

ActivityInfo 批次 Runtime `a172c39922d85ad1f1df81b3649ae3fd06c6e4f152ef1ceb129cdaabcad574fb`：`activity-info-baseline-01` 原生 **134项通过**（resources-20cdcb6ef94a4f99）；`activity-info-runtime-01` 运行器 **142项通过**（packages-c9f3537f2ec04c04）。自身Activity与别名、禁用/另一声明进程、直接启动过滤、原metadata、缺失组件、长flags拒绝及防御复制均验证；无Java/Native fatal。该结果不支持跨包查询或完整PMS。

`wechat-first-launch-25` 在实际目标启动前被旧已附着入口拒绝，操作84ff4721-cc57-4714-88bf-640132666d57为FAILED/LAUNCH_TARGET_REJECTED，没有新业务执行。`activity-info-resources-01` 通过生产reclaimEmptyTasks实际移除已确认附着、进程死亡且为空的task1976；原未附着UNKNOWN pin继续保留。接着同构建`wechat-first-launch-26`（control-e75bf61618ad481a，PID24695）再次进入LauncherUI，但实际flags 0x100c0280包含MATCH_DIRECT_BOOT_AUTO，被严格flags边界拒绝；launch3a992e00-8e89-4eda-9139-0d3fa629f76d保持UNKNOWN。新增AUTO语义对照，且把已有凭证回收提前到控制启动的开放入口检查之前；没有清账或把旧UNKNOWN改为成功。

ActivityInfo AUTO构建 Runtime `eb563f58220b9218222b580768151af7313f8d79f364afd992a73a8cf100b2e4`：`activity-info-baseline-02` 原生 **136项通过**（resources-d19328354a0f49cd），`activity-info-runtime-02` 容器 **144项通过**（packages-76e071b83e3245d5）。覆盖公开facade的AUTO标志和平台已展开的aware/unaware组合；未验收锁定启动前的设备状态或未经facade的原始IPM AUTO分支。控制启动前回收的产品变更由后续目标启动回执验证。
# 2026-10-15 后续进展

`wechat-first-launch-27`（Runtime `eb563f58220b9218222b580768151af7313f8d79f364afd992a73a8cf100b2e4`）已越过此前 ActivityInfo/AUTO 和启动前资源维护阻塞，Application 为 RUNNING，真实 LauncherUI 进入创建回调。仍未首屏确认：Google measurement 查询容器内未安装的 `com.google.android.gms.phenotype` 时被错误抛出 PROVIDER_NOT_IN_SPACE，主线程死亡；另有后台 SERVICE_BIND_FLAGS_UNSUPPORTED。操作 `02e610b9-a84d-46e8-99bf-bb3aa1dcd008` 保持 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED。未进入登录，不能算微信通过。


## Provider 缺失与 Service 调度标志（2026-10-15）

Runtime `216e7b2b6ea2a04142c28ac5f294a5c3d8c52b6da81f7469e521a9659eddc193`，生产引擎 `43eb13ff6db8652fc9ff4a9291d0422dca44ea6eeae1208b02ab3f33d4b6e475`。`provider-absence-baseline-01` 原生146项、`provider-absence-runtime-01` 容器154项通过，其中10项为实际 ContentResolver 缺失行为；无现有 Provider 错误吞掉/宿主 authority 退路。`service-flags-baseline-01` 原生10项、`service-flags-runtime-01` 容器13项通过，真实 AUTO_CREATE | NOT_FOREGROUND | WAIVE_PRIORITY 绑定、不同PID Binder、startId、解绑/停止，attach阶段startService约184.88ms返回。无AUTO_CREATE仍明确未支持。固定微信第28次复测使用该产品构建；单项对照不等同目标业务通过。

## 被动 Service 绑定（2026-10-15）

`wechat-first-launch-28` 的操作 `c807ff1d-2310-4e0a-b6f1-c0d162a9f9f4` 仍为 UNKNOWN/ACTIVITY_DELIVERY_PROCESS_DIED。Provider 缺失查询已越过；后台实际标志为0x20（WAIVE_PRIORITY，不含AUTO_CREATE），主线程下一缺口为ShortcutManager获取。无登录和首屏通过证据。

独立PASSIVE组件租约随后落地：不分配运行session，不启动Worker，不设置启动watchdog；真实后续start才提升同一租约。Runtime `18d49df4c775f24666419a66704d25754b959eaa10d8540bc4d44d129109e56c` 的 `service-passive-baseline-01` 原生12项、`service-passive-runtime-01` 容器15项通过。authority在绑定已保持至少1.2秒后，确认真实系统无目标进程、持久目标session数为0、已取消的第二组件租约RELEASED，再放行客体发出真实start。原先连接收到远端真实Binder，服务仅start一次，stop/onDestroy均观察到。`service-passive-startup-regression-01` 原有attach启动路径13项通过（非AUTO绑定现在被实际接受）。

这不是非AUTO绑定的完整竞争/authority死亡验收，也不证明所有Service未知请求可以回收；PREPARED结果仍留存。没有扩大组件池或清理历史未知任务。

## Shortcut 能力声明与 MCP 结束时序（2026-10-15）

Runtime `0774d4300140182e91dada3670703c74ccf231d32bc3412f53ca74b6f7f48e24`：`shortcut-capability-baseline-01` 原生147项、`shortcut-capability-runtime-01` 容器159项通过。客体ShortcutManager不持有系统Binder；仅将未实现的外部Launcher入口能力如实声明为pinSupported=false，其他命令/查询明确拒绝。未实现Shortcut发布、固定或冷点击，不能记为桌面快捷方式支持。

`service-passive-agent-01` FAIL：Hook部署、两次真实命中、停用恢复及session_close返回CLOSING已完成；设备先观察到CLOSED/NO_ACTIVE_HOOK并停止TCP入口，桌面第二次只读session_get收到TRANSPORT_EOF_OUTCOME_UNKNOWN（request operationId `4be5ed03-a193-4c55-af96-acaa0aca8807`）。Python失败清理又引发UiAutomation disconnect DeadObjectException。保留全部失败，不能用设备已关闭代替客户端收齐回执。

验收协调已修正：正常模式客户端读到最终CLOSED后，原子发布匹配run/session/close operation的确认；设备验收等待这条确认再关入口。没有修改生产关闭协议、吞掉EOF或重新执行旧副作用。新会话复验结果见后续目录。

`service-passive-agent-02` 在生产引擎 `b7c3882c1cbb02e1ef62ed17cbcc5c9726331114a9ee78487e0f7ce6e5b1c40e` 上 MCP18/设备13项通过；新增断言确认桌面端收到最终CLOSED后设备才关闭TCP入口。实际Hook两次命中、原函数恢复、真实设备截图和会话撤销仍由同一会话验收。未使用Sentinel。前一轮失败未改写，复验使用新的自研计数器项目和会话。

## 微信第29次固定目标启动

`wechat-first-launch-29`，run `control-f16e77d306bb4119`，PID 19941，launch `bce5335f-c94e-4539-9c7c-1e2c9595e6f2`。已越过上一轮Provider、Service非创建绑定和Shortcut能力发现；在 LauncherUI.O7 → Instrumentation.execStartActivity → RuntimeActivityIntent 明确失败 `INTENT_FLAGS_UNSUPPORTED`。保留 `UNKNOWN / ACTIVITY_DELIVERY_PROCESS_DIED`，没有登录或首屏验收成功。下一步按真实平台行为实现SINGLE_TOP并补持久标志及回调回执。

## standard Activity 的 SINGLE_TOP 标志

构建 runtime `ee43a63757f108ecf02b2e7317b5da0aa66b9e428050b00c0484670df448762f`。`activity-flags-baseline-02` 原生8项通过，run `activity-flags-4de6963e63c24e67`；`activity-flags-runtime-01` 容器18项通过，run `activity-flags-2ffd63268f9744d7`。真实standard根实例两次onNewIntent（含NEW_TASK组合），无标志/非栈顶场景各自新建；6次启动在持久账本为4次CREATE、2次NEW_INTENT且均RECEIVED，根实例真实onDestroy确认。schema26迁移保留历史flags未知，不用start结果2/3推断SINGLE_TOP回调完成。

`activity-flags-baseline-01` 保留失败：原生夹具以100ms猜测子Activity销毁完成而超时。改为onResume/onDestroy事件共同推进后通过；产品生命周期未为此增加重试或延时。

## 微信第30次固定目标启动

`wechat-first-launch-30`，run `control-8ebfb913c9b74ea8`，PID 27546，launch `5b7d9610-6c73-4fb6-8c89-a8dabd1e3637`，engine `539caeea4bf1263b88bd4fe962005e0d77972c367af01aa201068ddeff351947`。schema26生效，越过启动flags后在LauncherUI.O7的同一跳转遇到`EXTRA_UNSUPPORTED`。保留UNKNOWN；系统随后尝试恢复未确认Activity，被ACTIVITY_RESTORE_NOT_CONFIRMED拒绝，未将该恢复误记为新成功。下一步补有界、保类型且不可变的请求数据快照。

## Activity有类型数据与不可变快照

Runtime `8b7e81e34894f092790a2a28e35d561aa517ef99fc961f074109494263a9dae5`：`activity-values-baseline-01` 原生11项（run `activity-flags-4034188ddb624dd4`），`activity-values-runtime-01` 容器39项（run `activity-flags-c4df2141b8af41fc`）。包括真实Activity传递null、所有基础数值类型及数组、嵌套Bundle；摘要数值/数组类型区分、null/absent/empty区分、实际Parcel往返、深拷贝后原始数组与字段修改不影响快照；循环/超限/Binder/未准入Parcelable拒绝，原结果FD和Clip拒绝回归通过。SINGLE_TOP的4次CREATE/2次NEW_INTENT与销毁回执继续通过。

## 微信第31次固定目标启动

`wechat-first-launch-31`，run `control-9f6be8b8eb8a4d75`，PID 32460，launch `3017c5c3-18f4-4dea-b81f-a48d4dc28f2c`。有类型错误明确定位为 `ACTIVITY_EXTRA_TYPE_UNSUPPORTED:java.util.ArrayList`。尚未进入登录，原操作继续UNKNOWN；下一批增加逐元素限定的数据列表，循环/超限/未准入对象仍拒绝。

## 同一次launch内替换Instrumentation

Runtime `9bc3dd2d300d3f419e9b61bf3b187f3a007f97df0cb274e4b7dd5ffb08c08c3e`。`late-creation-baseline-{direct,super,super-replace}-01` 原生每种21项，`late-creation-runtime-01` 容器91项（run `creation-2b3dde0579354a01`）通过。客体getContext在实际performLaunchActivity中创建并替换为新的override声明类，该声明收到逻辑名称/Intent；直接/super/super后替换最终对象、主题、结果和默认线程回调通过。构造器登记不等于对Unsafe或任意Native同UID代码提供强隔离。

## 有界数据列表真机验收

同一 `9bc3dd2d300d3f419e9b61bf3b187f3a007f97df0cb274e4b7dd5ffb08c08c3e` 构建，`activity-lists-baseline-01` 原生12项（run `activity-flags-fb8e2a9d84f04363`）、`activity-lists-runtime-01` 容器43项（run `activity-flags-d984145a5f7b452c`）通过。实际String/Integer ArrayList保留null元素，列表深拷贝不受原对象修改影响；循环列表和真实请求FD拒绝；既有标志、数据、原生结果及回执回归通过。

## 微信第32次固定目标启动

`wechat-first-launch-32`，run `control-602c30f6476344f2`，PID 9575，launch `95fa6203-ccde-4914-80b3-61c02500ede9`。首次异常进入 LauncherUI.onStart → Application.onActivityStarted → sendBroadcast(intent, permission)，`BROADCAST_POLICY_UNSUPPORTED`。系统/应用后续自行恢复另外暴露 `RECEIVER_FILTER_UNSUPPORTED` 及 SplashHackActivity 转交嵌套Intent时的 `ACTIVITY_EXTRA_TYPE_UNSUPPORTED:android.content.Intent`。所有崩溃保留；不能把较晚恢复异常当首次根因，也不把onStart被调用视为完整首屏成功。

## 发送端接收权限筛选

Runtime `767689fcaa197479a7117936c1ecc7181c2e04e24efa5a18440aff875669c160`：`broadcast-send-permission-baseline-01`原生15项（run `activity-flags-63309b6fb9674142`）；`broadcast-send-permission-runtime-01`容器48项（run `activity-flags-a913ef41b4cd4a16`）。同一签名且请求权限的接收者真实命中；仅声明未请求者被过滤，不受限barrier仍命中。权威端恰好2条COMPLETED交付，拒绝目标未产生物理交付。旧注册端发送者限制复用相同签名/请求判定；只支持拥有的签名权限，未知权限与options不降级透传。

## 微信33的前置阻塞及资源核对

`wechat-first-launch-33`（run `control-922f791a587240b6`，prepare operation `7f67af21-4d05-461f-a169-84f89563a295`）在新worker启动前被APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED拒绝，状态FAILED / LAUNCH_TARGET_REJECTED；无新目标运行，不代表广播修复已在微信生效。`wechat-32-resource-inspection-01`生产诊断2项通过，allocated/retained/available=0/2/6。WelcomeActivity ticket `a1bba309-d1c8-47c9-8a5c-ca059ebd8a62` 为PREPARED、taskId=-1、原generation DEAD，没有entryFence；此前root资源已按正向附着证明退休。下一步补内部路由的精确关闭，保留UNKNOWN和资源pin。

`broadcast-permission-agent-01` 在此前同一已安装产品构建上，自主MCP18项/设备13项通过。没有使用Sentinel，观察→部署→实际命中→停用→桌面确认CLOSED完整闭环完成。该结果只覆盖该构建的MCP和自研Hook夹具。

## 内部Activity入口关闭及微信35

`wechat-first-launch-34` 未执行关闭：通用query按操作所有者正确拒绝读取客体内部route（OPERATION_OWNER_MISMATCH）。实现改为项目范围的入口列表提供关闭前事实，成功关闭操作提供精确票据回执，未放宽通用query。

`wechat-first-launch-35`（engine `94990902faf17e502a57872ddf741ed26c61bf7e6a9a99966d5acb35ce8749c2`，run `control-e9ac67bd199d4dfe`）：内部Welcome票据 `a1bba309-d1c8-47c9-8a5c-ca059ebd8a62` 的关闭operation `660880e0-2658-4d03-9ab7-ba958de731ec` 已成功。5项专项断言通过：通用query仍拒绝、错误binding拒绝、原操作UNKNOWN、重复关闭幂等且资源保留、原UNKNOWN及detail保持。closure不退休资源；工作台待确认root与内部子入口的关联交互尚需补验。

其后微信新PID24816、generation `0e2c9e9e75c305de4ed0eb2bb9020db6f4fc585d98fbab3f3274c420357ed1f3`，launch `f87a99b9-5229-4571-aa38-98791ff38f01` 已完成Application初始化，但15秒回执仍RUNNING，首屏断言失败。实际新worker首个线程异常是SYSTEM_BROADCAST_POLICY_UNSUPPORTED:android.intent.action.BATTERY_OKAY，随后应用主动exit。crash buffer较早恢复PID24600的ACTIVITY_RESTORE_NOT_CONFIRMED不是本次新PID的首因。后续remote恢复明确空过滤器actions=0/categories=0/priority=0，Splash恢复仍拒绝嵌套Intent。未登录、未通过目标验收。

## 空Receiver过滤器、电池事件注册与请求未绑定字段

Runtime `30d46823e39d65caefe475d854cc0acdc72ea092e3f95ddf997368d5af09c990`。`receiver-empty-baseline-01` 原生19项（run activity-flags-f2c7721a61444f6b），`receiver-empty-runtime-01` 容器54项（run activity-flags-7e28f8b360494618）通过。无action/data的空过滤器可用零flags真实注册/注销，不充当通配，重复注销明确失败。电池LOW/OKAY、电源连接/断开四个非sticky受保护事件通过零flags注册，未声称物理电量变更事件已实测。既有实际两条广播完成回执保持，空过滤器未生成交付。另补Activity identifier/sourceBounds尚未纳入摘要时的明确拒绝，不能默默携带未绑定字段。

## 微信36与实际资源证明

`wechat-first-launch-36` 在关闭前返回EXPLICIT_ENTRY_NOT_ELIGIBLE，未提交关闭/新启动。`wechat-35-resource-inspection-01`核实root ticket af44e9b1-c10f-45df-bd10-907f7dc84f29已真实附着task2114，其空任务经生产回收接口取得TASK_REMOVED_CONFIRMED并退休；两个内部Welcome票据9eababe6-ca79-44ea-8610-782b5a938f91与9a769781-2bc8-4695-ae67-6a9f63002ad2仍PREPARED/task=-1/原进程DEAD。支持重复--close-activity-ticket参数，仅关闭逐个明确选择的UUID，不自动选择全部未知记录。

## 有限嵌套Intent数据与真实Parcel快照

Runtime `94edb852670afda4ee3841dbbc5e7d0af1cc22d62ce12d413223a97c8a3ced22`：`nested-intent-baseline-01`原生20项（activity-flags-b3b89b400ef443c4）；`nested-intent-runtime-01`容器60项（activity-flags-84e7c5e4464e4c20）通过。嵌套Intent和内部数组实际跨Activity保留，原对象修改不能影响快照；组件变化改变摘要，循环、ClipData、URI授权标志及未知Parcelable拒绝。真实Parcel复制保留平台管理元数据，未移除Android来源保护；未额外构造/验证跨UID creator-token授权场景。摘要升至v4，历史摘要不迁移或回退重试。

## 微信37：创建回执通过，整体存活失败

`wechat-first-launch-37`（control-a004e204e4c34918，PID2413，generation 5631820e7658e446e8145753cfa989b77132c1ac92de09c1b209c5dc32312dfd），两个精确Welcome内部入口均关闭成功。launch 691041e1-b2df-4a5a-916f-62afa582d8d6首次取得SUCCEEDED / activity-CREATE-callback-completed，设备夹具16项断言通过；但后台wc_srvinit_8随即在BATTERY_CHANGED注册处异常并主动exit。顶层验证正确标为失败（ENGINE_LOG_EVIDENCE_FAILED），不能把创建回执改称稳定首屏通过。后续恢复PID1848/2259另见COMPONENT_PEER_MISMATCH。下一轮新增原worker连续30秒RUNNING观察及现场截图，仍不替代登录/业务验证。

## Sticky原生基线首次假设失败

`sticky-battery-baseline-01`：真实电池查询通过，但夹具直接修改返回Intent后期待再次查询是独立快照，断言失败。此设备的框架缓存可复用返回对象；不能要求原生未保证的对象独立性。修正为复制后修改、核对原对象与再次查询均保留真实值，保留本轮失败证据。尚未将该构建的sticky实现标为通过。后续增加收到嵌套Intent后经真实startActivity/SINGLE_TOP再次交付的检查，避免仅数据读取通过却未验证实际转发。

## 真实Sticky与嵌套Intent再次启动

Runtime `d1ba4bcccb4b3b8becbe83d9fa8d671065e58af0343c1dd2d4e6c5e3bcc24416`：`sticky-forward-baseline-02`原生25项（activity-flags-776d0c80f431484e）；`sticky-forward-runtime-02`容器68项（activity-flags-928f686f84174b25）通过。真实电池查询/注册返回值，isInitialStickyBroadcast、goAsync/finish及authority中COMPLETED的系统交付都取得证据；没有改动电池状态。收到的嵌套Intent经实际startActivity/SINGLE_TOP转发，在原生和容器均命中原实例；ledger共7条真实交付（4 CREATE / 3 NEW_INTENT）。保持两条受限/不受限私有广播完成回执，无假空查询和伪造callback。此结果不代表微信或任意跨UID Intent授权场景已经通过。
