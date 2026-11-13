# 第 13 批：宽回归通过 + 闲鱼从「起不来」到「登录页黑屏」（v86 → v87）

日期：2026-11-13
前置：`batch12-admission-fence-fix-20261113.md`（栅栏分层证明）

## 1. 宽回归：放宽的栅栏没有放出不该放的东西

v86 上对 4 个应用各做一次完整冷启（force-stop q6/q7 → Workbench → 两次「重新连接」→ 点应用 → 30s）：

| 应用 | top | admission-blocked | FATAL | 客体进程 |
|---|---|---|---|---|
| 淘宝 | `TaskP0$F0M1C0` | 0 | 0 | 3 |
| 携程旅行 | `TaskP0$F0M2C2` | 0 | 0 | 3 |
| 小红书 | `TaskP0$F0M1C2` | 0 | 0 | 3 |
| 微博 | `TaskP0$F0M2C2` | 0 | 0 | 3 |

4/4 落到客体页面、零栅栏拒绝、零崩溃。`gap-fence-regression-sweep` 关闭。

## 2. 闲鱼：先更正一条旧结论

闲鱼**有**隐私同意层（此前批次的推断是错的）：

- 首屏「温馨提示」+《隐私权政策》正文 + `同意` / `不同意`，无 EditText、无 WebView（原生页）；
- 点「同意」后进入「欢迎使用快捷登录」。

## 3. 崩溃链：我方在平台不会失败处抛了异常

点「同意」的瞬间客体进程 FATAL：

```
java.lang.IllegalStateException: USER_FRAMEWORK_MEMBERS_UNAVAILABLE
  at UserFrameworkAccess.prepare(UserFrameworkAccess.java:18)
  at UserFrameworkAccess.facade(UserFrameworkAccess.java:21)
  at RuntimeUsers.facade(RuntimeUsers.java:12)
  at GuestContext.getSystemService(GuestContext.java:119)
  at com.taobao.idlefish.TaoBaoApplication.getSystemService
  at org.chromium.components.embedder_support.application.a.getSystemService
  at org.chromium.components.policy.c.<init>
  at org.chromium.android_webview.policy.a.<init>
  at org.chromium.android_webview.AwBrowserProcess.a
Caused by: FIXED_MEMBER_REGISTRY_INCOMPLETE:64:{stage:READY,last:FIELD_REFLECT,ready:true,
  lastMismatch:[4,"182d310","183c588"],declaringRootsRead:8050,...}
```

三件事同时成立：

1. **调用点在客体主线程，且不 catch** —— Chromium/WebView 初始化（`AwBrowserProcess`）在应用自己的 `getSystemService` 上取 `UserManager`；
2. **我们在这里抛了** —— `RuntimeUsers.facade()` 需要 owner=64（`UserManager(Context, IUserManager)` 构造器）的固定成员，而本机 ROM 上该条目摘要不匹配（声明 `182d310` vs 实测 `183c588`），`prepare()` 直接抛 ISE；
3. **平台在这里不抛** —— `Context.getSystemService` 的合法答复只有 `Manager` 或 `null`。

这与既定判据一致（门面必须在平台不失败处不失败），属于「我们的承载力缺陷」，不是闲鱼兼容性问题。

## 4. 修法与 A/B

`GuestContext.getSystemService` 改为：先走原分派，任一门面抛 `RuntimeException` 就
`RuntimeFacadeContract.drop("SYSTEM_SERVICE_UNAVAILABLE:"+name, e)` 并回落到 `platform.getSystemService(name)`。
语义上这是「按平台答复降级」，不是吞异常——降级一定会留痕。

v87 实测（同一路径：清槽位 → 两次重连 → 滚动定位闲鱼 → 冷启 → 同意隐私）：

| 指标 | v86 | v87 |
|---|---|---|
| 点同意后的 top | 进程崩溃 | `TaskP0$F0M2C3`「欢迎使用快捷登录」 |
| FATAL | 1（USER_FRAMEWORK_MEMBERS_UNAVAILABLE） | **0** |
| `USER_FRAMEWORK_MEMBERS_UNAVAILABLE` 行 | 出现在 crash 缓冲 | 0 次 |
| 日志形态 | 异常穿透到 FATAL | `TripoderFacade: SYSTEM_SERVICE_UNAVAILABLE:user dropped cause=…USER_FRAMEWORK_MEMBERS_UNAVAILABLE` |
| admission-blocked | 0 | 0 |

即：同一个异常仍然发生，但它不再杀进程，被记成一次可观测的降级。

## 5. 新阻塞：登录页是 U4(UC) 内核 H5，画面全黑

