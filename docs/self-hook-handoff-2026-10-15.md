# 自研 Hook 替换：进度与 Agent 交接



## H25 并行集成即时点（用户已明确授权子代理，继续至全部完成）

- 用户因进度慢要求细分并子代理一起实施；已启动 storage_quiescence、formal_control、acceptance_plan，现均完成交付（仅formal_control正做最后映射验收器补充）。Root是唯一设备操作者，子代理没有ADB/安装/启动或共享build。
- 当前已合并Storage诊断patch：/private/tmp/tripoder-storage-parallel/storage-admission-diagnostics.patch。保持64次/1秒、+8..+20 PC窗口、原两页协议；新增实际attempt/elapsed/unsafe PC全计数、ATTEMPTS/DEADLINE/PAUSE/PREIMAGE/CLOCK/CONTEXT与context status/index。全量扫描发现上下文失效独立PAUSE协议失败，不误报PREIMAGE，471真实C函数mock合同通过，NDK3源/关键调用审计通过。额外已运行4IO热安装无进展保证，用户选择的是首条guest代码前固定Storage；不伪称H20压力pass。
- 已合并RuntimeNativeMappingEvidence+Bridge既有status的runtimeNativeMappings：Hook前捕获Host物理nativeLibraryDir，进程内读实际/proc/self/maps，PID/UID/physicalProcess/generation、完整maps摘要/字节/行数、核心全部映射rows/inode/device/canonical路径、实际core文件SHA/size、nativePaths、foreignBackendRows。读/身份异常失败，无新command/fallback。SDK212及纯Java mapping12、原293/15/85/117/policy295/startup8/typed45通过。formal_control两个validator已强化3个自有UID崩溃、安装前force-stop、HookUI/Agent实际worker25/25/2177启动原文与样本，并正补nativeMappings独立严格核验。
- H24 provider-identity-file-report Runtime b1948a3f（未归档时请立即归档，当前APK还在旧构建直到H25完成），nonce/pid小结果绑定已过、报告17975 bytes实际写出，但Authority聚合PATH_INVALID。代理确认旧canonical==absolute检查误拒可信Android宿主路径别名；真实旧失败分支没细分不能断言确切alias。已改P1Authority为可信getFilesDir canonical锚点+installed.data精确后缀逐级lstat目录/同UID，叶子lstat/O_NOFOLLOW/fstat身份/SHA/size/PID/UID与installGeneration核验。fixturereceipt加reportDirectory和Canonical仅诊断，不用输入选路径。5项路径反例+两个文件API37隔离编译通过。产品16KiB结果限制保留，文件handoff复用ProbeReports。
- **当前同时构建：self-hook-probe h25-admission-mappings-build.log（session66009）及Runtime h25-integrated-build.log（session48306），日志/session见对话。** 所有Runtime/Native构建输入现在冻结，formal_control只独占control-test及两正式validator，不影响Runtime。
- 构建完：核心新诊断一次main压力记录明确结果，不重复追幸运；当前核心范围相关独立检查/manager/capacity等按影响核验；Runtime provider-identity-integrated后优先p3和必要组件队列，完整p0/packages/p4/p1/p2，以及正式control/hook-ui/Agent normal+disconnect、最终制品/性能/文档。**不能在单阶段停止**。
- 细分矩阵由agent写docs/self-hook-acceptance-plan-2026-10-16.md及product evidence README（Root暂不改这两份以免冲突）。严格已有完整pass仅P0 60/packages159/P4 105/sources17；packages旧APK缺归档，要最终重跑。全P2部分136/218/providers206不是pass；H20main压力失败保留。

## H23 当前即时点：动态Receiver注销/claim竞态修复（继续）

- provider-identity-multiprocess run见该目录device-observation（APK ec1267c15354c1686285712551d9c9eb4f56702db2d0a97ed96e38a54e415a3f 已归档）。35秒观察器使真实multiprocess Provider全部通过（本地一次初始化、双PID/不同instance、引用确认）。随后PackageResources被真实main广播异常杀死，resultCanceled，不是刻意owned-uncaught日志；原崩溃明确RECEIVER_CLAIM_FAILED→RECEIVER_REGISTRATION_CLOSED，ReceiverConcurrencyChecks稳定receiver两线程40次注册/广播/注销暴露竞态。
- 原RuntimeReceivers.before两次本地closed检查与Binder claim间可发生unregister；权威已CLOSED时拒绝抛SecurityException，Android main将其作为callback异常终止guest。现Authority按真实注册状态给明确rejectedClosedRegistration，Adapter返回null（已有生命周期协议会不进guest、不记after，Android自身finish PendingResult）；未知registration/错误target/component/payload、静态ticket状态仍严格拒绝。没有catch任意异常或吞权限错误。
- BroadcastLedger.registrationActive区分ACTIVE、CLOSED和未知/未激活；注销同事务把尚未claim的PREPARED delivery标UNKNOWN，保留真实operation/ticket/returned=false/finished=false。已RUNNING callback仍可完成，迟到系统完成不制造guest成功；死亡与退休规则保持。
- provider-identity/providers含真实FailureDispatchChecks的预期原handler日志，crash classifier新增这两个phase仍须完整passed receipt+同UID/PID+精确线程/Throwable/全部stack；额外main fatal绝不豁免。主机7项包括新phase错误线程/失败receipt拒绝通过。SDK211及既有host293/15/85/117/policy295/startup8/typed45通过。
- PackageResources collector新增精确result request有效检查再completeActivity，避免崩溃CANCEL只显示缺pid。
- **当前build h23-receiver-close-build.log，session见对话。** 接着provider-identity-receiver-close --install，继续所有组件/p3/fullP2/正式control/hook-ui/agent审批UNKNOWN；产品b88160c3旧包须最终重建（未含此次修复）。
- 文档runtime/README已改当前自研构建参数和8槽能力，旧android/README标历史；adapters/design说明当前25/2177接线及受限验收，未将旧Gadget或早期证明当最终通过。当前Native仍H20，main压力IN_FLIGHT未通过记录不追认；实际product首次执行前Storage安装已在多轮数十进程通过。

## H22 当前即时点：冷Service观察器时限校正，identity独立继续

- providers-default-application run providers-f2c0428dd7bb4a5c / Runtime b789cf80c5d771003c8999f77325967c09d452da471e3ec6fa7ae5d6499bea49，206检查处停止。两空间完整testProviders（每空间真实本地/远程/重启/初始化循环/读写权限/UNKNOWN3保留/所有引用释放）已过；默认Application peer真实所有权/权限也已过，testProviderIdentity身份/私有delegate/元数据/预分发拒绝均过。失败在multiprocess fixture自己的`link.ready.await(10s)`，不是产品30秒deadline；真实辅助Service进程仍在完成自研冷安装，冷fixture总预算不能比产品30秒先到期。
- 仅fixture观察器改35秒（原产品30秒+5秒观察），产品启动上限、回执与权限不变；无预热跳过冷路径。新增`--phase provider-identity`仅原testProviderIdentity（包含multiprocess和完整PackageResources/system services/history/真实time tick），600秒整批；后续完整P2仍会跑全部，不用此独立模式替代。
- **当前build h22-multiprocess-observer-build.log，session见对话**，接着provider-identity-multiprocess --install。随后其它独立组件/p3/完整P2/正式control/hook-ui/agent normal+disconnect审批UNKNOWN和最终收口。不能把206检查当整批passed；原始失败保留。
- 正式Engine78d27d19已归档，但fixture新源码/摘要改变后必须重建产品再验收。当前Native仍H20；core main两次IN_FLIGHT压力准入拒绝记录未解决，不能冒充全部核心矩阵通过。实际首条guest执行前Storage已在本轮十多个进程真实安装。

## H21 即时点：Provider循环已越过，默认Application准入修复

- providers-map-scan run providers-10e63622f6364db5 / Runtime72b78f39 已越过真正冷Provider初始化循环（cycleB实际cycle=true，约9.4–12.13秒完整2177安装）和真实读写/observer/grant-gates，共56项处停止。新失败不是超时：provider-peer无自定义Application，正常Android默认android.app.Application被RuntimeSelfHookLoaders误判APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT，peer真实启动失败后上游PROCESS_CLOSING。
- 修复以已验签Artifact Manifest为准，Engine固定platformDefaultApplication；只在未声明或显式声明android.app.Application时，允许实际Class精确Application.class。选定loader仍必须归属制品且Class.forName(selected,false)精确解析；其它boot类/错loader/错解析/host对象均拒绝；自定义App仍必须制品Class。未增加替代Application或fallback。
- Hostloader新增默认类精确准入、其它boot拒绝、外来selected拒绝、解析缺失拒绝，15检查通过；bootstrap293/component85/boundary117/policy295支持108/typed45/startupDeadline8及SDK211源码通过。
- validator每轮新增保存自己UID/本轮时间的TripoderSelfHook/Ownership/Runtime日志与2177安装耗时样本；采样不冒充全部进程覆盖。
- **当前build h21-default-application-build.log，unified session见对话。** 接着providers-default-application --install，再其余独立组件/p3/完整P2/正式控制hook-ui/agent审批UNKNOWN和最终收口。Native仍H20（maps快筛，精确命中行原完整核验）；H20 core main两次存储压力IN_FLIGHT未通过，不能追认。产品首条客体代码前安装场景与p3必须真实通过。

## H20 即时运行点：maps优化容量通过，providers运行

- 当前核心APK `6ba7f7cf1b16acecdc382fb2b36aff9562691b3fc96a3445c8b2768c641c25c5`，build fe73d4a6-fe1e-4861-95e4-9257234c8767，source b05462886a377e82b6538c6353fe3ac77934b0efb3367e62a1f34b04eddb3d34。manager1523/15 run74f757b4-322e-40e8-9a2d-c2aaf854d730独立通过（新增1515项5.174s）；4096完整容量run905da96e-b5ea-4780-8d67-f71027aab088独立通过（8192enable/4096disable/8192original/132writer/全部4096owner移动），57.038s，上一H19同项79.081s。
- main两轮 ed0d9b22-4103-41c9-a2c1-b67fb79a7a1d /70afaba9-a228-4577-b854-5ae041cf6e8d 在fixedlibc激活返回IN_FLIGHT(6)，未发布页面，workersErrors0，没有Native/Java fatal。并发压力不能冒充passed，未重复追认为成功。当前逻辑有64尝试/1秒两个边界，是否过早耗尽64还没有实际elapsed/PC诊断。产品范围是首条guest代码前安装，后续实际产品/P3路径仍需通过；若要调整此边界须先记录实际quiescence/elapsed/PC，不能弱化安全窗口或改PC。
- 当前Runtime `72b78f39b5ee4aa334ab5026166af0bc80148fc985dd86d2902b8a633161b5de`、正式Engine `7093764b52622c35435f0a01016443ae3158556550436ad12e744e13d159c544` 归档完成。
- **当前 `providers-map-scan --phase providers --install` 正在运行（unified session见对话）。** 不要改构建输入直到结束/核验。随后剩余组件/p3/完整P2/正式control/hook-ui/Agent normal+disconnect审批UNKNOWN、当前核心映射改动的其它相关回归、最终制品文档收口，目标仍未完成。

## H20 当前：实时maps解析成本优化（继续，未验收）

- providers-target-index APK1c390a7a run providers-6baf995bdfdf4bd8 仍在cycle A总30秒到期：A13.994s、B13.805s，B25/25 ready后仅0.55秒A就到原deadline。Java索引已将17s降至约14s但未修复该场景；失败原始bootstrap-log已保留。
- Native art_access.mapped每次扫描/proc/self/maps的所有行均sscanf七字段。改成strtoul先筛实际地址区间，仅包含目标的行执行原有七字段/权限/device/inode/offset完整核验；每次仍重新打开实时maps，没有映射缓存、没有弱化准入。准备成本继续下降才可能在30秒内完成，不增加总budget。
- **当前build核心probe h20-map-scan-build.log，session见对话**。新Native源码需新核心独立回归：主main实际存储/线程/ART、完整capacity、manager/coverage、构造/JNI合适回归；再重建Runtime/providers及后续完整组件/p3/正式控制/Agent审批UNKNOWN。新source改动后H19的当前产品包46f54167和Runtime1c390a7a均不是最终包；已归档历史不要追认。

## H19 当前运行点：索引核心回归已通过，providers继续

- 当前核心APK `4cbcc42f641527ab66efed0f2ab1fea1964baa4cfe7d3affb8111e08ba98dcfc`，build `0e88c4dd-31b9-4b44-8010-6a9e24313ad8`，source `20b68fa6e4254374e2bca00d331b8f1fd9ba2c0158409cac55fe481805713d79`，artifact已归档H19。方法HashMap/manager Set优化当前构建五组独立核验通过：manager-all15/1523 run2f62ee67-f23e-40ff-968b-213751ebb824；4096容量run ee6cf8be-de9f-467a-bea6-414610c28c25（8192enable/4096disable/8192original/132writer/4096owner移动）；构造26 run e4e26901-938d-47ae-9ada-1bb2db6b71bb；JNI31/396native/384并发 run7a60fd02-49ab-4401-9403-d1037d4c876d；coverage同名双loader/动态caller/24轮观测两Class移动/37GC run630ad2cb-6dab-4cc2-a35f-5edf9b8e7001。独立manager新增1515项6.0975秒，不能拿设备热状态不同宣称比H15更快；实际整包另测。
- 当前Runtime APK `1c390a7a382f43a532e7dc9fbcab960b93a6a12611ce0851a3bd49d5bdc8d181`，正式Engine `46f54167cd4c8453043272a0b56d497f1cde3b42d719ee2b867454e851a33a78`，均归档；正式尚未安装验收。
- **当前正在运行 providers-target-index --phase providers --install，unified session见对话**。不要改构建输入，待结果检查冷依赖能否在权威原30秒完成；不能预先宣称性能改善/Provider通过。
- 后续继续其余组件creation/failure/service-startup/service-passive/activity-flags/entry/notification、p3、完整P2、正式control/hook-ui/Agent normal+disconnect审批UNKNOWN与最后制品文档。用户要求全部完成再停止，目标尚未完成。

