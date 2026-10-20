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

只有一台 ADB 设备时可以省略 `--serial`，也可以使用 `ANDROID_SERIAL`。手机无需额外点击配对或批准。多台设备时须指定 serial；多个客体时用 `targets_list` 和 `target_select` 明确选择项目与实际进程代次。只有一个主进程目标时，首次工具调用会自动选择。

旧配置里的 `--config` 暂时兼容：只读取可选 serial，不读取身份、私钥或会话授权；文件不存在也不影响 MCP 初始化。`pair.mjs` 只提示无需配对，不再生成密钥。新配置直接使用 `--serial`。

MCP 初始化和工具发现不依赖手机在线或 ADB 存在；执行工具时才建立设备连接。支持协商 `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25`。stdio 只输出 JSON-RPC，诊断写 stderr。手机传输为 ADB 转发到 `127.0.0.1:29731` 的无签名长度帧，不是 HTTP MCP 入口。

## 工具与操作

提供 42 个工具，覆盖目标选择、会话/进程、操作回执、Hook 观察/准备/激活/停用/参数、事件、DEX/Native 查询、UI、存储、网络流量、HTTP 和客体方法调用。完整参数以 `tools/list` 为准。

典型 Hook 顺序：`targets_list` → `target_select` → `hook_observe` → `hook_prepare` → `hook_activate` → 实际操作客体 → `hook_status` → `hook_disable`。准备返回的 token 和 sourceSha256 用来保证激活准确的已编译脚本，直接传给激活即可，无需手机审批。外部草稿不会覆盖手机项目编辑器的源码。`hook_discard` 用于丢弃未激活的准备；停用后无需额外丢弃。

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

关闭 MCP、调用 `session_close` 或停止电脑入口，都不会自动结束客体或停用已激活 Hook；需要停用时显式调用 `hook_disable`。停止入口后可在手机卡片重新开启。引擎重启后重新列出和选择目标。

## 验证

```sh
node --test agent/agent.test.mjs agent/audit.test.mjs
node android/runtime/script-tests/run.mjs
python3 android/runtime/test/HookRecoveryHostTest.py
python3 android/scripts/validate-agent-control.py --serial DEVICE_SERIAL --install --evidence docs/evidence/NEW_DIRECTORY
```

当前真机验收使用独立 stdio MCP 客户端和自研计数器：无配对/审批激活返回值 Hook、真实 UI 命中、同操作去重、停用后原始行为恢复、多个独立 Agent 接入、断开和关闭上下文后客体保持运行。验收同时核对 APK 与源码回执、进程和 Native 映射。结果见对应证据目录；此流程不代表 Sentinel 桌面或模型已经实际调用。

`sentinel-acceptance.mjs SERIAL FIXTURE_JSON EVIDENCE_DIRECTORY` 可为当前运行目标生成 Sentinel 导入配置和只读验证提示。可选 `server.mjs --audit /absolute/metadata.jsonl` 保存白名单调用元数据和摘要。

历史证据保留在 `docs/evidence/external-agent-*`、`sentinel-agent-*`。其中签名、手机审批、授权期限与断联终止等结论属于旧实现，不适用于当前入口。

## 通用启动探针与代码快照

见 [运行时观察与代码快照](../docs/runtime-observation.md)。新增工具覆盖能力查询、未运行项目、启动前配置、ClassLoader/DEX、事件、内存 DEX 快照和分块产物读取。配置默认关闭；没有新增配对或人工审批。
