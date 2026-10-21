# Tripoder MCP

Tripoder 提供 Node.js stdio MCP 服务。任何支持 MCP stdio 的电脑 Agent 都可以连接，无需设备身份、公私钥、配对文件、手机授权或脚本人工审批。打开 Tripoder 后入口默认自动启动；手机可在“电脑 Agent”卡片或通知中停止连接入口。

## 连接

先通过 ADB 连接手机、打开 Tripoder 并启动要操作的客体，然后在 Sentinel 等 Agent 客户端添加：

```json
{
  "mcpServers": {
    "tripoder": {
      "type": "stdio",
      "command": "/absolute/path/to/node",
      "args": ["/absolute/path/to/tripoder/agent/server.mjs", "--serial", "DEVICE_SERIAL"],
      "env": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"}
    }
  }
}
```

只有一台 ADB 设备时可以省略 `--serial`，也可以使用 `ANDROID_SERIAL`。手机无需额外点击配对或批准。多台设备时须指定 serial；MCP 连接处于 Tripoder 全局视角，不绑定空间、项目或应用。先列出空间、应用或运行目标，再在每次调用中明确传入目标；即使只有一个进程，也不自动选择。

旧配置里的 `--config` 暂时兼容：只读取可选 serial，不读取身份、私钥或会话授权；文件不存在也不影响 MCP 初始化。`pair.mjs` 只提示无需配对，不再生成密钥。新配置直接使用 `--serial`。

MCP 初始化和工具发现不依赖手机在线或 ADB 存在；执行工具时才建立设备连接。支持协商 `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25`。stdio 只输出 JSON-RPC，诊断写 stderr。手机传输为 ADB 转发到 `127.0.0.1:29731` 的无签名长度帧，不是 HTTP MCP 入口。

## 工具与操作

工具覆盖全局状态与资源、空间创建/重命名/删除、设备应用发现、电脑 APK 分块导入与手机应用导入、升级/移除/删除、运行模式、应用唯一项目源码与历史恢复、进程启停、启动入口恢复，以及 Hook、DEX/Native、UI、存储、网络、HTTP、方法调用与产物。完整参数以 `tools/list` 为准。

安装级工具（启动、停止、探针配置、运行模式、移除安装）使用：

```json
{"target":{"space":"default","package":"owned.app","installGeneration":"INSTALL_GENERATION"}}
```

进程级工具（Hook、UI、存储、网络、DEX 等）每次使用：

```json
{"target":{"space":"default","package":"owned.app","installGeneration":"INSTALL_GENERATION","processGeneration":"PROCESS_GENERATION"}}
```

`targets_list` 返回这些字段；`applications_list` 返回唯一项目及各空间安装的 `generation`，作为 `installGeneration` 使用。`projects_list` 的项目记录也按应用唯一，不作为 MCP 连接的范围。`target_select`、`session_get`、`session_close` 已移除，刷新客户端工具 schema 后使用显式参数。`guest_invoke` 的 Java 方法描述放在 `invocation`，`target` 专指运行进程。

电脑导入示例：

```json
{"operationId":"import-example-1","space":"default","apkPaths":["/absolute/base.apk","/absolute/split.apk"],"expectedGeneration":""}
```

`application_import` 以 12 KiB 分块传输原始 APK；升级必须传当前 `expectedGeneration`。上传中断后用 `application_import_status` 查看原操作和偏移；不自动重放未知安装。`application_import_cleanup` 可取消尚未提交的上传，或释放已终结安装的暂存 APK，未知/运行中安装拒绝清理。

`project_get` / `project_script_put` / `project_selectors_put` 按包名操作唯一源码。写入需要当前 SHA，防止手机编辑器和 MCP 互相覆盖，保存不会部署。多个旧项目的差异源码保留为历史归档；先通过 `project_archives_list` / `project_archive_read` 审阅，再显式恢复或接受当前版本。手机入口在全局设置的“历史脚本与恢复”。

典型 Hook 顺序：`targets_list` → `hook_observe` → `hook_prepare` → `hook_activate` → 实际操作客体 → `hook_status` → `hook_disable`。准备返回的 token 和 sourceSha256 用来保证激活准确的已编译脚本，直接传给激活即可，无需手机审批。外部草稿不会覆盖手机项目编辑器的源码。`hook_discard` 用于丢弃未激活的准备；停用后无需额外丢弃。

工具结果的 `content[].text` 是 JSON，检查 `ok`、`state`、实际命中和错误数。每个修改明确提供唯一 `operationId`；重复的同 ID、同内容返回原回执，改内容会拒绝。结果不明时使用原 ID 的 `operation_get` 对账，不能更换 ID 重放修改。

Java `hook_observe` 和 `startupHook.selectors` 共用严格字段契约：

```json
{"operationId":"observe-counter-1","selectors":[{"id":"counter","kind":"java","className":"com.example.Counter","methodName":"value","argumentTypes":[]}]}
```

`argumentTypes` 必填，无参数时传 `[]`；可选 `classLoaderId` 必须来自当前进程。selector 的 `id` 不得重复。Native selector 使用 `id/kind/moduleName/symbol/argumentTypes/returnType/buildId/signatureSource`，`kind` 为 `native`，`signatureSource` 必须为 `confirmed`；不接受 Java 字段或未确认的 Native 签名。完整类型、长度和字段约束由 `tools/list` 发布，MCP 派发前校验；设备也会在执行观察命令前校验，拒绝错误 selector 时保留已有观察结果。

