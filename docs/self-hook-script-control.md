# 自研 Hook：受限脚本执行与控制接入

日期：2026-10-15。当前交付包含进程内 source → 受限 AST → 完整计划编译、Java handler 执行核心、既有 Worker JSON 命令控制器、调用生命周期和 Bridge 安装点。它已通过自有主机契约测试，尚未接入产品真实 Hook 后端，不能据此勾选 H5 或宣称产品已经去除 Frida。

## 复用边界

`src/draft.ts` 仍是 TypeScript 源码、Babel 解析和受限语言的现有规范；`plan.ts` 的证据绑定、`parameters.ts` 的参数 schema、`evidence.ts` 的 ABI 值域、`android/runtime/agent/hook_execution.ts` 的调用顺序是本实现的对照。没有引入 eval、动态 Java 类执行、任意 JavaScript 引擎或第二套 Broker/审批/操作日志。

新增代码位于 `android/runtime/src/dev/tripoder/runtime/script/`：

| 文件 | 责任 |
| --- | --- |
| `RestrictedAst.java` | 校验、复制并解释 Babel 形状的 handler AST；每次调用使用独立作用域和预算 |
| `ScriptValues.java` | 脚本值边界、不可变复制、精确 BigInteger、IEEE 数值与 ABI 范围 |
| `ParameterSpec.java` | 参数 schema、默认值、范围、Unicode 码点长度和序列化预算 |
| `HookRule.java` | 不可变规则，before → original → after 或 replace |
| `HookInvocationSession.java` | 一个已准备版本的调用计数、参数 CAS、停用和排空；不存 operationId 或审批结果 |
| `HookBackendUncertain.java` | 发布/退休所有权不确定，禁止生成确定失败的 ACK |
| `HookCommandDispatcher.java` | 单控制线程、无排队/重放、忙碌拒绝及不确定结果通道 |
| `DraftLexer.java` / `DraftParser.java` / `DraftSyntax.java` | 有界词法、语句/表达式与类型语法解析、所有回调的早期绑定错误检查 |
| `DraftCompiler.java` / `HookEvidence.java` | 同一源码快照的 SHA、definePlan/setup、参数与真实观察符号的选择和不可变计划 |
| `HookWire.java` / `SelfHookControl.java` | 严格 JSON、原 command 字段、prepare token/期限/代次、证据失效和活动目标丢失 |

`RuntimeHookBridge` 增加一次性的 `installSelfHookControl(handler)` 和 `installSelfHookControl(SelfHookControl.Platform)`；后者直接使用桥内固定身份及 Android elapsedRealtime 构造源码编译控制器。新控制器安装后，Java `submit` 在专用线程执行并复用原来的 request/future/complete 机制；12 秒超时仍是 `HOOK_AGENT_TIMEOUT_UNKNOWN`，迟到结果只记录 ACK 未送达，不重放命令。请求容量检查与登记改为同一短锁，消除原来的 size/put 竞争。

**当前产品仍由已有 Frida 适配器覆盖 `RuntimeHookBridge.submit`。没有任何 bootstrap 调用新安装点；不存在根据失败自动切换后端的路径。** 主产品依赖、旧 TS 文件和构建入口均未在本批改动中删除。

## 实际语言和值域

Worker 的输入保持 `prepare(source,evidenceId)`，客户端没有 AST 字段。`DraftCompiler.compile(source,evidence)` 是唯一产品编译入口；同一不可变 source 快照用于解析与 UTF-8 SHA-256，计划保留 `compilerVersion=tripoder-restricted-source-v1`、源码 SHA 和完整 evidence。未配对的源码 surrogate 明确拒绝，避免不同编码器摘要歧义。`RestrictedAst` 接收 AST 的接口用于内部执行与独立 Oracle 测试，没有暴露成 Worker 命令，因此不能提交“展示源码 A、执行 AST B”。

