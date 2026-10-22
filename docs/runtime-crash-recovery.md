# 引擎崩溃后的有界恢复

恢复管理位于独立的 `:recovery` 进程。它不加载客体或 Native Hook，也不打开运行数据库。用户启动引擎时，它持有唯一的引擎前台通知；工作台通过它取得已经完成恢复的 authority 控制入口。生命周期协议不占用业务 ControllerLease。

## 持久化与预算

`tripoder-recovery.db` 由恢复管理进程独占，使用 WAL 与 FULL synchronous。绑定 authority 之前先提交本次启动的 epoch 和计数。首次启动之后，每个故障周期最多自动恢复三次，间隔为 2、10、30 秒加不超过 255 毫秒的抖动；30 分钟滚动窗口另限五次自动恢复。统一启动预算 30 秒，孤儿 worker 观察最多 15 秒且包含在该预算内。

状态为 `STOPPED / STARTING / READY / BACKOFF / OPEN / BLOCKED`。达到预算进入 OPEN，不因时间流逝、重新打开页面或恢复管理进程重建而解封。显式重试只授予一次机会，失败后不衔接自动重试。READY 持续五分钟且完成真实数据库查询后才结束当前故障周期，滚动计数仍保留。后台环境受 Android 服务生命周期限制；整个应用被终止时，不构造互相拉起的服务链。

恢复管理进程中断时，旧 authority 主动结束，worker 延续既有 authority Binder 死亡退出策略。新管理进程保留预算并进入 BLOCKED，等待显式重试。用户停止先持久化 STOPPED，再停止 authority；普通页面打开不会重新启动它。数据库损坏、记录丢失、孤儿进程仍存活或无法确认终止均保持阻塞。不会根据旧 PID 杀进程。

## 操作与组件

运行数据库 schema 36 新增 `operation_scopes` 和 `recovery_paused`。ACCEPTED/RUNNING 在 authority 恢复时转为 UNKNOWN；终态和 UNKNOWN 均保留。没有恢复队列或重发原请求的实现。原 ID 继续遵守既有去重规则，冲突范围内的新 ID 也被拒绝。范围包含空间、安装包或保守的全局范围；旧日志缺少范围时按全局处理。预期的范围拒绝不会将数据库标记为损坏。

`NOT_DISPATCHED` 与 `MAY_HAVE_DISPATCHED` 只是派发边界证据，不单凭它们把 UNKNOWN 改成失败。结果核对仍使用 ExecutionContract.reconcile 的具体证据规则。进程死亡、UI 已读或关闭旧入口不能证明外部副作用没有发生。

所有 authority 冷入口经过 RuntimeRecoveryGate；未持有本次恢复许可时，服务绑定与系统组件唤醒不能构造 Broker。恢复期间默认不预热 worker、不自动重启客体、不重新部署 Hook。死亡代次的 Activity/Service/Receiver 恢复被拒绝；对应旧 PendingIntent、Alarm 所使用的 token 和 Job 版本关闭执行入口，但保留系统身份及原 UNKNOWN 日志。确认死亡与实际资源证明仍是租约回收的前提。

显式新启动经过范围门禁后才允许建立新 worker。后续新生成的组件版本使用新进程的正常路径。其他安装实例的启动和修改不因无关目标的 UNKNOWN 被全局阻断；历史上无法确定范围的记录除外。

工作台恢复后只查询原操作。旧会话回包不能覆盖新连接状态，MCP 旧执行上下文也不被恢复。控制 `query` 已移除顺带过期启动入口的行为；健康探测只读取数据库。设置页提供停止引擎与自动恢复，以及熔断后的显式重新连接。

## 验证入口

主机：

```sh
rtk proxy python3 android/engine/test/run-recovery-tests.py
rtk proxy python3 android/runtime/test/HookRecoveryHostTest.py
rtk proxy python3 android/workbench/test/run-mcp-tests.py
```

真机故障注入只由 `--recovery-acceptance` 构建加入，且构建器拒绝将它放进正式 `dev.tripoder.engine` 包。先使用固定工具链构建单独 QA 包，再安装匹配的控制测试包：

```sh
rtk proxy python3 android/scripts/validate-engine-recovery.py \
  --serial d861a4fa --evidence docs/evidence/engine-recovery-2026-10-22/new-run
```

验收使用实际进程死亡、Binder 重连和 SQLite 重开，记录安装包 SHA-256 与设备报告摘要。注入点在副作用文件已 fsync、终态尚未写入之间；验证原操作 UNKNOWN、派发次数为一、同 ID/新 ID 防重、无关范围可用、三次自动预算、超过最长退避仍熔断、恢复进程死亡后预算保留、冷绑定不能绕过门禁、单次手动重试和用户停止。主机测试不替代这些设备断言；独立 QA 包通过也不代表第三方客体业务兼容性验收。
