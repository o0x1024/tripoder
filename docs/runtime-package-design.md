# Tripoder 包与资源查询契约

2026-10-14。P3 的包查询由当前安装代次和逻辑空间定义，不能查询手机上同名应用来填补缺失能力。当前只实现受限的自身应用、Provider 声明及 XML 资源；完整 PackageManager、跨应用解析和 P6 仍未完成。

## 权威与生命周期

运行进程绑定 `(space, package, installGeneration, processGeneration)`。APK 签名、内容摘要、资源和 Manifest 在安装时验证；LOAD 再验证安装描述与制品。该进程只使用绑定的不可变 APK 与对应 Resources。调用方传入的 `ApplicationInfo.sourceDir/publicSourceDir` 不能选择资源来源，也不能更改安装记录。

主机 PackageManager 仅承担明确的设备事实查询及对安装副本的档案解析；客体查询不能委托它寻找其他已安装应用。升级产生新安装代次，运行中的资源不能就地替换。包不存在、权限拒绝、能力未实现与操作结果未知分别表达，不以空对象掩盖不支持的 API。

## 当前能力

| 入口 | 语义与边界 |
|---|---|
| `getApplicationInfo(self, flags)` | 支持 0 与 `GET_META_DATA`，两种 int/long 重载保持完整 flags；未知位明确拒绝并报告十六进制值。元数据来自 Manifest，未请求时省略。 |
| `getProviderInfo` / `resolveContentProvider` | 使用当前安装的 Provider 索引；按 enabled、metadata、URI pattern 查询标志生成结果。其他包/authority 尚不支持，不查主机同名 Provider。 |
| `getResourcesForApplication(self)` | 返回当前安装的资源视图；调用者提供的路径不改变来源。 |
| `getXml(self, id, appInfo)` | 从当前安装 Resources 读取；appInfo 可为空，非空须与请求包一致。缺少 XML 返回 null，这是该 API 的规定语义；跨包查询与身份不一致仍明确拒绝。 |
| Context / AppComponentFactory 获得的 ApplicationInfo | 元数据副本不能改变运行引擎保存的安装记录；这属于 Tripoder 内部所有权约束。 |

元数据副本不是恶意同 UID Native 代码的安全隔离。当前运行引擎也没有因此获得所有 Android 包查询、权限、签名或系统服务能力。

## 对照与验收

自研 `PackageResourcesActivity` 在正常安装和 Tripoder 中运行相同 APK，验证 Manifest 值、int/long flags、应用与 Provider 的 `loadXmlMetaData`、直接 XML 解析和不存在资源的返回。Tripoder 另验 caller 路径不能改源、错误身份/跨包/未知高位 flags 拒绝以及元数据副本。

`package-resources-baseline-01` 的原生缓存对象独立性假设失败，保留证据；修正为只对 Tripoder 权威记录验证该约束。`package-resources-baseline-02` 的 10 项平台语义通过。内核设备结果在 [证据索引](evidence/runtime-p2-plus-2026-10-14/README.md) 逐构建记录，不能把基线通过当作 Tripoder 通过。

后续按真实声明与场景补充 PackageInfo/签名、组件查询/Intent 解析及跨包可见性。每个能力都须读取逻辑安装权威，定义完整查询标志、对象所有权与生命周期，再以自研对照和真实目标验证；不为单个应用硬编码查询结果。

