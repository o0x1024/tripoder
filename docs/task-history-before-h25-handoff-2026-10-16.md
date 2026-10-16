# Tripoder 自研 Hook 实施任务

## 当前实施状态（2026-10-16 H19）

用户要求先解决存储Hook，并完成剩余接入和验收后再停止。持续实施；下方H0–H13是历史快照，不能当当前结论。

- [x] 固定libc十入口绝对尾跳，移除存储近地址页依赖；真实在途PC安全核验、两页提交/恢复、24+24项和408986并发调用独立通过。
- [x] Engine和两套产品构建切换单一自研核心：25项真实安装、2177绑定，产品不包装/加载Gadget或旧JS adapter；物理/逻辑Application、Java/Native动态Hook保留。
- [x] 构造26、JNI31、manager1523/15、完整4096容量与移动GC/132writer事件独立通过。
- [x] 实际Runtime P0 60、packages159、P4 105、Activity sources17通过；完整P2已运行至Provider，尚未全过。
- [x] Activity冷目的进程改复用不可变组件保留，避免来源UI同步等Hook导致ANR；真实来源/目标重建与远程结果通过。
- [x] 主机统一30秒启动截止点合同8及SDK211源码通过；权威原总上限保持30秒。
- [ ] 统一截止点的Provider冷依赖/循环/写操作UNKNOWN真机回归（当前正在运行providers-shared-deadline）。
- [ ] 剩余独立组件、p3实际存储、完整P2最终回执。
- [ ] 当前正式Engine/Workbench的control/hook-ui、真实手机审批及Agent断连UNKNOWN。
- [ ] 最终当前源制品、性能证据、文档和交付收口。

最新runId、包摘要、失败根因与正在运行session见 [当前交接](docs/self-hook-handoff-2026-10-15.md)。不能将单阶段通过当作全部完成。


## H12/H13 当前状态（2026-10-16）

用户最新询问当前进度；已说明核心验收与完整产品验收的区别，继续原目标，未完成不标为完成。

- **H12 固定接口真机独立通过**：APK `9d68937481d60f55baba550e7ffc72e7ed1e81b9f9fbc78ed42e18fdd4bd7ae7`，build `d7496290-51d8-4f39-ab51-9a347f889911`，source `3e700e30da947be1acc8c14df9716437793cf6086c4f1bdd0ce2f6391411f08f`，run `722f1a92-5bd2-4c28-8cf0-8d33c6873178`。46访问器、17安装检查/652固定入口、10 manager门面；实物H5 `artifacts/9d689374`。这是physical passthrough夹具，不是实际Engine路由验收。
- **产品源代码已切换**：`RuntimeSelfHookProduct`完整25项真实plan；同一`RuntimeManagedInstaller`用于固定组件/manager/Activity/包管理。NETWORK_STATS含Traffic62，WIFI_P2P含Channel.close，HOOK_CONTROL接真实复合Platform与现有Authority控制链。Engine加载`tripoderselfhook`，删去Gadget等待与重复packages.initialize。没有保留替代后端。
- Application安装职责已拆：Framework阶段准备固定成员，Ownership阶段捕获真实物理所有权，Reflection阶段真实准备并发布pending view及两入口Hook。Native `prepareOwnershipNative`和`beginViewNative`分别一次；旧夹具调用同步更新，构造前14项需新构建回归，不能用旧证据追认新API。
- `self_hook_native.py`构建产品专用SO：只链接core及一份io_adapter/path_policy和新`self_hook_storage.c`，不链接probe JNI/TP_SELF_HOOK_TESTING。模板/固定平台契约与产品暂停临界区二进制审计已过；CPP visibility设hidden以去除意外vtable导出。`check_park_binary --product`只排除三个明确位于probe源码的函数，保留全部产品关键函数要求。
- `build-runtime-probe.py`/`build-workbench.py`都只包装一份`libtripoderselfhook.so`，包装清单严格核对；产品receipt记录selfHookNative/fridaInputs空及包内去依赖。源码SDK209、主机bootstrap293/loader11/component85/boundary117/policy293(108支持)/typed45通过。**包内无Frida不等于产品验收通过**。
- 首次完整Runtime APK `fe3f2db6c41409375905795d640fe744cf13d4e5b5aa7e0d37c156eb9999fbeb`已构建并运行p0，失败run `p0-dce7527cedac4ae0`，证据`docs/evidence/self-hook-product-2026-10-16/p0-first`。实际异常`FIXED_RUNTIME_HOOKS_NOT_PREPARED`，在客体之前拒绝，已补Framework阶段FixedComponentHooks.prepare/FixedRuntimeHooks.prepare/FixedManagerInventory.inspect。同时复用FrameworkAccess.prepare已读declarationCache，避免整合清单重复消耗Native8192预算。**新修复正在重建，尚未验收。**
- 首次workbench Engine APK `46c04c35714439afda4321a540d251d078446387f614f9621d2b13b5958a5f31`编译/包装成功，尚未安装验收，且缺上述最新初始化修复。不要部署旧包。P0失败cleanup另暴露已关闭authority后异步peerDied写库的既有问题，证据保留，未修。
- 当前重建命令使用三个显式`--platform-libc/--platform-art/--platform-linker`参数，unified session见当前对话。测试期间勿改构建输入；run后立即独立验证/存档。后续真实P0/P2/P4、首次Application/factory身份、各manager路由、审批UNKNOWN与最终性能和完整product包验收仍未完成。

