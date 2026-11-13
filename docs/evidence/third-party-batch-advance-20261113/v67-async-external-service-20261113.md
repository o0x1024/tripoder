# v67：异步（oneway）外部服务请求不再抛异常 —— 快手「同意条款后必崩」的根因与修复

设备 `d861a4fa`。本轮从 `build-facade-v66` 走到 `build-facade-v67`。

## 1. 现象

v66 上，快手进入「欢迎使用快手」隐私页，点「同意并继续」后**客体主线程/工作线程 FATAL**，UI 退回 Workbench：

```
FATAL EXCEPTION: AppOpsManagerNoteOpBatching
Process: com.smile.gifmaker, PID: 25213
java.lang.IllegalArgumentException: EXTERNAL_SERVICE_REQUEST_MUST_BE_SYNCHRONOUS
  at dev.tripoder.runtime.RuntimeExternalServices.lambda$install$0(RuntimeExternalServices.java:38)
  at dev.tripoder.runtime.RuntimeExternalServices$0.invoke(D8$$SyntheticClass:0)
  at dev.tripoder.runtime.RuntimeManagedInstaller.lambda$install$2(RuntimeManagedInstaller.java:57)
  at dev.tripoder.selfhook.ManagedHook.execute(ManagedHook.java:324)
  at dev.tripoder.selfhook.generated.Entry218.invoke(...)
  at com.android.internal.app.IAppOpsService$Stub$Proxy.noteOperationsInBatch(IAppOpsService.java:2594)
  at android.app.AppOpsManager.lambda$batchDuplicateNoteOps$5(AppOpsManager.java:10422)
  at android.app.AppOpsManager$$ExternalSyntheticLambda5.run(AppOpsManager.java:0)
  at android.os.Handler.handleCallback / dispatchMessage / Looper.loop
  at android.os.HandlerThread.run(HandlerThread.java:139)
```

要点：

1. 抛出点在**我们自己**的 `RuntimeExternalServices` 的 transact 拦截器里，不是应用、不是风控。
2. 触发者是 `AppOpsManager` 的**批量 note 线程**：平台自己建了一条 `HandlerThread`，把
   `IAppOpsService.noteOperationsInBatch` 放到后台线程上发——**异步（oneway）binder 调用**。
3. 我们的门面对 `flags != 0` 的 transact 直接 `throw new IllegalArgumentException(...)`。
4. 这个异常在 `Handler` 回调里逸出 ⇒ 整个进程 FATAL。

## 2. 判据

这是同一个不变量的第 N 个实例（h27/h30/h31 一族）：

> **门面在平台不会失败的地方，一个异常都不能给。**

平台对 oneway transact 的语义是「发出去就不等结果」，**从不校验调用线程、从不因为异步而失败**。
我们把「我只支持同步、需要回写 Parcel」当成平台的规则抛了出去，于是把一个平台允许的调用变成本地崩溃。
而且这个调用点客体没有任何 catch，也没有办法 catch（它发生在平台自己的 Handler 线程里）。

## 3. 修法（只改一处）

`android/runtime/src/dev/tripoder/runtime/RuntimeExternalServices.java`

```java
// before
if((Integer)args[3]!=0)throw new IllegalArgumentException("EXTERNAL_SERVICE_REQUEST_MUST_BE_SYNCHRONOUS");

// after：异步请求原样转发 + 记一次降级，绝不抛
if((Integer)args[3]!=0){
    if(owner.externalServices.asyncObserved.compareAndSet(false,true))
        android.util.Log.i("TripoderFacade","EXTERNAL_SERVICE_REQUEST_ASYNC_PASSED:interface="+contract+":transaction="+code);
    return original.call(args);
}
```

- 新增 `final AtomicBoolean asyncObserved`（每进程只报一次，避免日志风暴）。
- 语义选择上「原样转发」而不是「先转换再转发」：身份重写只是为了哄 `system_server` 的包名/uid 校验，
  而 oneway 调用拿不到回包，转发失败也无从观测；把它放过去最接近平台行为，且可观测（有降级码）。

