# 微博登录闭环（v66，2026-11-13 14:00 CST）

设备 `d861a4fa`，容器 `dev.tripoder.q6`，构建 `build-facade-v66`（sha256 `b584e4f3…`）。
本轮把微博从「登录链路被门面挡住」推进到**登录态闭环**，并在过程中定位到一个**新的失效族**
（无障碍 → 剪贴板 → 包名/uid 校验），它比登录本身更值得记，因为它会打断每一个批量推进流程。

## 1. 结果

- 登录方式：**短信验证码登录**（容器内 `com.sina.weibo/…account.login.LoginActivity`，`fl_login_root`）。
- 手机号：`153 2373 8553`（真机 SIM，运营商认证掩码与京东/大众点评/微博三处一致）。
- 验证码：**由运行时侧直接读机内短信**取得（`content query --uri content://sms/inbox --sort "date DESC"`），
  该号码收到的 `【微博】083952（微博登录验证码）` 在 12s 内到达并入表。
- 结果：点「登录」后直接进入内容页，再回「我」页得到**完整登录态**：
  关注 100 / 粉丝 1 / 访客 0、我的钱包、草稿箱、创作中心、消息角标 2、每日签到。
  该手机号未注册过微博，按页面提示「未注册手机号验证通过后将自动注册」，因此落地的是**新注册账号 `用户7169367851`**
  （无头像、无简介，属新号的默认形态；关注 100 是微博为新号预置的关注列表，不是我们点出来的）。

判定：**微博 = 可用（登录闭环）**。它不再是「登录链路被门面挡住」的应用。

## 2. 入口路径（关键，之前两轮都卡在这里）

首页与「我」页**都没有「登录」文案**（未登录时「我」页只显示 `用户7169367851 / 完善资料`，那是游客态的名字，
**不是**登录态）。真正的入口是：

```
首页 →「我」tab (1095,2470)
     → 右上齿轮 (1102,195)            # ImageView，无文本，只能按坐标
     → 设置页「账号管理」(180,396)
     → 「添加账号」(690,602)
     → LoginActivity / fl_login_root
```

登录页可用坐标（1208×2624）：手机号框 `(676,737)`、验证码框 `(600,873)`、`获取验证码` `(1028,873)`、
协议勾选 `(298,1099)`、`登录` `(603,1219)`、`使用当前手机号辅助注册` `(578,2350)`。

## 3. 弹出的「用户协议与隐私条款」是登录按钮的闸门

点「获取验证码」/「登录」前若未勾协议，微博弹一个**自定义对话框**（不是系统对话框）：

```
用户协议与隐私条款
为保障与你相关的合法权益，请阅读并同意用户协议、隐私条款
      [不同意]        [同意并登录]        # (808,1476)
```

点「同意并登录」= 勾选协议 + 继续原动作。之后协议框变为已勾选态，`获取验证码` 变成 `54秒后可重新获取`（倒计时正常）。

## 4. 新失效族：无障碍预取 → `TextView.canPaste` → `hasPrimaryClip` → `AppOpsManager.checkPackage`

### 现象

登录页一旦用 `adb shell uiautomator dump` 取树，微博**主线程立刻 FATAL**，UI 退回 Workbench。
同一进程在 4 次 dump 中重复崩溃（`13:52:43` `13:55:20` `14:01:29` …），这也是本轮前半段「应用点一下又回到桌面」的真正原因——
不是应用自己退出，是**我们驱动它的方式把它杀了**。

### 完整因果链（栈原文，`logcat -b crash`）

```
java.lang.SecurityException: Package com.sina.weibo does not belong to 10542
    at android.content.IClipboard$Stub$Proxy.hasPrimaryClip(IClipboard.java:551)
    at android.content.ClipboardManager.hasPrimaryClip(ClipboardManager.java:328)
    at android.widget.TextView.canPaste(TextView.java:16100)
    at android.widget.TextView.onInitializeAccessibilityNodeInfoInternal(TextView.java:14641)
    at android.widget.EditText.onInitializeAccessibilityNodeInfoInternal(EditText.java:347)
    at android.view.View.createAccessibilityNodeInfo(View.java:9863)
    at android.view.AccessibilityInteractionController$AccessibilityNodePrefetcher.prefetchDescendantsOfRealNode(…:1589/1612)
    at android.view.AccessibilityInteractionController.findAccessibilityNodeInfoByAccessibilityIdUiThread(…:411)
    at android.view.AccessibilityInteractionController$PrivateHandler.handleMessage(…:1782)
    at android.app.ActivityThread.main(ActivityThread.java:10847)
Caused by: android.os.RemoteException: Remote stack trace:
    at android.app.AppOpsManager.checkPackage(AppOpsManager.java:10794)
    at com.android.server.clipboard.ClipboardService.clipboardAccessAllowed(ClipboardService.java:1512)
    at com.android.server.clipboard.ClipboardService$ClipboardImpl.hasPrimaryClip(ClipboardService.java:796)
```

读法：

