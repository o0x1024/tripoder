# 设备观察能力

2026-10-15，微信第 20 次实际启动暴露电池、流量统计、Wi-Fi P2P 的获取缺口。以下为独立实现契约；设备通过后另附证据，不使用 VirtualApp 代码。

## 电池

电量、电流、充电状态和预计充满时间是设备全局事实，明确授予只读查询；它们不是某个逻辑空间的资源。每个 worker 返回独立 BatteryManager facade，不携带原始电池服务句柄。固定 API37 构造器必须匹配；仅 isCharging、computeChargeTimeRemaining 及公开数值属性 1–6 路由到真实平台查询，保留不可用 sentinel 与异常。getIntProperty 的不可用值按客体 targetSdk 的平台语义投影，不能采用引擎 targetSdk 替代客体声明。

设备序列号、未知属性、字符串属性和充电控制明确拒绝。平台自己创建的 manager 保留独立；这是执行协议中的接口边界，不能阻止同 UID 任意 Native/反射代码直接取得系统服务。自研原生/运行器夹具记录真实查询值、稳定 facade 和拒绝行为，不伪造 0/已充电。

## 流量观察

Process.myUid 与 ApplicationInfo.uid 继续使用真实执行 UID，不制造虚拟 UID。TrafficStats 当前 UID 的收发字节/包数、移动网络/全设备及接口字节/包数明确授予，原平台执行、缓存、UNSUPPORTED 与异常保留。它们包含引擎、同 UID 的其它空间、历史与并发活动，不能用于空间计费、网络隐私隔离或单应用性能归因。接口计数是同一设备观察域。底层公开计算入口 getUidStats/getTotalStats/getIfaceStats 分别检查真实 UID 与计数类型，再走原实现；不能因内部辅助调用被误拒绝而把真实统计变成假值。

其它 UID、UID/标签归因修改、profiling 和测试服务替换不授予。NetworkStatsManager 返回稳定、无底层服务句柄的 facade；历史查询和订阅抛出 NETWORK_HISTORY_CAPABILITY_NOT_GRANTED 的 SecurityException。这个异常说明 Tripoder 本批未授予能力，**不声称 Android 一定缺少 PACKAGE_USAGE_STATS**；平台在部分查询上可以提供 own UID 历史。没有返回空统计或 0，也没有替换调用为另一种统计来伪装原查询。自研夹具用无适配 authority 的前后真实 UID 计数夹取客体结果；数值不断变化，不要求跨时刻完全相等。

## Wi-Fi P2P

facade 与独立 physical manager 分开。获取服务不构造假 Channel：initialize 验证当前 engine 的 Context 对象链、客体声明的 ACCESS_WIFI_STATE/CHANGE_WIFI_STATE、客体 listener ClassLoader；保留调用者 Looper，随后用真实引擎身份执行平台 initialize。真实 getMessenger、AsyncChannel 连接与登记都由系统完成，null 与异常不改成成功。仅提供六项硬件支持查询和 Channel initialize/close；扫描、发现、组网、取消连接、移除组、对等端/本机网络身份查询均未授权。host 清单增加上述两个 normal 权限，未增加 location/nearby 运行时授权。

每 worker 至多 16 个已登记或正在初始化的 Channel。初始化前创建 pending callback 记录，真实提前断连会交给 listener 并关闭回调准入，返回后不会重新标记活跃。close 先关闭新回调准入，再执行平台 close，成功才移除记录；失败保留记录。已进入的 callback 可完成。普通内部未登记 Channel 的初始化失败清理由原平台执行；本协议不声称阻止敌对同 UID 反射取得物理对象。绑定的是对象与当前 worker，process death 交给真实 Binder death 生命周期，不据此推定全部无线资源已回收。

独立 Channel token 不等于独立 radio；主动 P2P 功能需要以后明确的全局资源租约。首批自研对照计划验证真实 initialize/close、19 次顺序会话与关闭后容量释放、未授权操作/伪 Context/外来 listener/null Looper 拒绝；没有通过实际无线断连或平台 close 失败注入前，不将这些生命周期分支标记为真机已验证。

## 电源与温度（限定设备验收通过）

电源 facade 只开放显式的设备状态查询以及真实 thermal status/headroom 订阅，不授予 WakeLock、重启、休眠、亮度和省电策略修改。isIgnoringBatteryOptimizations 只接受当前逻辑包，并返回实际引擎包的系统策略；这不是独立空间的电源配额。固定 API37 四参构造器匹配，原始电源/温度服务句柄不放在 facade 内。

状态与 headroom 使用独立的对象身份登记。最多 32 条，登记状态 REGISTERING/ACTIVE/REMOVING/FAILED 区分操作中与未知失败。真实初始回调可能早于注册返回，因此 REGISTERING 期间保留实际事件（最多32条，超限则注册失败），成功后才交给指定 Executor；不丢事件后通过额外查询制造初始事件。默认重载使用真实主线程 Executor；客体代码不在 Runtime 锁内运行。取消先关闭新回调准入，排队回调执行前再次核对身份与状态；系统取消失败保留未确认登记，不能伪装释放。

headroom保留NaN、预测参数、平台限频和异常；thresholds返回防御副本。夹具验证真实初始事件、请求的Executor、重复和取消后的实际排队事件、权限边界。默认主线程回调先由正常Android Activity对照验证；Application-only运行器夹具不能在阻塞主线程的onCreate内证明默认回调交付，需另一个实际生命周期路径补验。没有模拟升温，也不声明取消失败和热服务故障已验收。

`power-baseline-01` 原生126项、`power-runtime-01` 运行器133项通过，运行器APK 977e8bbd…；这些总数包含此前设备服务和包域回归。`power-agent-01` 对应产品MCP18/12通过。实际温度事件、指定Executor与停用排队回调已验证，默认主线程运行器回调仍待后续实际Activity路径。