## 4. 验证（端到端）

```
BUILD  exit=0  tripoder-q6.apk 2,323,699 B  sha256 055593c5…
INSTALL --no-incremental   Success
logcat -c -b crash
force-stop q6/q7 → Workbench → 两击「重新连接」→ 点「快手」→ 同意并继续
```

结果：

| 判据 | 观测 |
|---|---|
| 客体是否存活 | `topResumedActivity=TaskP3$F0M1C2`（快手前台，未退回 Workbench） |
| crash 缓冲 | `grep -c smile` = **0** |
| 降级码 | `EXTERNAL_SERVICE_REQUEST_ASYNC_PASSED` 命中 **1** 次（该路径确被执行，且没有抛） |
| UI | 快手 feed 正常（点赞 30.1万 / 评论 2.5万 / 分享 / 全屏观看；左上「登录」胶囊 + 「点击登录」徽标） |

⇒ v67 修好了「异步外部服务请求」这条出口。快手从「同意条款后必崩」变成 **feed 可用（未登录）**。

## 5. 边界

- 只验证了 `IAppOpsService.noteOperationsInBatch` 这一条 oneway 路径。其它异步外部服务
  （`IMountService`、`IPackageManager` 的 oneway、`IAlarmManager` 批量）**未逐一验证**；
  但它们共用同一个拦截器，判据相同。
- 快手**登录**仍未完成：feed 上的「登录」胶囊与「点击登录」徽标两次点击都无反应（页面未变化），
  入口需要在 `HomeActivity` 的树上定位（该页无 EditText，可以安全 dump）。
- 本轮只改了 runtime 一行 + 一个字段，未跑 control-test 回归套件；下一轮应补
  `BroadcastResolutionIntegration` + 与外部服务相关的断言。

## 6. 平安证券（`com.hundsun.winner.pazq`）：客体原生层 SIGSEGV，未启动

同一轮（v67、空槽位、单应用）启动平安证券，`mCurrentFocus` 始终留在 Workbench，UI 弹
「应用没有启动 / `android.os.DeadObjectException:null`」，`TripoderSupervisor` 报
`ProcessSupervisor.start failed for default/com.hundsun.winner.pazq/com.hundsun.winner.pazq phase=APPLICATION`。
crash 缓冲里是**原生崩溃**，不是 Java FATAL：

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000016 (write)
Cause: null pointer dereference
Cmdline: com.hundsun.winner.pazq   pid: 8279  uid: 10542
#00 pc 00000000006fe3f0  /apex/com.android.art/lib64/libart.so
    (art::JNI<false>::SetObjectField(_JNIEnv*, _jobject*, _jfieldID*, _jobject*)+228)
#01 pc 000000000015a014  <anonymous:79c0200000>
```

判据要点：

1. **栈里没有一帧 `dev.tripoder.runtime.*`**，也没有 appops / `callingPackage does not match UID` 这类
   系统服务校验痕迹——与本轮 §1 的快手崩溃（我方一行断言）形态完全不同。
2. `#01 <anonymous:...>` 是 JIT 生成的客体代码，`#00` 是它调进 libart 的 JNI 写字段；
   `fault addr 0x16` 说明 `_jobject`/`_jfieldID` 有一个是坏值（空指针 + 0x16 偏移）。
   属于**客体自己缓存了 jfieldID/jobject 并写穿**这一类错误。
3. 崩溃前 Provider 全部初始化成功（`KHSdkProvider`/`SkyEyeContentProvider`/`MFileProvider`/
   `SkyEyeContentProvider{data,wanfu}`），说明容器装载、包解析、provider 投影都通了，
   失败点在应用自己的启动逻辑里。

⇒ 归为**客体自身问题**，不是门面缺陷。待办（未做）：与「容器 hook 是否干扰了 jobject/jfieldID 缓存」
做区分实验——最小做法是同一 APK 在真机原生环境启动一次，若同样崩即证实为应用自身缺陷。

## 7. 王者荣耀（`com.tencent.tmgp.sgame`）：可用，直接到开区页

