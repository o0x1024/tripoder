# 电话服务的只读能力

2026-10-15 实施契约；首批只读能力已实现并通过下述限定验收。微信后台通过 Context 请求 phone，此前内核整体拒绝。

每个 worker 独立的 TelephonyManager facade 只开放列明查询，平台缓存对象和真实物理包身份保持不变。首批读取 phone type、SIM 状态、活动/支持 modem 数及语音/短信/数据硬件能力；这些是共享设备事实，不虚构每个空间独立的电话硬件。

设备/订阅标识、电话号码、运营商账户、位置、呼叫与短信操作、任意订阅选择及监听器不在这批范围，调用明确拒绝。需要危险权限的查询仅在后续有完整声明/授权规则后增加；本批不申请 READ_PHONE_STATE 或位置权限。未知方法不给默认值，不切到宿主应用运行路径。

固定平台的 Context 构造器是必需 adapter，签名缺失即失败。所有 Context 返回同一客体 facade；所有未列出的实例方法拒绝。静态方法和任意同 UID Native 路径不是该 facade 的安全边界，不宣称强隔离。

同一个自研原始 APK 在 Android 和 Runtime 读取真实 enum/硬件计数；Runtime 额外验证 facade 稳定、标识查询/订阅切换拒绝。通过后复测微信，按具体缺口补后续能力，不能把构造 manager 成功等同于电话子系统完整支持。

参考 [AOSP TelephonyManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/telephony/java/android/telephony/TelephonyManager.java)，只分析公开平台职责，不使用 VirtualApp 代码。

原生 Android 对照 `device-services-android-baseline-01` 已通过 43 项，其中电话设备能力和两张卡槽状态共 3 项、媒体 filter 登记/取消 1 项。run `resources-2cf510a567eb464b`；这不是 Runtime 通过证据，Runtime 组件回归仍在执行。

Runtime `device-services-runtime-p2-01` 547 项通过（`p2-83e436a1c64c4931`），上述真实查询及 3 项 facade/拒绝边界均通过，未观察到 Java/Native fatal。Runtime APK `198b46d87b8ec9b018e46c9b11a19cda75e1e64f70e163f5b98726fdc74785f6`；监听器与需要危险权限的查询仍不支持。

## 实施中的信号强度订阅

第九次微信启动实际请求 registerTelephonyCallback。新切片只接纳完整 listener 集合恰为 SignalStrengthsListener 的客体对象，其他监听组合明确拒绝；不截断未实现接口。每 worker 上限 16 个登记，平台真实 callback 先进入引擎 main executor，再以防御副本发到客体提供的 executor。登记成功才开放交付，取消先关入口再取消平台登记；排队但尚未进入客体的回调在实际执行时再次核验。

只允许不包含位置信息的显式选项；设备标识、通话状态、ServiceState、任意订阅选择仍拒绝。没有申请危险权限、模拟信号或补发虚构事件。测试等待真实 modem 初始状态，检查 executor、重复登记、混合未授权监听、位置选项和取消排队回调。结果待单独记录。

参考 [Android SignalStrengthsListener](https://developer.android.com/reference/android/telephony/TelephonyCallback.SignalStrengthsListener) 与 [注册接口](https://developer.android.com/reference/android/telephony/TelephonyManager#registerTelephonyCallback(java.util.concurrent.Executor,%20android.telephony.TelephonyCallback))。平台声明初次注册会提供当前状态；验收仍以实际回调为准。

新增切片的 Runtime 设备回归 `signal-filter-runtime-p2-01` 已通过 556 项（run `p2-a2b005b9d9ca4691`），未观察到 Java/Native fatal；实际信号与取消、组合 filter 检查均通过。范围仍限上述能力，完整电话子系统与真实媒体插拔未验收。
