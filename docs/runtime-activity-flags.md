# Activity 启动标志与回执

2026-10-15：限定显式 Activity 请求新增 SINGLE_TOP。Android 负责真实任务栈与实例选择，Tripoder 将原始标志传给系统，并只在真实创建或 onNewIntent 回调完成后确认交付。

## 契约

- standard Activity 位于栈顶时，SINGLE_TOP 复用该对象并调用 onNewIntent；不在栈顶时仍会创建新对象。它不修改 manifest 的 launchMode。
- NEW_TASK 与 SINGLE_TOP 组合也不能仅凭平台 start 返回 2/3 判定交付成功。平台返回值表示任务处置，不能替代业务回调回执。
- schema 26 在 activity_launches 持久化 requestFlags。旧记录保留 NULL，表示没有记录过；迁移不猜测历史请求，也不将其改成成功。
- 带 SINGLE_TOP 的候选启动保留 PREPARED，进入真实回调后为 ENTERED，回调完成后为 RECEIVED。进程退出、异常或缺少回执保留 UNKNOWN。
- 当前 startActivityForResult 与 SINGLE_TOP 组合明确拒绝；CLEAR_TOP、CLEAR_TASK、文档任务等尚未纳入此契约的标志明确拒绝，并输出原始十六进制标志。没有删除标志后重试。

## 验收

自研 FlagTopActivity 使用 standard 声明，在原生 Android 和 Tripoder 分别执行：栈顶 SINGLE_TOP、NEW_TASK + SINGLE_TOP、无标志新建、被另一 Activity 覆盖时 SINGLE_TOP 新建。检查真实对象、onNewIntent、原始 getIntent 不被隐式替换、子实例销毁和根实例返回。容器还核对所有交付为真实 CREATE/NEW_INTENT 回执，不能混入无回调的 TASK_TO_FRONT 结果。

设备结果、构建摘要与未通过项以[证据索引](evidence/runtime-p2-plus-2026-10-14/README.md)为准；本文定义契约，本身不代表验收成功。

## 请求值域

Activity 请求摘要使用版本4的有类型长度前缀编码。支持 null、String、Boolean、Byte/Short/Integer/Long、Character、Float/Double、上述基础数组和String数组、嵌套Bundle、受限的原生Intent数据对象及仅包含准入数据的ArrayList。保留数值类型、浮点位表示、数组类型、null值及无extras/空Bundle的区别。拒绝Binder、FD、任意Serializable和未准入Parcelable；错误只记录类型，不输出业务值。

最大编码48KiB，嵌套8层，合计256个键，每个数组/列表4096元素，合计4096个数据节点，每段文本4096字符。循环Bundle、非法UTF-8文本和私有tripoder前缀键被拒绝。char/char数组保留UTF-16代码单元，而String使用严格UTF-8。

启动先验证并深拷贝请求，再持久化摘要和调用系统。深拷贝后的摘要必须与原始校验结果相同；复制期间发现变化就失败，不重试。物理路由额外记录原始extras是否存在，接收时去除路由字段后还原其存在性。旧摘要不会被改写成版本4或在不匹配时重试另一种摘要。


嵌套Intent仅接受exact android.content.Intent，必须有显式component；不接受URI、MIME、ClipData、selector、identifier/sourceBounds或URI grant标志。其component/action/package/flags/categories及递归准入extras纳入摘要；循环/深度/数量界限共同计数。作为extra传递不授予启动权限，后续真正start仍经过当前空间、安装、声明组件及请求标志校验。

快照改为真实平台Parcel复制，解决Bundle.deepCopy不会复制任意Parcelable内部对象的问题。Android维护的来源/creator元数据随原对象保留，不调用removeLaunchSecurityProtection或移除creator token。Tripoder摘要约束逻辑数据，不能替代系统来源保护；同UID Native的强隔离限制仍成立。公开extra仍不允许裸Binder、FD或任意Parcelable。该设计参照[Android Intent的平台元数据与Parcel契约](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/Intent.java)，不是重新实现Android来源令牌。
