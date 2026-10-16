# Receiver 投递与完成契约

实现前审查，2026-10-14。独立开发；复用空间安装、声明解析、单 authority、进程预约与执行契约，不使用 VirtualApp 实现。

广播是系统异步投递：发送返回、进入 onReceive、异步 finish、系统有序最终结果是不同证据。不能直接调用 onReceive，不能创建假的 PendingResult，不能把超时或发送返回当完成。

## 身份与系统边界

静态 Receiver 使用每进程一个物理 manifest stub。authority 固化发送身份快照、目标安装与进程代次、声明组件、payload 摘要和不可猜的一次性 ticket。进程池支持只预约、不预绑 Worker；Android 因广播启动进程后，Factory 经 bootstrap claim 同一预约，使用客体 factory 创建真实 Receiver。Application 和 Provider 初始化完成后才允许 Android 投递。并发组件启动加入同一预约，不能另建进程身份。

动态注册保存逻辑 filter 与 generation，分配独立物理 action，使用 Android 注册真实客体 Receiver 和指定 Handler。空间内匹配由 authority 完成，不能原样把客体 action 注册进宿主，也不能以 setPackage(host) 代替空间授权。注销与死亡使注册失效；不能恢复数据库记录来伪造系统注册。

adapter 只在 Android 实际回调边界替换客体 Context/Intent。PendingResult 对象与每次 delivery 对应，支持同一 Receiver 同时持有多个 goAsync 结果。peekService 必须显式拒绝或进入 Service 路由。

## 状态与故障

投递 ticket：PREPARED → RUNNING → FINISH_REQUESTED；真实有序最终回调确认后为 COMPLETED。普通广播只能记录应用回调已完成，不能声称系统最终结果。执行后异常、目标死亡、authority 中断或缺失 ACK 保留 UNKNOWN，不自动重发。发送者先死亡不能撤销已交给 Android 的投递；它只影响发送者最终回调的可达性。

首次实现显式单目标有序广播与普通广播；多目标有序链必须保留 Android 的同一条优先级、abort 和结果传播链，不能用多次 sendOrderedBroadcast 冒充。sticky、asUser、未支持权限或选项在实现前明确拒绝，不透传。

## 必须通过的门槛

- 未预热 Worker 的静态 Receiver 真正冷启动：PID、factory、Application 顺序和系统 PendingResult。
- goAsync 返回后未完成；finish 后才有真实 ordered final-result。重复消费、篡改 payload、旧代次与错误空间全部拒绝。
- 动态 Handler 正确；同实例两个异步投递倒序完成互不串线；注销竞态明确。
- 宿主同 action 诱饵与另一空间同 action 零命中。
- 初始化期间自发广播无死锁；发送者先退出、异步目标死亡、authority 死亡与 ACK 丢失不自动重复执行。
- 每个通过快照绑定 APK、源码摘要、设备、PID/UID、operation/ticket 状态，不以 fixture 通过宣布 P2–P6 完成。

## 适配器的确定性方法模型

`receiver-01` 在自有匿名 Receiver 的 Hook 安装阶段触发 `libopenjdkjvmti.so / FieldUtil::GetFieldName` 原生崩溃。安装 onReceive Hook 本身不需要读取任意字段；为已锁定的 Frida Java bridge 7.0.13 增加显式 `members: methods` 模型，只枚举方法。生成目录中的依赖副本使用 `android/scripts/runtime_bridge.py` 的确定性变换，校验上游两文件 SHA，记录生成文件 SHA。npm 原文件不变，运行时没有“失败后换路径”的分支；版本或修改前置条件不符即构建失败。

这不证明设备 JVMTI 任意字段读取安全，也不代表 P4 全量 Hook 通过。Frida 依赖及修改来源必须保留其原许可证和通知。正在以同一匿名实例、多 PendingResult 和真实系统投递进行后续验证。

`receiver-02` 首批 189 项通过：两个空间各完成真实冷 Receiver、有序异步最终结果和动态异步倒序完成，账本归零；同构建 P1 179 项回归通过。完整故障门槛仍未通过。

## 后续审查发现：并发 bootstrap 必须只允许一个构造者

