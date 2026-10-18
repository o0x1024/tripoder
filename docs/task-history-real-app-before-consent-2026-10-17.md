# 真实应用验收：用户手动协议操作前的历史记录

下文为历史状态，当前状态以 task.md 第 0 节为准。

## 0. 当前执行状态（覆盖下方 H37 历史快照）

- 真实 ART/JNI 首轮32：21 项功能断言通过（C Hook 6 hits，ART Xlog.getLogLevel(long) 4 hits / 0 errors，均已停用），但结束把完整报告再次经 Binder 返回，触发 Workbench TransactionTooLargeException（1071640 bytes），整轮仍判失败。已改为 fsync 完整报告后仅返回小型 SHA-256 回执，主机强校验文件与回执；`product-wechat-managed-jni-33` 重跑中，生产 Hook 未改。
- ART 补充真实目标检验：`product-wechat-managed-jni-32` 正在对微信原始 Xlog.getLogLevel(long):int 的 native Java 声明执行 ART Hook 原值转发，另同时验证 C 标量函数；历史 abort 的精确失败谓词仍未证明。已只读搜索三个 Native 证据根的 24 个核心制品，未找到原 BuildID 93c7fe2591d4888a560c674d61d219a2013fcabb，见 art-binary-availability-28.json。
- 王者 Native `product-honor-native-29` 尚未进入 Hook：singleTask 将 guest 放到实际 task3490，gateway 在 task3489。旧回收只支持两者同任务，因此原 LIVE 票据继续阻止新启动。已让实际根任务以 tripoder.ticket/component/generation/delivery 与持久启动记录匹配后回收；root RETIRED 且 consumed gateway 的空壳独立清理，不改业务历史。新增资源验收要求未回收的 UNKNOWN/未确认票据保持逐项一致；`product-honor-task-roots-30` 8 项通过：真实任务3490与空gateway3489分别确认消失，13 张其它未确认票据逐项保持不变。Native `product-honor-native-31` 已通过冷启动/连续观测，但在协议页因 TARGET_MODULE_NOT_LOADED 停止，未安装任何 Hook；目标 libmarsxlog.so 要进入后续流程才能观察，等待用户协议授权。
- 正式当前构建控制链：`product-control-service-24` 92 项通过，包含 16 次 Native 声明激活/停用；`product-hook-ui-service-25` 11 项通过，含实际调用、停用及下一次启动恢复；Agent normal `agent-normal-service-26` 13 项设备 + 18 项 MCP 检查通过，disconnect `agent-disconnect-service-27` 9 项设备 + 16 项 MCP 检查通过；不代表 Sentinel 客户端端到端验收。
- 最新性能 `performance-preconditioned-23` 已按预先定义的 1 对预热 + 5 对测量完整完成：无中位数 >5% 退化，但基线各路径波动约 20%–35%，frequencyPolicyEnvelopeUnchanged=false，performanceAccepted=false。CPU5 2.7648→2.1888 GHz 与旧 C 181→225 ns 同步变化；原样本全部保留。仅只读查验了官方 fixed-performance 命令，尚未更改模式；已准备 run-fixed-performance-benchmark.py 并异步征求临时启用/结束关闭的授权。
- 当前源码 P4：`p4-service-current-21` 105 项通过，Runtime APK 98d81c410b8f0aacf6da5affea4d51bc8e029fec0cf01d01075e006ff707a3de。当前 Probe 7104c67ca7cf9ab8639c8391e8c75256aa11b9f54a47c3727a0cf19dae4b69e9，451 输入一致；`jni-service-current-22/e481ffeb-5674-4cc7-9fb8-de618cd888a4` 设备与独立 verifier 均通过 33 项，400 次外部线程调用。性能 runner 已补充预先声明的预热轮与 CPU 频率策略前后证据，仍保留所有原样本及原 5% 门槛；尚未执行新性能轮。
- 正式王者首启 `product-honor-first-20` 8 项通过，30.274 秒 18 样本、最大间隔 1872 ms，无 Java/Native fatal，截图仍为腾讯协议页。项目 be87df1a-ea8c-45c0-8834-507eaa7d87cb / binding 3e46387c-b771-45c2-a581-2d2e223e5252 / install 5e3947bf-973d-4126-8fea-8f4a5e3a026e，space p6-20261014。已再次异步询问用户同意协议方式，无回答时不能点击。libmarsxlog.so 的同名无参标量查询已静态核对，尚未 live Hook。
- 微信停用后再次冷启动：`product-wechat-after-disable-19` 8 项通过，保留原安装/数据/Hook 历史，无 Java/Native fatal；这是从正式控制入口显式新启动，不能等同自动恢复旧 Hook 组件。正式王者首启已通过；Native `product-honor-native-29` 接续验证，未同意协议或登录。
- 真实微信 Native 当前通过：`product-wechat-native-17` 16 项通过，原 libwechatxlog.so 的 __xlogger_Level_impl 真实命中 257 次 / 0 errors；DISABLED 回执→IDLE 释放、5 秒后仍 IDLE、activeReferences 恢复为 0、原 APK SHA 不变。新服务请求准入修复已在原数据冷启动验证，无 Java/Native fatal。当前正式 Engine eba6a12f5f65dd04091336fd73b0ee96719cd86dd733094a0811d00eceeb26b0，Workbench a8cfab688d517bcbdc3c624e1bb5a5fb6c2b2b69e6f828d65eb7da46a9e30e78。尚未登录后业务验收，下一步停用后再次冷启动与正式王者。
- Native 重跑 `product-wechat-native-15` 在启用 Hook 前冷启动失败：新代际微信 bindService 被旧 Service lease 的 hookRecoveryBlocked 拒绝（HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START）；之后的 COMPONENT_PEER_MISMATCH 为死亡后的次生入口冲突。已将 ServiceLedger 新 SERVICE_ROUTE 请求与系统恢复分开，新请求仍要求同一组件所有权、旧代际 DEAD、未关闭；系统恢复继续保留旧 Hook 屏障，不清 hookOperation/UNKNOWN。主机 16 项边界检查与 SDK 244 源编译通过，正式产品重建复测中。
- 真实微信 Native 首轮 `product-wechat-native-13` 已绑定原 libwechatxlog.so / BuildID、安装 __xlogger_Level_impl 原值转发，停用回执 21 hits / 0 errors；整轮因验收脚本在后续 IDLE 状态读取 hits 字段而失败，不算完成。已依据既有 DISABLED 回执→IDLE 协议修正验收脚本，生产 Hook 未改；待重跑模块引用恢复检查。
- 控制冻结修复设备验证通过：`product-wechat-session-11` 正式微信 8 项通过，30.503 秒内 20 个连续样本、最大间隔 2293 ms；Workbench 真正 isForeground=true 且内核 cgroup frozen=0，旧冻结原因已闭合。尚未等同登录后业务验收。真实 Native 目标改用同库无参 `__xlogger_Level_impl`（原二进制与 Tencent Mars 声明核对）进行原值转发，不把不支持的 JNI 指针参数伪装为整数。
- 控制端生命周期修复已实现：`WorkbenchSessionService` 用普通 Android specialUse 前台服务承载用户建立的控制会话，失败时停止；不启动 Agent 端口，不修改系统冻结设置。目标稳定性验收增加采样最大间隔 5 秒的连续性断言，原 30 秒存活门槛不变。当前正式 Engine `3a8ff8650996617fdfa1523b5114bd77a5a362c2e3a6cc53ce8aaefa11bb5f6f`、Workbench `bd72992ef76c444f297023148fe9d0e60205bbb05773f24824d80b211d5ee224`，真实微信复测中。
- 控制查询超时首因已定位：正式微信 `product-wechat-preferences-09` 第一笔 hookProcesses 已返回；Workbench PID 4869 的 Android 状态为 instrumentation/FGS、isFrozen=false，但 `/sys/fs/cgroup/apps/uid_10401/pid_4869/cgroup.freeze=1` 且 events frozen=1，持续存在。authority 线程无请求阻塞。MIUI 在客体前台后冻结无前台 Service 的 Workbench，连诊断线程也暂停；不能再归因为 Binder 死锁。计划补齐用户启动控制会话的真实前台服务生命周期，并增加稳定性采样连续性断言。
- 最新偏好存储修复：`preferences-interrupt-18` 89 项设备检查通过，APK `3dc563b9d5f0b2a5057cf8120185c4a0b5a51e61dfd807943eba308399cdfc31`；主机等待语义 9 项通过。正式微信 `product-wechat-memory-06` 仍未通过，捕获微信子进程 `PREFERENCES_WAIT_INTERRUPTED` 退出和控制查询 300 秒超时，均留证；欢迎页截图不是完整验收。已修复读取等待和已排队写入的中断边界，重建正式产品待复测。
- 跨代际空任务回收：`product-empty-task-05` 验证任务 3405 的真实 Android 移除，3 张同安装不同代际票据均有独立 DEAD 会话证明，数据与旧 UNKNOWN 不删除。
- 最新续接：`process-memory-late-17` 81 项设备检查通过，Runtime APK `47ca1ff8ba00e3e12ef229c8eecc902ef4fb2956c2dcd310e329e428e8d8fc49`。真实微信 Matrix 延迟退出的 `getProcessMemoryInfo([I)` 已补真实系统查询、请求顺序及当前安装 PID 边界；发送方等待超时只标 operation UNKNOWN，不再撤销已提交的广播准入。10 秒冷投递验收未变。
- 正式微信 `product-wechat-memory-02/03` 尚被旧入口/任务挡住：精确未派发入口 `a98ceef6-2d32-42e3-840b-d59211c4ea43` 已按生产协议关闭，保留 UNKNOWN。任务 3405 为同安装跨进程代际的空任务；旧自动回收的同代假设导致 `reclaimed=[]`。正在修复为逐票据核验安装/会话死亡和 Android 任务移除证据。正式产品与 Probe 的旧验收不代表当前源。
- **最终目标未完成，Goal active。** 用户范围为真实微信/王者正常运行及真实 Hook 生命周期，不能以 fixture 或 30 秒启动观察收口。未提交/推送。
- 设备 `d861a4fa`；协议与账号操作需用户决定。王者已到真实协议页，异步确认仍无回复，未代其同意。截图：`docs/evidence/real-app-goal-2026-10-17/honor-pending-01/terms-page.png`。
- Runtime 当前 APK `f142629df1fe399736f18a437407f94ae475620dda07df1b4dae7b994748e162`，源码 receipt 一致。`receiver-local-15` 77 项通过；原微信空间 `wechat-receiver-local-06` 6 项启动观察通过，无 Java fatal / Pending ingress failure，业务和 Hook 尚未验收。
- 原空间必须复用：微信 `target-3be31e2dd7434859-wechat`；王者 `target-7d7c5dd3de0d4342-honor`。王者原 APK SHA `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`，微信 `f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d`。
- PendingIntent/Alarm 已统一 authority gateway，创建 token 不预占进程槽，实际投递复用 BroadcastLedger；schema31 保留旧路由、UNKNOWN 与撤销回执，关闭旧直连入口。真实显式/动态隐式广播、有序完成、exact Alarm、双进程死亡后冷投递通过，冷全过程约 7.3–7.5 秒，10 秒门槛未变。
- 退出历史通过 Android `setProcessStateSummary` 的 68 字节 generation/data 标记关联真实记录，reason/status/timestamp 不伪造；不同安装不可混入。普通 Java onReceive 调用与持有真实 PendingResult 的系统派发分开处理；没有制造票据或回执。
- 正常并发 PI 修改在 Broker 锁外串行化；真实持久 UNKNOWN 继续拒绝重放。微信旧 `de24d322-8639-4313-bd37-796a618f5542` 当时被 requireIdle 拒绝，但只读快照未发现微信仍持有未关闭 owner；不是删 UNKNOWN 的理由。详见 `pending-journal-state.json`，新的 inspector 可读取微信全部 mutation。
- JNI ABI=4：移除两份结果传输数组，约 192 → 120 B/call；原生线程 attach-as-daemon 一次、pthread 退出时 detach，已有 JVM 线程借用。当前 `jni-current/76e5e24f-68da-4e03-b818-036cf809aca5` 已独立验证 33 项，400 次外部线程调用，4 次附着/4 次退出 detach、在途租约归零。
- 当前 Probe 已重建：`fdbc0b335974855f6ddb8eac68693a9f7da9a877691d3df8c833f6d9c4482931`，451 个输入核对一致。benchmark 交替覆盖安装旧/新探针；现已恢复当前 Probe 并完成 JNI 独立验证。当前无运行中的构建或设备 validator。
- ABI4 五轮 `performance-jni-v4` 已完成：JNI 均值约 -14.6%，外部线程 JNI 均值约 -91.1%，无中位数 >5% 退化；但基线波动超 5%，**performanceAccepted=false**。测量时本任务微信仍在后台；全部样本保留。已停止 Runtime/Engine/Workbench 的任务负载并归档前置条件，独立 `performance-jni-v4-quiescent` 也已完成并拒绝：CPU5 的 scaling_max/cur 观测为 2188800 kHz，C 基线从首轮 181 ns 变为后续约 230 ns；说明固定 CPU 掩码并不保证频率稳定。全部五轮样本保留，阈值未变。后续应先完善频率资格证据，不得靠择优重跑宣称稳定。
- ART 历史 `TP_MANAGED_ENTRY_OWNERSHIP` abort 确定根因仍未证明；原 tombstone、原 guard 重放与更多现场诊断均保留。禁止把未复现写成修复。入口 guard 未放宽。后续入口准备合并为单次一致暂停；纯 helper DEX 模板/框架枚举在全局解释策略之前计算，实际入口发布仍在策略之后。独立 DEX 93 项/36 文件通过。
- `managed-admission-current/18c53fb3-1590-4af2-97af-eb1bb6a79a34`：739 项、640 次切换、688 次实际 GC、33 组 Native root 移动通过，仍明确 historicalAbortRootCauseProven=false。
- **正式产品已恢复当前工作区构建，不再是诊断重放包。** Engine `f1fed85826e9a07f5d5111cf58035c8b23f6de2a5c7e80346c0f1866c34dd179`，Workbench `57eb02e346994db0761226a36e04cc16c226e368e9c3e663a3baf3c1445b6a82`。406 个输入一致；`product-control-current` 92 项通过，含 16 次 Native 切换，无 fatal。`product-hook-ui-current` 11 项已通过，含实际调用命中、停用和下一次启动恢复。Agent、当前源 P4、正式产品真实目标和登录后业务仍待验收。
- 真实 Native Hook 候选已从原微信 APK 提取：`libwechatxlog.so` 的 `Java_com_tencent_mars_xlog_Xlog_getLogLevel`，DEX native `(J)I`，JNI ABI 为 `JNIEnv*, jobject, jlong → jint`，RVA `0x10014`。仅候选，尚未 live observe/prepare/call；证据 `real-native-hook-candidates/`，不能据静态分析声称 Hook 成功。
- 下一步：完善性能环境资格与诊断；正式 Agent 与实际产品目标、当前 P4；继续 ART 根因；在用户处理协议/登录后验证业务与真实 Hook。任何设备脚本串行运行，构建/验收期间冻结源输入，不清数据库/缓存/UNKNOWN、不扩槽、不用 root/run-as、不加 fallback。

以上新证据均在 `docs/evidence/real-app-goal-2026-10-17/`，JNI/ART 前期证据在 `native-core-rootcause-2026-10-17/`。失败轮次均保留。详细机制见 [PendingIntent 状态](docs/runtime-pending-intents-plan.md)、[Native 续接](docs/self-hook-native-followup-2026-10-17.md)。

本文件只写**当前状态与下一步**。历史快照已归档，不要按旧状态倒退：

- H25–H36：[docs/task-history-h25-h36-2026-10-16.md](docs/task-history-h25-h36-2026-10-16.md)
- H25 之前：[docs/task-history-before-h25-handoff-2026-10-16.md](docs/task-history-before-h25-handoff-2026-10-16.md)
- 最近一次详细交接：[docs/runtime-handoff-2026-10-16-h37.md](docs/runtime-handoff-2026-10-16-h37.md)
- 文档总索引：[docs/README.md](docs/README.md)