日期：2026-10-16。目标：完整替换产品中的 Frida Hook 依赖；固定 Native/ART 核心已分项验证，产品迁移仍未完成。

**接手入口：[当前进度与 Agent 交接文档](docs/self-hook-handoff-2026-10-15.md)。** 用户已要求全部完成后再停止，持续Goal为active；最新结果见 [H5证据](docs/evidence/self-hook-h5-2026-10-15/README.md)。下文各批次只证明对应构建与范围。

用户已明确选择第一种：客体代码执行前初始化，之后仍支持动态启停Hook；任意既有AOT在途帧迁移不再是产品验收要求。最新H4a完成真实成员清点、2048槽容量、F1 Service访问及11组同构建独立验收。完整替换、其余固定适配/真实安装器和产品控制链仍未完成，产品未切换Frida。

方案：[docs/self-hook-design.md](docs/self-hook-design.md)。迁移范围：[docs/self-hook-adapters.md](docs/self-hook-adapters.md)。


### H13 最新复测结果（覆盖上面的“正在重建”）

当前没有构建或设备测试在运行。所有完整P0均尚未通过；完整产品目标未完成。

- 初始化顺序修复已经进入实际Runtime：前11项真实安装已执行至PACKAGES完成，未开放客体。第12项STORAGE准备阶段失败，后13项尚未执行。错误不是暂停线程超时：`prepare=3(TP_FIXED_LIBC_RELAY), relayStage=1, relayErrno=12(ENOMEM), activate=1未尝试, pause=0/observed0`。
- 先保留单一近地址分配规则并提取为`native/near_address.c/.h`，由ART owner leaf、linker notifier、Native export和fixed libc共用。扫描真实maps的127MiB双向完整gap，只做一次MAP_FIXED_NOREPLACE精确占用；拒绝竞争/异常地址，不增加后端。fixed libc旧的仅向下96MiB搜索已移除，但仍复现准备失败。因此**双向搜索并未解决完整Runtime的存储接入**，不要宣称修复成功。
- 新增fixed libc准备阶段、errno及真实pause snapshot诊断；snapshot零初始化。完整产品Native编译、全部固定平台/模板/产品暂停临界区二进制审计仍通过。
- P0记录：`p0-second` APK cad9c583，run p0-2845586d3ed04e10；`p0-storage-diagnostic` APK814f7a7f；`p0-near-address` APK9a0fb1f2；最新 `p0-relay-detail` APK `87ae66c7d63786381b1241213cd8a2ce3d9f59468b703a728e1bb43d110836e8`，完整runId见该目录device-observation.json。全部为失败证据，实物均归档在product证据artifacts。最新bootstrap-log.txt按自有UID和run时间留存。
- 后续建议先去掉**固定libc替换对近地址跳转页的结构性依赖**：这十个精确syscall wrapper不调用原wrapper，已有完整两页原像与一次暂停发布，可研究保持BTI后的4字absolute LDR x16/BR x16/literal，不必为存储另造原调用trampoline。**尚未实现，须先证明固定入口20字节窗口的符号/重定位/直接入边及ABI/BTI、再更新探针和独立页字节核验，不能直接冒充支持。**
- 已做只读初查：固定libc全部SHF_EXECINSTR段157336条32位指令中，B/BL、B.cond、CBZ/CBNZ、TBZ/TBNZ没有直接目标落入十个`entry+4..entry+20`窗口。还缺符号/重定位、独立汇编与设备原调用/并发/恢复证明。不要只据这一次初查实现宽补丁。
- P0失败cleanup的AUTHORITY_CLOSED仍未修，原因待收口失败startup的真实death回执/重复peerDied后再关闭Store，不能吞异常。正式workbench Engine上次46c04c35包已经旧，不要安装该包冒充最新源验收。

## H9/H10 最新

- [x] 完整4096方法预算真机通过，8192 Hook调用/4096停用调用/132writer事件，4096 owner根观测移动；同构建构造26和JNI SDK31回归通过。
- [x] manager1270重测7.166秒，未证明比旧7.041秒改善；不计最终产品性能达标。
- [ ] 新四类manager固定构造/字段和typed路由已实现，总1523入口；SDK201编译与主机检查通过，当前自有设备验收中（session46896）。
- [ ] Actor/PM/TrafficStats/ChannelClose/UI/Failure真实接线、Engine25项、真实审批UNKNOWN、产品Native构建IO合并、去Gadget与最终性能。

## H8/H9 当前

- [x] F6固定Service14/Provider28/Receiver3，共45真实入口，16项真机检查与45个boot根通过；产品回调已接线，Engine尚未调用。
- [x] 其余22类完整2848声明已采集，未授予任意Hook准入；固定总量已超过2130。
- [ ] managed预算4096及FastNative owner比较优化正在构建/验收；Native入站仍1024。当前build session70487。
- [ ] Activity、包管理、其余系统门面接线、完整25项计划、真实审批UNKNOWN与最终Engine/构建去Gadget及性能。

## H6/H7 当前状态