- `mCurrentFocus=com.tencent.tmgp.sgame/SGameActivity`，进程两个：`com.tencent.tmgp.sgame` +
  `com.tencent.tmgp.sgame:xg_vip_service`（uid 10542）。
- UI 已渲染到登录/开区页：`王者荣耀` logo、`退出`(右上)、`离线5V5`、`?`、喇叭、
  账号条 **`澎湃 微信440区 火鹰船长` + `换区`**、`开始游戏 START`、`上传日志`、`12+ CADPA 适龄提示`。
- crash 缓冲本轮为空（`grep -c FATAL` = 0）。

关键意义：这是**游戏引擎（UE/Unity 类）+ 原生 so 密集**的应用，且账号上下文（微信 440 区）已从本地会话恢复，
说明容器对「大体积原生库 + 图形渲染 + 本地账号态」这条链路是可用的——比前几批的工具类/电商类应用更能说明承载力。

## 8. 一条新的 dump 失败条件（操作口径补充）

快手 feed 页（`HomeActivity`，无 `EditText`）执行 `uiautomator dump` 返回：

```
ERROR: could not get idle state.
```

原因是短视频 feed 持续动画，无障碍等待「窗口静止」永不满足。**这与剪贴板族是两个不同的失败条件**：

| 页面特征 | dump 结果 | 客体 |
|---|---|---|
| 有 `EditText`（微博登录页、拼多多手机号页） | 触发剪贴板 `checkPackage` | **FATAL 被杀** |
| 无 `EditText` 但持续动画（快手 feed） | `could not get idle state.` | 存活，只是拿不到 XML |
| 无 `EditText` 且静止（Workbench、拼多多登录页） | 正常返回 | 存活 |

⇒ 操作口径再收紧：dump 前先看页面有没有输入框（有就不 dump）；dump 报 idle 失败时不要重试，
直接改 `screencap` + 坐标驱动。

## 9. 微信（`com.tencent.mm`）：能启动、能到手机号登录页，但登录闭环在账号侧

同一构建（v67），空槽位单应用：

| 阶段 | 观测 |
|---|---|
| 启动 | `WelcomeActivity`（地球页 + 底部「登录」/「注册」+ 右上「语言」） |
| 点「登录」(227,2288) | `MobileInputUI`，手机号登录页 |
| 填手机号 | 手机号框 (547,769)，分段写入 `153/2373/8553` 成功显示 `153 2373 8553` |
| 点「同意并继续」(604,2143) | 页面切到**密码模式**：手机号回填、出现「密码」框 + 「用短信验证码登录」链接 |
| 点「用短信验证码登录」 | **无响应**（3 次：tap、长按 swipe、点掉 autofill 弹窗后重试），页面不变 |
| 点「更多」(711,2294) | **无响应** |

判据：

1. 该页的 **EditText 与绿色大按钮都正常**（输入生效、页面推进），**小尺寸文字链接不响应**；
   同一时刻 logcat 15:47–15:50 区间**没有一条 `TripoderFacade` 降级日志、没有客体异常**，
   说明不是我们的门面拦下了什么，而是点击本身没有产生可见效果（或在应用内部被忽略）。
2. 与快手 feed 的「登录」胶囊/「点击登录」徽标是同一类现象（大控件响应、自绘小控件不响应），
   已作为待查族记入 inventory，不影响「应用可用」的判定。
3. 微信真正的门槛在**账号侧**：容器内没有已登录的微信号，短信验证码登录在新设备上还要「好友辅助验证」，
   密码未知。⇒ 微信登录闭环不属容器可推进范围，交回用户（需密码或已登录设备扫码）。

## 10. 微信/QQ 双进程存活

`ps` 可见 `com.tencent.mm`、`com.tencent.mm:push`、`com.tencent.mm:tools`、`com.tencent.mobileqq`、
`com.tencent.mobileqq:MSF`、`com.tencent.soter.soterserver` 全部存活，无 FATAL。
⇒ 「大型社交应用 + 多进程 + 原生 so」这条链路在 v67 上是通的，失败点只在登录凭据。
