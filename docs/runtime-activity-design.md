# Tripoder Activity 解析、实例和任务设计

状态：2026-10-14 设计评审补充，分批实现中，尚不代表 P2 全量通过。独立研发，仅使用 Android SDK/平台行为和自研夹具；不使用 VirtualApp 代码，不回退宿主启动。

## 已确认的结构问题

1. 原 `RuntimeSlot` 将进程预约和 Activity 组件身份合在一起。同一个逻辑进程内两个不同 Activity 被系统视为同一 manifest 组件。standard 顺序创建可以工作，singleTop/singleTask 和 CLEAR_TOP 则可能错误复用。仅修改进程内的 `mActivityInfo` 无法改变 AMS 已经做出的任务决策。
2. `RuntimeComponents` 原来先匹配 ActivityInfo，Engine 则最后一项覆盖。真实王者荣耀的同名 Activity 和 alias 会造成两端解析不一致，且 ArtifactSet 原来直接拒绝该 APK。
3. 原来的实例 ticket 同时代表一次启动；实际系统复用实例的 `onNewIntent` 不能覆盖旧实例身份。必须区分组件租约、实例身份和启动交付。
4. 结果映射与 callback capability 尚在内存，带结果的冷恢复继续明确拒绝。不能把旧 generation 重新启用，也不能用手动回调补偿。

## 单一组件解析

保留原始 manifest 声明作为审计输入。安装代次内建立统一的有效 ActivityInfo 索引，控制器、Worker、PackageManager 查询和实际启动共用同一选择规则。

`ResolvedActivity` 包含请求组件名、实际实例类、有效 ActivityInfo、声明/filter 与不可变安装代次。alias 的可见 ComponentName 与权限/filter 属于 alias，实例化 targetActivity；process/launchMode/taskAffinity 采用平台解析后的有效信息。不能把 alias 不支持的原始属性当成有效行为。

已在当前小米 Android 17 真机只读确认：王者 `com.tencent.tmgp.sgame.douyinapi.DouYinEntryActivity` 最终解析为后声明 alias，target=`com.tencent.tmgp.wdsdk.douyin.common.DouYinEntryActivity`，launchMode=`LAUNCH_SINGLE_TASK`，affinity=`com.tencent.tmgp.sgame`。这只是系统安装版本的解析证据，不是客体运行通过。

隐式解析限定当前空间，并使用 Android IntentFilter.match。必须处理 DEFAULT、MIME/data/categories、package、enabled/exported/permission。歧义需明确拒绝或空间内选择器；不调用宿主 chooser。尚未实现的 filter 类型直接拒绝。

## 进程与组件租约分离

复用 ProcessSupervisor、唯一 RuntimeStore 写入者和 ActivityLedger，不另造虚拟任务栈。

- ProcessSlot：物理 Android 进程、space/package/install/process/generation，确认死亡前不能换身份。
- ActivityComponentLease：space/package/install/逻辑组件/processSlot/physicalStub/launchMode/affinityGroup。
- Activity instance ticket：真实实例、taskId、当前 generation 和可恢复状态。
- Launch delivery：一次启动的逻辑 Intent 摘要、源实例、目标组件租约、实际创建或 newIntent 收件状态。

manifest 中生成有界 stub 池，实际 launchMode/affinity 必须在 Android 调度前已确定。同一逻辑 affinity 跨进程使用一致的物理映射，不同空间分离。耗尽显式失败，不串用其他身份。

首次创建走真实 Factory→CLAIM→CREATED；复用走系统真实 onNewIntent→验证 delivery→原客体回调→ACK。不手动调用 onNewIntent，不自动 setIntent；getIntent 是否改变由客体决定。旧实例 ticket 与新交付 ID 分开。

## 实施与验收顺序

1. 统一有效解析、alias 实例化及同名声明。真机自研夹具证明 target 类、alias 可见名/权限/进程一致，跨包拒绝正确；保留标准 Activity/Service/Provider/Receiver 冷恢复回归。
2. 分离组件租约，先 standard 与 singleTop、普通无结果 Activity 启动。真机证明同进程 A/B 不错复用，顶部复用、非顶部新建，真实 onNewIntent 次数和 getIntent 行为正确。
3. singleTask/CLEAR_TOP、affinity 与 Context 启动。真实 A-B-C→A 的销毁顺序、taskId、Back 路径、跨空间不混栈；保留 Android 后台启动限制，准备成功不能冒充显示成功。
4. 持久化在途结果：sourceTicket、requestCode、targetTicket、operation、结果授权和实际收件阶段。进程死亡后轮换 capability，在 Android 实际 onActivityResult 后确认收件；ACK 丢失 UNKNOWN，不重放业务回调。
5. immutable Activity PendingIntent：真实系统 token、持久 creator/space/install、filterEquals/requestCode 身份；UPDATE/CANCEL/ONE_SHOT 与创建者死亡实测。mutable fill-in、未知 token 和未实现类型继续拒绝。