## H19 最新：启动成本的Java索引优化（继续，未验收）

- providers-shared-deadline run providers-5abf7b3092ac4a00 仍在A/B冷依赖总30秒耗尽。完整时间线：client13.38s、remote16.98s、restart16.88s、cycleA17.01s，cycleB尚未完成时A原截止点到达；没有放宽总30秒。6e991964及正式3cc6443c制品均已归档。
- 单一deadline保留，确实消除了独立8秒/10秒计时，但不足以解决17+17秒启动成本，不能宣称已修复Provider。当前权威等待图/无fallback/UNKNOWN边界未改。
- 发现ManagedHook.ownsTarget与validateTarget每次线性扫描所有Entry，以及FixedManagerInventory.permitsHook对Family列表线性contains。2177项产品在解释策略下形成平方级Java反射比较。改为Executable->Entry HashMap索引与已验证Family方法Set；保持实际Class身份/完整签名/GC Java根，所有Native准入、flags、writer与full preimage核验不变。不是raw heap Class缓存，不减少固定2177入口。
- **当前核心probe build h19-target-index-build.log，session见对话。** 先完整4096、manager1523、构造/JNI当前Java注册语义回归；再重建Runtime跑providers。之后其余组件/p3/完整P2/正式control/hook-ui/agent审批UNKNOWN及最终文档制品收口，目标未完成不要停止。

## H19 当前即时点：统一30秒权威启动截止点（继续）

- providers-contract APK f4e648e4 run providers-c76da1debb0f4784：精确missingType=null已通过；真实stable/unstable、死亡后旧client拒绝、新进程同数据通过。随后循环Provider A等待B的自研Hook安装时，RuntimeProviders.ready旧8秒计时触发PROVIDER_INITIALIZATION_TIMEOUT（此时B还在CONNECTIVITY），不是循环检测漏报或Native崩溃。
- 已将原30秒总启动上限表达为权威Slot分配时创建的startupDeadlineElapsed，认证bootstrap传Engine。Supervisor等待和watch、Engine构造/Application、Provider就绪都用同一剩余时间；不再有内部8/10/15秒抢先到期，也不在每层重新给30秒。READY Future即使deadline过去仍可立即读取。缺失/非法deadline拒绝，无兼容分支。
- 新纯Java StartupDeadlineTest8项（不重置预算、安装后剩余、过期、非法值）通过；当前host293/11/85/117/policy295支持108/typed45，SDK211源通过。
- **当前build h19-startup-deadline-build.log，session见对话；接着providers --install新证据目录，再剩余组件/p3/完整P2/control/hook-ui/agent审批UNKNOWN。** 正式产品a5553741已归档但不含这次deadline修复，必须后续重建；勿把旧包当当前最终产物。用户要求全部完成再停止，目标未完成。

## 最新运行点 H19：P2推进至Provider旧断言，继续实施

- `p2-component-reservation` run `p2-3b8b2ff1d47f4912` 在218项检查处停止；先前来源ANR已越过，完整来源/结果/alias/churn/singleTop/death/services都继续执行，未观察Java/Native fatal。失败为`provider-unknown-authority-rejected-space-0`。
- 已确认是验收源陈旧：RuntimeProviderBroker和runtime-provider-design既定契约明确已认证空间内无声明返回无capability，getType/query=null，写操作IllegalArgumentException；packages159中MissingProviderChecks已经逐API验证。旧Providers fixture仍期待getType抛任意RuntimeException，与当前契约冲突。只改此断言为精确null，没有修改/放宽Runtime路由或权限。
- 新增`--phase providers`执行原本完整testProviders+testProviderIdentity，先隔离后续真实死亡/循环/写操作UNKNOWN/身份/宿主decoy检查。修正sources phase也核对p2Fixtures摘要。当前build `h19-provider-contract-build.log`，session见对话。
- 下一步providers独立、其余creation/failure/service-startup/service-passive/activity-flags/entry/notification，p3，完整P2最终重跑，正式control/hook-ui/agent normal+disconnect，最终制品/文档收口。目标未完成，不要停在单阶段。
- H18正式Engine `5ecd8c1fff076d23c3eabaf5999dd1723b75e78d50b5cd3f07b2607398c601c8` 已构建且当时所有源码摘要一致、纯自研包装审计通过，尚未执行正式控制验收。此次fixture重新构建后须重建正式产品以绑定当前fixture摘要。

## H18 当前即时点（继续，未完成）

- sources-request-binding在独立sources阶段仍收到当前唯一request=201的RESULT_CANCELED，故不是先前reparent残留。保留唯一request绑定修复，但不把它当业务修复。
- **真实系统事件已确认根因**：11:56:11.631 am_anr来源slot0，FocusEvent(hasFocus=false)等待5000ms；随后am_kill `user request after error`。来源Main.onResume在同步startActivityForResult时等待RuntimeBroker.routeActivity里的processes.ensure，目标::sourceremote当时才安装到CONNECTIVITY。源主线程被远端2177入口启动阻塞，非Java异常。原始精确自己包事件存sources-request-binding/owned-system-lifecycle.txt。
- Root修复：Activity路由复用Service已有reserveComponent，只做权限/不可变目标分配/ticket/回调准备，然后让Android真实Activity请求启动目标；不在来源UI等目标Application或Hook。目标仍在Guest首条代码前完整25项，cold provider/claim流程不变，没有第二队列或后端。
- ExecutionContract.issueCallback允许向未执行的ALLOCATED不可变目标保留capability；来源必须live、目标不得ended、空间/operation owner/期限仍核验。consumeCallback仍要求实际attached目标pid/uid/generation，未attach不可消费、错UID/错PID拒绝、单次消费和死亡撤销不变。route回调窗口60s=最多30s启动+原30s交付，仍低于5min上限。
- 新host ExecutionContractTest覆盖保留目标不启动、未attach拒绝、错误真实UID/PID拒绝、正常attach后消费、一次性、dead不可重发，当前host-result.json已通过。
- **当前正在build h18-activity-reservation-build.log，session见对话**。下一步run sources独立验证真实远进程启动和来源主线程无ANR，再完整P2、其它组件、p3和正式control/agent审批UNKNOWN。当前最新正式Engine仍旧build产物，要在最后源稳定后重建。不要结束或标Goal complete。


## H17 当前即时点（继续，未完成）

- 完整P2-first运行136检查（容量8、并发ensure、重启、task modes、singleInstance、reparent及真实系统回执）后，ActivitySources收到RESULT_CANCELED无checks，collector提前completeActivity导致JSON异常。没有Native/Java fatal。不能据这份失败判定ActivitySources成功，也不能确定是runtime还是旧回调混淆。
- P2旧harness所有启动都用requestCode23、共用一个queue。现改每次launchOwned分配唯一requestCode，普通结果按实际Intent绑定的request精确等待；不匹配结果保留在unmatchedControllerResults，不做错误ACK。EntryFences特意并发等待两个回调的原始queue路径保留。onActivityResult记录实际controllerRequest；ActivitySources先核验有效对应结果才completeActivity。
- 新增sourcesOnly独立阶段，隔离此前reparent残留回调。**当前运行 `--phase sources --install`，evidence sources-request-binding，当前APK `0290bf505de3b1607466e4ca9075e56e52b6b65a5fa15c29865c30653b10c4ea`，session见对话**。期间勿改输入。若仍canceled，再沿真实生命周期查，不跳过断言。
- P2整批collector预算改1200s（每个单操作/启动超时没变）；它包含数十进程初始化，不能用原600s整批预算替代单次产品性能门槛。
- 当前工作台正式产物cba25a5d（先前build）未运行；其源已因新的harness/fixture源摘要变化而需最后重新构建。正式控制/审批/UNKNOWN、其它组件及p3仍未完成；不要停在P4/P0/容量里程碑。


## 当前即时接手点（H16；完整目标尚未完成，继续）

- **P4真实Java/Native完整105项已通过**：APK `8dcb82ba1183956bc6caa23d95f7c0e19ede7be05e7b1047d4d5434660f521b2`，run `p4-9dac402cbf73431f`，evidence `p4-live-admission`；实物已归档。九Java精确重载、源码prepare/approve token/实际激活、4并发热栈、before/after/replace/原Throwable、64位精度、递归、8线程、参数快照、DRAINING/停用、重启不自动恢复；Native/mixed实调、真实卸载/新generation、prepared失活、错误无原调用、活跃模块引用>0及两次OS退出均通过。
- P4系统ExitInfo已实际验证：script错误真实SIGNALED/9；活跃dlclose真实EXIT_SELF/198。validator按同次PID/UID/process/time绑定系统记录，没有补旧Gadget日志、没有接受Native crash。原始日志保留，预期失效不当成功业务结果。
- `p4-system-exit`在第68项失败仅因为未加载模块+错误BuildID同时输入，错误优先级与原合同不同；已将Native选择顺序为：已验证Artifact目录→真实live唯一模块→完整GNU BuildID/文件/符号核验→最终活性复检。无降级ID/卸载模块准入；P4-live-admission全过。安装validator先force-stop自有probe再更新，避免AMS恢复上次无runId验收Activity造成陈旧额外执行。
- **当前完整P2正在跑**：evidence `p2-full-first`，`validate-runtime-probe --phase p2`（600s），unified session见当前对话。正式workbench/engine当前源也在build（h16-live-product-build.log）。测试期间不要改输入。
- 后续仍必须：P2全流程（若失败按真实错误修），creation/failure/service-startup/service-passive/activity-flags/receiver/entry等未被完整P2覆盖的独立阶段；p3实际存储；正式workbench-control/hook-ui/agent approval及UNKNOWN；最终当前源APK/源码/文档/性能证据收口。不要在P4通过就停止或标Goal完成。


## H16 即时点（继续，完整目标尚未完成）

- 当前Runtime APK `afe99d0a49cbe2aacd28ca11e525365c79d792406db38a61e24182d8f439baaf`，正在跑`p4-system-exit --phase p4 --install`。正式workbench/engine同步构建中（h16-product-build.log）。两session见当前对话。源/Native/perf保持H15；测试期间不要改输入。
- P4-first确实正确拒绝globalThis，返回DRAFT_UNKNOWN_IDENTIFIER:globalThis；失败仅旧Gadget验收期待DRAFT:，已将断言收紧为具体码。
- SelfHookControl.status在未激活且prepared的真实evidence失活时清除准备/evidence并discardObservation；不是等待模块事件时伪造有效准备，activate仍做原本完整核验。新增host回归（卸载后status为IDLE且不发布），脚本合同**676**通过。
- P4退出验证改用真实ApplicationExitInfo：P1Authority probeProcessExit只接受同UID、已DEAD且PID明确的session，按pid/realUID/physical :slot[0-7]/本次时间筛选系统记录。P4 native-failure必须SIGNALED(2)/9，active-unload必须EXIT_SELF(1)/198；保存pid/uid/process/timestamp/reason/status。validator保留全部原始crash观察并核对系统记录，取消依赖旧Gadget HOOK_PROCESS_TERMINATED文本。Native module inventory展示核心真实activeReferences，卸载前必须>0。**完整P4尚未通过，不能宣称退出验收完成。**


## 当前即时状态（H15，继续至全部完成）

- P0 60项完整通过（56672c49，p0-single-observation），packages **159项已通过**（最新Runtime APK `c2e094b743634307ff71803cd2512915fcf6647c02decfa159363411cacacf5a`，evidence packages-policy-codes，run packages-6bd9279812954cd3）。真实反射/包接口/签名/元数据/缺失Provider/10manager门面含通知、Power、NetworkStats/Traffic、Wifi、Shortcut均进入产品代码；未知能力拒绝保留。
- H15当前核心d990e6d6完整4096容量独立通过：run d37cd302-4103-4429-9bd5-d4c7162006c8，8192 enabled+4096disabled+8192original、132 writer events、全部4096 owner移动；78.807秒是GC/调用/报告全测试耗时，不是启动。构造26、JNI31、manager1523/15见下。
- **当前正在跑validate-runtime-probe --phase p4，evidence p4-first，unified session见对话**，不能改构建输入直到run/校验结束。Native/Ctor/GC逻辑仍保持已验收源。
- 后续必须跑完完整P2和独立creation/failure/service-startup/service-passive/activity-flags/receiver/entry、存储p3，真实product/control审批UNKNOWN及最终当前源Engine/Workbench构建/验收。不在此处停止。
- 已预读到P4旧验收最后用Gadget时代`TripoderHook:HOOK_PROCESS_TERMINATED:<reason>`日志确认退出；当前SelfHookPlatform Java故障failStop直接killProcess，linker活跃卸载failStop直接sys_exit_group198（critical section不能JNI/log/alloc）。若走到这一步，不要伪造旧日志或放宽致命信号判断；应以本次PID/generation/UID、真实ApplicationExitInfo及相同库活跃引用/原调用audit证据绑定退出。**此调整尚未实现，先看实际P4结果。**


