# 自有源码脚本 → ManagedHook 集成夹具

该目录只验证四个自有 static 方法及四个已知直接 caller。没有接入 Runtime 产品 backend、Broker 审批链、第三方目标或任意调用者覆盖；报告固定 `productBackend=false`、`fullCallerCoverage=false`、`authorityApprovalPath=false`。不使用 Frida，也没有错误后切换原后端的路径。

## 构建接入

清单见 [integration.json](integration.json)。新增 APK Java 源码根：

1. `android/self-hook-probe/script-fixtures/dev`：六个 Java 文件。
2. `android/runtime/src/dev/tripoder/runtime/script`：已完成的独立源码编译/执行/控制核心。

继续使用现有 self-hook 核心、Native 库及 ProbeEnvironment/ProbeFiles/ArtProbe/ArtCompilation。**不要把本目录 `host/` 或 `build/`、runtime/script-tests 打入 APK。** 源码/构建来源回执需要包含上述新增生产源码根，否则新 Activity 不能继承旧构建证明。

Manifest 新入口：

```xml
<activity android:name=".ScriptControlProbeActivity" android:exported="true" />
```

入口完整名称 `dev.tripoder.selfhookprobe.ScriptControlProbeActivity`。必须传入新 UUID `runId`、明确 `artCompilation=jit|aot`、`ownedScriptScope=true`。遗漏 scope 标志会在加载 Native 库前写入失败结果 `OWNED_SCRIPT_SCOPE_REQUIRED`。包名必须是 `dev.tripoder.selfhookprobe`，平台、APK/Native SHA 等继续经过 ProbeEnvironment 原门禁。

设备启动、安装和 AOT 编译只由主 agent 调度；本目录脚本不使用 ADB。

## 实际安装边界

`ScriptFixturePlatform` 仅接受 `ScriptOwnedFixture` 中四个实际 Method。它读取当前 APK 的单个 classes.dex，用现有 DexCallerClosure 校验每个目标的候选集合恰好等于一个 `ScriptOwnedCaller` 方法，并核对 target/caller 都与 ManagedHook 使用同一实际 loader。任何集合变化或其他目标均拒绝；不能把全 APK 的 dynamic/external 调用点计数当成已经覆盖，它们会如实写入结果。

`prepare(source,evidenceId)` 只编译源代码；直到 activate 的 backend.publish 才创建 ManagedHook handles，并在全部 helper/根准备后逐一 enable。四个 handle 在该进程内重复使用并保留到进程结束。已失去发布所有权或部分发布失败的 backend 只终止当前自有探针进程，不生成成功/确定失败的 ACK。

每次真实回调通过 ScriptFixtureCodec 显式转换 Integer/Long/Float 与 Double/BigInteger。`ManagedHook.Invocation.original` 执行实际原函数，原 Throwable 对象保持；float 在真实 ABI 编码处舍入，handler 的参数快照保留原脚本值。此夹具只接收受控有限浮点值，不扩展到任意对象、Native 模块或非有限原函数结果。

## 设备检查内容

- 安装前实际采样四个 target 与四个 caller 的 quick 入口，必须分别看到请求的 JIT 或 AOT 映射；不能以循环次数代替编译证据。
- 源码 → prepare → 错误 SHA 拒绝 → activate → 重复 activate 拒绝；prepare 时没有任何 ManagedHook handle/发布，目标仍执行物理原方法。
- 100 轮 int before/original/after、2^53 以上 long 精度、float 16777217 → 16777216 舍入与 after 参数快照、原异常目标正常调用。
- 原 Throwable 身份一致且不计脚本错误；4 线程 400 次调用，物理原方法次数精确。
- 在原方法闩锁中更新参数，旧调用使用旧快照；disable 返回 DRAINING，后续调用只执行原函数，旧调用返回后才真正退休。
- replace 验证 Long.MAX_VALUE 和 float 负零，原方法次数不变；before 错误原方法 0 次，after 错误原方法 1 次，无重试。
- 四轮发布均退休，四个 Native `originals` 计数应为 integer=504、wide=100、single=100、failure=101；相同阶段物理方法体增量为 integer=505、wide=100、single=100、failure=101（integer 多一次已停用后的直接调用）。

## 结果 schema

每个新 run 目录保存 `result.json`：顶层 `schema=1`、`phase=H5-owned-script-control`、runId、编译模式、原 ProbeEnvironment 构建/平台/文件身份、passed；失败包含 error/cause，不能计入通过。

`script` 包含 checks、scope、上述三个 false 边界字段、compilerVersion、sourceFiles（文件/SHA）、完整请求响应 commands、八个真实入口的 compilation、callerEvidence（DEX SHA、四条精确 target/caller、实际 loader 绑定、全图风险计数）、四个 native 报告、publicationEvents、physicalBodyDeltas 及检查标志。源码文件四份、helper DEX 四份、relay 二进制四份、编译快照四份，以及 maps/smaps 和 storage 报告，均保存在同一 run 目录。主 verifier 应独立核对源码 SHA、helper/relay SHA、最终 active=false、计数和作用域字段。

## 已执行的主机验证

```sh
rtk proxy python3 android/self-hook-probe/script-fixtures/check-host.py
```

只写本目录 build：API37 jar 增量编译通过；按现有 probe manifest 的 minSdk=36，用独立 D8 生成自有 Fixture/Caller DEX；14 项 codec、实际自有 Java 方法体、源码规则调用与 DEX caller 集合检查通过。报告 `build/host-report.json` 绑定新增源码、脚本核心、Android jar 和所读既有依赖 class 的 SHA，明确 `deviceTested=false`、`managedHookTested=false`。**尚未执行该 Activity 的真机验证。**