- [x] 真实1270 manager完整安装：1262项prepare6.993秒/publish27.75毫秒；完整2048 batch容量与GC roots验证通过。
- [x] ApplicationAdapter.begin 构造前pending逻辑身份、factory/guest八阶段、逻辑processName与物理host分离，独立14项通过。
- [x] JNI私有寄存器传输：普通/Fast/Critical/实例真实声明、18个ABI参数、101回调、GC/异常/LocalFrame，独立33项通过。尚非Native声明SDK。
- [x] Native声明SDK：独立loader、普通/Fast/Critical各32参数、动态注册/注销、384并发/GC调用、395原生调用、lazy named/未解析/在途停用，独立30项通过。target data由ART管理，原调用保留同declaring class Native备份帧。
- [x] 产品Java backend Native声明：16方法/4 Native声明/43检查；SDK混合寄存器31项/396原生调用无错；同构建混合19项、构造前Application14项回归通过。
- [ ] 真实25项bootstrap、审批UNKNOWN链、Engine/build去Gadget及产品性能验收。

## H6 固定安装器与构造入口（历史）

- [x] `RuntimeManagedInstaller` 实际使用 ManagedHook 发布固定回调；部分准备/发布失败直接进程终止，无空安装器或回退。组件/manager typed installer 共用这一份所有权。
- [x] 六类 manager 的完整声明 SHA 固定校验，只有已核验清单中的实例方法可作为 boot Hook；manager installer 额外要求完整方法集合相等。
- [x] 自有设备全部1270个 manager入口真实安装并校验。`67b2908f…` run `651caf5f-a626-46d4-8308-638aca1a7b71`：15项/原逐项流程，1262新增项32.8秒。
- [x] 构造入口保留真实Constructor标志、ClassExt备份与原调用，成功必须完成原构造；原构造异常不能吞掉后返回成功对象。`b3daf8d9…` 23项独立通过。
- [x] prepareBatch统一整批预检与helper ClassLoader；`198af6cd…` run `83b80984-a7ed-4ab0-a1be-296b4f0b3268` 构造/普通方法混合26项通过；run `69790829-0ead-4e7e-b19e-585fcc218a24`全部1270安装15项通过。1262项35.2秒（准备23.3/发布11.9），没有证明共用loader提升性能。
- [x] 一次SuspendAll整批发布/退休与锁内ClassExt根核验已通过：`7d7c81f5…`构造26项、1270入口15项、产品混合19项。1262项publish从11.9秒降至28.4毫秒，总23.8秒，prepare仍23.8秒。
- [ ] 当前加入按ABI形状/槽号十进制宽度复用的批内DEX模板；79项主机检查/36份独立dexdump通过，尚待设备验收。
- [ ] 完整产品固定安装计划、构造前逻辑身份、审批/UNKNOWN、Java native JNI独立路径和最终性能/去Gadget仍未完成。

## H5 Native模块生命周期

- [x] 固定linker notifier真实安装；完整寄存器保留、sealed ELF/FDE、同一park窗口初次snapshot与发布、精确路径generation与CAS引用保护。
- [x] 自有设备47项、4线程400次load/call/close通过；重载旧代次拒绝、重复引用、无效ELF与缺失重定位失败通过。[H5证据](docs/evidence/self-hook-h5-2026-10-15/README.md)。
- [x] Native活动引用与dlclose冲突：完整witness/系统PID退出信息独立匹配，直接exit_group198，无成功ACK。
- [x] Native入站ABI78项/13类型组/4000并发嵌套/1024槽；原产品4个真实导出含PAC变体各32项/2000并发通过。
- [x] shared-page prepared跨restore与activation迟到回调25项；只剩invocation lease时卸载EXIT_SELF198通过；Java/JNI Native核心19项/400 foreign pthread通过。
- [x] NativeHookValues加入脚本主机ABI合同，671项通过；复用原ScriptValues类型与范围校验。
- [x] live Build ID/可执行段与prefix准入、多实例歧义及notifier epoch已通过独立设备验收；同路径双实例modules总48项通过。
- [x] 产品RuntimeNativeObservation/Backend真实本体31项、4导出/2次激活/400 foreign调用通过；RuntimeSelfHookPlatform混合计划19项、1 Java/2 Native同会话通过。
- [x] 普通编译器自然叶子入口30项/6导出、3168字节末页恢复；4096项目录10项及空目录8项；JNI25项/400 foreign调用与FPCR/FPSR隔离，均同构建独立通过。SDK195源码编译通过。
- [ ] 真实审批/UNKNOWN控制链、Java native声明独立JNI入口、固定安装器、逻辑Application构造期及性能仍缺。Engine/build仍使用Gadget。

## H4b / H5 当前增量

