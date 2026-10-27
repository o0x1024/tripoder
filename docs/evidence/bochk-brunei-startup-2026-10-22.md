# BOCHK Brunei 原生与客体启动对照

日期：2026-10-22，Asia/Shanghai。设备：Xiaomi M154FF / madrid，USB ADB `d861a4fa`。

## 结论

`com.bochk.bn.aos` 1.2.0（versionCode 43、targetSdk 36）原生冷启动可进入 MainActivity；同一设备上 THIN、THICK 客体启动均在加载 `libbochk_bn_aos.so` 后约 10 秒，由应用自身的 `ymmqh.o` 线程调用默认异常处理器，进而杀死 slot 进程。UI 的 DeadObjectException 是进程死亡的后续结果。

直接退出机制已确认；保护组件在容器内为何未正常完成初始化、具体哪项环境校验或运行时语义导致该结果，尚未确认。不能将 `ymmqh.I: !` 直接解释为某个具体的反虚拟化、签名或调试检测错误码。

## 三组现场证据

| 运行方式 | 关键时间（设备本地） | 结果 |
|---|---|---|
| THICK，slot7 PID 27555 | 23:23:02.829 库加载 ok；23:23:12.807 Report Exiting；23:23:12.878 SIG 9 | `ymmqh.I: ! → ymmqh.x.b → ymmqh.x.a → ymmqh.o.run` |
| THIN，slot7 PID 27925 | 23:25:17.335 库加载 ok；23:25:27.294 Report Exiting；23:25:27.326 SIG 9 | 同一异常与退出链路 |
| 原生冷启动，PID 27344 | 23:25:31.355 启动 MainActivity；启动约 12 秒后检查 | PID 仍存在，topResumedActivity 为 `com.bochk.main.MainActivity` |

原生通过 force-stop 后启动 SplashActivity 做冷启动对照；没有清理应用数据，没有登录或执行金融业务。MainActivity 存活仅用于启动对照，不代表完整业务验收。

原生及客体测试期间 USB 调试、无线调试、开发者选项均为 1。故“开启 ADB”本身不足以解释原生与客体的差异；这不排除保护组件组合评估多个环境信号。

## APK 静态核对

- 应用继承链：`AppBaseApplication → com.bochk.base.base.BaseApplication → ymmqh.aa → android.app.Application`。
- `ymmqh.aa.<clinit>` 调用 `ymmqh.X.d()`；引导路径启动 `ymmqh.o` 线程并加载 `bochk_bn_aos`。
- `ymmqh.o.run` 最多循环 10 次，每次休眠 1000 ms。静态 int 非负时读取报告；取得非空报告则提前结束循环，最终调用 `ymmqh.x.a(report)`。报告读取本身可能阻塞，10 次休眠不是所有状态下的硬超时。
- `ymmqh.x.b(String)` 在参数为 null 时构造 `ymmqh.I("!")`；`ymmqh.x.a(String)` 打印 Report 并调用默认 UncaughtExceptionHandler。本次异常文本与该路径相符，但没有直接回读当时的报告参数。
- 原生安装由 base.apk 及 arm64-v8a、xxhdpi、zh 三个 split 组成。客体日志显示 base 和三个 split 进入类加载路径，且保护库实际加载成功。不能将本次故障简单归因于缺失 ARM64 split 或找不到该库。
- ARM64 split 还包含 `libpdfinterface.so`、`libpdfium.so`、`libtoolChecker.so`。本轮未发现客体走到这些后续业务库的证据。

## 同时发现的运行时缺陷

两种模式都出现 `installAppWidgetBoundary` 对 `AppWidgetManager.sService` 反射失败的 NoSuchFieldException。日志随后继续到保护库加载，所以它不是该线程的直接未捕获致命异常；是否间接影响保护组件仍未知，不能用屏蔽日志视为修复。

客体还出现 odex/dalvik-cache 权限警告，但随后的 dexLoader、resources、hooks 均完成。现有证据不足以将这些警告认定为本次首因。

当前日志缺少保护库之后的 Application attach/published 及客体 Activity 生命周期证据。失败窗口落在 Application 继承链的早期引导附近；未取得死亡前的主线程栈，无法进一步确认它是在 native 初始化阻塞、抛异常，还是主动拒绝某项环境条件。

## 下一步

优先在该 10 秒窗口内，采集当前安装制品的主线程栈、Application 构造/attach 阶段以及保护报告通道状态。应复用运行时已有启动诊断能力；若现有制品无法提供，增加仅记录阶段与线程栈的受限诊断，再冻结源码、绑定构建回执重现。

根据首个实际差异检查共享启动链中的 Application/LoadedApk 身份发布顺序、类加载器/路径，以及保护组件需要的进程与系统接口语义。两种模式均失败只能帮助缩小到共同路径，不能据此证明所有模式专属差异都无关。

不要先跳过退出线程、延长其等待时间、返回伪造检查结果，或将原生运行当作客体成功。当前工作区有其它进行中的源码修改，本轮未修改、构建或重装运行时，因此本结论针对手机当前安装制品。

## 证据与收尾

证据目录：`bochk-brunei-startup-2026-10-22/`，包含两种模式的目标 slot 日志、原生启动摘要、看门狗/引导类反汇编及 APK SHA-256 元数据。完整 APK 与额外反汇编留在 `/private/tmp/tripoder-brunei-20261022/`，未放入仓库。

已将 BOCHK Brunei 恢复为测试前的 THICK 模式，并通过 UI 确认；三个调试开关仍为 1。未清数据、未重装、未关闭 UNKNOWN 启动票据、未提交或推送。
