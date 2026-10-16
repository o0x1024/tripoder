# 调用者候选、身份绑定与运行时覆盖

日期：2026-10-15。H3k已完成真实loaded-class枚举、Native方法观察及双动态loader/boot目标分项设备验证。候选图、方法绑定、原生观察和完整覆盖是四个不同的验收结果。当前完整覆盖仍未实现，不能用本模块宣布 H3 或产品替换完成。

## H3k当前机制

- resolved static/direct调用保留准确owner；仅未解析的继承static及虚分派保守扩展同签名。17项D8和55项多DEX/loader主机检查通过。实际扩展工作预算由同一DexCallerClosure维护，CallerCoveragePlan不再重复使用过度保守的签名乘积估计。
- `LoadedClassInventory`实际接入探针：约2.18万Class、同名不同loader、真实Class移动、24轮并发GC枚举；预算耗尽拒绝部分清单。
- 方法观察器已设备运行，使用完整READ_ROOT/MARK_ROOT契约将ArtMethod owner经读屏障规范化。ABI激活前及400轮并发GC根观察后核对真实owner、DEX index、入口和绑定；不能把地址token跨快照当作可解引用句柄。
- 已实测逐方法deopt-set不能阻止未来caller内联；Core首次enable改为[进程解释租约](self-hook-process-interpreter.md)，prepare不切策略。晚加载101002次全部命中，但未来AOT/image及所有writer仍未闭合。
- `ManagedHook`与原调用存储已支持实际动态defining loader和boot allocator。单个boot currentApplication通过不代表caller-sensitive通用支持。
- registry直接读取现有owner中的原子desired，不再回调C选择函数；实际ART interposition仍false。保留原LR的预链接leaf与异步展开已通过，见[入口所有权](self-hook-entry-ownership.md)。

当前证据见[H3k](evidence/self-hook-h3k-2026-10-15/README.md)。后文冷启动调查、传统PAC规划器及未设备执行的只读PAC observer是各自历史或独立能力，不应混同于当前保留原签名的leaf路径。

## 已实施的输入与输出

`CallerCoveragePlan(List<DexFile>, targetKey)` 复用现有 `DexCallerClosure` 和 `DexCallGraph`，不增加另一套 DEX 解析器。

- 输入为最多 64 份已经校验的不可变 DEX；拒绝重复定义、非 managed 目标和超过预算的图。额外限制 250,000 个定义、500,000 次调用签名扩展工作，避免仅按去重后的边数限制而遗漏重复调用点的计算成本。
- 输出包含排序后的 DEX SHA、目标、保守调用者候选、精确静态祖先，以及每个未解析调用点的 caller DEX SHA / method index / code unit / opcode。`analysisDigest` 只是分析内容摘要，不替代 ArtifactSet 或操作身份。
- virtual/interface/super 调用使用既有同签名保守候选；无继承关系的同签名类型可能成为候选。这些边不能被宣称为精确动态分派。
- 对整个输入集合清点反射、MethodHandle、invoke-custom、类加载、外部方法引用与 Native 声明。只看目标的静态祖先会漏掉通过反射或 MethodHandle 命名目标的调用。
- 构造器、类初始化器、不可执行候选形成具体缺口，不静默从 `Method[]` 中删除。

`CallerMethodBindings(plan, definingLoader, Map<descriptor, Class<?>>)` 绑定调用方已经持有的真实 Class 身份。它不调用 `Class.forName` 或 `loadClass`，也不初始化目标；反射检查可能解析签名类型，因此不能把它当作阻止类加载的屏障。

每个成员保留完整 DEX 描述符和返回类型、DEX SHA / method index、真实 Method 与 defining loader；同名不同 loader、描述符映射错误、缺失类、修饰符不符、构造器和不可执行成员均拒绝。匹配完整返回类型避免协变桥方法被 `getDeclaredMethod(name, args)` 误选。当前只接受一个明确的非 boot defining loader，跨 loader 图必须由后续 Runtime 实现真实归属/委托关系，不能合并同名类。

`scopedProbeCallers(64)` 返回防御性复制的 `Method[]`，供现有自有探针使用；任一候选绑定失败就整体拒绝。`requireCompleteRuntimeCoverage()` 始终带具体缺口拒绝：没有外部可设置的布尔值可以把静态分析升级为 Runtime 证明。这个拒绝接口是防误用保护，不是完成 H3 的替代实现。

