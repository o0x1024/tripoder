# 门面异常契约：从广播族推进到非广播门面（`build-facade-v56`）

> 日期：2026-11-13 · 设备 `d861a4fa`（Xiaomi madrid / Android 17）· 宿主 `dev.tripoder.q6`
> 上一轮：[`../facade-exception-contract-20261113/README.md`](../facade-exception-contract-20261113/README.md)（`build-facade-v55`，广播族闭合）
> 本轮回答一个问题：**同一条不变量在 Activity/Service、PackageManager、ActivityManager、ContentProvider 上会遇到什么。**

## 1. 判据（先定判据，再改代码）

「平台会不会这样抛」不是「这个异常好不好看」，而是三分类：

| 类别 | 例子 | 处置 |
|---|---|---|
| 平台的**拒绝通道** | `SecurityException`（权限/loader/受保护广播） | 保留，原样逸出 |
| 平台的**参数通道** | `IllegalArgumentException`（null intent、越界投影）、`FileNotFoundException`（provider 文件不存在）、`IllegalArgumentException("Unknown URL …")`（AOSP 对不可达 authority 的原话） | 保留或**翻译成**它 |
| 运行时**内部策略/容量** | `IllegalStateException`、`UnsupportedOperationException`、带 cause 的包装 | **降级**：记录 + 平台式答复（null / false / 0 / 空集合 / 空 Bundle） |

关键是第三类之外还要做**翻译**：不是把所有东西吞掉，而是把内部失败换成「平台在这一步会说的那句话」。provider 打不开就抛平台原样的 `FileNotFoundException`；authority 拿不到就抛 AOSP 原话 `IllegalArgumentException("Unknown URL content://…")`——这两处比原来的 `IllegalStateException` **更**接近真机，而不是更宽松。

## 2. 覆盖面：本轮改造 24 处

### `GuestContext`（Context 组件 API，客体主线程）

| 旧行为 | 新行为 | 观测码 |
|---|---|---|
| `startService` 包装 ISE | 返回 `null`（平台「没启动」的答案） | `SERVICE_START_FAILED` |
| `stopService` 包装 ISE | 返回 `false` | `SERVICE_STOP_FAILED` |
| `bindService`（#1/#2/#5 三个重载）包装 ISE | 返回 `false` | `SERVICE_BIND_FAILED` / `ISOLATED_BIND_FAILED` |
| `bindService(BindServiceFlags)` 用 `Math.toIntExact` | **`ArithmeticException` 也会逸出**（此前未登记）→ 掩码到 32 位 | `BIND_FLAGS_REDUCED` |
| `bindIsolatedService` 标志越界 → UOE | 掩码后继续 | `ISOLATED_BIND_FLAGS_REDUCED` |
| `unbindService` 包装 ISE | 静默 | `SERVICE_UNBIND_FAILED` |
| `updateServiceBindings` → UOE | 空操作 | `SERVICE_UPDATE_BINDINGS_IGNORED` |
| `startInstrumentation` → UOE | 返回 `false` | `INSTRUMENTATION_UNSUPPORTED` |
| `sendStickyOrderedBroadcast`(+AsUser) → UOE | 退化为普通有序广播（不保留 sticky 视图） | `STICKY_ORDERED_BROADCAST_FLATTENED` |
| `createDeviceProtectedStorageContext` → ISE | 返回当前 context（存储仍可用，只是不带 device-protected 语义） | `DEVICE_PROTECTED_STORAGE_FAILED` |

### `GuestPackageManager`（PM 门面）

| 旧行为 | 新行为 | 观测码 |
|---|---|---|
| `getApplicationInfo` 标志字超 32 位 → UOE | 窄化后照常返回 | `APPLICATION_INFO_FLAGS_REDUCED` |
| `getServiceInfo` 同 | 同 | `SERVICE_INFO_FLAGS_REDUCED` |
| `ProviderInfo` 同 | 同 | `PROVIDER_INFO_FLAGS_REDUCED` |

依据：平台对**不认识的标志位是忽略**，不是抛异常——这是「新应用跑在旧平台」的正常路径。

