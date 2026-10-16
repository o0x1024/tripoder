# Android ARM64 原生引擎构建

此目录把固定的 QEMU 5.1.0 源码构建成 APK 内的唯一引擎 `libtripoder-qemu.so`。JNI 直接调用 `qemu_init`、`qemu_main_loop` 和 `qemu_cleanup`，没有宿主包查询、Intent、SAF 文件回调或旧动态库加载路径。

## 构建环境

当前脚本固定用于 Apple Silicon macOS 构建主机，输出 Android ARM64 API 33 原生库。设备验证 APK 的 targetSdk 保持 36。NDK r30 和各原生依赖的来源、长度及 SHA-256 均在 `dependencies.lock.json`，下载和构建目录为忽略的 `../build/native`。

本机使用系统 make、Clang、Python 3.9 运行上游生成器；准备脚本由 Python 3.12 以上运行，宿主 gettext 工具位于 `/opt/homebrew/opt/gettext/bin`。pkgconf 从锁定源码构建。原生依赖不链接 Homebrew 的 macOS 库。

```sh
rtk proxy python3 android/vm-probe/native/prepare.py --download
rtk proxy python3 android/vm-probe/native/build-dependencies.py
rtk proxy python3 android/vm-probe/native/build-engine.py --configure
```

`prepare.py --verify-only` 核对下载缓存。源码解包后应用 `patches/` 中的差异；已有源码树若缺少准备回执或补丁摘要变化，准备脚本直接停止，需使用新源码目录。`build-dependencies.py --from-stage glib --skip-configure` 等选项只用于开发时继续已配置构建；改变编译参数必须重新配置。

`build/native/output/receipt.json` 记录原生输入和输出摘要。APK 打包前再次核对这些输入和动态库，源文件变化后拒绝打包陈旧引擎。带调试符号的 DSO 保留在 `objects/qemu/aarch64-softmmu/`，APK 仅去掉调试段。

## PAC 与线程归属

Android 对现代 targetSdk 应用的新线程分别初始化 PAC APIA 密钥。保存了返回地址的协程栈不能直接交给另一线程恢复。诊断版已在设备上捕获保存线程与恢复线程不一致，与旧版本 `siglongjmp` 的 PAC 异常相符。

补丁的执行规则如下：

- 空闲协程池只在线程内部复用，使用 pthread-key 析构，同时覆盖 Java 和 QEMU 创建的线程。
- 新协程直到第一次实际执行才创建栈和跳转上下文。
- 已终止任务的空闲栈若在新线程执行新任务，释放旧栈并重新初始化；不保留旧任务的活动帧。
- 已 yield 的活动协程仍绑定原线程。跨线程恢复在 `siglongjmp` 前记录错误并终止验证进程。
- `sigaction` 修改由互斥锁串行化。保留 PAC 编译选项、FORTIFY、栈保护和 Android 系统保护，不修改 PAC 密钥或目标 SDK。

GLib 补丁修正旧原子操作宏的类型；Pixman 补丁修正浮点返回值的无效 const 类型，均为 NDK Clang 21 编译要求。没有跳过编译器的类型检查。

## 设备回归与验收

引擎启动前在同一普通 APK 内运行 `coroutine-selftest.c`：提交线程先执行任务使池内存在已签名栈，再向四个线程派发新任务；每个线程反复创建、暂停、恢复和回收超过池容量的一批协程。多层调用栈上的 canary 和线程身份在每次恢复后检查，线程退出输出池释放数量。通过标记为 `TRIPODER_COROUTINE_SELFTEST_PASS`，包含实际任务数与切换数。

这组回归不代替完整 Android 启动。客体启动仍须核对独立内核、`sys.boot_completed`、Activity、Package、SurfaceFlinger 和画面；也不代替后续图形性能、客体 Hook 及跨应用缺包负向测试。若活动协程 guard 触发，必须修复真实调度归属，不能删除 guard 或关闭 PAC。

## ARM64 代码块链接竞态