## 只读原生方法观察器

`native/coverage/method_observation.c` 与 `coverage/MethodObservation.java` 已接入构建并在ABI设备探针执行；严格NDK编译亦通过。

入口 `MethodObservation.capture(bindings)` 接受目标加最多 64 个已绑定 caller。普通 JNI 阶段复用现有 ART 完整函数字节契约，校验方法地址可读、保留 Method/Class 强引用，并预分配输出。随后复用 JIT 排空和 SuspendAll，在一个窗口内只读实际 ArtMethod 的 owner、flags、DEX index、indices、data 和 quick entry，并用经过字节验证的 `Thread::DecodeJObject` 核对实际 owner 等于强引用声明类。窗口内没有 JNI、文件 I/O、堆分配、反优化或入口写入。恢复运行后才复制结果到 Java。

Java 检查载荷版本、数量、重复/未对齐方法地址、声明类身份、DEX index、语义 flags 和非空入口。地址仅为本次观察的诊断 token，不能在后续运行中作为可解引用的对象句柄。序号仅表示此采集器的观察顺序，不是 Runtime operation generation。当前尚未从真实 DexFile 内存取得摘要，因此 DEX index 相等不能单独证明 ArtifactSet 来源；该缺口保持开启。

自有夹具可按以下顺序接入，不改变现有控制面：

1. 从已有 ArtifactSet 取得 DEX，创建 `CallerCoveragePlan`。
2. 用探针已经持有的 Class 对象建立 descriptor map，创建 `CallerMethodBindings`。
3. 在现有 `ManagedHook.prepare` 前调用 `MethodObservation.capture`，保存真实快照；仅在自有范围使用 `scopedProbeCallers(64)`。
4. Hook 切换、JIT/AOT、GC 后重新观察并保存独立结果，核对源方法身份、入口变化和 owner 移动；不能跨快照直接解引用旧 token。
5. 产品激活仍须经过现有 Broker/Bridge/操作代次，并完成下表运行时机制；没有额外的状态权威或临时后端。

## 全量已加载清单机制及边界

只读 ART 观察可以成为真实覆盖输入，但“供入的 Method 集合”不是“进程内所有已加载方法”。实际固定平台动态符号表含 `ClassLinker::VisitClasses(ClassVisitor*)`，RVA `0x42390c`、长度 `0x224`；mini-debug ELF 另含 `VisitClassesInternal`，RVA `0x4264d4`、长度 `0x4ac`。二者须联合查询，不能只看 mini-debug 表便判断导出不可用。本地 AOSP `class_linker.cc` 的 `VisitClasses` 包装会先持 `classlinker_classes_lock_`，再在禁止线程挂起的区间调用 Internal。直接在现有 SuspendAll 内调用 Internal，或伪造未绑定的 C++ visitor，尚未证明锁序与 ABI 安全。

实际collector已使用公开VisitClasses包装、固定visitor slot2和注册HandleScope；在类锁内回调不调用JNI或分配，释放后将真实根转Class[]。移动GC和溢出拒绝已验证。没有绕过包装直接在SuspendAll中调用Internal，也没有给有限方法观察输入打上全量清单标记。类快照仍不证明未来发布覆盖。

| 尚缺机制 | 所需证据 |
|---|---|
| 真实 DEX/loader 来源 | Native ArtMethod→declaring Class→DexCache/DexFile 与 ArtifactSet 摘要、实际 defining loader 校准 |
| 已加载 boot/application 代码 | 有锁序和 GC 生命周期证明的完整类/方法清点，不能靠业务传入 Class map |
| JIT/AOT 的内联和直接调用 | 实际 code version/inline information 绑定，以及针对受影响代码的失效验证 |
| 后续类与代码发布 | AOT 类加载、JIT 发布、动态 DEX/MethodHandle 等进入统一安装协议的真实观测点 |
| 在途帧 | 活跃编译帧的版本/原调用语义与切换边界，不能只停掉未来 quick entry |
| 原子安装和失效 | 与现有 Hook 代次、审批、UNKNOWN 对账、ready ACK 相同的提交边界 |

## 验证记录