- [x] F2/F4/F5通过同一个固定Framework注册表接入；24类/21字段，32项自有设备检查通过，含self-broadcast、Provider handle、6种manager构造及逻辑历史值。
- [x] 产品接口由RuntimeSelfHookFixedFramework承接，原Runtime准备流程绑定这些访问器；真实Hook安装器仍待接入，不能据此宣称25项bootstrap完成。
- [x] 从Bridge提取唯一RuntimeJavaObservation，保存Method及实际loader；RuntimeJavaHookBackend复用原脚本控制生命周期，以Method身份保留Handle。
- [x] 产品Java backend本体已在独立loader设备夹具通过34项/12方法/2次激活，包含所有SDK Java标量、原异常、无初始化观察、失效及句柄复用。产品authority/审批transport和Native backend明确尚未连接。
- [x] JavaHookValues主机完整类型边界纳入633项脚本合同检查；旧脚本夹具改为复用它，JIT/AOT各473项通过。SDK编译189源通过。
- [ ] 真实基础安装器、构造前逻辑身份、完整Runtime/控制面切换、Native模块通知/入站ABI/通用安装/JNI native method及性能。
- [x] linker清理分支、通知驱动generation和卸载同步fail-stop已完成分项验收，见上方H5。

## H4a 产品容量与固定成员

- [x] 用户选择启动前初始化已纳入契约；不再要求任意既有AOT帧迁移，运行中动态Hook保持目标范围。
- [x] 固定设备六类manager真实声明清点1270个实例方法；保留完整描述符/flags/hash，未执行系统服务，未据此开放全部boot Hook。
- [x] 同步扩展Java、helper DEX、Native entry/backup/ClassExt根、writer登记表与汇编leaf到2048；Java与已加载Native容量在准入前核对。
- [x] 真机2048不同方法/16声明类、4096次Hook调用、2048次停用后调用、超预算拒绝、移动GC后全部根及最后一槽132次writer事件通过；主机2066项/80万并发选择及35 LLVM oracle通过。
- [x] F1固定Service两字段/一方法、真实JNI调用、产品FrameworkCalls委托/准备已实现；自有设备Proxy夹具23项通过，不等于真实系统Service生命周期验收。
- [x] 修复全部探针结果发布竞态：独占claim、临时文件写完/fsync、原子发布；6发布者只提交一次，当前11个run均验证commit marker。
- [x] 当前`e6941e9b…`构建11组独立验收通过；JIT/AOT ABI各2048项、脚本各473项，启动边界、Framework/Application、动态coverage及上述容量/F1均有单独回执。
- [ ] F2/F4/F5固定成员、实际基础安装器、Runtime首次guest执行门禁、完整控制链及最终无Frida产品验收。
- [ ] 产品启动/稳态性能；全容量压力总耗时不是产品性能通过，安装策略仍需真实产品测量。

## H3m 当前执行边界

- [x] 独立小型AOT调用者在JNI屏障中跨越Hook激活：旧帧返回10、新调用110，relay正确，原调用桥只计一次；保留反例，不计完整覆盖通过。
- [x] `ManagedHook.initializeProcess()`复用同一writer/解释租约，在Handle准备前显式建立进程策略；失败闭锁、重复及晚调用拒绝；报告明确不覆盖既有编译帧。
- [x] 同一APK提前初始化对照：进入帧前真实入口变为解释桥，恢复调用与新调用均110，原调用桥两次；独立验收通过。
- [x] Runtime安装计划新增首项PROCESS_EXECUTION_POLICY，主机门禁293项及166个SDK源码类型检查通过；产品Engine尚未接入。
- [x] 当前`ff16b593…`构建：JIT/AOT ABI各2048项/400轮并发根观察，脚本各473项；writer各132次事件；JIT组合动态loader/boot目标/entry发布通过。
- [x] 14项验收器篡改检查，错误值/次数、伪造旧帧覆盖或产品完成均拒绝。
- [x] 用户明确选择启动边界：客体代码执行前初始化，之后动态添加/更新/启停Hook。既有AOT在途帧反例继续保留为能力边界，不再阻塞该产品范围。
- [ ] 真实Runtime执行边界、所有必要Framework安装器、构造期逻辑身份、完整writer/未来image、控制面、性能与去Frida产品验收。

## H3l 当前入口发布

- [x] 编译时嵌入近地址ELF，无TEXTREL/依赖/init；回跳与registry只在文件字节中填写，再memfd完全封存后加载，23个PC均有加载器可见FDE。
- [x] 从旧entry+4改为entry+8：PAC和FP/LR保存保留原位置，leaf只重放x20/x19保存，所有PC为SP+32；不存在短暂CFA切换状态，不依赖随机采齐该单指令。
- [x] 自有入口40万次调用、10万次desired发布、512次真实异步展开、单字页提交及完整原文件页恢复，整份ELF字节独立绑定。
- [x] 真实固定UpdateEntryPoints：64次Reinitialize、64次UpdateMethodsCodeImpl、4次JitCodeCache全局失效均执行原逻辑；JIT回归127405次调用无错，完整页仅entry+8单字变化。
- [x] 实测源ART页无BTI；严格保持源页/保存原文件视图/发布页属性一致，新ELF有BTI。此前错误要求源页必须BTI的失败记录保留，不追认通过。
- [x] 当前统一CFA构建ABI JIT 2048项、400轮并发根观察及381541次并发调用通过。
- [ ] 全部同构建JIT/AOT/动态loader/脚本矩阵。组合运行发现原一阶段tp_park_init会拒绝第二个消费者，已新增核对实际handler的共享进程准备接口，保留init单次语义；新构建验证中。
- [ ] obsolete/AddImageSpace/vendor等完整写入闭包、产品固定成员/构造期身份/控制面和性能；不得将中央writer标记当成完整覆盖。

