# Tripoder MCP：stdio 与 HTTP

两种用户可选入口共用手机 MCP 和原有控制管线，不使用 ADB、自动传输切换或旧配置兼容。

```
stdio 客户端 → 本地 Node 适配器 → HTTP /mcp ─┐
HTTP 客户端 ──────────────────→ HTTP /mcp ─┴→ WorkbenchAgentControl → Binder → authority → 客体
```

## 手机准备

1. 手机与客户端连接到可互通的 Wi-Fi 或以太网；首版使用 IPv4。
2. Tripoder「设置 → 外部 Agent → 开启网络 MCP」。首次安装默认关闭。
3. 记录手机显示的 `http://IP:29731/mcp`，导出连接配置到可信位置。

配置仅包含 `url`，例如 `{"url":"http://IP:29731/mcp"}`。不包含证书、密钥或访问令牌。

入口只使用唯一的 Wi-Fi/以太网 IPv4 地址，与系统默认上网是否走蜂窝网络无关。多个局域网地址时明确拒绝，需先选择单一网络。网络地址消失时关闭，重新开启并导出新配置。已明确开启的入口会在下次打开 Tripoder、引擎就绪后恢复；不是开机自动启动。停止入口后保持关闭，需手动重新开启。

## stdio

需要 Node 24 或以上。把手机导出的连接配置保存到电脑，添加：

```json
{
  "mcpServers": {
    "tripoder": {
      "type": "stdio",
      "command": "/absolute/path/to/node",
      "args": ["/absolute/path/to/tripoder/agent/server.mjs", "--connection", "/private/path/tripoder-connection.json"]
    }
  }
}
```

适配器通过 HTTP 访问手机，不查找/运行 adb，不使用 ANDROID_SERIAL。手机离线时初始化和工具发现也会失败，不能把本地缓存当成手机在线。stdout 只输出 MCP JSON-RPC；诊断写 stderr。

不再接受 `--serial`、`--device-port`、旧 `--config` 或配对文件。

## HTTP

客户端选择 **Streamable HTTP**，URL 为手机显示的地址，无需配置 Authorization 请求头。

端点仅使用 HTTP，不生成、导出或校验证书。客户端显式配置手机显示的 HTTP 地址；不接受旧 HTTPS 地址或 certificatePem 字段，不自动切换协议。没有实现 OAuth 自动授权，也不宣称所有云端 MCP 客户端都可连接手机局域网。

协议版本仅 `2025-11-25`；POST JSON 响应，通知返回 202，GET SSE 返回 405。initialize 返回 `Mcp-Session-Id`；后续 POST 需携带该头和 `MCP-Protocol-Version: 2025-11-25`。DELETE 释放该 MCP 会话，不结束客体或停用 Hook。请求需同时接受 `application/json`、`text/event-stream`。校验 Host、Origin、请求大小和字段后才派发工具。

入口开启后，可访问该局域网地址的客户端无需凭证即可调用工具。手机不需要逐条批准工具调用。连接停止不能撤销已经派发的业务命令；不确定结果使用原操作 ID 对账。

## 工具与文件

手机工具契约由 `agent/tools.mjs` 在构建时生成 `mcp-contract.json` 并打包进 APK。HTTP 发布共享工具；stdio 从手机读取同一列表，额外发布本地便利工具 `application_import`、`file_pull`、`file_pull_status` 和 `artifact_verify`。

全部 37 个工具（HTTP 33 个，stdio 额外 4 个）的入参契约由 `tools.mjs` 和 `tool-schema.mjs` 共同定义。字段说明标明 ID/游标的查询来源、空字符串的含义、必填项和数值/长度边界；HTTP 与 stdio 读取同一份手机契约。全局查询使用 `runtime_query` 的固定 action；没有默认 action。模型看到完整公开 anyOf action 分支；同源路由元数据通过 MCP `_meta` 发布，客户端和手机派发前分别严格校验。只有 `headers` 和脚本 `parameters` 等动态字典允许自定义键，并限制值类型。

复杂参数示例（外层仍需 `operationId` 和精确 `target`）：`http_send_request.request` 使用 `{method:"GET",url:"https://example.com/",timeoutMs:5000}`；`ui_dispatch_action.action` 使用 `{kind:"tap",x:120,y:240}` 或 `{kind:"setText",id:"search",text:"example"}`；`guest_invoke.invocation` 使用 `{className:"com.example.Counter",methodName:"value",argumentTypes:[],arguments:[]}`。Java `long` 必须传十进制字符串，`byte[]` 使用偶数长度十六进制字符串；参数数量、类型与范围在派发前校验。

