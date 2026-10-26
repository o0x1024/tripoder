# MCP 工具分组 v3

日期：2026-10-23。HTTP 由 77 个收敛为 33 个工具，stdio 提供 37 个工具（含电脑侧 artifact_verify）。协议仍为 2025-11-25，Tripoder serverInfo 为 0.4.0。旧 MCP 工具名不再派发；已有操作日志、目标代次和生命周期约束继续执行；静态查询与对象签名边界按本文 v3 定义。

| 工具 | action | 效果 | 入口 |
| --- | --- | --- | --- |
| runtime_query | capabilities, status, resources, spaces, applications, device_applications | 查询 | HTTP / stdio |
| application_query | targets, process, launch_targets, launch_entries, launch_recovery, import_status, manifest, storage, process_history | 查询 | HTTP / stdio |
| application_manage | import_device, set_mode, reverify | 修改 | HTTP / stdio |
| space_manage | create, rename | 修改 | HTTP / stdio |
| project_query | get, archives, archive | 查询 | HTTP / stdio |
| project_update | script, selectors, restore, accept_migration | 修改 | HTTP / stdio |
| launch_manage | close, abandon | 修改 | HTTP / stdio |
| probes_query | configuration, status | 查询 | HTTP / stdio |
| probes_control | configure, stop | 修改 | HTTP / stdio |
| code_query | loaders, dexes, runtime_class, runtime_classes, resolved_class, native_exports | 查询 | HTTP / stdio |
| artifact_query | list, read | 查询 | HTTP / stdio |
| events_query | runtime, hook, network | 查询 | HTTP / stdio |
| process_inspect | hook_status, modules, diagnostics, ui_tree | 查询 | HTTP / stdio |
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

每个工具只有一种查询/修改属性，不混合只读查询和变更。分组工具必须明确 action；没有默认值。模型看到完整公开 anyOf action 分支、固定枚举、参数类型、字段来源和各动作必填项。模型、Node、Java 与 Sentinel 使用同一份公开 action schema；MCP _meta dev.tripoder/actionSchemas 只保留同源路由元数据，不能与公开分支分歧。Tripoder 先选择唯一 action 分支，再严格校验必填项、类型、范围、目标形状、未知字段及语义约束，最后映射到唯一内部方法。Sentinel 缓存完整契约，在派发前再次校验；私有路由元数据不进入模型工具 Schema，公开 anyOf 分支完整保留。

例如 file_query(action=stat) 需要 source，不允许混入 exportId；file_query(action=export_status) 只接受 exportId；file_export(action=begin) 需要 operationId、source、followSymlinks。静态读写属性继续控制恢复和重试。Hook observe/prepare/activate/disable/discard、进程启动/停止、DEX 快照、ClassLoader 创建、应用/空间删除及 operation_get 保留独立入口。

## 引用迁移与恢复

目录通过 _meta dev.tripoder/previousTools 声明旧工具到新工具/action 的映射。Sentinel 仅迁移该服务器命名空间内精确匹配的工具引用，并持久保存映射以修复旧编辑器之后的保存；服务器改名后仍按配置 ID 解析。Assistant Profile、默认 agent.tool_config 与当前会话/打开配置面板的手动、预选、固定、禁用列表去重，描述、模型设置和其他字段不改。旧禁用动作迁入合并入口时，整个入口保持禁用，并从手动/预选/固定列表移除；不以合并绕过原有禁用。若需要使用其中其他动作，可以在新目录中重新选择该入口。

首次连接和自动会话恢复都在发布新目录前处理映射。数据库事务失败会回滚迁移。打开的编辑器通过现有 MCP 引用事件更新工具列表和保存基线，不覆盖未保存文本；会话工具配置同时重映射 Rust Manual 枚举与独立手动列表，保留其他配置字段。工具调用卡片会标明所选 action。旧名称调用返回 TOOL_RETIRED / MCP_TOOL_RETIRED、替代工具/action、dispatched=false；不注册隐藏执行别名，也不自动改写并重放旧修改。

HOOK_OUTCOME_QUERY_REQUIRED 的恢复步骤为 operation_get 加 process_inspect(action=hook_status)，保留原 target。UNKNOWN 始终对账原 operationId；刷新工具或恢复传输不证明旧修改失败。文件下载器使用 file_export(action=begin) 与 file_query(action=export_status/manifest/read)，续传仍读取同一快照。

## 验证范围

主机验证覆盖全部内部动作映射、正反参数样例、读写属性、退役名称拒绝、目录下载/续传、Tripoder 电脑侧 MCP 接收、数据库事务回滚与旧编辑器保存修复。按要求不做真机验收、不安装更新；不将接口契约测试称为真实模型选工具准确率评测。两端需重建升级并刷新 MCP 目录后使用。

## v3 静态查询、对象与错误边界

`application_query(action=manifest)` 只需要安装 target；cursor/limit 可省略，首段为 0/32。结果来自完整的受校验安装描述，覆盖 base/splits、权限及完整 IntentFilter 声明；资源引用保留 UNRESOLVED，平台默认值单独标记，不能据此推断实际授予权限或组件可达性。`application_query(action=storage)` 也只需要安装 target，并明确标记 BOUNDED_LIVE_VIEW / LIVE_FILES_NOT_ATOMIC_SNAPSHOT。旧 process_inspect 的 manifest/storage action 和旧 Hook 执行方法均已移除。

