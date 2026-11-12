# BOSS直聘容器内一键登录：崩溃、根因与修复（2026-11-13）

## 一、结论

1. **BOSS直聘的容器阻断点不在抖音那一族「安装来源」判据上**——与京东一致（[京东证据](../jd-container-login-20261113/README.md)）。容器给出 `installer=null`、客体跑宿主 uid `10542` 而包名不自洽，BOSS直聘照常渲染启动页、登录页与本机号码一键登录，**全程无任何来源/渠道类提示**。
2. **第一次实测的失败不是对方风控，是我们自己的运行时缺陷**：客体在「登录成功后发送本地广播」时，被 `RuntimeBroadcastIntent.envelope()` 以 `IllegalArgumentException: BROADCAST_INTENT_UNSUPPORTED` 打死，主进程 `am_proc_died`。
3. 修复方式是把「形状不合就抛」改成「先降级到可承载形状再投递」（`build-bcast-v54`），回归实测：同一路径下进程全程存活、crash 缓冲为空、登录态进入资料完善页。

**判据归属**：BOSS直聘的可登录性由**承载层的 API 保真度**决定（这里是广播门面），而不是由来源事实决定。这与抖音（服务端渠道归属，P2 边界）、京东（同样不吃来源族）共同构成一条可外推的判据规则：**遇到「某应用在容器内不能登录」，先分清是①我方门面契约缺陷 ②对方本地风控 ③对方服务端判据，三者处置完全不同。**

## 二、环境

| 项 | 值 |
|---|---|
| 设备 | `d861a4fa` |
| 宿主 | `dev.tripoder.q6`；修复前 `build-hint-v53`，修复后 `build-bcast-v54`（`sha256 e4658561…7d7727`，2,319,603 B） |
| 原生包 | `com.hpbr.bosszhipin`，`versionName=14.170`，`versionCode=1417010`，`minSdk=22 targetSdk=33` |
| 原生 installer | `com.xiaomi.market`（首次装 2026-09-23，最近更新 2026-09-27） |
| 原生 codePath | `/data/app/~~k8OA45b9hNokbMGQaGdD1Q==/com.hpbr.bosszhipin-wZHeGyFxqXdTBu9sIo7rWw==` |
| 容器收录 | Workbench 应用列表已含「BOSS直聘」（9 应用之一），无需导入 |

## 三、实测序列

### 3.1 启动（v53）

```bash
adb -s d861a4fa shell uiautomator dump /sdcard/wb3.xml    # 应用列表；BOSS直聘 [66,820][372,867]
adb -s d861a4fa shell input tap 219 843
```

观测：

- 进程 `23954 com.hpbr.bosszhipin` + `24098 com.hpbr.bosszhipin:mms`，**两者 uid 均为 10542**（=宿主 `dev.tripoder.q6`）；
- `topResumedActivity=dev.tripoder.q6/…TaskP0$F0M0C2`；
- 首屏正常：`找工作 招人才 直接聊 / 我要找工作 / 我要招人`，**无任何来源提示**。

### 3.2 登录页

点「我要找工作」后直接进入本机号码一键登录页（UI dump 可读）：

```
153****8553                                                   ← 运营商取到的本机号（掩码）
首次验证通过即注册BOSS直聘账号
一键登录
已阅读并同意《天翼账号认证服务条款》、《BOSS直聘用户协议》和《隐私政策》…
认证服务由中国电信提供
```

勾选框 `com.hpbr.bosszhipin:id/checkbox`（`checked=false`）位于 `[72,1444][120,1495]`。

### 3.3 第一次登录：崩溃（v53）

勾选协议 → 点「一键登录」→ 14 秒后回到 Workbench，主进程消失（仅剩 `:mms`）。系统事件：

```
am_crash : [23954,0,dev.tripoder.q6:slot0,949501508,java.lang.IllegalArgumentException,BROADCAST_INTENT_UNSUPPORTED,RuntimeBroadcastIntent.java,12,0]
am_proc_died: [0,23954,dev.tripoder.q6:slot0,0,2]
```

crash 缓冲里的完整栈（关键方向：**认证已经成功**，崩在成功之后的通知广播上）：

```
FATAL EXCEPTION: main  Process: com.hpbr.bosszhipin, PID: 23954
java.lang.IllegalArgumentException: BROADCAST_INTENT_UNSUPPORTED
  at dev.tripoder.runtime.RuntimeBroadcastIntent.envelope(RuntimeBroadcastIntent.java:12)
  at dev.tripoder.runtime.RuntimeBroadcastIntent.encode(RuntimeBroadcastIntent.java:18)
  at dev.tripoder.runtime.RuntimeReceivers.prepare(RuntimeReceivers.java:215)
  at dev.tripoder.runtime.RuntimeReceivers.send(RuntimeReceivers.java:204)
  at dev.tripoder.runtime.GuestContext.sendBroadcast(GuestContext.java:153)
  at android.content.ContextWrapper.sendBroadcast(ContextWrapper.java:524)
  at com.hpbr.bosszhipin.module.login.util.l.i(SourceFile:24)
  at com.hpbr.bosszhipin.module.login.util.l.d(SourceFile:37)
  at com.hpbr.bosszhipin.module.login.util.l.b(SourceFile:0)
  at com.hpbr.bosszhipin.module.login.util.l$c.onSuccess(SourceFile:22)
  at com.bszp.kernel.user.UserInfoHandler.onSuccess(SourceFile:13)     ← 用户信息已到手
  at com.hpbr.bosszhipin.module.login.util.i$a.onSuccess(SourceFile:79)
```