## H15 当前点（持续实施，未完成）

用户要求全部接入/验收完成再停止；继续原目标。不要将P0或容量通过视为完整完成。

- **P0实际完整通过60项**：run `p0-b7f3c9c46b2345b8`，证据`p0-single-observation`，APK `56672c49824a297c927b081cb8f70c4ff0917d3f4f9918428d34519690fc6bbe`，实物已在product artifacts/56672c49。六个真实进程25/25、2177绑定，双应用往返/重建/JNI/数据隔离/重启/旧回调/UNKNOWN丢ACK对账都过，无Native/非预期Java fatal。
- 前一P0 `p0-config-read`已真实source-received SUCCEEDED，失败仅旧断言期待旧receipt字符串。现断言验证完整`source-onActivityResult-completed;resultCode=-1`。其它错误不吞掉。
- 新物理验收名SelfHookTaskP已实际释放旧永久退役租约。只读before 2610 DEAD+1ATTACHED，8旧component leases；after 2613 DEAD、只剩本轮LIVE attached的新名lease，旧名字未再发放。对比快照retained-before/retained-after；未清库/删operations/tickets/data。
- Activity恢复失败为DeviceConfig LIST_config被SettingsReadProvider拒绝；增加明确只读LIST_config、arg必须null，仍拒所有设置写命令/跨user。物理resolver在pending逻辑视图前捕获，未加alternate backend。P0后续恢复/重建已通过。Inspector缺runId时直接finish，避免更新APK后AMS自动恢复测试Activity造成无意义fatal。
- **减少重复Native Method观测**：新增`tp_art_method_observe`一次JNI Method地址/可读映射校验+一次mutator snapshot同时返回稳定ArtMethod地址和view；原snapshot委托它。OriginalStorage/ManagedHook不再对同一target/helper重复扫描maps。保留所有Class/Method/loader根和flags/backup/full-batch核验，没有缓存raw heap Class键。
- 新核心APK `d990e6d6eafb29d1f9d2e4e88574b816071ca55b69238eb24d75f4d384e7fb86`，build `97dd480f-3ea4-46a6-bcb8-216396786643`，source `e61979d636d0635bfb2e2955628eb0b6b2501c1c983b3299d9d52e830d5af1c6`（H15 artifacts/d990e6d6已归档）。构造26 run c085bdbf-1bfb-4895-9713-d97e0afa4fe9，JNI31/32参数/396native/384并发 run fe1d552e-33d5-4664-9733-670086a94c31，manager1523/15 run 6e923005-d978-477f-9bf5-84e301d809e3，完整4096容量 run d37cd302-4103-4429-9bd5-d4c7162006c8，均已run通过并立即独立核验。
- manager新增1515项安装5.4199s，prepare5.3635s/publish33.39ms；旧同1523为8.971s（H10），不把不同数量1270的7s混比。新完整Runtime六进程启动11.326/13.126/14.207/14.618/14.752/14.837s，仅记录真实波动，未宣称最终性能收口。
- `packages-single-observation`前103+断言进入真实包管理/反射/元数据/通知通道后失败，原因policy用Family枚举复数NOTIFICATIONS/SHORTCUTS生成错误拒绝码。既有公开合同是NOTIFICATION_CAPABILITY_UNSUPPORTED/SHORTCUT_CAPABILITY_UNSUPPORTED。已修复prefix并加两个具体合同断言（host policy295，SDK210+旧293/11/85/117/typed45、脚本671通过）。不能把此失败验收当pass。
- **当前Runtime APK c2e094b743634307ff71803cd2512915fcf6647c02decfa159363411cacacf5a 已build，正在跑 packages-policy-codes，命令含--install，unified session见当前对话**。期间勿改构建输入。接着完整P2/各独立组件phase、P4/真实审批UNKNOWN、正式workbench-control和最后文档/构建/性能。当前正式Engine `24310b56…` build包装通过但缺最新policy prefix修复，尚未验收/不要冒充当前源的最终包。


## H14 当前继续点（2026-10-16）

用户最新明确要求“先解决存储 Hook 卡点，把剩余接入和验收全部完成后再停止”。继续执行，目标未完成，不要在里程碑停止。

- 存储已改为唯一的BTI保留绝对尾跳：六字固定syscall leaf保留BTI c，后4字 LDR x16/BR x16/literal。没有near relay、没有旧方式fallback。`libc_entry_audit.py`检查157336条可执行指令、9294符号、1609重定位，没有进入覆盖窗口的静态引用；独立NDK `check_libc_absolute.py`核对20字节机器码。
- 宽补丁首次并发run `513d39bb-0ec3-4ab7-8aa8-f7279563c8f1` 失败并保留：线程停在旧SVC返回PC+12，恢复新literal触发SIGILL，`libc-fatal.txt=FIXED_LIBC_RESTORE_FAILED`。不是BTI目标失败。随后实现`fixed_commit_batch(epoch,observed)`在每轮全部线程暂停时检查所有真实PC；PC在entry+8..+20时不修改、恢复线程让旧调用自然完成，再取新闭包快照。只在完整安全窗口一次发布两页；最多64次/1s，明确IN_FLIGHT拒绝，不重放调用/改PC。暂停临界区binary allowlist新增真实tp_park_context调用。
- **H14存储独立验收已通过**：APK `7ba280b7f5ac1b50d2532feb26317a19d7f93dbbbd55df4afc59e72c25458cf8`，build `a237484e-07c9-4d1d-90fb-400ef93602d9`，source `fc9cab303731ef8cb070094c49de08a2b84d1e5fb02c60756223b26e542d5fc9`，run `f4f09a4b-8eae-4e20-8a86-cd8c4abdd84f`。24安装/恢复检查+24真实IO规则、408986并发调用、40精确修改字、2原文件页恢复，verify-self-hook-probe独立通过。ASM oracle与实物在H14 artifacts/7ba280b7。同APK earlyApplication14/8阶段 run `419c1b63-500e-4543-9786-dab6e44f1146`独立通过（最新prepareOwnership/beginView分工）。
- 完整Runtime已真机安装成功 **25/25、2177绑定**（无Gadget）；每进程约15–15.7s，尚非性能收口。首24阶段通过后发现HOOK_CONTROL对普通无GNU BuildID的JNI库libp0fixture.so错误地全进程拒绝。修复边界：`Inventory.path`只接受已验证Artifact的canonical文件；完整ELF/BuildID检查延后到已加载模块展示/目标选择。无BuildID模块展示UNAVAILABLE并拒绝Native selector；**没有合成ID或降低Hook准入**，允许普通JNI执行。
- 修复后的产品Native31（4导出/2激活/400foreign） run `c86a580b-33d2-4fee-b787-f15c5cbfeb87`，混合19 run `ff71247e-930a-40a3-a42f-80ad0c1db3ee`均独立通过。APK `5380c2d368fd46fa59956c7693fbc698e6e83dfadc9aff0a28732a76b6f048e5`，build `45499d08-d141-4bcd-bf76-92819ee1376b`，source `804d272ce72b563be5bc8eefa7295a1538837559812896bf984ad154c697bdde`，已归档。
- P0实际进入Activity前又发现warm入口重复走cold bootstrap provider，P0自己的Broker与:authority争夺writer。RuntimeFactory在已有Engine的restoreActivity/Service只等待构造，后面的Engine Broker claim继续严格验ticket；cold仍走原provider。
- 死亡收口现在统一ProcessSupervisor：直接allocate也走claimComponent；去掉RuntimeBroker.attachPeer第二个death listener；Launch.peerDied用deathObserved防重复通知，先持久化死亡后release/disconnected ACK。SDK210及host合同/脚本671通过；真实cleanup仍随P0等验证，勿仅据编译宣称完成。
- `p0-component-owner`（APK f6dd2fbc）失败为PROCESS_CAPACITY_EXHAUSTED。用新增test-only `RuntimeStateInspectionActivity`以SQLite OPEN_READONLY读取现有DB（不构造authority、不恢复、不清库）；第一版全sessions达2000限额拒绝，后改按实际资源引用读取。**已核实2610 DEAD+1 ATTACHED，八个旧Activity组件租约占满槽；services/broadcast/provider open均0；实际仅Inspector任务/进程。** 主要为旧P2/entry/failure验收的未确认入口，不能据任务列表缺失释放。快照 `retained-before/read-only-state.json`，对应APK `6c6d3e9f394796c5d7810ac34da45a10d8a8c80f2138d3940d30ec8a69cfec9c`实物已存档。
- 正在验证**验收APK固定物理组件新命名SelfHookTaskP**：generator输出RuntimeComponentBuild的精确prefix，RuntimeActivitySlots只接受当前prefix并永久拒绝旧TaskP；工作台正式Engine仍TaskP（不改旧生产任务名）。RuntimeRetiredActivities只有旧session DEAD、所有ticket RETIRED、全部leased实际名字在Manifest缺失且factory永久拒绝后，才删除旧物理component lease；tickets/operations/data全部保留。不是清库/扩大槽位/复用不确定身份。
- **当前APK `44b50fe44a154817d4efc66b12af754e73059f1ff39e8b4ea3990e40038bbca0` 已构建**，正在跑`validate-runtime-probe --phase p0 --install`，evidence `docs/evidence/self-hook-product-2026-10-16/p0-self-hook-pool`。unified session见当前对话。完成前不要改输入。下一步看P0业务/cleanup，核对升级前后tickets/UNKNOWN历史未删、旧物理名拒绝/资源释放，接着P2/各组件、P4真实Java/Native与UNKNOWN/审批、实际workbench-control及最终性能。不得结束或标Goal完成。
- 两个核心构建命令仍显式三个platform binaries；所有shell顶层rtk。设备仅自有d861a4fa；不root，不清数据/缓存，不碰第三方。


## H12/H13 当前状态（2026-10-16）

用户最新询问当前进度；已说明核心验收与完整产品验收的区别，继续原目标，未完成不标为完成。

- **H12 固定接口真机独立通过**：APK `9d68937481d60f55baba550e7ffc72e7ed1e81b9f9fbc78ed42e18fdd4bd7ae7`，build `d7496290-51d8-4f39-ab51-9a347f889911`，source `3e700e30da947be1acc8c14df9716437793cf6086c4f1bdd0ce2f6391411f08f`，run `722f1a92-5bd2-4c28-8cf0-8d33c6873178`。46访问器、17安装检查/652固定入口、10 manager门面；实物H5 `artifacts/9d689374`。这是physical passthrough夹具，不是实际Engine路由验收。
- **产品源代码已切换**：`RuntimeSelfHookProduct`完整25项真实plan；同一`RuntimeManagedInstaller`用于固定组件/manager/Activity/包管理。NETWORK_STATS含Traffic62，WIFI_P2P含Channel.close，HOOK_CONTROL接真实复合Platform与现有Authority控制链。Engine加载`tripoderselfhook`，删去Gadget等待与重复packages.initialize。没有保留替代后端。
- Application安装职责已拆：Framework阶段准备固定成员，Ownership阶段捕获真实物理所有权，Reflection阶段真实准备并发布pending view及两入口Hook。Native `prepareOwnershipNative`和`beginViewNative`分别一次；旧夹具调用同步更新，构造前14项需新构建回归，不能用旧证据追认新API。
- `self_hook_native.py`构建产品专用SO：只链接core及一份io_adapter/path_policy和新`self_hook_storage.c`，不链接probe JNI/TP_SELF_HOOK_TESTING。模板/固定平台契约与产品暂停临界区二进制审计已过；CPP visibility设hidden以去除意外vtable导出。`check_park_binary --product`只排除三个明确位于probe源码的函数，保留全部产品关键函数要求。
- `build-runtime-probe.py`/`build-workbench.py`都只包装一份`libtripoderselfhook.so`，包装清单严格核对；产品receipt记录selfHookNative/fridaInputs空及包内去依赖。源码SDK209、主机bootstrap293/loader11/component85/boundary117/policy293(108支持)/typed45通过。**包内无Frida不等于产品验收通过**。
- 首次完整Runtime APK `fe3f2db6c41409375905795d640fe744cf13d4e5b5aa7e0d37c156eb9999fbeb`已构建并运行p0，失败run `p0-dce7527cedac4ae0`，证据`docs/evidence/self-hook-product-2026-10-16/p0-first`。实际异常`FIXED_RUNTIME_HOOKS_NOT_PREPARED`，在客体之前拒绝，已补Framework阶段FixedComponentHooks.prepare/FixedRuntimeHooks.prepare/FixedManagerInventory.inspect。同时复用FrameworkAccess.prepare已读declarationCache，避免整合清单重复消耗Native8192预算。**新修复正在重建，尚未验收。**
- 首次workbench Engine APK `46c04c35714439afda4321a540d251d078446387f614f9621d2b13b5958a5f31`编译/包装成功，尚未安装验收，且缺上述最新初始化修复。不要部署旧包。P0失败cleanup另暴露已关闭authority后异步peerDied写库的既有问题，证据保留，未修。
- 当前重建命令使用三个显式`--platform-libc/--platform-art/--platform-linker`参数，unified session见当前对话。测试期间勿改构建输入；run后立即独立验证/存档。后续真实P0/P2/P4、首次Application/factory身份、各manager路由、审批UNKNOWN与最终性能和完整product包验收仍未完成。

