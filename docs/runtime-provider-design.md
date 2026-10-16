# Provider 执行契约与实现门槛

2026-10-14，P2 组件批次。独立实现，不使用 VirtualApp 源码。首批 `provider-07` 156 项真机检查通过，具体构建与范围见证据目录；本文中的完整门槛尚未全部通过。

## 系统与虚拟组件的边界

Android 为每个物理进程槽创建一个 inert transport Provider；它的 onCreate 只初始化宿主传输端，不装载客体代码。空间身份、安装代次及完整 Provider 租约在进程 bind/LOAD 之前提交。Runtime 使用 APK 声明的 AppComponentFactory 创建客体 Provider，并以 GuestContext、客体 ProviderInfo 调用 attachInfo；由 attachInfo 触发一次 onCreate。客体 Provider 没有注册进 Android PackageManager。

顺序为：factory/classloader → 构造并 attach 客体 Application → 本声明进程中 enabled Providers 按 initOrder 降序、同值保持声明顺序初始化 → 逐个 READY → Application.onCreate → RUNNING。多个 authority 别名共享同一 Provider 实例。

## 获取与调用

1. 客体 resolver 请求逻辑 authority。Authority 从已认证 sourceSession 的 space 解析，检查声明和唯一性，不查询宿主同名 provider。
2. 进程准备只等待 LOAD/ATTACHED。acquireExisting 仅查询已存活且已发布的实例，不启动新进程。
3. source 使用真实宿主 AttributionSource 获取指定物理 transport 的 stable/unstable ContentProviderClient；临时 acquisition capability 绑定 source、安装、目标进程代次、组件及物理槽。
4. transport 根据 capability、实际 Binder caller PID/UID 和操作类型授权后，调用对应 READY 客体 Provider。调用在 broker/SQLite 锁外。
5. 原始逻辑 URI 经严格可逆 envelope 传输；没有 URI 的 call 使用单独 envelope。客体不能提供物理 authority。未知方法直接拒绝。

本进程 Provider 已 READY 时可立即使用。主线程访问正在初始化的 Provider 必须报初始化环错误，不能等待自身。跨进程初始化需要记录依赖、检测环及限定等待；不得用无界 Binder 等待隐藏死锁。

## 身份、权限与持久性

nonexported 和同 UID 不区分虚拟空间。Runtime 仍需检查空间、组件、安装代次、exported、读写和路径权限；未实现的跨包权限规则拒绝。调用客体 Provider 回调不能自动获得其 ContentProvider.Transport 的 calling-attribution 状态，需作用域化 InvocationIdentity，嵌套调用及 clear/restore identity 后恢复正确上下文。系统调用始终使用真实宿主身份，不能把未实际安装的客体包名送给系统冒充已验证身份。

ProviderLease 是持久组件/物理槽映射，ProviderAcquisition 是 source 持有的临时 stable/unstable 引用。进行中的调用、Cursor、FD、observer 与释放 UNKNOWN 都可能延长引用，不能在 query 返回时统一回收。目标死亡后旧 acquisition 不复活；新获取使用新 capability。未知结果不得重播，引用检查必须接入安装/升级/卸载与槽回收。

此设计只承诺受控 Runtime 的身份语义，不提供同 UID 恶意 Native 代码之间的安全边界。

Wrapped resolver 的直接 query/getType/call 与显式 ContentProviderClient acquisition 是两条入口，分别由 Java router 和必需平台 adapter 接入。客体方法异常且不能排除副作用时记录 UNKNOWN，不重放。初始化 ready 握手不执行客体业务回调，可明确失败。调用记录与执行契约的接收/结束状态在同一 SQLite 事务中提交；恢复只能依据已持久化的未派发状态判定未执行，不能猜测 RUNNING 调用的业务结果。

平台 attachInfo 在触发客体 onCreate 前获取 AppOps。当前固定设备适配仅在平台私有 attachInfo 内提供一次初始化用途的 AppOps，随后同一 Context 的客体访问仍拒绝；不将整个 AppOps 服务加入通用透传列表。

