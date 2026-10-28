# AI Agent 全局控制面与 MCP 规范

MCP 连接到设备上的 Tripoder 全局入口，不绑定任何默认空间、应用、项目或进程。每次有作用域的调用都显式携带目标，完整连接配置与参数示例见 [MCP 客户端说明](../agent/README.md)，工具列表以 [`agent/tools.mjs`](../agent/tools.mjs) 与 [MCP 工具分组 v3](mcp-tool-groups.md) 为准。

---

## 1. 控制链路

提供两种显式选择的接入入口：

- **stdio MCP** → 本地 Node 适配器（[`agent/server.mjs`](../agent/server.mjs)） → 手机 HTTP `/mcp`
- **Streamable HTTP MCP 客户端** → 手机 HTTP `/mcp`

手机端 `AgentMcp` 统一处理初始化、工具发现、参数校验和分派 → `WorkbenchAgentControl` → 主进程 Binder → `:authority` → 安装或具体 `:slotN` 客体进程。网络入口默认关闭，仅绑定局域网 Wi-Fi/以太网 IPv4 地址（`http://IP:29731/mcp`），派发前校验 `Host`/`Origin`、协议版本 `2025-11-25` 和有界 HTTP 请求（详见 [MCP 网络接入规约](mcp-network-implementation.md)）。

MCP 会话只管理协议就绪状态（最多 64 个、空闲 24 小时过期），不持有目标或业务权限。关闭会话或全局入口不停止应用或已激活 Hook；可能已经派发的操作不能因断网推断已取消。

---

## 2. 显式对象与目标作用域

- **空间操作**：使用 `space`；应用删除使用包名和明确的安装代次清单。
- **安装级操作**：使用 `target: {space, package, installGeneration}`。
- **进程级操作**：使用 `target: {space, package, installGeneration, processGeneration}`。
- **项目源码操作**：按包名定位应用唯一项目，写入同时校验当前源码/声明 SHA（CAS 防并发覆盖）。
- **异步对账与产物读取**：操作查询使用原 `operationId`；不可变产物读取使用原 `processGeneration` 及 `artifactId`。

没有连接级的“当前选中目标”，也没有单候选自动选择（旧 `target_select` / `session_get` / `session_close` 已废弃）。目标列表从有效安装及已有精确绑定的 `RUNNING` 进程生成，不要求用户预先手动创建项目。

---

## 3. 核心能力覆盖

全局状态与槽位资源查询、空间管理、设备已装应用发现、原始 APK/Splits 导入与升级、安装移除、应用彻底删除、项目源码读写与历史恢复、进程启停与退出历史查询（`process_history`）、启动探针（`startupHook` / `probes_control`）、按需 Java/Native Hook 生命周期、运行时对象内省与存活实例调用（`heap_query` / `guest_invoke`）、ClassLoader/DEX 内存快照、多窗口/Compose UI 树查询与截图交互、存储与网络审计（被动 TLS/HTTP1/HAR）、环境伪装（`persona_control` / `env_mock`）、隐私/恶意行为探针及通用文件拉取（[file-pull.md](file-pull.md)）。

---

## 4. 状态幂等与恢复契约

- 运行时修改使用 `:authority` 持久化操作日志；主进程源码/名称修改使用原子落盘的本地操作日志，统一由 `operation_get` 查询。
- 相同 `operationId` 传入不同参数明确拒绝。进程退出或重启后，旧 `processGeneration` 拒绝执行；结果 `UNKNOWN` 时必须保留原 `operationId` 对账，严禁换新 ID 自动重放。
