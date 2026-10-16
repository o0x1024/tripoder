# Tripoder 自研 Hook 方案与审查

2026-10-16 当前状态：产品源码与两套构建已改为单一自研Java/Native核心；固定2177绑定/25项在首条客体代码前安装，P0/包管理/P4已有实际产品回执，剩余长流程继续验收。用户明确选择启动前建立解释策略，运行中动态Java/Native Hook保持；任意已有AOT在途帧迁移不属于该边界。以下按日期保留分阶段研究与旧方案，当前架构和结果以[启动链](self-hook-runtime-bootstrap.md)、[迁移清单](self-hook-adapters.md)和[交接](self-hook-handoff-2026-10-15.md)为准。

## 目标与范围

在 Tripoder 自己管理的客体进程中，用独立编写的 Native/ART Hook 核心替换 Gadget、Gum、frida-java-bridge，保持现有 Runtime、工作台、MCP、安装权威、进程身份、审批和执行契约。不复刻 Frida 的远程注入、通用调试器或多平台产品。

“自研”指 Hook 核心及其适配独立实现；Android NDK、编译器、现有 Babel AST 解释器和将来的独立 QuickJS 属于允许的通用依赖。使用其他现成 Hook 框架替换 Frida 不算本目标完成。不得复制 VirtualApp 或第三方 Hook 实现。

零 Root、零 ADB 激活、普通非 debuggable APK；ADB 仅安装测试 APK、启动探针及收集证据。外部 MCP 的现有 ADB 传输与客体运行权限分开。第一目标仍是已验证的 API 37 / ARM64 / 固定固件，不设计自动兼容、偏移猜测或失败后切换后端。

研究探针与当前产品构建相互独立。self-hook 产物不能包含或调用 Frida；既有产品暂未迁移不构成 self-hook 的恢复路径。每个阶段只证明该阶段，完整产品切换由 H6 决定。工作台和引擎 UID 分离、客体和引擎同 UID 的安全边界不因更换 Hook 改变。

## 审查发现与修订

| 级别 | 原方案缺口 | 修订及验收要求 |
|---|---|---|
| P0 | 只列 Hook API，缺少全部必要适配迁移清单 | RuntimeEngine 在 Application 前等待 adapterReady；按 [适配清单](self-hook-adapters.md)逐项迁移，所有必需项就绪才进入客体业务代码 |
| P0 | calls 为零被误当作跳板可回收 | 区分停止接纳、撤销入口、调用排空、内存回收；线程可能已进入跳板但尚未计数。没有经证明的静止边界就不回收；首版允许明确有界的进程期保留，不能宣称无限热更新 |
| P0 | ART 只讨论方法地址，缺少状态转换与原调用语义 | 覆盖 mutator 锁、SuspendAll、非 Runnable 线程、GC 引用、异常对象、监视器、类初始化、JNI 方法和 caller-sensitive 原帧；禁止在 ART 独占区间回调 JNI/Java/脚本 |
| P0 | 反射适配被当成普通 Java 方法替换 | Field.get/set 当前依赖 enter/leave 观察和原始访问检查；原始栈帧、类型错误先后、异常和引用生命周期必须等价 |
| P0 | 单条跳转编码被当成安全安装 | 指令搬迁、所有线程执行位置、W^X、页权限恢复、I-cache、入口所有权、BTI/PAC、异常展开独立验证。未知指令拒绝，绝不原样盲拷 |
| P0 | 逐方法反优化及quick所有权被误当成未来内联覆盖 | 真机已复现晚加载caller在目标relay仍正确时绕过；Core首次enable持有[进程解释租约](self-hook-process-interpreter.md)。准备不切策略、外来instrumentation拒绝；未来AOT/image、writer和产品性能仍独立验收 |
| P1 | 动态库只有枚举，没有通知锁语义 | 保留真实 link_map/加载代次；验证卸载通知时序、链接器锁与重入。不在通知内任意执行脚本或隐式 pin 模块 |
| P1 | API level 被当成完整平台身份 | 记录 fingerprint、ABI、页大小、libart/libc/linker64 SHA、实际 ART 结构及符号证据、CPU/页执行能力；系统/APEX 变更后重新验收 |
| P1 | QuickJS 被当成完整脚本替代 | 继续执行现有受限 TypeScript AST，不扩大为任意 eval；保持 BigInt、String、预算、原函数最多一次和参数快照。新 IR/解释器需要另行设计 |
| P1 | “去掉库名”被当成无 Frida | 构建输入、APK 条目、ELF DT_NEEDED、DEX/脚本来源、运行时 maps 一并核验。已有测试成功不能继承为新后端验收 |