## H3k / H2e 当前增量

- [x] 基于真实晚加载JIT绕过反例，将ManagedHook唯一编译策略改为首次enable持有进程解释租约；prepare不claim，外来级别拒绝，停用后保留到退出，不设置目标CompileDontBother。见[策略](docs/self-hook-process-interpreter.md)。
- [x] 当前核心策略ABI JIT/AOT各2048项、移动参数/备份根和400轮并发根观察通过；脚本JIT/AOT各473项通过。
- [x] 实际Class.classLoader用于ART分配器，覆盖独立loader及真实boot null；同名双loader不串拦、boot currentApplication原调用及物理身份通过。
- [x] 调用者Native只读观察器接入设备，激活前及并发GC后核对真实owner、DEX index和quick；候选分析复用同一实际扩展预算。
- [x] Native scalar汇编实机执行：13组编译器对照、490检查、4000并发errno无错；通用Hook/指针等仍未完成。
- [ ] 真实ART writer发布与完整直接写入闭包、未来AOT/image、caller-sensitive、产品性能及其余产品安装器。

## H3j / H5 当前增量

证据：[H3j索引](docs/evidence/self-hook-h3j-2026-10-15/README.md)。以下是分项实现，不代表产品完成。

- [x] 修复leaf+76展开边界：三个恒定状态CIE/FDE、LLVM核对22个PC、512次真实异步采样覆盖两种CFA状态并恢复准确调用点。
- [x] 复现并保留晚加载JIT绕过：目标relay未丢失，逐方法deopt-set不能阻止后续caller内联。
- [x] 进程级解释执行独立实验101002次调用全部拦截、无已知caller清单；仅实验通过，产品策略与性能矩阵尚未完成。

- [x] resolved static/direct caller按准确owner绑定；17项D8及55项多DEX/loader检查通过。
- [x] LoadedClassInventory接入自有设备；约2.18万类、同名双loader、两个Class实际移动、24轮GC并发枚举通过；预算耗尽不返回部分结果。
- [x] owner policy改成现有entry的原子desired只读视图；ManagedHook在同一暂停窗口提交desired/quick/active；ABI JIT 2048项回归通过。
- [x] 保留原PAC签名的预链接leaf夹具通过40万调用、10万状态发布及原CAS/SP检查；LLVM核对22条指令及CFI；512次实际异步采样覆盖两种CFA状态。真实ART拦截与动态代码发布仍未完成。
- [x] 自有脚本源码到真实ManagedHook链路JIT/AOT各473项通过，包含参数CAS、原调用/原异常、DRAINING与退休；产品Platform/审批链仍未接入。
- [x] 修复scalar夹具pthread_join失败的worker生命周期问题；H2e设备ABI执行已通过。
- [ ] 完整未来类/代码发布和系统writer闭包、boot/guest loader及剩余产品适配；产品仍使用Frida。

## H0 方案审查

- [x] 核对当前产品 Gadget/Java Bridge/Gum/脚本运行时依赖。
- [x] 独立审查 ART、并发退休、反射原帧、模块时序、脚本语义和产物证明的遗漏。
- [x] 固定首版平台范围；缺能力拒绝，不做后端 fallback。
- [x] 明确复用 Broker、操作身份、审批和证据契约，建立必要适配清单。

## H1 基础实现（已完成）

- [x] 实现纯 C AArch64 指令规划与明确拒绝；失败不发布部分结果。
- [x] 覆盖分支范围、PC 相对指令、块内目标、BTI、非法指令、地址溢出及重叠测试。
- [x] 用 NDK 汇编器独立核验编码，不使用第三方 Hook 引擎。
- [x] 构建独立 non-debuggable 探针，仅打包 self-hook 与自己的测试代码。
- [x] 采集 fingerprint、API/ABI、页大小、libart/libc/linker SHA 和进程 maps。
- [x] 真机执行共享核心检查与 RW→RX 合成指令搬迁，回执绑定 runId/APK。
- [x] 记录源码/产物/依赖证据，明确没有修改活跃代码、没有完成真实 Hook。

## H2 Native Hook

当前：H2a v2 自有 ELF / 固定 ABI / 协作域的真实 Hook 已通过真机验收，H2 整体仍未完成。[当前发布模型](docs/self-hook-page-publication.md)；[通过证据](docs/evidence/self-hook-h2a-v2-2026-10-15/1a1f301c-314b-4b00-aeb6-1227fbf7694d/README.md)。

