# Runtime 虚拟化核心设计规范 (Runtime Design)

本文档汇总 `android/runtime` 模块在**「统一最小承载底座（Level 1）+ 按需策略扩展（Opt-in）」**架构下的核心设计规范，涵盖 Guest `Application` 引导、四大组件显式虚拟化、存储隔离、系统服务边界及 `/proc` 视图机制。

---

## 1. Application、真实 Linker 路径与逻辑 LoadedApk 引导

当 `:slotN` 进程首次承载某个 Guest `(spaceId, packageName, processName, installGeneration)` 时，由 [`RuntimeBootstrapProvider`](../android/runtime/src/dev/tripoder/runtime/RuntimeBootstrapProvider.java) 与 [`RuntimeEngine`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java) 在任何四大组件执行前完成以下引导闭环：

1. **只读执行副本与真实 Native 库搜索路径**：
   - [`RuntimeExecutionCode`](../android/runtime/src/dev/tripoder/runtime/RuntimeExecutionCode.java) 在安装数据代次内建立物理执行副本。
   - [`RuntimeSharedLibraries`](../android/runtime/src/dev/tripoder/runtime/RuntimeSharedLibraries.java) 与 [`RuntimeDexLoader.createForGuest`](../android/runtime/src/dev/tripoder/runtime/RuntimeDexLoader.java) 使用物理提取目录 `info.nativeLibraryDir` 建立客体 `PathClassLoader` 的真实 linker namespace 搜索路径，确保 `System.loadLibrary` / `dlopen` 100% 命中真实 `.so`；同时在 `applicationInfo.nativeLibraryDir` 保留供客体自检的逻辑路径视图。
2. **激活 Native 基础路径重定向 (`fixed_libc` + `path_policy`)**：
   - [`RuntimeSelfHookStorage.install()`](../android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookStorage.java) 激活 `libc.so` 10 个核心路径入口的 `path_policy` 重定向，将 `/data/data/<guestPkg>`、`/data/user/0/<guestPkg>`、`/data/user_de/0/<guestPkg>` 映射至该 Space 与 `dataId` 专属沙箱目录（默认 0 `seccomp` 过滤器）。
3. **构造逻辑 `LoadedApk`、`GuestContext` 与裸 `ContextImpl` 收口**：
   - 构造关联 Guest APK/Splits、资源（`AssetManager` / `Resources`）与 `PathClassLoader` 的逻辑 `LoadedApk`。
   - 保留 `ApplicationInfo.metaData`、`sourceDir`、`dataDir`、`signingInfo` 等完整清单属性。
   - 将底层裸 `ContextImpl` 的 7 个外部存储目录入口收口至同一 [`GuestStorageContext`](../android/runtime/src/dev/tripoder/runtime/GuestStorageContext.java)，确保即使客体、壳或 WebView 通过 `getBaseContext()` 拆包调用也不会触发宿主/客体包名失配的 `SecurityException`。
4. **启动顺序与壳委托（Shell Delegate）兼容**：
   - 严格按顺序执行：建立真实进程名 (`RuntimeProcessIdentity.establish`) ──► 按需激活 `startupHook`（若显式配置） ──► `AppComponentFactory.instantiateClassLoader / instantiateApplication` ──► `Application.attachBaseContext(guestContext)` ──► 安装当前进程匹配的 `ContentProvider` ──► `Application.onCreate()`。
   - 若加壳应用在 `attachBaseContext` 中已将真实代理 `Application` 写入 `ActivityThread.mInitialApplication`，`RuntimeEngine` 保留该客体委托实例而不覆盖，防止壳在 `onCreate` 转发时自调用死循环。

---

## 2. 四大组件与跨组件数据交付

### 2.1 Activity 调度与生命周期替换

- **单跳免网关启动与槽位映射**：
  - 拦截 `IActivityTaskManager` / `IActivityManager` 的 `startActivity*`、`finishActivity`、`getIntentSender` 等调用。
  - 向 `:authority`（[`RuntimeBroker`](../android/runtime/src/dev/tripoder/runtime/RuntimeBroker.java) / [`ProcessSupervisor`](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java)）请求签发绑定了 `ActivityLease` 的物理槽位 Activity（[`RuntimeActivitySlots`](../android/runtime/src/dev/tripoder/runtime/RuntimeActivitySlots.java)）。
