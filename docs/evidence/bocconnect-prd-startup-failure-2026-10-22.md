# 中银商聚 `com.bocconnect.prd` 在 Tripoder 客体环境启动失败分析

- 时间：2026-10-22 22:47–22:56（设备本地时间）
- 设备：Xiaomi M154FF（madrid），Android 17 / SDK 37，HyperOS `V816`，`ro.boot.verifiedbootstate=green`、`ro.boot.flash.locked=1`、`ro.debuggable=0`、`release-keys`（**未 root**，无 `su`/Magisk）
- 客体：`com.bocconnect.prd` v63，空间 `default`，`installGeneration=e1b5c910-61c0-4332-9722-e700e18d2207`，mode `thin`
- 启动组件：`cn.swiftpass.bct.module.home.SplashActivity`

## 1. 结论摘要

1. **不是 Tripoder 运行时框架崩溃。** slot 正常引导（`runtime mode=thin`、`dexLoader`→`hooks` 全部完成），Tripoder 自身没有任何异常栈，也没有 native 崩溃/tombstone。
2. **客体在 `Application` 初始化阶段就中断**，此后**从未创建任何 Activity**（日志中完全没有 `SplashActivity` / `FragmentLifeCallback` 等生命周期输出；原生运行同一 APK 时这些日志齐全）。
3. 中断后，**客体自带的“自杀看门狗”线程** `uyf.s` 在约 **10 秒**后抛出 `uyf.Q("!")`，交给默认 `UncaughtExceptionHandler`，进程被 `SIG 9` 杀死 → slot 进程死亡 → 控制器侧表现为 `DeadObjectException` / `LAUNCH_PREPARATION_UNCONFIRMED`。
4. 该看门狗由应用基类 `uyf.aY extends android.app.Application`（业务类 `cn.swiftpass.bct.BctApp extends uyf.aY`）的**类静态初始化**时启动，并依赖加固/上报 native 库 `libbocconnect_prd.so`。
5. **原生对照实验**表明：同一版本 APK 直接在真机运行可以走到 `SplashActivity` → `RegisterProtocolActivity`，随后外跳条款 PDF 并 `exit(1)`。也就是说 APK 本身在这台机器上能加载并运行到 UI，但应用自身的安全/首次注册流程会主动终止进程；客体环境里的失败发生在更早的 `Application` 初始化。

## 2. 失败时间线（客体运行，logcat）

```
22:48:02.728  TripoderBoot: runtime mode=thin
22:48:02.739  TripoderBoot: verify +11ms / archiveInfo +123ms / activityIndex +424ms
22:48:03.233  TripoderBoot: dexLoader +505ms / resources +506ms / indexes +506ms
22:48:03.240  TripoderBoot: register +512ms
22:48:04.248  TripoderBoot: hooks +1519ms
22:48:04.248  TripoderHardened: Installed IWindowSession proxy for host package: dev.tripoder.engine
22:48:04.257  nativeloader: clns-11 ... target_sdk_version=36
              library_path=/data/user/0/com.bocconnect.prd/code/com.bocconnect.prd-e1b5.../lib/arm64
22:48:04.290  avc: granted { execute } .../lib/arm64/libbocconnect_prd.so        ← 客体 native 安全库加载
22:48:04.300  nativeloader: Load .../libbocconnect_prd.so ... : ok
              （此后客体再无任何日志；无 Activity 创建）
22:48:14.291  JavaExceptionHandler: Process: dev.tripoder.engine:slot6, PID: 2513
22:48:14.291  JavaExceptionHandler: uyf.Q: !
                at uyf.b.b(SourceFile:6)
                at uyf.b.a(SourceFile:0)
                at uyf.s.run(SourceFile:27)
22:48:14.301  Process : Process is going to kill itself! → Sending signal. PID: 2513 SIG: 9
22:48:14.380  TripoderRuntime: android.os.DeadObjectException
22:48:14.382  ActivityManager: Scheduling restart of crashed service ... RuntimeSlot$WorkerG
```

旁证：MIUI 稳定性服务把这次崩溃归属到客体包名：

```
MQSEventManager: Handle ThirdParty stability event | pkg=com.bocconnect.prd, uid=10445, type=1
```

注意进程实际是 `dev.tripoder.engine:slot6`（uid 10445），说明 Tripoder 的包名身份映射是生效的；崩溃仍被应用自身的看门狗触发。

## 3. APK 反编译确认（base.apk 57 MB，classes.dex…classes4.dex）

- `AndroidManifest.xml`：`<application android:name="cn.swiftpass.bct.BctApp">`，launcher `cn.swiftpass.bct.module.home.SplashActivity`；另有 `:ghjsrukkhd`、`:statistical`、`:uanuediffm`、`:zciajirkmn` 等独立进程声明（后者会派生 isolated 进程 `...:zciajirkmn:uyf.p`）。
- `Lcn/swiftpass/bct/BctApp;` → `Superclass: Luyf/aY;`；`Luyf/aY;` → `Superclass: Landroid/app/Application;`。
- `uyf.aY.<clinit>`：连续两次 `invoke-static uyf.au.b()V`。
- `uyf.aY.attachBaseContext`：`Application.attachBaseContext` + `uyf.au.b(Luyf/aY;)V`（把 Application 交给安全/上报 SDK）。
- `uyf.au` 静态字段 `a=true`、`b=true`；`<clinit>` → `uyf.au.b()` → `uyf.au.a(null)`：当 `b!=0` 时执行 `uyf.au.a()` =
  - `uyf.b.a()`：`new Thread(uyf.s).start()`
  - `uyf.au.d()`：`System.loadLibrary("bocconnect_prd")`