1. 触发者是**平台的**无障碍子系统（`uiautomator dump` 走 `AccessibilityInteractionController`），
   它逐个节点调 `createAccessibilityNodeInfo`；
2. 平台自己的 `TextView.onInitializeAccessibilityNodeInfoInternal` 会调 `canPaste()` 来决定是否暴露「粘贴」动作，
   而 `canPaste()` 的实现就是 `ClipboardManager.hasPrimaryClip()`；
3. 该调用带着**客体自称的包名** `com.sina.weibo` 进 `system_server`，而 binder 侧 uid 是内核盖章的 **10542（宿主）**；
4. `ClipboardService.clipboardAccessAllowed` 第一件事是 `AppOpsManager.checkPackage(uid, package)`，于是抛
   `SecurityException: Package com.sina.weibo does not belong to 10542`；
5. 这个 SecurityException 在**客体主线程**的无障碍消息里逸出，无人 catch ⇒ FATAL。

### 它意味着什么

- 这是 C1（客体跑宿主 uid 却自称 guest 包名）在**又一个 AOSP 服务**上被强制执行，落点与网易云音乐的
  `IStorageManager.getVolumeList` 同族（h33），但**触发条件不同**：存储那次是应用启动路径必经；
  剪贴板这次是 `TextView` + 任意无障碍遍历才触发。
- 后果不只是「我们没法 dump」：**任何一个读屏/无障碍服务**（TalkBack、自动化测试、车机/手表投屏）打开这个应用都会把它打崩。
  也就是说这条不是工具问题，是容器可观测性 + 可访问性的结构性缺陷。
- 反向约束：本轮之后**禁止对容器内带 EditText 的页面做 `uiautomator dump`**；改用 `screencap` 看像素 + `input tap` 按坐标，
  只有 Workbench（我们自己的常规 View 应用）可以照旧 dump。

### 可行的修法（尚未实施，下一轮的候选）

按「门面在平台不失败处不失败」的同一条不变量，剪贴板应当**门面化**而不是放行到 `system_server`：

- 在客体侧拦截 `android.content.ClipboardManager`（以及 `ClipboardManager` 拿到的 `IClipboard` endpoint），
  在容器内维护**每个 space 私有的剪贴板**；
- `hasPrimaryClip()` / `getPrimaryClip()` 用容器私有内容回答，`setPrimaryClip()` 写入容器私有内容；
- 语义上这比现状更正确：容器与真机共享系统剪贴板本来就是跨应用数据泄漏（在容器里复制的密码会出现在真机剪贴板，
  反过来真机剪贴板内容也会漏进客体）；
- 判定口径：修好后重新对微博登录页做 `uiautomator dump`，`crash` 缓冲应保持 0，且 dump 能返回 `fl_login_root` 子树。

## 5. 操作台账（可复现）

| # | 动作 | 观察 |
|---|---|---|
| 1 | `am force-stop dev.tripoder.q6/.q7` → 重开 Workbench → 连点两次「重新连接」 | 清空槽位与活动台账（否则见 §6） |
| 2 | Workbench 列表点「微博」 | `topResumedActivity=TaskP1$F0M2C2`，首页 feed |
| 3 | `我`(1095,2470) → 齿轮(1102,195) → `账号管理`(180,396) → `添加账号`(690,602) | 到 `LoginActivity`，`fl_login_root`（**用 screencap 校验，不 dump**） |
| 4 | 手机号框(676,737) → `input text` 分三段 `153`/`2373`/`8553` | 截图确认 `153 2373 8553`（一次长串只会写入 `153`，见 §6） |
| 5 | 点 `获取验证码`(1028,873) → 弹协议框 → `同意并登录`(808,1476) | 收到 `【微博】083952`；按钮变 `54秒后可重新获取` |
| 6 | 验证码框(600,873) → `input text 083952` → `登录`(603,1219) | 直接进内容页；回「我」页为完整登录态 |

## 6. 顺手确认的两个交互事实

- **`input text` 长串会截断**：一次 `input text 15323738553` 只写进 `153`（焦点/输入法吞字符）。
  可靠写法是 3–4 位一段、每段后校验。
- **启动一个「已有未确认 Activity 记录」的应用会被拒**：Workbench 弹
  「应用没有启动 / 操作没有完成（`APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED`）」。
  这是 UI 拒绘（不是崩溃、无 FATAL），绕法是 §5 第 1 步的强停 + 重连清台账。
- 顺带看到 Workbench 自己的评分弹窗（`给我们评分` / `不了，谢谢`），与容器无关，点「不了，谢谢」即可。

## 7. 边界（不要外推）

- 本轮只证明**短信验证码登录**闭环；未测：密码登录、扫码登录、第三方（微信/QQ）授权登录。
- 新注册账号是**空号**（0 微博、100 预置关注），未做发博/关注/私信等写操作，也未验证登录态的持久性
  （重启容器后是否需要重新登录：未测）。
- §4 的崩溃族本轮**只观测、未修**；剪贴板门面化仍在候选清单里，判据已写在 §4 末。
