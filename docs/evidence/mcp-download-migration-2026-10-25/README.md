# Tripoder MCP 下载统一验收

日期：2026-10-25（Asia/Shanghai）。下载接收器统一到电脑侧 Tripoder MCP；Sentinel 原生 `mcp_file_pull`、注册、专用结果组件、打开下载 IPC 和专用路径映射已删除，没有执行别名、转发或自动传输切换。

## 代码边界

- 复用 `agent/file-pull.mjs`，手机仍通过原有 HTTPS `file_export` / `file_query` 提供不可变快照和分块。电脑侧 stdio MCP 发布 `file_pull` 与 `file_pull_status`。
- 修正任务失败回执：终结失败的查询返回 `ok=false` / MCP `isError=true`。只有 COMPLETE 表示完成；回执提供最终路径、字节数、单文件 SHA-256 和 manifest SHA-256。
- 身份不匹配时不改写原续传记录；已完成任务重启恢复时重新验证本地字节；最多两个下载，不排队。相对目标路径在派发前明确拒绝，使用 MCP 服务所在操作系统的绝对路径语义。
- 原有并发测试在无队列线程池上将 Future 完成误认为 worker 已回到等待状态，造成饱和断言竞态。饱和测试改用独立的新派发器；生产派发逻辑未修改。
- 两个工作区原有其他未提交修改保留。删除前，原本已有修改的 Rust 下载器已备份到工作区外的临时目录。

## 自动化验证

- `python3 android/workbench/test/run-mcp-tests.py`：通过生产 Java 安装清单、文件导出、443 个 schema 样例、并发超时/忙/会话撤销检查和全部 30 项 Node 测试，无跳过。覆盖 HTTPS 目录、Unicode、空文件、分块、暂停/重启续传、源/服务器身份变化、目标冲突、状态错误语义和本地并发上限。
- Sentinel：`npx vitest run src/components/Agent/ToolCallMessagePanel.test.ts src/components/Agent/toolRenderSupport.test.ts`，29 项通过。
- Sentinel：`npm run build:dev` 完成 Vite 前端构建和 Tauri Rust 调试应用构建。构建有现有大 chunk 提示及一个未使用函数警告，未阻断构建。
- 两仓库 `git diff --check` 通过。Sentinel `src` / `src-tauri` / `docs` 对原生工具类名、注册名、打开下载命令和专用下载路径类型的扫描无残留。

主机 fixture 不代表 Android Binder 或真实应用业务验收。以下单独记录实际模型、真实手机和桌面链路。

## 实际模型会话

使用新构建的 Sentinel 调试桌面及现有电脑侧 `tripoder-stdio` 连接。未安装 APK、重启手机应用或切换应用运行模式。下载源为手机上先前已完成的 DEX artifact，不涉及银行业务操作或账号数据。

- 会话：`170f8a14-f6f0-4670-8379-fae558ed9f30`，标题 `MCP file_pull 下载迁移验收`。
- 执行：`11ae80bd-f6b3-483c-8156-891a127852e0`，20:40:29–20:40:42，`succeeded`。
- 模型真实调用：1 次 `mcp__tripoder-stdio__file_pull`、7 次 `mcp__tripoder-stdio__file_pull_status`。没有 shell、ADB、原生下载工具或模型拼装 base64。
- 状态：STARTED → DOWNLOADING → COMPLETE。工具结果最大 990 字符；完整二进制没有经过模型上下文。
- 文件：`/Users/like/android/cmb_analysis/mcp-download-acceptance-20261025/cmb_dynamic_10.dex`。
- 大小：6,552,947 字节。独立读取本地文件计算 SHA-256，与事先查询的手机产物回执、下载结果及清单均一致：`a51f022b04b0101e99ebaaf7fb95f2179641278927a44bbadc30ae402802ec40`。
- 使用 Sentinel 原有文件预览打开同目录的 manifest。将本会话工作目录显式设为 `/Users/like/android/cmb_analysis` 后，预览显示真实 JSON 中的来源、大小和哈希；未绕过工作区映射限制。
- 已持久保存本验收会话的两项 MCP 下载工具选择。未重写旧会话或为原生工具注册兼容名称。

只读证据验证器 `agent/verify-download-session.py` 将同一 tool call 的模型持久记录、执行回执、harness 完成状态、真实模型用量、本地文件和 manifest 相互核对。输出见 [session-verdict.json](session-verdict.json)。证据不保存聊天内容、连接配置、凭证或二进制。

```sh
python3 agent/verify-download-session.py \
  --database '/Users/like/Library/Application Support/sentinel-ai/database.db' \
  --server-name tripoder-stdio \
  --operation-id mcp-download-acceptance-20261025-a \
  --expected-size 6552947 \
  --expected-sha256 a51f022b04b0101e99ebaaf7fb95f2179641278927a44bbadc30ae402802ec40 \
  --output docs/evidence/mcp-download-migration-2026-10-25/session-verdict.json
```

本次通过的是下载、校验、MCP 调用及桌面展示验收，不声称 DEX 方法重建、反编译完整性或银行应用业务验收通过。手机直连 HTTP 不提供电脑本地落盘；需要下载时显式配置电脑侧 MCP 服务。
