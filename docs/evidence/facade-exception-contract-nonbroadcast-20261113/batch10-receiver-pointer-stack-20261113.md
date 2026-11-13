# 第 10 批：receiver-pointer-stack 家族收口（v72）

**日期**：2026-11-13
**前置**：`batch9-facade-audit-20261113.md`（按「跑在谁的栈上」普查，metric 75→70）
**本轮范围**：`RuntimeReceivers.before()/after()/asynchronous()/event()` —— 即客体的 `onReceive` 帧与其 `goAsync` 后续线程
**设备**：未使用（PIN 锁屏），本轮只有静态审计 + 编译验证

---

## 1. 判据（沿用，不改）

> 平台在这个调用点会失败吗？会 → 允许抛；不会 → 一个异常都不能给，只能返回平台会返回的值。

`RuntimeFacadeContract` 的 javadoc 已把这条写成契约：门面只能暴露 `SecurityException`（权限/加载器拒绝）与
`IllegalArgumentException`（平台同样拒绝的参数），其余内部失败经 `drop(code, error)` 记录后降级为 no-op 或最接近的诚实投递。
`drop()` 本身只写 `Log.w("TripoderFacade", …)`（每码最多两条，上限 64 码），不会抛。

## 2. 为什么这一族必须先修

`RuntimeReceiverProxy.onReceive` 的骨架是：

```java
try{ entered=true; logical=delivery.before(guest,physical,token); … guest.onReceive(context,logical); }
catch(RuntimeException|Error error){ failure=error; }
finally{ …; if(entered) try{delivery.after(guest,failure==null);}catch(RuntimeException ack){ if(failure==null) throw ack; } }
```

两个后果：

1. `before()` 抛 → 客体在**尚未进入 onReceive** 时就 FATAL（`Error receiving broadcast`）；
2. `after()` 抛 → 客体 `onReceive` **已经成功返回**，仍被 `throw ack` 升级成崩溃。平台在 onReceive 返回之后没有任何会失败的动作。

`before()` 覆盖的三条路径：静态接收器（系统分发线程）、动态注册接收器（系统分发线程）、系统广播 action（同上）。
这三条都不是「宿主线程」。

## 3. 本轮改掉 9 处（metric 70 → 61）

| # | 位置 | 旧行为 | 新行为 | 平台在该点的真实答复 |
|---|---|---|---|---|
| 1 | `before()` finals 分支 | `throw SECURITY(BROADCAST_FINAL_RECEIVER_MISMATCH)` | 记码，**跳过** finals 特例，继续走常规注册路径 | 平台没有「final receiver 账本」，只是投递 |
| 2 | `before()` 系统广播分支 | `throw SECURITY(PENDING_RESULT_REUSED)` | 记码后**覆盖** entry | 每次派发一个全新 pendingResult，复用不可达 |
| 3 | `before()` | `throw SECURITY(SYSTEM_RECEIVER_NOT_REGISTERED)` | 记码 + **直通 physical** | 系统 action 的广播照常投递 |
| 4 | `before()` | `throw SECURITY(BROADCAST_RECEIVER_MISMATCH)` | 记码 + 直通 physical | 匹配由系统完成，客体侧没有第二意见 |
| 5 | `before()` claim | `throw SECURITY(BROADCAST_ORDERED_MISMATCH)` | 记码，**以 `receiver.isOrderedBroadcast()` 为准** | 平台的真值就是 receiver 自己的标志 |
| 6 | `before()` claim | `throw SECURITY(PENDING_RESULT_REUSED)` | 记码后**覆盖** entry | 同 #2 |
| 7 | `before()` claim 失败 | `throw ISE(RECEIVER_CLAIM_FAILED)` | 记码 + 直通 physical | 平台没有「claim」这一步，broker 超时是我们的 |
| 8 | `asynchronous()` | `throw SECURITY(RECEIVER_ASYNC_OUTSIDE_CALLBACK)` | 记码 + return | `setResultData` 在平台侧不因「不在回调内」而抛 |
| 9 | `asynchronous()` | `throw SECURITY(PENDING_RESULT_IDENTITY_MISMATCH)` | 记码，继续上报事件 | 平台用 receiver 自己的 pendingResult，不比对身份 |

另修一处**不计入 metric**（审计器的码正则不匹配带后缀的码）：

| 10 | `event()` | `throw ISE(RECEIVER_EVENT_UNCONFIRMED:<event>)` | 记码不抛 | 平台没有「事件上报」这一步；且该点在 `after()` 里发生在 onReceive 成功返回之后 |

