# Launcher Shortcut 的能力边界

Tripoder 目前没有面向系统 Launcher 的持久客体入口：外部点击尚不能经不可变 capability 验证安装、空间、逻辑 Intent 与关闭状态后重新准入。因此，客体当前没有“固定到系统桌面”的能力，不能借用宿主 ShortcutManager 发布一个跳往宿主或旧工作台的入口。

公共 ShortcutManager 是能力查询入口。`isRequestPinShortcutSupported()` 应如实报告 false，而不是在获取服务对象时崩溃；它不查询或伪装物理 Launcher 的支持状态。其他未实现的查询和命令仍明确抛 `SHORTCUT_CAPABILITY_UNSUPPORTED`，不返回假空列表或假写入成功。此能力声明是当前产品范围，不是完整 Shortcut 运行支持，也不是把失败请求换成宿主执行。

后续完整接入要求：安装/数据代次隔离的 id 命名空间；持久快捷方式与入口 capability；真实 Android publish/pin/取消结果；Icon、资源、Intent 字段无损映射；系统 Launcher 点击后的冷恢复；升级/卸载/关闭时的退休和迟到点击拒绝。IntentSender 成功通知及 UNKNOWN 结果不能省略。完成这些条件后，能力查询才可按实际 Launcher 与运行策略返回 true。

验收分别记录原生设备的能力观察、容器当前 false 声明，以及未支持调用的明确拒绝。此切片不能计为“桌面快捷方式功能已完成”。