每一批证据绑定实际 APK 摘要、源码摘要、设备版本、原始结果和日志。入口通过不等于微信/王者全量可运行；只有两者约定核心场景及用户接管登录完成后才能判定 P6。

## 已落地与在验收改造

schema 8 的统一索引、alias 和独立 standard 组件租约已通过 `activity-components-01` 的 340 项真机检查，同构建 P1 `activity-components-p1-01` 199 项通过。每进程有界生成 16 个 standard 组件，所有源文件均低于 2000 行。

后续 schema 9 增加独立 `activity_launches`：候选实例 ticket 与实际 receiver ticket 分开；PREPARED→ENTERED→RECEIVED，异常/进程死亡为 UNKNOWN。singleTop 复用时不覆盖旧实例。无结果启动的目标契约是在真实 create/newIntent 回调完成后确认；schema 9 的 create ACK 曾位于 onActivityCreated，后续评审发现它可早于客体 onCreate 返回，schema 10 改到 onActivityPostCreated；for-result 仍等待真实结果收件。只接精确 `Activity.performNewIntent(Intent, ComponentCaller)` 签名，设备不具备时必需适配器失败，另一旧分支明确拒绝。

客体启动的有界逻辑 Intent 摘要绑定 delivery；控制器准备接口当前允许可信控制器补充 primitive extras，未宣称其为已封存的用户授权产品。真实 ComponentCaller 对象保留，package/referrer 提供来自 authority 的逻辑来源；UID 保持实际共享 UID。caller URI 权限查询仍拒绝。单参/双参 setIntent 由 Android 原实现处理，新 Intent 不自动覆盖旧 getIntent。

singleTop 新增 16 个物理组件/进程，仍未接入 NEW_TASK、自定义 affinity、singleTask 或通用 Context 启动。该源码状态需新构建验证，不复用 schema 8 的通过结果。


## 生命周期租约复核（schema 10，等待新构建真机验收）

结果收件、实例销毁、系统任务引用是三个不同事实。FINISHED 不能跳过后续 postDestroy；destroyedConfirmed 绑定 generation/instance，只接受 finishing 销毁，重建 claim 清零。preDestroy 捕获当时的真实 taskId，postDestroy 保存证据。每次物理组件分配具有独立 lease UUID，历史 ticket 不会错误保护或释放后来的槽位占用。

回收先在 SQLite 事务外获取系统任务快照，authority 同步串行保持账本不被并发修改，再使用当前 lease 及全部关联 ticket 判断。所有实例必须终结且获得 finishing postDestroy 或对应进程 DEAD；其 taskId 仍正向可见，baseIntent/baseActivity/origActivity/topActivity 均不引用该 stub。无实际实例只有已确认 NEW_INTENT 并绑定 receiver/instance 的候选可以豁免；未知启动继续占用。

getAppTasks 缺席不是任务删除证明：Android RecentTasks 可将仍含 Activity 的任务移至隐藏集合。因此移除缺席即 REVOKED 的恢复逻辑；旧版本这种 REVOKED 显式迁移为 UNKNOWN，不将历史推断提升为完成证据。根组件、无法观察任务继续保留，后续需要可信的任务移除流程。install 与 process 复用也检查这些组件租约。当前未映射的 allowTaskReparenting/documentLaunchMode 明确拒绝。

验收新增：同一活进程顺序运行 24 个不同子组件，超过 16 槽容量；一个组件真实 recreate 后再完成；核对 24 个不同 lease、复用物理槽位及每个 finishing 销毁确认。原回调内立即再次启动、单组件多实例、进程死亡及冷恢复继续回归。这不替代根任务删除、隐藏任务、全部迟到消息验收。

后续 singleTask 设计还需两项修正：alias/target 以 targetActivity ?? name 作为系统任务匹配身份，每次 requestedComponent 单独授权并保留到 Intent；affinity 使用 (space, logicalUidDomain, effectiveAffinity)，首批 logicalUidDomain 为 packageName，避免宿主共享 UID 错误合并不同虚拟包。王者全局 allowTaskReparenting 必须真正实现后才能进入目标验收。

平台参考：[RecentTasks](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/RecentTasks.java)、[ActivityRecord](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityRecord.java)。这些平台结构用于设计审查；当前 Android 17 设备行为仍由独立夹具证明。


## 任务观察与历史引用（schema 11，待验收）