源码依据：`android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java`、`RuntimePlatform.java`；`android/runtime/agent/index.ts`、`hooks.ts`、`reflection-view.ts`、`hook_modules.ts`、`storage.ts`；`android/scripts/runtime_bridge.py`、`build-workbench.py`；`src/draft.ts` 和 [现有 P4 契约](runtime-hook-design.md)。

## 责任划分

| 模块 | 唯一职责 | 不承担 |
|---|---|---|
| Native Core | 明确支持的指令规划、跳板、发布与停用、原调用、模块身份 | 空间/项目/操作数据库 |
| ART Core | 已测 ART 中的类/方法/引用/线程边界与方法变更 | 以 public JVMTI 附加绕过 non-debuggable 要求 |
| Runtime Adapters | 现有框架语义在 Java/C++ 中的必要拦截 | 用户脚本授权和独立后端选择 |
| Script Executor | 受限 AST、同步调用、不可变参数版本与预算 | 任意脚本 eval、新语言或隐式类型转换 |
| RuntimeBroker / Hook Bridge | 复用已有 owner、operationId、generation、证据与 ACK | 第二套状态权威或结果未知后的重放 |

必要拦截与用户 Hook 使用统一的进程内目标所有权表；用户不能覆盖基础适配。禁止用一张新的持久表重复现有 Hook 操作状态。对目标身份和生命周期的检查不能只在 prepare，activate 前还须再次比较。

## Native 契约

### H1：只规划，不修改活跃代码

`android/self-hook` 第一批实现纯 C 的 AArch64 指令规划器。输入显式指令字、源/目标地址；所有指令来自调用方已验证的完整读取。返回值只代表静态计划，不授予写入权限、不证明目标身份或并发安全。

首版使用 4 字节直接 B，保留入口 BTI c/jc，生成 BTI c + 搬迁指令 + 直接返回分支的固定跳板。保留 BTI 不等于已完成硬件 BTI 验收。直接分支超出范围明确拒绝，不添加借用 x16/x17 的远跳路径。规划器只识别明确白名单：NOP/BTI、ADD/SUB immediate、ADR/ADRP、B、B.cond、CBZ/CBNZ、TBZ/TBNZ、标量 LDR/LDRSW literal。拒绝 BL、PAC、RET、其他间接跳转、异常/系统指令、未知编码及不满足范围的指令；后续扩展逐项验收。

块内直接分支重定向到对应搬迁地址；ADR/literal 指向源覆盖区域，以及原分支/ADR/literal 指向将被跳板覆盖的目标区域时拒绝。入口规划还拒绝回跳到保留 BTI 前缀，避免原调用重新进入 replacement。ADRP 保持原页地址，但后续地址使用及自观察语义仍须调用方证明。失败时不发布部分输出。地址溢出、对齐、分支边界、源与跳板重叠和保留编码均有负例。H1 不分配近地址、不改 ELF.text、不处理任意入口中间跳入，也不提供安装接口。

独立 non-debuggable 探针运行相同核心测试，并在自建匿名页验证 RW→RX、真实执行与静态搬迁结果；从不请求 RWX。这仅验证分配页面的执行能力，不证明已有 ELF 页可以安全修改，更不证明并发热补丁或 Java Hook。

### H2：真实 Native Hook 的前置门槛

必须先明确有效函数入口/覆盖窗口和 ABI；无符号、IFUNC、未知签名、varargs、结构体返回、signal handler、异常跨跳板等未批准能力拒绝。保持 x18、callee-saved、SIMD/FPU、SP 对齐、errno、线程局部状态；enter/leave 观察与 replacement 分别验收。

安装需要目标页准确权限、内容和所有权复验；不能将 mprotect 或单条对齐写入视为所有线程已安全停止。Native 线程的静止协议独立于 ART SuspendAll。部分写入、权限恢复或 cache 同步失败后不能返回成功并继续不明状态。准备失败没有写入则可以 FAILED；变更后结果不明按已有契约终止所属进程并保留证据。

