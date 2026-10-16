# P4 客体 Hook 执行契约

状态（2026-10-15）：Java/Native、参数更新、在途停用及产品 MCP 已取得限定场景证据；完整 P4 仍未通过。最新音频构建的 P4 首次在 ART 栈遍历崩溃，原样重跑 98 项通过，正在修复反优化线程边界并补并发门槛。延续 P1–P6 授权，不使用 VirtualApp，不启用旧 GuestService/GameRuntime。

## 复用边界

复用 `src/draft.ts` 的受限 AST、`plan.ts` 的符号与计划检查、`parameters.ts` 的参数约束、`evidence.ts` 的 Java/Native 类型检查，以及已固定版本的 Gadget。复用当前 RuntimeEngine 中同一个适配脚本和同一个 ProcessSession；不能另装一个可以覆盖组件路由 Hook 的无归属脚本。

旧 `android/agent/index.ts` 仅能选两个自研固定目标，并且部署顺序是先 install 再 commit。该适配器不能直接接入生产工作台：准备失败、部分激活、迟到控制消息和在途卸载都没有完整契约。可以复用类型转换和“原函数最多一次”的规则，但需要重新实现通用目标绑定与生命周期。

## 身份与证据

每个准备结果固定 space、package、installGeneration、artifact descriptor SHA、processGeneration、真实 PID、计划源码 SHA、参数版本。Evidence ID 与完整身份摘要绑定；UI 显示的是哪个进程实际确认，不能把任一进程的成功推广为全局成功。

- Java：用实际 Class 对象、声明方法与重载签名、ClassLoader 对象身份绑定。loader 身份由运行进程的对象身份表分配，不用 toString/可覆写 equals/hashCode 推导。不从宿主 ClassLoader 搜索同名目标作为替代。只支持 SDK 当前可表达的同步基本类型与 String；构造器、对象句柄、异步回调必须另设能力，不能隐式转换。
- Native：只选当前制品允许的模块，检查真实映射路径、实际 Build ID/制品 SHA、模块加载代次与地址。符号名不包含函数签名；签名必须由明确证据或用户确认提供，不能从名字猜测。无符号、模块未载入或签名不明均无法激活。
- 模块卸载和进程死亡使该次准备失效。重新加载/重启不自动复用地址、loader 或旧 ACK。

## 准备、激活和失效

1. Authority 接受唯一 operationId，记录目标身份和请求摘要；在短锁内确认当前 session/owner，然后锁外发 Worker 控制，允许客体回调重入 Broker。
2. Worker prepare 只解析 AST、绑定真实符号、检查预算与类型，不修改方法。返回绑定身份和 prepare token。源码检查通过不等于 Hook 生效。
3. activate 必须携带同一 token、进程代次、源码/参数摘要。所有目标准备完后才发布本地版本，并在实际安装和 flush 后返回 ACK。
4. 如果部分目标已经改变但激活不能完整确认，进程失效并终止，Authority 保留 UNKNOWN/失败证据；不恢复调用原函数伪装操作成功。没有写入的准备失败可以明确 FAILED。
5. 重复请求只查询已存结果。ACK 丢失后不重放 activate 或业务调用；旧 owner、旧代次和旧 token 明确拒绝。

## 调用与卸载

一次调用捕获一个不可变计划/参数版本。before、original、after 的顺序固定，replace 不隐式调用原函数，原函数最多调用一次。Java 回调错误以明确 Java 异常终止本次调用；Native 回调错误不能跨 C ABI 抛 JavaScript 异常或返回虚构值，记录错误后终止该进程。

必须明确处理递归、多个线程和活动调用计数。disable 先停止接受新的计划版本，再确认旧版本无在途调用后释放回调对象；不能在回调未结束时回收 NativeCallback 或同步等待阻塞唯一 JS 执行线程。用户明确关闭 Hook 后恢复正常应用行为是操作语义，执行错误时偷偷走原函数则违反契约。

Java 原函数抛出的 Throwable 必须原对象传回，不能包装为脚本错误；before/after/replace 或 ABI 转换失败才使用显式 `HOOK_EXECUTION` 异常。控制命令在 Worker 内拒绝重入，调用线程可以在原方法运行期间继续接受参数更新和停用命令。参数更新以计划 revision 与 parameterVersion 比较交换；调用进入时捕获不可变参数对象，不能让同一次调用的 before/after 使用两个版本。停用在存在调用时返回 DRAINING，后续状态查询确认排空并卸载，不能将 DRAINING 显示为已停用。

`p4-java-03` 是当前首批证据（Runtime APK `a1ba887b30bbd66d28923b1464b83d58a594aea570eaf3b798a4fec75e9d6892`）：声明方法观察/准备不初始化目标；真实静态和实例重载、String/null、long 精度、8 线程 200 次调用、递归、原异常对象、错误时原函数次数、停用、重启后旧证据拒绝，共 40/40。它没有覆盖随后参数版本与在途停用改动，不能当作新构建通过。

