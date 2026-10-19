# 第三轮真机客体兼容性普查与架构修复（2026-10-19）

> 设备 `d861a4fa`（Xiaomi M154FF / madrid，HyperOS 4 / Android 17，API 37）。
> 引擎以 `dev.tripoder.engine.dev` 并行安装（原 `dev.tripoder.engine` 的签名密钥不在本机，无法覆盖安装）。
> 客体为设备上真实安装的 17 个应用，逐个走生产入口（`TargetLaunchIntegration`：导入 base.apk → 建项目 →
> prepareLaunch → startActivity → 首屏确认 → 稳定性采样）。
>
> 本轮所有结论均来自设备证据；每个失败都保留 `*.json` 与完整 `*.logcat`。

---

## 1. 结果总览

| 阶段 | 通过 | 说明 |
| --- | --- | --- |
| 13:01 基线（`20261019-125638`） | 1/17 | 只有云音乐通过；其余 16 个分别死于生命周期、权限、Provider、类加载器、原生崩溃 |
| 13:18 第一批修复后（`20261019-131800`） | 5/8（重测子集） | 大众点评、京东、滴滴、高德、云音乐通过 |
| 13:43 第二批修复后（`20261019-134308`） | 7/8（重测子集） | 新增百度地图、爱奇艺、头条、抖音、小红书、B站 |
| 最终全量（`final-summary.json`） | 见该文件 | 17 个客体一次跑完的权威结果 |

---

## 2. 本轮确立的架构边界

这些不是按应用打的补丁，而是"客体身份 / 平台身份 / 平台状态"三条边界上的统一规则。每条规则都能解释一类失败，
并且修复后同一类应用一起恢复。

### B1. 框架继承来的生命周期不是客体代码

`Service` 子类没有覆写 `onBind` 时，`getMethod` 解析到 `android.app.IntentService.onBind`（boot class）。
原来的安装器把它当成需要 hook 的客体方法，而全局 hook 一个框架类必须注册 boot adapter，于是安装期直接抛
`FIXED_BOOT_MANAGED_ADAPTER_REQUIRED` 并杀死进程（百度地图首因）。

**规则**：只有声明类是客体/客体库类加载器时才是"我们的"生命周期；boot class 的实现按平台原样运行。

- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookComponents.java`
- 证据：`20261019-125638/com.baidu.BaiduMap.logcat`（`FIXED_BOOT_MANAGED_ADAPTER_REQUIRED:android.app.IntentService.onBind`）

### B2. 宿主未授予的能力 = 客体设备状态不可用，不是异常

客体的 `uses-permission` 不会让宿主进程获得授权（`READ_PHONE_STATE` 在设备上是 `granted=false`）。
`TelephonyManager.getDataNetworkType()` 在平台内部调用 `getDataNetworkTypeForSubscriber`，被系统服务以
`SecurityException` 拒绝；爱奇艺的 Tinker 在 `attachBaseContext` 里把这个异常包装成
`Tinker Exception:getDataNetworkTypeForSubscriber` 并终止启动。百度地图则直接使用废弃的
`PhoneStateListener` 注册，被 `TELEPHONY_CAPABILITY_UNSUPPORTED:listen` 拒绝。

**规则**：设备状态观测只描述"这台虚拟设备现在有什么状态"。宿主没有的能力返回该 API 已定义的中性值
（`NETWORK_TYPE_UNKNOWN`、空列表等）并记录一条 `DEVICE_STATE_UNAVAILABLE`；旧版监听器注册是订阅而不是查询，
能转发就转发，不能转发就是"无无线电状态可上报"的静默订阅。

- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagerPolicy.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java`
- 证据：`20261019-131800/com.qiyi.video.logcat` 修复前；`20261019-133716/com.qiyi.video.json` 修复后 8/8
- 证据：`20261019-133219/com.baidu.BaiduMap.logcat`（`listen`、`getAllCellInfo`）→ 修复后 8/8

### B3. 平台不认识的 authority 不能下发给平台