广播和 Worker 启动同一已预约进程可能竞态。在 Receiver Factory 主线程同步等待 Worker onBind，会阻塞后者依赖的同一主线程。共享预约需要原子分配 bootstrap 构造权：尚未绑定 peer 的预约可由系统组件 claim；Worker 到达后加入同一结果，不能再 LOAD。若 Worker 已拿到构造权，Factory 只能等已不依赖 onBind 的构造完成，不能重复 new RuntimeEngine。Adapter 内部可见的 current 引用也不能直接当成构造完成，须独立完成信号。这一门槛必须有可重现的竞争测试，不能由普通冷启动通过替代。
# 2026-10-14 并发启动补验

`receiver-03` 193 项通过，APK `bade18ed6721d7ee57da5821597e5a958d2885926291c95966c787fd60ecea89`。探针先阻塞 authority 主线程上的 Worker bind，再确认目标已处于 ALLOCATED 且 PID=-1，然后让真实 Android Receiver 冷启动。Receiver 接管同一预约，完成 Application 启动后，原 ensureProcess 返回同一 generation/PID；断言发生在 bind gate 释放之前。每空间 Application/factory 各一次，三个广播交付完成、注册关闭、无未决操作。

`receiver-02` 同构建历史回归补录：P1 179 项（p1-regression-05），P0 57 项（p0-regression-08）。这些属于旧 APK `4b5ce621970f3e2c7b527cb7e9af8c7becab86a19209eb7ba22d3c7e1d789474`，不能替代本次并发修复的回归。


## 2026-10-15 死亡投递的资源释放与迟到入口

生产诊断发现：已死亡目标的一条 UNKNOWN 广播会永久占用物理进程槽。不能通过把业务 UNKNOWN 改成功、删除 ticket 或直接忽略该引用来回收，因为 Android 可能仍投递旧 Intent。

schema 21 已按以下边界实施：

1. 增加独立资源退休证明，目标必须在持久 sessions 中确认 DEAD；动态投递还要求原 registration CLOSED。保留原业务状态与 effect unknown。
2. 每次物理静态 Receiver Factory 入口都先经 authority 核验 ticket、物理组件、实际 PID/UID 和原目标的槽身份；包括已有 RuntimeEngine 的热进程。验证不得在 Application/factory/onReceive 之后才执行。
3. 已退休 ticket 返回自研空接收器，由 Android 正常结束该物理投递；不创建客体 Engine、Application、factory、PendingResult 或业务回调，不伪造 returned/finished。
4. 有序广播迟到的 systemDone 只记录真实物理结束；资源退休后的 UNKNOWN 业务操作不得因此改为 SUCCEEDED。
5. 完成冷/热入口与持久墓碑验证后，进程池才能忽略已退休资源。未知 ticket、错误槽及篡改 envelope 继续明确拒绝。

目标死亡后，COMPLETED 投递也保留资源退休标记，以拦截随后重复到达的物理 Intent；原 SUCCEEDED 业务结论保持不变。资源回收不删除 ticket，也不依赖当前新进程的包名来猜旧消息归属。

`receiver-retirement-02` 定向验收 54 项通过，Runtime APK `fa5e3989abc7f6964e1bc0c45ef7e21cb6db4879553cda81b7a1f8932e173076`。包括原有冷启动、Worker/Receiver 竞争、Handler/goAsync、多 PendingResult；新用例由测试 Application 延迟真实平台 send，保留原客体最终 Receiver，再确认旧目标真实死亡。同槽的新空间客体 PID/代次与 factory/Application 计数未变化；旧投递未进入客体 Receiver。原存活发送者通过实际 Binder 认证报告 Android 的最终回调，业务仍 UNKNOWN，returned/finished 均为 0。

authority 经真实进程终止并重启后，旧 ticket 冷投递仍被拒绝，随后同物理进程中的自研检查器确认 `RuntimeEngine.current == null`。已完成投递重放也没有重复执行，业务保持 SUCCEEDED。未知 ticket、错误物理槽和额外 envelope 字段均明确拒绝。自有 UID 无 Java/Native fatal。完整 P2 及产品旧记录的资源回收仍在后续验证，不能把 54 项视为 P2 全部完成。

`receiver-retirement-01` 未通过：测试延迟标记误放在引擎私有目录，Native 文件策略拒绝客体进程读取，未形成延迟。修复只把测试标记放入自身测试空间，没有扩张产品路径许可。