当前已验收：单 base APK；query/getType/call 和有界基础类型 Bundle；insert/update/delete/bulkInsert 和有界 ContentValues。call 的跨包权限按读写能力同时检查，写操作仅检查写权限，BEGIN 与 DISPATCH 均校验。批处理、取消、FD、通用 observer、URI grants、完整权限授予和 Cursor 后续调用的身份语义待续。对当前未实现能力明确拒绝。

## 分批验收

首批：query/getType/call、真实跨进程 transport、factory 创建、初始化顺序、自身 Provider 查询、相同 authority 的跨空间隔离、同名宿主 decoy 零命中、stable/unstable 获取释放、死亡后的旧 capability 拒绝、初始化环与错误 URI 拒绝。

随后分别接入并验证写入、批处理、FD、取消、observer、URI grant、恢复与操作结果丢失。首批通过不能替代完整 ContentResolver 或微信、王者荣耀验收。

## 存储层接入后的实施顺序

P3a 与首批 Native 入口通过后，继续复用本文件的单一获取 capability、one-use operation 和实际 Binder 身份，不另建 Provider/URI 权威。

1. 先接入 insert/update/delete/bulkInsert 与有界 ContentValues：现代与 legacy SDK 重载遵守相同语义，Runtime 不把异常写入重放或改成成功。target 抛异常可能已有部分副作用，仍记 UNKNOWN；调用返回计数/URI与执行状态分开验证。跨包读/写/path permission 必须按实际操作类型检查。
2. 批处理保留 Android ContentProviderOperation/back-reference 与目标 Provider 的真实事务责任。需要逐项检查 URI 与权限，不能只检查第一条或批次根 URI，也不能把部分写入异常当作自动回滚。
3. FD 通过真实 ParcelFileDescriptor/AssetFileDescriptor Binder 传输。target callback 返回只证明目标执行，不证明 source 收到文件能力；需要单独的交付 ACK、未知状态和安装/数据引用。原始 fd 整数不能跨 Binder 当作能力。
4. 关闭 Java PFD 不代表所有 dup、detach、Native FD、mmap 都已消失；不能据此提前解除安装保护。正常进程死亡能证明本进程 FD 已关闭，跨进程再次转移仍需纳入授予契约。URI grant 与文件能力的撤销语义不能混为“强行使已打开内核 FD 无效”。

步骤 1 的正常写入与权限门槛已在 `provider-writes-01` 的 283 项检查中通过；异常副作用/响应丢失仍待验收。步骤 2–4 尚未实现或验收；当前 Provider 文件入口继续明确拒绝。

## Cursor 后续调用与资源持有

正在实现的 Cursor 适配保留真实 Cursor、CursorWindow 和 Android BulkCursor 通道，不复制快照替代延迟查询。每次 query 有独立身份包装；延迟取数、窗口补取、respond/requery 重新验证实际源 PID/UID、源代次与尚未释放的 acquisition，然后作用域化调用者。目标进程本地的 Binder 死亡/构造失败清理只允许 unregisterContentObserver/close，并使用空调用者身份；不能伪装已死亡的源。窗口传出后，其缓存行无需再次进入目标，不能宣称撤销已经交付的数据。

Facade 分开核算“客户端已关闭”与“物理引用可释放”：正在进行的调用和返回 Cursor 持有引用；显式 client.close 阻止新调用，但直到 Cursor 确认关闭才释放实际 client 和 acquisition。close 抛错时保留未确认引用，不自动重试客体 close。此批不允许 Cursor Binder 转交第三方；源死亡由 Android 清理，目标死亡不重新查询。后续独立 FD 能力不能沿用 Cursor close 的释放假设。

## 接收确认与批处理边界（实施中）

Provider 调用分开保存业务 callback 状态与 delivery 状态。目标 FINISH 只证明回调返回；源适配器拿到已反序列化结果并登记 Cursor 持有后发送 RECEIVED，随后才返回应用。此 ACK 不代表应用已经消费结果。源关闭引用/死亡而没有 ACK 时 delivery 记 UNKNOWN，不能重发原业务调用。旧数据库通过 schema 7 显式迁移，历史交付标为 UNKNOWN，不从过去的成功回调推测“已经收到”。目标确认死亡不妨碍仍存活源对已经返回的成功结果发 ACK。