微博启动时 `registerContentObserver(content://com.sina.weibo.provider.SinaWeiboSPProvider/...)` 被转发给
平台，平台按自己安装的微博（另一个 UID）解析 authority，报
`Permission Denial: opening provider ...`。平台的 ContentService 从来不知道客体 Provider 的存在。

**规则**：注册/通知按 authority 归属路由。客体 authority 的观察者、通知都留在本进程（由客体自身的写入触发），
其余 authority 保持平台真实注册。

- `android/runtime/src/dev/tripoder/runtime/RuntimeSettingsObservers.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookPlatformCallbacks.java`（`notifyChange`）
- 证据：`20261019-133219/com.sina.weibo.logcat`（registerContentObserver 拒绝）

### B4. 客体类加载器的可观察类型必须是平台类型

平台给每个真实应用的是 `LoadedApk.mClassLoader instanceof PathClassLoader`。运行时为了完整性从密封内存加载
DEX，用的是 `InMemoryDexClassLoader`，于是淘宝的 `AppBundleComponentFactory` 在
`InjectClassLoader.<init>` 里把应用类加载器强转 `PathClassLoader` 时崩溃；腾讯新闻的插件 Provider 也走同一类
假设。

**规则**：验证过的内存 DEX 序列仍是定义类加载器；对外呈现的 `LoadedApk.mClassLoader` 是一个不携带 DEX 的
`PathClassLoader` 外观，父加载器是内存加载器。类解析、native 库命名空间、资源查找都不变，只有可观察类型被纠正。

- `android/runtime/src/dev/tripoder/runtime/RuntimeGuestLoader.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java`
- 证据：`20261019-125638/com.taobao.taobao.logcat`（`InMemoryDexClassLoader cannot be cast`）→
  `20261019-133716/com.taobao.taobao.logcat` 已无该异常

### B5. 客体可以替换自己的 LoadedApk 类加载器

小红书的热修复框架把 `LoadedApk.mClassLoader` 换成自己的包装加载器。原来的所有权校验把它当作身份破坏
（`APPLICATION_OWNERSHIP_VIOLATION:logical.mClassLoader`）并终止进程。

**规则**：`LoadedApk.mClassLoader` 和 `mResources` 一样属于客体自有状态。只要替换后的加载器父链仍能回到本客体
的验证加载器，就跟随；父链离开本安装的替换仍然拒绝（防止借替换引入外来加载器）。

- `android/self-hook/native/application_adapter.c`（`owned_loader`）
- 证据：`20261019-133219/com.xingin.xhs.logcat` → 修复后 `20261019-134308/com.xingin.xhs.json` 8/8

### B6. 不能用字段手术关闭平台自己的每进程监控

运行时为了避免 MIUI 的 `MessageMonitor`/Scout 干扰，曾把 `MessageMonitor.ENABLED` 置 false、把
`Looper.mMonitor` 置 null。HyperOS 4 的 `Looper.loopOnce` 在每次派发时都会解引用该监控器，于是应用在下一条
消息上崩溃：

```
java.lang.NullPointerException: ... MessageMonitor.markFinish(...) on a null object reference
  at android.os.Looper.loopOnce(Looper.java:340)
```

淘宝、小红书、抖音、头条、B站都命中；表现为"首屏 15 秒内未确认"或 ANR（B站）。

**规则**：平台每进程状态（Looper 监控器）不是运行时可关闭的扩展点；禁止用反射字段手术改变它。移除该手术后，
抖音、头条、小红书、B站全部恢复。

- `android/runtime/src/dev/tripoder/runtime/RuntimeFixedPreparation.java`
- 证据：`20261019-133716/com.xingin.xhs.logcat`（NPE）→ `20261019-134308` 同一应用 8/8；
  `tv.danmaku.bili` 从 ANR → 9/9

### B7. 客体可见包名与平台操作包名分离

平台服务用 `AppOpsManager.checkPackage(callingUid, callingPackage)` 校验"这个包是否属于这个 UID"。
客体包名没有安装在本进程的 UID 下，直接下发必然被拒。运行时在 `GuestContext` 上明确两条身份：
`getPackageName()` 返回客体包名（客体看到的世界），`getOpPackageName()` 返回真正拥有本 UID 的已安装包名
（平台校验的世界）。

