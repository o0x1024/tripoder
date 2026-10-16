# P2–P6 连续实施契约

2026-10-14，用户明确授权连续推进 P1–P6；P1 已有历史验收。后续不能以限定 fixture 通过代替完整路线完成。

本轮 P6 只包含手机现有微信 8.0.79（3200）与王者荣耀 12.1.1.1（1201010103）。钉钉、原神与物理手柄移出本轮；登录由用户在手机上接管。版本清单见 `evidence/runtime-p2-plus-2026-10-14/target-inventory.json`，不读取已登录宿主应用的私有数据。

## 实施顺序与验收

1. **P2 进程与组件**：Authority 按 space/install/声明进程持久化预约，锁外 bind/LOAD，短锁确认 READY；并发请求共享一次启动，Binder 回入不得死锁。组件解析仅查空间安装。依次接入多 Activity、Service、Provider（在 Application.onCreate 前）、Receiver、任务栈、URI 与 PendingIntent。使用真实 Android 组件/进程调度，检查创建、取消、重建、停止、死亡、迟到消息和容量耗尽。探针预先启动固定 A/B 不能作为生产调度方案。
2. **P3 文件与系统语义**：围绕同一空间身份实现 Java 文件/偏好/SQLite、Native 文件与 FD/mmap 归属；逐项定义 PM/AMS、URI 授权、账户、剪贴板、定位及网络策略。允许的宿主硬件能力必须显式记录；未知能力拒绝，不默默透传。实际 Java/Native 读写结果必须一致，同 UID 恶意 Native 隔离仍不成立。
3. **P4 Hook**：复用已有 SDK/AST/Gadget，把符号、loader/module 与安装/进程代次绑定；prepare/activate/ack 逐进程确认。设备验证真实命中、原函数最多一次、错误退出、多线程/递归与在途卸载；失败不得恢复调用原函数掩盖错误。
4. **P5 工作台**：接入统一 runtime 控制 API，旧运行入口退出生产路由。可信控制端与客体使用不同 Android UID；导航与客体生命周期分离。项目与草稿检查/人工部署/授权自动操作使用相同契约。用户后续提出外部 Agent 方向，内置模型接入暂缓，见 [候选设计](external-agent-control-design.md)。手柄不在本轮验收。
5. **P6 实际应用**：固定微信与王者荣耀 APK/证书/数据实例，实际完成启动、用户登录、王者→空间内微信授权→王者返回、退出恢复及约定 Hook。空间内微信缺失时明确失败，宿主微信不参与。保留核心场景、通知与性能实际证据；任何未通过项不能标记产品完成。

## P2 的首要结构修正

现有 LOAD 同步回调 REGISTER，随后客体主线程 Application.onCreate 回调 EVENT。Authority 不能持有 Broker monitor 等待 bind/LOAD，否则跨线程回入死锁。必须拆为预约、锁外启动和完成确认；安装/升级仍依据已持久化的预约拒绝修改。

进程槽是 Android manifest 的有限资源；槽位不等于虚拟应用身份。空闲槽分配给一个不可变 ProcessSession，死亡并确认系统解除连接后才能回收。不以重新使用旧进程、旧 bootstrap 或旧数据路径处理容量/启动失败。

## 状态与证据

当前 P2–P6 均未验收。每次修改以源码摘要、APK SHA、固件、真实 PID/UID、空间/代次、操作与断言记录；旧 P0/P1 证据保持历史不可变。完成一阶段继续下一阶段；需要用户登录时才交回手机操作，其他可独立实施部分继续推进。不使用 VirtualApp 代码，不引入 fallback 后端。

## 真实目标制品带来的设计约束

只读提取手机已安装 APK 并使用 Android SDK `apkanalyzer`/`apksigner` 检查；没有读取账号数据，也没有启动目标应用。详情见 `evidence/runtime-p2-plus-2026-10-14/target-apk-preflight.json`。

| 实际目标 | APK / DEX | Manifest 与 Native | 对路线的影响 |
|---|---|---|---|
| 微信 8.0.79 / 3200 | 285,284,760 字节；18 个 DEX，共 190,053,436 字节 | 2099 Activity、123 Service、47 Receiver、30 Provider；209 个 arm64 库；36 个声明进程名 | 当前 64 MiB DEX 预算不足；需要真实 Provider 启动顺序、组件工厂、多个任务模式和按需进程调度。声明进程数不等于同时运行数，不能据此直接无上限扩池。 |
| 王者荣耀 12.1.1.1 / 1201010103 | 701,939,162 字节；6 个 DEX，共 40,277,916 字节 | 82 Activity、5 alias、43 Service、28 Receiver、27 Provider；最大 arm64 库 227,661,664 字节 | 安装/解压限额需要明确的大制品预算、磁盘预留和流式校验；alias、Native 大库、图形和外部资源必须单独验收。 |

