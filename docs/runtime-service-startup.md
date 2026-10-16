# Service 请求与进程初始化分离

2026-10-15。微信第23次启动在 Application.attachBaseContext 中调用跨进程 startService。旧路由先 prepare 目标 Worker，等待适配器加载后才返回物理 Service 路由；该等待占用了调用方 Application 的10秒窗口，实际超时。仅增加超时会继续把两个独立生命周期绑在一起。

## 执行边界

authority 验证调用方、安装、声明组件、进程和参数，持久预留目标 session 与 Service lease 后返回。调用方执行真实 Android startService/bindService；只有系统返回后才写 ANDROID_REQUEST_ACCEPTED。它不表示目标 Application、Service.onCreate 或业务完成。

目标进程由 Android 的真实组件请求创建，Factory 经 BootstrapProvider 核验已预留 lease/session/物理 PID，再原子认领 peer。没有预留的恢复入口仍要求旧代次确证死亡、开放且安装一致。Service 实例 claim 在 guest Application 初始化前执行。没有等待目标适配器、没有自定义调度队列和重复启动。

原生对照证实：不带 BIND_AUTO_CREATE 的绑定也可能被 Android 接受并保留连接，不能按“无 Service 实例”返回 false。此模式已补持久 PASSIVE 组件租约：保存安装/逻辑进程/物理组件所有权，但没有 process generation、运行 session、Worker bind 或启动 watchdog。系统仍收到原始绑定标志。后续真实 start/自动创建请求预留运行 session 并将同一租约提升为 RESERVED，实际系统回调连接原先的 ServiceConnection。仅有 PASSIVE 租约不是创建 Application/Service 的授权。

绑定/解绑结果仍由 Android 回执确认。未激活的 PASSIVE 租约只有在无 PREPARED 调用、无未释放绑定、真实系统无该 Service 记录时才能回收；已激活租约仍走原有死亡/系统引用检查。安装更新和槽位分配都受 PASSIVE 所有权约束。此新增路径的设备验收正在进行；不是已通过全部竞争和死亡恢复的结论。stop 未存在组件保持不启动 Application。系统请求异常/ACK丢失仍保留 UNKNOWN，不假写成功或自动重放。

普通绑定允许公开调度标志 AUTO_CREATE、DEBUG_UNBIND、NOT_FOREGROUND、ABOVE_CLIENT、ALLOW_OOM_MANAGEMENT、WAIVE_PRIORITY、IMPORTANT、ADJUST_WITH_ACTIVITY、NOT_PERCEPTIBLE；原始位值进入操作摘要，并原样传给 Android。应用和 authority 共用同一校验，异常包含完整十六进制值。不支持的 capability 委托、后台启动授权、external/isolated 仍明确拒绝；不能清除未知标志后重试。夹具已用 AUTO_CREATE | NOT_FOREGROUND | WAIVE_PRIORITY 验证真实绑定及回调，WAIVE_PRIORITY 单独绑定正验收中，其他组合的调度优先级效果尚无设备验收。

## 验收

自研夹具直接在 Application.attachBaseContext 发出跨进程 startService，并使用真实 bind、Binder、startId 和 stop 回调验证；运行器验收仅启动 Application，不以额外 Activity 任务占用掩盖调度错误。原生与容器分别验证逻辑组件、实际远端 PID、回调、无 AUTO_CREATE 的缺失服务、同一物理 UID，以及固定设备下请求耗时。两秒只是本机验收阈值，不是所有设备的服务时限承诺。

`service-startup-runtime-02` 在 Runtime APK `46749b61e696906b76317d69142175039fb009bfe66f8d22cd01b9c5f30897e6` 上13项通过，实际请求约149.47ms；远端真实Binder、startId、逻辑身份、解绑和停止均确认。无AUTO_CREATE仅验证明确拒绝，不算功能支持。仍需更完整的 start/stop/bind 竞争、请求已发但 ACK 丢失、系统重启以及迟到无 Intent 的 Service 创建资源退休证明；不以本批解决这些边界。
