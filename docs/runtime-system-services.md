# 系统服务的第一批能力边界

2026-10-15 设计记录。微信/王者旧启动证据共同停在 `SYSTEM_SERVICE_UNSUPPORTED:activity`；微信另需自身 Service 元数据。以下能力已实现：原生对照 21 项、修复关闭回执后的 P2 组件回归 485 项通过（`completion-fence-p2-03`，Runtime APK `3e728e4f9af18963e8686f7f6fe47923f66377bec90a6d70088b194073450045`），本轮 Java/Native fatal 均未观察到。未写明设备通过的能力不算已支持。

## ActivityManager

客体需要的是具体查询语义，不是整个宿主系统服务的权限。每个 RuntimeEngine 持有单独的客体 facade；通过已固定平台的必需 adapter 构造，所有受管 Context 返回该实例。宿主框架自己的缓存实例保持独立。构造器不匹配或 adapter 缺失直接失败。

| 查询 | 权威与范围 |
|---|---|
| getMemoryClass / getLargeMemoryClass / isLowRamDevice | 明确授予的设备内存特征，读取真实平台结果 |
| getMemoryInfo | 填充调用者对象，保留完整平台字段，不返回共享可变对象 |
| getRunningAppProcesses | Broker 认证实际 PID/UID/generation，派生 space/package/install；注册的 ATTACHED/RUNNING 进程与真实系统 PID/UID/物理进程名交叉匹配，返回逻辑名称和真实 PID/UID |
| getHistoricalProcessStartReasons | 已认证的同空间/包/安装/本次启动历史；只投影实际记录的启动和 onCreate 时间，失败保留 ERROR，不虚构首帧。详见 [启动历史](runtime-start-history-design.md) |

进程查询采用锁内身份快照、锁外系统查询、锁内代次复核。Application.onCreate 期间的 ATTACHED 进程必须可查询。其他空间、包、authority 和工作台进程不进入结果；范围外的 importance reason 身份不外泄。系统查询不可用明确失败，不能用注册表伪造观测结果。

其他实例方法一律拒绝，尤其任务操作、进程终止、用户数据清除、服务控制、任意 PID 内存、历史退出信息、监听器和 FD dump。静态方法不经过 facade，必须另设策略和验收；该适配不构成对同 UID 任意 Native 代码的强隔离。

## 自身 ServiceInfo

复用校验过的 APK、RuntimeManifestIndex 及已解析的 GET_SERVICES 元数据。索引覆盖自身包全部声明进程。支持 int 与 ComponentInfoFlags 重载，首批仅 GET_META_DATA 和 MATCH_DISABLED_COMPONENTS；未知 flags 和其他包查询拒绝。

保留服务声明的 processName；不能替换为查询它的 worker 进程。缺失或默认不含的 disabled 服务抛 NameNotFoundException。ApplicationInfo、ServiceInfo 和 metadata 返回防御性副本，不能让调用者修改权威记录。

微信实际失败 flags 为 `0xc0280`，增加 MATCH_DIRECT_BOOT_AWARE / MATCH_DIRECT_BOOT_UNAWARE 的声明筛选：单个 flag 只选对应组件，两个都选两类，disabled 仍独立受 MATCH_DISABLED_COMPONENTS 控制。它只提供元数据查询，不开放设备加密存储或锁屏前执行。新增 7 项 direct-boot 及 1 项 ApplicationInfo 默认声明进程检查已通过：原生 `direct-boot-android-baseline-01` 共 29 项，Runtime `logical-application-p2-01` 共 509 项。ApplicationInfo.processName 现保留 Manifest 默认进程，当前 Worker 的身份仍由 RuntimeEngine.processName 独立持有。

## 验收

自研同一原始 APK 在 Android 和 Tripoder 分别验证内存特征、MemoryInfo 字段、当前与声明远程进程、ServiceInfo 标志和元数据；Tripoder 额外验证跨空间过滤、旧 PID/代次拒绝、未知服务方法拒绝和记录不可变。随后运行已有组件回归，再重试固定微信/王者版本。首页、登录前初始化、登录、业务场景分别记录，不将解除一个初始化异常算成 P6 通过。

微信的 GuestContext→ContextImpl 类型冲突已经通过实际字段写入定位，所有权修正与验收门槛见 [Application 设计](runtime-application-design.md)。不能把 GuestContext 整体换回宿主 Context 来掩盖类型错误。

平台参考：[AOSP ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java) 的 `handleReceiver` 在工厂调用前读取 Application 的底层 Context；[AOSP Application](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/Application.java) 的 attach 关联底层 Context 的 LoadedApk。它们用于分析所有权，不等同于当前小米固件源码；客体是否修改物理框架引用仍须设备观测，不能仅凭类型异常下结论。
