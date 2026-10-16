# 受保护系统广播

## H35 空闲与省电状态观察（2026-10-16）

真实微信连续日志确认其在注册 `android.os.action.DEVICE_IDLE_MODE_CHANGED` 时被旧动作范围拒绝，客体未捕获异常后自行 exit(1)。现将 `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`、`ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED` 和 `ACTION_POWER_SAVE_MODE_CHANGED` 接入同一受保护平台观察路径。注册前仍由固定平台确认 protected-broadcast；保留真实 ReceiverDispatcher/PendingResult、非 sticky 和既有关闭/注销语义。客体发送这些系统 action 继续拒绝，不产生合成回调、不修改设备空闲或省电策略。

公共 SDK fixture 新增三个实际注册/注销与非 sticky 检查、三个伪造发送拒绝。真实 idle/light-idle/power-save 状态切换不由本轮脚本强制触发，不能把注册成功写成实际事件投递通过。

2026-10-15。微信第七轮主线程需要 SCREEN_OFF，后台有系统/自身动作混合 filter 及自身签名 permission。基础屏幕/分钟广播切片已通过下述首轮验收，媒体与并发登记扩展正在复测。

沿用真实 Android ReceiverDispatcher、guest Receiver 实例和 PendingResult；不手工调用 onReceive，不制作假 PendingResult。平台动作与逻辑私有动作可以进入同一登记，但授权来源不同：私有动作继续使用 authority 的随机物理 action/ticket，系统动作只允许精确列出的受保护动作，并在固定设备上确认 Android 的 protected-broadcast 声明。

第一批 SCREEN_ON、SCREEN_OFF、USER_PRESENT、TIME_TICK 不携带文件 URI。flags=0 仅用于纯受保护系统 filter；含私有动作必须显式 exported/not-exported。未知系统 action 仍拒绝。MEDIA_SHARED 等存储广播需先建立外部资源身份及 URI 映射，不能把宿主物理路径交给客体来消除异常。

后续媒体状态切片只观察系统存储卷状态，不授予文件访问：MEDIA_SHARED/MOUNTED/UNMOUNTED/EJECT/REMOVED/BAD_REMOVAL/CHECKING/NOFS/UNMOUNTABLE 必须使用纯媒体动作、单一 file scheme 的 filter。系统 URI 仅能是 StorageManager 实际报告的公开卷根，拒绝子路径、authority/query/fragment 及任意宿主私有目录。原始公开卷根是共享硬件事实，不声称是每空间独立存储。

schema 23 在注册权威中持久保存实际观测过的卷根，处理卸载后 getDirectory 变为空的情况；后续已接纳挂载事件补入新的实际根。最多 64 项。worker 只提交原始观测，authority 依据自己的已持久/当前平台观测核验后才给客体。没有创建数据文件、路径透传后端或伪造媒体事件。当前自研测试仅验证注册/取消；真实媒体挂载/卸载交付仍未验收。

注册仍在 authority 登记，由现有会话认证与关闭门槛决定能否接收新工作。平台动作到达时校验实际已登记 filter、代次与 PendingResult，保留真实返回/goAsync/finish 生命周期。私有动作仍需要原有精确 ticket，不因为混合 filter 绕过 claim。取消关闭交付入口并取消实际 Android 登记；进程死亡不恢复旧 Receiver。

自身签名 permission 仅在已校验 APK 声明和请求、且接收范围为同包时授予。不能删除 permission 参数后宣称实现跨包权限；跨包发送/接收必须另有签名和权限权威规则。受保护系统发送者是否豁免 permission 用原生对照确认，不凭应用名称特判。

验收使用自研包：等待真实 Android TIME_TICK（最长 70 秒），回调中 goAsync/finish 实际 PendingResult 并核对 guest Context；混合私有动作仍按空间路由；伪造系统发送及错误权限拒绝。原生和 Runtime 的资源验收超时随真实分钟事件上限设为 110/130 秒（Runtime 额外覆盖 35 秒有界并发登记）。屏幕事件本轮只验证登记/取消，不切换用户设备锁屏；不能把 TIME_TICK 的通过称为屏幕事件实测通过。

系统投递复用现有持久 BroadcastLedger 及完成协议，来源明确记为 `PLATFORM_PROTECTED:<action>`，不伪装成客体发送。新的 claim 属于新工作，受关闭准入控制；实际 returned/finished 沿原有完成回执路径对账。系统投递没有 guest source session，也没有虚构 ordered 最终回调。本批有序系统广播明确拒绝。

