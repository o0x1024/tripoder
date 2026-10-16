# H3c 自有 managed 方法与已编译调用者

日期：2026-10-15。当前仅支持独立探针的两个自有 `static int(int)` 方法，尚不是通用 ART Hook API。

## 元数据与原调用

当前备份使用 [H3g 独立方法存储](self-hook-managed-storage.md)：ART LinearAlloc 分配、目标类 ClassExt Native 根跟踪，位于声明方法表之外。H3c 本身保留原声明类与 Method 的四个强引用，两个备份槽另由共享存储保留十二个引用及一个注册表引用。旧 `ManagedBackup` 声明槽移植方案已删除；它在后续 H3g 冷启动中暴露了应用映像损坏，历史当次进程结果不证明其生命周期正确。

固定 libart 的 `ArtMethod::CopyFrom` 复制方法身份并处理 declaring-class barrier。备份原调用统一使用解释器入口，禁止编译该备份，避免保留不拥有的 JIT 代码指针。原方法恢复时从备份中的实时 GC 根复制，不能恢复旧快照中的 Class 地址。AOSP [类 Native 根访问器](https://android.googlesource.com/platform/art/+/refs/heads/android16-release/runtime/mirror/class-refvisitor-inl.h)遍历类实际拥有的全部方法槽位；设备检查仍按固定二进制和实际运行结果约束。

当前原调用使用存储模块明确核验过的固定 `kPointer` JNI ID 契约；其他 ID 表示直接拒绝。H3c 对照探针仍在 JIT 排空、ART mutator 独占期间把目标转换为普通 JNI 入口，回调在原线程调用原方法一次，原异常保持 pending，整数加法遵循 Java 环绕语义。存在旧 managed 帧时应使用 H3g 的独立 Native helper 入口，不能套用 H3c 的方法类型转换。

`ScopedJitSuspend` 在 `SuspendAll` 前执行，按相反顺序恢复。函数 RVA、完整机器码和 Runtime 的 Instrumentation 字段来源均由实际 libart 绑定；未知平台不执行。顶字节带标签的 Native 堆指针仅在查询 maps 时去除 TBI 字节，实际访问保留原标签。

## 实际发现的缺陷

仅替换 target quick entry 不足以覆盖已编译的调用者。JIT 与 AOT 两组均复现：对小方法 `InlineFixture.plusOne` 设置替换后，预先编译的 `ManagedCaller.call` 的 10,000 次调用全部绕过新入口，返回旧值；计数器为零。

对该**已知调用者**调用固定 ART 的 `Instrumentation::Deoptimize` 后，确认 deoptimized 集合包含该方法，入口为 `art_quick_to_interpreter_bridge`。随后 10,000 次调用全部进入自研替换，错误为零。核验器分别检查反优化前的真实 JIT/AOT 映射和反优化后的解释器入口，未对整个进程开启解释执行。

反优化登记保留原始 ArtMethod 地址，另持有一个 Method 全局引用直至进程退出。该引用计在反优化模块中，独立于上述存储引用。当前没有释放或 ClassLoader 卸载能力。

## 验收与限制

两组均通过 25 项 managed 检查：原调用一次、同线程、同一个异常对象、GC 请求后调用、整数溢出、10,000 次直接反射入口替换、编译调用者绕过见证和选择性反优化后的 10,000 次拦截。停用后原返回值恢复。[构建及证据](evidence/self-hook-h3c-2026-10-15/README.md)。

这些结果没有覆盖全部实例/同步/递归/对象返回 ABI，也没有证明通用 GC 压力、ClassLoader 卸载或任意线程激活。当前元数据安装/恢复发生在测试协调者已确认自有目标调用结束的边界，不能套用到其他线程仍保留该方法栈帧的场景。改变方法类型时旧 managed/native 栈帧如何解析，必须单独处理。

已经证明入口替换存在编译调用者遗漏，因此产品接入还必须完成：

1. 自动找出受影响的直接及内联调用者，包含跨 Dex、ClassLoader 和 boot image 的身份关系。
2. 在一次发布协议中完成调用者失效、目标变更及 JIT 排空；不能先对客体宣告 ready 再补处理。
3. 处理未来加载的 AOT 调用者、正在执行的帧和编译任务；不能靠提前加载所有类改变客体语义。
4. 验证逻辑停用与物理元数据恢复的区别，解决旧入口/备份尚可达时的生命周期。

H3c 手工指定的一个调用者仅证明选择性反优化路径。后续 [H3d 静态调用者索引](self-hook-caller-index.md)已去掉该名称硬编码，但范围仍是自有静态链，不能称完整自动内联覆盖已完成。
