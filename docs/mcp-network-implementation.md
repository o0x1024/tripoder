# stdio / HTTP MCP 网络接入交付

更新日期：2026-10-25（Asia/Shanghai）。当前 MCP 已改为 HTTP；主应用部署、权限流程、双入口实际模型调用及 MCP 下载验收见 [HTTP 真机交付证据](evidence/mcp-http-only-2026-10-25/README.md)。下方历史 TLS/认证证据保留归档，不代表 HTTP 版真机验收。

## 当前架构（2026-10-25 HTTP 版）

stdio 客户端 → Node 适配器 → 手机 HTTP `/mcp`；HTTP 客户端直接访问同一入口。手机严格校验工具契约，再进入 `WorkbenchAgentControl → Binder → authority`。不使用 ADB 传输、HTTPS 降级或旧证书配置兼容。

- `AgentHttp` / `AgentControlService` 使用普通 Socket/ServerSocket，保留有界请求、Host/Origin、协议和会话校验。
- `AgentNetworkIdentity` 只选择明确 LAN 地址，导出 `{"url":"http://IP:29731/mcp"}`。不生成/导出/校验证书，TLS 证书实现已删除。
- Node/Python 只接受 URL-only HTTP 配置；旧 HTTPS 地址和 certificatePem 字段明确拒绝，不自动切换。
- Sentinel 使用现有通用 HTTP MCP 客户端连接此地址，证书字段为空；无需关闭其通用 TLS 校验。
- Android 37 本地网络权限必须获准后才启动网络入口；点击“开启网络 MCP”会请求系统授权，拒绝时明确提示且不宣告就绪。该权限保护 TCP/UDP 的局域网访问，参见 [Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)。
- 电脑侧 MCP 负责导入、下载与产物验收；手机保留统一执行管线，UNKNOWN 不重放。

## 历史验证结果（以下非本次 HTTP 验收）



| 层级 | 结果 | 证据 |
| --- | --- | --- |
| 生产 Java HTTP/MCP/schema 的主机测试 | 16 项通过 | `evidence/network-mcp-2026-10-22-01/host-tests.txt` |
| 项目源码 CAS/恢复回归 | 18 项通过 | `evidence/network-mcp-2026-10-22-01/source-tests.txt` |
| Android 真机启动、可信 TLS、未认证拒绝 | 3 项通过 | `evidence/network-mcp-2026-10-22-01/device-09/bootstrap-result.json` |
| stdio 与独立原生 HTTPS 客户端调用 | 23 项通过 | `evidence/network-mcp-2026-10-22-01/device-09/network-result.json` |
| ADB 停止期间调用，结束时仍未运行 | 通过 | `evidence/network-mcp-2026-10-22-01/device-09/summary.json` |
| 正式安装 stdio / HTTP 只读调用 | 9 项通过，ADB 停止期间调用 | `evidence/network-mcp-2026-10-22-01/product-readonly/summary.json` |
| 正式 APK 与验收 APK 源码一致性 | 489 个输入文件全部一致，当前客户端源码亦匹配验收摘要 | `evidence/network-mcp-2026-10-22-01/final-verification.json` |

真实 Hook 返回值：stdio `7 → stdio-7 → 7`，HTTP `7 → http-7 → 7`；两者均有实际命中，errors=0。覆盖了原始 APK 上传/安装、进程启动、跨入口读取同一进程、Hook 激活/调用/停用恢复、跨入口操作去重、过期目标拒绝、错误令牌/Origin 拒绝与证书信任要求。测试结束已删除本轮自有空间、安装和项目，并释放本轮上传暂存；两个临时验收宿主安装也已卸载，手机保留更新后的正式 Tripoder。

真机验收安装是 `dev.tripoder.engine.mcpnettesttls`，保留原正式应用数据。ADB 只用于装包、准备和临时配置导出；业务调用程序没有 ADB 路径，stdio 子进程的 PATH 也不提供任何外部程序。凭证只在私有临时目录中使用，退出后删除，不进入证据。

正式产品 APK：`android/product/build/tripoder.apk`，SHA-256 `c624fe220deecdf1d6043c12775dfb837195dd4bbcec0109eb2635ec8109c88d`。当前已更新手机正式安装，保留原应用数据；正式安装另通过两种入口的 9 项只读检查，期间 ADB server 也已停止。独立验收 APK 与正式 APK 包名不同，源码输入相同，完整 Hook 流程在独立安装验证，正式安装验证只读接入；不能称 Sentinel 桌面或真实模型已调用。

## 验收中修复的问题与保留的失败

- `device-01`：手机未连接 LAN，入口明确失败。
- `device-02`：Wi-Fi 存在但默认上网走蜂窝；已改为选取唯一实际 LAN 地址，不依赖默认 Internet 网络。
- `device-03/04`：Mac TUN 路由与访客网隔离造成网络失败；用户已处理网络并切换到可互访网络。
- `device-05/07`：TLS 私钥原先只授权 SHA-256，Conscrypt 的 NONEwithECDSA 摘要签名被 AndroidKeyStore 以 INCOMPATIBLE_DIGEST 拒绝。已修正密钥授权并验证；没有降低证书验证或采用其它传输。
- `device-06`：网络切换期间没有 LAN 地址，明确失败。
- `device-08`：首次完整通过；`device-09`：最终客户端脚本复跑通过。旧失败目录没有覆盖。错误密钥只存在于本轮旧独立测试安装，临时测试安装已卸载；产品不增加旧密钥迁移或自动重建分支。

