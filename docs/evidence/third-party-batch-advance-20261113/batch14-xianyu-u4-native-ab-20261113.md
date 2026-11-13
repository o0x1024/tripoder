# 第 14 批：闲鱼 U4 登录页黑屏的原生 A/B 判别（容器侧缺陷，非兼容性）

机时：2026-11-13 20:51–20:58　机型 d861a4fa　容器 build-fence-v87（已推 dc517b6e）

## 1. 结论摘要

| 命题 | 判定 | 依据 |
|---|---|---|
| 黑屏是「窗口/面没建起来」 | **否** | 客体登录页窗口在 SurfaceFlinger 有 `VRI-…HalfTransparentUserLoginActivity#29747`，`mHasSurface=true`、`isOnScreen=true`；原生层（应用图标、红包角标、底部加载条）正常合成 |
| 黑屏是 GPU/EGL/gralloc 资源失败 | **否** | 全量 logcat（7.4 MB）内零 EGL/gralloc/BufferQueue/SurfaceFlinger 错误；GPU 与 Render 子进程都只是 `ChildService normal process, disable seccomp-bpf` |
| 黑屏是 H5 没加载出来 | **否** | `chromium cookie_manager` 对 `http://mobilegw.alipay.com/` 写 cookie 被 Strict-Secure 拒绝的告警出现 4 次 ⇒ 页面确实加载并跑过 JS |
| 黑屏是客体窗口没有硬件加速 | **否** | `dumpsys gfxinfo` 有 `Graphics info for pid 17053 [dev.tripoder.q6:slot0]` 的帧统计段，宿主 pid 12923 为 `Pipeline=Skia (Vulkan)`，同一 HWUI 上下文 |
| 黑屏是闲鱼兼容性 / 风控 | **否** | 原生 A/B：同一台机、同一个 U4 内核、同一种层拓扑（只有 VRI 层、无 SurfaceView 子层）→ 原生正常渲染（见 §3） |
| 失败点 | **容器侧：U4 的 `WindowAndroid` 在容器内拿到 `window=null`** | 容器日志 `u4java_WindowAndroid: SetPreferredRefreshRateAsStartRefreshRate failed … window=null`；原生同一内核**不打印该行**（§4） |

## 2. 容器侧读数（v87，闲鱼停在黑屏页时）

```
# SurfaceFlinger --list
RequestedLayerState{…TaskP0$F0M2C3#29745}            ← ActivityRecord 层
RequestedLayerState{b967e14 com.taobao.idlefish/com.ali.user.mobile.login.ui.HalfTransparentUserLoginActivity#29746 parentId=29745}
RequestedLayerState{VRI-com.taobao.idlefish/…HalfTransparentUserLoginActivity#29747 parentId=29746}
# 无 SurfaceView[…]/SurfaceTex… 子层 ⇒ 内容由进程内绘制，不是独立 surface

# dumpsys window windows
Window{…HalfTransparentUserLoginActivity}: mHasSurface=true isReadyForDisplay()=true isOnScreen=true

# dumpsys gfxinfo（无参全量）
** Graphics info for pid 17053 [dev.tripoder.q6:slot0] **   ← 客体进程有 HWUI 帧统计
** Graphics info for pid 12923 [dev.tripoder.q6] ** Pipeline=Skia (Vulkan)
```

进程态势（`ps -A`）：`com.taobao.idlefish`(17053) / `:channel` / `:windowChannel` / `:normal_render_proc0`(18938) / `:gpu_process`(19003)，uid 均为 10542，零崩溃。

U4 唯一的自述异常：

```
E u4java_WindowAndroid: SetPreferredRefreshRateAsStartRefreshRate failed,
  mDisplayAndroid=org.chromium.ui.display.e@4d8f28b, window=null,
  context=org.chromium.components.embedder_support.application.a@4e2ac4d
```

