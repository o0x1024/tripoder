# Thin 模式性能减法（2026-10-20）

> 历史设计与验收记录。当前已收敛为单运行时，运行方式和迁移规则见 [单运行时收敛](single-runtime.md)。本文的模式名称与测量值仅描述当时构建。

目标是让普通客体使用 Android 的执行器和系统服务，运行时只保留包、资源、数据目录和组件承载所必需的转换。当前完成首轮精简；**尚未验收整体冷启动达到原生性能**。

## 已实施

- **保留 JIT/AOT**：thin 的 `ManagedHook.initializeProcess(true)` 不调用全局退优化、不关闭 JIT、不修改进程调试状态。仅被 Hook 的方法设置 `kAccCompileDontBother`，原方法备份和停用后的入口使用稳定桥，避免持有可能被 ART 回收的 JIT 代码地址。thick 保留完整适配，在固定适配初始化期暂停 JIT，完成后恢复原有设置；新目标首次发布前反优化调用者，并使用同样的方法级编译/内联屏障。
- **预热不提前决定执行策略**：无客体身份的空闲槽只准备框架元数据和 helper 模板；收到实际模式后才初始化 ART 并准备该模式需要的目标。
- **七类系统服务直接使用 Android 实现**：网络连接、电话、音频、电池、电源、Wi-Fi P2P、网络统计。thin 不再安装这些 Manager 的整类 Hook，也不再包装流量标记、UI 音效、失败诊断和 Wi-Fi Channel 关闭操作。固定 Hook 从 **2300 降到 1021**。
- **标准 ClassLoader**：thin 用真正的 `PathClassLoader` 直接加载 APK，删除资源代理父加载器和空的外层加载器。APK 资源由平台加载器提供，避免不被 ART 识别的自定义加载器链。
- **PendingIntent 只读查询不写事务**：thin 的普通已存在 token 查询、纯 `FLAG_NO_CREATE` 查询不再创建 operation 行和 SQLite 事务。`UPDATE_CURRENT`、`CANCEL_CURRENT` 及首次创建仍沿原有真实变更路径执行。
- **去掉额外隐藏与监控干预**：thin 的 maps/mounts 走真实 procfs；保留必要的进程名和数据路径转换。停用额外的 linker 路径隐藏、调试崩溃处理器，以及对厂商监控线程的反射干预。
- **可观察的执行策略**：Hook status 返回 `executionPolicy` 和 `fixedBindings`。验收脚本按实际发布的绑定数核对 bootstrap，不再假定所有模式都必须安装同一数量的 Hook。

ART 对不可编译方法的内联限制见 [AOSP HInliner::IsInliningAllowed](https://android.googlesource.com/platform/art/+/master/compiler/optimizing/inliner.cc#1477)。本实现仍受仓库固定设备 ART 合约约束。

## 真机证据

设备：`d861a4fa`，API 37；本轮重新构建并覆盖安装了产品与测试 APK，保留应用数据。构建使用仓库已有 `context/probe/sdkroot`、`context/probe/ndk-shim`（该本地驱动以 API 35 native stubs 编译；产品 minSdk 仍为 36），没有修改全局 SDK。

| 检查 | 结果 |
| --- | --- |
| 控制面集成（含 thin/thick 策略、模式切换、真实 authority 死亡恢复） | 71 项通过 |
| 真实 UI Hook 激活、实际命中、停用、原行为恢复 | 11 项通过 |
| RuntimeMode host 检查 | 24 项通过 |
| 原有 interpreter policy host 检查 | 通过，含 256 次重复验证和外部 owner 拒绝 |
| 产品构建、固定平台二进制合约、JNI 导出检查 | 通过 |
| 网易云音乐 thin 启动并持续运行 | 成功；最终观测窗口未发现 Java/native fatal |

网易云最终进程的策略为 `preserveCompilation=true`、`claims=0`、`policy=method-entry-only`。最终一次启动：工作台点击至 Android 首次绘制约 **3159ms**；其中 DEX 加载约 **5ms**，框架 Hook 初始化约 **585ms**。较早自定义加载器样本的 DEX 加载约 **1386ms**。

先前原生安装的一次 `am start -W` 冷启动为 **520ms**。它与工作台点击的计时起点不同，不能直接作为严格倍数对比；但足以说明当前仍有明显的宿主准备开销。也不能把 guest Activity 自身约 418ms 的绘制时间称为整个应用的冷启动时间。

最终一次持续运行约五分钟的 gfxinfo 样本：61133 帧，平台 deadline jank 0.01%，legacy jank 19.19%，P50 12ms、P90 20ms。两套 jank 定义同时记录，不能只选择较好的一项。播放动画、页面、内容缓存与刷新率没有与原生安装对齐，**这些是运行观测，不是同工作负载的原生性能对比验收**。

最初一次控制面测试在入口过期回收阶段超时；后续两轮完整控制面测试均通过，未通过增加超时或放宽断言处理。连续动画使 shell `uiautomator dump` 无法等待空闲，未据此生成滚动对比结论。

精简证据与构建身份位于 [evidence/thin-performance-2026-10-20](evidence/thin-performance-2026-10-20/summary.json)。未把应用截图、账号信息、接口响应或完整运行日志放入仓库。

## 剩余验收边界

冷启动仍需削减工作台到 slot 的准备、剩余约 1000 个框架 Hook 的发布和客体 Application 初始化开销。交互、播放和冷启动需要在相同 APK、数据状态、刷新率及场景下重复测量，才能声称追平原生。

保留编译后，不承诺旧的已编译/内联调用者全部热迁移；`preexistingCompiledFramesCovered` 和 `fullCallerCoverage` 继续明确返回 false。Hook UI 验收通过并不等同于所有商业应用或所有 JIT 调用形态的完整覆盖。

## 同日补充：沉浸式窗口白边修复

网易云的窗口本身已经覆盖 `1208×2624`，但旧版通用 Insets 补丁只判断 `FLAG_FULLSCREEN`，把使用 `LAYOUT_FULLSCREEN` 的页面误当作普通页面，在内容根节点加了顶部 144px、底部 60px padding。客体内容因此缩至 `[0,144][1208,2564]`，露出浅色窗口背景。

现在尊重 `LAYOUT_FULLSCREEN` / `LAYOUT_HIDE_NAVIGATION`：客体已经管理系统栏布局时不安装额外 listener；普通旧版页面继续使用兼容避让，并保留应用原有 padding。若普通窗口在创建后切换为沉浸式，只撤回兼容层添加的 padding。

已重新构建、覆盖安装并目视验证上下白边消失，网易云内容根节点恢复为 `[0,0][1208,2624]`，状态栏与手势导航保持可用；普通计数器页面的真实 UI Hook 回归 11 项通过。Native core 未变化。[本次精简证据](evidence/guest-insets-fix-2026-10-20/summary.json)
