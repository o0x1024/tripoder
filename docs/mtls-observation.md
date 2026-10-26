# 客体自身 mTLS 请求观测：最小闭环

日期：2026-10-25（Asia/Shanghai）。范围：自有应用、自研 HTTP/1.1 封装、标准 TLS 1.3、THICK 模式真机。

## 已实现

客体界面点击 → `OwnedHttp.exchange(String)` → `SSLSocket` → 同设备 loopback mTLS 服务。服务要求客户端证书，客户端使用独立 CA 信任库并开启 HTTPS 主机名校验。证书和私钥在每次构建时随机生成，仅用于自有测试，有效期两天；没有使用泄露的 CA 密钥或关闭认证。

观察脚本通过现有 `hook_observe → hook_prepare → hook_activate` 安装到指定方法：

- `before` 记录 `mtls.attempt`，原样返回参数。它表示调用尝试，不能证明请求已上网。
- `after` 在原方法成功返回后记录 `mtls.exchange`，包含成对请求和响应，原样返回结果。
- `captureEvidence` 将成功交换持久化，绑定进程、安装代次和脚本 SHA。
- MCP 使用 `events_query(action=hook)` 读取事件，使用 `artifact_query` 读取持久化证据。

应用本身没有调用 Tripoder 记录 API。MCP 只点击客体按钮、读取结果与控制观察脚本；`guest_invoke` 只调用无网络副作用的 `Main.summary()`。验收脚本禁止调用 `http_send_request`。

## 验收结果

[真机报告](evidence/mtls-observation-2026-10-25/device-01/report.json) 的 16 项检查通过：

| 场景 | 原始基线 | 观察开启 | 观察停用 |
| --- | --- | --- | --- |
| TLS 1.3、客户端认证、请求响应字节匹配 | 通过 | 通过 | 通过 |
| 缺少客户端证书 | 拒绝 | 拒绝 | 拒绝 |
| 客户端不信任服务器 CA | 拒绝 | 拒绝 | 拒绝 |

开启期间有 3 次真实方法命中、0 个 Hook 错误、3 条调用尝试和 1 条成功交换。停用后无新增观察事件；停止客体后仍能读取证据并验证 SHA-256。主机 JVM 使用相同核心代码完成真实 TLS 测试，结果见 [主机报告](evidence/mtls-observation-2026-10-25/build-01/host-result.json)。未使用 ADB，也没有重建或覆盖手机 Tripoder 宿主。

测试空间 `mtls-2422829b-adf` 保留自有安装 `dev.tripoder.mtlsfixture` 供检查，客体已停止，Hook 已停用；未清理用户其他空间或应用。

## 复现

构建依赖现有 Android SDK/JDK 和开发签名配置，路径沿用项目的 APK builder。证据目录必须是新目录。

```sh
rtk proxy python3 scripts/qa/build_mtls_fixture.py --evidence docs/evidence/mtls-observation-new/build
rtk proxy node scripts/qa/mtls-observation-acceptance.mjs --evidence docs/evidence/mtls-observation-new/device
```

连接配置默认读取 `~/.tripoder/tripoder-connection.json`，可通过 `--connection` 指定；APK 可通过 `--fixture` 指定。测试 TLS 证书过期后需重新构建。生成的测试私钥只进入被 Git 忽略的构建 APK；证据目录不复制 APK 或私钥。

源码：

- `android/compatibility-test/mtls-fixture/`：Android UI、独立 HTTP 封装、TLS 材料加载、正反向握手测试和 JVM 入口。
- `scripts/qa/build_mtls_fixture.py`：生成临时测试 CA、主机验证、APK 构建与输入摘要。
- `scripts/qa/mtls-observation-acceptance.mjs`：MCP 安装、UI 驱动、原始/观察/停用对比、事件与持久化证据核对。

## 边界与下一步

这验证了 **已知自研请求库的语义入口可在 mTLS 保持有效时被动观测**。没有新增通用网络采集器，也没有改变 `events_query(action=network)`；后者仍属于既有主动请求记录链路。完整请求响应仅针对本测试的短文本 HTTP/1.1 数据成立。

没有验证任意第三方应用、TLS 原生缓冲区、隐藏符号、静态链接 TLS、HTTP/2、QUIC、WebView、多进程、流式大正文或业务加密。当前 native selector ABI 仅公开数值参数，不能据此声称已有通用 `SSL_read/SSL_write` 缓冲区捕获能力。

下一阶段应先定义有界 native 内存读取与真实字节数契约、生命周期和失败状态，再对自有 native TLS 测试库实现采集；必须分别验收分段读写、部分写入、失败/重试、并发连接、关联和丢失/截断。未支持的实现明确返回不支持，不自动替换网络栈或降低 TLS 校验。
