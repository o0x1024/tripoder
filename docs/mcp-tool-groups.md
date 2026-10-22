# MCP 工具分组 v2

日期：2026-10-22。HTTP 由 77 个收敛为 33 个工具，stdio 由 81 个收敛为 36 个。协议仍为 2025-11-25，Tripoder serverInfo 为 0.3.0。旧 MCP 工具名不再派发；已有原生运行时方法、操作日志、目标代次和生命周期语义保持一致。

| 工具 | action | 效果 | 入口 |
| --- | --- | --- | --- |
| runtime_query | capabilities, status, resources, spaces, applications, device_applications | 查询 | HTTP / stdio |
| application_query | targets, process, launch_targets, launch_entries, launch_recovery, import_status | 查询 | HTTP / stdio |
| application_manage | import_device, set_mode, reverify | 修改 | HTTP / stdio |
| space_manage | create, rename | 修改 | HTTP / stdio |
| project_query | get, archives, archive | 查询 | HTTP / stdio |
| project_update | script, selectors, restore, accept_migration | 修改 | HTTP / stdio |
| launch_manage | close, abandon | 修改 | HTTP / stdio |
| probes_query | configuration, status | 查询 | HTTP / stdio |
| probes_control | configure, stop | 修改 | HTTP / stdio |
| code_query | loaders, dexes, runtime_class, apk_classes, apk_class, native_exports | 查询 | HTTP / stdio |
| artifact_query | list, read | 查询 | HTTP / stdio |
| events_query | runtime, hook, network | 查询 | HTTP / stdio |
| process_inspect | hook_status, modules, diagnostics, manifest, storage, ui_tree | 查询 | HTTP / stdio |
| application_upload | begin, write, commit, cleanup | 修改 | HTTP / stdio |
| file_query | stat, list, export_status, manifest, read | 查询 | HTTP / stdio |
| file_export | begin, cancel, release | 修改 | HTTP / stdio |
| file_pull | start, cancel | 修改 | 仅 stdio |
| process_start | 独立动作 | 修改 | HTTP / stdio |
| application_stop | 独立动作 | 修改 | HTTP / stdio |
| classloader_create | 独立动作 | 修改 | HTTP / stdio |
| dex_snapshot | 独立动作 | 修改 | HTTP / stdio |
| operation_get | 独立动作 | 查询 | HTTP / stdio |
| hook_observe | 独立动作 | 修改 | HTTP / stdio |
| hook_prepare | 独立动作 | 修改 | HTTP / stdio |
| hook_activate | 独立动作 | 修改 | HTTP / stdio |
| ui_dispatch_action | 独立动作 | 修改 | HTTP / stdio |
| http_send_request | 独立动作 | 修改 | HTTP / stdio |
| guest_invoke | 独立动作 | 修改 | HTTP / stdio |
| hook_update_params | 独立动作 | 修改 | HTTP / stdio |
| hook_disable | 独立动作 | 修改 | HTTP / stdio |
| hook_discard | 独立动作 | 修改 | HTTP / stdio |
| space_delete | 独立动作 | 修改 | HTTP / stdio |
| application_import | 独立动作 | 修改 | 仅 stdio |
| application_remove | 独立动作 | 修改 | HTTP / stdio |
| application_delete | 独立动作 | 修改 | HTTP / stdio |
| file_pull_status | 独立动作 | 查询 | 仅 stdio |

## 参数和派发

每个工具只有一种查询/修改属性，不混合只读查询和变更。分组工具必须明确 action；没有默认值。模型看到扁平对象、固定枚举、参数类型、字段来源和各动作必填项；完整 action 契约位于工具的 MCP _meta dev.tripoder/actionSchemas 中。Tripoder 先选择唯一 action 分支，再严格校验必填项、类型、范围、目标形状、未知字段及语义约束，最后映射到唯一内部方法。Sentinel 缓存完整契约，在派发前再次校验；元数据不进入模型工具 Schema。

例如 file_query(action=stat) 需要 source，不允许混入 exportId；file_query(action=export_status) 只接受 exportId；file_export(action=begin) 需要 operationId、source、followSymlinks。静态读写属性继续控制恢复和重试。Hook observe/prepare/activate/disable/discard、进程启动/停止、DEX 快照、ClassLoader 创建、应用/空间删除及 operation_get 保留独立入口。

## 引用迁移与恢复

目录通过 _meta dev.tripoder/previousTools 声明旧工具到新工具/action 的映射。Sentinel 仅迁移该服务器命名空间内精确匹配的工具引用，并持久保存映射以修复旧编辑器之后的保存；服务器改名后仍按配置 ID 解析。Assistant Profile、默认 agent.tool_config 与当前会话/打开配置面板的手动、预选、固定、禁用列表去重，描述、模型设置和其他字段不改。旧禁用动作迁入合并入口时，整个入口保持禁用，并从手动/预选/固定列表移除；不以合并绕过原有禁用。若需要使用其中其他动作，可以在新目录中重新选择该入口。

首次连接和自动会话恢复都在发布新目录前处理映射。数据库事务失败会回滚迁移。打开的编辑器通过现有 MCP 引用事件更新工具列表和保存基线，不覆盖未保存文本；会话工具配置同时重映射 Rust Manual 枚举与独立手动列表，保留其他配置字段。工具调用卡片会标明所选 action。旧名称调用返回 TOOL_RETIRED / MCP_TOOL_RETIRED、替代工具/action、dispatched=false；不注册隐藏执行别名，也不自动改写并重放旧修改。

HOOK_OUTCOME_QUERY_REQUIRED 的恢复步骤为 operation_get 加 process_inspect(action=hook_status)，保留原 target。UNKNOWN 始终对账原 operationId；刷新工具或恢复传输不证明旧修改失败。文件下载器使用 file_export(action=begin) 与 file_query(action=export_status/manifest/read)，续传仍读取同一快照。

## 验证范围

主机验证覆盖全部内部动作映射、正反参数样例、读写属性、退役名称拒绝、目录下载/续传、Sentinel 原生 HTTPS 接收、数据库事务回滚与旧编辑器保存修复。按要求不做真机验收、不安装更新；不将接口契约测试称为真实模型选工具准确率评测。两端需重建升级并刷新 MCP 目录后使用。
