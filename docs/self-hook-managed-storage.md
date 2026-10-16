# H3g：原调用存储、在途帧与应用映像

2026-10-15。本文记录原调用存储及历史 H3g 验收；H3h 已扩展 managed Java ABI，最新进度见 [交接文档](self-hook-handoff-2026-10-15.md) 与 [task.md](../task.md)。产品仍使用 Frida，本文不表示 H4–H6 已完成。

## 缺陷与修正

旧 H3c/H3g 实验把目标 `ArtMethod` 复制进另一类预先声明的 Native 方法槽。虽然当次原调用和 GC 请求测试能够通过，该槽仍属于另一类的声明方法数组，其 owner、DEX index 和 method index 已与所属数组不一致。

`518e7c75-edb6-4c86-9ff5-99abfdb338ad` 当次运行通过后，下一进程 `a35bdc53-1b9d-43a9-a3de-0654f5cd42b9` 在 `ClassLinker::AddImageSpace` 崩溃，尚未加载探针 Native 库。AOSP 的应用映像生成逻辑会逐项保存类的声明方法数组，并按所属类和方法元数据进行重定位。因此，当次进程通过不能证明持久化后的元数据仍合法。

当前实现删除了两个预声明备份类，H3c 探针与 H3g 入口试验共用 `OriginalMethodStorage`。备份由 ART 的 `LinearAlloc::Alloc(kArtMethod)` 分配，始终位于声明方法数组之外，通过目标类 `ClassExt` 的 Native 根访问器跟踪。备份保持原 owner、DEX 和 CodeItem，不设置 obsolete 标志；它仍是可调用的原始方法。这里使用 ClassExt 的根容器，不执行类重定义或旧栈迁移。

## 分配与发布协议

1. 普通 JNI 绑定完整 ART 函数字节和当前平台 SHA；只接受已经核验的 `kPointer` JNI ID 表示。验证自有 FastNative 方法确实带有对应标志。
2. 普通 JNI 预分配每个 owned class 一组固定容量的 `long[64]` 方法指针数组和 `DexCache[64]` 根数组，以及 owner、loader、反射对象等引用。
3. FastNative 保持 Runnable/shared-mutator 状态。安装真正登记到当前线程的 HandleScope 后调用 `EnsureExtDataPresent`，让分配过程中发生的 GC 能更新 owner 与 ClassExt。HandleScope 的容量、已用数量、引用偏移和线程链接偏移均由实际设备函数体核验。
4. 同一类可复用本核心已发布的64槽数组；首次要求 ClassExt 两个根数组均为空，后续要求两个数组身份均属于本核心。已有其他所有者时直接拒绝，不覆盖、不扩容其他子系统的根数组。新增槽位时重新发布相同数组以执行 ClassExt 写屏障。
5. 从该 loader 的 ART allocator 分配空方法存储，准备 JNI 根数组。`Class.dexCache` 的 Java 声明是 `Object`，按真实字段声明解析，再由 JNI 执行读屏障和数组写屏障。
6. 返回普通 JNI，先暂停 JIT，再 SuspendAll。复制原方法、设置备份解释器入口、发布 ClassExt 两个根数组并检查归属，随后按反序恢复。独占区没有 JNI 调用、分配、文件访问或 Java 回调。
7. 每次存储快照在 SuspendAll 下核对 Class → ClassExt → 方法数组/DexCache 数组 → 独立备份的完整关系，并检查备份不在声明方法数组中。

每个成功槽保留 6 个强引用，注册表另保留 1 个类引用；上限为 64 个尝试槽。H3h 的19个成功槽保留114个槽位引用，全部保留到进程退出，未实现回收或 class loader 卸载。分配失败的尝试也占用预算；不得以重试绕过上限。

## 保持 managed 目标的入口

目标只修改 quick entry 和必要的编译控制位，原 owner、DEX index、CodeItem、方法索引和 managed 类型保持不变。入口跳板把 `x0` 切换到单独的真实 Native helper 元数据，再进入 JNI bridge；它不改变 LR、SP 和实参寄存器。该 ARM64 编码由独立 LLVM 汇编器核对。

目标激活时已经在运行的旧 managed 帧继续原版本；停用时已经进入 helper 的回调保留其捕获的策略版本。备份、helper 和 RX|BTI 跳板均保留到进程退出，以覆盖停用后仍在途的调用。