- [x] 初版验证文件页写后恢复 RX 被拒绝，保留 EACCES 失败证据；该路径已被新发布模型替换，不作为 fallback 保留。
- [x] 实现来源文件身份与匿名执行页所有权分离，准备 RX 后在协作域排空期间同址发布，关闭时恢复原文件映射。
- [x] 真机完成 45 项真实 Hook 检查：安装/原调用/停用、候选页拒绝、排空超时、重入/旧句柄拒绝、实际卸载与重载。
- [x] 四线程 50 轮安装/停用，360,209 次调用的值、epoch 状态、原调用次数、errno 检查通过。
- [x] 104 次匿名页发布、3 次文件映射恢复；保存视图/候选页峰值 3 页，关闭后临时及已发布匿名页均归零。
- [x] 绑定当前源码、APK、平台和四阶段映射快照；原文件 SHA 不变，恢复 inode/device/offset 后释放观察租约并确认卸载。
- [x] H2b：非协作 Native 线程暂停原语 15 项检查通过，包含线程创建、信号阻塞拒绝、迟到信号与新 epoch 恢复；JIT/AOT 回归通过。与页发布的组合已在 H2c/H2d 继续验证。[设计](docs/self-hook-native-thread-park.md) / [证据](docs/evidence/self-hook-h2b-2026-10-15/README.md)。
- [x] H2c：暂停与最小页提交组合，19 项检查通过；JIT/AOT 各 50 轮、57,306,154 / 58,341,866 次直接 ELF 调用无错误。拒绝路径、旧回调在途停用、join 后文件映射恢复和实际卸载通过。[设计](docs/self-hook-noncooperative-publication.md) / [证据](docs/evidence/self-hook-h2c-2026-10-15/README.md)。
- [x] H2d：固定 libc 十入口、两个完整共享页、BTI 保留、两页统一提交与文件映射恢复，复用已有 C 存储策略；JIT/AOT 均通过 24+24 项检查、371,260 / 311,165 次四线程调用无错误。第二页校验失败时两页不变、全页字节和原文件身份恢复已独立核验。[设计](docs/self-hook-fixed-libc.md) / [证据](docs/evidence/self-hook-h2d-2026-10-15/README.md)。

以下是 H2 尚未完成的通用范围，不能继承自有 fixture 的结论：

- [ ] 产品所需目标入口、签名、加载代次、覆盖窗口与 Hook 所有权。
- [ ] 非专属 ELF 页面、BTI/PAC/MTE 和非协作 Native 线程静止协议。
- [ ] replacement / enter-leave 分离，以及更广 ABI 的原调用验证。
- [ ] 非协作调用的发布/退休、跳板尚未计数窗口、预算耗尽和长期稳定性。
- [ ] 第三方模块卸载/重载、链接器锁重入、失效与发布中故障终止验证。
- [ ] 明确拒绝未知 ABI、结构体、varargs、异常展开及信号场景；逐项扩展。

## H3 ART 风险门槛

- [x] 初次只读清点实际设备 libart 导出符号并绑定 SHA；进程内访问与锁语义由下述 H3a 继续验证。
- [x] H3a：实测普通 dlopen/dlsym 无法解析所需 ART 符号；建立实际 libart SHA / RVA / 完整函数字节契约，验证 10 次只读 SuspendAll/ResumeAll 与 15 项 JNI/方法身份检查。
- [x] H3b：实际 Field.get Native 入口替换，保持原 managed frame；62 项检查、四线程 20 轮切换通过。显式观测编译入口后，JIT/AOT 各 1,000,000 次调用全部拦截；完整反射变体仍未完成。[证据索引](docs/evidence/self-hook-h3b-2026-10-15/README.md)。
- [x] H3c：两个自有 static int(int) managed 方法原调用备份与替换，25 项检查通过；JIT/AOT 均复现已编译 caller 10,000 次全部绕过，选择性反优化该 caller 后全部拦截。[设计](docs/self-hook-managed-methods.md) / [证据](docs/evidence/self-hook-h3c-2026-10-15/README.md)。
- [x] H3d：DEX 039 精确静态调用链索引，独立 D8 Oracle 及主机 14 项检查通过；JIT/AOT 真机按 DEX 与 loader 身份自动找到 caller，并完成反优化对照。[设计](docs/self-hook-caller-index.md) / [证据](docs/evidence/self-hook-h3d-2026-10-15/README.md)。
- [x] H3e：Field.get/set 原始 Native 帧与不可变逻辑 Application 身份核心，JIT/AOT 各 76 项检查、一百万次精确读写对、约 1390 万次并发读取通过。真实 ActivityThread 与产品接入仍待完成。[设计](docs/self-hook-reflection-view.md) / [证据](docs/evidence/self-hook-h3e-2026-10-15/README.md)。
- [x] H3f 成员访问：普通 JNI 清点发现 4/20 项非 SDK 拒绝；固定 ART 注册表已通过 7 类 / 20 成员、16 项普通 JNI ID 校准和真实物理归属核验。仅固定 kPointer 表示，无豁免、无后端切换。[设计与证据](docs/self-hook-framework-access.md)。
- [x] H3f 构造与生命周期：独立 loader 的自有 Application 在真实逻辑 LoadedApk/Context 中完成 attach/onCreate；JIT/AOT 各一百万次真实字段读写对、四万次并发读取、11 次物理归属检查通过。factory/Application 构造与生命周期均一次；物理 host 保持不变。[设计](docs/self-hook-application-adapter.md) / [证据](docs/evidence/self-hook-h3f-2026-10-15/README.md)。
- [x] H3g 固定 ABI：保持目标 managed 元数据的独立 Native helper；JIT、重复冷启动 JIT、AOT 各 1,071 项检查通过，旧帧与在途停用、42 次切换和原调用次数正确。[设计](docs/self-hook-managed-storage.md)。
- [x] 删除旧声明槽备份，改用 ART LinearAlloc 与 ClassExt Native 根；H3c/H3g 共用存储，H3c 两个目标的 JIT/AOT 回归通过。未覆盖通用 ABI 或移动 GC 证明。
- [x] 同一 APK 的 JIT 后续进程实际加载持久化 base.art，重复完整 Hook 通过；AOT 完成独立冷启动回归。该构建未清除编译缓存。speed AOT 最终进程没有 runtime-image 映射，不计入映像检查通过。[证据](docs/evidence/self-hook-h3g-2026-10-15/README.md)。
- [ ] 通用 Native C ABI、caller-sensitive Java 路径、loader 卸载、预算与长期稳定性。
- [x] H3h 进程内 Java ABI：生成 managed helper、类型化 JNI 原调用；19 种签名场景，JIT/AOT 各 2,048 项检查。含实例/同步/对象/数组/void、宽整数/浮点、递归、原异常、原调用最多一次和类初始化时序。通用 Native C ABI 仍未完成。
- [x] H3h 移动 GC：JIT/AOT 均观测到 4 个在途参数对象和 19 个备份声明类引用移动，根关系及返回对象身份正确；四线程 20 轮切换分别 454,383 / 705,039 次调用无错误。
- [x] H3h 冷启动编译模式已区分（历史诊断保留）：旧 `714c16a0-8e45-4625-85c3-807e140a9d24` 无最后方法快照，根因未确认；新增 `df4fb694-b5f4-475c-bdb2-dcb5d5901c36` 明确为系统 first-use/speed-profile 后已进入 AOT，与探针定向 JIT 预期不符，flags 没有 bit25。已把 cold 实际编译模式验收与严格 JIT/AOT 矩阵分开。目标编译所有权改用进程内 deopt 集合，以消除临时 CompileDontBother 进入映像的结构风险；该风险不能再表述为已证明的本次失败根因。
- [x] 静态/虚调用/interface/super 的保守候选图与通用签名/defining loader 绑定已实现；独立多 DEX 主机 55 项检查通过。完整 Runtime 覆盖仍明确为 false，native 只读观察器尚未接入设备验收。
- [x] 入口所有权纯 C 注册/路由构件：82 项 ASan/UBSan、80 万次并发选择、35 项固定 ELF/LLVM 审计通过；不等于已拦截所有入口写入。
- [x] 传统 A-key/SP 的独立 PAC 规划器与自有预链接夹具：613 主机检查、31 LLVM 检查及 7 个 ART 前导离线检查通过。未知/PAuth_LR/B-key 拒绝，尚未设备运行或接入 ART。
- [ ] PAC 实际平台准入、动态 FDE/异步展开、系统级失效与直接写入口闭包；不能只凭普通 GC 通过就启用产品。
- [ ] 自动识别受影响调用者、后续 AOT 类加载及在途帧迁移；不能将单个已知 caller 反优化当作完整内联覆盖。