- **壳实例向真实 Guest Activity 转换**：
  - 在 [`RuntimeFactory`](../android/runtime/src/dev/tripoder/runtime/RuntimeFactory.java) 与 `Instrumentation` 边界提取 `ActivityLease` 中的真实 `ComponentName` 与原始 Guest `Intent`，使用 Guest `ClassLoader` 实例化目标 `Activity`，修正其 `mBase`、`mApplication`、`mActivityInfo` 与 `Window` 主题。
  - `AutofillClientController` 向系统返回物理槽位组件名（与真实 UID 和 Activity Token 一致），而 `Activity.getComponentName()` 向客体返回逻辑组件名。

### 2.2 Service 与 `bindServiceCommon` 统一边界

- **主动与绑定生命周期**：
  - [`RuntimeServices`](../android/runtime/src/dev/tripoder/runtime/RuntimeServices.java) 与 [`RuntimeServiceSlots`](../android/runtime/src/dev/tripoder/runtime/RuntimeServiceSlots.java) 路由 `startService`、`stopService`、`bindService`、`unbindService`。
  - 底层裸 `ContextImpl` 的服务绑定统一收口至 `bindServiceCommon`（覆盖 `Handler`/`Executor`、`int`/`long` flags 及 `BIND_EXTERNAL_SERVICE`），向系统 AMS 发起绑定时使用物理宿主 Context，解决 Chromium/WebView 渲染进程绑定时的 UID 归属校验。

### 2.3 ContentProvider 与 `RuntimeProviderIndex`

- **安装期索引与按序初始化**：
  - 安装事务解析所有 `<provider>` 的 `authorities`、`process`、`initOrder`、`multiprocess` 属性并构建 [`RuntimeProviderIndex`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviderIndex.java)。
- **同进程与跨进程路由**：
  - [`RuntimeProviders`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviders.java) 与 [`RuntimeProviderTransport`](../android/runtime/src/dev/tripoder/runtime/RuntimeProviderTransport.java) 处理同进程懒加载与跨槽位进程 Binder 桥接，包装 `Cursor` 与 `ParcelFileDescriptor`。

### 2.4 BroadcastReceiver 与 `RuntimeReceiverProxy` 显式代理

- **零运行期 ART Hook 的动态广播代理**：
  - [`RuntimeReceivers`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceivers.java) 将动态注册、Manifest 静态接收器及有序广播最终接收器统一包装为 [`RuntimeReceiverProxy`](../android/runtime/src/dev/tripoder/runtime/RuntimeReceiverProxy.java)。
  - 彻底废除运行期对客体 `BroadcastReceiver.onReceive` 子类动态安装 ART Hook 的机制，共享平台 `PendingResult`，保留 `goAsync`、有序结果与注销账本，同时支持注册无 Action 但含 `data`/`category` 的 `IntentFilter`。

### 2.5 跨组件 `Parcel.LazyValue` 类加载器保真 (`RuntimeGuestPayload`)

- Android 13+ 的 `Parcel.LazyValue` 在延迟解包时会固定捕获构造时的 `ClassLoader`，后续仅调用顶层 `Bundle.setClassLoader()` 无法更新已形成的 `LazyValue` 或嵌套子 `Bundle`。
- [`RuntimeGuestPayload`](../android/runtime/src/dev/tripoder/runtime/RuntimeGuestPayload.java) 在 Activity Intent、Activity Result、SavedInstanceState、Service 与 Broadcast 交付边界重新封装平台 `Parcel`，保留未解包数据与 Binder，并在读取时为所有层级的 `Bundle` 关联客体 `ClassLoader`。

---

## 3. PendingIntent、Alarm 与 JobScheduler