停用过程区分逻辑版本关闭与物理入口撤销；回收必须证明所有旧入口/跳板/回调不可达。内存预算耗尽明确拒绝新激活，不偷偷释放或重启。动态库卸载与重载使用真实代次，不以路径/Build ID 相同复用地址；不通过长期 pin 掩盖生命周期。

当前 H2a 只接受自有专属页和协作调用域。初版文件页写后恢复 RX 被拒绝；现采用唯一的“匿名页 RW 准备 → RX → 排空 → 同址发布”模型，关闭时恢复保留的原文件映射。来源身份与实际执行映射分开验证，不继续保留原写脏路径。[当前页所有权与发布契约](self-hook-page-publication.md)。此协议不能直接套用到未知线程或包含其他函数的共享页。

## ART 与脚本门槛

首轮只研究固定平台，不承诺所有 Android。公开 ART TI 代理要求 debuggable，不能作为现有 non-debuggable 产品的捷径。应用类和 boot class 必要入口分别验证，真实 Class/ClassLoader 身份不能替换成名称。

需要真实 JIT/AOT/内联证据；循环调用次数不等于编译状态证明。测试实际线程上的异常对象、递归、static/instance/native、synchronized、类初始化失败、多 ClassLoader 同名类、GC 和启动前后安装。原始调用最多一次。当前唯一编译策略是[进程解释租约](self-hook-process-interpreter.md)，产品须在客体代码执行前显式建立；这不构成选择性反优化或既有AOT在途帧迁移的证明。

脚本仍通过 `prepareDraft`，保持现有受限语法、步数/内存/调用深度约束。独立 QuickJS 的锁、跨线程回调和嵌套原调用必须设计，不能将所有同步 Hook 排队到单一线程导致 Binder/锁重入死锁。调度、时限和终止语义没有证明前不得开放任意回调。

## 分期与决策门槛

| 阶段 | 交付 | 通过条件 |
|---|---|---|
| H0 | 审查、依赖清单、契约和任务 | 每一必要适配有归属；未支持能力与失败方式明确 |
| H1 | 纯 Native 规划器、主机/设备独立探针、依赖回执 | 编码/边界/拒绝测试；non-debuggable、无 Frida、RW→RX 和合成指令真实执行；不得称 Hook 完成 |
| H2 | 固定设备上的真实 Native 安装/原调用/停用 | 原函数次数、并发、BTI/PAC、已有 ELF 权限、失效、模块卸载、预算及故障证据 |
| H3 | ART 风险验证 | 上述 ART 语义与线程门槛；应尽早与 H2 研究交错，H1 通过不能推迟 ART 的可行性决策 |
| H4 | 必要适配迁移 | 无 Frida 构建运行自研未修改 APK 的 Application/四类组件；每项对照现有契约 |
| H5 | 脚本/MCP 接入 | 复用审批与操作身份，实际生效/停用/错误/UNKNOWN 全链路 |
| H6 | 产品切换 | 新后端当前构建全套回归、目标业务及性能证据、产物扫描，才移除产品 Frida 构建依赖 |

上一轮 2–6 周原型 / 4–8 个月限定替换仅为 2–3 名相关经验工程师的粗估，不是承诺。H2/H3 的风险门槛未通过前不据此安排完整交付日期。

## 证据与完成定义

每次构建保存源码摘要、工具链版本、APK/ELF SHA、签名报告、依赖清单；每次设备执行使用新 runId，回执绑定实际 APK、PID/UID、平台和本次证据。timeout、崩溃、缺失回执、UNKNOWN 均非通过。主机测试、合成页执行、真实 ELF Hook、ART、Runtime 产品、目标业务分别记账。

正式去除 Frida 需要产品源码输入及依赖图可审计、打包文件允许列表、ELF 依赖检查和运行时映射证据。对封装/静态链接的第三方实现，仅检索字符串不能证明独立来源；构建来源清单也是门槛。

当前状态与任务以根目录 [task.md](../task.md) 为准。

## 首批实施结果

