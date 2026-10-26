# Native TLS 有界明文观测

日期：2026-10-25。此能力复用现有 native Hook 和事件/证据通道；不新增 TLS 代理，不修改证书校验，不使用泄露的 CA 私钥。

## 接口契约

在已确认签名的 native 函数 `after` 回调中调用：

```ts
const buffer = ctx.native.captureBuffer(1, 2, 1024);
```

三个参数分别是缓冲区指针所在的参数下标、缓冲区容量所在的参数下标、最大采集字节数。

- 当前接口面向 `SSL_read/SSL_write` 一类“返回有符号实际字节数”的函数。指针槽使用既有 AArch64 `uint64` 机器字 ABI；容量支持整数 ABI，结果支持 `int32/int64`。不把 `SSL_read_ex/SSL_write_ex` 的布尔结果误当字节数。
- 只能在原函数返回后调用，每次调用最多采集一次，最多 1024 字节。读取地址来自本次原函数实际收到的参数；脚本无法给此接口传入任意地址或偏移。
- 原函数返回值小于等于零时返回 `NO_DATA`，不读内存；正数大于容量时返回 `INVALID_LENGTH`。
- 读取成功返回 `COMPLETE`、`hex`、`capturedBytes`、原始 `capacity/result` 十进制字符串以及 `truncated`。`COMPLETE` 只表示这一段快照完成；`truncated=true` 仍意味着数据不完整。
- 内核读取失败或仅部分读取时返回 `UNAVAILABLE`，不暴露部分内容，不直接解引用指针，也不以信号恢复或其他读取方式自动重试。
- 复用 `process_vm_readv` 的现有内核入口，保留 `errno`。作用域绑定当前线程和 native 调用；跨线程、调用结束后、Java 回调、原函数执行前均不允许采集。

例如：

```ts
ctx.native.function('libownednativetls.so', 'SSL_read', {
  argumentTypes: ['uint64', 'uint64', 'int32'],
  returnType: 'int32',
  buildId: symbols.read.buildId,
}).after(({args, result}) => {
  const data = {
    connection: String(args[0]),
    direction: 'read',
    buffer: ctx.native.captureBuffer(1, 2, 1024),
  };
  ctx.debug.emitEvent('tls.bytes', data);
  return result;
});
```

`SSL*` 标识必须结合进程代次和 `SSL_new/SSL_free` 生命周期使用；指针可复用，不能作为跨生命周期的永久连接 ID。同一连接的数据顺序还要求其读写调用有正确的序列化约束。返回正数的写入只证明 TLS 接口接受了字节，不证明服务端业务已处理。

## 实现位置

- `NativeBufferScope.java`：作用域、ABI、实际长度、次数与上限校验。
- `NativeHook.Invocation.snapshotArgument`：只访问原调用的真实参数，拒绝未完成和过期调用。
- `native_buffer.c`：不会因无效用户指针而直接崩溃的有界内核读取。
- `RuntimeNativeHookBackend`：在既有调用/原调用令牌路径上建立作用域，不引入第二个 Hook 后端。
- `DraftCompiler` / `src/plan.ts`：限制解释器和 TypeScript SDK 的同名能力；没有 provider 的模拟环境明确拒绝读取。

## 自有真机用例

`android/compatibility-test/native-tls-fixture/` 将测试专用 OpenSSL 静态链接到一个客体自有库，导出真实 `SSL_read/SSL_write/SSL_new/SSL_free`。由客体 UI 按钮驱动两个同时存活的 native TLS 客户端，连接同设备的 Java mTLS 服务。服务器核对客户端身份和请求原文，客户端校验服务器证书与主机名。采集器与应用收发路径分离，原应用不调用 Tripoder 事件 API。

测试覆盖：

1. 原始基线、开启观测、停用观测三个阶段。
2. 两条并发 TLS 1.3 连接、47 字节请求分段、37 字节响应分段和最大 61 字节读取。
3. 握手后主动做一次非阻塞读，必须返回 WANT_READ；随后正常读取，确认失败读取不捕获未初始化字节。
4. 独立 native 内存探针：有效短读、上限截断、结果超过容量、无效地址、零结果、负结果、跨不可读页的部分内核读取，并检查原返回值和 errno 保持不变。
5. 依据 TLS 对象生命周期重组请求与响应，和应用及服务端的原文逐字节比较；保存每段证据并检查停止客体后仍可读取。

