# 第 8 批（续四）：哔哩哔哩同意后崩溃的完整栈——根因是 Toast 撞上物理 NotificationManagerService 的包名↔uid 校验

上一轮只抓到 `FATAL EXCEPTION: main` 与 `Process: tv.danmaku.bili, PID: 28536` 两行，归属悬置。容量泄漏修好之后（见 `batch8-slot-capacity-leak-20261113.md`）同一个应用能真正起来了，完整栈一次拿到。

## 1. 完整栈（v79，`logcat -b crash -d`）

```
FATAL EXCEPTION: main
Process: tv.danmaku.bili, PID: 13325
java.lang.SecurityException: Package tv.danmaku.bili is not owned by uid 10542
    at android.os.Parcel.createExceptionOrNull(Parcel.java:3414)
    at android.os.Parcel.readException(Parcel.java:3323)
    at android.app.INotificationManager$Stub$Proxy.enqueueToast(INotificationManager.java:3935)
    at android.widget.Toast.show(Toast.java:222)
    at com.bilibili.droid.toast.BToastV3.show(BL:3)
    at com.bilibili.droid.ToastHelper.showToastSafely(BL:4)
    at com.bilibili.app.comm.list.common.widget.PromoToast.showTopToast(BL:15)
    at com.bilibili.pegasus.components.CommonConfigComponent$onViewCreated$2$a.emit(BL:42)
    at com.bilibili.pegasus.vm.PegasusViewModel.D(BL:2329)
    at com.bilibili.pegasus.vm.PegasusViewModel$dispatch$1.invokeSuspend(BL:13)
    Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException
Caused by: android.os.RemoteException: Remote stack trace:
    at com.android.server.notification.NotificationManagerService$Stub.enqueueToast(NotificationManagerService.java:4492)
    at com.android.server.notification.NotificationManagerService.checkCallerIsSameApp(NotificationManagerService.java:13577)
```

## 2. 归属判定：不是门面抛点，是系统服务侧的身份拒绝

按项目既有判据（`stack-attribution-rule-refined`：先看 crash 缓冲有没有 `dev.tripoder.runtime.*` 帧）：

- 栈里**没有**任何 `dev.tripoder.runtime.*` 帧。所以这不是我们某个门面方法直接抛出的异常。
- 抛出点是**真实系统进程** `system_server` 的 `NotificationManagerService.checkCallerIsSameApp`，理由是 `Package tv.danmaku.bili is not owned by uid 10542`。
- 中间帧全是 bilibili 自己的代码：`PromoToast.showTopToast → ToastHelper.showToastSafely → BToastV3.show → Toast.show`。

这同时意味着 **h41 的形态预测被推翻**：h41 预测完整栈里会出现 `dev.tripoder.runtime.*` 帧（即我们自己的门面抛点）。实际没有。

但 h41 的**实质判断仍然成立**：这次崩溃依然是容器造成的、且是容器该修的。理由不是「谁抛的」，而是「平台在真机上会不会失败」：

- 真机上客体的 uid 就是它自己包的 uid，`enqueueToast` 的 `checkCallerIsSameApp` 必然通过；
- 容器里客体跑宿主 uid 10542 而自称 `tv.danmaku.bili`，这条校验必然失败（同一族事实已在 `h14-c1-confirmed`、`h33-system-service-uid-package-check` 各记一次：存储服务、AppOps、现在是通知服务）。

所以 bilibili 在这里做的事情是**完全正常且防御过**的：它自己的包装方法就叫 `showToastSafely`，只是它防的是自己以为的风险，不会去防「系统告诉我这个包不属于我这个 uid」。

## 3. 为什么这条值得修，而不是记成「应用不兼容」

同一族已经出现三次（存储 `IStorageManager.getVolumeList`、AppOps `noteOperationsInBatch`、通知 `enqueueToast`），每次都由**系统服务**而不是应用来拒绝「包名 ↔ 运行 uid」不自洽。这类拒绝的特征是：

1. 平台异常类型是 `SecurityException`，应用不会 catch（真机上不可能发生）；
2. 落点在 `main` 线程的框架回调里（这里是协程 Main.immediate），必然升级为进程 FATAL；
3. 应用侧无法规避——它连 `Toast.show()` 都要被拦，这是最基础的 UI 反馈。

容器侧的可修方向明确：**Toast 面自持**。与已登记的剪贴板门面（`gap-clipboard-identity`）是同一形状——凡是「物理服务会校验调用方身份、而容器内身份必然不自洽」的 API，都应该在容器内回答（自己渲染 toast / 自己返回空 clip），不要让调用到达物理服务。这不需要改应用，也不需要更精确的身份投影。

## 4. 边界

- 本轮**只定性、未修复**：Toast 面还没做。下一轮应当先做「最小实验」验证这条通路的普遍性（见 `h42` 的预测），再决定实现形态。
- 这条不只影响哔哩哔哩：任何在容器内调用 `Toast.show()` 且未捕获 `SecurityException` 的应用都会崩在同一行。反过来说，本轮其它应用没崩，说明它们要么用自绘 toast，要么没走到这条路径——这**不能**当作「Toast 面没问题」的证据。
- 上一个 `PID 28536`（19:19）与本轮 `PID 13325`（19:31）是两次独立崩溃、同一异常；前者因为当时容量已耗尽而无法取栈，后者取到完整栈。两次现象一致，互为印证。
- 本轮 `+32s` 时 `topResumedActivity` 仍是 Workbench：崩溃发生在 Activity 前移之前（PegasusViewModel 的 dispatch 在 onViewCreated 之后立刻跑），所以「起来→崩→回 Workbench」与观测一致。这不改变归属判断，但说明**该应用在容器内目前不可用**。
