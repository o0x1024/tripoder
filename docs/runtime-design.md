# Runtime 虚拟化核心设计规范 (Runtime Design)

本文档汇总 `android/runtime` 模块的核心设计规范，涵盖 Guest `Application` 引导、四大组件虚拟化、存储隔离、系统服务代理以及多开任务栈与 `/proc` 隐身机制。

---

## 1. Application 与逻辑 LoadedApk 引导

当 `:slotN` 进程首次承载某个 Guest `(spaceId, packageName, processName)` 时，由 `RuntimeProcessBootstrapper` 在任何四大组件执行前完成以下引导闭环：

1. **激活 Native 路径重定向 (`path_policy`)**：
   - 将 `/data/data/<guestPkg>`、`/data/user/0/<guestPkg>`、`/data/user_de/0/<guestPkg>` 映射至该 Space 与 `dataId` 专属的沙箱目录。
   - 配置 Native 库搜索目录，确保 `System.loadLibrary` 与 `dlopen` 优先加载 Guest 提取出的 `lib/<abi>/*.so`。
2. **构造逻辑 `LoadedApk` 与 `Application`**：
   - 通过 Framework Context 关联 Guest APK 路径、资源（`AssetManager` / `Resources`）与独立 `PathClassLoader`。
   - 将 Guest 的 `ApplicationInfo`（包含正确的 `packageName`、`dataDir`、`nativeLibraryDir`、`sourceDir`、`targetSdkVersion`、`metaData`）注入逻辑 `LoadedApk`。
   - 替换 `ContextImpl` 与 `LoadedApk` 中的包名与目录引用，使 `context.getPackageName()`、`context.getFilesDir()` 等 API 原生返回 Guest 视图。
3. **启动顺序保证**：
   - 按 Android 标准规范执行：`Application.attachBaseContext(base)` ──► **安装当該进程匹配的 `ContentProvider` (`installContentProviders`)** ──► **激活项目级 Self-Hook 脚本** ──► `Application.onCreate()`。

---

## 2. 四大组件虚拟化

### 2.1 Activity 调度与生命周期替换

- **Intent 解析与槽位映射**：
  - `RuntimeHookEngine` 拦截 `IActivityTaskManager` / `IActivityManager` 的 `startActivity*`、`startActivities`、`finishActivity`、`getIntentSender` 等调用。
  - 若目标 Intent 命中沙箱内已安装应用，向 `:authority` 请求 `prepareLaunch`，获取绑定了 `ActivityLease` 的物理壳 Activity（如 `RuntimeSlotNActivity`、横屏变体 `Landscape` 或透明变体 `Translucent`）。
- **Flag 与 LaunchMode 语义保持**：
  - 完整保留并模拟 `FLAG_ACTIVITY_NEW_TASK`、`FLAG_ACTIVITY_CLEAR_TOP`、`FLAG_ACTIVITY_SINGLE_TOP`、`FLAG_ACTIVITY_CLEAR_TASK` 以及 `singleTop` / `singleTask` / `singleInstance` 语义。
- **壳实例向真实 Guest Activity 转换**：
  - 在 `ActivityThread.H` 消息循环与 `Instrumentation` 层拦截壳 Activity 的创建流程，提取 `ActivityLease` 中的真实 `ComponentName` 与原始 Guest `Intent`。
  - 使用 Guest `ClassLoader` 实例化目标 `Activity`，修正其 `mBase` Context、`mApplication`、`mActivityInfo` 与 `Window` 主题后，交由系统继续驱动完整的 `onCreate -> onStart -> onResume` 生命周期。

### 2.2 Service 与被动系统服务

- **主动 Service (`startService` / `stopService` / `bindService` / `unbindService`)**：
  - 拦截并路由至 `:authority` 签发 `ServiceLease`，再驱动目标槽位中的 `RuntimeSlotNService` 在进程内创建并管理真实的 Guest `Service` 实例池。
  - 维护每个 Guest Service 的 `startId` 队列与 `IBinder` 连接池，支持跨进程 `ServiceConnection` 绑定与死亡通知。
- **被动系统唤醒服务 (Passive Leases)**：
  - 针对 `JobService`、`NotificationListenerService` 等由系统发起绑定的组件，通过宿主代理组件接管系统回调，并在 `:authority` 注册被动租约后桥接至对应的 Guest Service 实例。

### 2.3 ContentProvider 与跨进程查询

- **`InstallProviderIndex` 内存索引**：
  - APK 安装阶段即解析所有 `<provider>` 的 `authorities`、`process`、`initOrder`、`multiprocess` 属性并持久化至快速索引表，启动期查询达到 $O(1)$。
- **同进程与跨进程路由**：
  - 当 Guest 调用 `ContentResolver.acquireProvider` / `acquireUnstableProvider` 时，若目标 authority 属于当前进程或声明了 `android:multiprocess="true"`，直接在本地进程懒加载实例化；
  - 若属于其他 Guest 进程，通过对应槽位的 `RuntimeSlotNProvider` 进行跨进程 Binder 桥接，并包装返回的 `Cursor` 与 `ParcelFileDescriptor` 以确保引用计数与关闭生命周期正确释放。
- **SettingsProvider 安全兜底**：
  - 拦截 `Settings.Secure` / `Settings.System` / `Settings.Global` 查询，对 `android_id` 等敏感指纹返回 Space 绑定的虚拟值。

### 2.4 BroadcastReceiver 与系统受保护广播

