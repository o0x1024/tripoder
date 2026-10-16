# Activity 创建准入与 Instrumentation

2026-10-15。微信第22次启动证实：客体自定义 Instrumentation.newActivity 可以直接创建替代对象，AppComponentFactory 并非必经入口。旧实现只在 Factory 返回时登记实例，在这种合法创建方式下会遗漏 ticket、逻辑 Context 和结果归属。此批独立实现平台创建边界，不复制目标应用代码。

## 创建协议

热入口在固定 API37 的 ActivityThread.performLaunchActivity 开始处，用真实 record 的物理 Intent 先向 authority bootstrap/claim。一次 permit 绑定线程、record/token、ticket、delivery 与物理组件；同步嵌套 launch 明确拒绝。Application 初始化完成后，选择当前真实 Instrumentation 的 newActivity 声明类，按 Class 身份安装入口，避免不同 ClassLoader 同名类冲突。

客体 Instrumentation 收到逻辑 ClassLoader、组件类名与去除路由凭据的 Intent。它可以直接创建、调用 super，或调用 super 后替换返回对象。仅最外层最终对象被登记；必须属于当前安装的 loader、尚未 attach、未登记且非 null。实际实现类可以不同，逻辑元数据仍归属已认领的原组件。

base Instrumentation 的构造选择保持本机 Android 语义：未绑定 mThread 的应用自建实例使用平台默认 AppComponentFactory；绑定当前 ActivityThread 的实例使用本安装 factory。绑定其它线程明确失败。这里不根据失败再切换构造方式。

冷入口已进入默认 Instrumentation/Factory 后才加载 adapter，不会重新进入已开始的外层平台函数。Factory 复用同一 permit 协议，并在 Application.onCreate 结束后读取最新 Instrumentation；不固定绕过应用自定义构造。实例创建失败、异常和 ACK 丢失均保留真实失败/UNKNOWN，不重试构造。

## attach 和主题

在真实20参 Activity.attach 入口校验 ticket/delivery/物理组件及热入口 token，然后传入逻辑 Context/Application/Intent/ActivityInfo。真实 ActivityThread、Instrumentation、Binder token 和窗口参数继续由 Android 管理。这样客体 attachBaseContext 和自定义 callActivityOnCreate 在 super 前也应看到逻辑身份。attach 返回后才上报真实附着。

平台在 attach 后还会读取原 record.activityInfo 的 theme。只对这一个已附着的物理 ActivityInfo 对象建立弱引用主题映射，避免逻辑主题被随后物理 stub 的主题覆盖；不改整个 record/packageInfo。实际主题和资源还需原生/运行器夹具对照。

## 关闭与边界

热入口已关闭时，performLaunchActivity 仍执行真实原方法（Android 为该 token 注册 client record），但其 newActivity 返回仅负责结束的宿主 RejectedActivity（setResult(CANCELED)+finish），不进入客体 Instrumentation 的 getContext/lifecycle，也不加载客体类。冷入口同样返回该宿主 RejectedActivity。两条拒绝路径因此统一：真实 token 恒有一个会立即取消的 record。这样即使服务端独立派发 TopResumedActivityChangeItem 等非生命周期事务（其投递与 launch 成败无关、无法靠 finishActivity 抢先阻止），也只会命中一个正在 finish 的宿主活动而无害，不会因 record 缺失打挂承载进程中的 successor。早先“热入口直接返回 null 依赖 handleLaunchActivity 的 finishActivity”已被真机证伪（独立 TopResumed 事务先于 finish 到达，见 self-hook-product-2026-10-16/entry-h26 证据）。两处都是入口拒绝，不代表旧物理组件或 affinity 已退休。

每次 launch 重新选择 Instrumentation，并在 Instrumentation 基类构造返回客体之前按运行时 Class 登记其 newActivity 声明。这样在 performLaunchActivity 内由 getContext override 创建并替换的新 Instrumentation，也会先经过同一 permit 再获得逻辑参数。自研原生三种模式各21项、容器91项已验证该场景。绕过Java构造器的Unsafe对象或adapter安装前未登记的第三方实例不在此结论内；同UID任意Native代码仍不是强隔离对象。旧10参 newActivity 自行 attach 的返回对象不属于本协议，已 attach 对象拒绝。

首批验收为自研应用安装 Instrumentation，三种 root 构造方式、super后替换child、真实结果返回、attachBaseContext/资源/主题观察，以及默认主线程温度回调。后续还需冷恢复、错误对象/异常/同步重入和关闭入口回归。源码实现不是验收通过。

## 实施审查修正

一次构造使用稳定的逻辑 Intent，客体 Instrumentation 对普通字段的修改传给实际 Activity；完成时仍核验逻辑组件与包不变。物理路由 envelope 独立保存。冷路径二次 claim 被关闭时，仅由 Factory 登记的准确宿主拒绝对象可以使用其匹配 envelope attach，不放行任意未登记 Activity。attachBaseContext 可包装已授予的 GuestContext，后置检查验证对象链，不能再次替换其 base。

显式 Activity Intent 允许有界 action/categories 及与显式 component 一致的 package；请求仍必须显式，不提供空间外解析。摘要现采用[版本3有类型数据契约](runtime-activity-flags.md)，保持UTF-8长度前缀并排序键与categories；避免字符串值伪造后续action字段。旧非空摘要不做兼容重算或自动重放。

真实结果仍通过 Android 原始 Intent/结果通道传输。支持null数据及有界标量extras（最多16项、总Parcel48KiB、单字符串16Ki字符），拒绝FD、Binder、自定义Parcelable、ClipData及尚未支持的URI/路由字段。保留调用方的整数结果码，明确区分RESULT_CANCELED与自定义非取消码；callback完成后才写接收回执。没有把任意返回值包装成自研JSON成功。