schema 10 的单一 positive-recents 策略在历史 Task 已关闭时会永久保留有限槽位。进一步只读审查 Android 公共调用链确认：getRunningTasks 的默认参数不使用 visible-recents 过滤；服务端遍历 attached leaf tasks，同 effectiveUid 的任务可见。联合 `AppTasks → RunningTasks → AppTasks` 并集可覆盖当前封闭运行器的自有任务；RunningTasks 跳过空/全 finishing 任务，而持久根 Intent 由 AppTasks 补充。查询异常或返回上限不参与回收，不能将其他 UID 或不同 Android 实现直接纳入已验证范围。

新增 preCreate 实例握手，在客体 onCreate 之前正向确认任务根属于宿主；外部 UID 任务、reparenting、外部组件混入和 PendingIntent 尚未放开。全部实际实例终结并且 finishing 销毁已确认或 generation DEAD，且无未决 claim 时，联合观察缺席可作为当前范围“没有可恢复任务引用”的资源证据，不冒充收到 taskRemoved。账本查询与回收在同一 authority monitor 中串行，系统 Binder 查询不在 SQLite 事务里；每个候选按独立 lease UUID 关联本代 ticket，旧消息不能修改新占用。

永久退役的物理名称属于另一种证明：PM 明确不再声明该 Activity、Factory 显式拒绝该旧名称、对应进程 DEAD、此后不再复用这个名称。schema 11 对旧 RuntimeSlot Activity 记录写入 RETIRED/resourceProof，仅解除物理进程占用；数据与未知业务结果仍保护。当前历史数据均来自自研 Runtime 探针；商业迁移不能直接把来源不明的历史 task 当作已验证自有任务。

真机用两个真实 document task（maxRecents=1）产生 hidden-recents，要求最近任务中缺席但运行任务中仍存在，并在实际 root finishAndRemoveTask 后两类观察均消失。该测试必须通过后才能接受本平台上的联合观察。随后重新运行 24 child 回收与原完整限定回归。