依据：[Android PackageManager API](https://developer.android.com/reference/android/content/pm/PackageManager)，尤其 `getApplicationInfo` 与 `getXml`。未使用 VirtualApp 代码。

## 2026-10-15 自身 PackageInfo 与安装时间（schema 24，待验收）

查询仅覆盖当前逻辑安装。组件、元数据、原 APK 的签名身份来自已验证档案；所有返回值独立复制，flags 决定可见字段，未知 flags 与其它包查询明确拒绝。GET_PERMISSIONS 尚未实现，不能以宿主授权冒充访客授权。

安装时间写入与安装发布同一数据库事务，使用该次成功操作的提交回执时间；升级保留首次安装时间。既有 schema 23 安装只能通过原始安装请求摘要和成功回执重建时间，无法证明的历史升级首次时间保留未知，自身 PackageInfo 查询明确失败，禁止猜测或使用宿主包时间。

待验收：原生 Android 基线、P1 安装中断/升级历史、P2 元数据/签名/权限，以及微信原包再启动。

原生基线 package-permission-baseline-01 揭示测试假设错误：普通应用的 VersionedPackage 版本选择器不会要求安装版本相等。已按该设备原生结果和 [AOSP ComputerEngine.getPackageInfoInternalBody / resolveInternalPackageName](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/core/java/com/android/server/pm/ComputerEngine.java) 修正自身普通包查询；静态共享库多版本不是本轮能力。该失败保留，不计通过。

## 单 worker 包域与真实代理链（2026-10-15，实施验收中）

实际微信第十九次启动表明：自定义 PackageManager 子类虽然实现了查询值，仍缺少平台对象的形态与代理生命周期。不能增加一个同名占位字段、忽略反射写入，或放开运行器私有字段来推进启动。目标 APK 仅作本机诊断，没有复制其代码进入 Tripoder。

采用单一包域：一个不可变 worker 仅承载一个客体安装，因此其 ApplicationPackageManager、ActivityThread.sPackageManager 与 ServiceManager 的 package 获取入口均进入该逻辑域。客体可在真实 mPM/sPM 字段安装自己的代理；受支持调用确实经过当前代理。无需根据线程名、ClassLoader 或栈片段猜测调用者身份。物理引擎组件仍需的自身元数据构成显式基础设施域；除此之外的手机安装包不开放。

实现分工：RuntimePackages 保留 immutable root IPackageManager 与受验证资源；GuestContext 的 final 字段和 ContextImpl 自身 mPackageManager 缓存拥有 facade，额外的 PM→Resources 登记使用弱引用，避免永久保留 Activity/配置 Context。既有 GuestPackageManager/GuestPackageInfo 暂作内部元数据投影实现。固定平台 packages adapter 负责构造、缓存关闭、启动前全局切换及完整实例方法准入。所有父类非静态具体方法也纳入拒绝边界，不能漏掉隐藏的安装、锁或 Provider 能力。资源始终来自已登记的 Context 视图；未知 facade 不猜测资源来源。

切换必须早于客体 AppComponentFactory、ClassLoader 工厂及 Application 构造。切换部分失败就终止 worker，不能恢复物理 PM 后继续客体。原始 physical IPackageManager 只保存在适配器私有闭包，用于明确的引擎包元数据、真实设备 feature 与 protected-broadcast 判断。仅保存旧 PackageManager 对象不够：它内部的静态缓存可再次进入逻辑域并递归。运行器控制、授权和持久账本不依赖可变客体代理。

root 的 asBinder 只返回本地 Binder；queryLocalInterface 指向 root，未实现 raw transaction 明确拒绝。getService/checkService/waitForService 等声明支持的 managed package 入口返回同一个本地 Binder；直接 rawGetService/native wait 及 package 服务通知等未支持入口明确拒绝。此能力不声称封闭任意 Native ServiceManager/Binder 获取，仍不是同 UID 恶意代码安全边界。

物理查询在实际 saved-IPM 调用边界再次核验引擎包名、真实 user 和组件集合，公开 Bridge 不能绕过 root 校验。组件集合来自切换前本地解析的引擎已安装 APK；不经一次巨大 Binder PackageInfo 回复搬运全部占位组件。物理 PackageInfo 仅开放基础信息/metadata/签名，完整组件、权限、instrumentation 清单不开放；单组件只允许已声明 Activity/Service/Provider。该基础设施域对 worker 可见，原先“宿主包完全不可查询”的测试断言已不符合新契约，改为明确的其它包拒绝检查。这不是第三方包透传。

ServiceManager.getIServiceManager 及其返回的底层服务接口没有完全封闭。平台隐藏 API 禁止普通客体反射查找的方法，测试单独记为 platform-hidden-api-denies-lookup，不计作运行器实际路由覆盖。适配器启动时要求固定签名存在，但签名存在与方法执行通过是两回事。JavaScript 适配异常必须转成明确 Java 异常；包域切换的字段和静态 getter 一致性检查失败即终止启动，不能返回空值继续。

ActivityThread 的 SDK 沙箱判定在创建 Activity 前使用其 mSystemContext 查询包服务。该精确 Context 在唯一合法激活时登记，获得同一逻辑 PM；不授予 android 包元数据，也不允许任意伪造 Context。激活写入前检查 root、物理 host 和首次初始化阶段，重复调用先拒绝再返回，不得先改 mPM。getApplicationLabel 在资源身份验证后调用平台 PackageItemInfo.loadLabel，保留平台标签选择及文本处理算法。

`package-world-application-09`：50 项定向检查通过，runtime APK `8cfdfef0cf49037b668ceeac2252505506791e39ee8afb399a7066597353269a`，run `packages-b81106afa9d74b29`。包含代理真实命中/恢复、后台查询、全局 getter 和配置 Context、物理包/用户/未声明组件拒绝、伪造 Context 拒绝，以及 32 组临时 Context/PM 的 64 个弱引用全部回收。未产生 Java/Native fatal。仅证明 Application-only 契约，包信息/XML 回归、Activity/组件、MCP 与真实目标仍需新构建验证。

新夹具门槛：真实 facade 类型与 mPM 字段；实例、后台线程和新配置 Context 的代理命中；sPM 静态 getter 与新 facade 绑定；恢复代理后停止命中；常规 ServiceManager 入口一致；外包、安装器、继承隐藏 API、raw Binder 拒绝；原物理 feature 与广播策略调用不递归。之后必须跑资源/组件、MCP 与真实目标。动态 dex 的 notifyDexLoad、包使用/更新通知仍需独立的有证据契约，不能作为空成功或转发宿主来消除错误。

参考：[ApplicationPackageManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/app/ApplicationPackageManager.java)、[ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/app/ActivityThread.java)、[ServiceManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/os/ServiceManager.java)。本节为正在实施的独立设计，不是 P6 验收结论。

## ActivityInfo 精确查询（2026-10-15）

微信第24次启动实际进入LauncherUI.onCreate，AppCompat的自身ActivityInfo查询此前未实现。新增查询复用已验证且平台解码的完整组件集合；不局限当前运行进程，不借宿主组件代替。alias返回自身name及原targetActivity，enabled/Application.enabled、directBoot过滤与GET_META_DATA分别处理；未知组件通过真实平台facade返回NameNotFoundException。未知flags、其他包仍拒绝。

每次ActivityInfo经平台Parcel深复制，ApplicationInfo使用逻辑安装路径/UID和独立metadata。修改返回对象不改变运行器的组件模板。自研夹具增加别名、禁用且位于另一声明进程的Activity，并分别运行原生与运行器查询。实现仍待本批设备证据，不能计为通用PMS完成。

本批设备证据：`activity-info-baseline-01`原生134项、`activity-info-runtime-01`运行器142项通过（其中新增ActivityInfo原生8项、运行器9项），固定Runtime APK a172c39922d85ad1f1df81b3649ae3fd06c6e4f152ef1ceb129cdaabcad574fb；不是全部包管理功能验收。