后续同 Runtime APK 的 `receiver-retirement-p2-01` 完整现有 P2 套件 501 项通过（run `p2-d68098fda63e4b1c`），自有 UID Java/Native fatal 均未观察到。产品引擎 APK `5eb62d475e61599136736542c5eba7582530d98c801cae72bccde97ba7486bf3` 的 `receiver-retirement-resources-01` 显示 schema 21，allocated/retained/available 为 0/0/8，历史死亡广播已不占槽。`receiver-retirement-agent-01` 的自主 MCP 验收 18 项协议与 12 项设备检查通过。这些结论不包含尚未实现的系统广播、JobScheduler 或两款真实应用业务验收。

## 2026-10-15 发送端接收权限限制

`sendBroadcast(intent, permission)` 的 permission 限制接收者；动态注册的permission则限制发送者。这两个方向分别在authority筛选，不能互换。首批支持发送方制品自己声明的signature/signatureOrSystem权限：目标必须在同空间、声明请求该权限并具有相同签名。signatureOrSystem仅使用可验证的signature分支，不推断客体系统特权。权限筛选在目标预约前完成；无合格目标不创建进程或交付。权限限制加入每次交付operation摘要，路由凭据仍由同一安装/进程代次绑定。

原生`broadcast-send-permission-baseline-01`15项、容器`broadcast-send-permission-runtime-01`48项通过；runtime SHA `767689fcaa197479a7117936c1ecc7181c2e04e24efa5a18440aff875669c160`。目标声明请求后实际收到，只有声明而没有请求时不收到；不受限发送仍正常。权威端恰有2条已完成广播，没有为被拒绝发送创建任何票据。原生负向观察以同一Handler后续barrier为界，权威端额外验证了无交付记录。

其它权限所有者、系统权限/AppOps、options、多权限、无接收者的ordered最终回调与多接收者ordered链仍未实现，明确失败。非零priority仍拒绝，新增过滤器动作数/分类数/priority诊断，不默默移除priority。该限定结果不代表完整广播语义。


2026-10-15：支持无action、无data、无category的空过滤器，真实注册并持久保留注册ID；它不会匹配任何隐式广播，不是通配符。带data但无action仍明确拒绝。新增BATTERY_LOW/BATTERY_OKAY/ACTION_POWER_CONNECTED/ACTION_POWER_DISCONNECTED四种非sticky事件，沿用平台受保护广播校验和真实回调。验证见receiver-empty-baseline-01（19项）/receiver-empty-runtime-01（54项），电池实际状态切换尚未验证。[Android IntentResolver 索引语义](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/core/java/com/android/server/IntentResolver.java)作为语义参考，独立实现且未使用VirtualApp代码。


Sticky首批范围为受保护的BATTERY_CHANGED。registerReceiver(null, filter)由authority向真实Android读取快照，不新建注册或伪造delivery。实际注册同时保留平台返回的sticky值和系统异步onReceive；返回值只生成经注册过滤器核验的数据视图，只有真实callback走交付账本与PendingResult.finish。返回null仅表示平台确实无snapshot。不得捕获失败后返回假空值。未知sticky类型、null receiver的混合/私有过滤器及权限策略明确拒绝。实现与设备验收结果分开记录。

## 注销与迟到claim的同一权威边界（2026-10-16）

本地closed检查不能替代权威准入。BROADCAST_CLAIM在核对ticket目标、组件、registration和payload后，注册已CLOSED则返回明确rejectedClosedRegistration；系统观察同样在创建operation前判断关闭。未知/未激活注册、错误身份和损坏payload仍拒绝。适配器不调用guest、不建立Invocation、不记录returned/finished，让Android自身完成PendingResult。注销事务将未claim的PREPARED投递保留为UNKNOWN；已RUNNING的调用仍按真实回执完成。系统完成不等于客体成功，原始票据与操作保留，资源最终退休仍需要目标死亡和入口封锁证据。

该边界修复了两线程真实注册/广播/注销中发生的RECEIVER_REGISTRATION_CLOSED main崩溃，失败记录见provider-identity-multiprocess；当前修复的真机回归另列，不把源码编译当成完成。