批次按每个 operation 的逻辑 URI 和读写类型在 BEGIN/DISPATCH 两处检查。使用公开 SDK ContentProviderOperation Parcel/CREATOR 冻结副本，最多 32 项、256 KiB；真实执行仍只调用一次客体 applyBatch，保留 back-reference、expectedCount、yield 标记和客体事务责任。暂拒 batch call 和 exceptionAllowed；不把默认批次宣称为原子事务。

仅字节限额无法防止巨大 sparse selection key 的内存分配。固定平台必需适配器验证 mSelectionArgs 为 SparseArray，键位在 0..127，值只允许 String/null/平台 BackReference，引用索引必须指向前序操作。原对象和冻结副本均检查，再调用 SDK resolve 做有限类型验证；合成值仅用于预检，不用于执行、不预测真实返回 ID/计数。字段形状变化直接拒绝。受控来源加 Parcel 大小上限不构成任意恶意序列化输入的安全证明。

目标回调抛错时，写入可能已经生效：账本保留 UNKNOWN，Runtime 不重放、不模拟回滚。未知业务效果继续保护安装/数据；所有引用关闭且目标进程确认死亡后，不再单凭该未知业务效果永久占用物理进程槽。安装保护与进程槽回收是两种不同责任。

## API 依据

仅使用公开 SDK 和 AOSP 平台契约核对接口：[ContentProvider](https://developer.android.com/reference/android/content/ContentProvider)、[AppComponentFactory](https://developer.android.com/reference/android/app/AppComponentFactory)。本地 SDK `data/res/values/attrs_manifest.xml` 的 initOrder 定义为高值优先；具体设备内部适配须通过真机验证，不以文档存在推定可用。

## 重复类声明的原生对照与索引修正

2026-10-14，自研 `dev.tripoder.provideridentity` 以同一个 Provider 类声明两个不同 authority 和 metadata，在设备原生安装后观察：包元数据保留两条声明；`getProviderInfo(ComponentName)` 返回第一条，`resolveContentProvider(authority)` 分别返回对应声明；进程只创建一次第一条对应的实例，attachInfo 的 metadata 为空，应用可通过带 GET_META_DATA 的 PM 查询读取元数据。完整对照记录在 `provider-identity-baseline-04`；与 Runtime 验收使用同一签名 APK。

第二个 authority 不属于已附着实例。原生 Transport 实际拒绝调用，但此固件的本地 Provider 引用计数还产生 `connection is null`，可能遮住 SecurityException 并影响下一次获取。Tripoder 复现组件和 authority 的身份规则，不复现系统引用计数错误，也不把第二条声明合并成可调用的别名。AOSP [ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java) 的组件实例索引与已发布 authority 索引为独立结构。

RuntimeProviderIndex 保留两种索引：第一条组件用于初始化、租约、进程和权限；每个 authority 保留原始声明用于元数据解析。BEGIN 和 DISPATCH 在进入业务回调前核对已发布 authority，不匹配直接抛 SecurityException，不能创建 UNKNOWN 业务效果。跨进程或 enabled/multiprocess 不同的重复类仍明确拒绝，尚无其生命周期验收依据。相同声明内的分号别名继续共享实例。

GuestPackageManager 的自身 Provider 查询从当前已验证 APK 取得信息，按 flags 返回独立 metadata 副本；未知跨包查询继续拒绝。RuntimeWire 和 Provider Facade 保留标准异常类型，避免把调用方可区分的权限拒绝统一改写成 IllegalStateException。此批代码已实现，当前构建的完整回归结果另列证据，不以原生对照代替 Runtime 通过。

## multiprocess 的本地惰性实例（实施中）

两个目标原始 APK 在生产引擎的首次启动均触发先前的 `MULTIPROCESS_PROVIDER_UNSUPPORTED`。独立正常安装夹具 `provider-multiprocess-baseline-01` 证明本机行为：声明在独立 `:provider` 进程的 multiprocess Provider，并不在所有进程启动时主动创建；主进程与 `:peer` 进程首次访问时各自在调用线程创建一个实例，后续获取复用该进程实例。

这与 AOSP 的 [启动 Provider 查询](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/pm/resolution/ComponentResolverBase.java) 按声明进程筛选、[canRunHere](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/services/core/java/com/android/server/am/ContentProviderRecord.java) 允许同 UID multiprocess 在调用方创建的职责分离一致。只阅读平台契约核对行为，新增实现为本项目独立编写，未使用 VirtualApp 代码。

Runtime 以逻辑包身份区分应用，不能因为所有客体共享引擎实际 UID 就把其他包 Provider 装入调用方。仅同空间、同安装、同逻辑包的 multiprocess 获取可以注册到 source processGeneration。不同包仍使用声明进程并检查 exported/权限。

惰性实例使用现有 ProviderLease 的 `(generation, component)` 唯一性、发布状态和 acquisition/operation 账本；初始化在锁外的实际调用线程执行。同进程重复获取不重复 onCreate；失败保存 FAILED，不能再初始化重试；递归初始化明确报环。acquireExisting 不创建新实例。原声明进程的启动初始化仍按原生命周期执行。新增实现的真机结果待单独记录。

首轮实现已通过 `provider-multiprocess-runtime-01` 的 **446 项** P2 真机检查，其中新增 7 项：主进程/服务进程分别惰性创建、后续重复获取、实例与 PID 对应、调用者身份、实际服务进程复用及所有调用回执/引用确认。并发首次获取、初始化异常和更多跨包变体仍待验收。

## 2026-10-15 同次 publication 的私有 Provider 委托（待验收）

微信第十二次启动中，已声明 Provider 的 attachInfo 先调用父实现，再把同一 Context/ProviderInfo 传给内部的另一个 Provider 实例。该私有对象并不在 manifest publication 表中，原先仅按已登记对象判定平台 attach 的方法无法覆盖此语义。只读核对原始 APK 的该方法调用关系，没有复制目标实现。

新增按线程、初始化调用栈嵌套的 attachment scope：从已准入根 publication 开始，finally 结束；内部对象须属客体 loader，并保留同一 Context 和原声明的 package/name/authority/process/exported/read/write identity。该范围内 Android ContentProvider.attachInfo 可获得一次平台 AppOps，客体 onCreate 与后续 getContext().getSystemService 不能获得它。内部对象不成为新的 publication，也不追加任何 authority。范围之外实例不获此能力。

自研夹具按同一结构创建私有委托，检查单次 onCreate、逻辑上下文、平台能力不可取出及范围外 attach 被拒绝。仅此类生命周期委托在本批支持；任意 Context 包装和新的动态 authority 不在范围内。当前待原生、P2 与真实微信复测。

原生对照 `provider-delegate-baseline-01` 已通过 59 项（resources-4fa11a3b52ad4bfa），包含同样私有委托结构与声明未请求的签名权限注册；内核端待 P2 回执。
# Provider 不存在的 API 语义（2026-10-15）

authority 在已认证的 space 内完成声明查询后，没有匹配 Provider 是确定的“未安装”，不同于权限拒绝、初始化失败、transport 死亡与 UNKNOWN。此分支返回无 capability，不查询宿主 PM。guest resolver 的 query/getType 返回 null，stable/unstable client 获取返回 null；insert/update/delete/bulkInsert/call/applyBatch 按 Android 的相应公开 API 抛 IllegalArgumentException。存在的 Provider 的权限、进程和执行失败保持原异常，不转换为“未安装”。

此语义由手机 API37 ContentResolver 实现及自研 MissingProviderChecks 对照；真实运行结论记录在 evidence 索引中，不把代码落地等同于验收。微信的 phenotype authority 只是暴露问题的触发点，实现没有针对其包名作特判，也没有借用系统 GMS Provider。


## 冷依赖的统一启动截止点（2026-10-16）

权威分配进程槽位时生成一个单调时钟截止点：分配时刻加30秒。该值随已认证bootstrap传到RuntimeEngine；监督器watchdog、构造、Application完成和Provider就绪等待都使用剩余时间，不在内部层重新创建8秒或10秒计时。固定适配先于所有客体代码，冷依赖进程的安装时间也计入原预算。真实初始化循环仍由权威等待图拒绝；超时不能证明无副作用，现有UNKNOWN/死亡回执边界不变。已经READY的Future不因启动截止点过期而失效。

旧8秒Provider等待在H19实机循环场景提前超时，原失败保留在`self-hook-product-2026-10-16/providers-contract`；统一截止点的真机结果另列，不把主机计时合同通过当成完成。
