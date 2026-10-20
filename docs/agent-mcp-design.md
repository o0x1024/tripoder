# AI Agent 控制面与 MCP

当前实现以 `agent/server.mjs`、`agent/tools.mjs` 和 Android `RuntimeAgentSessions` 为准。电脑 Agent 直接连接，无需设备配对身份、公私钥、签名、手机授权或脚本审批。完整导入配置见 [MCP 使用说明](../agent/README.md)。

## 接入链路

```text
任意 MCP stdio 客户端
  → Node.js agent/server.mjs
  → ADB forward
  → 手机 127.0.0.1:29731（无签名长度帧，protocol=2）
  → 工作台现有 Binder 连接
  → :authority 目标校验与操作日志
  → 实际客体进程的 Hook/UI/检查管线
```

打开 Tripoder、工作台连接引擎后自动启动电脑入口。电脑只需 Node.js、ADB 和设备连接；单设备自动识别，多设备用 `--serial`。ADB 路径优先从环境获取，也会查找常见 Android SDK 与 Homebrew 安装位置。手机可停止入口并重新开启。

MCP 的初始化和工具发现采用延迟设备连接：手机离线、未安装 ADB、旧 `--config` 文件不存在，均不会导致 stdio 初始化直接退出。真实工具调用会返回可读的设备或入口错误。MCP 协商支持 `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25`。

## 目标上下文

`targets_list` 列出实际 RUNNING 且项目绑定、安装代次仍有效的进程；`target_select` 选择项目与 processGeneration。只有一个主进程候选时可以自动选择；有歧义时明确返回候选列表。这个选择用于确定操作对象，不是身份验证。

上下文不设授权到期时间或总请求次数限制。停止进程、更新绑定后，重新选择时清理过期上下文，避免反复启动耗尽容量。操作仍检查准确绑定和进程代次，防止旧请求落到新的应用实例。

多个 Agent 可连接同一目标，手机控制保持可用。并发操作通过现有管线串行处理；冲突或未知结果需查询当前状态，不能隐式换目标或重放。

## 当前 42 个工具

| 能力 | 工具 |
| --- | --- |
| 目标与上下文 | `targets_list`、`target_select`、`session_get`、`process_get`、`session_close` |
| 操作对账 | `operation_get` |
| Hook 生命周期 | `hook_observe`、`hook_prepare`、`hook_activate`、`hook_status`、`hook_modules`、`hook_disable`、`hook_discard` |
| Hook 参数与事件 | `hook_update_params`、`hook_events` |
| DEX / Native | `dex_search_classes`、`dex_inspect_class`、`native_exports_search` |
| UI / 诊断 | `ui_dump_hierarchy`、`ui_dispatch_action`、`crash_diagnostics_get`、`manifest_inspect` |
| 存储 / 网络 / 调用 | `storage_inspect`、`network_traffic_get`、`http_send_request`、`guest_invoke` |
| 启动与探针 | `runtime_capabilities`、`projects_list`、`probes_get`、`probes_configure`、`process_start`、`application_stop`、`probe_status`、`probes_stop` |
| 动态代码与产物 | `classloaders_list`、`classloader_create`、`dex_list`、`class_inspect`、`dex_snapshot`、`runtime_events`、`artifacts_list`、`artifact_read` |

参数和边界以 `tools/list` 返回的 schema 为准。MCP 通过 process_start 启动已绑定且已停止的项目；安装、卸载、截图和任意 shell 尚未暴露。通用启动探针、动态加载与代码快照参见 [运行时观察](runtime-observation.md)。

## Hook 与失败语义

正常顺序为观察实际方法 → 准备受限脚本 → 使用返回的 token 与准确源码 SHA 激活 → 操作客体 → 查看真实命中/错误 → 显式停用。准备 token 保证对应准确编译产物，不需要人工签发或审批。外部草稿不会写入项目编辑器。

修改操作携带 operationId。同 ID、同内容返回原回执，改变内容拒绝；设备未知结果返回 UNKNOWN，保留原 ID 用 `operation_get` 对账。内存响应缓存有界，底层修改日志继续防止重复副作用。

关闭 MCP 只移除电脑的 ADB 转发；`session_close` 只关闭目标上下文；停止电脑入口只关闭连接与上下文。它们均不自动停用 Hook 或结束客体。需要恢复原方法时显式调用 `hook_disable`，停用后验证实际调用结果。结束客体仍使用工作台应用控制。

## 验收边界

`validate-agent-control.py` 使用真实手机、自研计数器和独立 stdio MCP 客户端，验证免配对/免审批、真实返回值 Hook、重复操作去重、显式停用、多个客户端及断开后进程存活，并核对进程和原生映射。该验收不等于 Sentinel 桌面/模型实际调用，也不证明所有第三方应用兼容。

旧证据中的 P-256、手机审批、十分钟授权和断联终止属于历史实现，当前已移除。