- [x] 固定 libart 身份、已用结构/符号证据及 non-debuggable 进程内 Native/managed 入口变更可行性。
- [ ] 应用方法与 boot-class 必要拦截；ClassLoader 和原始反射访问语义。
- [ ] JNI 引用、原异常、类初始化、递归、监视器和调用线程身份。
- [ ] JIT/AOT/内联实际证据；GC/反优化/激活并发，不能仅以预热次数代替。

## H4–H6 产品迁移

H3i新增验收（不等于产品迁移完成）：

- [x] 以新构建关闭cold独立验收缺口；19个不移动owner均绑定真实自有AOT image，并关联同构建先前owner移动证明。历史失败保留，不追认旧运行通过。
- [x] ClassExt/方法owner/DexCache读取使用固定ART根读屏障，完整验证两个新增函数体；JIT/AOT/cold各400轮根观察，三次分别经历37/34/33次GC，cold窗口38次屏障地址修正，无根关系错误。
- [x] AOT代码文件与实际mapped APK路径/安装代次精确绑定，覆盖相邻odex与dalvik-cache；17个根证据、10+10个Java/Python路径验收测试通过。

- [x] H4 三批 Java 适配与安装门禁源码：Application/QueuedWork、24 项完整安装计划、loader 身份、Service/Receiver/Provider 回调、身份及 observer 边界；281+11+85+117 项主机检查。真实固定成员/安装器/Engine 切换未完成，不能视为产品可用。
- [x] F0 只读 PhysicalApplication JNI：12-field registry和独立探针Native已接入；真机Application JIT/AOT各409次固定JNI物理检查，其中400次跨线程；委托selected loader生命周期通过。79 C mock、15 HotSpot场景/53断言及166源SDK37编译回归通过。产品Framework安装器/构造期逻辑身份仍未完成。
- [x] 六类系统 manager 门面共81项能力，新增215+45项主机检查；实际 family 安装完成才允许构造 facade。NetworkStats/WifiP2p/Notification/Shortcut 与真实安装仍待完成。
- [x] H5 进程内 source→受限 AST/完整计划编译、ABI/参数/调用生命周期与既有 JSON 控制协议：593 项主机检查，含现有 SDK/Babel 正负 Oracle、IEEE 对照、8线程8,000次调用、token/期限/代次/参数CAS/UNKNOWN无ACK。未安装真实产品 backend，首版语法 profile 与其余 Babel 语法差异仍明示。
- [x] H5自有Android真实ManagedHook集成夹具JIT/AOT各473项通过。
- [ ] H5真实产品Platform接入，控制/审批/UNKNOWN端到端设备验收。
- [ ] 按迁移清单实现全部必要适配，统一 bootstrap ready 门禁。
- [x] 存储适配的自研固定 Native 入口核心与既有 C 策略在独立探针中验证；Runtime bootstrap 产品接入仍未完成。
- [ ] 独立脚本执行器复用受限 AST、精度/预算/版本/原函数最多一次契约。
- [ ] 接入现有 Hook Bridge、工作台与 MCP，验证审批与 UNKNOWN 对账。
- [ ] 当前构建完成组件/存储/Hook 回归及失败、进程死亡和恢复门槛。
- [ ] 产品产物、构建来源及运行时均无 Frida，才删除产品旧依赖路径。
本轮验收只使用自有夹具与自有环境。第三方应用业务验收不在本轮授权范围内，也不能继承自研夹具结论。

