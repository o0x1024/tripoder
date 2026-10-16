# 通知渠道与分组

2026-10-15，首批通知渠道/分组通过原生 73 项、P1 237 项与 P2 598 项所在套件的设备验收。微信目标尚未通过，具体边界见末尾。

渠道和分组由 Android NotificationManager 实际创建、查询与删除。Tripoder facade 只改写所有权标识：`dataId + 编码后的逻辑 ID`。dataId 已由安装事务生成，升级保留、卸载后重新安装更换，因而复用现有安装生命周期，无需另建身份数据库。不同空间/安装的同名渠道互不覆盖；返回值恢复逻辑 ID、group 和分组成员，完整 Parcelable 副本保留系统重要性与用户设置。

单批最多 64 项，逻辑 ID 为不超过 512 字节的有效 UTF-8；转换后小于平台的 ID 长度上限。先验证整批再调用系统，避免后半项校验失败时前半项已写入。文本仅接受有界 String；声音只接受无声音或系统默认通知/铃声/闹钟 URI。会话渠道、自定义音源 URI、实际通知投递、PendingIntent/RemoteViews、委托发通知、免打扰修改都明确拒绝，不能因获得 Manager 就隐式授予。

areNotificationsEnabled 等首批状态查询来自真实引擎包的系统设置，不能解释成独立 Android UID 的通知权限或已支持 notify。系统设置里的应用归因仍是 Tripoder 引擎；各空间的渠道 ID 隔离不构成敌对同 UID 代码的安全沙箱。实际通知投递与权限申请仍是后续能力。

卸载提交移除安装所有权，恢复阶段根据仍被 installs 引用的 dataId 清除对应孤立系统渠道/分组，并复查真实系统查询。只处理 Tripoder 版本化命名空间，不删除其他引擎渠道。崩溃在卸载提交前则保留，提交后重启继续回收；数据库提交回执与系统资源回收证据分别记录，回收失败显式报错。

自研原生/runtime 对照检查字段、查询副本、重建不抬高重要性、分组删除真实移除成员、未授予能力拒绝；P1 检查不同空间相同 ID、升级/authority 死亡保持渠道、卸载两侧断点对应的系统资源状态。另验证异常观测仍向客体自己的 UncaughtExceptionHandler 交付原 Thread/Throwable。未通过设备检查前不标记本能力完成。

平台契约：[NotificationChannel](https://developer.android.com/reference/android/app/NotificationChannel)、[NotificationManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/NotificationManager.java)、[NotificationChannelGroup](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/NotificationChannelGroup.java)。没有使用 VirtualApp 源码或替代执行路径。

证据：`notifications-baseline-02`、`notifications-runtime-p1-01`、`notifications-runtime-p2-02`。P2 Runtime APK 为 `138bd5978aa902e21c12480f3b54c9207aecc53a98318f45de4c908d6b2660d6`；没有 Native fatal，Java crash buffer 中保留一条故意触发的自研异常分发事件，并由完整成功回执证明 handler 收到原对象、进程继续完成验收。其余 Java fatal 仍令验收失败。`notifications-runtime-p2-01` 原脚本因此失败的记录保留。

微信第十五次 Application 已取得 RUNNING 回执，但后台 notification 缺口使首页未确认；第十六次被此前未知 Activity 引用挡住，没有执行新 Application。因此以上夹具证据不代表微信渠道创建、登录或消息通知已验收。

## 取消能力（2026-10-15 实施，设备验收待补）

取消按稳定 dataId 映射独立的 notification tag，保留完整 int ID。null、空字符串和普通 UTF-8 tag 使用不相交编码；拒绝超过 512 字节或不完整 Unicode 输入。cancel(int) 映射 null，cancel(tag,int) 只向 Android 发送本安装的物理 tag/id，实际取消行为和平台异常保留。cancelAll 读取真实 active notifications 的快照，仅逐项取消本 dataId 的标签；不调用物理包级 cancelAll，因此不会清掉引擎服务或其它空间的记录。并发新投递可留在快照之外，本批不承诺并发投递的线性化批量删除。

验证分开记录：客体 facade 的缺失 ID 取消路径；probe 在真实系统投递五条自研通知后调用同一生产取消类，核对 null/空 tag 区分、幂等、跨 dataId 与引擎记录保留。后者只在测试 APK 声明 POST_NOTIFICATIONS，脚本仅在显式 notification 阶段和参数授权下临时授予并恢复本测试包权限。不会给产品或第三方应用授权。本批未开放生产 notify、PendingIntent 或通知 UI，不能把取消验收称为通知投递完成。