独立 javac + D8 生成真实双 DEX，主机测试 **55 项通过**。包括跨 DEX 传递链、virtual/interface/super 保守候选、真实 invoke-polymorphic / invoke-custom 指令、独立动态入口清点、全 primitive/多维数组参数、协变返回桥方法、不同 defining loader 拒绝、构造器 caller 拒绝、只读集合、观测载荷错误拒绝。观测载荷测试使用人工数据，只验证解析和拒绝逻辑，**不算 ART 采集成功**。

```sh
rtk proxy python3 android/self-hook/tests/coverage/check_caller_coverage.py \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --sdk /opt/homebrew/share/android-commandlinetools
rtk proxy python3 android/self-hook/tests/coverage/check_observation_native.py \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30
```

结果位于 `android/self-hook/build/coverage-host/report.json` 和 `native-compile-report.json`；包含源码与 D8 / object 摘要。初始 34 项结果保留在 `report-initial-34.json`。全部验证限定本地自有夹具；本子任务没有安装 APK、修改设备状态、执行第三方应用或宣称产品完成。

## 冷启动的编译所有权调查

主 agent 报告：同一 APK `55a8cd67…` 的首次 ABI JIT run `8955e032-0400-487e-9f23-de7957ab5a78` 通过，保留缓存冷重启 run `714c16a0-8e45-4625-85c3-807e140a9d24` 失败于 `ABI_COMPILATION_NOT_OBSERVED:bool`。旧失败没有热身前 snapshot，不能把根因确认为 flags 持久化。后续新版 deopt-set 冷运行 `df4fb694…` 已捕获 bool flags=`0x10200009`（bit25 为0）、quick=`0x6cef15aed0`，实际位于该 APK 的 `base.odex` RX 映射；该运行失败的直接原因是探针要求 JIT，而系统已提供 AOT。冷启动验收与 JIT/AOT 定向矩阵必须分开，不能把编译模式不符误报为不能编译。以下为只读结构风险与入口机制分析；并未在本子任务复现设备失败或实施修复。

### 临时方法标志为何会进入映像

