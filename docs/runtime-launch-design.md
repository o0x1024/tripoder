# 独立工作台的客体启动契约

状态：首批入口已验收，完整 P5 仍在实施。`workbench-launch-atomic-01` 通过 50 项控制检查；同构建 `workbench-launch-ui-02` 通过 6 项实际 UI/进程检查，包含工作台死亡时客体继续运行及工作台恢复后查询完成回执。首次 Gateway 因事务内任务观察失败，保留在 `workbench-launch-ui-01`。沿用共享 Runtime、安装目录、项目绑定、ProcessSupervisor 和 ActivityLedger；不开放物理 stub，不调用设备上同包应用，不使用 VirtualApp。

## 接收与执行

工作台先读取绑定对应的可启动声明，再由用户明确选择组件。启动请求包含唯一 operationId、project、bindingOperation、installGeneration 和组件；Authority 再次核对这些身份。解析与制品校验在后台执行，短锁只保护接收与状态提交。

一次性凭证绑定当前 authority、控制连接、实际控制 UID、项目绑定版本、安装代次、进程代次、组件和有效期。明文凭证只返回一次，数据库只保存摘要。重复请求只允许查询原操作，不重发凭证。凭证被系统入口消费时，在一个事务中创建 Activity ticket 并记为 DISPATCHED；不将 component、path、space 等可修改参数交给入口自行解释。

Gateway 在引擎 Authority 进程中，受签名权限和 ComponentCaller 的固定工作台身份约束；拒绝引擎自身 UID、错误包、过期凭证、旧 owner 和重复消费。Gateway 必须处于前台，并为客体使用引擎自己的任务。页面离开和工作台死亡不拥有客体生命周期。

启动操作的成功来自 ActivityLedger 对实际 onCreate/onNewIntent 完成的确认。Activity 启动 API 返回只是已提交。准备完成但从未消费的凭证可在期限到达后明确失效；已消费但执行结果不明的操作保持 UNKNOWN，不重放。Authority 重启不能恢复旧凭证权限。

## 分批验收

先验证自研计数器 APK：不同 UID 控制端、真实 Activity 呈现、计数持久化、页面重建、返回工作台、旧凭证与错误调用者拒绝、工作台死亡期间客体仍运行。后续扩展任务复用与真实目标启动，须分别验收；不能把入口通过计为微信或王者业务通过。

项目源码和未保存草稿仍属于工作台；启动不隐式部署脚本。Hook 部署遵循独立 prepare/activate/ACK 契约。

## 事务与恢复审查

系统任务观察在 SQLite 事务外完成；ActivityLedger 提供同一次票据提交中的关联写入，消费凭证和票据/组件租约一起提交。不能在外面套一层事务后再调用会观察 Android 的准备方法。

schema 18 增加 controller_launches，仅保存控制入口能力；不另建组件或进程权威。恢复在已确认旧会话死亡后处理旧 authority 的 READY 且 ticket 为空的记录，以持久无派发证据将 UNKNOWN 协调为 FAILED；DISPATCHED 不适用此规则，不自动重放。

首批入口明确拒绝该安装仍有活跃或未确认 Activity 的重复打开请求。已存在任务的显示/复用、跨 authority 的已消费入口恢复和完整超时协调仍需要后续实现与验收。
