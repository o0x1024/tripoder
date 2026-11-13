# 第 8 批附属：文本注入全废的真因是锁屏持有窗口焦点

日期：2026-11-13（v70，宿主文内版本号 `bf7eca26…`，设备 d861a4fa）
结论一句话：容器内「点得动、打不进字」不是客体窗口不可聚焦，而是**锁屏 `NotificationShade` 一直握着 display 0 的窗口焦点**；`input text` 全部投给了锁屏，`input tap` 仍按区域派发给应用窗口。

## 1. 现象与判别

在同一台设备上，`input tap` 能把小红书从隐私页一路推到手机号页，但同一次会话里两个

```
$ adb shell input tap 600 570          # 点手机号输入框
$ adb shell input text 15323738553     # 静默丢弃，输入框仍是占位符
```

三条读数同时成立：

```
dumpsys window | mFocusedWindow=Window{80103a9 u0 NotificationShade}
dumpsys window | mCurrentFocus=null
dumpsys window | mShowingDream=false mDreamingLockscreen=true
dumpsys input  | FocusedDisplayId: 0 / FocusedApplications: displayId=0 …TaskP3$F0M1C2 / FocusedWindows: （空）
```

读法：
- `FocusedApplications` 里有客体 ⇒ **应用级**焦点是有的（所以 `am start`、Activity 生命周期、截图都正常）；
- `FocusedWindows` 为空、`mCurrentFocus=null`、`mFocusedWindow=NotificationShade` ⇒ **窗口级**焦点在锁屏手上，任何 `input text` / `input keyevent` 都进不了客体；
- 触摸不要求窗口焦点，所以 `input tap` 依然命中 —— 这正是「点得动、打不进字」的成因。

## 2. 排除「客体窗口不可聚焦」这个候选

把纯宿主 Activity 拉到前台（完全不涉及客体）：

```
$ adb shell am start -W -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
dumpsys window | mCurrentFocus=null
dumpsys input  | FocusedApplications: displayId=0 …WorkbenchActivity / FocusedWindows:（空）
```

宿主自己的 Activity 也拿不到 `mCurrentFocus`。⇒ 与 xhs 的窗口栈、与子窗口、与 `FLAG_SECURE` 都无关，是设备侧焦点持有者的问题。这条排除了「我们的虚拟窗口没设可聚焦标志」的假设。

## 3. adb 侧解不开锁屏

| 手段 | 结果 |
|---|---|
| `input keyevent 224`（WAKEUP） | `mFocusedWindow` 仍是 NotificationShade，`mDreamingLockscreen` 仍 true |
| `input swipe 600 2500 600 700 250`（上滑） | 无变化 |
| `wm dismiss-keyguard` | 无变化 |
| `input keyevent 26` ×2（灭屏/亮屏） | 只让 `FocusedApplications` 重新出现客体，`mCurrentFocus` 仍 null |
| `force-stop dev.tripoder.q6/q7` + 重开 | 无变化（两次） |

⇒ 设备处于有安全锁的状态，解锁需要 PIN/指纹/密码。这不是容器能改的，也不是代码缺陷。

## 4. 影响面

- **所有需要打字的路径当场不可用**：小红书验证码（本轮就是要补的最后一步）、携程/淘宝/支付宝等的手机号登录、以及任何图形验证码输入。
- 不影响的路径：启动、隐私同意、页面跳转、登录入口识别、一键登录（运营商侧，不需要打字）、截图/dump 取证。上一轮已经闭环的微博/拼多多登录不受影响是因为当时焦点健康。
- 之前把根因记成 `com.xiaomi.subscreencenter/SubScreenLauncher` 抢焦点，是**错的**：它只是 display 1 的 FocusedApplication（妙享背屏），display 0 的焦点持有者是 NotificationShade。

## 5. 复现与门控

`android/scripts/xhs_login_finish.py` 把这条结论变成了可执行的闸门：每次写文本前断言 `mCurrentFocus` 属于容器，不满足就灭屏/亮屏重试两次，仍不满足则**中止而不是静默写入**。本轮运行结果：

```
FOCUS: mCurrentFocus=null
focus missing before phone number; toggling screen (attempt 1)
FOCUS: mCurrentFocus=null
focus missing before phone number; toggling screen (attempt 2)
focus gate before phone number: FAILED
ABORT: no guest focus, phone number cannot be written
```

这正是上一轮验证码被静默丢弃的位置 —— 现在它会在写入前失败并说明原因。

## 6. 边界与下一步

- 本文只主张「窗口焦点持有者是锁屏」以及「adb 解不开」；不主张 `NotificationShade` 持焦点是 MIUI 的 bug 还是被我们的多屏（display 1 副屏）激活所诱发。
- 可继续验证的方向（需要先解锁）：解锁后立刻读三项读数，若能恢复 `mCurrentFocus=<客体窗口>`，则「解锁即恢复」成立，可把该判断写进批处理前置检查（`guest_ui.py` 起来先断言一次）。
- 与 `batch8-xhs-login-20261113.md` 的关系：那篇记录的「焦点悬空」是症状，本篇给出持有者；两篇不冲突，本篇更具体。

## 7. 更正：灭屏/亮屏不是恢复手段，它会把设备锁上

`xhs_login_finish.py` 的焦点门连续两次灭屏/亮屏之后，设备停在了 PIN 锁屏上，`uiautomator dump` 直接给出键盘本体：

```
TextView  请用数字密码或指纹解锁            [323,300][884,369]
TextView  点击图标使用人脸解锁              [105,387][1103,437]
PasswordTextView  PIN 码区域               [251,737][956,875]
TextView  紧急呼叫 / 返回                   中国电信
```

因此本文 §3 里「灭屏/亮屏无变化」的读法要补一句因果：**那两次 keyevent 26 正是把设备变成锁屏状态的动因**。
它先让 `FocusedApplications` 短暂回到客体（上一轮据此误判为「恢复配方」），再把 keyguard 抬到前台，
从此 `input text` 一律失效 —— 上一轮验证码被丢弃、本轮手机号写不进去，都是这个动作的后效。

正确的做法（写进批处理前置）：

```
adb shell svc power stayon true        # 驱动期间保持常亮，禁止自动灭屏
adb shell settings put system screen_off_timeout 1800000
# 任何时候都不要用 input keyevent 26 去「恢复焦点」，它等于锁屏
```

判别口径：只要 `dumpsys window | mFocusedWindow=` 是 `NotificationShade`，就不要再尝试任何文本注入，
应当停下来要求解锁，而不是继续灭屏/亮屏或 force-stop。

## 8. 本轮由此得到的批处理前置检查

```
mFocusedWindow != NotificationShade   # 否则一律视为「设备已锁」，中止打字类动作
FocusedWindows 非空                    # 否则窗口级焦点缺失
```

两条同时满足再开始「启动 → 登录入口 → 写手机号」的流水线；只满足后者时只能做启动与入口识别（tap 类）。
