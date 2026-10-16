# 固定框架成员访问

## 2026-10-16 declaring-class GC root 修复（当前源码）

当前注册表已扩展至55个固定root，并已用于自研Runtime。下方H3f是历史范围。本次用户真机诊断报告 `resolveNull=[0,47,0,11,0]`、`lastMismatch=[23,"20f5310","a4c4cf0"]`：ApplicationStartInfo整类方法被declaring owner比较过滤。这个特定运行数据来自用户，本次没有重新运行该设备场景。

已核对 Native 契约：ArtMethod/ArtField首字是native GcRoot压缩引用，不能直接与JNI Class handle解出的当前对象地址比较。两者都可能在32位地址范围；关键是concurrent GC期间原始slot仍指向from-space，而JNI root已经指向to-space。boot image不可移动根掩盖了问题，不能靠提前加载所有类或重试枚举解决。

方法和字段现在都把**实际根槽位地址**交给固定 `artReadBarrierForRootSlow`（RVA `0xa2c4e4`，72字节），其按collector/thread marking状态转入 `MarkFromReadBarrier`（RVA `0x207210`，1424字节）。复用 `art_storage_contract.h` 的原有函数体证据，但在Framework registry准备阶段独立校验/绑定，不依赖OriginalMethodStorage已经初始化。原始root不得伪装成jobject送入DecodeJObject；其stack-handle路径只是原始读取。

读取与owner解码均在同一次FastNative/Runnable窗口；保留合法继承副本、runtime helper、clinit和JNI ID decoder往返检查。新增 `declaringRootsRead`、`declaringRootsAdjusted`、`lastMismatchCanonical` 诊断；调整计数不是放宽owner验证。

已通过：实际production resolver的确定性host回归（模拟from-space slot，旧原始比较变异版本必须失败；新方法/字段解析成功，真正继承/clinit/runtime/无效ID仍拒绝）；固定完整ELF SHA及10个函数体核验；ARM64/API37严格编译。证据：`docs/evidence/framework-declaring-root-2026-10-16/`。**尚未重建/安装APK，尚无新的真机GC压力、微信或P4验收。** 下一步在首次成员枚举阶段施加真实GC压力，校验55个root的完整声明摘要，再复测微信；缓存后的反复读取不能替代首次枚举。

### H36 真机 GC 压力验收补充

已重建安装 Runtime `6369d433d45c2e5112f2d2e9c76884983a672e5b4afa1c12df6f64ee64a2d88c`。独立oracle来自8个真实boot JAR、13个DEX，原始class_data/method_ids/proto_ids解析与SDK dexdump逐类交叉核对；设备逐JAR核对SHA，55类/6824声明齐全。probe直接调用自身私有原生注册表入口，持续GC压力在prepareNative之前启动，每个ordinal首次读取前交错1ms，不用Java声明缓存替代首次枚举。

三个独立冷进程，**每轮121项通过**：

| 目录（framework-gc-h36下） | GC次数 | 根地址修正 | 首次枚举时长 |
|---|---:|---:|---:|
| gc-02-interleaved | 924 | 28 | 8.673s |
| gc-03-interleaved | 757 | 27 | 8.693s |
| gc-04-interleaved | 721 | 30 | 8.469s |

每轮ApplicationStartInfo的44个声明与独立DEX一致（46个原生槽中另有2个合法排除项）；每轮全部6824个声明完整，无Java/Native fatal。12个真正继承副本和34个clinit仍按既有规则排除；不要求null计数机械归零。`gc-01`较短87ms/8次GC也通过，但无转发修正命中，不能单独作为根转发覆盖。汇总核验：[gc-verification.json](evidence/framework-gc-h36/gc-verification.json)。这完成本次GC root压力验收，不代表微信、王者或最新P4已通过。

```sh
rtk proxy python3 android/self-hook/tests/check_framework_roots.py --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
```

## H3f 历史记录

2026-10-15。H3f 的成员访问门槛已通过；后续 [逻辑 LoadedApk / Context 与 Application attach](self-hook-application-adapter.md) 也已独立验收。产品仍依赖 Frida。

## 问题与唯一实现

普通 JNI 冷启动清点：7 个类正常加载，20 个必需成员中 16 个可访问；`LoadedApk.mDefaultClassLoader`、`mAppComponentFactory`、七参数构造器及 `ContextImpl.createAppContext(ActivityThread, LoadedApk, String)` 被非 SDK 策略拒绝。不能重试同一调用、跳过字段或改用物理宿主 Context 后返回 ready。

`FrameworkAccess` 在已固定的 API 37 / ARM64 / libart SHA 上提供一个固定成员注册表。只允许七个 boot 类、十一个字段、九个方法；不接收任意类名、字段名或用户偏移，不开启全局非 SDK 豁免。普通 JNI 清点是独立诊断入口，不是这个注册表的备用后端。

准备阶段在普通 JNI 中验证 libart 文件映射及八段完整机器码、Runtime 的 JNI ID 表示、自己的 Native 方法 flags 和可读地址并集；随后注册自己的三个 `@FastNative` 方法。每次 FastNative 最多处理一个成员，不执行文件 I/O、脚本或应用回调。Android 将此注解列为公开 API，但执行期间会限制 GC 暂停，因此每次工作必须有界，不能持有长期 Native 锁。[Android JNI 指南](https://developer.android.com/ndk/guides/jni-tips#faster_native_calls)

## JNI ID 与引用

真实设备使用 `kPointer` JNI ID，固定实现明确要求 `Runtime.jni_id_type == 0`；其他表示拒绝。字段转换使用实测 `EncodeArtFieldInternal`，方法转换遵循完整 `FindMethodID` 函数证明的指针表示，排除继承副本、运行时 helper 和类初始化器。每个 ID 经真实 ART decoder 往返校验，返回反射对象后再核对声明 Class、名称、参数、返回值和静态属性。没有将 jobject 或通用 opaque ID 猜作元数据指针。

试验发现低层 `JniIdManager::EncodeGenericId` 实际为索引表示服务，不能忽略调用者的 ID 类型分支后直接套用；该错误版本在首次 ID 往返时被拒绝，没有执行字段写入。它已由唯一的固定指针表示实现替换，不保留为另一条运行路径。

七个 boot Class 全局引用保留至进程结束。元数据遍历只针对这些固定 boot 类，单类最多 1024 个方法，整个注册表最多 8192 次有界访问；不覆盖应用类卸载、JVMTI 重定义或任意 loader。Native 地址不返回给 Java。可读区间在普通 JNI 中准备，FastNative 不读取 maps。

## 已验证与后续

run `65aab644-b251-42fe-bb6e-171c5de588e6`：20 个必需成员全部通过；16 个与普通 JNI ID 校准一致；770 个声明方法反射对象完成签名筛选；真实物理 Application、LoadedApk、ContextImpl 与 ActivityThread 归属正确。三项 FastNative flags 和 `jniIdType=0` 已记录。

本次没有写入框架字段，没有改变框架方法绑定。自身三个 Native 方法注册不等于安装框架 Hook。证据核验绑定当前源码、APK、Native、平台 SHA、maps 和本次 runId；见 [回执](evidence/self-hook-h3f-2026-10-15/65aab644-b251-42fe-bb6e-171c5de588e6/verification.json)。

当前构建 `fdb5d874…` 重新运行固定注册表，run `7a76d834-3b8f-4bbd-9f69-bc8c3a6dd6e1` 通过。该构建的逻辑 LoadedApk / ContextImpl、初始 Application 反射视图、attach/onCreate 与物理宿主归属也已通过 JIT/AOT；后续才推进产品 bootstrap 与其余必要适配。