「欢迎使用快捷登录」页 dump 只有 1 个文本节点，截图两帧（间隔 12s）都是**纯黑**：仅左上应用图标、右上「100」红包角标、底部一条加载进度条，文件大小 23272 / 23287 B（几乎不变 ⇒ 不是在继续绘制）。

日志证据（`logcat -d | grep -i chromium`）：

```
BindService doBindService, intent: Intent { cmp=com.taobao.idlefish/.uc.proc.GpuProcessService0 }
  extras={..., org.chromium.base.process_launcher.use.exported_service=true,
          org.chromium.base.process_launcher.child_service_params.library_process_type=4,
          info.core.libs=[webviewuc], lib.path=…/app_u4sdk/dlibs/_386240697/lib/arm64-v8a}
Service onBind - intent: Intent { cmp=com.taobao.idlefish/.uc.proc.NormalRenderProcessService0 }
ipoder.q6:slot4: No implementation found for void org.chromium.base.memory.DlmallocBlackBerryRepairor
  .nativeSetEnabledMremap(boolean) … - is the library loaded, e.g. System.loadLibrary?
```

由此可知：

- 闲鱼自带 **U4（UC）WebView 内核**，通过绑定**自己包内的 exported service**（`GpuProcessService0` / `NormalRenderProcessService0`）拉起 GPU 与渲染子进程；
- 我们的运行时把这类自绑定服务按槽位起了新进程（`dev.tripoder.q6:slot4`），渲染进程确实起来了；
- 但渲染侧出现 native 方法缺失（`DlmallocBlackBerryRepairor.nativeSetEnabledMremap`），画面没有内容 ⇒ 黑屏。

**未定论**：是「U4 的 GPU 子进程在我们的多进程模型下拿不到正确的 binder/共享内存」还是「U4 内核依赖的 native 库在容器里没被完整映射」，本轮没有证据区分。这决定了修法在门面层还是在库加载层。

### 5.1 追加排查：不是「还在加载」，而是「渲染出来了但不合成」

再等 25s 后的第三帧仍是 23288 B（三帧 23272 → 23287 → 23288），且 U4 的子进程全部健在：

```
17053 com.taobao.idlefish
18086 com.taobao.idlefish:channel
18642 com.taobao.idlefish:windowChannel
18938 com.taobao.idlefish:normal_render_proc0
19003 com.taobao.idlefish:gpu_process
```

`uc.proc` 相关 24 行日志里**没有** fail/denied/exception/died（绑定成功），全量 logcat 也没有该客体的 `net::ERR_*`。唯一异常是三条 `DlmallocBlackBerryRepairor.nativeSetEnabledMremap` 缺失（Chromium 的堆修复钩子，属软错误）。

因此「库没加载」与「网络失败」都被排除，指向**窗口/合成面**：日志里两次出现

```
E u4java_WindowAndroid: SetPreferredRefreshRateAsStartRefreshRate failed,
  mDisplayAndroid=org.chromium.ui.display.e, window=null, context=…embedder_support.application.a
```

`window=null` 说明 U4 的 `WindowAndroid` 没拿到任何窗口句柄 ⇒ 有 surface 容器（顶部栏与加载条是原生 View）但 H5 无法附着合成面 ⇒ 只剩黑底。下一步要查的是：我们交给客体的 `Window`/`WindowManager`/`Display` 投影是否让 U4 的 `WindowAndroid` 建不出窗口（这是第四条可复用的候选：**H5 内核类应用的黑屏先看 `window=null`**）。

## 6. 边界

- 宽回归只覆盖 4 个应用、各 1 次冷启；闲鱼只覆 1 次同意路径。
- `getSystemService` 的降级是**兜底**，不是修好 owner=64：`UserManager` 门面在本机构建上仍然不可用，任何真正依赖它的客体路径都会看到平台 manager（含宿主/真机的用户级限制），不是容器化的答复。要真正修，需重算 fixed-members 摘要（生成管线），这是一个独立任务。
- 黑屏只观测到「不绘制」，**没有**验证该页在原生环境下是否正常、也没有拿到 U4 渲染失败的具体返回码。
- 未测：黑屏页面是否仍可 blind-tap 触发登录（WebView 内容未渲染，通常无命中区）。

## 7. 复现

```
python3 /tmp/regress4.py     # 宽回归（v86）
python3 /tmp/xy88.py         # 闲鱼：清槽位+两次重连+滚动定位→冷启→同意→读 top/crash/facade
adb shell 'screencap -p /data/local/tmp/s.png'; adb pull /data/local/tmp/s.png /tmp/s.png
```