- **PendingIntent 虚拟化**：[`RuntimePendingIntents`](../android/runtime/src/dev/tripoder/runtime/RuntimePendingIntents.java) 与 [`PendingIntentLedger`](../android/runtime/src/dev/tripoder/runtime/PendingIntentLedger.java) 维护原始 Guest Intent 注册表，外层包装为宿主物理 `IntentSender`，只读 `FLAG_NO_CREATE` 查询不产生冗余写事务。
- **JobScheduler 虚拟化**：[`RuntimeJobScheduler`](../android/runtime/src/dev/tripoder/runtime/RuntimeJobScheduler.java) 作为显式 Java 子类经 `:authority` 调度账本映射 `(spaceId, packageName, namespace, guestJobId)` 至宿主 [`RuntimeJobSlots`](../android/runtime/src/dev/tripoder/runtime/RuntimeJobSlots.java)。

---

## 4. 存储隔离与 `dataDir` 长度契约

- **隔离目录与长度预算**：
  - 每个 `(spaceId, packageName, dataId)` 拥有独立的内部数据目录、Device Protected 目录（`device-protected`）与外部私有目录。
  - **路径长度硬约束（$\le 109$ 字节）**：部分商业加固壳（如招商银行 `libCmbShield.so`）使用固定 127 字节栈缓冲区拼接 `ApplicationInfo.dataDir`，一旦超出即触发 `FORTIFY: vsprintf` 崩溃。因此宿主包名与空间目录布局必须确保生成的 `dataDir` 字节长度恒 $\le 109$ 字节。
- **WebView 与基础设施目录路由**：
  - [`RuntimeWebView`](../android/runtime/src/dev/tripoder/runtime/RuntimeWebView.java) 自动为不同逻辑进程配置隔离数据后缀，并在 `notifyDexLoad` 白名单中放行客体自身数据目录与代码目录的合法动态 DEX 加载。

---

## 5. 系统服务边界：底座显式承载 vs. 按需扩展门面

| 服务类别 | 最小底座默认行为（Level 1） | 按需扩展行为（Opt-in Persona / EnvMock） |
| --- | --- | --- |
| **PackageManager (`IPackageManager`)** | [`RuntimePackages`](../android/runtime/src/dev/tripoder/runtime/RuntimePackages.java) 显式 endpoint：合并沙箱内安装与宿主可见系统包，还原客体原始签名（`signingInfo`/`signatures`）与 `metaData`。 | — |
| **Notification (`INotificationManager`)** | [`RuntimeNotifications`](../android/runtime/src/dev/tripoder/runtime/RuntimeNotifications.java) 在 Binder 边界按空间映射通知渠道、分组与 tag。 | — |
| **BiometricPrompt** | [`RuntimeBiometrics`](../android/runtime/src/dev/tripoder/runtime/RuntimeBiometrics.java) 在构造入口投影真实宿主包名/UID/AttributionSource，校验客体清单权限后交由系统硬件认证执行。 | — |
| **普通设备服务（Connectivity / Telephony / Location / Battery / Audio / Power / WifiP2p）** | **默认零 ART Hook**：直接从物理宿主 Context 获取平台原生 Manager 透传执行。 | 仅在显式配置 [`RuntimeSandboxPersona`](../android/runtime/src/dev/tripoder/runtime/RuntimeSandboxPersona.java) 或 [`RuntimeEnvMock`](../android/runtime/src/dev/tripoder/runtime/RuntimeEnvMock.java) 时，按需挂载对应服务门面返回虚拟指纹或注入网络故障。 |

---

## 6. 多开任务栈展示与 `/proc/self/*` 隐身机制

- **最近任务栏（Recents）独立卡片**：[`RuntimeTaskPresentation`](../android/runtime/src/dev/tripoder/runtime/RuntimeTaskPresentation.java) 将最近任务卡片的标题与图标实时替换为当前 Guest 应用的品牌名称与图标。
- **`/proc/self/*` 进程环境隐身**：
  - 真实进程名通过 `Process.setArgV0` 与主线程 `comm` 设置为客体逻辑进程名。
  - [`path_policy.c`](../android/runtime/native/path_policy.c) 在 `fixed_libc` 打开 `/proc/self/cmdline`、`maps`、`smaps`、`fd/*` 时按需过滤宿主与运行时快照特征，并将沙箱物理路径反映射为客体期望的标准路径视图。
