# Tripoder Self-Hook (`android/self-hook`)

本模块是 Tripoder 完全自研的底层 AArch64 Native Hook、ART Java Hook、`fixed_libc` 双页路径包装器与受限 TypeScript 脚本执行引擎（零 Frida / 零 Xposed 依赖）。

详细设计规范见 **[Self-Hook 与脚本引擎设计 (`docs/self-hook-design.md`)](../../docs/self-hook-design.md)**，整体演进计划见 **[架构梳理与分阶段实施计划 (`docs/implementation-plan.md`)](../../docs/implementation-plan.md)**。

---

## 核心组件

- `include/tripoder_a64.h`、`native/a64_relocate.c`、`native/a64_entry.c`：ARM64 白名单指令重定位规划器，支持 PC 相对寻址指令搬迁与 `BTI c/jc`、`PAC` 前导指令保留。
- `native/owned_hook.c`、`native/code_page.c`、`native/page_commit.c`：基于 `mremap` 的 W^X 匿名执行页同址原子发布与原文件映射恢复。
- `native/thread_park.c`：有界信号线程安全停顿（`tp_park_begin_bounded`），确保改写窗口内无活跃线程 `PC`。
- `native/fixed_libc.c`：接管 `libc.so` 10 个核心路径入口的双页事务包装器。
- `native/art_access.c`、`native/art_managed_entry.c`、`native/art_method_storage.c`、`native/art_reflection_view.c`：ART Java 方法 Hook，支持最小底座启动期一次批量发布（保留 JIT）与按需业务 Hook 局部调用者反优化、GC Root 读屏障安全及反射/调用栈隐身。
- `native/native_buffer.c`：基于 `process_vm_readv` 的有界内核内存安全读取，支撑被动 TLS 明文捕获（`ctx.native.captureBuffer`）。

---

## 主机与设备验证

```sh
# 运行 AArch64 指令规划器与核心单元测试（ASan/UBSan + LLVM 汇编对照）
python3 android/self-hook/tests/check_host.py \
  --toolchain /Users/len/Library/Android/sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin

# 运行 Java/Native Hook 策略与 SDK 编译测试
python3 android/runtime/self-hook-tests/run.py
```
