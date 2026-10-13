# 王者荣耀兼容性：第一阶段

2026-10-13，目标为手机当前已安装的 `com.tencent.tmgp.sgame`，版本 **12.1.1.1**，versionCode **1201010103**。宿主为未 Root 的 Android 17 ARM64 手机，Tripoder 为普通 UID 的非 debuggable release。

**已完成安装包清点和真实入口类／资源加载预检；尚未在 Tripoder 中启动游戏，也未验证游戏 Hook。** 项目首页继续显示“等待兼容性适配”，添加应用不等于运行兼容。

## 当前证据

- 单一 base.apk，701,939,162 字节，6 份 DEX。
- APK SHA-256：`6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384`。
- 259 个 Native 库条目，包含 ARM64 和 ARMv7；观察到 libunity.so、libil2cpp.so 和 libGameCore.so。
- 85 个 Activity。主入口为 `com.tencent.tmgp.sgame.SGameActivity`，Application 为 `com.tencent.tmgp.Common.SGameApplicaton`（拼写来自安装包）。
- Tripoder 的 `:compatibility` 进程通过 DexClassLoader 加载 Application、主 Activity、UnityPlayer 三个类，类型关系均符合预期。
- PackageManager 返回的目标资源可读取应用名称和图标。

探测代码显式使用 `Class.forName(name, false, loader)`，没有实例化目标类、执行目标 Application／Activity 生命周期、加载目标 Native 库或发起游戏登录。类加载成功只能证明这一加载阶段可行。

原始报告：[设备预检 JSON](../artifacts/hok-compatibility/on-device-preflight.json)、[安装包清单](../artifacts/hok-compatibility/archive-inventory.json)、[核心 ELF 依赖](../artifacts/hok-compatibility/engine-linkage.json)。手机端报告保存在对应项目的 `compatibility/latest.json`，可从“测试 → 查看完整报告”打开。拉取到电脑的原 APK 仅用于本地分析，位于已忽略的 artifacts，不打包进 Tripoder。

## 本次实施

- 任意已添加且当前可见的安装包均可执行结构检查：版本、签名摘要、base／split APK 摘要、DEX、ABI、Native 库、组件和进程。
- 单独的非导出 `CompatibilityService` 运行探测，使用独立于客体运行器的 `:compatibility` 进程，避免污染已运行示例的 ClassLoader。
- 仅继承 boot class path，避免宿主类意外掩盖目标依赖缺失。探测结束解绑并退出该进程。
- 45 秒超时、进程退出和加载异常均返回失败。报告含 UID、PID、宿主 APK 摘要和时间；`compatible`、`launchAttempted`、`hookVerified` 仍为 false。
- 结果和检查记录归属项目；运行中不使用 Root、ADB、Shizuku 或外部进程注入。

加载实现依据：[DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)、[PackageManager.getResourcesForApplication](https://developer.android.com/reference/android/content/pm/PackageManager#getResourcesForApplication(android.content.pm.ApplicationInfo))。设备实测结果以本项目 JSON 为准。

## 下一阶段的顺序与验收门槛

1. **通用客体描述与生命周期**：将当前写死的 FixtureActivity／FixtureApplication 入口替换为已校验的包描述；在项目数据目录中准备 Application Context、资源和生命周期。验收目标是生命周期可进入且异常可收集，不能用系统拉起原游戏代替容器启动。
2. **包身份、组件路由和文件路径**：补充目标 PackageManager、Activity／Service／Provider 路由、进程名、权限与存储语义。系统 UID 仍属于宿主，不能通过改包名就假定获得原应用权限或数据。
3. **Native 和图形初始化**：处理 ARM64 库目录、JNI、链接命名空间、Surface／Unity 生命周期。静态依赖中 libunity.so 需要 libLightProfiler.so 和 libtprt.so，libil2cpp.so 需要 libtprt.so，libGameCore.so 需要 libpandora.so 和 libpuerts.so；包内有这些条目，但实际链接仍待验证。不能假定只加载 libmain.so 就完成 Unity 环境。
4. **游戏启动与 Hook 验收**：先达到稳定首屏，再做用户登录后的资源加载、前后台切换、输入、性能和指定 Hook 用例。Hook 点必须来自真实观察和确认的签名；目前没有任意游戏函数 Hook 的通过证据。

没有将任何现有失败归因于反作弊；当前阻塞首先是 Tripoder 通用客体引擎尚未实现。普通应用和其他三款目标的完整兼容性同样未通过。