## 并行实施所有权

- 主 agent：共享 self-hook 核心/API、构建入口、所有设备与 APK 操作、集成证据和 task.md。
- `caller_coverage`（已停止）：coverage 核心/观察器、PAC、loaded-class 与独立夹具；最新269 C++/14 Java/55 caller检查通过，未设备运行、未覆盖未来类发布。
- `runtime_adapters`（已完成本批）：RuntimeSelfHook 适配、RuntimeBridge、组件/manager测试；不操作设备，不提前 ready。
- `script_control`（已停止）：脚本控制593主机检查，自有Android集成夹具仅主机14项；另有Native scalar ABI 2103主机检查，汇编未执行。设备运行前必须修复 scalar_fixture.c 的 pthread_join 失败导致 worker 栈状态可能提前失效的问题。

## 本轮记录

方案审查完成，9 项缺口及修订已写入方案和适配清单。首批实现包括纯 C 规划器、独立 LLVM 对照、固定平台探针、构建与证据核验脚本；复审修复两类错误接受，并补齐旧报告防误用和源码版本绑定。

- 主机：92/92 核心检查通过，ASan/UBSan 无报告；LLVM 汇编器对照 38/38 通过。
- 构建：独立 APK、打包允许清单、Native 依赖和开发签名检查通过。
- 设备：用户允许安装后，以新 runId `788d110d-b812-43ff-a06e-8077b826acba` 重试成功。普通 non-debuggable 进程（UID 10403）中 92/92 核心检查、16/16 合成页检查通过；RW→RX、实际指令执行、平台身份和映射均已核验。
- 证据核验：当前源码、主机被测源码、构建身份、实际 APK/Native SHA、runId、平台和 maps 一致；探针没有 Frida/Gadget/Gum 映射。H1 完成，不代表真实 Hook 或产品去 Frida 完成。
- [通过证据](docs/evidence/self-hook-h1-2026-10-15/788d110d-b812-43ff-a06e-8077b826acba/README.md)；[保留的首次安装拒绝记录](docs/evidence/self-hook-h1-2026-10-15/67663c4a-9e21-473a-94f1-bfcd9990d92b/installation.json)；[复现步骤](android/self-hook/README.md)。

H2a 初版：发现文件页写入后无法恢复 RX（errno 13），在未修改目标入口前明确拒绝并卸载模块。[历史失败证据](docs/evidence/self-hook-h2a-2026-10-15/a48ab268-ec77-4e87-ae59-7fa0e40f8589/README.md)。

H2a v2 本轮：唯一匿名页发布路径在普通 non-debuggable 进程内通过，45/45 Hook 检查、92/92 核心检查、16/16 合成页检查通过；此前主机 92+38 报告与本次源码一致。没有修改工作台、引擎或客体应用。

H3f：普通 JNI 的四项非 SDK 拒绝已明确记录；固定 kPointer ART 注册表与逻辑 Application 适配均在独立探针通过。当前构建还完成 H1–H3e JIT/AOT 回归，固定 libc 四线程调用 370,254 / 356,218 次无错误；非协作 ELF 调用 47,027,447 / 48,572,823 次无错误。

H3g 历史失败：`518e7c75-edb6-4c86-9ff5-99abfdb338ad` 当次通过后，`a35bdc53-1b9d-43a9-a3de-0654f5cd42b9` 在 `ClassLinker::AddImageSpace` 冷启动崩溃。旧构建恢复时清理缓存的动作不计入验收。当前已删除声明槽移植，APK `20dbd17d…953d1277` 的两次 JIT 与一次 AOT 入口检查通过；两个 JIT 后续进程的真实应用映像映射核验通过。核心 JIT/AOT 回归的固定 libc 并发调用分别 367,144 / 358,919 次，非协作 ELF 调用分别 51,377,037 / 64,422,771 次，均无错误；逻辑 Application 两组各一百万次读写对与 11 次物理归属检查通过。

当前实施顺序：通用原调用/helper ABI 与移动 GC → 完整 ART 调用者与在途帧门槛 → Runtime 自有 Bridge 直接实现及 H4 必要入口适配 → H5 脚本与控制面 → H6 去依赖和自有环境产品验收。H2 通用范围及 H3–H6 仍未完成，当前产品仍依赖 Frida。所有实施限定在授权源码与自有测试范围，不执行第三方应用验证或危害网络安全的操作。