QEMU 5.1 原实现原子写入 `B+NOP` 或 `ADRP+ADDI` 两条指令。但另一个虚拟 CPU 可能已执行第一条，更新后再执行第二条，从而组合出错误地址。上游 [d59d83a](https://github.com/qemu/qemu/commit/d59d83a1c38869b1e1a4f957eb939aaa8a342721) 将更新改为单条指令，[a2495ed](https://github.com/qemu/qemu/commit/a2495ede07498ee36b18b03e7038ba30c9871bb2) 修正 LDR 的立即数字段。本地代码与旧缺陷实现一致。这是额外发现并修复的并发缺陷；随后故障指令取证证明本次重复 SIGILL 的直接原因是下节 CMLE 编码错误。

此处保持 5.1 的 TB 接口，将跳转槽布置为 8 字节对齐的 `B/LDR`、固定 `BR X30`、8 字节目标地址。每次先原子更新完整目标地址，再仅修改首条 32 位指令，reset 指向地址数据之后。未减少虚拟 CPU 数量或 TB 缓冲区，未关闭 MTTCG、PAC 或宿主保护。

`tcg-branch-selftest.c` 为该逻辑提供原生回归：实际执行近跳转、正负约 160 MiB 的远跳转；四线程并发执行 100 万次，同时更新跳转 10 万次；再模拟线程已加载旧地址后暂停、目标更新后从第二条指令恢复。回归通过标记为 `TRIPODER_TCG_BRANCH_SELFTEST_PASS`，必须以真机日志确认，不能以构建成功替代。

`crash-diagnostics.c` 只在 SIGILL 时读取引擎自己登记的可执行代码页、寄存器与自身映射，记录后恢复 Android 原信号处理并让原指令再次触发异常。它不跳过指令、不恢复失败执行，也不读取其他应用内存。

## 启动动画阶段的 CMLE 编码错误

指令诊断版在 `run-1791894389049` 再次崩溃，故障 PC `0x6c54e44760` 对应 `0x6ee0a800`。前后指令组成正常的向量比较代码；QEMU 的 `I3617_CMLE0` 基础操作码错误地写成 `0x2e20a800`。NDK 汇编 `cmle v0.2d, v0.2d, #0` 得到 `0x6ee09800`，与上游 [6c2c777](https://github.com/qemu/qemu/commit/6c2c7772f69bcd7e7a88308fd6aaf19debb7ada4) 的单行修复一致。此故障没有关闭向量指令集来规避，而是修正编码。

`tcg-simd-selftest.c` 调用真实 TCG 编码器，在手机上实际执行生成指令，检查不同元素宽度的负数、零、正数及符号边界。诊断记录及反汇编保存在 `docs/evidence/android-source-vm-2026-10-13/tcg-instruction-diagnostic/`。

单元素 64 位形式另需使用专用标量指令，不能发送架构保留的 1D 向量编码。已移植上游 [d81bad2](https://github.com/qemu/qemu/commit/d81bad24dfea6ec0331599de1f31d822aba9dae1) 的操作选择修复，并将比较回归扩展到 8 种形式、56 组输入，覆盖高半部清零。该相邻缺陷由源码核对确认；本次客体故障记录实际触发的是 2D CMLE 错误，不混为同一个崩溃证据。

## 固定虚拟屏幕的模式契约

旧 QEMU 通用 EDID 生成器为首选分辨率生成约 75 Hz 的 DTD，并额外声明多个桌面模式。在本镜像里，1024×768 的另一个模式为 60.00384 Hz。DisplayManager 以 0.01 Hz 容差接受它，SurfaceFlinger 的容差为 0.001 Hz；镜像关闭帧率覆盖，60 Hz 上限使候选集合变空，最终触发 `No matching frame rate modes for primary range`。故障发生在客体 SurfaceFlinger，原生引擎与客体 watchdog 均未触发这次重启。

本实验只有一块固定 1024×768 屏幕。`panel-edid.c` 定义唯一首选 DTD：61.44 MHz 像素时钟、1280×800 总时序，精确为 60 Hz；不声明 established、standard、CTA 或可推导额外模式的连续频率范围。QEMU 的实际 virtio-gpu EDID 请求直接使用这份 128 字节数据，其他尺寸明确失败。未修改客体帧率策略、断言或安全配置。

`tests/verify_panel_edid.py` 编译生产 C 生成器，独立解码 EDID，验证校验和、时序、唯一模式、整数纳秒换算容差，以及非法尺寸／短缓冲区不写入。设备启动前再次运行同一生成器自检。还必须由客体日志证明实际读取了唯一模式，并验证系统服务和桌面；EDID 测试不能证明 HWC 的 fence／commit 成功，也不构成实际帧率测试。
