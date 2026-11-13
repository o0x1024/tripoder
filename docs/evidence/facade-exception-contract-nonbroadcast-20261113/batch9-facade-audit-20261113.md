# 门面断言复核：落在客体栈上的抛出点普查（`build-facade-v71`）

> 日期：2026-11-13 · 设备 `d861a4fa`（Xiaomi madrid / Android 17）· 宿主 `dev.tripoder.q6`
> 上一轮：[`README.md`](README.md)（`build-facade-v56`，24 处非广播门面降级）
> 本轮起因：上文 §5.1/§5.2 把「self-hook 拦截层、`RuntimeReceiverProxy.before()`、`RuntimeActivityManager.facade()`」列为**待复核边界**，并给了它们一个豁免理由——*「不在客体调用栈上」*。本轮把这个理由逐条查了：**其中一半不成立**。
> 约束：设备处于 PIN 锁屏（`mFocusedWindow=NotificationShade`），本轮全程**不需要真机**，只做静态普查 + 编译验证。

## 1. 判据（沿用，不重定义）

「平台在这个调用点会失败吗？」

| 类别 | 含义 | 处置 |
|---|---|---|
| `platform-throw` | 平台在该调用点自己就抛（对未派发 receiver 调 `setResultData` 抛 `IllegalStateException`；null 参数抛 IAE/NPE） | 保留，类型贴近平台原话 |
| `must-degrade` | 平台在该调用点**不失败**（返回 null / false / 空集合 / 正常值） | 不得抛；记录码 + 返回平台式答案 |
| `needs-review` | 未判定 | 审计时人工判 |

## 2. 方法：家族按「跑在谁的栈上」划，不按文件划

这是本轮唯一的方法论修正。上文按**文件**分「广播族 / 非广播族」，于是 `RuntimeReceivers.java` 被整体当成「广播线程侧」。实际上同一个文件里的代码跑在两种栈上：

| 家族 | 入口 | 实际执行者 | 抛出的后果 |
|---|---|---|---|
| `receiver-pointer-stack` | `RuntimeReceiverProxy.onReceive` → `RuntimeReceivers.before/after` | **系统广播分发线程**，即客体的 `onReceive` 栈 | `onReceive` 内经 `throw` 逸出 ⇒ 客体进程 FATAL |
| `package-endpoint` | `RuntimePackages.dispatch` ← 我们的 `IPackageManager` endpoint | 客体调 `PackageManager.*` 的 Binder 线程 | 组件的 PM 调用点收到平台从不给的异常 |
| `getsystemservice` | `GuestContext.getSystemService` → `Runtime*.facade()` | **客体自己的线程** | `Context.getSystemService` 抛 `SecurityException` |
| `host-internal` | 其余不变式 | 运行时自身 | 不参与判定（本轮未审） |

审计器：[`../../android/scripts/facade_throw_audit.py`](../../android/scripts/facade_throw_audit.py)（正则抓 `throw new X("CODE")`，按上表归组，再对 `PLATFORM_THROW` / `MUST_DEGRADE` 两张表分类；`--strict` 用作抑制新增的门禁）。

## 3. 普查结果

```
total_throwing_sites=1888
guest_facing=71  must_degrade=28  needs_review=1857(incl. host-internal)  platform_throw=3
metric=70        # 需要处理的客体栈抛出点（降级前 75）
```

落在三条客体栈上的 **71 处**，按家族：

- `getsystemservice`：13 处 `*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED` 等（`RuntimeAudio`、`RuntimeBattery`、`RuntimeConnectivity`、`RuntimeLocation`、`RuntimeNetworkStats`、`RuntimePower`、`RuntimeTelephony`、`RuntimeWifiP2p`、`RuntimeActivityManager`）。
- `package-endpoint`：`PACKAGE_ENDPOINT_NOT_READY`、`PACKAGE_CACHED_ENDPOINT_CHANGED`，以及 20+ 处 `PACKAGE_*` / `COMPONENT_*` 不变式。
- `receiver-pointer-stack`：`BROADCAST_FINAL_RECEIVER_MISMATCH`、`RECEIVER_NOT_OWNED`、`RECEIVER_ACTIVATION_UNCONFIRMED`、`SYSTEM_RECEIVER_NOT_REGISTERED`、`BROADCAST_RECEIVER_MISMATCH`、`BROADCAST_ORDERED_MISMATCH`、`PENDING_RESULT_REUSED`、`RECEIVER_CLAIM_FAILED`、`RECEIVER_CALLBACK_STACK_MISMATCH`。