- 自研 C 规划器已实现；92 项主机检查在 AddressSanitizer / UndefinedBehaviorSanitizer 下通过，38 项由 NDK LLVM 汇编器独立对照通过。
- 复审修复了回跳 BTI 前缀与引用跳板覆盖区域两类错误接受，并加入负例。共享检查也已编入设备 APK。
- 独立 non-debuggable APK 已构建，打包条目/Native 依赖/来源摘要检查通过。主机报告与构建回执按同名源码摘要绑定；每次执行有独立身份，失败不得沿用旧完成回执。
- 首次安装被手机拒绝，记录独立保留。用户允许安装后，新 runId `788d110d-b812-43ff-a06e-8077b826acba` 真机执行通过：普通 non-debuggable ARM64 进程内 92 项共享核心检查、16 项合成页检查成功，包括 RW→RX 与七个实际执行断言。
- 已核验当前源码、主机源码、构建身份、实际 APK/Native SHA、平台二进制身份和 maps。探针运行无 Frida/Gadget/Gum 映射；`liveCodePatched`、`hookInstalled`、`javaHook`、`btiEnforcementTested` 均为 false，不能扩张为真实 Hook 或硬件分支保护验收。
- [代码与复现说明](../android/self-hook/README.md)；[H1 通过证据](evidence/self-hook-h1-2026-10-15/788d110d-b812-43ff-a06e-8077b826acba/README.md)。
- H2a 初版发现文件页写后恢复 RX 返回 EACCES，并在目标入口变更前明确拒绝。[初版设计及失败证据](self-hook-native-h2a.md)保留。
- H2a v2 重设计页所有权后真机通过 45 项检查：真实入口安装/原调用/停用、发布前拒绝、排空超时、四线程 50 轮安装/停用及 360,209 次调用、原文件映射恢复、卸载与重载。104 次匿名页发布，临时视图峰值 3 页，关闭后临时/匿名页计数归零。源码、APK、平台和四阶段 maps 已绑定；原 fixture 文件 SHA 未变。
- [当前模型](self-hook-page-publication.md)、[H2a v2 通过证据](evidence/self-hook-h2a-v2-2026-10-15/1a1f301c-314b-4b00-aeb6-1227fbf7694d/README.md)。结论仅限自有 ELF / 固定 ABI / 协作域。
- [ART Native 入口](self-hook-art-design.md)已验证固定符号/锁契约、保留原反射帧的 Field.get 替换；JIT/AOT 实际入口下各一百万次调用全部拦截。
- [owned managed 方法](self-hook-managed-methods.md)已验证原调用备份与已知 caller 的选择性反优化。JIT/AOT 均复现入口替换后的编译调用者遗漏，反优化后 10,000 次全部拦截。自动调用者发现、未来加载的 AOT、在途帧及通用 Native 线程边界仍是产品接入前置门槛。
- [静态调用者索引](self-hook-caller-index.md)已在单 DEX/单 loader 的 owned fixture 上自动完成 caller 选择；虚调用、动态加载与完整运行时调用闭包仍未覆盖。
- [Native 线程暂停](self-hook-native-thread-park.md)原语已通过 JIT/AOT；线程创建可能因信号阻塞被明确拒绝。尚未将暂停与代码页提交组合，不能据此宣称非协作 Hook 完成。
- [H2c 非协作调用发布](self-hook-noncooperative-publication.md)已将暂停原语与专属页提交组合；JIT/AOT 的四线程各 50 轮、超过五千万次调用通过。共享页、完整 ABI、硬件保护与未知调用栈仍未完成。
- [H2d 固定 libc 共享页](self-hook-fixed-libc.md)已验证十个必要存储入口、两个 RX|BTI 页统一提交/恢复，复用 Runtime C 策略；JIT/AOT 各 24+24 项检查通过。通用共享页/ABI/模块生命周期与其余产品适配仍未完成。

## 原始规范

- [Arm AAPCS64](https://github.com/ARM-software/abi-aa/blob/main/aapcs64/aapcs64.rst)：寄存器、栈与调用约定。
- [Android ABI / PAC / BTI](https://developer.android.com/ndk/guides/abis)：分支保护与目标平台限制。
- [Android ART TI](https://source.android.com/docs/core/runtime/art-ti)：可调试状态与公开代理接口边界。
- [AOSP ART instrumentation](https://android.googlesource.com/platform/art/+/master/runtime/instrumentation.h)：运行时状态与锁契约；具体设备实现仍须按二进制验证。

独立实现依据规范、项目已有契约和自研夹具；没有引入第三方 Hook 引擎源码。