参考：[RunningTasks](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/RunningTasks.java)、[ActivityManager API](https://developer.android.com/reference/android/app/ActivityManager#getRunningTasks(int))。

本批 schema 11 限定真机证据：`activity-leases-04` 364/364，详见 evidence README；只证明上述自有任务观察与组件租约场景，同构建其他阶段回归另记。

目标 manifest 容量复核（只读，非启动验收）：微信当前 APK 原始声明含 34 种 affinity 写法，launchMode 0/1/2/3 数量为 1707/243/126/23；王者包含默认/sgame/diff/simcpux 四种写法，0/1/2/3 为 53/11/21/2，application allowTaskReparenting=true。alias 有效属性仍须经统一平台索引解码，不能直接使用这份原始计数调度。affinity 池必须按实际活动租约分配并明确容量，不能为全部 manifest 声明永久占位；商业目标的多种 launchMode/reparenting 不得用 standard 静默替代。

## 来源实例与结果身份（schema 12，在验收）

ROUTE 的身份由 Binder PID/UID、process generation、source ticket、source instance 联合确定。来源必须已正向 attached，允许真实 onCreate 中继续启动；完成进程准备后的第二次校验拒绝已销毁、重建或换代的旧来源。请求摘要包含来源代次、票据、实例、目标组件、结果模式和 payload 摘要。账本保存 sourceTicket/sourceInstance/callerComponent/callerKind，不能由客体自报调用包名。

结果 API 区分三类：可信控制器入口保留 Android 的真实调用方；客体 for-result 使用已认证的逻辑来源组件；普通启动 getCallingPackage/getCallingActivity 为空。历史记录缺少来源时显式 UNRECORDED，不将同 UID 推断成控制器身份。ComponentCaller 的 UID 仍为真实共享 UID，逻辑包名来自 authority；结果进入前核对接收 ticket/当前 instance，允许同 ticket 的真实重建；客体回调完成后才 ACK，异常为 UNKNOWN。结果回调作用域退出必须清理，不能污染下一次 onNewIntent。

本次同时修复物理资源永久占用：只有正向 OWNED_TASK_ATTACHED、对应进程 DEAD、完整 recent/running/recent 联合观察不再存在 taskId 的旧实例，才可写入 RETIRED 和具时间/代次的资源证据。未 attached 或任务仍可见的记录继续保留。该状态只释放物理槽，不提升操作/交付/UI 结果，也不允许重新播放不确定副作用。

新自研夹具覆盖来源缺失/伪造实例/不存在票据、真实 recreate 后旧实例拒绝、alias 来源跨进程、目标重建保持调用方、普通启动空结果身份、结果回调 ComponentCaller 和持久化来源。前两次运行因 getter 二次映射失败，完整记录见 `activity-source-01/02`；修正使用一个 getCallingActivity 映射入口，成功证据待后续运行更新。

平台语义依据：[Activity 调用方 API](https://developer.android.com/reference/android/app/Activity#getCallingActivity())；平台文档不替代本机验收。

## 任务复用下一批必须补充的约束

1. canonical target 用于任务匹配仍不足以完整表达 alias：Android 的 reparenting singleTop 去重还比较请求 Intent.component。静态 physical activity-alias 必须保留这层身份，不能仅把 alias 存在 extra。真正放开 reparenting 前应有相邻不同 alias 的实测。
2. affinity 使用平台解码后的 ActivityInfo.taskAffinity，跨声明 process 共享同一映射；原始 XML 相对名称不能自行猜解。有限租约池不得变成每进程一个固定 affinity。
3. RESET_TASK_IF_NEEDED 及真实 task 迁移是 reparenting 验收的一部分；只接受 manifest 标志而继续禁止触发 flags 不构成支持。迁移可能没有 destroy/create，taskId 必须以实例绑定刷新。
4. singleTask 冷复用必须测试“路由先准备新 Worker → Android 重建旧 initial Intent → 投递新 Intent”；ACTIVITY_CLAIM 已有 DEAD generation 的 restore/rebind，不在 Factory 复制恢复路径。
5. singleTask clearTop 命中根后可能把 task.baseIntent 更新为新 candidate Intent。当前候选收件后为 FINISHED；后续从该入口冷恢复必须有独立、持久的 task-entry 引用语义，不能把 FINISHED claim 放开以重放。

审查来源：[ResetTargetTaskHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/ResetTargetTaskHelper.java)、[ActivityStarter](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityStarter.java)。以上为设计约束，尚不是能力声明。

## schema 13 验收与 schema 14 任务迁移实现

schema 12 来源与结果身份已取得 P2 400、P0 60、P1 199、P3 274 项同构建证据；schema 13 的 singleTask/singleInstance/物理 alias 已有 P2 411、P0 60 项同构建证据，均见逐构建 README。旧的待验收段落是历史设计记录，不应作为当前最新结果。

schema 14 在同一池中增加 standard/singleTop 的 allowTaskReparenting profile；singleTask/singleInstance 不参与重归属。请求允许的 task flags 明确限定为 NEW_TASK / RESET_TASK_IF_NEEDED；for-result + NEW_TASK 仍拒绝。其他 task 清理策略仍拒绝，不做属性静默透传。原始逻辑 flags 进入请求摘要，系统附加的物理 flags 不改变授权请求；客体 getIntent 保留系统实际交付值。

运行实例在 onResume 与来源路由时刷新 taskId，变化须属于声明允许重归属的组件且新 task 由本引擎持有。没有手动 reparent、回调或仿真任务栈。新增独立夹具使用两个指向同一 singleTop 目标的 alias 验证 RESET 迁移保留二者、真实 taskId 改变、实例不重建、迁移后结果来源仍正确。当前等待该新构建验收。
## 系统启动回执与组件回调的区分（2026-10-14，schema 16 限定夹具已验证）

`Instrumentation.checkStartActivityResult` 接收真实系统启动结果。适配器只记录返回码，不代替系统启动，不自行调用客体生命周期。请求源由已接受的 source ticket、instance 与 Binder 认证的进程代次校验；每次交付最多写入一次系统结果。

系统 `START_TASK_TO_FRONT=2` 和 `START_DELIVERED_TO_TOP=3` 都不能独立证明“没有 onNewIntent”：singleInstance 将任务带到前台时仍会交付新 Intent。非 standard 模式保留原回调完成门槛。当前仅在已允许的显式 Intent、NEW_TASK/RESET flags 范围内，对 standard 根任务复用使用 `SYSTEM_CONFIRMED`；还必须观测到同组件租约、同进程代次的唯一 LIVE 根实例，并由 Android 自有 task 的 baseIntent ticket 交叉确认。缺少或有歧义时继续保持未确认，不猜 task。

真实回调保留 `CREATE/NEW_INTENT → ENTERED → RECEIVED`。无回调任务复用采用 `TASK_TO_FRONT/TASK_ALREADY_FRONT → SYSTEM_CONFIRMED`，执行结果记录系统返回码、taskId 和根 ticket，不写“回调完成”。它的候选 ticket 没有新实例；已有根实例继续固定该组件租约。

schema 15 的试验构建在 singleInstance 回执上过早标记成功，真机随后出现 `ACTIVITY_NEW_INTENT_MISMATCH`。该构建整轮验收失败。schema 16 的显式数据修正将这些错误的非 standard 系统确认退回 UNKNOWN，保留原结果和原因，不把错误历史当成功证据；旧的缺回执请求也不会自动补写成功。有限资源观测分别报告已分配、历史保留和可用槽，容量验收必须覆盖全部实际可用槽及溢出拒绝。

平台依据：[AOSP ActivityStarter.recycleTask / complyActivityFlags](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/ActivityStarter.java)。最终行为以指定固件上的自有 APK 验证为准，不使用 VirtualApp 源码。