- `uyf.s.run()`（smali 语义）：
  ```java
  String msg = null;
  for (int i = 0; i < 10; i++) {
      if (uyf.b.d() < 0) {              // 静态 int，默认 -1
          msg = uyf.b.b();              // 读取“报告”
          if (msg != null) break;
      }
      Thread.sleep(1000);
  }
  uyf.b.a(msg);                          // ← 永远会走到这里
  ```
- `uyf.b.a(String)`：`uyf.b.b(msg)` 构造 `uyf.Q extends RuntimeException`（`new Q("!")`）→ `Log.e("Report","Exiting: ",…)` → `Thread.getDefaultUncaughtExceptionHandler().uncaughtException(currentThread, throwable)` → 进程被 `SIG 9`。
- 另有一套可读的崩溃处理器 `cn.swiftpass.bct.utils.CrashHandler`：`uncaughtException` → `handleException(Throwable)` →（`AndroidUtils.saveErrInfoToGalleryByScopedStorage`）→ `sleep` → 委派默认 handler → `System.exit(1)`。它会**吞掉原始异常且不打印到 logcat**，这解释了为什么客体侧看不到真正的首个异常。

结论：`uyf.aY` 的静态初始化一旦执行，就启动了“10 秒内拿不到安全报告就自杀”的看门狗，并加载 `libbocconnect_prd.so`。客体里失败点正好紧跟在该 native 库加载之后，10 秒后看门狗准时开火。

## 4. 原生对照实验（同版本、同设备、绕过 Tripoder）

```
adb shell am start -n com.bocconnect.prd/cn.swiftpass.bct.module.home.SplashActivity
→ onActivityCreated SplashActivity
→ START RegisterProtocolActivity
→ START VIEW dat=https://www.bochk.com/dam/smeinone/bocconnect/Chattnc_*.pdf (外部浏览器)
→ Zygote: Process 28699 exited cleanly (1)     // 约 0.25s

adb shell am start -n com.bocconnect.prd/cn.swiftpass.bct.main.MainActivity
→ 同样 160ms 内外跳条款 PDF 并退出
```

- 原生两次运行都能创建 Activity，说明 APK 的类加载、资源、native 库在这台机器上没问题。
- 但应用在“首次注册/条款”流程里自行 `exit(1)`（`CrashHandler` 分支），且 USB 调试开启时该流程会反复重启到同一状态。
- 因此**客体侧的问题点比原生更早**：原生至少能创建 Activity，客体在 `Application` 初始化阶段就没有下文。

## 5. 最可能的根因（按可能性排序）

1. **加固/风控 SDK 在虚拟化容器内校验失败，触发自杀看门狗。**
   失败点紧贴 `libbocconnect_prd.so` 加载；客体被 Tripoder 重写为
   `/data/user/0/com.bocconnect.prd/code/.../base.apk` 路径、运行在 `dev.tripoder.engine:slotN` 进程/uid 10445 下、由 `clns-11` isolated namespace 加载。
   该类 SDK 常校验 APK 路径、签名证书、进程名/uid、`/proc/self/maps`、ClassLoader 归属，任一不符即静默终止。
2. **`Application` 初始化里某个 SDK 在容器内抛异常**（WebView/Firebase/GMS/Keystore/网络），被 `CrashHandler` 吞掉后看门狗接管。
3. **应用与 Android 17(SDK 37)/HyperOS 3 的兼容性问题**；但原生能到 Activity，优先级低于 1、2。

## 6. 建议的下一步

1. **拿到首个原始异常**（当前最缺的证据）：
   - 该应用的方法签名里大量使用 `Throwable/Context` 参数，Tripoder Java Hook 选择器目前只接受标量/`String`/`byte[]`，无法直接 hook `CrashHandler.handleException(Throwable)`；
   - 可行替代：hook 无参方法定位阶段（`BctApp.onCreate`、`uyf.aY.onCreate`、`uyf.b.a()`），或在宿主机对 engine 进程做全量 logcat 抓取；
   - 也可用 `storage_inspect(files)` 查看客体容器内是否有崩溃上报文件（`CrashHandler` 写 Gallery 失败时可能残留）。
2. **试一次 `thick` 模式**（`application_mode_set` → `thick` → 重新 `process_start`），排除 thin 下 shim 不完整导致的初始化差异。
3. 若确认是 APK 签名/路径强校验：这类金融加固 App 在容器内通常无法运行，需要在 Tripoder 侧补齐对应身份伪装，或在产品上明确标注“不支持”。

## 7. 本次分析的局限

- 提交 `probes_configure`（startupHook）后 **MCP 控制面失去响应**（`MCP_TRANSPORT_ERROR`），无法确认该 probe 是否生效，也无法读取 `hook_events`；设备侧 engine 进程仍在（`17159` authority、`12053` engine、`8529` slot7）。可能需要重启 Tripoder MCP 服务；重启会清空 probe 配置。
- 原生对照通过 `adb shell am start` 以 shell 身份启动，与用户从桌面点击启动的调用方不同（银行 App 可能区分调用方）。
- 未取得 tombstone（无 root），因此只能基于 logcat + 静态反编译 + 行为对照判断。