时刻 T=20:51:33.114 / 20:51:37.257，紧跟 `WebViewChromiumAwInit: awBrowserStartCCB onReceiveValue. awbrowser async ok` 之后——即 AwContents 初始化期。

## 3. 原生 A/B（同一台机）

```
am start -n com.taobao.idlefish/com.taobao.idlefish.maincontainer.activity.MainActivity   # uid 10328
tap 我的 → tap 头像/昵称区 → top = com.taobao.idlefish/.webview.WebHybridActivity
```

结果：页面完整渲染（「我的鱼力值 / 400 / 专属权益 / 如何提升鱼力值」全量可见），`screencap` 1.28 MB（黑屏时同机同分辨率约 23 KB）。

层拓扑对比：

| | 容器 | 原生 |
|---|---|---|
| ActivityRecord 层 | 有 | 有 |
| 窗口层 | `b967e14 …HalfTransparentUserLoginActivity` | `35a59c0 …webview.WebHybridActivity` |
| VRI 层 | 有 `#29747` | 有 `#29795` |
| SurfaceView 子层 | 无 | 无 |
| 渲染结果 | 黑屏（仅原生控件可见） | 完整渲染 |

⇒ 层结构与渲染路径一致，唯一差别是渲染内容。

## 4. 判别：容器打印，原生不打印

原生方向在 U4 初始化时刻（20:57:16，同一 logcat 窗口内可见 `u4proc: AwContents onAttachedToProc(Render0/1/1)` 与 `u4java_AwContents: WebContents visibility changed: true <- false`）**没有任何 `u4java_WindowAndroid` 行**；容器方向在同类时刻打印两次 `window=null`。

`window=null` 的直接后果与现象一致：U4 的进程内合成器没有输出目标，因此不产出帧，而原生视图仍由框架 HWUI 正常绘制 ⇒ 「窗口里有内容但 H5 区域全黑 + 底部加载条还在动」。

> 更正上一批的口径：此前把 `window=null` 记为「合成面没建立」，容易被理解成「窗口/面缺失」。实测窗口与面都在，缺的是 **U4 自己去绑定窗口的那个引用**。

## 5. 下一步（未做）

1. **静态定位 window 来源**：从 `com.taobao.idlefish` base.apk 的 dex 里定位打印 `SetPreferredRefreshRateAsStartRefreshRate failed` 的方法，读出它从哪个 API 取 `window`（`Activity.getWindow()` / `View.getRootSurfaceControl()` / `SurfaceView.getHolder()` / 内联缓存字段），再对照容器在该 API 上的投影。
2. **客体探针**（更便宜，二选一）：在 `publishApplication` 自检点打印 `getWindowManager().getDefaultDisplay()`、当前 Activity 的 `getWindow()`、`getDecorView().getWindowToken()`、`ViewRootImpl` 是否 attach，与 `window=null` 的时刻对齐。
3. 修好后回归：闲鱼「欢迎使用快捷登录」应出现登录按钮；同一改动可能同时影响其它自带 U4/UC 内核的应用（淘宝系）。

## 6. 边界

- 原生 A/B 的对照页是 `WebHybridActivity`（鱼力值），**不是**同一个 `HalfTransparentUserLoginActivity` 登录半屏：该 Activity 未导出（`SecurityException: not exported from uid 10328`），无法用 `am start` 直达，原生侧也没走到登录页。
- 原生侧是未登录态（「昵称未填写」），本次未登录，未验证登录后的行为差异。
- 「原生不打印 `window=null`」的判定范围是 `logcat -t 4000/6000` 窗口，该窗口内已包含原生 U4 初始化与首个 H5 页面加载；更早的日志已滚出。
- 只覆盖 U4 内核 H5 一条路径；系统 WebView（`com.google.android.webview`，pid 17758 也在容器内跑着小红书/其它）本轮未做同类 A/B。
- 未验证「修复 WindowAndroid 绑定后页面是否可直接登录」（还可能有账号/风控层）。
