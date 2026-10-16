# Tripoder self-hook 研究实现

这是独立编写的 AArch64 / ART Hook 研究实现。H1、H2a 自有 ELF、H3b Native Field.get、H3c 两个自有 managed 方法与已知 caller 反优化已分项通过。通用能力尚未完成，不能作为成品 API。产品当前仍使用 Frida。

- `include/tripoder_a64.h`：规划契约与明确错误码。
- `native/a64_relocate.c`：白名单指令搬迁、地址计算及拒绝检查。
- `native/a64_entry.c`：单指令入口替换计划；可保留 BTI c/jc。
- `native/owned_hook.c` / `code_page.c`：固定 ABI 协作域、匿名执行页同址发布、原文件映射恢复与回收。
- `native/thread_park.c`：固定信号/epoch、线程集合闭合、超时取消与恢复。
- `native/page_commit.c`：复用页所有权的最小提交关键区，仅直接系统调用和 cache maintenance。
- `tests/check_park_binary.py`：构建时检查暂停/提交关键区的实际 ELF 调用集合。
- `native/art_access.c` 与固定平台契约头：实际 libart 字节/映射绑定，ART 锁、JIT 排空、方法快照与选择性反优化访问。
- `native/art_method_storage.c` / `OriginalMethodStorage`：声明方法表之外的 ART 分配与 ClassExt Native 根；替代会损坏持久化映像的旧声明槽备份。
- `native/art_int_entry.c` / `OwnedIntHook`：自有 static int(int) 的独立 Native helper 入口，保持目标 managed 元数据与在途帧类型。
- `java/dev/tripoder/selfhook/dex`：DEX 039 精确静态依赖索引，尚不覆盖完整 Runtime 分派。
- `tests/core_checks.c`：主机与 ARM64 设备共享检查。
- `tests/check_host.py`：ASan/UBSan 检查及 LLVM 汇编器独立编码对照。
- `../self-hook-probe`：固定设备、non-debuggable 独立 APK。

详细边界见 [方案](../../docs/self-hook-design.md)，进度见 [task.md](../../task.md)。规划器接收调用方给出的指令值和地址，不读取目标映射，不分配跳板，不发布入口变更。失败时输出保持原值；未知编码不盲拷，不自动改用其他方案。

## 构建与主机验证

当前工具链为 NDK r30、Android SDK platform `android-37.0`、build-tools `37.0.0`。以下路径对应本仓库开发机；参数不存在时构建明确失败。

```sh
rtk proxy python3 android/self-hook/tests/check_host.py \
  --toolchain android/vm-probe/build/native/tools/android-ndk-r30/toolchains/llvm/prebuilt/darwin-x86_64/bin

rtk proxy python3 android/scripts/build-self-hook-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --key android/runtime-probe/build/acceptance-only.p12 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so

rtk proxy python3 android/self-hook/tests/check_dex_graph.py \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --apk android/self-hook-probe/build/self-hook-probe.apk
```

开发签名密钥只是本地测试输入。APK 不读取工作台/引擎数据，不带网络权限，也不加载客体 APK。构建只编译显式清单中的自研 C/Java 源码；产物 Native 依赖允许集是 `libc.so/libm.so/libdl.so`。通用 APK 构建原语来自现有 `runtime_apk_builder.py`。

主机结果在 `build/host/host-report.json`，APK 与构建回执在 `../self-hook-probe/build/`。报告记录唯一身份、源码摘要和工具链版本；构建前后源码必须一致。启动新构建/检查时删除其旧完成回执，失败不能沿用旧 PASS。应检查命令退出码，再将回执复制到本次独立证据目录。

## 设备验证契约

只使用 `platform.json` 明确指定的 API 37 / ARM64 / 4096 字节页 / fingerprint 和三个系统二进制 SHA。这个文件只属于研究探针，不能成为产品第二套平台权威；产品集成时复用并增强现有 RuntimePlatform。

安装包名 `dev.tripoder.selfhookprobe`。手机必须允许本次测试安装；安装被拒绝时停止设备步骤，记录 NOT_RUN。每次运行先 force-stop **这个独立探针**，再用新 UUID 启动 `dev.tripoder.selfhookprobe/.ProbeActivity`，传入字符串 extra `runId` 和 `artCompilation`（明确为 `jit` 或 `aot`），确认 LaunchState 为 COLD。AOT 模式先对本包执行 `cmd package compile -m speed -f --primary-dex -v` 并保存命令回执；JIT 模式使用新安装的未 speed 编译 APK。指定编译入口未在期限内出现即失败，不切换模式。已存在 Activity 的 intent 投递不算新测试。每次报告写入：

```text
/sdcard/Android/data/dev.tripoder.selfhookprobe/files/<runId>/result.json
/sdcard/Android/data/dev.tripoder.selfhookprobe/files/<runId>/maps.txt
/sdcard/Android/data/dev.tripoder.selfhookprobe/files/<runId>/maps-{prepared,active,disabled,restored}.txt
```

每个 UUID 只能使用一次；重复调用不能覆盖结果。启动崩溃、无回执、安装拒绝、超时都不是通过。结果包含实际 APK/Native SHA、构建身份、UID/PID、SELinux 域、平台与各阶段 maps 摘要。收集完整运行目录到证据目录的 `device/`，并复制对应的 `build-receipt.json`、`host-report.json`、Native 依赖/notes 及签名报告（`self-hook-probe/build/self-hook-probe/signature.txt`）。然后执行：

```sh
rtk proxy python3 android/scripts/verify-self-hook-probe.py \
  --evidence <本次证据目录> --run-id <本次UUID>
rtk proxy python3 android/scripts/verify-self-art-compilation.py \
  --evidence <本次证据目录> --expected <jit或aot>
```