更新日期：2026-10-16。工作目录：`/Users/like/code/tripoder`。


### H13 最新复测结果（覆盖上面的“正在重建”）

当前没有构建或设备测试在运行。所有完整P0均尚未通过；完整产品目标未完成。

- 初始化顺序修复已经进入实际Runtime：前11项真实安装已执行至PACKAGES完成，未开放客体。第12项STORAGE准备阶段失败，后13项尚未执行。错误不是暂停线程超时：`prepare=3(TP_FIXED_LIBC_RELAY), relayStage=1, relayErrno=12(ENOMEM), activate=1未尝试, pause=0/observed0`。
- 先保留单一近地址分配规则并提取为`native/near_address.c/.h`，由ART owner leaf、linker notifier、Native export和fixed libc共用。扫描真实maps的127MiB双向完整gap，只做一次MAP_FIXED_NOREPLACE精确占用；拒绝竞争/异常地址，不增加后端。fixed libc旧的仅向下96MiB搜索已移除，但仍复现准备失败。因此**双向搜索并未解决完整Runtime的存储接入**，不要宣称修复成功。
- 新增fixed libc准备阶段、errno及真实pause snapshot诊断；snapshot零初始化。完整产品Native编译、全部固定平台/模板/产品暂停临界区二进制审计仍通过。
- P0记录：`p0-second` APK cad9c583，run p0-2845586d3ed04e10；`p0-storage-diagnostic` APK814f7a7f；`p0-near-address` APK9a0fb1f2；最新 `p0-relay-detail` APK `87ae66c7d63786381b1241213cd8a2ce3d9f59468b703a728e1bb43d110836e8`，完整runId见该目录device-observation.json。全部为失败证据，实物均归档在product证据artifacts。最新bootstrap-log.txt按自有UID和run时间留存。
- 后续建议先去掉**固定libc替换对近地址跳转页的结构性依赖**：这十个精确syscall wrapper不调用原wrapper，已有完整两页原像与一次暂停发布，可研究保持BTI后的4字absolute LDR x16/BR x16/literal，不必为存储另造原调用trampoline。**尚未实现，须先证明固定入口20字节窗口的符号/重定位/直接入边及ABI/BTI、再更新探针和独立页字节核验，不能直接冒充支持。**
- 已做只读初查：固定libc全部SHF_EXECINSTR段157336条32位指令中，B/BL、B.cond、CBZ/CBNZ、TBZ/TBNZ没有直接目标落入十个`entry+4..entry+20`窗口。还缺符号/重定位、独立汇编与设备原调用/并发/恢复证明。不要只据这一次初查实现宽补丁。
- P0失败cleanup的AUTHORITY_CLOSED仍未修，原因待收口失败startup的真实death回执/重复peerDied后再关闭Store，不能吞异常。正式workbench Engine上次46c04c35包已经旧，不要安装该包冒充最新源验收。

## H9/H10 当前接手点

本节覆盖下面旧快照。用户要求全部完成后停止；已明确告知产品仍加载Gadget，尚未完成Engine25项/审批UNKNOWN/最终切换。

- **4096完整预算已真机独立通过**：APK `6658d058…`，build `1eb3bcde-ca1b-4e5d-b8dc-4e2a11677ae7`，source `1cb796a4a5ff1d6f0d85e60318555301cd3ed8175c8b4682cf029c732456c2e4`。run `667012a0-8db2-4671-95ec-97dcab0e643e`：8192 Hook调用、4096停用调用、132真实writer事件、32声明类、全部4096 owner根观测移动，109.942秒含GC/逐项报告/调用，不是启动性能。
- 同APK constructor26 run `dd17c693-e017-4943-9b95-96c48e49635c`，manager1270/15项 run `370ef0c9-6feb-4364-982d-550816d305a3`，JNI SDK31/396原生调用run `4404bdea-6c9e-4f18-bb92-829cb71d35b1`均独立通过。manager新增1262项7.166秒，prepare7.117秒/publish28.86毫秒，**没有比旧7.041秒改善的证据**。FastNative owner比较是已验证状态转换调整，不宣称性能达标。
- helper host oracle保持79/36，边界63/64/4095；公共scalar16/200/240帧和Native入站1024未扩大。修复helper checker读取旧generated DEX的问题：只重建其自有generated目录，禁止混解旧边界。
- **H10新代码**：FixedManagerInventory/Family扩至10，新增WifiP2p82、NetworkStats36、Notification104、Shortcut31，总1523 instance targets；完整声明SHA由实际F7 inventory派生。相应Bridge的4个工厂占位已替换为同一RuntimeSelfHookManagers/FixedFramework后端；新增 typed routes `RuntimeSelfHookManagerExtras`，不新增业务状态。
- ManagerFrameworkAccess增加4固定ctor、6 Wifi feature/5 notification property、通知channel/group identity值操作。Native字段增至24：Wifi.mService、NotificationGroup.mId/mChannels。固定类仍49。native字段static位表缺3项已在编译static_assert发现并修正。
- SDK201源码编译，bootstrap293/loader11/component85/boundary117/manager policy293（108支持项）/原typed45通过。新增4路由尚缺设备完整语义验收，不把旧typed45当成它们的覆盖。
- **当前APK `99b5c5fa…`**，source `e94e350f390adc218b862fb968aa2fc700f2e68396761c8427acfc135d552b2e`，build receipt内有buildId。已install，自有product-framework **session46896** / run `d57633fa-c4a0-4af3-8fa2-9cb476f0f257` 正在跑。期待38访问器检查/10门面（新增4Ctor+2通知身份）+16组件检查/45真实入口。完成后立即verify-self-product-framework→manager-all（期待1523/15）→verify --all-managers；勿在run和独立verify之前改输入。
- 新manager所有“排除的null记录”只作diagnostic，不再把clinit/copied/runtime记录计为方法权威；精确Methods/Constructors hash、declaringClass、abstract/native计数继续严格校验。没有替代后端。
- 只读agent正在梳理Activity/Package所需固定字段/隐藏调用，输出将是 `/tmp/tripoder-runtime-field-contracts.json`；无设备/仓库编辑权限。接下来完成Actor/PM/TrafficStats/ChannelClose/UI/Failure的固定准入与真实批次、单一产品Native库IO接入、实际25项Engine plan、真实控制审批UNKNOWN和性能。不要给plan填空installer，产品仍未切换。

## H8/H9 当前接手点

用户仍要求全部完成后停止；刚询问进展，已明确回复产品仍加载Gadget。Engine完整25项、审批UNKNOWN、最终性能/去依赖均未完成。

- **45个F6固定组件入口已实际安装验证**：APK `78590e09…`，build `713c61be-2354-42af-bea4-2856470f8e65`，run `4c29f726-7aa9-4c45-b330-e44aafd21300`，source `9df64101dbd4c08027a0a2e32874c000c538216d6b822e9bd23b9315feb5bd3e`。32访问器检查之外16组件检查；Service14、Provider28、Receiver3，真实RuntimeManagedInstaller、平台原函数/异常身份、Service token、PendingResult、45个boot备份根。**夹具为physical passthrough，实际产品路由回调/Engine未验收**。
- 固定核心新增`FixedComponentHooks`，同一Native registry的6类声明SHA来自 `component-inventory.json`；严格选45个Method后才准入。实际wrapped resolver是 `ContentResolver$1`，它不实现acquireExisting，需精确Hook其基类ContentResolver.acquireExistingProvider(Context,String)，不得猜签名或换resolver。该wrapper原acquire/release抛UnsupportedOperationException；夹具保留精确原异常，不当成功handle。
- 产品`RuntimeSelfHookFixedComponents`已把45入口映射到实际PlatformCallbacks/Components，含继承boot Service生命周期，仍未由Engine调用。`RuntimeManagedInstaller`已放开Native预检以与已验收SDK一致。
- `RuntimeSelfHookApplication.prepare`现在在pending逻辑视图前创建/缓存真实基础settings resolver，保持物理基础设施身份；待Engine整体验收。未来应按真实能力把prepare拆Framework/ownership/reflection阶段，不填空installer。
- 失败run保留：`9412997d…`（夹具漏ServiceToken.prepare）、`6909e162…`、`62f78f6a…`（wrapper原异常被错误当作handle）；不追认。后者有完整原异常栈。d2d581d8/52a9d790实物已归档。
- **其余22固定类完整声明已采集**：APK `ac8904a9…`，build `4686fd2f-887b-4655-bdef-6cf0a9313ee0`，run `de697b64-c689-4d0f-a7ea-648b30d31538`，source `64b5b399f455fa48f48d6fe87dfd72cd8081bc04c061694e34c444fcde24d829`。同run F6回归独立通过。Native registry扩到49类，完整新增2848声明，调用预算6673/8192；清单在 `runtime-inventory.json`，**没有因此授予这些方法Hook准入**。Class slots27–48见FixedFrameworkMembers；PackageManager真实层级只有ApplicationPackageManager→PackageManager→Object。
- 新固定总量：旧6manager1270 + 新4manager253 + TrafficStats62 + 包管理两层500 + F6 45，已超过2130；2048不足。**正在将唯一managed预算改4096**（Java/C/assembler/helper oracle/32类容量夹具/最后槽4095），Native入站仍1024。SDK ABI检查2048项是检查数量，不能误改。
- 同时去除原方法存储的每槽两次普通JNI identity transition：target以被Method/Class/loader根保留的稳定ArtMethod地址判重；owner在新FastNative findOwnerNative里用JNI IsSameObject/read barrier比较，不用raw堆地址做键，不新建第二份map。Java bindNative签名新增ownerLookup Method，C两FastNative一起注册/核验。
- **当前build session70487**，日志 `h9-capacity-build.log`。先检查build结果，再install→constructor26→独立verify→manager-all15测时间→verify→capacity4096→verify→JNI SDK31回归。测试/核验期间不得改构建输入。新容量与新优化尚未验收。
- H7 SDK逐32参数精确字比较加强已通过：APK `ab8070da…` / run `5ed63c87-8248-48e2-9412-3da5aa18209c`，31项/396原生调用/384并发。旧汇总hash恰好可相消，因此该run覆盖此前仅hash的验收。
- 生产agent是`android/runtime/agent/index.ts`，不是旧hooklab demo `android/agent/index.ts`。只读agent已结束，目标清单 `docs/evidence/self-hook-h5-2026-10-15/runtime-fixed-targets.json`。下阶段以清单完成其余固定目标/字段/构造器与真实installer，再接Engine和构建；单一产品SO要合入一份io_adapter/path_policy，不能同时配置两份状态或打包probe JNI。

## H6/H7 最新接手点

本节覆盖下面历史快照。用户要求全部完成后停止，启动边界选择已确认；**产品迁移未完成，Engine/build 仍用 Gadget**。持久 Goal 工具读出 blocked，但本线程已按用户继续指令执行，不能将历史状态当成新的外部阻塞。

