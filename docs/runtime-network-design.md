# 网络观测能力

2026-10-15。微信真实初始化请求 connectivity，目前整体拒绝；本批按具体系统能力实现。独立研发，不使用 VirtualApp 代码。

## 权威与范围

客体 Context 返回每个 RuntimeEngine 独立的 ConnectivityManager facade。固定平台 adapter 创建该对象，系统缓存 manager 保持物理引擎身份。客体 APK 声明 ACCESS_NETWORK_STATE 才能调用网络状态能力；声明从已校验 APK 读取。平台结果使用引擎真实 UID，不能冒充原始安装应用 UID。

首批只开放当前网络、网络列表、NetworkInfo、NetworkCapabilities、LinkProperties、代理、计费状态、后台限制，以及默认网络/指定 NetworkRequest 的被动回调。真实系统无网络则返回其真实空值。未知方法、主动申请网络、路由绑定、设备设置、系统默认网络或其他 UID 查询均明确拒绝。

引擎与测试运行器声明正常权限 INTERNET 和 ACCESS_NETWORK_STATE，支持用户导入应用的网络运行；不申请电话、位置、联系人等危险权限。原始 socket 使用同一引擎 UID，本批不是每应用网络防火墙，不能宣称不同空间有独立 VPN 或网络 UID。

## 回调生命周期

回调对象必须由当前客体 ClassLoader 所有，最多同时 32 项。原对象以身份映射到独立平台代理；重复注册拒绝，取消只接受本代次注册。被动注册提交真实 Android，平台成功之后状态 ACTIVE；平台错误原样传播。取消先关闭新的交付入口，再向 Android 取消，失败保留失败状态以便重试，不能报告取消成功。

代理只转交真实系统事件；不补造 onAvailable。排队但尚未开始的旧事件在取消后丢弃；已进入客体的回调允许完成。位置敏感 callback flags 本批拒绝。进程终止由真实 Binder 死亡回收平台注册；跨进程/跨空间不复用对象。

## 验收门槛

同一自研 APK 原生对照与运行器分别查询真实网络，并接收一次实际默认网络 callback 后取消。运行器额外验证同 Context 稳定对象、重复注册/错误对象取消、未知修改方法拒绝、已取消 callback 不再进入以及不声明权限的客体被拒绝。记录真实 PID、网络 handle 和回调线程；不把接口构造成功算作真实网络或目标应用通过。

`network-runtime-p2-01` 已通过 531 项（run `p2-3400b1b26c1845c6`，Runtime APK `ee18ce3912816b4f9ace65f7c9bb1617cfec7a0708aadddcff47b261424a1a91`），其中新增 10 项网络观测/回调门槛均通过。无权限声明的 P2 自研 Application 在每次启动中验证查询被明确拒绝。未验证网络切换、网络服务死亡、32 项容量耗尽及真实互联网请求，不能扩大为完整网络验收。

原生对照 `network-android-baseline-02` 在网络检查前，被“当前启动记录立即可见”的旧测试假设阻断；`03` 等待 5 秒后仍失败，增加原始观测发现该固件返回持久历史快照，多数 PID 为 0，未包含当前 PID，不能归因于首帧时序。修正原生对照只检查实际自身包/UID 历史；运行器的当前 PID 与 onCreate 内可见性仍作为独立契约严格验收，不删除运行器门槛。两轮失败保留。

参考公开 Android SDK 和 [AOSP ConnectivityManager](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/master/framework/src/android/net/ConnectivityManager.java)，不复制实现。

修正原生历史查询测试前提后，`network-android-baseline-04` **34 项通过**（run `resources-402103fadf934576`）。实际 PID 24319，查询与回调网络 handle 同为 553161510925，回调线程 ConnectivityThread，观测到 3 个网络。网络状态是当轮真实设备快照，不作为固定预期值。
