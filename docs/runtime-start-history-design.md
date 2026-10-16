# 客体启动历史的权威

2026-10-15。schema 22 已实施，自研设备验收通过，真实目标继续验证。微信请求 `ActivityManager.getHistoricalProcessStartReasons(int)`；直接返回引擎 UID 的 Android 历史会混入别的空间、物理组件及启动 Intent，因此不能整表透传，也不能靠 PID 猜历史归属。

Tripoder 应记录自身实际执行的逻辑 Application 启动：进入构造/attach 的边界、调用 onCreate、初始化成功或明确失败。记录由已认证的真实 PID/UID/进程代次提交，authority 从会话派生 space/package/install/processName 并持久化。账本不接受客体自报范围、PID、时间或其他进程名。时间在 authority 接纳相应边界时取实际单调时钟，不能用当前时间补造历史。

查询范围固定为同一 space/package/install、当前设备启动周期，按持久顺序返回最近最多 64 条；maxNum 为 0 表示这个保留窗口，负数拒绝。进程死亡和 authority 重启保留已记录事实；旧版本没有记录的启动不补写。无记录时空集是权威查询结果，存储或认证错误必须向调用者失败。

返回平台 ApplicationStartInfo 类型，但语义是**Tripoder 的逻辑 Application 启动**。PID/UID 为实际 worker，processName 为声明逻辑名称；每次是新的 Application，startType=COLD；由运行内核发起，reason/component=OTHER。不冒充宿主物理 fork、Activity 绘制完成或 Android 历史退出报告。只填已观测的 LAUNCH/ONCREATE 时间；没有首帧证据不能标记 FIRST_FRAME_DRAWN。启动 Intent 不包含物理路由、bootstrap 或 ticket。

门槛：onCreate 内能看到自己的已记录启动；同空间远程进程记录可见、同包其他空间不可见；历史副本不可修改账本；maxNum 与错误参数正确；进程死亡/authority 重启后仍保留同代次事实；明确启动失败记录 ERROR，未知死亡不能伪造已观测失败；与真实 Android 对照区分物理启动与逻辑启动含义。

实现对应 ApplicationStartLedger、两个有界 Binder 操作及固定平台 ApplicationStartInfo 投影。失败回执可越过关闭门槛，但必须属于已存在的本代次启动记录，不能凭失败回执新建启动。未知死亡不更改 STARTING/ON_CREATE 记录，也不把未到达的首帧写成完成。自研 MetadataApplication 增加真实初始化失败进程，P2 检查失败保留、空间过滤和 authority 重启后原始时间不变。

`start-history-p2-01` 的完整现有 P2 套件 **521 项通过**，run `p2-e04f40c2f13b489c`，Runtime APK `3c95a866740c60947f69d6be33f765c5238d0ecae7aeb719daad353ff501522e`。新检查包含 onCreate 内查询、真实失败 ERROR、空间过滤、可变时间 Map 的防御副本、进程死亡后记录保留、authority 实际终止重启后原时间不变。每个 P2 Application 另检查替换初始 Application 为另一对象会被拒绝。未将这些检查视为 P2–P6 全量完成。

参考 [Android ApplicationStartInfo](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/app/ApplicationStartInfo.java)。本设计不通过克隆物理引擎历史来推测逻辑身份，也不将未记录值伪装成真实观测。