- 真正的固定安装器与整个六类 manager（1270 入口）已接入。批量 Native 发布只暂停一次，完整预检、根检查、策略注册和验证后恢复。HelperDex 批内模板复用 host79/36真实 dexdump通过。
- 最新 manager 性能：APK `8a8de27a…`，run `0cea1ec5-df14-4d43-8fe6-c83f52c72451` 独立15项通过；1262新增入口总7.041秒，prepare6.993秒、publish27.75毫秒。此前35.244→23.828→19.475秒各自证据保留，不能说第一步共享loader已改善。7秒仍非最终产品性能达标。
- 同 APK 的完整2048 batch capacity：run `10892234-6f2f-4797-97a7-968cb1673689`。4096 Hook调用、2048停用后调用、132 writer events、2048个备份owner均观测到移动，43.846秒包含调用与GC，不能作为启动耗时。
- Constructor 已作为 Executable 接入原存储/ABI/helper；原构造必须正常完成。26项包括混合构造/普通方法批量准备、错误目标整批预检。最近独立run `c332f416-36ae-49b6-8885-d06f6b0bfc5b`，APK `777ae76b…`。
- **构造前 Application 逻辑视图已实现并独立验收**：APK `7529134a…`，run `a4b8ede2-8d28-42b0-af20-6599da76748d`，14检查/8阶段。`ApplicationAdapter.begin` 必须在factory/guest clinit前；pending currentApplication为null，getProcessName为客体逻辑名称。attach/onCreate后提升唯一guest；底层物理字段始终host。产品 RuntimeSelfHookApplication 已接同一真实两入口安装器，尚未接Engine。旧 Application JIT run `0aa5d88b-9cb5-4fe0-9bef-5b081c9e1194`、混合19项run `8d939daa-539a-4c76-bc1d-7d7f2ae7d23e` 同APK通过。
- **JNI 私有寄存器通道已验收**：APK `8d21c5ea…`，build `90e09b89-6f40-4cf4-b8d3-1d96b58d83bd`，run `1b41ece6-376c-4c60-89a9-39d940a426b4`，独立33检查/4种真实Native模式/101次Java回调。公共scalar仍16参数、200-byte capture、240-byte入站；私有普通/Fast带2隐藏参数允许80-byte overflow，共享1024槽。Native/Fast/Critical标志、generic帧/Runnable状态、GC栈遍历、精确pending exception和LocalFrame清理均验证。**尚非Native方法Hook SDK**。
- Critical注册可能先在延迟表，data仍lookup。失败run `6059484d-48e4-413c-9bb5-f60b9e721558`（APK `f687e603…`）保留，不追认；新验收用实际GetRegisteredNative查询。不能清掉Critical标志假冒managed。
- **Native quick所有权实验通过50项**：APK `9b43605e…`，run `0e395110-0b49-4625-b034-9b24c41234f1`。实际Register/Unregister后保持quick Hook，停用使用当前ART绑定；28次managed quick调用，104次raw Java回调。
- **Native声明已接SDK并通过30项**：APK `99a5a969…`，build `e61c8d5e-b820-4c4e-b70a-2d80ffd659f5`，run `345d8f3b-8851-4d5a-b5ee-abb4244a3ea8`。独立InMemoryDexClassLoader、普通/Fast/Critical各32参数、67次注册、384并发/GC调用、395原生调用无状态/loader错误、异常身份、未解析替换/原调用失败、lazy named解析及在途停用。构造26项同APK run `27d139b9-91a2-4c07-990c-059e0ae13d19` 回归通过。
- `art_jni_original.*` 使用同声明类/flags的ClassExt根备份，quick为generic JNI，data是不可变私有raw adapter。target data从不修改。adapter中的FastNative helper在Runnable查询GetRegisteredNative；null时显式target传给JavaVMExt::FindCodeForNativeMethod(vm,target,nullptr,true)，再RegisterNative并使用最终返回指针。无C mutex/自建版本池。`original()` 读取其实际执行时的绑定，不声称保持Hook入场时版本。目标loader根保护ART管理的该loader库，不保护用户手工注册/卸载的任意外部指针。
- SDK内部保留32参数，所以私有ABI overflow容量为208字节；公共scalar16参数及200/240字节帧未扩大。Native原调用与export共用1024不可回收入站槽。Native未清除Fast/Critical标志；managed旧flag处理不变。
- **产品Java backend Native声明已通过**：APK `dafa3eec…`，build `8c4d9614-bdff-4c80-8148-307db93354c7`，source `093e6cf31472ae6c896b04c2f9196e0250297495650c2130965d804a53ac877f`。43项/16方法/4 Native声明，run `dc220d83-21a4-4f92-9a25-7a9b4d720013`；SDK新增混合GPR/FPR overflow/negative-zero31项、396原生调用无错，run `ea3f2813-db3f-4f59-98b3-52662594dc1f`；同APK混合19项run `017638f4-3166-4ef3-9048-bea01f95973c`、earlyApplication14项run `18a473e8-2732-4a3d-a841-b92c1e8a12b5`均独立通过，已归档实物。SDK198源码与原host合同、脚本671也通过。
- 当前继续真实Engine25项接线。只读agent正在梳理现有TS adapter与Java typed endpoints的实际目标；不得给Capability填空lambda、把requireActive当真实Hook安装或先删除Gadget。
- `/root/product_context` 仅只读核验固定ART解析/注册函数与状态，未操作设备。不要从FastNative调用wait=true MakeVisiblyInitialized：它要求普通JNI Native状态无ART锁。Class初始化可见性另有13/14/15状态与monitor入队尾部窗口，证据在/tmp。
- 剩余：完整25项真实bootstrap、其余固定目标和真实安装器、真实审批/UNKNOWN、Engine去依赖和完整产品/性能验收。不得以探针通过宣称产品完成。

## H5 Native模块当前状态（持续Goal）

最新（覆盖下面所有历史快照）：产品Native后端和复合Platform已落地并通过独立自有设备验收。最新APK `464d0fcc…`，buildId `4635610f-cd2a-4cf9-9cd2-358c273d022e`，sourceDigest `4e922e4c6d5e0e572b147712142415ec7bad566c67a81f2a108c381a9d398c7b`，实物在H5 `artifacts/464d0fcc`。混合19项、Native产品31项、JNI25项/400foreign、自然叶子30项/6导出与3168字节末页、目录4096项10检查/空目录8检查均已独立通过；详见[H5证据](evidence/self-hook-h5-2026-10-15/README.md)。SDK195源编译及host脚本671项通过。

- `RuntimeSelfHookPlatform`验证完整有序证据，Java/Native各持其精确分区，一个HookInvocationSession管理混合计划。发布/退休任何部分不确定均fail-stop；未接真实Engine authority。
- `RuntimeNativeObservation/Backend`由已验证ArtifactSet清单与NativeModules真实generation/live BuildID/exec/prefix共同准入，不通过dlopen pinning。真实事件64项预分配环、epoch稳定检查、多实例歧义新选择拒绝。清单预算现在4096，初扫scratch不在guest栈。
- Native每activation不可变、每调用独立module lease与token；JNI线程attach/detach保留原异常边界；FPCR/FPSR原调用与Java环境隔离。shared-page restore后必须重新认领原文件页；不要删除。
- prefix已覆盖常见STP/STRD/STRX/SUBSP及自然MOV/ALU/FP/SIMD/RET叶子，不给夹具插NOP。最后文件页允许Linux零尾但仍完整比较实际映射。PACM为下一指令前缀，legacy PAC/BTI路径SP-only；PACM/PC-modifier仍拒绝。
- 已完成固定Java native ART只读调查（agent product_context已结束）。RegisterNative `0x456080` 在RuntimeCallbacks后发布data或Critical延迟表，首次解析直接用其返回指针；Unregister `0x85e0f4`恢复lookup，Critical可见初始化writer `0x22e0c0`。不能删ManagedHook的native拒绝后冒充支持；普通/Fast的JNI handles与Critical入口分别适配。证据在 `/tmp/tripoder-art-*-native*.disasm.txt` 等，下一批应先归档关键结构。
- 后续实施：统一guest Native物理加载名（源码已把Engine.nativeLibraryDir改canonical，尚待新构建验证），真实固定安装器/25项bootstrap，构造期身份与真实控制审批UNKNOWN链，Java native独立入口，最终去Gadget与性能。整体未完成，不得结束成批次建议。

用户再次要求继续。Goal保持active，全部产品去Frida未完成，不要停止于批次验收。最新[H5证据](evidence/self-hook-h5-2026-10-15/README.md)。

- 已实现`native/modules/`固定linker notifier、128精确路径模块清单、generation/live/ref原子状态；真正通知驱动，卸载有引用直接syscall退出198，不pin客体库。
- 实际dlpi_name是`/system/bin/linker64`，映射文件仍核对`/apex/com.android.runtime/bin/linker64`。旧错误名字的两次拒绝保留。完整源页/Build ID/DT_DEBUG/映射inode都检查；不猜list首项。
- 近地址封存ELF保存31GPR/32SIMD/NZCV/FPSR/FPCR，83PC FDE真实可查，完整寄存器对照通过。二进制critical call allowlist包含notifier和初次parked scan/publication。
- 最新`c5797f21…`构建在`artifacts/c5797f21`。normal run `ffb6f163-8842-4ce5-bc81-8c902140b314`独立47项/400并发调用通过；death run `fc293d26-5e6b-4610-9916-cb7c0c3669a2`匹配PID4967/EXIT_SELF198与Native ref1 witness，无成功ACK。
- `40208f55…` witness0600导致pull失败，保留失败；新构建0644已重跑，不追认旧run。早期597d3ef0的44项与ceb39470的47项也已归档，但不混算。
- build-self-hook-probe现在要求`--platform-linker android/self-hook-probe/build/device-linker64`，runner增加`modules`/`modules-death`。独立verify-self-module-lifetime.py / verify-self-module-death.py。
- 正在新增`native/abi/scalar_inbound.*`与A64静态1024入口（尚未构建/验收），下一步接typed compiler对照、并发errno/重入/原调用验证。Native安装/FDE/Java native声明和复合Platform仍缺。
- 只读agent`product_context`正在探索Native SDK/安全安装接线，不能替它假设已经完成。它已发现现有产品自有库入口是STP/STR栈前导，当前relocator不支持；不要给夹具加NOP绕过。
- RuntimeEngine/build仍用Gadget。真实25项plan、构造前逻辑身份、全预算与性能仍缺。继续完整目标，不得将Native模块引用测试宣称为通用Hook安装。

## H4b / H5 当前接手状态（持续Goal）

**用户要求“继续实施，全部完成后再停止”。已经建立active持续Goal，目标是完整产品去Frida并验收，不能把小批次完成标记为Goal complete。第一种启动边界已确认，不再询问。没有外部阻塞。**

- F2/F4/F5已真实实现：FrameworkAccess Native根扩展到24类/21字段；同表typed组`FixedFrameworkMembers`、`ComponentFrameworkAccess`、`ManagerFrameworkAccess`及产品`RuntimeSelfHookFixedFramework`。缓存只读方法枚举，避免重复消耗8192访问预算。Application旧语义组索引未变。
- 固定成员当前独立验收run `01e29eb2-5095-4007-9acf-bddd760dd339`，32项通过：合法64位flags及平台禁用位拒绝、真实self-broadcast PendingResult/goAsync身份、Provider handle、selection/back-reference、6 manager构造、ApplicationStartInfo精确long且无host Intent。
- Bridge的观察记录已提取为`RuntimeJavaObservation`，不是新增重复authority。`RuntimeJavaHookBackend`是产品Java部分，按Method身份保存Handle，复用SelfHookControl生命周期；当前没有装成完整产品Platform，因为Native部分和真实审批transport还缺。`JavaHookValues`覆盖所有SDK Java标量/null/void。
- 产品Java代码直接编入自有probe，在独立DexClassLoader跑通run `c2525314-27d8-407f-b8f7-5f610146c378`，34项、12方法、2次激活；包含观察/源码准备不初始化、激活初始化、全类型/instance/void/原Throwable、恢复、不同symbol ID复用同一Method、失效证据拒绝。别把Java-only harness的空modules列表移进产品。
- 最新APK `da3eff64876c063d55bf2a30d2d12a7e9ee23ddfe25ac9174eaf4990020c9c4f`，buildId `973a4bec-7831-44db-bf23-42c29d6e46c6`，sourceDigest `f352ed4c5c889e53514d9d12ef82c914dad4f3f469987be80e7da7bdffac58d0`，实物在H4b `artifacts/da3eff64`。
- H4b较早`c957eec8…`的F0/F1和共用产品codec的脚本JIT/AOT各473项也独立通过。主机脚本633项，Runtime 189源码SDK编译；最新明细见[H4b证据](evidence/self-hook-h4b-2026-10-15/README.md)。未知/未验收项仍标false，不混构建通过数。
- root已pull `android/self-hook-probe/build/device-linker64`，SHA与platform.json的b6dd216b…一致。只读agent `/root/product_context`已确认notifier RVA55d80大小8，BTI c;RET；r_debug RVA1bcbc0，正常卸载先通知再munmap，源码对照不需猜offset。分析在`/tmp/tripoder-linker-inspection-01a13fcb.*`，已归档H4b/linker-static。
- agent当前继续分析load_library的0x7a4a8清理分支。已知函数[794e4,7b154)、新soinfo分配/构造和solist_add支配清理，外部复用路径直接返回；需等待其最终证明和补充文件，不能提前认定完整释放闭包。agent只读、未操作设备/源码。
- 后续实施主线：真实固定基础安装器与完整目标预算/性能；构造前Application逻辑视图；产品Java/Native复合Platform接已有Authority链；Native需要真实通知generation、通用入站scalar ABI、export安装和JNI native方法，不得删除这些能力冒充完整替换。Engine/build仍使用Gadget，25项bootstrap尚无完整真实plan。
- Native notifier不能直接复用ART专用leaf。原no-op可能被LTO利用，relay须保持原现场；notifier内无JNI/脚本/malloc/stdio/dlopen，active/PUBLISHING卸载须直接syscall fail-stop。普通线程才能读快照、做文件hash/路径核验。
- 未提交/推送。所有设备测试仅自有probe；仍可继续直接执行，不需要新的权限确认或用户选择。

## H4a 当前接手状态

**本节是当前状态。用户已明确选择第一种启动边界，不要再次询问。继续目标仍为完整产品替换；本批完成容量、F1和证据发布，产品仍使用Frida。** [H4a证据](evidence/self-hook-h4a-2026-10-15/README.md)、[设计与下一步](self-hook-product-capacity.md)。