`event()` 是最高价值的一处：它被 `before()`（L279 systemDone）、`after()`（L304 returned/failed）、
`asynchronous()`、`finished()` 共同调用，任一次 authority 抖动都会变成客体崩溃。

### 直通 `physical` 的语义与代价

`before()` 返回 `physical` 时，客体拿到的是**系统交进 onReceive 的那份 Intent**——对静态接收器就是平台原本会给它的东西。
代价：这份 Intent 可能带我们自己的信封 extras（`tripoder.delivery` 票号、binder 占位），
以及不会经过我们的解码/字面化。这是「泄漏少量内部结构」换「不崩」，与 `RECEIVER_NOT_OWNED`（v71）口径一致。

## 4. 复查后**保留**的抛出点（附理由）

| 码 | 位置 | 为什么保留 |
|---|---|---|
| `ANDROID_PENDING_RESULT_REQUIRED` | `before()` L274 | 客体把从未派发的 receiver 直接调 onReceive 时，平台自己也会抛（`checkSynchronousHint` → ISE）。属「平台会失败」，但**抛型应是 ISE 而非 SECURITY**，留待下一轮统一口径 |
| `RECEIVER_NOT_REGISTERED` | `unregister()` L177 | AOSP `unregisterReceiver` 对未知 receiver 抛 IAE，平台一致 |
| `BROADCAST_INTENT_REQUIRED` | `send()` L200 | `sendBroadcast(null)` 平台 NPE，属平台失败面 |
| `BROADCAST_RESULT_REQUIRES_ORDERED` | L228 | 非有序广播上调 set/getResult*，平台抛 ISE |
| `ORDERED_SYSTEM_BROADCAST_UNSUPPORTED` | L288 | 我们确实不支持；但平台支持 → 应改成「降级成普通发送」而不是 UOE（需要单独一轮验证语义影响） |
| `BROADCAST_PREPARATION_UNCONFIRMED` | L241 | `prepare()` 失败时返回 null 需调用方全部处理 null（`send()` 当前没处理），先不动 |
| `BROADCAST_SEND_UNCONFIRMED` | L271 | `host.sendBroadcast/sendOrderedBroadcast` 真会抛（权限、bad parcelable），平台同样失败面 |
| `RECEIVER_LOADER_MISMATCH` | L61/L229 | 加载器隔离，属「权限/加载器拒绝」类 |
| `STICKY_ACTION_REQUIRED` | L69 | 参数错误面；平台对 sticky 的答复不一致，保留 |

## 5. 度量

```
v71: total_throwing_sites=1888  guest_facing=71  must_degrade=28  metric=70
v72: total_throwing_sites=1879  guest_facing=62  must_degrade=21  metric=61
```

复现：`python3 android/scripts/facade_throw_audit.py`（末尾 JSON 的 `metric` 即待处理数；F9 门禁只降不升）。

## 6. 构建

```
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v72 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64

BUILD_EXIT=0
tripoder.apk  2,323,699 B  sha256 6ce8d4948d9263f2f0c557355c8c…
```

## 7. 边界（别过度推断）

1. **只编译，未上机**：v72 没有安装到设备（PIN 锁屏）。`BUILD_EXIT=0` 只证明 javac 接受这 9 处改写，
   不证明广播投递行为在真机上符合预期。
2. 改的是「少抛异常」，方向单一（只会让客体更不容易崩）；但 #7 的直通会让 `after()` 因空栈记一条
   `RECEIVER_CALLBACK_STACK_MISMATCH`（已是记码不抛），日志里出现该码是**预期**而非回归。
3. 未做 control-test 落地：F1–F10 断言清单仍在 `batch9-facade-audit-20261113.md`，本轮只把 F9 基线从 70 下调到 61。

## 8. 下一步

1. `package-endpoint` 家族（`RuntimePackages.java`）：`PACKAGE_ENDPOINT_NOT_READY`（`asBinder[]`，客体构造
   `ApplicationPackageManager` 时被调）、`PACKAGE_CACHED_ENDPOINT_CHANGED`（L94），以及 26 处待判码按同一判据分类。
2. `getSystemService` 家族余 12 处：13 个 `*_FACADE_IDENTITY_INVALID` / `*_FACADE_REQUIRED`，v71 只改了 ActivityManager。
3. 每关一处下调 F9 基线并重跑 build，全程不需要真机。
