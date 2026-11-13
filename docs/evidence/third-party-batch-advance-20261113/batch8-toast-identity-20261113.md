# 第 8 批（续六）：Toast 面自持的最小实验——普遍性成立，两个缓存替换点都不成立

上文（`batch8-slot-capacity-leak-20261113.md`）把哔哩哔哩的崩溃定性到 Toast 撞系统服务身份校验。本文回答两个问题：

1. **这是不是普遍现象**（不只是 bili 自己写崩）？——**是**。
2. **容器能不能就地把它答掉**（自持这一面）？——**这一轮的两个改造点都不成立**。

## 1. 实验装置

新增 `RuntimeToastProbe`（`android/runtime/src/dev/tripoder/runtime/`），挂在 `RuntimeSelfHookApplication.publishApplication()` 之后——那个位置的执行者就是客体进程自身（`pid` 与 `ps` 一致），所以测到的是客体真实的 Toast 行为。探针按顺序做三件事，**每一步都 catch**，因此它永远不会自己把客体弄崩：

1. 用客体自己的 `getApplicationContext()` 发一条 `Toast` → 记 `PHYSICAL=OK/THREW`；
2. 反射替换缓存的 `INotificationManager` 为 Proxy（`enqueueToast` 由自己答，其余全部委托物理服务），记 `SWAP=OK/…`；
3. 再发一条 `Toast` → 记 `FACADE=OK/THREW`。

## 2. 结果一：普遍性成立（v80）

在哔哩哔哩客体进程（pid 5380）里：

```
W TripoderToastProbe: PHYSICAL=THREW java.lang.SecurityException: Package tv.danmaku.bili is not owned by uid 10542
I TripoderToastProbe: SWAP=OK interface=android.app.INotificationManager
W TripoderToastProbe: FACADE=THREW java.lang.SecurityException: Package tv.danmaku.bili is not owned by uid 10542
```

**这条异常与 bili 的代码无关**：同样的消息在我们自己写的两行 `Toast.makeText(...).show()` 上原样出现。bili 只是没有 catch 它。

## 3. 结果二：`NotificationManager.sService` 不是被用的那一个（v81）

v80 换的是 `android.app.NotificationManager.sService`：写入成功（`SWAP=OK`），但 Toast 照样抛。所以 v81 改成把**两个类**的静态缓存都找出来换掉，并逐个打印候选字段：

```
CANDIDATE android.widget.Toast.sService:android.app.INotificationManager
SWAP=OK   android.widget.Toast.sService interface=android.app.INotificationManager
CANDIDATE android.app.NotificationManager.sService:android.app.INotificationManager
SWAP=OK   android.app.NotificationManager.sService interface=android.app.INotificationManager
FACADE=THREW java.lang.SecurityException: Package tv.danmaku.bili is not owned by uid 10542
```

**两个静态字段都换成了我们的 Proxy，Toast 仍然打到了真实系统服务**——判据是：Proxy 的 `enqueueToast` 分支会打印 `ANSWERED=…`，而日志里一行都没有。这说明这个 ROM（MIUI / Android 16）上 `Toast.show()` 取 `INotificationManager` 的路径既不是 `Toast.sService` 也不是 `NotificationManager.sService`（可能是 MIUI 自己的 Toast 实现，或实例级/内联缓存）。

## 4. 这轮否掉了什么（避免下次重走）

- 「把 `NotificationManager.sService` 换成 Proxy 就能救 Toast」——**否**。
- 「Toast 用自己的 `sService`，换它就够」——**否**。
- 「先换接口再发 Toast 是最小代价的修法」——**否**，代价最低的修法不在这一层。

## 5. 边界与下一步的两条路

- 本轮**没有**让哔哩哔哩的同意流程跑通（探针只证明了机制，没有回到业务路径做 A/B）。
- 探针默认关闭（`PROBE=false`，与 `MAPS_PROBE` 同例），开启即复现本节全部日志；v80/v81 是实验构建，不进基线。
- 剩下的两条路，代价差别很大：
  1. **让系统服务调用携带「真正持有这个 uid 的包名」**（`GuestContext.getOpPackageName()` 从客体包名改为宿主包名）。一行改动，`checkCallerIsSameApp` 自然通过；代价是客体自报的 `opPackageName ≠ getPackageName()`，会改变所有走该名字做归属校验的调用（AppOps/通知/Toast），需要一轮回归。
  2. **在客体字节码层 hook `Toast.show()`**（容器自绘）。语义最干净，但需要新增 owner `android.widget.Toast` 到 `FixedRuntimeHooks.OWNERS/HASHES`（钉死摘要），要过生成管线，不是一行改动。
- 另外记一个工程侧事实（本轮踩到两次）：`build-workbench.py` 默认产物包名是 `dev.tripoder.engine`，装到宿主必须显式 `--package dev.tripoder.q6`；且本机 MIUI 会起 `com.miui.securitycenter/AdbInstallActivity` 确认页，12 秒不点就 `INSTALL_FAILED_USER_RESTRICTED`——必须轮询 `topResumedActivity` 并点「继续安装」(355,2381)。