- 固定设备六类manager完整声明清点1270实例方法；支持白名单仅81个，原64槽不足。Native注册表现在有15个boot类根，Application组仍准确保持7 owners/12 fields/9 methods；Manager/Service是同一注册表的语义组。清点没有放开全部boot Hook，`requireManagedHookTarget`仍执行明确准入。
- Java/helper/Native entries、OriginalMethodStorage及每类ClassExt数组、writer表与两个汇编leaf统一2048。`HookCapacity`在调用前与Native值核对；`remainingCapacity`基于既有used计数，停用不回收。根快照改堆分配并按数量分配JSON缓冲，不留旧64KiB截断边界。
- 16声明类/2048不同方法真机通过4096次Hook调用、2048次停用后调用、超额拒绝及最后一槽132次真实writer事件。所有2048个备份owner根均观测到移动（16个唯一Class），GC后关系正确。该压力耗时包含准备、调用、GC、停用，不是产品性能验收。
- F1 `ServiceTokenAccess`固定两个字段/一个interface方法，JNI保持原线程/真实token/boolean/int/原异常。产品FrameworkCalls默认委托及Framework准备已接入，167源码SDK编译通过。自有设备Proxy夹具23项/6调用/2线程通过；未发送真实系统stop，完整Service安装器仍缺。
- 修复证据发布竞态：`ac407a21…`原采集文件为空，设备后来写完70988字节，分别保留。所有14个探针Activity改用ProbeFiles.publishResult：独占永久claim、临时文件写完fsync、ATOMIC_MOVE。6发布者主机测试只有1提交；最终11个run均有claim且无残留part。
- helper host oracle新增负slot和63/64/2047真实DEX解码；ABI verifier同步51项，旧50项失败没有追认。原H3m启动策略在最新AOT构建再次通过。
- 最新APK `e6941e9b75600b277a545ca49721cfa368db758e5e8dc48a9c3f058673c144c3`，buildId `a5e729f6-9792-471b-8416-7bd2bc50e9a4`，sourceDigest `5f15982efee92217f530c9923b10225edf5b479954ff70e3e6e4a5ea7a4e12d0`。实物在H4a `artifacts/e6941e9b`；11组独立verification见[matrix.json](evidence/self-hook-h4a-2026-10-15/matrix.json)。JIT/AOT ABI各2048项、脚本各473项，另有Framework/Application/coverage/容量/固定成员/启动策略。
- 下一阶段：F2 Receiver/Provider/flags固定访问器，F4/F5 manager构造/历史值，按实际清单实现真实安装器并检查完整安装预算，再接Runtime首次guest代码执行边界与产品控制链。完整25项安装计划尚无实际总入口，不能先删Gadget或用空installer标记READY。
- 跨构建证据不可混算。所有运行仅自有探针；最终自有进程停止，未清数据或编译缓存。没有提交或推送。只读结构子agent已结束，无设备任务由它执行。

## H3m 当前接手状态

本节覆盖后续历史快照。用户要求继续直至完成；实际完整替换仍未完成。最新[H3m证据](evidence/self-hook-h3m-2026-10-15/README.md)、[执行边界](self-hook-process-interpreter.md)。

- 真实AOT在途帧反例已稳定复现：JNI屏障前进入AOT，另一线程enable，旧帧恢复返回10、新调用110，target quick仍是relay。原H3l偶发失败不能靠增加诊断后的偶然通过消除。
- 新增一次性`ManagedHook.initializeProcess()`，复用同一绑定/中央writer/解释租约，在任何Handle准备前显式执行；失败后prepare闭锁。它不证明此前没有guest执行，也不能迁移旧AOT帧。
- 同构建提前初始化对照独立通过：原本AOT方法在进入帧前已是解释桥，屏障后和新调用均110，原调用桥准确两次。`processPolicy`显式报告旧编译帧覆盖和完整覆盖均false。
- Runtime安装计划首项已加PROCESS_EXECUTION_POLICY；Engine仍运行既有Frida入口，不能提前称产品切换。主机293门禁检查及166源码SDK类型检查通过。
- 当前APK `ff16b5932a076369f182ca1bb0a1c2ef9b1be043b39f1a87f5542151f889e8d6`，buildId `df4e57f5-3809-4751-b614-3991561e95f7`，sourceDigest `6ad4038a2d1c9204c1f64eaaa3d3906eb383c618910aeea7af3fc64ea51fb224`。实物保存在H3m的`artifacts/ff16b593`。
- 当前构建JIT/AOT：writer各132次事件，调用25730/8869；ABI各2048项、移动4对象、400轮根观察；脚本各473项。JIT组合coverage通过24轮观察、2个Class移动、32次GC，已不再受H3l共享暂停域问题阻塞。
- 新验证器区分“反例验证通过”与“新帧覆盖通过”，产品/既有AOT帧迁移都保持false；14项篡改检查通过。所有runId见H3m索引，不混用旧APK结论。
- **范围已确认**：用户明确回复“我选第一种”，接受客体代码执行前初始化，之后继续动态添加/更新/启停Hook。无需再次询问；任意既有AOT帧迁移不再是本产品的完成条件，仍需证明所有guest首次执行都在边界后。
- 仍缺真实产品启动顺序、固定Framework安装器/构造期身份、direct-store/未来image闭包、caller-sensitive、控制面和性能。禁止因核心分项通过就删除产品依赖或宣称完整替换。

## H3l 当前接手状态

**本节覆盖下文H3k及更早快照。继续实施已获用户授权，所有运行仅限自有探针。** 最新[H3l证据](evidence/self-hook-h3l-2026-10-15/README.md)与[入口设计](self-hook-entry-ownership.md)。

- 已真实安装中央UpdateEntryPoints：原PACIASP及STP FP/LR留在原位置，entry+8的STP x20/x19移至近地址leaf后回entry+12。leaf不改SP，唯一FDE/全部23个PC均为CFA=SP+32。旧+4/三FDE为历史研究，不是当前fallback。
- `dfb7b7cc...`构建：自有入口run `922891da...` 40万调用、10万desired、512次异步展开和完整文件恢复独立通过；真实writer run `a9ffc782...` 的132次原writer事件/127405次调用通过；ABI JIT run `eb91dbbc...` 2048项、4对象移动、400轮根观察、381541次并发调用通过。
- 真实代码/FDE由已嵌入模板生成sealed memfd，再android_dlopen_ext加载到排他保留的近地址区。核验整份ELF、唯一补丁字、原文件身份和BTI。实际ART源页无bt，保持一致；新ELF有bt。`InvalidateAllCompiledCode`的this是JitCodeCache，不是Instrumentation。
- 原H3k run `05786700...` 已独立通过中央writer；`bb5c3dcc...`因旧BTI验收器假设失败，没有verification。H3k `74fa61bb...`随机未命中旧post-STP状态，促使本次从结构上消除CFA变化。
- H3l组合run `84950608...`前置loader阶段后，因第二次调用一阶段tp_park_init返回BUSY而拒绝自有入口准备，整次未通过。已新增`tp_park_prepare_process`：只复用本核心已拥有且实际handler仍准确的signal reservation，不把BUSY当成功、不另装handler；旧tp_park_init仍保持一次性。正在构建/验收该共享域修复。
- 当前`writerInterpositionInstalled=true`仅中央writer；`completeEntryWriterClosure=false`、`fullCallerCoverage=false`及产品READY仍保持未完成。还缺直接store/未来image、caller-sensitive原帧、产品安装器/构造期视图/控制面与性能。

## H3k / H2e 上批状态

**以本节及[H3k证据](evidence/self-hook-h3k-2026-10-15/README.md)为当前状态。下文H3j/H3i/原快照均是历史，不能按旧限制或旧构建直接继续。产品仍使用Frida，完整替换未完成。**

- 当前已验证APK `fa03e4f5722f4505a683c3a6fb1754665c1a8141bb784e8bc9d26c118d769bcc`，buildId `6caee2dd-2c9b-4a0a-ad29-c621e60cb5c3`，sourceDigest `4ac451c6e61b43fd66f4eb1a0f73918f8483787034cb652a7a10e70821023ebe`。实物保存`docs/evidence/self-hook-h3k-2026-10-15/artifacts/fa03e4f5/`。
- Core ManagedHook已采用首次enable持有进程解释租约的唯一策略，移除其逐方法deopt-set编译权威；prepare不claim、外来级别不认领、disable保留到进程退出。见[设计](self-hook-process-interpreter.md)。产品性能仍未验收。
- 前一`eba28a0a`构建：ABI JIT/AOT各2048项及400轮并发根观察、脚本JIT/AOT各473项独立通过。新`fa03e4f5`的ABI JIT又通过2048项，且方法观察器真实核对激活前/GC后owner、DEX index及quick。其他编译模式须对后续源码独立回归。
- 动态target不再被硬编码为Core defining loader。OriginalMethodStorage在FastNative中从真实Class.classLoader取归属；boot真值为null，不能用Java的BootClassLoader包装对象。三个备份（主APK、动态loader、boot）均rooted，17个槽位global roots，boot allocator一次。
- 当前coverage run `f641a0b2-6265-4742-bf8a-1f4169ac4456`：101002次未来双loader调用全部命中；独立loader target1001次与同名另一loader1001次无串拦；boot ActivityThread.currentApplication1000次，原调用身份与F0物理host正确；24轮枚举/28次GC/两个Class移动；512次异步采样覆盖两种CFA状态。
- `e929d764...`随机采样失败保留：14749次无错误却未命中保存后状态。已去掉异步采样worker中的foreign 64槽长扫描，保持原leaf/FDE和验收阈值，后续真实采到两种状态；不是放宽门槛。
- [H2e标量ABI](evidence/self-hook-h2e-2026-10-15/README.md) run `bbdda59c-fea3-482e-a5aa-c932a9649ae7`：13组编译器对照、490项设备检查、4000并发errno；汇编已执行。无通用目标发现和Hook安装。
- 仍未完成：真实ART writer interposition、动态ELF/FDE发布、直接store闭包及未来AOT/image；caller-sensitive原帧、loader卸载、产品固定Framework/manager安装、构造期逻辑身份、Platform/Engine切换与无Frida产品验收。不要设置完整覆盖或产品ready为true。
- 当前推进顺序：固定writer入口的真实安全发布与展开 → 失效/Trace/类发布事件验证 → 固定产品安装器和构造期身份 → 产品控制面接入 → 所需能力全部通过后切换并移除旧依赖。无自动fallback、无周期修回入口。
- 代码位置：`native/art_interpreter_policy.*`及完整契约、`native/art_method_storage.c`、`native/coverage/method_observation.c`、`LoaderTargetChecks.java`、`AbiCallerIndex.java`。所有shell仍以rtk开头；仅自有探针设备运行，不清数据或编译缓存。

## H3j / H5 上批实施记录

用户已授权继续至完成；最新分项见 [H3j证据](evidence/self-hook-h3j-2026-10-15/README.md) 与 [入口所有权设计](self-hook-entry-ownership.md)。它们覆盖下文H3i/原快照中的旧进度。

重要新增证据：`eca1662b-ff09-42d6-8b78-9fa085ea7608` 已复现激活后新建caller在JIT后绕过（第67682次调用，目标quick仍为relay）。逐方法deopt-set不能证明未来内联覆盖。独立进程级解释执行实验 `dde4724e-aa62-44fc-a43c-29e4b3d5facf` 的101002次调用全部拦截，caller保持解释入口；该实验尚未代表产品策略切换，性能与完整矩阵仍须评估。

自有预链接leaf的分段CIE/FDE已独立通过：`590fcb87-eb39-46ed-a712-b3d22e155b35` 的512次有效异步采样覆盖两种CFA状态，LLVM覆盖22个指令PC。不要再使用跨线程本地unwinder认证：当前设备已观察到线程签名域不同。受控worker自己展开，普通observer落盘；这不是任意信号调用者的通用安全保证。

- exact static caller精度已修，17项D8、55项多DEX/loader检查通过。
- loaded-class C++已进入APK并真机通过：约2.18万类、两个同名不同loader类、真实Class移动、24轮GC并发枚举。观察快照不等于未来发布覆盖；该flag仍false。
- registry改为owner原子desired视图，ManagedHook修复resume后才更新active的窗口。预链接leaf保留原签名LR，40万次自有调用与原记账/SP通过；实际ART interposition仍false。
- 脚本夹具已接入Manifest/build/runner/独立verifier；源码→真实ManagedHook JIT/AOT各473项通过。产品backend、审批权威链和完整caller覆盖仍false。
- scalar pthread_join失败改为自有进程fail-stop，已NDK编译；不再是未修代码缺陷，但scalar设备ABI仍未验收。
- 新runner活动为 `coverage` 与 `script`；script自动带ownedScriptScope=true，仍要求新runId与严格jit/aot。自有动态DEX仅来自构建打包asset。
- 后续重点是真实writer发布/展开和未来类发布门禁，再做产品Platform与固定成员/manager；不得把任何当前分项视为完整替换。

## 继续实施后的最新增量（H3i）

用户在交接后要求继续。**以下增量覆盖正文中的旧构建、cold未通过、F0未接入等状态；正文其余部分保留为原交接快照。** 最新完整证据及失败过程见 [H3i索引](evidence/self-hook-h3i-2026-10-15/README.md)。

- 当前APK `9af55148c9a458686f30e37ffc73aa737b5690989b557b08434594c2d3c5147d`，buildId `730e941e-c9af-4c8e-a949-18462ae57712`，sourceDigest `339e1a64c004dd3c827396a710162614a59f529fd40b3cf3da08e2d41b6a8de3`。
- Java ABI的JIT/AOT/真实AOT image冷启动各2048项与400轮并发根观察通过；同构建不同进程cold聚合通过。历史 `2fd6…` 失败未改写为通过。
- 新增逐槽根快照与独立映像归属判断；修复并发GC中直接比较原始引用的错误，绑定固定ART根读屏障及Mark完整函数字节。cold压力窗口实际38次屏障修正，全部根关系正确。
- FrameworkAccess已12字段，包含静态sCurrentActivityThread；ApplicationAdapter物理路径改为固定JNI字段，允许selected loader委托至defining loader。PhysicalApplication已链接进独立探针；JIT/AOT各409次物理检查、其中400次跨线程，以及完整Application生命周期通过。
- 新构建共享Native/libc/ART主探针AOT回归、产品适配主机754项与166源SDK37编译通过。完整产品仍未切换；factory/Application构造期逻辑视图未解决。
- `docs/self-hook-managed-storage.md` 已更新64槽共享ClassExt数组；不再保留旧8槽说明作为当前实现。
- 当前优先顺序：exact static caller精度 → loaded-class设备GC/CheckJNI与未来发布 → 系统入口写入/PAC/展开 → owned script与产品Platform → scalar夹具线程退出修复/设备验收 → 剩余产品安装与切换。
- 本轮只有主Agent实施；旧子Agent未重启。自有探针已停止，编译缓存保留。下一次main探针前必须对当前APK运行check_dex_graph.py，runner现在会拒绝旧APK的oracle。