密钥授权依据：[Android KeyGenParameterSpec.Builder.setDigests](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...))；MCP HTTP 语义依据：[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。

## 历史使用与边界（已由当前 HTTP 说明替代）

见 [使用说明](../agent/README.md)。安装正式 APK 后在“设置 → 外部 Agent”开启入口并导出配置，用户选择 stdio 或 HTTP。HTTP 客户端无需认证请求头，需支持本地证书信任；没有实现 OAuth 自动授权或公网中继。

后续已完成 Sentinel 桌面接入：为每个 MCP 服务器配置独立受信公共证书，保留 TLS 校验，实际发现 69 个工具，并通过桌面调用 `runtime_capabilities`。按用户要求卸载 `globaltest` 及配套测试包后，再次停止 ADB 服务、在 Sentinel 断开并重新连接主应用，工具调用返回 `ok=true`、`is_error=false`，requestId=`bcfbe4ea-13bf-4ce4-b470-d53e74d3bad8`。完整桌面证据保存在 Sentinel 仓库的 `docs/evidence/mcp-trusted-certificates-2026-10-22-01/`。

尚未覆盖云端客户端接入、长时间锁屏运行和派发后丢失回执的网络故障注入；主机侧已验证 UNKNOWN 不自动重放。下一步建议验证长时间锁屏和网络切换后的明确恢复流程。

## 2026-10-22：schema 与执行等待修复

- 全部 70 个工具（HTTP 69 个、stdio 本地导入 1 个）的字段增加含义、ID/游标来源、长度边界和额外字段策略。HTTP 请求、五种 UI 动作及其 `type` 别名、Java 方法调用、动态参数字典使用明确的嵌套 schema。无参工具明确说明调用 `{}`。
- Node 与手机 Java 使用同一契约，补齐通用 anyOf、number、null、字典值和属性名校验；方法调用额外校验参数数量与 Java ABI 数值/long/hex-byte[] 边界。
- 手机 MCP 会话锁不再包围 Binder 执行。查询等待 15 秒、修改等待 60 秒；两个有界执行槽且不排队，耗尽时明确返回未派发。超时查询返回 UNAVAILABLE，修改返回 UNKNOWN 并携带原 operationId 和禁止自动重放的恢复步骤。
- Sentinel 对单次 MCP 调用设置 75 秒等待预算，并在等待远端期间释放客户端 mutex。取消/中断收尾通过持久化执行记录解析实际 conversation_id；工具开始记录携带 execution_id/generation，并处理取消后延迟写入开始记录的竞态。工具卡片支持 cancelled/timed_out 显示。

验证：70 份 schema 通过标准 JSON Schema 编译；生产 Java 校验器通过 320 个共享参数案例及阻塞查询/修改、服务发现、会话撤销和执行槽耗尽检查；HTTP/stdio 主机测试 19 项通过。Sentinel 数据库回归 3 项、MCP 适配器回归 4 项、工具卡片测试 12 项通过，`cargo check -p sentinel-ai --lib` 与 `vue-tsc --noEmit` 通过。

最终 APK 已构建，SHA-256 为 `17d483973dbd1667380773494bfb8e5b81361d80aff9a82dd32e01d47813e3a0`；包内契约与源码一致，相关生产输入摘要已核对。本轮没有安装手机或重启 Sentinel，也没有改写用户现有对话数据库；以上结果属于构建与主机验证，尚不代表更新后的真机/桌面端验收。需要更新手机 APK、运行新的 Sentinel 构建，并重连 MCP 以读取新工具契约。


## 2026-10-25：移除连接凭证

手机不再生成、导出、校验或重置 MCP Bearer 令牌，Node 与 Python 客户端不再读取或发送连接令牌。新连接配置严格只接受 `url`、`certificatePem`，不兼容含 token 的旧配置；重新导出即可。连接配置不再要求 0600 权限。TLS 1.3、证书信任、Host/Origin、MCP 会话和业务目标/操作回执继续使用现有路径。

主机验证：生产 Java schema 443 个案例、查询/修改超时和会话生命周期检查，以及 HTTP/stdio/文件 pull 等 25 项 Node 测试通过。Android 与 Sentinel 桌面无凭证连接尚未真机验收；更新手机 APK、重新导出配置并配置 stdio 的 `--connection` 参数后进行验证。

2026-10-25 后续交付：已覆盖安装现有 `dev.tripoder.cmbqa`，更新本机连接配置与 Sentinel 保存配置；ADB 停止期间 stdio/HTTP 只读真机验证 9 项通过。安装后恢复中断及最终显式重连证据见 [本轮验收](evidence/mcp-no-credentials-2026-10-25/README.md)。Sentinel 桌面重连尚未验证。
