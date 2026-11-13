# 第 2 批 · 网易云音乐（com.netease.cloudmusic）：启动崩于系统存储服务的 uid/包名一致性校验

- 设备：`d861a4fa`，容器构建 `build-facade-v66`（`b584e4f3…`）
- 时间：2026-11-13 13:10
- 结论：**不可用（结构性，非本应用风控）**。崩溃点是 `IStorageManager.getVolumeList` 抛
  `java.lang.SecurityException: callingPackage does not match UID`，即**系统服务**在核对
  「调用包名 ↔ 调用 uid」一致性时失败。这是 C1 身份事实（客体以宿主 uid 10542 运行、
  却以 `com.netease.cloudmusic` 自称）第一次由**系统服务**而非第三方风控引擎执行。

## 1. 现场

UI（Workbench 失败框）：

```
应用没有启动
android.os.DeadObjectException:null
[知道了]
```

`crash` 缓冲（`FATAL EXCEPTION: Init Thread #5`，pid 11054）：

```
Process: com.netease.cloudmusic, PID: 11054
java.lang.SecurityException: callingPackage does not match UID
  at android.os.Parcel.createExceptionOrNull(Parcel.java:3414)
  at android.os.storage.IStorageManager$Stub$Proxy.getVolumeList(IStorageManager.java:1465)
  at android.os.storage.StorageManager$1.apply(StorageManager.java:331)
  at android.os.storage.StorageManager$1.apply(StorageManager.java:320)
  at android.app.PropertyInvalidatedCache.recompute(PropertyInvalidatedCache.java:1546)
  at android.os.storage.StorageManager.getVolumeList(StorageManager.java:1483)
  at android.os.Environment$UserEnvironment.getExternalDirs(Environment.java:251)
  at android.os.Environment.getExternalStorageDirectory(Environment.java:903)
  at gu.b$c.invoke(ProGuard:2)
  at kotlin.SynchronizedLazyImpl.getValue(ProGuard:21)
  at com.netease.cloudmusic.core.filemanager.FileManagerImp.register(ProGuard:69)
  at com.netease.cloudmusic.process.task.app.create.WorkPathCleanerTask.init(ProGuard:24)
  at com.netease.cloudmusic.core.startup.StartupTask.run(ProGuard:41)
  at ej.a.run(ProGuard:5)
  at java.util.concurrent.ThreadPoolExecutor.runWorker(...)
```

三件套其余两项：进程 `com.netease.cloudmusic` 已被回收（`no netease process`），
准入栅栏 0，`TripoderFacade` 无降级记录 —— 说明**没有**落到我们的门面降级路径上。

## 2. 判据：这次不是「应用风控」，也不是门面抛异常

- 栈里没有任何 `dev.tripoder.runtime.*` 帧（连 `callApplicationOnCreate` 都没进到这里，
  崩溃发生在 `Application.onCreate` 之后的 StartupTask 线程）。
- 异常由 `IStorageManager$Stub$Proxy` **从系统服务读回来的**（`Parcel.readException`），
  即抛出者是 `system_server` 的 StorageManagerService，不是我们。
- 也就是说：客体的 `ActivityThread.currentOpPackageName()` 是 `com.netease.cloudmusic`，
  而 Binder 调用方 uid 是 10542（宿主）→ 服务端一致性校验拒绝。

这正是 `docs/douyin-risk-control-strategy-analysis-20261112.md` 里 C1 的同一个事实，
只是执行者从「抖音服务端风控」换成了「AOSP 存储服务」。**影子身份不只被应用看，也被系统服务看。**

## 3. 已有的设计为什么没挡住

仓库里本来就有这条防线：`android/runtime/src/dev/tripoder/runtime/RuntimeStorageIdentity.java`
——它在 Binder 边界重写 `IStorageManager.getVolumeList` 的 `callingPackage`（guest 包名 → 宿主包名），
挂在 `RuntimeSelfHookPackages` 对 `android.os.ServiceManager#getService("mount")` 的钩子上。

本轮实测它**没有生效**，判据是日志里一条都没有：

```bash
adb -s d861a4fa logcat -d | grep -iE "TripoderStorageIdentity|storage-identity"   # 空
```

（`RuntimeStorageIdentity.forward()` 首 4 次调用必打 `getVolumeList first=… callingPackage=… -> …`；
`transactionCode()` 解析失败会走 `RuntimeFailThrough.note("storage-identity", …)`。两种输出都没有 ⇒
`wrap()` 根本没被调用，不是重写写错了。）

自检日志证明钩子本身装好了：`TripoderSelfHook READY:READY:29/29:bindings=228`。

最可能的解释：`StorageManager` 在**框架代码内部**取 `mount` 服务（`StorageManager.<init>` /
静态缓存初始化），而当前 self-hook 的执行策略自我声明为
`preexistingCompiledFramesCovered:false`、`allowUnhookedCompilation:true`
——即**框架自身（boot image）里的调用点不保证被去优化**，所以挂在
`ServiceManager` 上的回调对框架调用方不生效，只对应用字节码调用方生效。

## 4. 修的方向（按代价排序）

1. **把投影提到应用可见的 API 上**：钩 `android.os.Environment#getExternalStorageDirectory()`
   这类**应用直接调用**的静态方法，直接返回宿主的真实路径（`/storage/emulated/0`），
   根本不进 Binder。这正是「在平台不失败处不失败」的落地方式。
   - 代价：`FixedRuntimeHooks` 的目标表是**按 owner 索引 + 声明摘要（SHA-256）钉死**的
     （`OWNERS`/`HASHES`，运行时用 `FrameworkAccess.fixedMembers(owner)` 现算摘要比对），
     新增 `android.os.Environment` 这个 owner 必须走生成管线重算摘要，不能手改常量。
2. **让 `RuntimeStorageIdentity` 真正接上**：确认框架取 `mount` 的调用点是否需要显式
   「框架调用覆盖」策略（把 `preexistingCompiledFramesCovered` 打开或对该 owner 单独去优化）。
   需要一次带诊断日志的构建来定位，成本一次 build。
3. 兜底：不做（这不是可以靠文案或重试绕开的检查，重启/重试都会在同一行崩）。

## 5. 边界

- 只测了一个入口（冷启动到 `WorkPathCleanerTask`）；不排除该应用还有其它先崩点。
- 未验证「钩了 Environment 之后」该应用是否还有第二个同类检查（如 `getStorageVolumes`、
  `getAllocatableBytes`、`Context.getExternalFilesDir`）。
- 本轮**没有改代码**，因此没有新构建；结论只对本轮 bin（v66）成立。

## 6. 复现

```bash
adb -s d861a4fa logcat -c; adb -s d861a4fa logcat -b crash -c
adb -s d861a4fa shell am force-stop dev.tripoder.q6; adb -s d861a4fa shell am force-stop dev.tripoder.q7
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
python3 android/scripts/guest_ui.py tap "重新连接"
python3 android/scripts/guest_ui.py tap "网易云音乐" --scroll
sleep 40
adb -s d861a4fa logcat -b crash -d | head -12
```