## 原交接快照

**当前结论：自研核心已有分项真机证据，产品仍使用 Frida，完整替换尚未完成。** 本文是本轮停止实施时的交接快照；后续 Agent 应先读本文，再读 [task.md](../task.md)、[总体方案](self-hook-design.md) 与 [产品适配清单](self-hook-adapters.md)。

## 1. 目标、边界和交接状态

- 最终目标：完成自研 Native/ART Hook、调用者覆盖、脚本控制和全部必要产品适配，切换产品并移除 Frida 依赖。
- 所有验证仅限自有夹具、自有测试环境；不要扩展到第三方应用、攻击、规避检测或其他危害网络安全的操作。
- 不做兼容后端或 fallback。未证明的能力应明确拒绝；保留现有产品路径直到新后端满足切换条件，不代表设计双后端回退机制。
- 复用既有 Broker、Store、操作身份、审批、journal、UNKNOWN 对账，不建立另一套权威状态。
- 文件超过 2000 行按功能拆分；所有 shell 命令以 `rtk` 开头，见 `/Users/like/.codex/RTK.md`。
- 工作树包含大量已有修改及未跟踪文件，**本轮未提交或推送**。不要 reset、clean 或以当前 Git diff 之外的文件为“无关文件”删除；换机器交接需要包含这些源码和证据。
- 三个实施子 Agent（caller_coverage、runtime_adapters、script_control）均已结束，没有正在运行的构建或设备操作。自有探针 `dev.tripoder.selfhookprobe` 已 force-stop；未清理数据或编译缓存。
- 本文整理阶段只更新文档，不再继续实现。下一 Agent 可从第 7 节开始。

## 2. 完成度总览

| 范围 | 已实现及验证 | 仍缺什么 |
| --- | --- | --- |
| H1 指令核心 | 纯 C AArch64 规划；92 核心检查、38 LLVM 对照；独立探针 | 结论限于明确支持的指令与平台 |
| H2 Native | 自有 ELF、非协作暂停与发布、固定 libc 十入口及两个共享页；有 JIT/AOT 真机证据 | 通用 ABI、目标/模块代次所有权、PAC/展开、长期运行等 |
| H3 固定 ART | Native 反射原帧、managed 原调用、调用者反优化、逻辑字段视图、固定成员访问、Application 生命周期 | 完整 boot/guest-loader 产品覆盖及系统入口写入闭包 |
| H3h Java ABI | 19 类签名场景，JIT/AOT 各 2048 检查；实测移动 GC | 最新构建 cold 独立验收未过；任意 loader、卸载等未完成 |
| 调用者与入口所有权 | 保守 DEX 图、loader 绑定、只读观察器、注册/路由、PAC 规划、loaded-class 枚举源码与主机测试 | 多项尚未集成探针；未来类加载、直接入口写入、动态 CFI 尚未闭合 |
| H4 产品适配 | Application、组件与六类 manager 门面 Java 实现、安装门禁；完整 166 源 SDK37 编译 | 固定成员、真实安装器、剩余 manager、Engine 接入及真机验收 |
| H5 脚本控制 | source→受限 AST/计划、参数/调用/控制协议；593 主机检查 | 自有 Android 真实集成夹具尚未跑；产品 Platform/backend 未接入 |
| 通用 Native scalar ABI | 六种 scalar/最多 16 参数布局与汇编源码；2103 C 主机检查、独立 SO 编译 | 有待修复线程退出问题；汇编尚未执行，未接入 Hook 或产品 |
| H6 产品切换 | 尚未完成 | 当前产物和运行时仍依赖 Frida；不得标记替换完成 |

历史证据入口：[H2d 固定 libc](evidence/self-hook-h2d-2026-10-15/README.md)、[H3f Application](evidence/self-hook-h3f-2026-10-15/README.md)、[H3g 存储修复](evidence/self-hook-h3g-2026-10-15/README.md)。这些报告绑定各自构建，不能替代最新源码的验收。

## 3. 当前构建与关键真机证据

### 3.1 环境与产物

- 自有设备 serial：`d861a4fa`；Android 17 / API37 / ARM64 / 4096 字节页。设备连接、权限和安装状态在接手时重新确认。
- fingerprint：`Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.24.0.XEOCNXM:user/release-keys`。
- 包名：`dev.tripoder.selfhookprobe`；此前观察 UID10403，不能跨重装硬编码为事实。
- SDK：`/opt/homebrew/share/android-commandlinetools`，platform `android-37.0`，build-tools `37.0.0`。
- Java：`/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`。
- NDK：`android/vm-probe/build/native/tools/android-ndk-r30`。
- 缓存 ELF：`android/self-hook-probe/build/device-libart.so`、`android/self-hook-probe/build/art-debug.elf`、`android/runtime-probe/build/device-libc.so`。
- libart SHA256：`2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13`。
- libc SHA256：`b963b539545b64aa8fbcaed3228d04bcb6466bfd47055aa16e4ccdcadfdc461e`。
- linker SHA256：`b6dd216b7a775b8f04c825cada286bfe7ddaeecf2c0c10e617287118965abc00`。

当前本地 APK：`android/self-hook-probe/build/self-hook-probe.apk`，回执 [build-receipt.json](../android/self-hook-probe/build/build-receipt.json)。

| 字段 | 当前值 |
| --- | --- |
| APK SHA256 | `2fd6cdc2db31a4adf504aebcc9395ae6929e749fed26c77cca0e109032ac3d9d` |
| buildId | `c27483ed-cace-45ae-9cf8-cd9a89c05879` |
| sourceDigest | `951a25b6ceab1bc26ece420ba99a8e95f7384f0eccd8623466d66e89703531e8` |
| Native SHA256 | `a10e46a826c55c50298206e267e4fd270c5571b6c4e9c573aeb27bfaf334b396` |

整理前已核对 receipt 内已登记源码哈希无变化。**这不代表后续新增文件均进入 APK。** coverage C/C++、PhysicalApplication JNI、脚本集成夹具、scalar ABI 尚未接入当前探针构建；部分 Java 类可被递归收集，但对应 Native 未打包、未执行。

### 3.2 H3h 当前结果及冷启动问题

证据根目录：[self-hook-h3h-2026-10-15](evidence/self-hook-h3h-2026-10-15/)。

| runId | 构建/模式 | 结论 |
| --- | --- | --- |
| `4bba6be6-9f2f-41ee-b52e-166d46ad6fb4` | 当前 `2fd6…`，JIT | 独立 verification PASS；2048 检查/19 签名，545465 worker 调用；4 参数对象、19 owner 引用移动，57 次 container 槽位移动观察 |
| `0de7a3f4-dc3d-498b-969e-5195efb2fb7b` | 当前 `2fd6…`，cold | 设备 result passed=true、2048 检查；独立 verifier 失败，无 verification.json，不能算完整通过 |
| `8955e032-0400-487e-9f23-de7957ab5a78` | 历史 `55a8…`，JIT | 独立 PASS，2048 检查，454383 worker 调用 |
| `33c4dbd2-ab43-4bc2-913e-43acb02075b0` | 历史 `55a8…`，AOT | 独立 PASS，2048 检查，705039 worker 调用 |

最新 cold 的失败点是 `verify-self-managed-abi.py` 的 `ABI_MOVED_BACKUP_ROOTS`：它无条件要求 `observedOwnerMoves > 0`。此次 app image 中的 Class 没有移动，但 4 个参数对象和 container 根发生了移动，19 个备份关系仍正确。57 是共享 container 的槽位观察数，不是 57 个不同对象。应补充映像归属证据并修正条件，保留真实移动对象/容器与根关系要求；同构建 JIT 的 owner 移动证明也应关联。**不能删除 GC 门槛来制造 PASS。** 聚合 cold 验证目前也因缺少独立 verification.json 而未通过。

另外两次“预期 JIT”失败必须区别记录：

- `df4fb694-b5f4-475c-bdb2-dcb5d5901c36`（历史 `549271…`）：已抓到 flags `0x10200009`、bit25 未设；实际 quick `0x6cef15aed0` 在自有 base.odex RX，系统状态 first-use/speed-profile，属于已经 AOT，与严格 JIT 预期不符。
- `714c16a0-8e45-4625-85c3-807e140a9d24`（历史 `55a8…`）：缺少最后方法快照，根因未确认。

旧版给目标设置 CompileDontBother 的持久化结构风险已通过进程内 deopt 所有权替代；**该风险并未被证明是上述失败的根因**。不要再把两者等同。H3g 更早的声明槽移植导致 AddImageSpace 冷启动崩溃，则是另一个已经修复并有独立证据的问题。

历史 `55a8…` APK 完整 SHA：`55a8cd67d0473bc1126dd66c7f48c3a50163182316f1a8aa231cc85861710c95`。其 main JIT/AOT runId 为 `dd3681cd-aab7-490c-8f38-3735b4fcaed3` / `c2c4fc35-71ae-4a6d-a599-02b5553f5cd6`；Application 为 `523ae2df-b362-4543-bc76-eae706e5b6eb` / `bc3a1e77-048f-417c-aeac-8157b05e3536`，均已独立通过。`android/self-hook-probe/build/h3h-aot-proof.json` 只绑定该旧 APK，不能用于当前 APK。

## 4. 核心实现定位与限制

### 4.1 Managed Java ABI 与 GC

核心目录：`android/self-hook/java/dev/tripoder/selfhook/` 和 `android/self-hook/native/`。

- `ManagedHook.java`、`dex/AbiSignature.java`、`dex/HelperDex.java`：生成 DEX039、精确签名 managed helper，实例 receiver 作为首个 Object；装箱后进入 dispatch，返回值按类型处理。
- `art_managed_entry.c`、`managed_abi.c/.h`：RX|BTI relay，保留 target managed 元数据，通过类型化 JNI 调用 rooted backup，保留原 Throwable。
- `Invocation.original()` 限同线程、最多一次，handler 返回后失效；同步方法的 handler 和 original 均在原 receiver/Class monitor 内。
- 类初始化和已知 caller 反优化在 enable 时发生，不在 prepare 时提前执行。
- 目标使用进程内 Instrumentation deopt 集合；不设置目标 CompileDontBother，并拒绝基线已带 bit25 的目标。仅清 Nterp 入口快位。helper/backup 则保持不可编译。
- `OriginalMethodStorage.java`、`art_method_storage.c`：LinearAlloc 备份和 ClassExt Native 根；64 次槽位预算，每个 owned class 共用 64 槽 ClassExt 数组，增加槽位时重新发布写屏障；每槽 6 个 JNI globals，加注册表根。19 槽 fixture 共有 114 个槽位 roots。
- 限制：64 entries、32 managed 参数、每 entry 256 次切换、已知 caller 至多 64；代码及根按进程生命周期保留。当前 target 限核心 defining loader 的自有类，非 native/abstract/interface/Proxy，**尚非任意产品 loader 或 boot 方法通用 API**。
- 旧 `OwnedIntHook` 是历史固定 int 测试，不能作为通用后端现状。
- [managed-storage 文档](self-hook-managed-storage.md) 中旧单槽/8 次预算说明尚待同步为新共享数组/64 次模型。

探针：`AbiFixture.java`、`AbiCaller.java`、`AbiLazy.java`、`ManagedAbiChecks.java`、`ManagedAbiProbeActivity.java`、`GcProbe.java`、`probe_gc.c`。主机 helper 检查 50 项及 22 次独立 dexdump；closure 检查 9 项，旧 DEX oracle 14 项。真机覆盖 primitive/object/array/void、实例/同步、宽整数、浮点负零/NaN/混合溢出参数、递归、异常、错误类型、类初始化、并发及在途 GC/停用。

### 4.2 调用者、系统入口写入、PAC 和 loaded classes

源码：`java/dev/tripoder/selfhook/coverage/`、`native/coverage/`、`tests/coverage/`。夹具实际位于 **`android/self-hook/coverage-fixtures/`**。设计见 [caller-coverage](self-hook-caller-coverage.md)，该文尚未追加最后 loaded-class 批次。

主机产物位于 `android/self-hook/build/coverage-host/`：

| 报告 | 验证范围 |
| --- | --- |
| `report.json` | 多 DEX/loader 绑定 55 项 |
| `native-compile-report.json` | 方法只读观察器 Native 编译 |
| `entry-ownership-report.json` | 82 项 ASan/UBSan、800000 次并发路由 |
| `entry-writer-audit.json` | 26 个直接 B/BL 边、2 个直接 writer、35 项 LLVM 审计 |
| `runtime-image-analysis.json` | 固定 ART 映像/入口行为分析 |
| `pac-plan-report.json` | 613 主机、31 LLVM、7 个实际 ART prefix 检查 |
| `pac-platform-report.json` | 52 主机检查、NDK/import/5 条 MRS 审计 |
| `loaded-class-report.json` | 269 C++（含 128 模拟根移动）、14 Java、55 caller 回归、ARM64 编译 |

上述新增 Native 能力**均未设备执行或接入实际 ART Hook 所有权管理**。

关键事实与门槛：