- `android/runtime/src/dev/tripoder/runtime/GuestContext.java`
- 证据：`TripoderIdentity create package=com.sina.weibo opPackage=dev.tripoder.engine.dev host=dev.tripoder.engine.dev uid=10421`

---

## 3. 仍未闭环的 5 个客体（根因已定位到层）

| 应用 | 首个失败 | 已定位的层 | 下一步 |
| --- | --- | --- | --- |
| 知乎 | `SecurityException: callingPackage does not match UID`（`StorageManager.getVolumeList`，发生在 Application 静态初始化、尚未 attach） | 平台服务用"当前操作包 + 真实 UID"校验；此调用点在 `ActivityThread.currentOpPackageName()`，需确认返回的包名 | 在 native `createNative` 里用 JNI 调用 `ActivityThread.currentOpPackageName()` 并记录；据此决定是修正加载期上下文还是把 `StorageManager` 纳入管理器虚拟化 |
| 微博 | `SecurityException`（`AppWidgetServiceImpl.enforceCallFromPackage(provider.getPackageName())`） | 组件作用域平台服务：服务端校验的是 `ComponentName` 的包名，`getOpPackageName` 无法影响 | 新增 `APP_WIDGETS` 管理器族：客体组件返回空 widget 列表/`null` info，不请求平台 |
| 淘宝 | `ClassNotFoundException: com.taobao.tao.welcome.Welcome`，`DexPathList` 指向 **引擎 APK** | 客体自定义 `AppComponentFactory.instantiateActivity` 收到的是宿主加载器，未拿到客体加载器 | 在工厂转交路径上强制 `engine.loader`，并为工厂返回的加载器建立显式所有权回执 |
| 腾讯新闻 | `ClassNotFoundException: com.tencent.news.loader.p.ProviderN1`，`DexPathList[[]]` | 插件 Provider 由客体工厂动态加载；当前 Provider 发布拿到的加载器不含插件 DEX | 让 Provider 发布使用工厂返回的加载器，并记录发布时的加载器身份 |
| 支付宝 | `SIGSEGV` / `__strlen_aarch64` ← `libc++_shared.so`（加载 `liblogging.so`、`libantdlog_cpp.so` 之后） | 客体 native 初始化把空指针传给 C++ 字符串；需确认是哪个 JNI/属性返回空 | 在 native 加载序列上加探针（JNI 返回值、属性、`getPackageName` 等）定位第一个空值 |

---

## 4. 方法与复现

- 普查脚本：`/private/tmp/guestlab/census.py`（读取 `apps.txt` 的 17 个真实安装包，逐个跑控制测试并保存
  `.json` + 完整 `.logcat`）。
- 构建：`android/scripts/build-workbench.py --package dev.tripoder.engine.dev`（SDK 37 / build-tools 37，
  `--ndk context/probe/ndk-shim`），产物 `android/product/build/tripoder-dev.apk`。
- 安装：`adb install -r` 引擎与控制测试；控制测试的 `targetPackage` 由构建脚本替换，不再硬编码引擎包名。
- 证据目录：`/private/tmp/guestlab/census/20261019-125638`（基线）、`131800`、`133219`、`133716`、`134308`、
  最终全量目录；本目录保留 `baseline-summary.json` 与 `final-summary.json`。

## 5. 变更文件

```
android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookComponents.java      B1
android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagerPolicy.java  B2
android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookManagers.java       B2
android/runtime/src/dev/tripoder/runtime/RuntimeSettingsObservers.java      B3
android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookPlatformCallbacks.java B3
android/runtime/src/dev/tripoder/runtime/RuntimeGuestLoader.java            B4（新增）
android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java                 B4
android/self-hook/native/application_adapter.c                              B5（+ 所有权诊断）
android/runtime/src/dev/tripoder/runtime/RuntimeFixedPreparation.java       B6
android/runtime/src/dev/tripoder/runtime/GuestContext.java                  B7
android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookApplication.java    身份回执（诊断）
```