## 4. 关键更正：`getSystemService` 家族不在宿主线程上

上文 §5.2 的豁免理由是「前者在广播分发线程，后者是运行时自身不变式」。代码路径否掉了后半句：

```java
// GuestContext.java:101
if(ACTIVITY_SERVICE.equals(name))return engine.activityManager.facade();
...
// RuntimeActivityManager.java:12（改造前）
synchronized ActivityManager facade(){
    facade=RuntimeBridge.createActivityManager(engine.host);
    if(facade==null||facade==platform())throw new SecurityException("ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID");
}
```

调用者是**客体**：`someView.getContext().getSystemService(ACTIVITY_SERVICE)` 一路直入 `facade()`。真机在这里的答复是「一个 Manager 或 null」，永远不会是 `SecurityException`。同一个形状还有 12 个服务（见 §3）。

`receiver-pointer-stack` 的豁免同样只有一半对：`before()` 确实跑在系统分发线程上，但**那正是客体的 `onReceive` 帧**——`RuntimeReceiverProxy.onReceive` 用 `catch(RuntimeException|Error){...throw error;}` 原样转抛，所以「在宿主线程上」不等于「不会杀客体」。更硬的一条在 `after()`：

```java
// 改造前：guest.onReceive 已经成功返回，然后在 finally 里抛出
}).catch(...){if(failure==null)throw acknowledgement;}
```

客体 `onReceive` 成功之后，我方对账失败反而把成功变成崩溃——平台在 `onReceive` 返回后没有任何会失败的步骤。

## 5. 本轮已改 5 处（v71）

| 位置 | 旧 | 新 | 语义 |
|---|---|---|---|
| `RuntimeReceivers.before` | `throw SecurityException(RECEIVER_NOT_OWNED)` | `drop` + **返回 physical**（直通） | 平台对「receiver 不在我们的表里」照常投递；`physical` 就是系统交给我们的那一份真 Intent |
| 同上 | `throw ISE(RECEIVER_ACTIVATION_UNCONFIRMED)`（5s 超时） | `drop` + 返回 physical | 平台注册是同步的，没有「激活未确认」这个状态 |
| 同上 | `throw ISE(RECEIVER_ACTIVATION_INTERRUPTED)` | `drop` + 返回 physical | 同上 |
| `RuntimeReceivers.after` | `throw SecurityException(RECEIVER_CALLBACK_STACK_MISMATCH)` | `drop` + 返回 | 发生在 `onReceive` 返回之后，平台此处无失败点 |
| `RuntimeActivityManager.facade` | `throw SecurityException(ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID)` | 降级为**平台对象**（新增 `platformOrNull()`） | `getSystemService` 的合法答复是 Manager 或 null |

选择「直通 physical」而不是「返回 null 跳过」的理由：`physical` 是系统投递进 `onReceive` 的原始 Intent（静态 receiver 场景下它与平台交给应用的就是同一份），跳过等于让客体**丢一条广播**，那才是平台不会发生的事。代价写进 §8。

审计器口径的位移：`guest_facing 76 → 71`、`metric 75 → 70`（可复现，见 §7）。

编译验证（无真机）：

```
BUILD_EXIT=0
android/product/build-facade-v71/tripoder-q6.apk  2,323,699 B
sha256 c8db4d6d5a98ba43c269d493514567e47a6264b4aa24df12c1117ffae9b53103
```

## 6. 剩余工作清单（精确到点）

按家族，每条都给「平台会怎么答」：

| 家族 | 剩余 | 处置模板 |
|---|---|---|
| `receiver-pointer-stack` | `BROADCAST_FINAL_RECEIVER_MISMATCH`、`SYSTEM_RECEIVER_NOT_REGISTERED`、`BROADCAST_RECEIVER_MISMATCH`、`BROADCAST_ORDERED_MISMATCH`（`ordered` 就以 `receiver.isOrderedBroadcast()` 为真值，不拿我们的答复去比对）、`PENDING_RESULT_REUSED`×2、`RECEIVER_CLAIM_FAILED`、`SYSTEM_RECEIVER_CLAIM_FAILED` | 一律 `drop` + 投递 `physical`（claim 失败时还要 `event(...,"failed")` 让账本收敛） |
| `package-endpoint` | `PACKAGE_ENDPOINT_NOT_READY`（`asBinder[]` 在客体构造 `ApplicationPackageManager` 时被调用）、`PACKAGE_CACHED_ENDPOINT_CHANGED`、`COMPONENT_SETTINGS_QUERY_FAILED/WRITE_FAILED` | 前者返回 null binder（平台拿不到服务时的样子）+ 码；后者 `drop` + 返回空 Bundle |
| `getsystemservice` | 其余 12 个 `*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED` / `AUDIO_CALLBACK_OWNER_INVALID` / `WIFI_P2P_CONTEXT_OWNER_INVALID` | 同 §5 第 5 行：降级为平台对象或 null |

