# stdio / HTTP MCP 网络接入规约

- **更新日期**：2026-10-28
- **关联文档**：[Agent 全局控制面](agent-mcp-design.md) · [MCP 工具分组 v3](mcp-tool-groups.md) · [客户端使用说明](../agent/README.md)

---

## 1. 当前架构（HTTP `/mcp` 统一入口）

```
stdio 客户端 → 本地 Node 适配器 (agent/server.mjs) → 手机 HTTP /mcp ─┐
Streamable HTTP 客户端 ────────────────────────────→ 手机 HTTP /mcp ─┴→ WorkbenchAgentControl → Binder → :authority → :slotN
```

- **纯 HTTP 局域网传输**：手机端 `AgentHttp` / `AgentControlService` 使用标准 `ServerSocket`，绑定唯一的 Wi-Fi/以太网局域网 IPv4 地址（不依赖系统默认上网是否走蜂窝网络），导出配置仅包含 `{"url":"http://IP:29731/mcp"}`。不使用 ADB 转发、不生成或校验自签 TLS 证书、不兼容旧 HTTPS 配置。
- **协议与安全边界**：
  - 协议版本严格要求 `2025-11-25`（`MCP-Protocol-Version: 2025-11-25`）；`initialize` 返回 `Mcp-Session-Id`。
  - 业务派发前强制校验 `Host`、`Origin`（防 DNS Rebinding）及有界 HTTP 请求大小。
  - Android 17 (API 37) 首次开启「设置 → 外部 Agent → 开启网络 MCP」时显式请求系统本地网络权限（Local Network Permission），获准后才启动监听。
- **双端工具同源契约**：
  - 手机端公开工具与本地 stdio 适配器工具均由 [`agent/tools.mjs`](../agent/tools.mjs)、[`agent/tool-schema.mjs`](../agent/tool-schema.mjs) 与 [`agent/tool-groups.mjs`](../agent/tool-groups.mjs) 同源生成。
  - stdio 入口在手机公开工具基础上额外提供电脑本地便利工具：`application_import`（分块上传本地 APK）、`file_pull` / `file_pull_status`（本地落盘与断点续传）及 `artifact_verify`。

---

## 2. 执行预算、并发槽位与 `UNKNOWN` 恢复语义

- **会话与执行锁分离**：
  - MCP 会话仅维护协议就绪状态（最多 64 个会话，空闲 24 小时过期），不持有当前选中目标或业务锁。关闭 MCP 会话不停止客体应用或已激活的 Hook。
  - 手机端 MCP 独立执行池最多容纳 **2 个并发未结束事务**（不排队，耗尽时立即返回 `MCP_DISPATCH_BUSY` / `dispatched: false`）。
  - 查询操作等待预算为 **15 秒**（超时返回 `UNAVAILABLE`），修改操作等待预算为 **60 秒**（`process_start` 冷启动传输预算为 **65 秒**；超时返回 `UNKNOWN` 并保留原 `operationId`）。
- **目录与普通响应大小预算**：
  - `tools/list` 目录响应上限为 **1 MiB**；普通工具调用响应上限为 **512 KiB**。
- **严禁自动重放 `UNKNOWN` 操作**：
  - 修改操作一旦进入网络 I/O 后中断或超时，必须通过 `operation_get` 与对应状态查询接口（如 `process_inspect(action=hook_status)` 或 `application_query(action=process_history)`）按原 `operationId` 对账，禁止换新 `operationId` 自动重试。

---

## 3. 验证入口

```sh
# 主机端契约、Schema 与传输回归测试
python3 android/workbench/test/run-mcp-tests.py
python3 android/workbench/test/run-source-tests.py
node --test agent/schema.test.mjs

# 真机端到端网络 MCP 验收（ADB 仅用于安装与初始化，业务调用期间停止 ADB 服务）
python3 android/scripts/validate-network-mcp.py --serial d861a4fa --install --evidence docs/evidence/mcp-network-run
```
