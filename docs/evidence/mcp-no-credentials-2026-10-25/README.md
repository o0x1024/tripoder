# MCP 无凭证连接：2026-10-25

已按手机现有包名 `dev.tripoder.cmbqa` 构建并覆盖安装，未卸载或清除数据。最终 APK SHA-256：`943afdab57b7084227a02f69f964a4e947662629c28ffb73f7620953dfe1f36a`。

配置 `/Users/like/.tripoder/tripoder-connection.json` 只含 `url` 和公开 TLS 证书，地址为 `https://192.168.31.71:29731/mcp`。Sentinel 保存的 stdio 参数已补齐 `--connection`，HTTP 地址/证书已更新并移除 Authorization。导入配置 `/Users/like/.tripoder/tripoder-mcp-v3.json` 同步更新。

最终 [只读真机验证](final-readonly/network-result.json) 9 项通过，ADB server 在调用期间关闭；覆盖无凭证入口、Origin 拒绝、证书信任、两种入口工具列表一致，以及各自读取能力和空间列表。能力返回 authenticationRequired=false。安装 APK 摘要与构建回执一致。

保留失败：首次最终安装后的 bootstrap 返回 SUPERVISOR_INTERRUPTED；临时显式恢复检查曾报告 READY，但 instrumentation 结束后再次中断，第一次网络验证为 ECONNREFUSED。临时测试包已卸载。随后通过工作台现有“重新连接”恢复，引擎和网络入口实际就绪，完成上述最终验证；未修改恢复策略、清理数据或重放业务命令。

尚未验证 Sentinel 桌面实际重新连接；已更新持久化配置，需在 MCP 管理界面断开后重新连接。未执行客体启动、登录、Hook 或业务操作验收。