两款应用都声明 `androidx.core.app.CoreComponentFactory` 和多种 launchMode。不能因为自有 standard Activity 夹具通过，就跳过组件工厂与任务栈语义。应用内部恰好存在名为 `:fallback` 的声明进程是输入元数据，不是 Tripoder 的 fallback 执行路径。

2026-10-14 补充核对原 APK 的 ZIP 元数据：微信包含 16,011 个条目、展开总量 653,578,040 字节；王者包含 1,623 个条目、展开总量 1,450,837,567 字节，最大条目 234,455,040 字节，同时包含 ARM64 与 ARMv7 库。安装策略必须明确选择 ARM64、完整校验所有 ZIP 内容并只提取选中 ABI；APK 含其他 ABI 不等于切换执行后端。不得仅提高 APK 大小常量、继续把微信 190 MB DEX 全部复制到 Java heap。

下一安装批次的验收包含：有界的大制品预算与磁盘余量、合法 Native 文件名、混合 ABI 的明确选择、流式 DEX 校验和只读映射、完整性缓存的失效依据。真实目标的签名与制品验证仍然只是安装前置证据，不能代替 Application 启动、登录和业务场景。

## P2 审查后的实现约束与当前证据

- 将 authority 预约与锁外 bind/LOAD 分开；session 在绑定前落盘，包含声明进程、物理 Activity stub 和 authority 代次。
- Activity 使用持久 ticket 与每次创建的 instance nonce。进程死亡不自动释放 Android task 对安装及 stub 的引用；未知的创建请求不能重播。结果已经送达与 target 的 post-destroy 回调允许前后乱序，不得把迟到的合法销毁当崩溃，也不能复活终态。
- 冷 Activity 由 nonexported BootstrapProvider 找同一个 authority，读取原票据、检查原进程死亡与安装代次，再创建新 session。主线程入口直接初始化 Application，禁止 post(main) 后同步等待自身。
- 同进程 recreate 的回调表按 logical ticket + requestCode 记录；跨进程死亡后的 for-result 协调尚未实现，当前明确拒绝，不能列为 P2 完成。
- Android 后台前台切换规则保持生效。冷恢复测试需要外部 Launcher 动作；测试协调器从后台直接 moveToFront 被系统拒绝的失败证据保留，不能以返回 void 认定恢复成功。
- 已有第一轮多进程与嵌套 Activity 的 61 项通过证据（`process-activity-01`）与 P0 回归 57 项（`p0-regression-01`）。随后加入票据/冷恢复，属于新的构建，必须重新通过门槛；旧结果不能代替当前构建。

P2 仍需：完整任务模式与 in-flight result 恢复、真实 Service/Provider/Receiver、URI/PendingIntent、拆分包执行、启动超时与迟到回调的故障验收。P3–P6 仍未验收，尚未到用户登录接管步骤。


### Service 接入前的审查结论

复用 `ApkManifest`/平台 PackageInfo 的声明解析、同一 authority、进程调度和持久租约。旧 ComponentController/GuestService 只包含工作台或 Messenger 运行器，不能算作可复用的通用 Android Service 生命周期。

下一实现批次使用独立的 Service stub 池，Factory 返回真实客体 Service，由 Android 驱动 create/bind/start/destroy。一个物理 Service 不能同时代表两个客体 Service，否则 AMS 会合并绑定缓存和 startId。Service Factory 的 Intent 允许为 null，Provider Factory 没有 Intent，因此它们需要持久 stub/authority → 组件租约，而不是依赖 Activity extras。

还须处理两项前置问题：Application.onCreate 内的跨进程绑定不能形成互等启动；Provider 必须在客体 Application.onCreate 前安装。Service.stopSelf/stopSelfResult/前台通知依赖真实系统 token 和物理 component，不能只替换 Context。UNKNOWN 绑定或系统重启未确认时继续占用租约，不能在 onDestroy 或进程死亡时盲目复用 stub。

第一组 Service 门槛固定为：同进程两个 Service、跨进程真实 Binder 调用、bind/unbind/rebind、多个 startId 与 stopSelfResult、started+bound 共存、null binding、sticky/null Intent 冷恢复。之后再做 Receiver 的真实系统分发/goAsync 和 Provider 的初始化顺序/获取链。具体实现与验收状态见下节。

### Service 实施进度与约束

已拆分 LOAD（验证身份、loader、必需适配器）与 START_APPLICATION；内部组件准备等待 ATTACHED，控制端的完整启动等待 RUNNING，Application.onCreate 的同步组件请求不再等待自身。Factory 返回真实客体 Service，Android 创建实例并驱动生命周期；租约、请求及调用方连接身份落入同一 authority 数据库。Service token 和物理组件用于 stopSelfResult，客体看到逻辑 Context、Application、Intent 和 ServiceConnection 名称。