`code_query(action=runtime_classes)` 是当前客体类视图与安装 DEX 名称集合的有界查询，覆盖 base/splits，返回 truncated；`resolved_class` 是当前根 ClassLoader 的反射视图，`runtime_class` 指定显式 loader。它们不宣称是安装层静态类详情查询。

Java Hook 签名支持标准反射类型名、默认包/Unicode 类名和 Class.getName 数组形式；void 不能作为参数，原语/Native ABI 保留字不能冒充对象类型。普通对象通过本次调用内的不可伪造引用传递，支持 null、原样转发和可赋值对象返回；不得构造、反射、任意调用或跨调用保留对象。String/byte[] 仍使用其明确的值契约。guest_invoke 独立限制 JSON ABI，并在构造 receiver 或执行方法前拒绝对象返回值。

脚本使用 ctx.java.objectInfo、snapshotThread、snapshotThrowable 取得受限快照；框架访问器非虚调用，不执行客体重写 getter/toString。snapshotThrowable 的 maxFrames 为 1..32、maxCauses 为 1..6，cause/suppressed/消息/栈截断均显式标记。ctx.debug.captureEvidence(tag, payload) 仅接受 JSON 数据，使用已有 authority 目录 FD 和原子产物写入，文件与元数据同步后才返回 COMPLETE；每进程最多 64 次。artifact_query(action=list) 支持 cursor/limit 和 nextCursor，kind=hook-evidence 可在原进程退出后读取，校验 SHA。emitEvent 的 Java long/BigInt 明确转为十进制字符串，captureEvidence 不隐式转换 BigInt。

Node、Android 与 Sentinel 共享严格 action 契约和 JSON Pointer 错误形状：errorCode=INVALID_TOOL_ARGUMENTS、issues[].path/code/expected。按 kind/type/action 选择分支后报告叶字段，避免无关 anyOf 错误；不回显 source 或请求正文。startupHook 要求显式 enabled=true。非法 URL、请求头和 GET/HEAD body 在网络 I/O 前拒绝；进入 I/O 后修改失败仍保留 UNKNOWN。只读查询失败不建立修改未知门。目录枚举不再隐式修复安装绑定，历史 UNKNOWN 不阻塞纯目录读取。按项目传递的修改使用明确 bindingOperation 解析实际空间，避免应用多空间安装时错误扩大为全局作用域；缺失或歧义仍拒绝，不放宽 UNKNOWN 门。

v3 需要两端更新并刷新目录；旧版本明确拒绝，不降级、不注册执行别名、不改写并重放旧调用。没有 Activity entry 的启动准备 UNKNOWN 不提供可执行的 close acknowledgement：canAcknowledge=false、recoveryState=NO_ENTRY_TO_FENCE_OUTCOME_UNKNOWN，原结果继续保留。

`application_query(action=process_history,target)` 从已有 sessions 表读取该精确安装的历史代次，包含 DEAD/FAILED，支持 cursor/limit。启动返回 UNKNOWN 时先对账原 operationId，再按该安装枚举历史代次并查询原 artifact_query；必须校验产物安装身份与 sourceSha256，不选择“最新 PID”或把进程死亡当作原操作失败。此查询不建立绑定、不恢复执行、不写入或修改 UNKNOWN。

所有 JSON integer 明确限制在安全整数范围；使用 Java int 的游标与 artifact_read.offset 再限制为 2147483647，避免 getInt 的静默溢出导致读取错误位置。

文件下载由电脑侧 MCP 的 `file_pull` / `file_pull_status` 唯一实现。Sentinel 不内置接收器；直接手机 HTTP 不提供电脑本地落盘，也不自动切换传输。下载回执和恢复规则见 [通用文件 pull](file-pull.md)。

## 2026-10-25 契约、恢复和验收修正

- `application_query(targets)` 不带 target；`launch_targets` 带安装 target，不接受 limit。模型看到的公开分支与执行校验一致，仍严格拒绝跨 action 字段；不静默忽略或自动改写参数。
- `artifact_verify` 为电脑侧只读验收工具，输入原下载 operationId 和绝对 destination。依次查询引擎捕获回执、验证下载 SHA/manifest、检查 DEX 内部头校验和节范围，并使用服务启动时明确配置的 `--dex-validator /absolute/dexdump` 做消费者解析。未配置则 usability=NOT_CHECKED，不自动安装工具、不修复文件或切换消费者。
- 只有 capture=CAPTURED、download=VERIFIED、format=PASSED、usability=PASSED 时单产物 taskComplete=true。file_pull COMPLETE 的 completionScope=DOWNLOAD_ONLY，不能代替整任务覆盖或反编译/业务验收。
- UNKNOWN 回执保留原 result/evidence，附 pendingOperationId、只读恢复步骤、newIdRetryAllowed=false。作用域拒绝指向阻塞的原操作，不指向新拒绝操作。没有 entry 的准备失败不调用 close；明确 stop 与死亡证据可用于重新规划，但不改变原 UNKNOWN。
- THICK 原启动记录的系统退出为 SIGSEGV/status11；APK 大小导致复制超时的旧结论撤回。具体故障栈缺失时保持 UNKNOWN。