词法/语法支持 SDK 模板、具名 `@abox/hook` import/别名、export default、definePlan/setup、param、Java/Native selector、匿名箭头/函数、const、对象和数组解构、条件/短路、标量运算、模板字符串、受限字符串方法与 Math 内建；支持十进制/十六进制/二进制/八进制数字、数字分隔符、BigInt、字符串转义、注释与受限类型注解。禁止 new、赋值、循环、async/generator、getter/setter、原型属性、Java 对象与函数注入。所有分支在编译时检查节点类型和重复/严格绑定；未执行分支里的禁用语法也不能隐藏。保留 JavaScript `return` 换行、箭头换行限制、运算优先级和 `??` 混用必须加括号的规则。

这是明确版本的受限源码语法，不宣称覆盖 Babel 的全部 TypeScript 语法。首版标识符限定 ASCII 字母/数字/`_`/`$`，字符串和模板内容允许 Unicode；不支持转义标识符、泛型函数、可选参数、映射/条件类型等高级类型语法。支持的无运行时类型语法为简单/限定名称、泛型类型引用、数组、union/intersection、对象属性类型、简单 type/interface、as/satisfies/non-null/type assertion。未覆盖形式直接拒绝，不转交其他编译器或旧脚本引擎执行。完整 Babel 白名单语法的等价性仍不是本批结论。

外部 globals、调用参数、参数对象及 handler 返回值均经过深复制与检查。对象/数组至多 64 项，字符串至多 8192 个 UTF-16 单元，BigInteger 中间值为有符号 128 位范围；AST 深度小于 80，总结构元素至多 32000。每个 handler 最多 3000 个求值步骤、调用深度小于 64；before 与 after 各自取得预算，与现有分阶段执行相符。每个 session 至多 32 规则、每规则最多 16 参数；总并发调用和递归调用的启用路径受 64 次预算约束。

long/int64/uint64 只以 BigInteger 表示，不能经 Double 中转。Java float 由实际 Backend 在 ABI 边界用 `ScriptValues.abi` 显式舍入到单精度并保留负零；解释器中的 before/after 参数快照只做值域校验，避免提前舍入改变 after 观察到的脚本参数。double 只接受有限值。char 是一个 UTF-16 单元；String 允许 null。数值转字符串使用最短往返十进制与 JavaScript 指数阈值，混合 BigInteger/Number 比较依据真实 IEEE 值进行，避免 2^53 边界误判。

该核心只接受当前 SDK 支持的基本类型/String，未开放任意对象句柄、Class、Method、地址或原函数 callable 给脚本。原函数只有 `HookRule` 的一个调用位置；replace 不调用 original，before 失败不调用 original，after 失败不重试 original。原 Throwable 包括原方法自己抛出的 `ScriptFault` 都按对象身份透传，不误记为脚本错误。Java 脚本错误是 `HOOK_EXECUTION`；Native 脚本错误要求后端终止该进程。

## Backend 与版本契约

`PreparedBackend` 必须由已经验证真实方法/模块身份、加载代次、ABI、调用者覆盖和生命周期的产品绑定层提供。构造 session 不发布入口。activate 进入内部 INSTALLING，全部 publish/flush 完成后才进入 ACTIVE；发布期间回调捕获同一个候选版本，但不能提前得到成功 ACK。部分发布或退休失败进入 FAILED，调用后端 failStop，并抛出 `HookBackendUncertain`。即便测试后端返回或抛普通异常，也不能把这个结果编码为确定失败 ACK。

每次调用捕获不可变参数快照。更新必须同时匹配 process generation、revision 和参数版本，最多 10000 版；before/original/after 不会跨版本。disable 转为 DRAINING；新回调只执行原方法，旧启用调用继续持有旧参数。控制线程在计数归零后退休入口；回调线程不等待自己排空，不在锁内运行 guest original。后端仍须保留已经进入跳板、尚未进入 Java 计数的回调根，不能用计数归零推导可释放 Native 跳板。

控制派发器不保存操作结果、不生成新的 operation 身份、不发起重试。未知 Error 不返回 success/failed ACK，永久关闭本派发器的新命令接收；现有 Worker 超时、Broker UNKNOWN 和 journal 对账承担后续判断。审批和持久操作记录仍必须在原有 Authority 链路完成。

