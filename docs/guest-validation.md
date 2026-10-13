# 独立 APK 客体真机验收（2026-10-13）

已在当前连接的 M154FF / Android 17 / API 37 / ARM64 手机上安装 Tripoder Hook Lab **0.2.0**。独立构建的 `dev.tripoder.fixture` APK 未安装到系统，由宿主的 `:guest` 进程加载；Java 类经专用 `DexClassLoader` 解析，Native 库在该进程内加载，真实 Hook 生效。

最终同一宿主 APK 在 ADB 连接与电脑 ADB 服务停止两种条件下均完成“自检 → 客体重启 → 再次自检”，共四轮，每轮 **31/31 通过**。原宿主 Hook 回归 **25/25 通过**；本轮 SDK 测试 **26/26 通过**。

## 安装包与运行身份

| 产物 | 大小 | SHA-256 |
| --- | ---: | --- |
| [宿主 0.2.0](../artifacts/tripoder-hook-lab-0.2.0-arm64.apk) | 13,736,783 字节 | `3a98e63b8c6cf56ec014cf30676cfa509c1d47f139f2f5f1e5d3e4ed5b26f24a` |
| [独立客体 1.0](../artifacts/tripoder-guest-fixture-1.0-arm64.apk) | 2,421,380 字节 | `47596feda5a81b1cce28a759ea66567221f604dc5828ce11c665c3ff494605c3` |

- 两个 APK 均已通过签名校验，编译后 Manifest 不请求权限；使用本机测试签名。
- 宿主普通 UID `10389`，`debuggable=false`、`debuggerConnected=false`。没有 Root、Shizuku、权限授予、外部 Frida attach 或 shell 代理。
- 宿主内嵌的客体 APK 与独立产物逐字节一致；客体仅含自身的 `libguestfixture.so`，没有 Frida Gadget 或宿主代理。
- 进程内代理为宿主内嵌 Frida Gadget 17.23.3 离线 Script 模式，Java Bridge 7.0.13；无外部控制器或网络监听。
- 客体目录是宿主私有目录下的独立子目录，**宿主和客体共享 UID，不提供恶意代码隔离**。

[完整 APK 校验记录](evidence/android-guest-2026-10-13/guest-package-verification.json)

## 真实 Hook 与数据结果

| 阶段 | Java `GuestProbe.greet("phone")` | Native `guest_delay_ms(3)` |
| --- | --- | ---: |
| 原始调用 | `guest-original:phone` | 16 |
| 开启 Hook | `guest-hook:phone` | 503 |
| 参数热更新 | `guest-live:phone` | 903 |
| 拒绝非法参数后 | `guest-live:phone` | 903 |
| 参数回滚 | `guest-hook:phone` | 503 |
| 卸载恢复 | `guest-original:phone` | 16 |

每轮执行 1000 组连续真实调用；四轮冷进程报告中 Java／Native 回调各累计 **1005 次**。Native 入口机器码启用时变化，卸载后恢复原始字节。GNU Build ID 为 `efd182946797b85e83ef8c6693817f90f48f563a`。独立客体源函数不读取 Hook 参数或开关，调用结果由代理实际拦截改变。

31 项检查还覆盖：客体类不在宿主 ClassLoader 中、专用加载器、只读 APK／SO、APK 摘要不变、非法导入拒绝且保留当前包、证据绑定实际客体、数据文件读写与重新安装 Hook。

## 进程与界面生命周期

| 条件 | 宿主 PID | 客体 PID | 数据计数 | 结果 |
| --- | ---: | --- | --- | --- |
| ADB 连接下重启 | 27316（不变） | 31349 → 31448 | 9 → 10 | 两轮 31/31 |
| 电脑 ADB 停止时重启 | 30782（不变） | 31968 → 32201 | 11 → 12 | 两轮 31/31 |
| 返回首页后再次进入 | 25724（不变） | 28836（不变） | 6 → 7 | 两轮 31/31 |

显式重启后客体 session 与 processInstanceId 更新，旧进程证据不复用。页面解绑后再次进入则复用存活进程中的运行时和 ClassLoader，避免重复加载 JNI 库。这里只验证显式重启和页面重新进入；没有注入任意 Native 崩溃或验证长期故障恢复。

实际点击界面完成：应用参数后 `guest-ui:phone / 1203`，回滚后 `guest-hook:phone / 503`，卸载后 `guest-original:phone / 16`；Native 指令变化标志分别为 true、true、false。通过系统文件选择器选择同版 APK 后再次通过 31 项自检，持久化计数到 8。

[页面重新进入记录](evidence/android-guest-2026-10-13/guest-lifecycle.json) · [界面操作记录](evidence/android-guest-2026-10-13/guest-ui.json) · [系统选择器导入后的自检](evidence/android-guest-2026-10-13/guest-import-result.json)

## ADB 不参与执行的验证

应用先安排 20 秒后自检，电脑停止 ADB 服务 **40.3 秒**，期间对本机 5037 端口检查 **80 次**，均无监听。两轮自检以及中间的客体进程重启全部位于该区间内；恢复电脑 ADB 后才读取报告和截图。

此验证没有拔除 USB、关闭手机调试开关或撤销电脑授权。它证明本轮加载、Hook、参数与重启操作自主发生在 APK 内，无需活动 ADB 服务；实现路径没有 ADB 激活步骤。普通安装后可点击“15 秒后自检”并拔掉 USB 复现。

- [连接状态的两轮完整报告与重启断言](evidence/android-guest-2026-10-13/guest-connected-restart-result.json)
- [电脑 ADB 停止期间的两轮完整报告与重启断言](evidence/android-guest-2026-10-13/guest-offline-restart-result.json)
- [ADB 时间、端口与未安装客体的检查记录](evidence/android-guest-2026-10-13/guest-offline-host-verification.json)
- [原宿主 25 项回归报告](evidence/android-guest-2026-10-13/host-v0.2.0-result.json)
- [最终手机截图](evidence/android-guest-2026-10-13/screen.png)

## 范围与下一步

本阶段完成的是**独立自有 APK 的受控代码载荷运行器**。仅接受构建时固定摘要的测试包，调用预先约定的 Java／JNI 入口；未在宿主中启动客体 `FixtureActivity`。APK 字节和签名保留不等于系统包身份或权限已经虚拟化。

尚未完成任意 APK 的组件生命周期、资源、窗口、系统服务与图形兼容；微信、钉钉、王者荣耀、原神均未验收。应用内目前只支持参数动态修改，任意 Hook 代码热替换、AI 生成、脚本隔离仍待实现。这些短时检查也不代表性能基准或长期稳定性结论。

下一步应以自有 APK 的 Activity／资源／图形样例验证组件兼容层，再逐步扩大应用启动范围。构建和手机操作见 [客体运行器说明](guest-runtime.md)。