顺带得到一个事实：崩溃后重新进入应用，启动页出现**退出登录**项 —— 说明那次一键登录**服务端已成功且登录态已落地**，死的只是进程。

### 3.4 缺陷与修复

`envelope()` 原实现把「携带 `clipData` / `selector` / `sourceBounds` / `identifier` / 任意非 `FLAG_RECEIVER_FOREGROUND` 的 flags」一律当作拒绝理由并抛 `IllegalArgumentException`。两个问题：

1. **越权严格**：这些形状里除 `clipData`/`selector` 外都无害；flags 在本运行时根本不参与路由（路由由 broker 按内容决定，`RuntimeReceivers` 只是把 flags 透传到外层信封），把它写进白名单没有安全收益。
2. **契约错误（更要紧）**：平台 `Context.sendBroadcast` 从不为「intent 形状」抛未检查异常；门面把内部校验失败抛进客体主线程，等价于让运行时决定应用生死。

修复（`android/runtime/src/dev/tripoder/runtime/RuntimeBroadcastIntent.java`）：

```java
private static final int BROADCAST_FLAGS = FLAG_RECEIVER_FOREGROUND | FLAG_RECEIVER_REGISTERED_ONLY
    | FLAG_RECEIVER_NO_ABORT | FLAG_RECEIVER_REPLACE_PENDING
    | FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS | FLAG_INCLUDE_STOPPED_PACKAGES | FLAG_EXCLUDE_STOPPED_PACKAGES;

/** 降到可承载形状，而不是拒绝：客体接收方看到的就是降级后的 intent。 */
static Intent sanitize(Intent original){ … 清 clipData/selector/sourceBounds/identifier，flags &= BROADCAST_FLAGS … }

static byte[] encode(Intent original){
    Intent reduced=sanitize(original); envelope(reduced);   // 先降后验
    …
}
```

系统广播 action 的策略拒绝（`SYSTEM_BROADCAST_POLICY_UNSUPPORTED`）**未改动**，仍按原设计抛出。

### 3.5 回归（v54）

```bash
adb install --no-incremental -r build-bcast-v54/tripoder-q6.apk
adb shell am force-stop dev.tripoder.q6; adb shell am force-stop dev.tripoder.q7
adb shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity   # 两击「重新连接」回到 9 应用列表
# Workbench → BOSS直聘 → 我要找工作 → (退出登录) → 确定 → 一键登录
```

结果：

| 检查项 | 结果 |
|---|---|
| 进程 | `27374 com.hpbr.bosszhipin` 与 `27553 :mms` 全程同一 pid，**未重启** |
| crash 缓冲 | 空（无 `FATAL`、无 `IllegalArgumentException`） |
| 登录结果 | 进入 `完善资料、快速找工作`（你是学生吗 / 希望在哪里工作 / 期望的职位 / 城市热招），即登录态已建立 |
| 号码 | 掩码仍是 `153****8553`（与原生同一账号） |

## 四、边界与残余风险

1. **n=1 回归**：只跑了一次「退出登录 → 重新一键登录」，未做多次复测。
2. **广播形状降级不是无损**：`clipData` / `selector` / `sourceBounds` / `identifier` 被丢弃。若某应用依赖 `selector` 做二次重定向，行为会与平台不同（广播场景极罕见）。
3. **flags 语义未实现**：白名单内的 flags 只是透传，`FLAG_RECEIVER_REGISTERED_ONLY` 这类「只投递给已注册接收方」的语义在本运行时的路由里**没有实现**，属已知语义差异（本次不影响登录）。
4. **残余同型风险**：其他门面仍可能在客体线程抛平台不会抛的异常（`RuntimeReceivers` 的 `BROADCAST_OPTIONS_UNSUPPORTED`、`BROADCAST_PREPARATION_UNCONFIRMED`、`BROADCAST_BINDER_PAYLOAD_UNSUPPORTED` 等）。本次只修了「形状」一层，没有把「门面不得杀死客体」做成全局不变量。
5. **业务链路未测**：只到登录态与资料引导页；未测投递简历、与 BOSS 沟通、在线简历保存、消息推送。
6. **真实账号副作用**：用真机号码 `153****8553` 在容器内建立了 BOSS直聘登录会话（首次验证即注册）。需要回滚时在容器内「退出登录」。

## 五、复现

```bash
# 复现崩溃（v53 或任何 envelope 仍硬拒的构建）
adb -s d861a4fa logcat -c
adb -s d861a4fa shell input tap 219 764          # Workbench 应用列表里的 BOSS直聘（坐标随布局变化，先用 uiautomator dump 取）
adb -s d861a4fa shell input tap 603 962          # 我要找工作
adb -s d861a4fa shell input tap 96 1469          # 协议勾选
adb -s d861a4fa shell input tap 604 1187         # 一键登录
adb -s d861a4fa logcat -b crash -d | grep -A3 BROADCAST_INTENT_UNSUPPORTED

# 验证修复（v54+）：同一序列，检查进程存活与 crash 缓冲为空
adb -s d861a4fa shell "ps -A -o PID,UID,NAME | grep hpbr"
adb -s d861a4fa logcat -b crash -d -t 80
```
