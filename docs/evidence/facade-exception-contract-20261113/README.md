# 门面异常契约：只允许平台自己会给出的异常逸出（`build-facade-v55`）

> 日期：2026-11-13 · 设备 `d861a4fa`（Xiaomi madrid / Android 17）· 宿主 `dev.tripoder.q6`
> 触发事件：BOSS直聘一键登录成功后主进程被运行时打死（`build-hint-v53`），修复形状校验后（`build-bcast-v54`）仍存在同族风险。本文把「不得抛出平台不会抛的异常」从一次性修补提升为**全局不变量**，并给出可执行契约断言。

## 1. 结论

**运行时门面（facade）向客体暴露的任何 API，只允许逸出平台自己会给出的异常；其余内部失败必须降级为「记录 + 丢弃」或「降级到最近的可投递形状」。**

平台只会用两类异常回答客体：

| 平台形状 | 含义 | 门面是否可抛 |
|---|---|---|
| `SecurityException` | 权限 / loader 拒绝（受保护广播的拒绝也是这一种） | **可以** |
| `NullPointerException` / 参数缺失 | 传入 null intent 之类（本运行时改为在边界前抛 `IllegalArgumentException`） | 可以，但只在边界显式校验处 |

除此之外（`IllegalStateException`、`UnsupportedOperationException`、带 cause 的 `IllegalArgumentException` 等）**一律不得逸出**：它们不是「平台的回答」，而是运行时内部的策略/容量失败。让它们进入客体主线程，等价于**运行时替应用决定生死**，而且现场与应用自身崩溃完全无法区分——这正是 BOSS直聘事件里差点被误记成「对方风控」的东西。

## 2. 为什么必须做成不变量

第一性原理上看，门面是**两个异常契约的交界**：客体按平台的契约写 `try/catch`（它只准备接 `SecurityException`），运行时却在同一条调用栈上表达自己的政策。两者不对齐时，**任何**一处过严校验都能凭空制造一次应用崩溃，而排查者看到的只有 `am_crash` 与一堆应用自己的栈帧。

因此在门面边界上只有两个合法动作：**降级**（给出平台会给的最接近结果）与**丢弃**（保持存活但少一个功能）。抛异常不是选项，除非平台也这么抛。

## 3. 落地：旧行为 → 新行为 → 观测码

统一记录点 `dev.tripoder.runtime.RuntimeFacadeContract`（tag `TripoderFacade`，按码去重、每码最多两条、上限 64 码），日志形如 `BROADCAST_OPTIONS_IGNORED dropped`。

| 位置 | 旧行为（可打死客体） | 新行为（v55） | 观测码 |
|---|---|---|---|
| `RuntimeReceivers.send` → `prepare`/`dispatch` 失败 | `IllegalStateException: BROADCAST_PREPARATION_UNCONFIRMED` / `BROADCAST_SEND_UNCONFIRMED` 逸出 | 记录后丢弃（不投递） | `BROADCAST_DROPPED` |
| `prepare` 收到 `BroadcastOptions` | `UnsupportedOperationException: BROADCAST_OPTIONS_UNSUPPORTED` | 忽略 options，照常投递 | `BROADCAST_OPTIONS_IGNORED` |
| 载荷含无法成字节的 Binder extra | `UnsupportedOperationException: BROADCAST_BINDER_PAYLOAD_UNSUPPORTED` | 剥 extras 后重编码投递 | `BROADCAST_BINDER_EXTRA_DROPPED` |
| 载荷超限 / URI 过长 | `IllegalArgumentException: BROADCAST_INTENT_TOO_LARGE` / `BROADCAST_URI_TOO_LONG` | 剥 extras（必要时再剥 data/type）后投递；仍失败才丢弃 | `BROADCAST_PAYLOAD_REDUCED` / `..._FAILED` |
| 系统（受保护）广播 action | `UnsupportedOperationException: SYSTEM_BROADCAST_POLICY_UNSUPPORTED` | 保留策略，但改为平台形状的 `SecurityException` | — |
| `sendBroadcastWithMultiplePermissions` 多权限 | `UnsupportedOperationException` | 收窄为第一个权限后投递 | `BROADCAST_MULTIPLE_PERMISSIONS_NARROWED` |
| `sendOrderedBroadcast(..., appOp, ...)` | `UnsupportedOperationException: BROADCAST_APPOP_UNSUPPORTED` | 忽略 appOp，照常有序投递 | `BROADCAST_APPOP_IGNORED` |
| `sendStickyBroadcast` prepare/dispatch 失败 | 异常逸出 | 记录后丢弃，不发布 sticky 视图 | `STICKY_BROADCAST_DROPPED` / `STICKY_QUERY_FAILED` |
| `registerReceiver` broker 失败 / 注册表满 / 重入 | `IllegalStateException: RECEIVER_REGISTER_FAILED` / `RECEIVER_REGISTRATION_LIMIT` / `..._IN_PROGRESS` | 惰性返回（注册不生效但进程存活） | 同名码 |
| `unregisterReceiver` 关闭确认失败 | `IllegalStateException: RECEIVER_UNREGISTER_UNCONFIRMED` | 记录后继续（不抛） | 同名码 |

