# Tripoder MCP

Tripoder 不连接模型。此目录提供 Node.js stdio MCP 服务，使用显式 ADB serial 将签名帧转发至手机的 `127.0.0.1:29731`。所有命令经工作台同一 Binder 控制连接，在引擎中校验配对主体、项目、绑定代次、安装代次、进程代次和源码审批，再进入现有 Hook 管线。

目前提供 11 个工具：`session_get`、`process_get`、`operation_get`、`hook_observe`、`hook_prepare`、`hook_activate`、`hook_status`、`hook_modules`、`hook_disable`、`hook_discard`、`session_close`。没有安装、任意 shell、任意 JavaScript eval、堆读取、文件导出或模型调用工具。客体输出只作为不可信数据。

## 启动

1. 工作台打开已绑定项目并启动客体。在“外部 Agent”卡片开启入口，查看设备配对公钥，将完整公钥保存到电脑文本文件。该公钥通过手机界面核对；不能信任连接端返回的首个未知公钥。
2. 为本次会话使用新的私有配置路径。运行：

   ```sh
   node /absolute/tripoder/agent/pair.mjs --serial DEVICE_SERIAL --device-public-key-file /absolute/device-public-key.txt --config /absolute/tripoder/.tripoder-local/session-name.json
   ```

3. 手机核对客户端指纹，选择实际进程并批准观察与准备。私钥只存在权限为 `0600` 的本地配置文件；不要复制私钥到 MCP JSON、聊天、环境变量、源码或证据目录。手机私钥由 Android Keystore 持有。
4. 在 Sentinel 工具管理导入以下配置，将所有路径换为真实绝对路径，然后连接：

   ```json
   {"mcpServers":{"tripoder":{"type":"stdio","command":"/absolute/node","args":["/absolute/tripoder/agent/server.mjs","--config","/absolute/tripoder/.tripoder-local/session-name.json"]}}}
   ```

5. `session_get` 确认实际目标；`hook_observe` 提交有界 selector；将返回的 `evidence.id` 与源码交给 `hook_prepare`。手机审阅准确源码及 SHA 后，才能用准备 token 和同一 SHA 调用 `hook_activate`。
6. 实际操作客体，使用 `hook_status` 检查命中、错误与在途调用；使用当前 revision 调用 `hook_disable`，再实际验证后续方法恢复原行为。最后 `session_close` 并查询到 `CLOSED`。停用不能回滚既有业务副作用。

MCP 协议固定为 `2025-03-26`，与当前 Sentinel 的 rmcp 客户端一致；其他版本明确拒绝，没有降级。stdio 只写 JSON-RPC，诊断写 stderr。工具业务结果在 `content[].text` 中保存完整 JSON，必须读取 `ok` 与状态；Sentinel 外层工具返回或“测试完成”提示不等于业务成功。

## 授权和失败语义

每次会话最长 10 分钟、256 个已接纳请求。批准后最多等待 8 分钟建立首次认证连接；连接后 45 秒未收到有效签名请求即撤销。请求接纳与 watchdog 都检查单调时钟截止时间，迟到请求不能续活，总期限不能延长。MCP 运行中每 10 秒发一次会话查询，计入设备预算。一个活跃修改会话排他控制一个实际进程。每次脚本激活需要手机批准对应源码摘要；外部草稿不会写入人工编辑器。

签名采用 P-256 / SHA256withECDSA，输入是 UTF-8 的 `tripoder-agent-v1\n方向\n会话ID\n准确payload字符串`。设备公钥与请求关联都必须匹配。设备和桌面分别验证对方签名，引擎再次验证客户端签名；localhost、USB、ADB 和 APK 签名本身均不赋予外部调试权。

修改工具必须显式提供 operationId。同 ID、同内容返回已保存回执；改变内容拒绝。连接中断不自动重放、不重新配对、不换进程或后端。结果不明返回 `UNKNOWN` 及原操作 ID，使用 `operation_get` 对账。引擎重启不恢复活跃授权。

撤销立即拒绝新的修改。已有 Hook、观察/准备状态或在途操作时，引擎保守地终止准确的客体进程并记录关闭操作。`session_close` 本身作为在途请求，也会触发该策略。`CLOSING` 不是完成，`CLOSE_UNKNOWN` 不能显示成功；只读会话/操作查询在原控制租约有效时仍可用于对账。

## 验收

```sh
node --test agent/agent.test.mjs
python3 android/scripts/validate-agent-control.py --serial DEVICE_SERIAL --install --evidence docs/evidence/NEW_DIRECTORY
```

2026-10-14 `external-agent-03`：独立 MCP 验收客户端 18 项、手机真实 UI 10 项通过。自研未修改计数器在同一 Activity/进程中从 Hook 输出恢复为原始输出，两个 UID 分离，未观察到 Java/Native fatal。手机审批由自研测试 Instrumentation 操作，不能称为人工审批验收。该结果不证明 Sentinel 桌面/模型实际调用，也不证明微信、王者或完整 P2–P6 通过。

失败记录保留：01 暴露拒绝账本错误码格式错误，已修正；02 重新启动 UI 未出现预期值，03 改为直接验证停用后的同一真实调用，重新启动另做回归，不能用 03 覆盖 02 的问题。


2026-10-15 `sentinel-agent-02`：真实 Sentinel AI 助手 8 次 MCP 调用、手机 12 项检查通过。观察→准确源码准备→手机自研 Instrumentation 审批→激活→2 次命中且 0 错误→停用→同一 Activity 原始计数恢复→手机撤销入口。`sentinel-ai-verdict.json` 将助手持久化工具记录与 MCP 原始回执 SHA-256 逐字节匹配，并确认同一执行的模型请求记录；没有读取其他会话或修改 Sentinel 数据库。该结论仍不覆盖人工源码审批、完整 P5 或两款真实应用。

第一轮 `sentinel-agent-01` 因首次连接前就开始断联计时而失败，保留 `failure-analysis.json`。修复后真实手机先等待 55 秒再提供 Sentinel 配置，验证首次连接等待语义。7 项单调期限测试、5 项 AI 证据校验器测试另行通过。

真实 Sentinel 验收入口：`validate-agent-control.py --phase sentinel`，会生成本次配置与提示；用户在 Sentinel 导入/连接并执行提示。验收结束后使用只读命令 `python3 agent/verify-sentinel-ai.py --database /absolute/database.db --evidence /absolute/evidence-directory` 生成 AI 调用证据。可选 `server.mjs --audit /absolute/metadata.jsonl` 只保存白名单元数据和摘要，不记录私钥、准备 token 或脚本正文。
