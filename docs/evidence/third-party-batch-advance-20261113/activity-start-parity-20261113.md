# 微博「凭空崩溃」第二族：Activity 起步门面（v64 / v65）

日期：2026-11-13
设备：d861a4fa；容器 `dev.tripoder.q6`；客体 `com.sina.weibo`

## 0. 这一轮在整条链上的位置

- 上一轮（v63）关掉的是**准入栅栏**：未 claim 的广播投递被误记为 UNKNOWN，把整个包作用域永久拒掉。
- 这一轮关掉的是**同一族的第二处**：Activity 起步门面在平台不会失败的地方抛异常，把「一次跳转」放大成「主进程 FATAL」。

两者共同的判据（h27）：**平台在该调用点不会抛，运行时一个异常都不能给**。修法不是改抛什么，而是让该失败不存在。

## 1. 判据 A：第一次崩溃锁到具体行

v63 装机后，微博首页可浏览、无栅栏。在个人页点「我的钱包」触发 scheme 跳转，主进程 FATAL：

```
java.lang.SecurityException: ACTIVITY_START_CALLER_UNOWNED
  at dev.tripoder.runtime.RuntimeSelfHookActivities.lambda$routing$0(RuntimeSelfHookActivities.java:204)
  ...
  at android.app.Activity.startActivityFromChild(Activity.java:7053)   ← execStartActivity(who=parent, target=child)
  at android.app.Activity.startActivityFromChild(Activity.java:7024)
  at android.app.Activity.startActivityForResult(Activity.java:6315)   ← mParent != null 分支
  ...
  at com.sina.weibo.BaseActivity.startActivity(BaseActivity.java:2108)
  at com.sina.weibo.utils.SchemeUtils.openWeiboScheme_old(SchemeUtils.java:800)
  at com.sina.weibo.router.core.RootUriHandler$RootUriCallback.onComplete(RootUriHandler.java:175)
```

因果链读出来是三段：

1. `ownsActivity` 的实现是 `activityInstances.containsKey(activity)`，而 `activityInstances` 是 `IdentityHashMap`，条目在 `activityDestroyed()` 里删除（`RuntimeEngine:99 / :655`）——所以「引擎没有记录」是唯一能触发这条拒绝的条件。
2. 现场的日志补齐了这条条件的内容：`class=com.sina.weibo.minev2.MineTabActivity;finishing=false;destroyed=false`——**不是**销毁后被回收，而是这个实例从来没被本引擎登记过。
3. 平台对同一次调用照做：`startActivityFromChild` 只是把 `child` 当 token 的来源，token 只影响结果记到哪个任务上。

## 2. 判据 B：改成 context 起步之后露出的第二处

把退役实例改成「按 context 起步」后，同一条点击路径**没有变绿**，而是换了异常：

```
android.util.AndroidRuntimeException: Calling startActivity() from outside of an Activity context
  requires the FLAG_ACTIVITY_NEW_TASK flag
  at dev.tripoder.runtime.RuntimeEngine.routeFromContext(RuntimeEngine.java:701)
  at dev.tripoder.runtime.RuntimeBridge.routeFromContext(RuntimeBridge.java:152)
  at dev.tripoder.runtime.RuntimeSelfHookActivities.lambda$routing$0(RuntimeSelfHookActivities.java:218)
```

这条异常**文本是平台的、语义不是**：平台只在「真的没有 token 的 context」上抛这句话；而这次调用方是有 token 的 Activity，平台不会抛。我们自己把调用方降级成 context，又用平台的 context 规则去拒绝它。

有意思的是两次崩溃的栈尾完全一致——说明「换个异常」不会移动失败点，这与 h23→h27 的教训是同一条。

## 3. 修复（两处，都在同一函数内）

`RuntimeSelfHookActivities.routing()` 的 `execStartActivit*` 分支：

```java
// 引擎没有记录的 Activity 实例：平台照做，运行时也不拒绝，按 context 起步。
if(activity!=null&&!RuntimeBridge.owns(activity)){
    Log.i("TripoderActivities","start from retired activity: class="+...);
    activity=null;
    // 重新落到 context 路径后，这次起步没有任务——平台对无 token 起步的规则就是这一位。
    if(intent!=null&&(intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)==0){
        intent=new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);args=args.clone();args[4]=intent;
    }
}
```

- `routeFromContext()` **不改**：它对真正的 context 调用方保持与平台一致的拒绝语义。
- 真正需要拦的东西仍然拦：紧随其后的 `RuntimeBridge.startsHost(intent)` → `ACTIVITY_START_HOST_COMPONENT_DENIED` 一位未动，宿主组件不会被这份降级打开。

## 4. 验证（v65，真机）

| 项 | 结果 |
|---|---|
| 构建 | `BUILD_EXIT=0`；`tripoder-q6.apk` sha256 `af681be1c39f073e3db7d886c02f352930aa3ca74b6db67f3a650c3a470e7b4b` |
| 同一条路径（我 → 我的钱包 → 返回 → ＋） | `start from retired activity` 命中 **2** 次 |
| crash 缓冲 | **空**（v64 在同一路径上仍崩 AndroidRuntimeException，v65 不崩） |
| 准入栅栏 | `admission-blocked` 计数 **0** |
| 进程 | 主 / `:remote` / `:image` 三进程全程存活（21326 / 21472 / 21938） |

即：**两次崩溃是同一条路径上的两个连续出口，现在都被关闭，且没有把宿主组件开口子**。

## 5. 登录入口：找到了，且可达

前面几轮找不到「登录」文本，是因为入口不叫「登录」也不在首页：

```
个人页 → 设置（账号管理 / 账号与安全 / 通用设置 …）
       → 账号管理 → 添加账号
       → 登录页 com.sina.weibo:id/fl_login_root
          短信验证码登录 · 手机号 · 验证码 · 「登录注册表示同意用户协议、隐私条款」· 登录按钮
          底部：密码登录 / 扫码 / 使用当前手机号辅助注册
```

登录页在容器内**完整渲染、可交互、无崩溃、无栅栏**（`top=TaskP1$F0M1C2`，dump 到 20+ 个控件与 bounds）。

## 6. 边界（这一轮没有做到的事）

- **登录闭环没有完成**：微博这条路径要手机短信验证码；「使用当前手机号辅助注册」在发码前先要人过关一次图片验证码（`请先验证身份` / `请依次点击 茄子 辣椒 土豆`）。第一次点击已被接受（计数 1/3），后续两次未命中，我没有继续对它做自动化尝试——它是微博的账号安全控制，属于用户侧交互，且通过后仍需用户手里的验证码。
- 只验证了微博；Activity 起步族是否还会在别的应用上以其它出口出现，未测。
- 只改了「起步」这一族；`finish` 分支的 `RESULT_CALLER_UNSUPPORTED`、`ACTIVITY_START_SIGNATURE_UNSUPPORTED` 等相邻出口仍在，只是这一轮没有被踩到。

## 7. 复现

```bash
# 崩（v63/v64 前）：个人页 → 我的钱包
adb -s d861a4fa shell input tap 1095 2470   # 我
adb -s d861a4fa shell input tap 174 1295    # 我的钱包
adb -s d861a4fa shell logcat -d -b crash | grep -A3 "FATAL EXCEPTION"

# 不崩（v65）：同一路径 + 设置 → 账号管理 → 添加账号
adb -s d861a4fa shell logcat -d -s TripoderActivities:V | grep -c "start from retired activity"
python3 android/scripts/guest_ui.py crash
```