保留不改的：`RECEIVER_LOADER_MISMATCH`（SecurityException，平台也这样拒绝）、`RECEIVER_NOT_REGISTERED`（平台对未注册接收者正是抛 `IllegalArgumentException`）、`RECEIVER_NOT_OWNED` / `RECEIVER_ACTIVATION_*`（发生在宿主广播分发线程，不落在客体调用栈上）。

## 4. 可执行契约（control-test）

`android/control-test/src/dev/tripoder/controltest/BroadcastResolutionIntegration.java` 新增 `facadeContract()`，把不变量写成断言（新增 9 条，总计 29 条）：

```
adb -s d861a4fa shell am instrument -w -r -e runId control-cd80726220173424 \
  dev.tripoder.controltest/dev.tripoder.controltest.BroadcastResolutionIntegration
```

```
INSTRUMENTATION_RESULT: checks=29
INSTRUMENTATION_RESULT: passed=true
INSTRUMENTATION_RESULT: resultFile=result-control-cd80726220173424.json
INSTRUMENTATION_RESULT: resultSha256=620a64eb7c1f61a6b59ae549889f1f5679c49013c1374f44d01b1f2125c7b69b
```

断言覆盖：`sanitize` 丢弃 clipData/selector/sourceBounds/identifier 并保留 receiver flags；嵌套 Binder 走 `encodeReduced` 被降级而非抛 `BinderPayload`；`platformAnswer` 仅对 `SecurityException` 返回 true（`IllegalStateException`/`UnsupportedOperationException` 均为 false）。

**这一步同时纠出一个错误假设**：`selector` 广播**平台自己就会拒绝**（首轮断言写的「不应抛」当场失败）。结论是 selector 从来不是「平台接受而运行时拒绝」的形状，契约里不应把它当作需要放行的形状——不变量针对的是**平台接受的形状**。

## 5. 端到端回归（v55，同一台真机）

`build-facade-v55`（`tripoder-q6.apk` sha256 `12172fc4…`，含本不变量）安装后重跑 BOSS直聘一键登录全链路（退出登录 → 手机号页 → 一键登录 → 天翼认证页 → 一键登录）：

| 观测 | 结果 |
|---|---|
| 客体主进程 | 登录前后同为 pid `6548`（未重启），`:mms` `6813` 存活 |
| crash 缓冲 | `FATAL` 计数 **0** |
| 落点 | 「完善资料、快速找工作」登录态引导页 |
| `TripoderFacade` 降级日志 | **0 条**（快乐路径上没有任何静默丢弃——降级没有掩盖问题） |

## 6. 边界（未覆盖，别过度推断）

1. **本次只覆盖广播/接收器门面**。其他门面（Activity/Service 启动、PackageManager、ContentProvider 等）仍可能有同族抛出点；不变量是全局规范，但落地是逐门面推进的。
2. **降级有损且语义可辨**：`BroadcastOptions`（如临时白名单）、多权限合取、`REGISTERED_ONLY` 这类 flags 在本运行时**不参与路由**（只透传），因此「投递成功」不等于「平台语义完全一致」。
3. **受保护广播仍会以 `SecurityException` 打死未捕获它的应用**——这是平台行为，不是缺陷。
4. **`BROADCAST_DROPPED` 是静默丢弃**：应用存活但少一次投递。定位只能靠 `TripoderFacade` 日志；日志按码去重（每码最多两条），不要用它做计数。
5. 端到端只回归了 BOSS直聘一条链路（n=1），未覆盖投递简历、沟通、推送等业务分支。

## 7. 复现

```bash
# 构建（三件套：android-37.0 + build-tools 37.0.0 + NDK 30.0.16248370）
python3 android/scripts/build-workbench.py --build-dir android/product/build-facade-v55 \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.q6

# 安装（一律 --no-incremental）
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v55/tripoder-q6.apk
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v55/tripoder-control-test.apk

# 契约断言
adb -s d861a4fa shell am instrument -w -r -e runId control-<16 hex> \
  dev.tripoder.controltest/dev.tripoder.controltest.BroadcastResolutionIntegration
```

## 关联

- 触发事件与修复起点：[`../bosszhipin-container-login-20261113/README.md`](../bosszhipin-container-login-20261113/README.md)
- 规范条目：[`../../guest-compatibility.md`](../../guest-compatibility.md) §3 第 6 条
- 代码：`android/runtime/src/dev/tripoder/runtime/RuntimeFacadeContract.java`、`RuntimeReceivers.java`、`RuntimeBroadcastIntent.java`、`GuestContext.java`