后续 `p4-lifecycle-02`（Runtime APK `d5f82880e9e01e4b602bd8d9b85a94c780a49fb93953d416e02791bb812ce391`）62/62 通过，在真实闩锁暂停的原方法上验证上述参数与 DRAINING 语义。Native 初版将已卸载 Hook 的回调句柄保留到进程结束，每进程最多 32 次激活、每次最多 32 个目标；这是对“已经进入 trampoline 但尚未进入 JS 计数”的线程保留引用，不能宣称已实现任意次数热更新或回调内存回收。

Native 只接受已验证制品目录的 ELF64/AArch64 动态库。选择时校验文件摘要、GNU Build ID 与真实映射中的 note，绑定完整路径、映射基址及模块 observer 分配的加载代次；只解析该模块声明的函数导出。签名必须显式确认，类型声明本身不构成 ABI 自动推导。卸载会撤销未激活证据；活跃目标模块卸载或 Native 脚本错误会记录原因并终止进程。重新加载同一路径/Build ID 仍须重新观察与准备。

### 动态链接器与恢复边界修订

`p4-native-05/06` 中，独立 `dl_iterate_phdr` 观测已卸载库的映射数为 0，但原 Frida observer 没有及时发出移除事件。核对固定版本 [Frida 模块注册实现](https://github.com/frida/frida-gum/blob/17.23.3/gum/backend-linux/gummoduleregistry-linux.c) 与 [Android 链接器](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker.cpp)，发现 Android 的 debugger 通知先于 soinfo 列表移除，而该 Frida 分支仍枚举 soinfo。不能以等待下一次加载或状态查询来修补旧令牌生命周期。

现在由独立编写的 `hook_modules.ts` 直接读取 ELF64 DT_DEBUG 与 `r_debug/link_map`，在 RT_CONSISTENT 通知中同步记录加入/移除；不复用滞后的模块注册表。导出函数来自已验证制品的动态符号表，拒绝未声明/IFUNC/不明确导出；实际地址仍须在同一加载代次的可执行映射内。模块链形状、预算或通知处理异常会使进程失败，不能继续用旧快照。`p4-native-07` 已实际确认卸载使准备失效、重载代次改变、旧 token 被拒绝，Native 类型错误与活跃模块卸载也均记录了相应 PID 的明确终止原因；该次整体验收仍失败于系统恢复旧 Activity 的问题。

schema 19 在激活命令进入 Worker 前持久记录 session 的 hookOperation。该进程的 Hook 状态无法在新进程中自动恢复，因此旧 Activity/Service 不得自动进入新进程执行原始逻辑。Activity 的旧入口明确取消，且在加载客体 Application 之前拒绝；Service 的旧恢复明确失败。显式启动新会话仍需重新观察、准备与激活。该标记不因停用或控制端死亡而清除，避免 ACK 丢失和未知结果下重新执行旧 Intent。旧 Activity 的回调验收改用独立 requestCode，不能把其他已终止入口的迟到结果当作当前调用结果。

## 验收门槛

### Android 17 反优化的线程边界

`audio-scalar-runtime-p4-01` 在 `Instrumentation::Deoptimize → InstrumentAllThreadStacks → StackVisitor::GetMethod` SIGSEGV；同构建 `audio-scalar-runtime-p4-02` 98 项通过不能否定这个时序缺陷。固定 bridge 的原 selective deopt 路径只转为 Runnable，没有暂停其他 Java 线程。

Android 17 的 [Instrumentation::Deoptimize](https://android.googlesource.com/platform/art/+/refs/tags/android-17.0.0_r1/runtime/instrumentation.cc) 要求 mutator 锁独占；[ThreadList::SuspendAll/ResumeAll](https://android.googlesource.com/platform/art/+/refs/tags/android-17.0.0_r1/runtime/thread_list.cc) 在当前线程非 Runnable 时建立这一边界。修订路径仍从已经返回 Java 入口的 agent 控制线程执行，只进行一次 native thunk：SuspendAll → Deoptimize → ResumeAll，中间不调用 JNI/Java/JS 回调。它仅适用于已固定 API 37/ARM64，缺能力直接拒绝；不降低 Hook 要求，也不以全局解释执行替换选择性反优化。

新增四个真实 Java 工作线程，在激活期间持续递归、分配与 GC，核验激活后线程仍存活、继续进展且能结束。修订构建 `5b44a94a6d2c60a4a67476775fdca605343a7f8a7e63cba98d26652f8dce8dc7` 的 `deopt-suspend-p4-01` 至 `04` 连续四轮各 102 项通过，四个工作线程每轮合计完成约 7.7–10 万次栈/分配循环；包含原 Java/Native、在途停用、失效和异常边界。该结果支持本次并发边界修复，不等于任意应用或长期稳定性已通过。

用自研且保持原签名的客体验证任意声明目标选择，包含 Java 重载、实例/静态方法、String/null、整数边界与 64 位类型、Native 明确签名；验证实际命中和返回值、原函数计数、before/after 异常和 Native 失败退出。另测多线程、递归、在途 disable、旧进程证据、模块卸载、部分激活、ACK 丢失与内存预算。

工作台项目与脚本只能绑定到同一安装/进程契约。P4 的自研目标通过不能替代 P6 的微信/王者业务场景；真实应用的可用符号、模块装载时机和允许的 Hook 场景需要独立证据。