取消后的 Receiver 实例保留有界退休记录（每进程总计最多 256 项），迟到 dispatcher 在调用客体前被关闭门槛截断；仍由 Android 结束真实 PendingResult。已经进入的 goAsync 回执可完成。进程死亡后不跨代次保留或复活旧实例。

## 第一轮结果

`system-broadcast-android-baseline-01` 39 项原生检查通过（run `resources-6c1dd6887d7b48d4`），实际 TIME_TICK 在 1792001160007 ms 被 PID 25529 接收并异步完成。

`system-broadcast-runtime-p2-01` 539 项通过（run `p2-5b1d251597cd40d6`，Runtime APK `171e3dfb33cb863631603884400fb7e7af940cbf3dcd6cb3a8a731937f2205b1`），真实系统投递与混合私有动作、客体 Context、异步完成及权限/伪造发送拒绝均通过。无 Java/Native fatal。该轮不覆盖高并发注册与 callback 的锁顺序；后续审查仍需消除持有 Receiver 锁调用代理适配器的潜在锁反转，不能将正常回归等同于并发稳定性证明。

后续实现将 registry 锁限制到状态变更，adapter/平台/Binder 调用全部在锁外；同一对象在途 register/unregister 明确拒绝竞争。实例发布发生在 adapter 安装完成后，真实交付等待 ACTIVE 回执。ActivityManager/ConnectivityManager 的 owns 查询改为读取 volatile facade，不持有构造/注册锁。新增两个线程各 20 次真实注册/取消、并发私有投递与系统查询，要求实际时间区间重叠且收到 callback；结果待下一轮设备验收。

`device-services-runtime-p2-01` 547 项回归通过，无 Java/Native fatal。两个注册线程的实际单调时间区间为 218068830213738–218069409800561 ns 与 218068830359676–218069430964051 ns；区间重叠，完成 40 次注册/取消，采样时已收到 26 次真实私有广播回调。此为约 0.6 秒定向交错证据，不是长时间压力门槛。媒体只覆盖 filter 登记/取消。

## 第九次目标复测后的 filter 修正（待验收）

先前要求媒体动作必须纯媒体 + file scheme 过严。Android 原生允许混合动作、也允许不带 scheme 的登记；不带 scheme 的 filter 自然不能匹配携带 file URI 的事件，这不是空实现。现保留其真实匹配规则：已支持动作可组合，媒体组合最多一个 file scheme，其他数据约束仍明确拒绝。

物理登记拆成同一真实 Receiver 的两条 Android filter：随机私有 action 不带数据，系统 action 保留原 filter 的 scheme。私有 payload 的 URI 仍由 authority 对原逻辑 filter 匹配后封装，不能让媒体 scheme 错误过滤掉私有 ticket。一次 unregister 撤销该 Receiver 的实际登记，仍使用同一逻辑登记/关闭协议和真实 PendingResult。新增原生与 Runtime 的组合登记和 IntentFilter 数据匹配对照；没有伪造媒体广播。

Runtime 资源夹具预算调整为 160 秒，覆盖分钟事件 70 秒、网络 10 秒、信号与取消 20 秒、私有投递 10 秒、并发登记 35 秒和固定开销；不是超时后改判成功。

新增切片的 Runtime 设备回归 `signal-filter-runtime-p2-01` 已通过 556 项（run `p2-a2b005b9d9ca4691`），未观察到 Java/Native fatal；实际信号与取消、组合 filter 检查均通过。范围仍限上述能力，完整电话子系统与真实媒体插拔未验收。

## 2026-10-15 导出动态 Receiver 的自有签名权限（schema 24，待验收）

Receiver 声明的权限必须由目标已验证 APK 定义并请求，保护级别为 signature。投递时根据已认证逻辑发送者核验其 uses-permission 和双方原 APK 的签名集合，缺少声明或签名不一致不创建投递。不依赖共享宿主 UID 的权限结果。当前仅支持完全相同的签名集合，不覆盖轮换证书授权。物理 Receiver 始终使用非导出随机动作，跨包投递只在同一逻辑空间内由 authority 发起。

数据库记录 permission；旧进程在 authority 恢复时终止并关闭所有旧注册，不复活迁移前未记录权限的注册。验收包含自包正向回调、同签名且请求权限的另包真实回调、同签名但未请求权限的投递账本为零。

注册权限修正：Receiver 的 permission 是对发送者的限制，注册者不必 uses-permission。已取消这项过强前置条件；本批支持本包声明的 signature 或 signatureOrSystem（Android 可能规范化成 signature|privileged）。Tripoder 没有系统/特权客体，逻辑发送者仍必须请求该权限且原 APK 签名完全相同，不提供特权绕过。原生夹具新增“仅声明但未请求”的注册验证，待回执。
