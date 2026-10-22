# Engine 崩溃恢复验收，2026-10-22

最终结果：主机恢复相关 191 项检查通过；独立真机 QA 包的最终 42 项断言通过，包含真实 SIGABRT、Binder 死亡与重连、SQLite 重开、自有客体冷启动。现有手机应用与数据未被 QA 包覆盖。源码修改保留在当前工作树，未提交或推送。

## 最终证据

- `device-04/verification.json`：最终安装包摘要、设备固件、run ID、42 项结果，以及按本次实际 authority PID 对应的 SIGABRT 日志证据。
- `device-04/result.json`：完整设备断言与状态快照，副作用计数为 1。
- `device-04/qa-signal-evidence.txt`：只保留本次故障 PID 的 SIGABRT 行。
- `device-04/instrumentation.txt`：设备结果 SHA-256 回执，验证器独立比对落盘报告。
- `host-recovery.txt`：恢复策略 31、执行契约 113、Service 策略 16、Hook 恢复 21、工作台恢复 10 项。
- `host-additional.json`：RuntimeMode 24、PendingIntent ledger 14、项目 schema 2，以及 MCP 412 个 schema 用例和 22 个 Node 测试。
- `production-packaging.json`、`production-build-receipt.json`：正式包已构建，未包含故障注入类或其他 Native 引擎。
- `qa-production-parity.json`：QA 与正式构建的共有运行源码、Native 核心和 MCP 合同一致性。

## 已证明的恢复边界

副作用文件 fsync 后、终态写入前发送 SIGABRT。新 authority 将原操作保留为 UNKNOWN；再次提交相同 ID 不派发，换新 ID 的同范围修改也拒绝。无关范围仍可修改。

初始启动之外自动恢复三次后进入 OPEN。观察 35 秒，超过最长 30 秒退避及抖动，启动计数仍为 4。额外冷绑定不能绕过门禁；杀死并重建恢复管理进程后，自动次数仍为 3、总次数仍为 4、状态仍 OPEN。普通连接不解封。显式单次重试把总次数增加为 5，随后死亡不会继续自动恢复。用户停止后再次打开工作台，仍保持 STOPPED 与原预算。

关闭自动预热后的自有客体 thin 冷启动、安装校验、升级代次、项目绑定与数据实例保持也通过。它不构成第三方应用业务或所有 Android 固件兼容性的验收。

`device-01` 为早期 SIGKILL 验证；`device-02` 保留了 SIGABRT 夹具过早返回的失败记录；`device-03` 为收尾前的 SIGABRT/客体验证。最终以 `device-04` 和正式构建的一致性记录为准。
