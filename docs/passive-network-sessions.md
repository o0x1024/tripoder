# 客体被动 TLS 采集、Native 缓冲区捕获与 HTTP/1 实时分帧

- **更新日期**：2026-10-28
- **架构归属**：第二层 — 按需扩展能力（Opt-in Network Observation），默认零开销，仅在显式调用 `network_control` 或部署 TLS Hook 脚本时激活。

---

## 1. Native TLS 有界明文捕获契约 (`ctx.native.captureBuffer`)

此能力复用 [`NativeBufferScope.java`](../android/runtime/src/dev/tripoder/runtime/script/NativeBufferScope.java) 与 [`native_buffer.c`](../android/self-hook/native/native_buffer.c) 的 `process_vm_readv` 内核安全读取通道，不新增中间人 TLS 代理，不篡改证书校验，也不使用泄露的 CA 私钥。

在已确认签名的 Native 函数（如 `SSL_read` / `SSL_write`）的 `after` 回调中调用：

```ts
const buffer = ctx.native.captureBuffer(1, 2, 1024);
```

- **参数定义**：缓冲区指针所在的参数下标、缓冲区容量所在的参数下标、最大采集字节数（上限 `1024` 字节）。
- **边界与安全保证**：
  - 面向返回有符号实际字节数（`int32`/`int64`）的函数，不将 `SSL_read_ex/SSL_write_ex` 的布尔返回值误判为字节数。
  - 原函数返回值 $\le 0$ 时返回 `NO_DATA`（不读内存）；正数大于容量时返回 `INVALID_LENGTH`；内核读取失败或部分读取时返回 `UNAVAILABLE`（不直接解引用无效用户指针，保留原始返回值与 `errno`）。
  - 作用域严格绑定当前线程和当次 Native 调用返回阶段。

---

## 2. 被动 TLS 会话采集与 MCP 调用流

所有调用均需传入准确的客体 `target`（含 `space`、`package`、`installGeneration`、`processGeneration`）：

1. **`network_query(action=inspect, moduleName)`**：检查指定客体库（或传入 `moduleName="auto"` 自动扫描已加载模块）的 `SSL_new/SSL_free/SSL_read/SSL_write` 导出及 Build ID。
2. **`network_control(action=start, moduleName, buildId, profile="openssl-ssl-read-write-v1", signatureSource="confirmed", operationId)`**：激活采集并返回 `captureId`、Hook revision、托管源码 SHA、覆盖边界和资源配额。
3. **触发客体网络请求**（通过 UI 自动化或业务交互）。
4. **`events_query(action=http, captureId, sinceSeq=0, limit=1..16)`**：实时读取连接关闭前已完成配对的 HTTP/1.1 交换记录；`events_query(action=network)` 读取已结束连接的快照；`events_query(action=har)` 导出标准 HAR 1.2 `log.entries`。
5. **`network_query(action=status)`**：查看存活连接、接入前已有连接产生的 `untrackedEvents`、配额消耗与分帧状态。
6. **`network_control(action=stop, captureId, operationId)`**：停用并排空 Hook。

### 配额与可配置上限
- 默认配额：每段最多 `1 KiB`、每方向最多 `4 KiB`、每连接最多 `64` 段、每会话最多 `16` 条连接 / `32` 条 HTTP 交换 / `64 KiB` 总字节。
- 启动采集前可通过 `network_control(action=configure, connections, messages, totalBytes, confirmHigherLimits=true)` 在安全范围内扩容（连接 `1..64`、交换 `1..256`、总字节 `4096..262144`）。

---

## 3. HTTP/1.1 持久连接实时分帧与关联规则

依据 RFC 9112 消息长度与响应关联规则，在连接关闭前实时发布已完整配对的 HTTP/1 请求与响应：

- **支持范围**：单连接顺序复用（Keep-Alive）、流水线（Pipelined）响应关联、`Content-Length`、无正文请求、`HEAD` 响应、`1xx` 临时响应（`101` 除外）、`204`/`304` 以及基本 `chunked` 分帧（含 Trailers 与多条 `Set-Cookie` 列表保留）。
- **偏移与无损字节**：请求与响应中的 `offset`、`bodyOffset`、`bodyRanges` 均为当前连接对应方向的绝对字节偏移，原始字节可从 `requestHex`/`responseHex` 无损提取；不自动解压 `Content-Encoding` 或猜测业务加密正文。
- **严格歧义拒绝**：重复 `Content-Length`、`Transfer-Encoding` 与 `Content-Length` 并存、不支持的 Transfer Coding、`101` 协议升级、`CONNECT` 隧道或分帧缺口均明确标记为不完整/不支持，绝不盲目搜索下一个疑似 HTTP 起始行。
- **原子产物持久化**：完整交换立即写入 `network.http` 产物，连接关闭或停止时写入 `network.connection` 产物（附 SHA-256），客体进程退出后仍可通过 `artifact_query` 读取。