### `RuntimeActivityManager`（ActivityManager 门面）

| 旧行为 | 新行为 | 观测码 |
|---|---|---|
| `getMemoryClass` 平台服务缺失 → ISE | 回落到 `Runtime.maxMemory()` 的 MB 数（不编造常量） | `ACTIVITY_MANAGER_UNAVAILABLE` |
| `isLowRamDevice` → ISE | `false` | 同 |
| `getMemoryInfo` → ISE | 保持输出全 0 | `MEMORY_INFO_UNAVAILABLE` |
| `getRunningAppProcesses` 观察失败 → ISE | 空列表 | `PROCESS_OBSERVATION_UNAVAILABLE` |
| `getProcessMemoryInfo`（预算/越界 pid/观察失败/身份漂移）→ ISE/SecurityException | 空数组 | `PROCESS_MEMORY_*` |
| `getHistoricalProcessExitReasons` / 启动历史 → ISE | 空列表 | `EXIT_HISTORY_RESPONSE_REQUIRED` / `APPLICATION_START_HISTORY_UNAVAILABLE` |

### `RuntimeProviderFacade` / `RuntimeProviders`（ContentResolver 路线）

| 旧行为 | 新行为 | 观测码 |
|---|---|---|
| `query` 内部失败 → ISE | `null` cursor | `PROVIDER_QUERY_FAILED` |
| `query` 带 `CancellationSignal` → 抛「不支持」 | 忽略信号、照常查询 | `PROVIDER_CANCELLATION_IGNORED` |
| `getType` → ISE | `null` | `PROVIDER_TYPE_FAILED` |
| `insert/update/delete/bulkInsert` → ISE | 空 Bundle（`insert`→null、`update/delete`→0） | `PROVIDER_WRITE_FAILED:<kind>` |
| `call` → ISE | `null`（平台「provider 不支持」的答案） | `PROVIDER_CALL_FAILED` |
| `openFile/openAssetFile/openTypedAssetFile` → ISE | **平台原样的 `FileNotFoundException`** | `PROVIDER_OPEN_*_FAILED` |
| 引用计数失衡 → ISE | 记录后返回 | `PROVIDER_USE_UNBALANCED` |
| authority 不可达 / 传输缺失 → ISE | **AOSP 原话 `IllegalArgumentException("Unknown URL content://…")`** | `PROVIDER_TRANSPORT_UNAVAILABLE` / `PROVIDER_ACQUIRE_FAILED:<authority>` |

`RuntimeFacadeContract` 同时新增两个原语：`nothing(code,error,answer)`（记录 + 返回平台式答案）与 `flagBits(code,flags)`（窄化标志字）。

## 3. 真机证据：京东一次冷启动就命中 3 次

`build-facade-v56`（`tripoder-q6.apk` sha256 `a9614cd8…`）安装后，Workbench → 京东：

```
LIST: ['BOSS直聘', '京东', '抖音']
TOP: topResumedActivity=...dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP0$F0M1C0
TEXTS: ['推荐','手机','女装','医药健康','男装','大额补贴5折起','¥120','惊喜红包',...]
CRASH: 0
--------- beginning of main
11-13 03:39:02.704 W TripoderFacade: SERVICE_START_FAILED dropped cause=java.lang.IllegalStateException:android.content.ActivityNotFoundException:PACKAGE_NOT_IN_SPACE:com.xiaomi.xmsf
11-13 03:39:02.707 W TripoderFacade: SERVICE_START_FAILED dropped cause=java.lang.IllegalStateException:android.content.ActivityNotFoundException:PACKAGE_NOT_IN_SPACE:com.xiaomi.xmsf
11-13 03:39:04.240 W TripoderFacade: APPLICATION_INFO_FLAGS_REDUCED dropped
```

三条日志各自对应一类此前**必然逸出到客体线程**的失败：

1. **×2 `SERVICE_START_FAILED`**：京东启动一个属于 `com.xiaomi.xmsf`（小米推送框架）的服务。容器里没有这个包，运行时把它包成 `IllegalStateException(ActivityNotFoundException: PACKAGE_NOT_IN_SPACE)`。平台对同样情形只会「服务没起来」，不会让调用方崩。v55 之前这行是 `throw`。
2. **`APPLICATION_INFO_FLAGS_REDUCED`**：京东查询 applicationInfo 时带了 32 位以外的标志位，旧实现直接 `UnsupportedOperationException`。平台是忽略未知位。