最终 [真机报告](evidence/native-tls-observation-2026-10-25/device-04/report.json) 54 项检查通过：45 次实际 native 命中、0 个 Hook 错误、0 条事件丢失、34 份字节证据。全部 34 份证据在客体停止后重新读取、验证 SHA-256，并与对应事件内容逐项比较。TypeScript 测试 45 项通过，Java 脚本契约检查 945 项通过。这里的检查数量包含逐产物完整性检查，不代表 54 种不同协议场景。

QA 宿主 APK SHA-256：`fb5e09e49d8cdaa4468baf634a0b27dd1c6f3d91abe802d35af4fbb150452f45`。本轮未更新现有主安装，能力部署在独立 `dev.tripoder.nativetlsqa`；最终测试空间为 `native-mtls-0478d05c-e4b`。

保留的失败：`device-01` 在创建空间前因测试名称超过契约长度被拒绝；`device-02` 启动了客体进程，但 Android 因 QA 工作台不在前台阻止 Activity 展示，未执行抓包，随后停止该测试客体。恢复 QA 工作台前台后，`device-03` 的 18 项初验通过；`device-04` 增加无丢失断言和全部产物核对后通过。首次 QA 网络引导还因端口冲突失败，随后通过明确的 QA 端口配置解决。

证据按 summary / calls / events 分文件保存，每个文件小于 2000 行。`device-04/runner.mjs` 是实际执行时的脚本快照，其摘要与报告一致；当前验收脚本仅追加了相同的分文件输出格式。构建完成后，工作区的构建脚本另有并发 `--build-dir` 修改；本轮保留该修改，最终摘要核对中明确记录它，未把该构建脚本版本称为已重新构建验收。运行时 Java、C 和 SDK 输入与本轮宿主构建摘要一致。

验收脚本：`scripts/qa/native-tls-observation-acceptance.mjs`。它调用 MCP 的 Hook、UI 和读取工具；不调用 `http_send_request`。ADB 仅用于独立 QA 宿主的安装、启动和连接配置准备，验收脚本本身不使用 ADB。

## 构建与复现

测试依赖 OpenSSL 3.5.0 固定归档，SHA-256：`344d0a79f1a9b08029b0744e2cc401a43f9c90acd1044d09a530b4885a8e9fc0`。这是固定的本机 loopback 验收依赖，不是建议产品采用该旧版本。第三方源码和生成的测试密钥均位于 Git 忽略的构建目录，未加入生产宿主依赖。

从 OpenSSL 官方下载归档并核对其官方 SHA-256 后，在解压目录执行：

```sh
export ANDROID_NDK_ROOT=/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
./Configure android-arm64 -D__ANDROID_API__=36 no-shared no-tests no-apps no-module no-dso
make -j8 build_libs
```

使用 `scripts/qa/build_native_tls_fixture.py --openssl <source-build-dir> --evidence <new-directory>` 构建客体。测试证书有效期两天，过期需重新构建。

宿主按现有 `build-workbench.py` 流程构建，额外传 `--package dev.tripoder.nativetlsqa --mcp-port 48766`。`--mcp-port` 仅允许独立 QA 包；端口冲突明确失败，不随机寻找替代端口。生产包默认端口不变。

```sh
rtk proxy python3 scripts/qa/bootstrap_native_tls.py
# 在手机上保持独立 QA 工作台位于前台，满足 Android 的 Activity 启动前置条件。
rtk proxy node scripts/qa/native-tls-observation-acceptance.mjs \
  --connection /Users/like/code/tripoder/.tripoder-local/native-tls/connection.json \
  --evidence docs/evidence/native-tls-new/device
```

## 覆盖边界

本阶段提供原生读写字节采集原语与指定 OpenSSL 库的用例。未宣称已自动识别所有第三方 TLS 库，也未覆盖隐藏/内联符号、直接 QUIC 数据路径、HTTP/2 解码、业务二次加密、大正文无损落盘或 TLS 对象并发无序调用。观察成功不代表整个应用全部流量已覆盖。

下一阶段应将 TLS 实现识别、生命周期编号、分段顺序和丢失/截断状态纳入正式采集会话，再提供协议解析。发现 `UNAVAILABLE`、截断或事件丢失时必须保留不完整状态，不能输出“完整请求”。
