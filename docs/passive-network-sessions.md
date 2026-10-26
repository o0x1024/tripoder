# 客体被动 TLS 采集会话

本阶段把 native 缓冲区观测接入正式的 MCP 会话，复用现有 `observe → prepare → activate → disable`、进程身份、Hook 所有权和操作回执。不会替换应用的 TLS 连接或客户端认证。

## Agent 调用顺序

所有调用均传准确的客体 `target`（包括安装代次和进程代次）。

1. `network_query(action=inspect, moduleName)`：检查指定客体库的 `SSL_new/SSL_free/SSL_read/SSL_write` 导出及 Build ID。匹配只代表候选；Agent 必须根据源码、符号或独立 ABI 证据确认签名。
2. `network_control(action=start, moduleName, buildId, profile="openssl-ssl-read-write-v1", signatureSource="confirmed", operationId)`：返回 `captureId`、Hook revision、托管源码 SHA、覆盖边界和资源上限。
3. 操作客体 UI 产生应用请求。
4. `events_query(action=http, captureId, sinceSeq=0, limit=1..16)` 实时读取完整 HTTP/1 交换；`action=network` 读取已结束连接。两个流各自使用 `nextCursor`，直到 `hasMore=false`。
5. `network_query(action=status)`：查看仍存活的连接、接入前已有连接产生的未跟踪事件、预算和错误。活动连接不会提前占用已结束连接的分页游标。
6. `network_control(action=stop, captureId, operationId)`：停用并排空 Hook；若返回 `DRAINING`，查询状态直到 `captureState=STOPPED`。原操作回执为 UNKNOWN 时，按既有 `operation_get` / Hook 状态路径核实，不能重新派发。

`events_query(action=network)` 现在只返回 `origin=GUEST_PASSIVE_TLS` 的被动采集，必须传本次 `captureId`，不返回 Agent 主动请求的旧记录。

## 连接、分段和完整性

- 每个采集 ID 下单独编号连接。原生 `SSL*` 只在当前生命周期内用作关联键，不向结果暴露地址；地址复用会分配新编号。
- 采集开始前已经存在的连接不能凭空补出创建证据，其事件计入 `untrackedEvents`，全局覆盖为 `INCOMPLETE`。
- 同一方向调用的 `before`/`after` 配对用于发现并发重叠；发现重叠时，记录 `CONCURRENT_SAME_DIRECTION`，不把完成顺序当作可靠字节顺序。
- `segments` 提供方向、原始流偏移、实际返回字节数、保留字节数和状态。截断或读取失败后，仍保留缺口位置。
- 每段最多 1 KiB、每方向最多 4 KiB、每连接最多 64 段、每会话最多 16 条连接/64 KiB。触及上限时保留明确的不完整原因，不自动切换采集方式。
- 停止时尚未关闭的连接标记 `CAPTURE_STOPPED_BEFORE_CLOSE`。默认查询只保留当前或最后一场会话；启动新会话后，旧会话的持久化产物仍可通过 artifact 查询。

`byteState=COMPLETE` 仅表示这个已观测连接生命周期的字节没有已知缺口。它不代表整个应用、所有进程或所有网络库均被覆盖。会话的最佳覆盖标记为 `OBSERVED_LIFETIMES_ONLY`，不会返回“全应用完整覆盖”。

## HTTP 解析与证据

当前支持持久连接中的多消息、Content-Length、基本 chunked、HEAD 与临时响应；完整交换可在连接关闭前查询。解析结果包含起始行、头值列表、正文偏移和范围；原始字节均能从 `requestHex/responseHex` 无损读取。详见 [实时分帧说明](http1-keepalive.md)。

头或正文不足、歧义长度、不支持的 Transfer Coding、HTTP/2 或无法关联的额外字节均不会被标成完整 HTTP 交换。原始字节仍保留，协议状态明确显示不完整或不支持。业务加密和压缩正文也不会被假装解码为文本。

完整交换立即保存为 `network.http`；连接关闭或明确停止时，复用 artifact 目录 FD 和原子写入，把字节及分段元数据持久化为 `network.connection`。每条记录附带 artifact ID、SHA-256、安装/进程代次、源码 SHA 和 Hook revision。产物写入失败会显示 `artifact.state=FAILED`，并影响会话覆盖状态；不会把内存中的记录冒充已保存证据。突然终止的未关闭连接没有持久化完成保证。

## 复现与验证入口

- 第一阶段 [真机报告](evidence/passive-network-sessions-2026-10-25/device-03/report.json) 19 项检查通过，准确查询两条并发 mTLS 连接，核对基线/观察/停用后行为，并在客体停止后读出两份持久化证据、校验 SHA-256。期间没有调用 `http_send_request`。
- 主机验证：TypeScript 45 项测试、Java 脚本契约 980 项检查、生产 Java MCP/schema 与传输相关的 40 项 Node 测试通过。检查数量包含边界与契约检查，不代表同等数量的第三方应用或协议覆盖。
- 本轮安装 `dev.tripoder.networksessionqa`，宿主 APK SHA-256 为 `07988c6f92817822ae75151df6a7fc33267690ffce316b08c4dba24e280a2e1f`；验收后 QA 宿主已停止。本任务没有更新主安装。
- 构建使用被冻结的源码输入，采集相关源码与当前工作区一致；其他任务对框架/包管理等文件的后续修改保留在工作区，未把它们称为本轮已验收。差异列表见 [摘要核对](evidence/passive-network-sessions-2026-10-25/verification.json)。
- `scripts/qa/network-session-acceptance.mjs`：自有应用真机流程，基线/采集中/停用后均核对真实 mTLS 数据；验证分页、独占 Hook、错误 capture ID、重启分配新会话以及停进程后读取证据。
- `NetworkCaptureTest`：指针复用、中途接入、截断、不可读、重叠、配额、停止和严格 HTTP 分帧。
- `NetworkControlTest`：真实受限源码编译器、现有 Hook 会话、实际长度快照与统一查询串联。

工作区有其他并发修改时，`scripts/qa/snapshot_network_session.py` 冻结明确输入，随后从快照构建独立 QA 包。不要跳过构建摘要检查来安装与源码不一致的产物。

本轮保留的失败：独立长路径构建曾超出 D8 参数长度上限，现改用一个 class JAR 传入同样的字节；连续工作区变更触发摘要拒绝后改为明确的源码快照。手机首次拒绝安装，用户允许后成功安装。引导 instrumentation 结束使 supervisor 中断，已通过 QA 自身“重新连接”按钮明确恢复。`device-01` 保留入口未就绪的失败；`device-02` 已读到正确流量，但测试错误地期望未知 capture ID 返回写操作式 REJECTED，公共协议实际返回只读 UNAVAILABLE；修正测试断言、停掉该失败用例的客体后，`device-03` 全部通过。

引导准备和 supervisor 恢复属于 ADB/QA UI 设置步骤；采集验收脚本只走 MCP。启动验收前应保持 QA 工作台在前台，满足 Android 的 Activity 启动前置条件。

## 当前边界与下一步

目前支持一个经独立确认的 OpenSSL 风格 ABI profile 和一个客体进程；不自动猜测隐藏符号、SSL_read_ex 的输出长度、Conscrypt、QUIC、WebView 或其他自研协议。采集占用既有单个 Hook 上下文，冲突明确拒绝。

HTTP/1 持久连接的逐消息编号/分帧已补齐，后续应为目标应用的真实网络库提供经验证的 profile；每种 profile 都要单独验收认证行为、分段、并发和完整性。HTTP/2 和 QUIC 需要独立协议实现。