**诚实边界**：这 3 次调用在 v55 上收到的是非平台异常；它是否当场变成崩溃取决于调用方有没有 catch，本轮**没有**做 v55/v56 的 A/B 崩溃对照。可确证的是「运行时不再向客体投递平台不会给的异常」，不可确证的是「v55 上一定崩」——后者需要单独对照实验（结论未测）。

同时：**降级没有把问题藏起来**——快乐路径上只出现 3 行、带原因、按码去重，且京东首页功能正常。这正是「可观测的降级」与「静默忽略」的区别。

## 4. 可执行契约

`BroadcastResolutionIntegration#facadeContract` 新增 3 项（总计 32）：

```
INSTRUMENTATION_RESULT: checks=32
INSTRUMENTATION_RESULT: passed=true
INSTRUMENTATION_RESULT: resultSha256=09bfa9fae2f179cda5f65ce48bf9720a3c52c402974dc60702e482441fc5be73
```

新增断言：`flagBits` 对越界标志字**窄化不抛**且已知位原样保留；`nothing(...)` 返回平台式答案而不是异常。

## 5. 仍未覆盖（别过度推断）

> **已复核（2026-11-13）** → [`batch9-facade-audit-20261113.md`](batch9-facade-audit-20261113.md)：第 1 条 `PACKAGE_ENDPOINT_*` 现只剩 2 处（`PACKAGE_ENDPOINT_NOT_READY` / `PACKAGE_CACHED_ENDPOINT_CHANGED`）；第 2 条的豁免理由**有一半不成立**——`RuntimeReceiverProxy.before()/after()` 跑在客体的 `onReceive` 栈上，`getSystemService` 家族的 13 处 `*_FACADE_IDENTITY_INVALID` 也在客体栈上。已改 5 处（v71），客体栈抛出点由 76 收敛到 71。

1. **self-hook 拦截层**：`RuntimeSelfHook` 对 `ApplicationPackageManager` 的 `intercept()` 仍保留 `SecurityException("PACKAGE_ENDPOINT_*")` 一类断言——它们发生在 Binder 调用内部，语义上是「平台拒绝」，但**未逐条按本判据复核**。
2. **宿主线程侧**：`RuntimeReceiverProxy.before()`、`RuntimeActivityManager.facade()` 的 `ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID`、`RuntimeServices` 内部仍在抛 `IllegalStateException`——它们不在客体调用栈上（前者在广播分发线程，后者是运行时自身不变式），本轮按「不落在客体栈」保留，但这是一条**需要持续复核的边界**，不是永久豁免。
3. **降级有损**：`startService` 返回 null 等于「没启动」；provider 空 Bundle 等于「没写」。应用不会崩，但会看到「静默没做」。
4. **样本**：1 个应用（京东）、1 次冷启动。BOSS直聘本轮未重跑（v55 已验）。

## 6. 复现

```bash
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v56 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.q6

adb -s d861a4fa install --no-incremental -r android/product/build-facade-v56/tripoder-q6.apk
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v56/tripoder-control-test.apk
adb -s d861a4fa shell am instrument -w -r -e runId control-<16 hex> \
  dev.tripoder.controltest/dev.tripoder.controltest.BroadcastResolutionIntegration

# 真机观测：容器内启动京东后
adb -s d861a4fa logcat -d -s TripoderFacade:W
```

## 关联

- 广播族同不变量：[`../facade-exception-contract-20261113/README.md`](../facade-exception-contract-20261113/README.md)
- 规范条目：[`../../guest-compatibility.md`](../../guest-compatibility.md) §3 第 6 条
- 代码：`RuntimeFacadeContract.java`（判据与记录点）、`GuestContext.java`、`GuestPackageManager.java`、`RuntimeActivityManager.java`、`RuntimeProviderFacade.java`、`RuntimeProviders.java`