未分类的 `PACKAGE_*` 不变式（约 20 处）需要逐个回答一个问题：**它在客体线程上被触发过吗**——若只在 authority 侧触发，转 `host-internal`；否则按表降级。

## 7. 复现

```bash
python3 android/scripts/facade_throw_audit.py            # 报告
python3 android/scripts/facade_throw_audit.py --strict   # 门禁（当前基线 violations=70）
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v71 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.q6
```

## 8. 可入 control-test 的断言清单

十条，每条都只依赖「平台会怎么答」这一条判据；`façadeContract` 里新增 F1–F8，F9/F10 是门禁与回归项。

| # | 断言 | 期望（平台答复） | 失败时码 |
|---|---|---|---|
| F1 | 对一个**从未注册**的 receiver 调 `RuntimeReceivers.before()` | 返回 physical Intent，**不抛** | `RECEIVER_NOT_OWNED` |
| F2 | registration 的 `activated` 未完成（`await` 超时）即投递 | 返回 physical，不抛、不丢 | `RECEIVER_ACTIVATION_UNCONFIRMED` |
| F3 | 在空回调栈上调 `after()`（模拟 `onReceive` 返回后对账失败） | 不抛 | `RECEIVER_CALLBACK_STACK_MISMATCH` |
| F4 | 令 `RuntimeBridge.createActivityManager()` 返回平台实例（注入） | `facade()` **返回平台对象**，不抛 | `ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID` |
| F5 | 令 `host.getSystemService(ACTIVITY_SERVICE)` 返回 null | `facade()` 返回 null，不抛 | `PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE` |
| F6 | 未初始化时调 `RuntimePackages.binder()`（`asBinder[]` 路径） | 返回 null binder，不抛 | `PACKAGE_ENDPOINT_NOT_READY` |
| F7 | 对未派发的 receiver 调 `setResultData`（与 `ANDROID_PENDING_RESULT_REQUIRED` 对齐） | **保持抛出**，且类型是平台的 IllegalState 形状 | —（`platform-throw` 对照组） |
| F8 | 上面每条降级都必须留下码：抓 `TripoderFacade` 日志 | 出现对应码，不是静默吞掉 | — |
| F9 | `facade_throw_audit.py --strict` 的 `violations` ≤ 70 | 只降不升（每关掉一处就下调基线） | `FACADE_THROW_BUDGET_EXCEEDED` |
| F10 | 微博 PushService 路径（v60 场景）仍不崩：`onReceive` 返回后客体在自建线程池调 `setResultData` | 不 `FATAL` | —（**需真机，等解锁**） |

## 9. 边界（别过度推断）

1. **未上机**：本轮只做到「编译通过 + 静态口径收敛」。5 处改动的真机行为（尤其直通 `physical` 会不会让客体看到我们的 envelope extras）**未验证**，等设备解锁后跑 F1–F8 + 微博回归。
2. **直通有损**：`physical` 未经 `RuntimeBroadcastIntent.decode` 整理，binder extras 仍是占位形式。走这条路的都是「我们本来答不上来」的分支，但仍是一次形状变化。
3. **计数会漂**：`1888` 是全仓正则命中数，含探测/夹具类；只有 `guest_facing=71` 这一列参与判定。
4. `PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE` 仍计为 FAIL——它的三个调用点（`memoryClass`/`lowRam`/`memoryInfo`）都在 `try` 内，实际不会逸出；审计器有意保守，不做调用点数据流分析。

## 关联

- 非广播门面判据与前 24 处：[`README.md`](README.md)
- 广播族：[`../facade-exception-contract-20261113/README.md`](../facade-exception-contract-20261113/README.md)
- 规范条目：[`../../guest-compatibility.md`](../../guest-compatibility.md) §3 第 6 条
