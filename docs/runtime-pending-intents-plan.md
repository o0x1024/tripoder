# 真实广播 PendingIntent / Alarm 下一阶段边界

2026-10-16，H35 真实王者首次进入 WorkManager 后的缺口。以下是实施计划，**尚未实现**，不能列为已支持能力。

## 已证实的根因

固定王者原 APK `6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384` targetSdk=30。本地固定 API37 DEX 的 `PendingIntent.checkPendingIntent` 调用进程级 `android.compat.Compatibility.isChangeEnabled(160794467L)`；Context包名只用于错误消息。宿主target策略导致旧客体触发S+ explicit-mutability错误。

仅绕过检查仍会让 IActivityManager 收到客体包名+宿主UID。当前 Runtime 无 PendingIntent工厂/token/send/cancel/IntentSender适配，GuestContext也无AlarmManager。Activity设计文档中的PendingIntent是计划项，不是现成实现。

## 最小完整实现

1. `PendingIntentFrameworkAccess`：固定 PendingIntent、AlarmManager、IntentSender 成员声明摘要与类型适配。boot root只在55后追加，不移动已有索引；更新 native `art_framework.c`、`FrameworkAccess`、`FixedFrameworkMembers`，纳入唯一 ManagedInstaller 与 Job物理预准备集合。
2. `RuntimePendingIntents`：可信客体Context入口、按已验证targetSdk校验flags、返回真实系统token的逻辑视图。target30无显式mutability仍是mutable，不偷偷改为immutable，不修改宿主全进程Compatibility delegate。
3. `PendingIntentLedger`：持久creator与space/install/data代次、类型/requestCode/filterEquals/身份flags、版本及mutation owner；真实创建/取消/UNKNOWN。creator不能绑定短命ProcessSession。
4. `RuntimePendingIntentBroker`：锁外真实getBroadcast/NO_CREATE/cancel，锁内记录版本和回执。NO_CREATE必须实际查系统，不能仅查DB或制造空返回。UPDATE_CURRENT/CANCEL_CURRENT/ONE_SHOT保持真实token规则和未知结果边界。
5. `RuntimePendingIntentIngress`：只有系统真实发射后，验证不可变路由能力和当前安装，才预约目标并建立一次性BroadcastLedger delivery。可变extras不能承载受保护路由；逻辑fill-in不能改变物理component/版本身份。升级/取消后的迟到入口在guest代码之前拒绝。
6. `RuntimeAlarms`：真实AlarmManager facade、按安装持久记录、调度/取消与未知回执。首批只支持已归属PendingIntent；listener/WorkSource/AlarmClock等未实现形状明确拒绝。exact权限从真实物理能力查询，不伪造。

BroadcastLedger.prepare目前只取source generation和identity；可抽出不可变origin并沿用已有prepare/claim/returned/finished/UNKNOWN。`systemDone`当前按source鉴权，不能用空source冒充OnFinished/ordered发送者；真实发射回执须有自己的owner。不得另造一套业务投递成功状态机。

## 固定入口闭包

- PendingIntent：getBroadcast/getBroadcastAsUser，所有send重载/sendAndReturnResult、cancel、creator/target/type/isImmutable视图、equals/hashCode/Parcel身份。
- IntentSender：getIntentSender形成的旁路以及Handler/Executor版sendIntent、creator/type视图，不能只拦PendingIntent.send。
- AlarmManager：set/setExact/setAndAllowWhileIdle/setExactAndAllowWhileIdle/setWindow/setRepeating/setInexactRepeating、cancel/cancelAll/canScheduleExactAlarms；两个setImpl的Handler/Executor入口也必须归属检查。
- Runtime固定声明摘要与Manager摘要格式不同，勿混用：前者`name+descriptor+":"+hex(modifiers)`，后者`descriptor+" "+decimal(modifiers)`。

## 验收门槛

- target30/31参数差异；真实NO_CREATE缺失/命中；更新、取消、一次性、token身份。
- 创建者死亡后真实投递；跨空间/升级/取消迟到拒绝；mutable fill-in不改受保护路由。
- Alarm实际派发/取消及exact权限观察；系统回执与业务效果分开。
- 完全冷进程Factory/固定准备/Hook/Application/onReceive分段计时。固定BroadcastConstants默认预算是`10000 * HW_TIMEOUT_MULTIPLIER`（还可配置）；Job的8秒ACK/18秒bind预算不适用。当前Factory在onReceive之前同步构建Engine，内部30秒deadline与goAsync不能证明广播无ANR。必须移除或预计算无身份昂贵工作并实测，不能靠预热worker替代冷验收。
- 最后重跑王者真实WorkManager初始化、UI/登录和正常动态Hook；fixture通过不等于业务验收。