方法索引字中低 16 位才是索引，高 16 位是可变热度计数。归属检查不把热度变化误判为方法身份变化。

## 历史 H3g 验收

同一 APK `20dbd17df6cf333b4435c0e8e576a48b5b96aca46b3f13b3b38c0eca953d1277`，source digest `ecdbac16b34134922fc741df2aef292572006749032283dce699a966ef69b5fd`：

- JIT、重复冷启动 JIT、AOT 各 1,071 项检查通过，均有实际编译入口观测。
- 激活前的旧帧返回 43，停用时已进入 helper 的调用返回 143，原异常对象保持一致。
- 四线程、42 次状态切换，分别完成 223,930 / 206,276 / 260,358 次 worker 调用；原函数执行次数与调用次数严格相等。
- 8 次在途帧期间的 GC 请求后根关系仍成立。当前报告 `observedOwnerMoves=0`，不能据此宣称已经证明移动 GC 更新路径。
- 首次 Hook 进程结束后，不清理编译缓存，同一 APK 的独立进程成功加载 `cache/oat_primary/arm64/base.art`，随后完整 JIT Hook 重复运行通过。
- H1–H3f 主探针 JIT/AOT 回归通过；包括固定 libc 十入口和两个共享页、Native 并发、反射及原调用存储。

证据与历史失败见 [H3g 证据索引](evidence/self-hook-h3g-2026-10-15/README.md)。独立冷启动核验器要求两次结果分别通过、APK/build/source 相同、PID 不同、时间先后成立，并且后一个进程同时具有实际应用映像及其文件映射。

## 剩余门槛

H3h 已在自有类上验证实例/对象/long/浮点 ABI及同步方法，但通用 Native C ABI、所有调用者及虚调用内联、后续类加载、目标模块卸载、回收和长期预算仍未完成。H3c 的旧目标 Native 标志切换仍只是历史对照探针，不能用于存在旧 managed 帧的产品目标。产品入口适配必须使用经过相应 ABI 和生命周期验收的核心。

下一步补齐冷启动独立验收与产品固定字段接入，再补齐 loader/caller 的激活闭包。通过后才能接入 Runtime Bridge、统一 ready 门禁和脚本控制面。

## 源码依据

- [AOSP RuntimeImage](https://android.googlesource.com/platform/art/+/refs/heads/android16-release/runtime/runtime_image.cc)：声明方法数组复制及 ClassExt 不进入运行时映像。
- [AOSP ClassExt](https://android.googlesource.com/platform/art/+/refs/heads/android16-release/runtime/mirror/class_ext-inl.h)：独立方法的 Native 根遍历。
- [AOSP HandleScope](https://android.googlesource.com/platform/art/+/refs/heads/android16-release/runtime/handle_scope.h)：可被 GC 访问的登记式句柄范围。
- [AOSP Class](https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/ojluni/src/main/java/java/lang/Class.java)：dexCache 的 Java 字段声明。

这些源码用于解释机制；API 37 的实际 ABI 以设备 ELF、完整函数字节契约和探针结果为准。

## H3h 根快照验收修正

H3i已完成同构建JIT/AOT/cold真机验收，见 [证据索引](evidence/self-hook-h3i-2026-10-15/README.md)。并发复制期间暂停mutators仍可能观察到旧原始引用；当前根关系读取使用固定ART读屏障，不能用原始地址直接与JNI强根比较。ART内部读屏障可能执行标记/复制，独占区只保证无显式JNI调用、分配和I/O。

当前报告在一次 SuspendAll 内采集全部已发布槽的 owner、目标/备份 owner 和三个 container 根地址，恢复 mutators 后才格式化。它们是历史证据地址，不用于后续解引用。独立验收器重新计算移动数量，并要求每槽的根关系成立、container 确实移动。

严格 JIT/AOT 运行仍要求实际 owner 移动。cold 中未移动的 owner 必须落在实际 owned app image 的匿名区域，并与准确自有 base.art 文件映射绑定；截断的 VMA 名必须与同一文件两端匹配。完整 cold 聚合还要求同构建的先前运行确实证明 owner 移动。参数对象实际移动及原返回引用身份门槛不变。
