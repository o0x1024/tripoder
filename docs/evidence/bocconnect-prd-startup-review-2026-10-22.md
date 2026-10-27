# 中银商聚启动失败复核（2026-10-22）

## 当前结论

已定位到应用初始化阶段及应用自带的延迟退出机制；尚未取得触发该机制的第一个失败点，不能将“反容器检测命中”写成已确认根因。

本轮仅做读取与分析，未修改运行时代码、重装应用、清理数据、调整模式或关闭启动票据。

## 本轮直接核实

- ADB 当前连接 Xiaomi M154FF / madrid；系统安装的 `com.bocconnect.prd` 为 versionCode=63、versionName=1.1.25、arm64-v8a、targetSdk=35。
- 当前进程包含 Engine、authority、slot7，以及中银商聚的 isolated 服务进程。进程存在不证明客体 Application 已就绪，也不能单凭 slot 名确定该槽当前目标。
- 当前可读 logcat 保留了原生进程加载 `libbocconnect_prd.so`、Fragment 生命周期和派生 isolated 服务的记录。
- `/private/tmp/boc/native_tail.txt` 保留的原生对照：22:51:00.715 创建 SplashActivity，22:51:00.756 创建 RegisterProtocolActivity，22:51:00.834 外跳浏览器，22:51:00.864 进程以状态 1 退出。只证明原生能进入 UI，不能证明完整业务正常，也不能单凭退出码判断是 CrashHandler 导致。
- `/private/tmp/boc/class_s.txt`、`class_b.txt`、`class_au.txt` 的 APK 反汇编确认了下述机制。
- `adb shell debuggerd -j 8529` 返回 `root is required`，未取得当前 slot7 的 Java 栈。本轮没有进行 root 操作。

## 应用自身的退出机制

`uyf.au.a()` 先启动 `uyf.s` 线程，再调用 `System.loadLibrary("bocconnect_prd")`。

`uyf.s.run()` 的准确控制流为：

```java
String report = null;
for (int i = 0; i < 10; i++) {
    if (uyf.b.d() >= 0) {
        report = uyf.b.b();
        if (report != null) break;
    }
    Thread.sleep(1000);
}
uyf.b.a(report);
```

`uyf.b.d()` 返回初始值为 -1 的静态 int；读取报告的方法会使用 `ParcelFileDescriptor.adoptFd`。报告为空时，`uyf.b.a(report)` 构造 `uyf.Q("!")` 并调用默认 UncaughtExceptionHandler。该循环也可能在读取报告时阻塞，不能将 10 秒当成所有运行状态下的绝对超时。

既有现场报告记录：客体 22:48:04.300 成功加载上述库，约 10 秒后出现 `uyf.Q: !`，随后自发 SIGKILL，控制侧收到 DeadObjectException。这个时序与上述代码一致。不过，本轮可读日志中没有重新取得该次客体崩溃的原始日志，时间线仍引用既有报告，未重新启动复现。

## 对既有报告的修正

参考：`bocconnect-prd-startup-failure-2026-10-22.md`。

1. 其中 `if (uyf.b.d() < 0)` 的伪代码写反；实际负值跳过报告读取，非负值才读取。
2. 默认处理器被调用是直接退出机制；具体保护策略、native 初始化停滞或运行时语义错误仍待分辨。不能仅凭库名、10 秒延迟和异常 `!` 确认某项安全校验命中。
3. APK 内存在 CrashHandler 不证明本次启动安装并调用了它，更不证明它吞掉了首个异常。
4. 没有 Tripoder 自身异常栈，不足以排除 Tripoder 的身份、生命周期或系统接口语义导致初始化失败。

## 建议的下一步

优先复用 `RuntimeStartupDiagnostics` 与 RuntimeEngine 现有启动阶段，在同一客体冷启动的 10 秒窗口内采集主线程栈、Application 构造/attach/onCreate 阶段及首个失败，保留原始启动结果。应用类静态初始化可能在构造之前执行，仅在 BctApp.onCreate 添加探针可能过晚。

依据首个实际失败再检查相应的包身份、路径、类加载器、独立进程/服务或系统调用语义；若证据指向保护校验，明确记录具体校验与不满足条件。此时再决定运行时是否能按真实语义支持。不要先屏蔽退出线程、延长超时、伪造成功或将原生启动当成客体运行成功。

## 补充：ADB 检测假设与原生启动复现

用户反馈原生也不能正常启动，并授权操作测试。以下为本轮新增证据，覆盖此前对原生外跳页面的推断。

