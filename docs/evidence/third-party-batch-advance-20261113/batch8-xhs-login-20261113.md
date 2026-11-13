# 第 8 批：小红书登录链路实测与两个容器侧缺陷

设备 `d861a4fa`，容器 v70（`tripoder-q6.apk` sha256 `bf7eca26…`），容器应用集合 installs=31。
本轮目标：把第 7 批新导入的小红书从「启动可用」推到「登录闭环」，顺带按 Boss 直聘的口径判断阻断点在我们还是在对方。

## 1. 登录链路走到了哪一步

| 步骤 | 动作 | 结果 |
|---|---|---|
| 1 | Workbench →「小红书」 | `TaskP4$F1M1C2`，落在「个人信息保护提示」 |
| 2 | 点「同意」 | 进到第二道隐私弹窗「请阅读并同意以下条款」（含**中国电信认证服务条款**） |
| 3 | 点「同意并继续」 | `TaskP4$F1M2C2` → 登录页（`+86 153 **** 8553`、「一键登录」、「其他登录方式」） |
| 4 | 点「一键登录」 | **无响应、不崩、页面不变**（与微信「用短信验证码登录」、快手「登录」胶囊同族） |
| 5 | 点「其他号码登录」 | 手机号登录页（`验证并登录` / `密码登录`） |
| 6 | 手机号分段写入 `153`+`2373`+`8553` | 成功（截图确认 `153 2373 8553`） |
| 7 | 点「验证并登录」 → 条款弹窗「同意并继续」 | 到**验证码页**：`输入验证码` / `重新发送(55s)` / `登录` |
| 8 | 读机内短信 | 取到码 **118950**：`【小红书】您的验证码是: 118950，用于手机号登录， 5分钟内有效。`（发件 106852508040607） |
| 9 | 点验证码框 + `input text 118950` | **字符未落入**（截图仍是 placeholder） |
| 10 | force-stop 重走一遍 | 隐私两道通过后误入首页 feed（关注/发现/长沙 + 底部「使用 +86 153****8553 登录 一键登录'）；**feed 渲染正常** |
| 11 | 首页底部点「一键登录」 | 同样无响应 |

即：**登录页面本身没有风控拦截**，小红书与京东/大众点评/BOSS直聘同口径（installer=null、客体跑宿主 uid 10542）仍能渲染运营商掩码号码。卡点在容器侧两件事上（下两节）。

## 2. 缺陷 A：输入焦点悬空，`input text` 全部丢弃

现象与取证：

```
dumpsys window
  mCurrentFocus=null
  mFocusedApp=ActivityRecord{… com.xiaomi.subscreencenter/.SubScreenLauncher t4}
dumpsys input
  FocusedDisplayId: 0
  FocusedApplications:            ← 空
  FocusedWindows:                 ← 空
  mInputShown=false
```

- `input tap` 仍然命中（按钮都响应），但**焦点类**输入全部失效：`input text` 被丢、软键盘不弹（`mInputShown=false`），IME 的 `mServedView` 还停在宿主的 `DecorView[WorkbenchActivity]`。
- `mFocusedApp` 停在 `com.xiaomi.subscreencenter/.SubScreenLauncher`（妙享背屏 launcher，display 1）。主屏有 resumed Activity 却没有 focused window，属于焦点悬空而非应用问题。
- **force-stop q6/q7 + 重开 Workbench 不能恢复**（两次实测都还是 null）⇒ 不是进程级状态，是展示/焦点层的设备级状态。

恢复配方（本轮唯一有效的一条）：

```
adb shell input keyevent 26   # 灭屏
sleep 2
adb shell input keyevent 26   # 亮屏
```

之后立刻读回：

```
FocusedApplications:
  displayId=0, name='ActivityRecord{… TaskP3$F0M1C2 t16478}'   ← 客体重新成为 focused app
FocusedWindows:
  displayId=0, name='… NotificationShade'                       ← 需再解锁
```

这与之前记录的 ANR 是同一事实的两面：ANR 记录里写的是
`Input dispatching timed out (… IndexActivityVXX is not responding. Waited XXms for FocusEvent(hasFocus=false))`，
即窗口**从未拿到焦点**，不是应用主线程卡死。

## 3. 缺陷 B：客体自建 `app_process` 子进程，容器零接管

小红书会 fork 自己的进程外负载：

```
Cmdline: /system/bin/app_process64 /system/bin com/xingin/tiny/daemon/e
pid: 23221, ppid: 22859           ← 22859 是 com.xingin.xhs 主进程
Abort message: 'No pending exception expected: java.lang.ClassNotFoundException: com.xingin.tiny.daemon.e'
stack: libart art::ClassLinker::FindClass → art::JNI<false>::FindClass
       → libandroid_runtime register_android_util_SeempLog → AndroidRuntime::startReg → app_process64 main
```

- 12 条 crash 记录，全部是**同一子进程**反复自杀重启（间隔 0.1–2s），主进程不受影响（4 个 xingin 进程存活）。
- 它不是我们的槽位进程（不经 `Instrumentation` 启动），所以容器的门面、身份投影、准入栅栏**都不覆盖它**；ART 在 `startReg` 注册期就因类找不到 abort，属子进程自举失败，不是我们抛的异常。
- 判定口径：crash 缓冲里 `Cmdline` 是 `app_process64 /system/bin <类名>` 且 `ppid` 指向客体主进程 ⇒ 客体自建进程，不进 runtime 修复队列。

## 4. 另一条容器侧观察：慢 Binder 拖慢首帧焦点

同一轮 ANR 的 `AnrClue` 是：

```
Slow Binder: com.xingin.xhs->dev.tripoder.q6:authority
clues=BLOCK_BINDER_CHAIN
```

也就是说首帧期间客体向我们的 authority 取数据被阻塞，进而焦点事件超时。这条与缺陷 A 叠加：**首帧慢 → FocusEvent 超时 → 焦点悬空 → 之后任何文本输入都进不去**。

## 5. 结论

1. 小红书的**可用性**判定通过：启动、两道隐私、登录页、手机号页、验证码页、首页 feed 全部正常渲染，无 FATAL（含子进程自杀也不影响主进程）。
2. **登录闭环未完成**，且阻断点是我们自己的：一旦发生 `input.app` ANR + 焦点悬空，验证码无法写入。用户在这一步没有可做的事，不需要向用户提问。
3. 「一键登录」在小红书上点击无响应（首页底部条与登录页两处），与微信/快手的小文字控件不响应同族，已并入 `gap-small-link-tap-unresponsive`。

## 6. 下一轮入口

- 先用「灭屏/亮屏」恢复焦点，再进 `我` → 登录 → 手机号 → 取新码 → 写入 → 登录；每写一段就截图校验（页面含 EditText，禁止 `uiautomator dump`）。
- 若要根治焦点悬空，方向是查 authority 首帧为何 `BLOCK_BINDER_CHAIN`（不是改 app，也不是改文案）。
