# Tripoder MCP 改为 HTTP

日期：2026-10-25。按用户要求，MCP 网络入口从 HTTPS 改为 HTTP，移除 TLS 握手、证书生成/导出/校验。旧证书配置和 HTTPS 地址明确拒绝，不做协议兼容或自动降级。

## 实现

- Android `AgentControlService` 使用普通 ServerSocket/Socket；`AgentHttp` 使用有界 HTTP/1.1 请求解析，Host/Origin、会话、协议版本、参数与执行语义保持。
- `AgentNetworkIdentity` 仅选择明确 LAN IPv4 地址，导出 `{"url":"http://IP:29731/mcp"}`。`AgentCertificate.java` 已删除；MCP 不再读取/创建 AndroidKeyStore TLS 密钥或证书。
- Node stdio 与 Python MCP 客户端只接受 URL-only HTTP 配置。Node 的未知派发判定从 TLS secureConnect 事件改为 HTTP 请求 finish 事件，连接拒绝与可能已发送仍分别保留未派发/未知边界。
- 手机设置文案、配置导出、网络验收程序、主机 fixture 及当前说明文档同步更新。APK 开发签名及 guest TLS 观测功能不属于 MCP 传输证书，没有移除。
- Sentinel 直接使用其通用 HTTP MCP 客户端，Tripoder 连接不设置受信证书。不对其他 MCP 服务设置跳过 TLS 校验；没有引入 danger_accept_invalid_certs。

## 验证结果

- `python3 android/workbench/test/run-mcp-tests.py`：生产 Java HTTP/MCP、468 个 Java schema 样例及40项 Node 测试全部通过；包括 HTTP 上传/下载、断点续传、状态/UNKNOWN/验收规则和拒绝旧 HTTPS/证书配置。
- Python `tools/tpmcp.py` 在同一实际 Java HTTP fixture 上完成 initialize、session、tools/list。
- 设置 `SENTINEL_HTTP_ACCEPTANCE_WORKSPACE=/Users/like/code/sentinel/sentinel-ai-pro` 运行上述脚本：显式执行 Sentinel Rust `connected_tripoder_http_without_certificate`，原生 HTTP initialize 与35个工具的目录发现通过，无证书配置。该测试使用主机 fixture，不代表真机或桌面 UI 验收。
- Sentinel `mcp_transport::tests` 四项通过；源码回归 `run-source-tests.py` 19项通过；Python语法检查及两仓库 `git diff --check` 通过。
- 独立目录完整 APK 构建通过，包名 `dev.tripoder.cmbqa`，APK SHA-256 `50f2f9d4dab82823bee974a0b18d5e74576764f12a3838042d66369b4672f4d5`。见 `build-summary.json`。

## 真机部署与实际会话验收

用户继续授权后，确认原 `dev.tripoder.cmbqa` 已被其他任务移除，安装 QA 包返回 `INSTALL_FAILED_USER_RESTRICTED`，未绕过系统安装限制。核验当前主应用 `dev.tripoder.engine` 的开发签名一致后，构建并覆盖更新主应用，保留原数据与安装。首版 HTTP 主应用 SHA-256 为 `68aa03bcf926aaa147b6c1cc69a90f7636d6f4943b8d967a3bdc9bf6d999bfc6`。

部署中发现一个独立缺陷：Android 37 上 `ACCESS_LOCAL_NETWORK` 未授予时，监听 socket 仍建立且界面错误显示“已就绪”；手机本机请求返回 HTTP 405，但电脑请求超时，系统 appops 记录访问拒绝。修复为：点击开启入口时请求本地网络权限；两个 Workbench 页面均接收授权结果；服务入口缺少权限时明确拒绝并关闭，不能宣告已就绪。遵循系统权限，不修改 appops 或网络隔离策略。系统权限对话框实际出现，后续观察到 `granted=true / USER_SET`，服务自动启动，外部请求恢复。

最终部署 APK：`/private/tmp/tripoder-http-primary-permission-20261025/tripoder.apk`，1,877,235 bytes，SHA-256 `9ea7af93e7e2714324cfc9139bb88c20a852f1413c941832f3415b470c219e74`。覆盖安装成功，直接读取已安装 APK 哈希与构建产物一致。未清除数据、未卸载主应用。详见 `deployment.json`。

已更新 stdio 引用的 `/Users/like/.tripoder/tripoder-connection.json` 为 URL-only HTTP；原配置独立备份。通过 Sentinel 原生界面更新 `tripoder` 的直连 HTTP 地址；证书字段为空，`tripoder` 与 `tripoder-stdio` 均在界面完成 Connected。

- 实际 LAN 验收：Node stdio 与独立 HTTP 客户端 9 项只读检查通过，包含普通 HTTP、拒绝不可信 Origin、共享目录及两入口能力/空间查询。见 `device-after-permission/network-result.json`。此前超时保留在 `device/network-result.json`；一次桌面 stdio 连接超时后，显式重新建立连接成功，未重放任何修改操作。
- Sentinel Rust 真机检查：显式执行 `connected_tripoder_http_without_certificate --ignored`，1 passed / 0 ignored，真实手机 HTTP initialize 与35个工具发现通过；不是主机 fixture。
- 真实模型只读会话 `bab493c8-1473-482c-a559-ef0b3aec0562`：两入口各 capabilities、status、targets，共6次调用、4次模型请求，0参数拒绝；同一运行时响应完全一致。核对持久化 assistant tool_calls、tool 回执、成功 run 与 provider usage，见 `readonly-session-verdict.json`。
- 独立真实下载会话 `5018cf6a-d66e-439d-973d-41ec8cb9a58f`：仅启用 Tripoder MCP `file_pull/file_pull_status`，1次 start +2次 status，4次模型请求，通过手机 HTTP 分块下载 Tripoder 自身 APK。模型没有使用 shell、ADB、脚本、原生下载或接收 base64；ADB 仅用于前置安装、界面/权限检查和安装哈希核对。独立只读核验器核对模型调用/回执、实际文件、manifest及全部哈希通过，见 `download-session-verdict.json`。
- 权限修复后的完整 APK 构建、真机缺权限拒绝/系统申请/授权启动流程、只读及下载验收通过；源码19项回归及两仓库 `git diff --check` 通过。

下载落盘：`/Users/like/android/tripoder-http-acceptance-20261025/tripoder.apk`，与最终已安装 APK 哈希一致。此项只证明 DOWNLOAD_ONLY；不提升历史 DEX 格式/消费者验收，也不证明 THICK 启动问题已修复。独立 THICK 复现仍需使用当前安装身份单独记录，不能用本次 HTTP 通过替代。

历史 HTTPS 验收证据保持归档，未用作 HTTP 真机证据。原 QA 构建收据保留在 `build-summary.json`，其部署描述只代表当时阶段，当前部署状态以本节和 `deployment.json` 为准。