- 测试前设备实际值：`adb_enabled=1`、`adb_wifi_enabled=1`、`development_settings_enabled=1`。
- APK `classes3.dex` 中 `com.daon.sdk.device.security.Security.isDebugging(Context)` 明确调用 `Settings.Secure.getInt(resolver, "adb_enabled", 0)`，读到 1 返回 true。
- `com.daon.sdk.device.IXAFactor.isDebugging()` 同时检查 debug build、上述 ADB 状态及 `Debug.isDebuggerConnected()`。这证明 APK 包含调试检测能力，不证明该 SDK 的检测结果触发了本次启动退出。
- 23:11:36 从手机桌面点击原生中银商聚图标，创建原生进程 PID 13982，随后创建 SplashActivity、RegisterProtocolActivity 并退出；手机浏览器 UI 标题为“安全警告提示網頁”。
- 浏览器 `TabWebLoad` / `PageItemMiuiWebView` 的实际 URL 为：`https://www.bochk.com/dam/securitywarning.html?reason=28&manufacturer=Xiaomi&model=M154FF&android=37&version=8.2.1&return=3&srcApp=bocconnect`。
- 因此当前原生外跳是安全警告页，不能再写成已确认的协议 PDF 流程。URL 中 `version=8.2.1` 与应用 versionName=1.1.25 不同，尚未确认该参数的具体归属。
- 已读取银行官方警告页：网页只显示通用安全风险说明及 Code: 28；页面脚本直接显示 reason 参数，未公开 28 的具体检测含义。不能将 Code: 28 直接解释为 ADB。
- APK 另有 reason=49、50 的安全跳转分支，其中 49 的调用路径包含 `uploadMalwareLog`。不能以这些分支替代本次实际 reason=28 的归因。

### 调试开关对照的当前状态

已在设备预置独立执行脚本，计划依次进行“USB/无线均开”“USB 关、无线开”“USB/无线均关”的原生启动对照；结束恢复两个开关为 1，并另外预置延迟 35 秒的恢复命令。始终不改客体/原生应用数据及开发者选项总开关。

关闭无线调试后原连接 `192.168.31.49:39561` 失效。用户重新开启无线调试并提供 `192.168.31.49:35379`，本轮已成功重连并回读结果。设备上的结果文件为 `/data/local/tmp/tripoder-boc-adb-result.txt`，执行脚本为 `/data/local/tmp/tripoder-boc-adb-test.sh`；本机脚本为 `/private/tmp/boc/adb-state-test.sh`，完整回读结果保存在 `/private/tmp/boc/adb-state-test-result.txt`。

| 设备时间 | USB 调试 | 无线调试 | 开发者选项 | 原生启动结果 |
|---|---|---|---|---|
| 23:12:57–23:12:58 | 1 | 1 | 1 | 跳转 securitywarning.html?reason=28，原生 PID 14150 以状态 1 退出 |
| 23:13:04–23:13:05 | 0 | 1 | 1 | 仍跳转 reason=28，原生 PID 15748 以状态 1 退出 |
| 原计划第三阶段 | 未取得阶段读数 | 关闭后连接断开 | 1 | **未完成**；结果文件在第二阶段结束，不能当成“全部关闭仍失败” |

重连后实际读数为 USB=0、无线=1、开发者选项=1，说明预置恢复命令没有恢复 USB 开关；结合测试日志没有第三阶段输出，原测试未能继续执行。不能依赖 adb shell 启动的 nohup 任务在关闭 adbd 后继续存活完成恢复。

**收尾已完成**：重新设置 USB 调试为 1，并通过当前连接逐项回读，确认 USB=1、无线=1、开发者选项=1，恢复测试前状态。

结论：关闭 USB 调试不足以消除原生 Code: 28。APK 存在 ADB 检测能力，但本次退出是否由无线调试、开发者选项或另一项安全检查触发，仍未确认。下一步应在手机端关闭 USB/无线调试及开发者选项总开关后，从桌面重新启动原生应用并观察；这一阶段需要手机端操作和记录，当前依赖 ADB 的控制链无法在关闭后继续观测。无需再次通过断开当前 ADB 连接来重复已完成的 USB 单变量测试。

### 有线 ADB 补充对照

用户连接 USB 后，确认 `d861a4fa` 为在线 USB transport，并通过该序列号操作，未依赖无线连接。

| 设备时间 | USB 调试 | 无线调试 | 开发者选项 | 原生启动结果 |
|---|---|---|---|---|
| 23:18:51 | 1 | 1 | 1 | 新建原生启动后跳转安全警告 reason=28 |
| 23:18:58 | 1 | 0 | 1 | 新建原生启动后仍跳转安全警告 reason=28 |

两轮均读取了实际开关状态，再启动同一 SplashActivity，并只读取各自启动时间之后的日志。浏览器 `TabWebLoad` 与 `PageItemMiuiWebView` 均记录了 reason=28 的完整地址。本机结果：`/private/tmp/boc/wired-wifi-test-result.json`。

测试结束已恢复无线调试，并逐项回读确认 USB=1、无线=1、开发者选项=1。

因此，单独关闭无线调试同样不能解决问题。已完成的三种组合均至少保留一种 ADB，尚不能区分“任一 ADB 开启即触发”与“其他独立安全检查导致退出”；开发者选项总开关也尚未被排除。最后的全关对照仍需在手机端完成：有线 ADB 同样依赖 USB 调试，插线不能在关闭 USB 调试后维持控制和日志采集。本轮没有再次关闭全部调试，也未增加诊断应用或改动金融应用。