[AOSP RuntimeImage](https://android.googlesource.com/platform/art/+/android16-release/runtime/runtime_image.cc) 的 `CopyMethodArrays` 先复制完整声明方法数组，再改写 declaring class、quick entry 与 data pointer；没有为普通 managed 方法清理 `CompileDontBother`。实际平台 `RuntimeImageHelper::CopyClass` 的 `0x3a8ca4` 从 Class+0x30 取方法数组，`0x3a8c64` 按 `8 + count * 32` 计算复制长度，`0x3a8cb4` 调用 memcpy。之后 `0x3aa300` 与 `0x3aa3a4` 分别写目的方法的 quick/data；`0x3aa44c–0x3aa454` 是 abstract 的 single-implementation 位清理，不是全方法 CompileDontBother 清理。这与源码所述持久化风险一致。

调查时旧 `art_managed_entry.c` 会给 target 设置 `0x02000000`，同时清 `0x00900000`，且停用后仍保留。实际 `Jit::CompileMethodInternal` 在 `0x46a9c4` 检查 bit 25；这证明临时禁止编译位被持久化可能影响后续进程，**不证明已记录冷运行的具体失败由它造成**。闭合个别失败因果需保存首轮初始 flags、切换后 flags、下一进程热身前 flags 及真实入口映射。新版冷运行已明确观察到 AOT，不能继续解释为禁止编译。不要自动清 bit：ART 自身也会合法设置该位。

### 逐方法反优化集合能解决哪部分

[AOSP Instrumentation](https://android.googlesource.com/platform/art/+/android16-release/runtime/instrumentation.cc) 的 `Deoptimize` 把方法放入进程内集合并更新入口、处理栈，不通过 CompileDontBother 表达所有权。实际 JIT 编译入口在 `0x46a9dc` 调用 `IsDeoptimized`，为这种选择性编译阻止提供实际代码依据。该集合不会随声明方法数组进入应用映像。

将 target/helper 登记为本进程拥有的逐方法反优化，再发布 relay，**可以作为下一自有探针的机制候选**，但以下条件仍须验收：

- 拒绝预先被其他所有者反优化的目标；安装、停用与共享 caller 的所有权不能互相删除。
- 不再把自有禁止编译状态写入目标的可序列化 flags。helper 与原调用备份也应明确各自的进程生命周期；不能把“只有 helper DEX 通常不入映像”当作所有 metadata 可任意修改的理由。
- Native 入口当前额外清理 NterpEntryFast 等位，必须审计这些位的必要性与正确恢复/重建语义。只移走 bit 25 尚不等于完整的映像语义证明。
- 不在冷启动对所有 target 盲目清 flags；现存合法的 ART 禁止编译状态须保留。修复验收应以新源码/构建身份、正常映像生成、保留缓存的多次冷启动证明成立，不能清缓存取得通过。

### 普通调用是否绕过 relay

[AOSP ArtMethod::Invoke](https://android.googlesource.com/platform/art/+/android16-release/runtime/art_method.cc) 的普通调用路径使用 quick entry；强制解释路径受 runtime 启动、Thread force-interpreter 等状态控制。实际 `ArtMethod::Invoke`（`0x2a35fc/0x21c`）在 `0x2a3670` 读 quick entry，正常路径未查询逐方法反优化集合。

[AOSP ShouldStayInSwitchInterpreter](https://android.googlesource.com/platform/art/+/android16-release/runtime/interpreter/interpreter_common.cc) 根据线程强制解释/异步异常与实际 quick entry 决定是否进入解释器。实际函数（`0x2c24c0/0xb4`）在 `0x2c252c` 读 quick，并比较解释器桥；也未单独查询 `IsDeoptimized`。因此，登记集合后再发布 relay 不会因这些普通入口自身查询集合而自动旁路。特殊强制解释、调试事件、在途反优化和内联依然需要独立测试，不能据此宣称全调用覆盖。

### 哪些事件会覆盖 relay

[AOSP JitCodeCache](https://android.googlesource.com/platform/art/+/android16-release/runtime/jit/jit_code_cache.cc) 的普通 `DoCollection → RemoveUnmarkedCode` 清理 zombie/OSR，不应概括为“每次 JIT GC 都重置所有方法入口”。单方法 `InvalidateCompiledCodeFor` 只有当前 quick 等于被失效 compiled header 的入口时才重置；actual `0x557110–0x557120` 的比较及 `0x5571b4` 的调用相符。自定义 relay 与旧 header 不相等时走另一分支。

明确的风险路径是 `InvalidateAllCompiledCode`：它遍历 managed method-code map 和 zygote map，调用 `ReinitializeMethodsCode`；实际函数 `0x8f6874/0x2d0` 的 `0x8f699c`、`0x8f6a50` 存在对应调用。后者 actual `0x557288` 查询逐方法反优化，命中后经 `0x5572b8` 向 `UpdateEntryPoints` 发布解释器桥，覆盖 relay。测试专用 `RemoveMethod` 同样可能重置入口；调试/全局 instrumentation、类初始化和 future code publication 仍应系统清点。

因此下一批应验证“进程内逐方法集合 + 自有 relay”与两种不同事件：普通代码 GC/单方法失效，以及系统级代码失效/重新初始化。产品需要在现有安装代次下接管精确目标的入口重新发布；不能仅在调用结束后检查入口已变或轮询修回，因为其间已可能漏拦。

若最终需要保留可序列化临时 flags，修复点应是**输出映像的副本**：对已登记且持强根的精确方法，按安装前记录的 owned mask 恢复拷贝中的位，同时保持活跃方法不变。映像可能在 active 期间保存，停用时恢复源 flags 无法修复已经生成的副本。这条路径需要新增固定平台序列化契约与并发验收；当前没有实现，不建议先以临时全局清位或关闭映像生成规避。

以上实际函数的完整反汇编保存在 `android/self-hook/build/coverage-host/{copy-class,jit-compile,invoke,should-interpret,reinitialize,invalidate-one,invalidate-all,gc-collection,gc-remove-zombie}.txt`，来源为当前固定设备 libart 的本地副本；官方 AOSP16 源码仅作为机制参照，不能替代实际 API37 字节契约。

## 入口写入路径与最小 owner 路由组件

`native/coverage/owned_entry_registry.{h,c}` 提供 `tp_entry_owner_register` 和 `tp_entry_owner_select`。它是进程期、有界的精确ArtMethod注册表；记录现有owner、既有generation和指向该owner原子desired的直接只读指针。组件不另存active/disabled状态，不生成操作身份，不写ArtMethod，也不安装任何拦截。重复同policy幂等；不同policy/代次占同方法冲突；容量耗尽、零值或未对齐入口拒绝。对应Method/Class/loader根保留到进程结束。

这不是产品激活器。真实同步发布拦截应在原写入前查询：未拥有方法继续原请求；拥有方法将owner的desired交给原writer。必须保留UpdateEntryPoints的CAS及旧JIT code zombie记账，不能改成裸写。已拥有但非法状态不得透传。leaf只做有界原子读，不调用C/Java、JNI、分配或锁。registry没有周期检查或修回入口；进程解释策略由独立固定ART租约承担。

`entry_writer_audit.py` 联合校验整个固定 libart SHA、内嵌 `.gnu_debugdata` 与供入 mini-debug 文件完全相同，再合并动态/mini-debug 符号。它线性解码 `.text` 的 B/BL，并通过实际 `.rela.plt`、GOT 和固定 PLT 指令格式解析跳板，输出监控函数完整字节 SHA、调用点、符号归属和 PAC 门槛。线性扫描不等于完整控制流证明，数据字仍可能产生候选；报告明确保留 indirect、inline/direct stores、未来生成代码、锁与 GC 生命周期缺口。

当前固定 ELF 找到 **26 个直接 B/BL 候选**，包括：

| 调用点 | 观察到的路径 |
|---|---|
| `0x3f491c` | JitCodeCache::Commit → UpdateMethodsCodeImpl |
| `0x3e9134` | MarkClassInitialized → UpdateMethodsCodeImpl |
| `0x556cd0` | QuickExceptionHandler::DeoptimizeSingleFrame → ReinitializeMethodsCode |
| `0x619d74` | VerifyClass → ReinitializeMethodsCode |
| `0x8dfc80` | MaybeSwitchRuntimeDebugState → InvalidateAllCompiledCode |
| `0x9e8eb4` | Trace::Start → InvalidateAllCompiledCode |
| `0x9d09e4 / 0x9d0a08 / 0x9d0a24` | DeoptimizeBootImageClassVisitor → ReinitializeMethodsCode |

还存在明确的统一写入器闭包反例：`SetEntryPointsForObsoleteMethod` 在 `0x862eec` 直接 `str x8,[x1,#0x18]`；`AddImageSpace` 的方法访问器在 `0x60d1c8` 按 pointer size 直接写方法 quick entry。是否会触及某个已注册目标，要由 class/loader/obsolete/future-image 生命周期证明决定，不能仅拦 `UpdateEntryPoints` 就称所有入口改写均已覆盖。实际平台还有厂商 `HyperArtAddImageSpace` 路径，未纳入完整语义证明。

七个监控函数（UpdateEntryPoints、UpdateMethodsCodeImpl、ReinitializeMethodsCode、InvalidateAllCompiledCode、InvalidateCompiledCodeFor、Deoptimize、Undeoptimize）首指令均为 `PACIASP`。测试直接调用项目现有 `tp_a64_plan_entry`，确认它对实际 UpdateEntryPoints prologue 返回 `TP_A64_UNSUPPORTED` 且不修改输出。**当前不得安装这些入口的拦截，不能跳过 PAC 或盲拷前导指令。** 下一实现门槛是按既有 H2 规范补齐 PAC/ABI/线程与页发布的真实支持，然后核实每个原入口及额外直接写入路径。此文不授权修改安全门槛，也没有尝试修改实际 libart。

验证命令：

```sh
rtk proxy python3 android/self-hook/tests/coverage/entry_writer_audit.py \
  --art android/self-hook-probe/build/device-libart.so \
  --debug android/self-hook-probe/build/art-debug.elf \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --output android/self-hook/build/coverage-host/entry-writer-audit.json
rtk proxy python3 android/self-hook/tests/coverage/check_entry_ownership.py \
  --art android/self-hook-probe/build/device-libart.so \
  --debug android/self-hook-probe/build/art-debug.elf \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30
```

ASan/UBSan 主机测试 **82 项**、四线程 **800,000 次**策略选择无错误；选择结果只来自原请求或现有 owner，未出现跨方法误路由。路由组件 ARM64/API37 严格编译通过。LLVM 独立检查 **35 项**：26 个实际分支目标、7 个 PAC 首指令和2处直接写入指令。结果在 `build/coverage-host/entry-ownership-report.json`。所有报告保持 `installedInterposition=false`、`completeEntryWriterClosure=false`，因此不构成防漏拦截或产品完成证明。

## 独立的传统 A-key/SP PAC 规划器

新增 `native/coverage/pac_a_sp_plan.{h,c}`，未修改旧 `a64_entry.c`、`a64_relocate.c` 或共享构建。它只生成离线计划，未安装真实 libart 拦截。

协议严格保留原目标 `entry+0` 的 PACIASP，只在 `entry+4` 规划一个四字节 B。此时原 SP 未改变，LR 已由原目标认证键签名。relay 使用 `BTI c; AUTIASP; LDR x16,literal; BR x16`，在尾跳 C replacement 前恢复正常 LR；replacement 本身必须以 PACIASP 开始。原调用 trampoline 为 `BTI c; PACIASP; 原第二指令; B entry+8`，因此从新的原调用返回地址开始重新签名，继而进入原方法的正常栈布局。

这遵循 [Arm 的 PAC/BTI 组合说明](https://developer.arm.com/community/arm-community-blogs/b/architectures-and-processors-blog/posts/p2-enabling-pac-and-bti-on-aarch64)：PACIASP 本身有特定 BTI 入口语义，不能简单换为更宽的入口。通过 x16 的尾分支具备相应 BTI 调用入口兼容语义，见 [Arm 尾分支说明](https://developer.arm.com/community/arm-community-blogs/b/architectures-and-processors-blog/posts/p3-enabling-pac-and-bti-on-aarch64)。原目标第一条指令不变，relay 没有修改 SP、参数寄存器、x18 或 SIMD 参数。

### 必须拒绝的环境与指令

根据 [Arm ISA PACIASP 定义](https://documentation-service.arm.com/static/67e40f3398aa3c3b6eea6a85)，存在 FEAT_PAuth_LR 且 PACM=1 时，PACIASP 还使用 PC 作为第二 modifier；对应 [AUTIASP 定义](https://documentation-service.arm.com/static/68da52dfbd7cab51328c0622) 使用 x16 提供该附加 modifier。因此只识别 `0xd503233f` 不能证明此方案成立。规划器只接受 `TP_PAC_A_SP_WITHOUT_PAUTH_LR`；UNKNOWN 与 PAuth_LR present 都拒绝。此枚举是离线输入约束，**不是运行时能力证据**；设备集成必须核验真实硬件/内核暴露的 profile。单有 HWCAP_PACA 也不能证明缺少 PAuth_LR，不能把一次传统调用成功当成永久 PACM 保证。

目前第二条仅准入：

- `SUB SP,SP,#imm`，立即数非零、16 字节对齐、无移位、最大4080。
- `STP x29,x30,[SP,#negative]!`，负偏移为16字节倍数、最大512字节。

这是无 PC 相对操作数的完整指令形状检查，不是任意前导字节复制。B-key、PACIASPPC、其他 PAC 形式、第二条 BL/RET/branch、异常寄存器组合、未对齐帧、未知 replacement 前导、页边界跨越、地址溢出、代码区重叠或分支越界均拒绝；失败不修改输出。LLVM 用 `armv9.5-a+pauth-lr` 独立编码六种 PAC/AUT 形式，对受支持与拒绝样本进行核对。

### 展开信息与生命周期

输出包含 relay 与 trampoline 的逐 PC CFA、LR 签名状态、FP/LR 保存位置。relay 从签名 LR 开始，在 AUTIASP 后（offset8）成为原始 LR；trampoline 在 PACIASP 后（offset8）成为签名 LR，在第二条执行后（offset12）改变 CFA/保存位置。规则依据 [Arm AADWARF64](https://github.com/ARM-software/abi-aa/blob/main/aadwarf64/aadwarf64.rst)；错误的 RA_SIGN_STATE 会使展开器对错误的 LR 状态执行认证，不能忽略。

离线 rows 不等于已注册 FDE。匿名生成代码在运行前还需真实的展开信息发布、生命周期保留和异步展开验收。源码页发布必须校验第一/第二条原始字节、线程 quiescence、W^X、cache sync、BTI 映射和没有外来入口进入 `entry+4`。现有七个固定 ART 函数的前两条都能通过本规划器的传统 profile 离线检查，但这些运行时条件与入口写入闭包没有因此通过。

### 自有汇编夹具与验证

`coverage-fixtures/pac_owned_fixture.{S,c,h}` 提供两种栈前导的 baseline、预链接 routed target、relay、original trampoline 和真实 C replacement。没有执行代码补丁。夹具 relay 为 PIC `ADRP+ADD+BR x16`；动态规划器的 `LDR literal+BR x16` 另由 LLVM 独立编码逐字节验证。两者在 AUTIASP 后均保持 SP 和参数，C replacement 调用原 trampoline 后改变结果。relay/trampoline 自带明确 FDE，LLVM 验证了对应位置的 CFA 与 RA_SIGN_STATE；尚未验证设备实际展开器或异步信号展开。

`tp_pac_fixture_run(report)` 供主 agent 在核验平台 profile 后集成自有设备探针。它计划执行1026项检查：读取实际 raw/signed/authenticated LR，验证签名有变化且认证还原，256组九个整数参数（含栈参数）、两个浮点位模式（含 NaN payload）分别经过 baseline/routed 两种前导，以及 SP 恢复。它尚未在设备执行，不能把编译结果算成1026项通过。

```sh
rtk proxy python3 android/self-hook/tests/coverage/check_pac_a_sp_plan.py \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30
```

当前结果：**613 项 ASan/UBSan 主机检查、31 项 LLVM 编码/展开检查、7 项固定 ART 前导离线检查通过**；规划器 ARM64/API37 编译通过，自有 fixture 以 BTI 和无 text relocation 链接成功。fixture SHA 为 `601e49d5aaf50a1d0dcf6619f779a3dabdbe36281e4821e984b508fcf0b8ca67`。报告位于 `build/coverage-host/pac-plan-report.json`，展开结果为 `pac-fixture-unwind.txt`。实际 ART 拦截、动态 FDE 发布、平台 profile 及设备执行均未验收。

## 只读 PAC 平台观察夹具

新增 `coverage-fixtures/pac_platform.h`、`pac_platform_collect.c`、`pac_platform_assess.c`、`pac_platform_json.c`。未修改现有核心、共享构建或设备。调用次序为：

```c
struct tp_pac_platform_report platform;
struct tp_pac_platform_assessment assessment;
char json[4096];
if (!tp_pac_platform_collect(&platform)) { /* 本次采集失败 */ }
tp_pac_platform_assess(&platform, &assessment);
size_t length = tp_pac_platform_json(&platform, &assessment, json, sizeof(json));
/* length==0 时拒绝保存残缺证据；否则写入本 run 的独立结果。 */
```

主 agent 可将三个 C 文件加入自有探针原生库，再通过普通 JNI 返回 JSON。夹具不加载另一个 Hook 后端，不修改 PAC key、PACM、CPU affinity 或信号处理器。`PR_PAC_GET_ENABLED_KEYS` 是只读查询，结果仅属于调用线程；不能由主线程一次查询推断所有现存工作线程。

### 采集边界与 SIGILL 分析

[Linux CPU feature register ABI](https://docs.kernel.org/arch/arm64/cpu-feature-registers.html) 由 HWCAP_CPUID 公布。夹具仅在该位可观测且设置时读取 `ID_AA64ISAR1_EL1`、`ID_AA64ISAR2_EL1`、`ID_AA64ISAR3_EL1`、`ID_AA64PFR1_EL1`、`MIDR_EL1`，均位于文档列出的 emulation 编码空间。没有 HWCAP_CPUID 时不会执行这些 MRS。ABI 可能掩掉字段或将未知寄存器返回零；MIDR 又只反映单次读取所在 CPU，因此输出不把零或单个 MIDR 解释为全系统缺失特性。

不通过可选指令试错来“探测”特性。[Linux HWCAP 文档](https://docs.kernel.org/arch/arm64/elf_hwcaps.html) 明确指出，缺少功能时不能依赖一定发生 SIGILL。当前使用已公布的 MRS ABI，不需要自行安装 SIGILL 恢复路径。若厂商内核违反自己公布的 HWCAP_CPUID ABI，探针应作为失败保留崩溃证据；本组件不会改 handler、siglongjmp 或把异常恢复成“feature absent”。

### schema 1 与准入语义

所有64位 auxv、寄存器与时间值采用字符串，避免 JSON 消费端整数精度损失。字段如下：

| 字段 | 来源与含义 |
|---|---|
| `kernel.release/version/machine/errno` | uname 原始结果；`contractVerified=false`，不以版本字符串替代内核源码/产物证明 |
| `pid/tid/monotonicNs/clockErrno` | 本次观察的进程、线程和时间 |
| `cpuBefore/cpuAfter/midrScope` | 采集前后 CPU 编号，仅供定位；不设置亲和性，不作无迁移或全 CPU 证明 |
| `auxv.hwcap/hwcap2/hwcap3/hwcap4/pageSize` | 每项含 `status`、`errno`、`valueHex`；缺项/错误值为 null，实际可观测零保留为零 |
| `prctl.pacEnabledKeys` | 当前线程 PR_PAC_GET_ENABLED_KEYS 原始结果；不支持/失败为 error，不冒充 key disabled |
| `idRegisters` | `linux_user_view / no_cpuid_abi / unread`；未读取时寄存器和拆分 fields 都为 null |
| `fields.apa/api/apa3/pacm` | 对可观测 ID 值做字段拆分；仍是内核处理后的视图 |
| `assessment/reasonBits/reasons` | `unknown` 或 `rejected`，附具体原因 |
| `plannerProfile/classicProfileAdmitted` | 当前固定为 `unknown / false`，此观察器不自行授权 PAC 安装 |

实际可观测的 PACA/BTI 未公布、APIA key disabled、非4K页、非 AArch64，以及 APA/API/APA3 为已知 PAuth_LR 编码6，都会拒绝当前固定协议。缺失数据、未来编码、零 ID、未知厂商内核保持 unknown；不会以 fallback profile 消化未知信息。

对于看似传统的 APA/API/APA3 编码1–5，下一步还需把设备的内核产物与可见性/归并规则绑定。当前 [Linux cpufeature 实现](https://github.com/torvalds/linux/blob/master/arch/arm64/kernel/cpufeature.c) 对这些地址认证字段使用 FTR_EXACT，而非普通功能常用的 lower-safe 归并，但这不能直接证明本设备厂商分支相同。ISAR3.PACM 的用户可见零尤其不能证明硬件不支持 PAuth_LR。需要固定实际内核的相关规则、特性可见性和 CPU 生命周期证据；本组件没有接受调用方布尔值的“内核已验证”开关。

采集成功本身也不准许运行上一节的签名 fixture。先审阅本 run 的原始证据并完成实际 profile 契约，再运行 `tp_pac_fixture_run`，保存其实际1026项结果与原始/签名/认证后 LR；不能用运行 fixture 成功反过来假装所有线程和未来 PACM 状态已获保证。之后才推进动态代码发布和入口写入闭包。

```sh
rtk proxy python3 android/self-hook/tests/coverage/check_pac_platform.py \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30
```

当前 **52 项 ASan/UBSan 主机评估/JSON检查通过**，包含 missing 与实际零的区分、PRCTL 错误、未知/未来 ID、64位精度、字符串转义、短缓冲和不可终止身份字符串拒绝。Android ARM64 原生库编译/链接通过；二进制检查确认5个预期 MRS、没有 MSR/PACM 指令及信号处理导入。报告在 `build/coverage-host/pac-platform-report.json`，原生库为 `libpac-platform-observer.so`；尚未在设备执行。

### 动态 FDE 的下一实施边界

规划器中的 unwind rows 是动态 FDE 构建的输入：每个 relay/trampoline 分别描述地址范围、CFA、签名 LR 状态及保存槽位。未来必须将不可变 FDE 与对应 code identity 同步准备，在代码可被调用前完成真实展开器注册，并在仍有帧或回调引用时保留二者。不能先发布入口再补注册，也不能只在停用时释放 metadata。

需先核实设备实际 C++ unwinder 的动态注册能力、注册接口身份/线程语义，以及 ART/native backtrace 所用展开器是否共享该注册表。仅链接一个 `__register_frame` 名字或在主机让 `llvm-dwarfdump` 解析成功，不能证明 Android 所有展开路径可见。下一自有验收应覆盖原调用异常跨越、relay/trampoline 每个状态点的异步采样、并发切换和停用后的在途帧；当前只有静态 FDE 编码证据，动态注册与异步展开均仍未完成。
