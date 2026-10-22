# stdio / HTTP MCP 网络接入交付

日期：2026-10-22（Asia/Shanghai）。代码已实施；独立真机安装已完成两种入口的实际工具调用。

## 架构

stdio 客户端 → Node 适配器 → 手机 HTTPS `/mcp`；HTTP 客户端直接访问同一入口。手机统一校验工具契约，再进入现有 `WorkbenchAgentControl → Binder → authority`。不使用 ADB 传输、旧连接配置兼容或自动切换。

- 工具定义：`agent/tools.mjs` → 构建生成 APK asset `mcp-contract.json`。
- `AgentMcp`：单一手机 MCP 分派、会话就绪状态、严格参数校验。
- `AgentHttp` / `AgentControlService`：有界 HTTP 请求、TLS 1.3、令牌与 Host/Origin 校验、前台服务和网络变化关闭。
- `AgentNetworkIdentity` / `AgentCertificate`：AndroidKeyStore 非导出私钥、当前 LAN IP SAN 证书、安装级可重置随机令牌。显式授权 TLS 所需的 NONE/SHA-256 digest，并在启动时校验密钥策略。
- `AgentConnectionActivity` / 设置卡片：首次默认关闭、开启/停止、系统文件选择器导出配置、重置凭证。
- Node stdio：只转发共同 MCP 消息，另外提供电脑本地 APK 导入便利工具，内部使用手机共享的 begin/write/commit 上传。

## 验证结果

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

## 使用与边界

见 [使用说明](../agent/README.md)。安装正式 APK 后在“设置 → 外部 Agent”开启入口并导出配置，用户选择 stdio 或 HTTP。HTTP 客户端需支持自定义 Bearer 请求头及本地证书信任；没有实现 OAuth 自动授权或公网中继。

后续已完成 Sentinel 桌面接入：为每个 MCP 服务器配置独立受信公共证书，保留 TLS 校验，实际发现 69 个工具，并通过桌面调用 `runtime_capabilities`。按用户要求卸载 `globaltest` 及配套测试包后，再次停止 ADB 服务、在 Sentinel 断开并重新连接主应用，工具调用返回 `ok=true`、`is_error=false`，requestId=`bcfbe4ea-13bf-4ce4-b470-d53e74d3bad8`。完整桌面证据保存在 Sentinel 仓库的 `docs/evidence/mcp-trusted-certificates-2026-10-22-01/`。

尚未覆盖云端客户端接入、长时间锁屏运行、真机凭证重置界面和派发后丢失回执的网络故障注入；主机侧已验证 UNKNOWN 不自动重放。下一步建议验证长时间锁屏和网络切换后的明确恢复流程。