- `application_import`（仅 stdio）：读取电脑绝对路径 APK，计算 SHA-256，使用共享分块上传接口。
- `application_upload(action=begin)`：传 `operationId`、`space`、`expectedGeneration`、`files:[{name,size,sha256}]`。首文件 `base.apk`，后续 `split-1.apk` 等。
- `application_upload(action=write)`：每块独立 `operationId`，传 `uploadId`、`index`、`offset`、base64 `data`，最多 12 KiB 原始字节。
- `application_upload(action=commit)`：`operationId` 必须等于原 upload ID。校验全部字节后安装。
- `application_query(action=import_status)`：按原 upload ID 查询暂存偏移和安装回执。
- `application_upload(action=cleanup)`：显式取消未提交上传或释放已终结安装的暂存；不清理 UNKNOWN/运行中操作。

HTTP 不读取客户端本地路径。双方均支持设备原始 APK 导入 `application_manage(action=import_device)`、源码 SHA/CAS、产物分块读取及原控制工具。

有安装作用域的调用带 `target:{space,package,installGeneration}`；有进程作用域的调用再带 `processGeneration`。连接没有默认选中目标，也不自动选择唯一候选。

修改使用明确、唯一的 `operationId`；同 ID、同内容返回原回执。执行前本地校验、连接失败可报告 `dispatched:false`。可能已派发后断网为 UNKNOWN，不自动重发，使用 `operation_get` 对账。查询到后续状态不等于原操作已成功。

手机端查询最多等待 15 秒，修改操作最多等待 60 秒；超时返回 `MCP_EXECUTION_TIMEOUT`，查询为 `UNAVAILABLE`，修改为 `UNKNOWN` 并保留原 operationId。同步 Binder 事务可能仍在执行，超时不证明远端停止。独立执行池最多容纳两个未结束事务，不排队；耗尽时返回 `MCP_DISPATCH_BUSY` / `dispatched:false`。ping、工具列表与会话撤销不持有执行锁。必须先检查原回执与实际状态，不能通过自动重试或重启入口来推断原操作失败。

典型 Hook 流程：application_query(action=targets) → hook_observe → hook_prepare → hook_activate → 实际调用/操作 → process_inspect(action=hook_status) → hook_disable。ACTIVE 不证明实际命中；应检查返回值、hits、errors 和停用后行为恢复。

## 工具分组

当前公开入口、固定 action、关键生命周期工具及旧引用迁移详见 [MCP 工具分组](../docs/mcp-tool-groups.md)。旧工具名不再派发，错误会给出替代入口；不会自动重放旧修改。

## 通用文件 pull

手机普通文件、递归目录和 DEX dump 共用 `file_export` / `file_query`；支持全局绝对路径、精确客体安装的数据/APK 根和已有产物引用。电脑侧负责落盘、断点续传、SHA-256 校验和目录重建；详见 [通用文件 pull](../docs/file-pull.md)。

## 验证

```sh
python3 android/workbench/test/run-mcp-tests.py
python3 android/workbench/test/run-source-tests.py
python3 android/scripts/validate-network-mcp.py --serial DEVICE --install --evidence docs/evidence/NEW_DIRECTORY
```

主机测试运行生产 Java HTTP/MCP/schema 代码，控制执行是主机适配器，不能代表 Binder 或真实 Hook。

真机 validator 必须使用匹配当前源码回执的 APK。ADB 仅负责安装、临时连接配置导出与自有环境准备；实际调用阶段停止 ADB server，独立原生 HTTP 客户端与 stdio 子进程分别导入原始 APK、启动客体、激活 Hook、验证返回值和命中、停用恢复，并跨入口核对目标和操作去重。连接配置仅保存在临时目录，验收产物不记录 Hook 准备 token。

建议使用 `--package dev.tripoder.engine.mcpnettest` 构建的独立验收安装，保留现有正式应用数据。真机测试不代表 Sentinel 桌面、云端客户端、锁屏长时间运行或公网可达性验收。

旧 `open-acceptance`、`global-acceptance`、`validate-open-agent` 等脚本/证据记录的是旧 ADB 接入，不能用于当前版本验收。

对象引用、静态 Manifest/存储及精确错误使用当前 [v3 契约](../docs/mcp-tool-groups.md)。旧 process_inspect(manifest/storage) 不再执行。运行物理验收可使用 `node scripts/qa/mcp-contract-acceptance.mjs --mode owned --space EXPLICIT_QA_SPACE --connection PRIVATE_CONNECTION --evidence OUTPUT`；target 模式要求明确安装/空间，报告业务启动与工具机制的不同验收结果。连接配置不放入 evidence 或 Git。