- **动态广播**：在 Guest 进程内重写 `registerReceiver`，将宿主包名身份修正后向系统注册，并在收到广播时还原 Guest `Context` 与 `Intent`。
- **静态广播与受保护系统广播**：
  - `:authority` 的 `RuntimeSystemBroadcastReceiver` 监听系统事件（如网络切换、充电状态、开机完成、包变更）并按 Manifest `<intent-filter>` 派发至匹配的 Guest Receiver。
  - 拦截 Guest 发送的广播，按目标包名与权限规则在 Space 内部隔离路由，防止不同 Space 之间的广播串扰。

---

## 3. PendingIntent、Alarm 与 JobScheduler

- **PendingIntent 虚拟化**：
  - 拦截 `ActivityManager.getIntentSender*`，将内部的原始 Guest Intent（Activity / Service / Broadcast）持久化到 `:authority` 的 `PendingIntent` 注册表中，外层包装为指向宿主代理组件的物理 `IntentSender`。
  - 支持 `PendingIntent.send()`、`cancel()` 以及 `FLAG_NO_CREATE` / `FLAG_UPDATE_CURRENT` / `FLAG_IMMUTABLE` 等标志位语义。
- **JobScheduler 虚拟化**：
  - 拦截 `IJobScheduler.schedule` / `cancel` / `cancelAll` / `getPendingJob`。
  - 为每个 `(spaceId, packageName, guestJobId)` 分配全局唯一的宿主 `hostJobId`，将 `ComponentName` 重写为目标槽位的 `RuntimeSlotNJobService`，触发时还原 `JobParameters` 并回调 Guest 的 `JobService.onStartJob`。

---

## 4. 存储隔离 (`dataId` + SharedPreferences + SQLite)

- **目录布局**：
  - 每个 Space 下的每个安装实例拥有独立的虚拟数据根目录：`<hostFilesDir>/spaces/<spaceId>/apps/<packageName>/<dataId>/`。
  - 同时隔离内部存储（`data/data`）、Device Encrypted 存储（`data_de`）以及外部私有存储（`Android/data/<packageName>`）。
- **多进程 SharedPreferences 与文件锁**：
  - 通过 Java Context 重定向与 Native `libc` 路径重写双重保证，确保 Java `getSharedPreferences`、MMKV、SQLite `openDatabase` 以及 Native `openat`/`statx` 均落在同一物理隔离目录下。

---

## 5. Package 域与系统服务门面 (System Service Facades)

| 领域 | 拦截与虚拟化机制 |
| --- | --- |
| **PackageManager (`IPackageManager`)** | 合并虚拟空间已安装列表与宿主可见系统包；重写 `getPackageInfo`（含伪造/保持原始 APK 签名 `GET_SIGNATURES` / `GET_SIGNING_CERTIFICATES` 以通过签名校验）、`getApplicationInfo`、`queryIntentActivities`、`getInstallerPackageName`。 |
| **Notification (`INotificationManager`)** | 重写通知的 `pkg`、Channel ID 前缀（按 `spaceId + packageName` 命名空间隔离）与小图标资源，使多个 Space 下的同名应用通知互不覆盖。 |
| **Account / Clipboard / Shortcut** | 拦截 `IAccountManager`、`IClipboard`、`IShortcutService`，按 Space 隔离剪贴板与账号视图，并将桌面快捷方式 Intent 包装为免网关直达启动 Intent。 |
| **Telephony / Wi-Fi / Audio / Media** | 拦截 `ITelephony`、`IWifiManager`、`IAudioService`、`IMediaSessionManager`，修复调用方 `callingPackage` 校验，并向设备指纹接口返回当前 Space 配置的虚拟身份。 |

---

## 6. 多开任务栈隔离与 `/proc` 隐身机制

### 6.1 最近任务栏（Recents）独立卡片与品牌展示

- 壳 Activity 使用动态任务亲和性与 `FLAG_ACTIVITY_NEW_DOCUMENT` / `FLAG_ACTIVITY_MULTIPLE_TASK` 组合，使不同 Space 或不同 Guest 应用在系统最近任务列表中拥有独立的 Task。
- 在 Guest Activity 生命周期中自动注入 `ActivityManager.TaskDescription`，将最近任务卡片的标题（Label）与图标（Icon）实时替换为当前 Guest 应用的名称（附带 Space 标识）与真实图标。

### 6.2 `/proc/self/*` 进程环境隐身

为防止应用通过读取 `/proc` 发现宿主包名 `dev.tripoder.engine` 或虚拟路径，Native `path_policy` 对 `/proc/self/*`（及 `/proc/<pid>/*` 当 `<pid> == getpid()` 时）实施按需虚拟化过滤：

1. **`/proc/self/cmdline` 与 `/proc/self/comm`**：重定向至内存匿名文件或沙箱伪造文件，内容改写为 Guest 逻辑进程名（如 `com.target.app`），抹除 `dev.tripoder.engine:slotN` 痕迹。
2. **`/proc/self/maps` 与 `/proc/self/smaps`**：过滤掉包含 `tripoder`、`self-hook` 或沙箱私有目录的特征行，并将沙箱路径前缀还原为标准 `/data/app/...` 与 `/data/data/<guestPkg>/...` 视图。
3. **`/proc/self/fd/*` 与 `readlinkat`**：拦截对打开文件描述符符号链接的读取，将底层沙箱真实物理路径反映射为 Guest 期望的虚拟标准路径。
