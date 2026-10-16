# 固定平台 ART 访问与 Native 方法入口

日期：2026-10-15。H3a / H3b 已分项通过；H3c 自有 managed 方法和已知 caller 的选择性反优化也已验证，完整 ART/反射适配和产品迁移尚未完成。

## 从实际边界出发

普通 APK namespace 看不到所需 libart 符号。首轮独立探针同时记录 `dl_iterate_phdr` 的实际模块和 `RTLD_DEFAULT` / `RTLD_NOLOAD` 查询结果：模块存在，六个符号无法通过普通动态加载接口解析。JNI `FromReflectedMethod` / `ToReflectedMethod`、原异常对象、监视器、线程身份、两个 ClassLoader 的同名方法分别验证。JNI ID 始终作为不透明 token 使用，不以本次 token 恰好等于内部地址推广为指针契约。

当前唯一 ART 访问机制固定到 platform.json 中的 libart SHA，按实际 ELF 的 dynsym RVA 和完整函数字节绑定 Thread::CurrentFromGdb、ScopedSuspendAll 构造/析构函数。调用前复核映射 inode/device/file offset、RX 权限和完整函数内容。没有猜测跨版本偏移，也没有切换到其他后端。`art_fixed_contract.h` 的证据来自本机实际 ELF；源码中的 AOSP main 只用于理解职责，不作为 OEM 二进制布局证明。

这不是公开稳定 ABI。系统或 APEX 更新导致 SHA 变化即拒绝。Java 平台门禁在任何 Native 执行前验证完整 libart SHA；内部绑定函数仅供此固定探针流程使用，尚不是通用发布 API。

## 锁与方法快照

固定构造/析构函数的反汇编证明其不访问 this 对象，直接转发到当前 Runtime 的 ThreadList。独立探针从普通 JNI（无 FastNative/CriticalNative 注解）进入，以 Native 线程状态请求暂停；独占期间只读取 32 字节方法快照，不调用 JNI、分配、回调 Java 或脚本。调用后恢复线程。真机已完成连续 10 次暂停/恢复。

这一协议只覆盖 ART 的 mutator 状态，不能当作全部 Native 线程静止。AOSP ThreadList 声明要求调用方不持有 mutator/thread-list/suspend-count 锁，并在 SuspendAll 返回时取得独占 mutator 锁：[ThreadList 契约](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/thread_list.h)。

32 字节布局先以实际 CopyFrom 机器码和 AOSP 结构交叉核对，再用自有 registeredAdd Native 方法验证 flags 和 data 指针恰好对应本次 C 实现。只有固定字段读取成立后才检查实际目标；不扫描未知内存寻找“看起来像”的入口。该校准还不是所有 ArtMethod 类型、复制和退休语义的证明。

## H3b：Field.get 的 Native 入口绑定

`java.lang.reflect.Field.get(Object)` 是本次唯一目标。先通过普通调用使原 JNI 入口解析，保存其真实 Native 实现；入口必须位于本次 SHA 对应的 libart。随后用 JNI RegisterNatives 绑定自研 C 函数，验证 data 指针变化而 flags 和 quick entry 均保持原值。[JNI 注册接口](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#registernatives)。

回调先直接调用原 Native C 实现，保留原 Field.get managed frame，不从 Java 包装器再次 Method.invoke。原访问检查或类型检查产生异常时立即保留 pending exception；仅在原调用成功且 Field 对象为自有 fixture 的指定对象时，返回预先创建的替换值。原字段本身不变。普通 Native 方法和非 Native managed 方法是不同的实现问题；这条注册接口不能替代后者。

对照项包括 private 字段拒绝、null / 类型错误以及与 private 组合的检查顺序、setAccessible 开启与撤销、实际存储值不变、GC 请求后的全局引用有效性。原反射实现依据：[AOSP Field Native](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/native/java_lang_reflect_Field.cc)。对完整产品仍需复用 ReflectionAccessChecks 验证 Application 逻辑视图及 Field.set/所有 primitive 变体。

生命周期使用一个进程期固定槽位、四个全局引用，最多 128 次绑定变更。准备数据在 ready 的 release/acquire 边界发布；每个回调只获取一次 enabled 版本。停用先关闭新替换，再恢复原 Native 注册；已获取旧版本的调用可以按旧版本返回。可能已经取到旧 C 地址但尚未进入回调的调用仍安全，因为代码、原函数和全局引用保留到独立进程结束。没有把“计数为零”误当成入口已经不可达；不宣称无限槽位或通用回收。

RegisterNatives 开始后，任何失败、flags/quick 意外变化或入口核验不一致都终止独立探针，不保留未知注册状态继续运行。

## 编译证据

四线程混合切换测试只能证明返回值落在允许版本中，不能单独证明热调用没有绕过 Hook。新增连续启用的一百万次读取，并要求每次结果都为替换值、Native 替换计数增量恰好一百万；停用后再次执行热调用必须恢复原值。

调用者 ArtMethod quick entry 在前后分别采样，并映射到同一次 maps。已观测到入口从 libart 运行时入口变为 dalvik-jit-code-cache，百万次调用全部拦截。另对独立探针包执行 speed AOT；命令成功本身不算 AOT 执行证明，必须看到实际 hotRead 入口指向本包 base.odex。

前三次 AOT 观察发现执行前入口仍是 `art_quick_resolution_trampoline`，运行后才指向 base.odex，严格核验拒绝。最终测试显式请求 jit/aot，通过反射进入所采样方法，先在 10 秒期限内观测到对应可执行映射，再开始热段；状态未出现即失败。热段前后均复核映射。

同一 APK 的最终 JIT/AOT 两组均通过 62 项 Native 方法检查，各一百万次热调用全部进入替换；四线程分别执行 3,573,192 / 3,456,027 次混合版本调用，错误为零。[构建身份和证据](evidence/self-hook-h3b-2026-10-15/README.md)。这不证明未测调用者的内联覆盖，也不代表普通 managed 方法已支持。

## 下一实现边界

普通 managed 方法需要独立解决原方法元数据/代码所有权、GC 根、JIT 代码寿命、解释器分派、AOT/内联调用者失效和并发退役。当前 [H3c 设计与证据](self-hook-managed-methods.md)已覆盖自有方法槽位、原调用和一个已知 caller；自动识别调用者与在途帧仍未完成。

Runtime 迁移继续复用 GuestContext、组件控制、C 路径策略和 Hook 操作 journal。自有 RuntimeBridge 占位方法应直接实现为 Java/JNI 调用；系统/客体真实入口才需要 Hook。完整 adapterReady 门禁不提前放宽，工作台和引擎本轮尚未迁移。
