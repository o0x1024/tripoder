# HTTP/1 持久连接与实时消息查询

日期：2026-10-25。复用既有 TLS 采集会话和 artifact 通道，在连接关闭前发布已经完整配对的 HTTP/1 请求响应。

## 使用

启动采集后调用：

```json
{"action":"http","target":{"space":"...","package":"...","installGeneration":"...","processGeneration":"..."},"captureId":"...","sinceSeq":0,"limit":2}
```

工具为 `events_query`。结果 `origin=GUEST_PASSIVE_HTTP1`，`exchanges` 是完整的交换记录。复制 `nextCursor` 继续查询，直到 `hasMore=false`。HTTP 消息游标与 `action=network` 的连接游标分别计数，不能混用；错误 capture ID 也不会自动选择其他会话。

每条交换具有 `captureId/connectionId/messageId/messageIndex`、请求与最终响应、临时响应列表、逐消息原始 `requestHex/responseHex` 和证据引用。请求与响应中的 `offset`、`bodyOffset` 和 `bodyRanges` 都是当前连接对应方向的绝对字节偏移；从逐消息 hex 提取正文范围时，需减去该消息的 `offset`。

`action=network` 继续查询关闭或明确停止后形成的连接快照。它包含整条连接保留的原始字节、分段元数据和 `http` 汇总。`network_query(action=status)` 中的 `messages/liveHttp` 显示实时完成数量、等待响应的请求数和分帧问题。

## 分帧和关联

- 支持一条连接上的顺序复用，以及多条请求已经发出后的流水线响应关联。
- 支持 Content-Length、无正文请求、HEAD 响应、1xx 临时响应（101 除外）、204/304 和基本 chunked 分帧。重复的普通头按值列表保留，包括多个 Set-Cookie。
- chunked 的 `bodyRanges` 指向每段净荷，`bodyBytes` 为净荷总长度；原始 chunk 大小行、分隔符和 trailers 仍保留在 hex 中。不会解压 Content-Encoding 或解密业务正文。
- 不完整头、正文或 chunk 会等待更多字节；连接关闭时仍不足则保留 INCOMPLETE。
- 临时响应属于当前等待最终响应的请求，不会占用下一条请求。底层读回调先于前一次写回调被处理时，会等待相应请求元数据到达。

这些边界依据 [RFC 9112 的消息长度规则](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3) 和 [响应关联规则](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.2)。实现采用明确的有限支持范围：重复 Content-Length、TE 与 CL 同时出现、不支持的 Transfer Coding、chunk extensions、101 升级、CONNECT 隧道、无长度且靠连接结束分帧的响应均返回明确原因。发生歧义或采集缺口后不搜索下一个看似有效的 HTTP 起始行。

## 完整性与资源

每条连接最多识别 16 条请求、每个请求最多 8 条临时响应、每个会话最多 32 条交换。沿用每方向 4 KiB、每会话 64 KiB、每段 1 KiB 和每连接 64 段的采集上限。

HTTP 交换一旦完整即写入 `network.http` 产物，可在连接仍存活时读取；连接快照仍写为 `network.connection`。产物失败、截断、并发顺序不明等状态会明确保留。已经完成的前缀消息不代表后续字节或整个应用都已被覆盖。

## 验证入口

- [最终真机记录](evidence/http1-keepalive-2026-10-25/device-02/report.json) 全部 29 项检查通过，覆盖原始基线、开启观察、停用后再次操作和重启分配新采集 ID。两条 native mTLS 连接保持打开时，MCP 分三页取回六条独立、准确关联的交换；103、HEAD、chunked/trailers 和重复 Set-Cookie 均与自有客户端、服务端的原文一致。停止客体后，全部八份产物重新读取并核验 SHA-256。
- [最终补充核对](evidence/http1-keepalive-2026-10-25/device-02/post-run-audit.json) 确认该进程只有六份 `network.http` 和两份 `network.connection` 产物，停用后的请求没有产生额外采集证据；原失败操作仍为 UNKNOWN。[验收摘要](evidence/http1-keepalive-2026-10-25/verification.json) 记录源码、宿主摘要和最终状态。
- [早先未完成记录](evidence/http1-keepalive-2026-10-25/device-01/report.json) 保留原样：前 24 项核心与停止检查通过；之后设备前台被其他操作切换，停用后的 UI 动作返回 UNKNOWN。该回执没有重放或改成成功，后续用了独立的明确停止操作，并保存 [停止后证据核对](evidence/http1-keepalive-2026-10-25/device-01/post-stop-evidence-audit.json)。最终通过来自用户确认手机空闲后的新独立试验。
- TypeScript 45 项、Java 脚本契约 1096 项和 MCP/schema 相关的 40 项 Node 测试通过。独立 QA 宿主 SHA-256 为 `6bc665ac9bc2a91d323ba3059bdca9785879a0aa1acbd03434f07cae81fd141b`；本任务没有更新主安装。
- `NetworkHttp1Test`：逐字节拆分、chunked/trailers、HEAD、103、响应先于写回调、流水线、歧义长度、升级/隧道、正文不足及消息预算。
- 自有 native mTLS 用例：两条同时存活连接，一条顺序复用，一条真实流水线，每条三次交换。客户端和服务端独立核对所有原始字节。
- `scripts/qa/http1-keepalive-acceptance.mjs`：通过客体 UI 发起流量；将连接保持打开，查询六条消息，随后释放并核对两条连接及全部八份产物。不会调用 `http_send_request`。

设备前台必须由 QA 流程独占。验收在每次 UI 动作前查询客体树，发现目标不可见就停止；操作出现 UNKNOWN 时保留原回执，不能换 operationId 重放。可以使用新的明确停止操作建立停止证据，然后进行新的独立试验。

## 边界与后续

当前仍只适用于已确认的 OpenSSL 风格 ABI profile。HTTP/2、QUIC、WebView/Conscrypt 的其他路径和更多第三方库需要独立 profile 与验收。下一步应选定一个目标应用，先确认它实际使用的网络栈与协议，再扩展覆盖范围。