`SelfHookControl` 保持原来的 modules/observe/prepare/activate/disable/updateParameters/cancelPreparation/discard/status 字段与 processGeneration 校验。prepare 只解析和绑定，不调用 backend prepare/publish；60 秒令牌绑定 evidence epoch、实际编译计划及源码 SHA。激活前再次核对完整进程身份与证据存活，错误 token/SHA 不消耗有效候选，已经消耗的激活不重放。最多 32 次激活并保留已退休 session，防止回调根提前释放。普通观察失效通过 epoch 使旧 token 无效；活动目标所有权丢失由 `invalidateActiveTarget` 进入 failStop。独立命令门禁使用原子标志，未持有控制锁执行后端/guest original。

## 验证

运行：

```sh
rtk proxy node android/runtime/script-tests/run.mjs
```

脚本只写 `android/runtime/script-tests/build/`，不操作设备、不安装 APK、不访问网络。它直接 bundle 当前 `src/draft.ts`，用同一安装的 Babel parser 生成 AST，并将现有执行器的结果作为独立 Oracle；Java 测试使用 `--release 17` 编译。报告绑定 Java 与现有 TS 源码 SHA，记录测试为 `owned-host-contract-only`、`productBackend: not-connected`、`deviceTested: false`。

本批通过 593 项检查。其中 58 组 Babel/现有 SDK 样例同时对照 AST 求值与新 source 编译，源码摘要由独立 Node SHA 核对；18 个 Babel/SDK 拒绝样例也被新编译器拒绝；另有 256 个固定种子的 IEEE 数值格式对照及现有 `draftTemplate()` 的完整 Java/Native 双规则计划对照。包含 long/uint64 边界、负零、char/String、float 实际舍入与脚本参数快照分离、类型拒绝、未执行恶意节点、预算耗尽、不可变捕获、原异常对象、前后置错误时原函数次数、参数 CAS、在途停用、退休失败和 UNKNOWN 无 ACK。8 线程共 8000 次调用的结果及原函数次数一致。

原 JSON 命令链通过自有 fake backend 实测：观察与 prepare 不发布、源码 SHA 错配、错误/重复/过期 token、身份与模块失效、参数版本冲突、在途 DRAINING 和旧参数快照、排空后 DISABLED、部分发布无 ACK、状态查询不重放。Native 编译另验 int64 精度、Build ID/签名拒绝及 setup 关闭后禁止重新注册。测试仅在自有主机执行；API 37 Android jar 与现有 Runtime 编译类的 Bridge 增量编译通过，这不代替全部产品源码重新构建。

## 尚未完成的产品依赖

1. **真实 Platform/Backend 未接入。** `Platform.observe` 必须复用当前 guest-owned Class/方法观察与模块制品、映射和加载代次证明；`Platform.prepare` 只做绑定/预检，实际 publish 必须在取得所有权的同一边界重新校验存活。不能以编译时符号匹配代替真实 H2/H3 覆盖。当前接口只在 fake backend 通过，产品 bootstrap 尚未调用安装重载。
2. **Native/ART 与死亡语义仍需设备证明。** failStop 的实际退出、原调用 ABI、JNI 原异常、暂停/发布、模块失效监听以及计数前回调根必须在自有设备进程验收。泛化语法尚有上述明确拒绝范围，不能把 58 组 Oracle 等同于完整 TypeScript 支持。
3. **端到端控制验收未完成。** 工作台/MCP 不能因主机测试绿色就显示新的 READY/ACTIVE。仍需现有审批、operation/generation、ACK 丢失、进程死亡、UNKNOWN 对账的当前产品构建验证，以及 Android 数值/Unicode 内建语义对照。Node/Babel 只用于主机独立测试，产品编译与执行没有 Node 依赖。

建议接入已经通过 H2/H3 门槛的实际 Platform，先使一个完整自有 Java 规则贯穿现有审批 → prepare(source) → activate → update → draining disable → UNKNOWN 恢复，再扩展 Native 和其他产品适配。