- 普通 JIT GC/单方法失效通常不覆盖自定义 relay；`InvalidateAllCompiledCode` 会。`ReinitializeMethodsCode`、`UpdateEntryPoints`、obsolete method 与 AddImageSpace 直接写入仍需完整控制；vendor HyperArt 路径未审完。
- 观察到的 7 个中央函数含 PAC 前导，旧规划器会拒绝。新方案仅限 classic A-key/SP；unknown、PAuth_LR、B-key 均拒绝。PACA bit 不能证明没有 PAuth_LR/PACM 的 PC modifier。
- PAC observer 只读 auxv、PR_PAC_GET_ENABLED_KEYS、受 HWCAP_CPUID 限制的 ID 寄存器与进程/CPU信息，不修改 keys、PACM 或 affinity。内核屏蔽值不能证明硬件能力不存在；缺 vendor contract 时 classicProfileAdmitted 仍为 false。
- 离线 unwind rows/预链接 CFI 不是动态 FDE 发布或异步展开证明。
- `LoadedClassInventory.capture(capacity)` 最大 65536：普通 JNI 预分配 roots，FastNative 注册 HandleScope 后调用固定 VisitClasses；回调无 JNI/分配/函数调用，释放类锁后转 Class[]，溢出不返回部分列表。`Snapshot.forLoader` 可用于真实 Class 绑定；`futureClassPublicationCovered=false`。
- 固定契约：VisitClasses RVA `0x42390c`，visitor slot2；Thread HandleScope 位于 `+0x118`，scope capacity/size/refs 为 `+8/+12/+16`。必须按固定 libart 字节契约验证，不推广到其他版本。
- `loaded_class_inventory.cpp` 要单独 clang++ 编译，采用 `-std=c++20 -O2 -fPIC -fno-exceptions -fno-rtti -fno-sized-deallocation -mbranch-protection=standard`，再链接到现有 C 库；当前对象无 libc++ 导入。

**待修 caller 图精度问题：** `DexCallerClosure` 目前可能按 name+descriptor 扩大 static 调用匹配，即使 exact definition 已存在。将 ScriptOwnedFixture.integer(I)I 加入 APK 后，可能把不相关 caller 纳入 ABI fixture。应对已知 static 定义使用精确 owner 边，仅对未解析继承 static 引用保守扩大，保留 virtual 保守性，并增加“不相关类同签名”的 D8 回归；尚未修复。

## 5. 产品适配与脚本子任务交接

### 5.1 Runtime 适配与 F0

源码：`android/runtime/src/dev/tripoder/runtime/RuntimeSelfHook*.java`、`RuntimeBridge.java`；文档：[runtime-bootstrap](self-hook-runtime-bootstrap.md)。

- bootstrap 有 24 项能力门禁，先验证所有 installers 再产生效果；失败闭锁，锁内不执行回调；所有安装完成且线程正确才可 selfHookReady。
- 已有 Application/QueuedWork、精确 loader 归属、Service 六生命周期/attach/stopSelf/foreground 拒绝、Receiver 同步异步边界、Provider acquire/release/身份/observer/权限边界、不可变 batch 选择快照。
- 六类 manager 为 ActivityManager、Connectivity、Telephony、Audio、Battery、Power，共 81 项能力；owned 未知调用拒绝，unowned 保持 original；实际 family 安装未完成不能构造 facade。
- NetworkStats、WifiP2p、Notification、Shortcut 仍待实现。
- `android/runtime/self-hook-tests/build/report.json`：281 bootstrap + 11 loader + 85 component + 117 boundary + 215 manager policy + 45 typed manager 检查；166 Java 源 SDK37 编译。deviceExecuted=false、productAccepted=false。
- **当前 Frida TS 仍会覆盖部分 RuntimeBridge 接缝；没有真实 Framework 安装器、Engine 切换或产品 READY。**

F0：`PhysicalApplication.java`、`native/physical_application.c` 和产品 `RuntimeSelfHookPhysical.INSTANCE` 已实现固定 JNI 字段读取。79 C mock ASan/UBSan、15 HotSpot `-Xcheck:jni` 场景/53 断言、ARM64 严格编译通过，未 Android 验证。

F0 要求准确 12-field registry：0=mInitialApplication、1=Application.mLoadedApk、2=LoadedApk.mApplication、**11=静态 sCurrentActivityThread**。只用 FromReflectedField/GetStaticObjectField/GetObjectField；不通过 managed getter、Field.get、GetFieldID。4 个 globals 保存 host/thread/apk/threadClass；跨线程以正常 JNI 附着及互斥保护，身份变化永久失败。

当前集成阻塞：

1. `FrameworkAccess` Java/Native 仍是 11 fields，Native 查询还拒绝 static，需要增加第11项并保留0..10索引；同步 ApplicationAdapter、probe_framework、计数/校准/verifier。
2. `application_adapter.c` 的 physical/create 仍调用 managed currentApplication/currentActivityThread；这些方法被 Hook 后，JNI 调用它们并不构成物理读取，需改固定 JNI static 字段。
3. `ApplicationAdapter.create` 仍要求 guest defining loader 等于 selected loader；应检查 `Class.forName(guestClassName,false,selectedLoader)==guestClass` 并验证两条 loader lineage，增加自有委托 loader fixture。
4. 构造期间逻辑 Application 视图仍未解决；当前视图在 factory/Application 构造后安装，不能宣称覆盖第一条业务指令。

`build-workbench.py`、`build-runtime-probe.py` 已把 FrameworkAccess/ApplicationAdapter/PhysicalApplication Java 纳入输入及回执，但尚未包装/加载新 Native 核心，不能解释成产品切换完成。

### 5.2 脚本编译和控制

源码：`android/runtime/src/dev/tripoder/runtime/script/` 的 15 个文件及 `RuntimeHookBridge.java`；文档：[script-control](self-hook-script-control.md)。

- 已实现 source→受限 AST/完整计划编译，复用 prepare(source,evidenceId) 协议与同一 sourceSHA；精确 BigInteger/浮点、参数 CAS/不可变版本、原 Throwable、original 最多一次、DRAINING/UNKNOWN 不 ACK 不重放。
- `RuntimeHookBridge.installSelfHookControl(SelfHookControl.Platform)` 是新接入口；单 handler、单线程 submit 及容量竞态已修。当前 handler 未安装，Frida 仍覆盖 submit；Platform 的实际 observe/liveness/generation/prepare/publish 未接入。
- 593 主机检查：58 Babel 正例/18 反例、256 IEEE 对照、SDK 模板、8线程8000次、token60秒/代次/CAS 等。报告 `android/runtime/script-tests/build/report.json`。
- 第一版仅 ASCII 标识符和简单 TS profile；高级 TS/generics 等明确拒绝，不等于完全 Babel 等价。

自有真实 ManagedHook 夹具位于 `android/self-hook-probe/script-fixtures/`，已有 README/integration.json/check-host.py。14 主机检查/API37 编译/D8 通过，但 deviceTested=false、managedHookTested=false。

- 构建只收集 `script-fixtures/dev` 与 runtime/script，不能把 host/build 打包。
- Activity `.ScriptControlProbeActivity`；新 runId、artCompilation=jit|aot、ownedScriptScope=true。
- schema1 / phase `H5-owned-script-control`，productBackend/fullCallerCoverage/authorityApprovalPath 均 false。
- 4 个 owned 方法和 4 个直接 caller 有 APK DEX 核对；预期 Native original 次数 integer504/wide100/single100/failure101；physical body 次数 integer505/wide100/single100/failure101；4 轮 publish/retire。
- 待接入 Activity、构建、runner case、独立 verifier，然后才跑自有设备；不能把这个夹具当审批链产品验收。

### 5.3 Native scalar ABI：未完成代码

源码：`android/self-hook/native/abi/{scalar_abi.h,scalar_abi.c,scalar_call_a64.S}`；测试 `android/self-hook/tests/abi/`；自有夹具 `android/self-hook-probe/abi-fixtures/`。

- 支持 I32/U32/I64/U64/F32/F64 和 void 返回，最多16参数，独立8 GPR/8 FPR、8字节溢出槽、SP16、raw bits 和 errno；struct/vector/varargs/未知类型拒绝。
- 2103 纯 C ASan/UBSan 检查通过，**assemblyExecuted=false**。独立库已编译到 `android/self-hook/tests/abi/build/libtripoderscalarfixture.so`；11 个类型化 C 对照及4线程4000次 errno 夹具未执行。
- **设备运行前必须先修 `scalar_fixture.c` 的 pthread_join 失败路径：当前只计失败，worker 可能仍使用即将离开作用域的栈状态。必须确认线程已结束，或结束自有测试进程，不能带着活线程释放状态。**
- 尚无 receipt、README、自动构建脚本、Activity；无 LLVM/CFI/BTI ELF 审计；没有安装 Hook。地址对齐/高16位检查不能证明模块真实性或租约有效；PAC/异步信号/异常展开未验证。

## 6. 验证规则与常用命令

一次只能有一个负责人安装/启动探针。构建或验证进行中不改其输入源码。每次用全新 runId；receipt、APK SHA、平台和 sourceDigest 必须一致。旧报告保留为历史，不能追认成新源码通过。不得通过清缓存、弱化 gate 或复用旧 AOT proof 掩盖失败。

以下命令在项目根目录执行；先查看脚本 `--help`。设备授权沿用仅自有探针范围，接手时确认连接与产物身份。

```sh
rtk proxy python3 android/scripts/build-self-hook-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --key android/runtime-probe/build/acceptance-only.p12 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so

rtk proxy python3 android/scripts/run-self-hook-case.py \
  --activity abi --expected jit \
  --evidence-root docs/evidence/self-hook-h3h-2026-10-15 \
  --adb /opt/homebrew/share/android-commandlinetools/platform-tools/adb \
  --serial d861a4fa

rtk proxy python3 android/self-hook/tests/coverage/check_loaded_classes.py \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --art android/self-hook-probe/build/device-libart.so
```

runner 不安装、不执行 AOT 编译、不清缓存；安装需单独操作。支持 activity abi/active/main/framework/application；cold 仅用于 abi，接受实际已编译 JIT 或 AOT，不是后端 fallback；严格 jit/aot 模式继续保留。AOT 要传匹配 APK 的 `--aot-proof`。

独立 ABI verifier：`android/scripts/verify-self-managed-abi.py --evidence DIR --run-id UUID --expected jit|aot|cold --sdk SDK`。独立输出采用排他创建，不能复用旧 verification.json。

冷启动聚合：`android/scripts/verify-self-cold-start.py --before DIR --after DIR --output NEW_JSON --image-kind runtime|aot`。要求相同已验证 APK/build/source、不同 PID 和时间顺序、准确映像文件及方法地址；AOT 匿名映射名称可中间截断，应通过实际映射/方法地址认定，不能仅匹配完整包名。

其他主机入口：`android/self-hook/tests/check_host.py`、`check_dex_graph.py`、`check_helper_dex.py`、`check_caller_closure.py`，`android/runtime/self-hook-tests/run.py`、`check_physical.py`，`android/runtime/script-tests/run.mjs`。具体参数以各脚本为准。

## 7. 历史H3h接手顺序（已被顶部当前点覆盖）

1. **关闭当前 H3h 验收缺口。** 检查 cold 实际 image Class 归属，修正独立 verifier，保持移动参数/container/roots 验证；重建并以全新 runId 跑同构建 JIT、AOT、cold，不清编译缓存。同步新64槽存储文档。
2. **接通真实物理身份 F0。** 完成12字段注册、static JNI读取、ApplicationAdapter两项修正及自有 Android 验证，补构造期身份边界。
3. **调用者和入口所有权。** 先修 exact static 图精度，再集成 loaded-class 观察器与实际 GC/CheckJNI；只读采 PAC 平台事实，在准入/动态 FDE/异步展开成立前不得直接启用 ART PAC patch。覆盖系统失效、直接写入口、未来类发布，不能以快照代替完整闭包。
4. **脚本实链。** 接入 owned script fixture，在真实 ManagedHook 上验证，再连接产品 Platform；保持产品审批/UNKNOWN 门槛未验收状态直到端到端通过。
5. **Native ABI。** 首先修 worker 生命周期问题，补独立指令/CFI/BTI 与回执审计，跑自有11例/并发 errno，然后才接真实 Hook 与模块租约。
6. **产品完整适配。** 完成剩余 manager、固定成员和全部必需 installers；复用已有业务状态与控制链，完成组件、存储、Hook、失败、死亡/恢复回归。
7. **最终切换。** 只有新后端能力与产品验收齐备，才统一切换 RuntimeEngine/TS/构建入口并移除 Frida。验证源依赖、APK/SO 产物、进程实际加载库均无 Frida，并更新 task.md 和证据索引。

可并行拆分为 caller/entry ownership、runtime adapters、script/native ABI 三块；共享核心API、构建和设备应由一个集成负责人串行控制。已有子 Agent 均已停止，不会继续修改文件。

## 8. 历史H3h启动说明（仅保留历史，不是当前指令）

> 请先阅读 docs/self-hook-handoff-2026-10-15.md、task.md、docs/self-hook-design.md 和 docs/self-hook-adapters.md。当前产品仍依赖 Frida，不能标记完整替换完成。保留全部已有修改和历史失败证据，从第7节优先序继续；先解决最新 cold 独立验收，再完成 F0 和调用者/入口闭包。所有设备操作限自有 dev.tripoder.selfhookprobe；所有 shell 使用 rtk。不做兼容/fallback，不削弱验收条件，不清缓存制造通过。每批更新 task.md、来源回执和独立验证，并明确主机/设备/产品三个完成层次。