错误回执含 `errorCode` 和 `recovery`（建议步骤，不自动执行）：

| 回执 | 下一步 |
| --- | --- |
| `state: REJECTED, dispatched: false` | MCP 本地拒绝，未发送设备命令；按字段错误修正输入。 |
| `outcome: REJECTED` | 设备确认在执行前拒绝；修正内容使用新的操作 ID。 |
| `state: UNKNOWN` | 保留原 ID，使用 `operation_get` 查询，不换 ID 重放。未带明确拒绝标记的旧 worker 错误也按 UNKNOWN 处理。 |
| `HOOK_OUTCOME_QUERY_REQUIRED` | 先查询 `pendingOperationId` 的原始回执，再读 `hook_status`。`hook_events`、重选目标、关闭会话不能解除该门。状态查询不等于原操作已经成功。 |
| `AGENT_FIELDS_UNSUPPORTED`（操作查询） | 重连 MCP 并刷新工具，继续查询同一原始 ID。设备兼容旧客户端附带 session 的操作查询。 |
| `AGENT_REQUEST_ID_CONTENT_MISMATCH` | 同一 ID 已绑定其他命令，先查询；不能拿 observe 的 ID 调用 discard。 |

只有明确的执行前拒绝释放本次占用；超时、派发后异常和未知结果继续保留进程状态门。失败的恢复查询不会覆盖最初待确认的操作 ID，也不会清除既有未知状态。

会话只是目标上下文，不代表已认证身份，不设十分钟授权期限或 256 次总调用上限。响应缓存仍有内存上限，底层修改日志保留幂等约束。项目绑定、安装代次和进程代次检查仍会阻止操作过期目标。手机 UI 和电脑可同时使用同一控制管线；正在执行的操作仍串行处理。

关闭 MCP 或停止电脑入口，都不会自动结束客体或停用已激活 Hook；需要停用时显式调用 `hook_disable`。停止入口后可在手机卡片重新开启。引擎重启后重新列出目标并使用新的代次。

## 验证

```sh
node --test agent/agent.test.mjs agent/audit.test.mjs
node android/runtime/script-tests/run.mjs
python3 android/runtime/test/HookRecoveryHostTest.py
python3 android/scripts/validate-agent-control.py --serial DEVICE_SERIAL --install --evidence docs/evidence/NEW_DIRECTORY
```

当前真机验收使用独立 stdio MCP 客户端和自研计数器：无配对/审批激活返回值 Hook、真实 UI 命中、同操作去重、停用后原始行为恢复、多个独立 Agent 接入、断开和关闭上下文后客体保持运行。验收同时核对 APK 与源码回执、进程和 Native 映射。结果见对应证据目录；此流程不代表 Sentinel 桌面或模型已经实际调用。

`sentinel-acceptance.mjs SERIAL FIXTURE_JSON EVIDENCE_DIRECTORY` 可为当前运行目标生成 Sentinel 导入配置和只读验证提示。可选 `server.mjs --audit /absolute/metadata.jsonl` 保存白名单调用元数据和摘要。

本次全局控制与单项目改造的主机验证命令：

```sh
node --test agent/agent.test.mjs agent/audit.test.mjs
python3 android/runtime/test/ApplicationProjectsSchemaTest.py
python3 android/workbench/test/run-source-tests.py
```

新的全局真机验收脚本通过真实 stdio MCP 验证两空间共用项目、显式目标交替、真实返回值 Hook/停用恢复和独立停止。它要求自有 launcher fixture 在该 Tripoder 安装中尚不存在；建议使用独立验收 APK。

```sh
node agent/global-acceptance.mjs --serial DEVICE_SERIAL --evidence artifacts/global-device-acceptance
```

原生界面与 schema 34 升级验收（需要先安装匹配签名的产品 APK 与 control-test APK）：

```sh
python3 android/scripts/validate-single-project-global.py --serial DEVICE_SERIAL --evidence artifacts/single-project-native
```

使用独立验收包时，加 `--package dev.tripoder.engine.globaltest --test-package dev.tripoder.controltest.globaltest`；stdio MCP 加 `--device-port 29732`。全局验收可用 `--native-sha256 EXPECTED_SHA256` 核对运行进程实际加载的原生库。源码写入与安装/删除后会通知原生界面刷新；正在编辑的草稿由 CAS 保留，旧目录快照不能把已归属应用的源码误归档。

源码存储测试使用主机 Context/AtomicFile 适配器，覆盖实际源码存储逻辑，不代表 Android IPC、原生原子文件崩溃恢复或真机 Hook 验收。

历史证据保留在 `docs/evidence/external-agent-*`、`sentinel-agent-*`。其中签名、手机审批、授权期限与断联终止等结论属于旧实现，不适用于当前入口。

## 通用启动探针与代码快照

见 [运行时观察与代码快照](../docs/runtime-observation.md)。新增工具覆盖能力查询、未运行项目、启动前配置、ClassLoader/DEX、事件、内存 DEX 快照和分块产物读取。配置默认关闭；没有新增配对或人工审批。

冷启动 `process_start` 的设备传输等待预算为 65 秒，MCP 客户端应至少保留 75 秒请求预算。其他请求仍使用有界的短预算。任何变更超时都不自动重发：先使用返回的原始 `operationId` 对账，再按明确目标读取实际状态。