核验器比较当前源码、主机被测源码、构建清单、实际 APK/Native、平台、maps 和各项检查结果，并以排他创建方式保存 `verification.json`。源码后来变化时，旧证据只能作为历史记录，不再通过“当前源码”的核验。

当前构建另需将 `self-hook/build/dex-host/report.json` 复制为证据目录中的 `dex-host-report.json`，绑定同一 APK 与解析器输入；主机失败不启动设备验收。
还须复制 `self-hook-probe/build/park-binary-audit.json` 与 `park-binary-audit.txt`，绑定 Native ELF 与实际反汇编。H2c 额外保存 `maps-parked-active.txt` 和 `maps-parked-restored.txt`。
H2d 还须复制 `self-hook-probe/build/libc-contract.json` 和 `libc-source-pages.bin`；构建参数 `--platform-libc` 是固定设备的原始系统 ELF，只供独立核对，不链接或打包。核验其 SHA 不匹配时构建立即失败。

H3f 还须复制 `art-framework-contract.json`；`--platform-art` 同样只供固定系统 ELF 校验。成员访问冷启动入口为 `.FrameworkProbeActivity --ez selfAccess true`；逻辑 Application 入口为 `.ApplicationProbeActivity --es artCompilation jit|aot`，均须额外传入新的 `--es runId UUID`，不能复用目录或进程状态。使用 `verify-self-framework-probe.py` 和 `verify-self-application-probe.py --expected jit|aot` 分别核验；AOT 必须保存同 APK 的 speed 编译回执。它们不替代主探针的完整 Native/ART/libc 回归。

H3g 另保存 `art-storage-contract.json` 和 `int-relay-oracle.json`。启动 `.ActiveEntryProbeActivity --es artCompilation jit|aot --es runId UUID`，使用 `verify-self-active-entry.py --evidence DIR --run-id UUID --expected jit|aot` 核验。进程内通过后，保留编译缓存，以同一 APK、新 UUID 冷启动并验证后续结果；`verify-self-cold-start.py --before BEFORE_DIR --after AFTER_DIR --output NEW_JSON` 要求实际加载探针运行时映像。speed AOT 的独立冷启动结果与 JIT 运行时映像验收分别记录；没有映像映射时不能宣称映像检查通过。详见 [存储与生命周期](../../docs/self-hook-managed-storage.md) 和 [H3g 证据](../../docs/evidence/self-hook-h3g-2026-10-15/README.md)。

H1 部分先在新匿名 RW 页完成全部写入，再转 RX，读取 maps 检查权限后执行；从不请求 RWX。七个执行断言分别检查原始加法、合成 detour、合成 trampoline、ADR 搬迁前后和 literal load 搬迁前后。H1 本身不证明已有 ELF 页修改、并发、硬件 BTI/PAC 或 ART Hook。

H2a v2 另载入自有 fixture，保留原文件 RX 视图；在新的匿名页完成 RW→RX 后，于协作调用排空期间同址发布，关闭时恢复原文件映射。45 项检查包含真实入口、原调用最多一次、errno、发布前拒绝、超时、并发、卸载及 generation。构建仅为研究探针启用 `TP_SELF_HOOK_TESTING`，注入一次候选页拒绝，与内核真实 errno 分开计数。详见 [当前模型](../../docs/self-hook-page-publication.md)；[初版 EACCES 记录](../../docs/self-hook-native-h2a.md)是历史失败，不是备用路径。

## 当前证据

2026-10-15：主机 92 项核心检查、38 项汇编器对照通过；独立 APK 构建和产物检查通过。首次安装被手机拒绝的记录已保留；用户允许安装后，新 runId 真机验证通过：普通 non-debuggable ARM64 进程内 92 项核心检查、16 项合成页检查成功，全部源码/产物/平台/maps 身份核验通过。

证据：[通过记录](../../docs/evidence/self-hook-h1-2026-10-15/788d110d-b812-43ff-a06e-8077b826acba/README.md)、[首次安装拒绝](../../docs/evidence/self-hook-h1-2026-10-15/67663c4a-9e21-473a-94f1-bfcd9990d92b/installation.json)。H1 完成；下一步为 H2 真实 Native 安装与 H3 ART 可行性风险验证。

H2a：[v2 证据](../../docs/evidence/self-hook-h2a-v2-2026-10-15/1a1f301c-314b-4b00-aeb6-1227fbf7694d/README.md)。通用 H2 仍须独立完成。

H2b：[线程暂停证据](../../docs/evidence/self-hook-h2b-2026-10-15/README.md)。H2c：[非协作 ELF 调用证据](../../docs/evidence/self-hook-h2c-2026-10-15/README.md)。H2c 的自有专属页、固定叶函数、稳定跳板和完整 join 回收证明，不能推广为任意 Native 模块安全性。

H3b：[Field.get 证据](../../docs/evidence/self-hook-h3b-2026-10-15/README.md)。H3c：[managed 与编译 caller 证据](../../docs/evidence/self-hook-h3c-2026-10-15/README.md)。最终 JIT/AOT 两组各通过 25 项 managed 检查，编译 caller 原先绕过，单个已知 caller 反优化后 10,000 次全部拦截。下一步必须解决自动调用者覆盖、未来 AOT 类加载、在途帧与非协作 Native 线程，再迁移 Runtime 适配。

H3d：[自动静态 caller 证据](../../docs/evidence/self-hook-h3d-2026-10-15/README.md)。主机 14 项检查、JIT/AOT 实际 DEX/loader 绑定的自动选择已通过；完整虚调用、动态/boot/AOT 加载、在途帧和统一发布仍未完成。