当前每进程 4 个 Service 槽，进程内不重新分配到另一组件；进程确认死亡、无未决请求/活跃绑定且系统服务记录消失才释放。前台服务通知策略、isolated/external service、多 Intent 共用一个 ServiceConnection 尚未实现，明确拒绝。这些边界不满足任意应用执行，仍须继续扩展。

2026-10-14 第一轮 11 项 Service 新增断言通过；sticky 冷恢复被小米后台自启动策略拒绝，整轮失败证据保留在 `evidence/runtime-p2-plus-2026-10-14/service-01`。系统调度许可属于实际运行环境前置条件；禁止通过主动重新 start/bind 伪造系统 sticky 恢复成功。

用户明确开启测试包后台自启动后，第二轮 `service-02` 90 项通过，系统完成新 PID/Binder/实例的冷恢复及 sticky null Intent 交付；完整 P2 仍未完成。其后继续接入 Manifest 声明的 AppComponentFactory 与自定义 ClassLoader/替换组件，以真实 factory 调用验证代替默认反射构造的假设。

### 真实制品声明与设备有效语义（新增审查）

真实微信/王者的 Manifest 使用浮点属性、资源引用 enabled、重复 Activity/Provider 声明。签名校验阶段保留原始类型和值、完整声明树；不能把资源引用当布尔常量，也不能仅凭类名去掉不同 authority 的 Provider。制品描述与设备资源配置下的有效 ComponentInfo 是两个层次：安装记录绑定原始制品摘要，执行前只解析该制品的 PackageInfo，并核对名称/类型/alias/Provider authority，不查询宿主已安装目标包。资源解析失败明确拒绝。

重复 Provider 涉及 ComponentName 索引、authority 索引和进程内真实实例三个身份。已完成独立原生对照和 Runtime 438 项回归：第一条组件负责初始化与 publication，每个 authority 保留各自 metadata，不把重复类的第二 authority 自动合并成可调用别名；不属于已附着实例的 authority 在回调前拒绝。跨进程或 enabled/multiprocess 不一致的重复类仍未支持。详情见 [Provider 契约](runtime-provider-design.md)。参考 [AOSP ComponentResolver](https://android.googlesource.com/platform/frameworks/base/+/0c36369480790826dffd7f2e1679a33fa0edb427/services/core/java/com/android/server/pm/resolution/ComponentResolver.java)。二进制属性格式依据 [AOSP ResourceTypes.h](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h)，未使用 VirtualApp 源码。

### 大包冷恢复的主线程预算

真实设备制品验证显示：微信完整复验约 5.4 秒、王者约 8.9 秒，另有 DEX 类加载时间。当前温启动由 ProcessSupervisor 在锁外准备；冷组件 Factory 的同步 bootstrap 仍可能把完整验证放到 Android 主线程。这是实际规模暴露的架构风险，必须专门测冷恢复耗时/ANR，不能仅放宽超时或以小夹具冷恢复通过代替。后续方案需要复用 Authority 的已验证不可变制品身份、明确 Worker 接收的验证凭据与失效规则，同时保留 DEX/Native 使用前的内容校验；尚未实现该优化。

P4 具体 prepare/activate/ACK、loader/module 身份与在途卸载设计已补充到 [Hook 执行契约](runtime-hook-design.md)。文档不是实现完成的证明。

### Authority 冷恢复的无关制品 I/O

已将安装元数据恢复与内容信任分开。冷恢复只处理持久引用和孤立发布物；COMMITTED 表示当时发布事务完成，不能证明重启后的字节完整性。所有制品使用者仍经 ApkStore.get 进入当前 authority 的空完整性缓存，首次使用完整复验，后续使用依赖文件身份/ctime 等失效依据；Worker 的 DEX/Native 使用前检查保持不变。

保留全部历史安装，在同一设备上 P1 整轮从 493.080 秒降至 11.134 秒，205 项检查通过，其中冷恢复 fullVerifications=0；并发前台组件、实际大 APK 冷 Factory 主线程耗时仍须单独处理。此项不代表已经修复安装期间长锁或所有启动性能问题。

### Service 标量参数扩展（2026-10-15，待验收）

保留原有显式组件、无 URI/ClipData/flags 的范围，参数扩展为 null、String 和八种 Java 标量包装类型。调用入口先复制 Intent/Bundle，类型不可变；摘要改为长度前缀、类型标签、原始数值位表示，避免文本拼接歧义。最多 16 项、键 100 字符、文本 4096 字符、规范编码 48 KiB。数组、嵌套 Bundle、Binder、Parcelable、FD 仍明确拒绝，并只记录类型，不打印参数值。验收通过真实跨进程 onStartCommand 检查 nullable key 与各标量类型。
