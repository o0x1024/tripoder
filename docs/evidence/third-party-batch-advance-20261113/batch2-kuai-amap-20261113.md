# 第 2 批实测：快手通过、高德地图崩溃族定位与修复（v65 → v66）

设备 `d861a4fa`，容器 `dev.tripoder.q6`，2026-11-13 12:49–13:0x。
本批目标：按 BOSS直聘 的做法，对容器内第三方应用逐个走「启动 → 隐私/协议页 → 首页 → 登录入口」，
每步记录三件套：`topResumedActivity` / `crash` 缓冲 / `admission-blocked`（准入栅栏）。

## 1. 快手（com.smile.gifmaker）：通过

| 观察点 | 结果 |
|---|---|
| top | `TaskP1$F0M1C2`（隐私页）→ `TaskP1$F0M0C2`（feed） |
| 进程 | `com.smile.gifmaker` + `com.smile.gifmaker:push_v3` 全程存活 |
| crash 缓冲 | 空 |
| 准入栅栏 | `admission-blocked` = 0 |
| 门面降级 | 仅 1 条 `ALARM_DROPPED ... ALARM_SHAPE_UNSUPPORTED:getNextAlarmClock` |

- 起点是「欢迎使用快手」隐私政策页（`同意并继续` 在 [214,2156][994,2300]，中心 (604,2228)），
  点下后进入 feed，截图可见左上「点击登录」徽标 ⇒ 首页真实渲染，不是白屏。
- `:push_v3` 存活说明 v61 的闹钟降级（原本会把 `:remote` 类进程打崩）在快手同样生效。
- 快手登录入口就在这个「点击登录」徽标（约 (102,551)），本轮未继续点，留给登录闭环批次。

## 2. 高德地图（com.autonavi.minimap）：从崩溃到首页

### 2.1 v65 的崩溃（我方门面，不是应用风控）

启动本身成功（隐私页 `TaskP1$F0M2C0E0`，`同意并继续` [214,2156][994,2300]），点同意后 crash 缓冲出现：

```
FATAL EXCEPTION: main
Process: com.autonavi.minimap, PID: 30634
java.lang.RuntimeException: Error receiving broadcast
  Intent { act=android.bluetooth.device.action.FOUND flg=0x10 xflg=0x4 pkg=dev.tripoder.q6 (has extras) }
  in dev.tripoder.runtime.RuntimeReceiverProxy@be08ba6
  at android.app.LoadedApk$ReceiverDispatcher$Args.lambda$getRunnable$0(LoadedApk.java:2181)
Caused by: java.lang.IllegalArgumentException:
  PROVIDER_EXTRA_TYPE_UNSUPPORTED:android.bluetooth.device.extra.UUID_LE:[Landroid.os.Parcelable;
  at dev.tripoder.runtime.RuntimeSystemBroadcasts.systemBundle(RuntimeSystemBroadcasts.java:149)
  at dev.tripoder.runtime.RuntimeSystemBroadcasts.extras(RuntimeSystemBroadcasts.java:127)
  at dev.tripoder.runtime.RuntimeSystemBroadcasts.envelope(RuntimeSystemBroadcasts.java:175)
  at dev.tripoder.runtime.RuntimeSystemBroadcasts.observation(RuntimeSystemBroadcasts.java:184)
  at dev.tripoder.runtime.RuntimeReceivers.before(RuntimeReceivers.java:291)
  at dev.tripoder.runtime.RuntimeReceiverProxy.onReceive(RuntimeReceiverProxy.java:27)
```

判据：栈里有 `dev.tripoder.runtime.*` 帧 ⇒ 这是**我们的承载力缺陷**，不是高德的风控
（与 `facade-must-honor-platform-exceptions` 的排障口径一致）。

机制：`systemBundle` 只接受一组白名单形状，其余一律 `throw`。它被**广播 envelope 与 provider 两处共用**
（错误码前缀 `PROVIDER_*` 即为证）。蓝牙设备发现广播携带 `Parcelable[]`
（`android.bluetooth.device.extra.UUID_LE`），于是：

1. `RuntimeReceivers.before()` 在处理投递时包 envelope → 抛 `IllegalArgumentException`；
2. 该异常发生在 `RuntimeReceiverProxy.onReceive` 内，也就是在 `LoadedApk$ReceiverDispatcher` 的派发体内；
3. 平台对「onReceive 里抛出的未捕获异常」的处置是**杀进程** ⇒ 一条不该被拒绝的系统广播变成客体 FATAL。

这就是 h31：第三个「门面在平台不失败处抛异常」的实例族，且抛点在派发体内，一定升级为进程崩溃。

### 2.2 修复（v66）

`RuntimeSystemBroadcasts.systemBundle` 从「校验即抛」改为「**丢弃不可表达的键 + 记录编码**」：

- 新增 `Parcelable[]` 支持（逐个元素过 `platformType()`，任一不合法则整键丢弃）；
- 键名过长 / 字符串过长 / 数组过长 / 嵌套过深 / 键数超限 / 过大 / 含 fd：一律**丢该键**（深度超限丢整层），
  不再抛；
- 丢弃按「码」去重，最多 32 条，`Log.w("TripoderFacade","BROADCAST_EXTRA_DROPPED:"+code)`。

保留不动的部分：`PROXY_INFO_EXTRA_INVALID`（ProxyInfo 类型错，属伪造输入）与 `envelope()` 的形状校验
（action 白名单、data 形状）——这些是**门面契约**，不是 extras 表达力问题。

无网络/无蓝牙时客户端的 extras 丢一个键不会影响它工作：它本来也拿不到该对象（非平台类型无法跨进程）。

### 2.3 v66 验证

```
BUILD_EXIT=0
b584e4f359b01b5e05959c433a5f105d92a4cc36875af5399888f2aea109636a  tripoder-q6.apk
```

| 观察点 | 结果 |
|---|---|
| top | `TaskP1$F0M2C0E0`，页面为高德**首页**：`查找地点、公交、地铁` / `首页` / `探索` / `对话` / `打车` / `未登录` / `15°C` |
| crash 缓冲 | **空** |
| 准入栅栏 | `admission-blocked` = 0 |
| 进程 | `com.autonavi.minimap` + `:locationservice` + `:widgetProvider` 三进程存活 |
| `BROADCAST_EXTRA_DROPPED` | **0 条** |

诚实边界：本轮没有蓝牙设备发现广播，**丢弃路径没有被真正触发**，`drops=0`。
所以 v66 证明的是「高德从崩溃变为可用（首页可达、三进程存活、crash 空）」，而不是「丢弃分支已被执行」。
要把丢弃分支真正跑一遍，需要构造一条带 `Parcelable[]` 的 `android.bluetooth.device.action.FOUND`，
adb 无法直接构造该 Parcelable，只能等真实蓝牙事件或写 control-test 反射调用 `systemBundle`——列入待办。

## 3. 微博：登录入口已定位（延续上一轮）

路径是 **个人页 → 设置 → 账号管理 → 添加账号**，落地登录页 `fl_login_root`
（短信验证码登录 / 手机号 / 验证码 / 协议 `iv_login_view_protocol` [262,1070] / 登录按钮 [60,1173][1148,1299]）。

未闭环的原因不在容器：需要手机短信验证码；发码前还有一次「请先验证身份」图片验证
（依次点击茄子/辣椒/土豆），第一次点击被接受（1/3），后两次未命中。

## 4. 复现

```bash
# 构建 + 安装 + 启动高德（v66）
bash /tmp/v66.sh

# 手动逐步（每次都重新 dump，Workbench 坐标逐轮漂移）
python3 android/scripts/guest_ui.py top
python3 android/scripts/guest_ui.py tap "高德地图" --scroll
python3 android/scripts/guest_ui.py crash
adb -s d861a4fa logcat -d -s TripoderFacade:W | grep BROADCAST_EXTRA_DROPPED
adb -s d861a4fa logcat -d -s TripoderRecovery:V | grep -c admission-blocked
```

## 5. 本轮新增/更新的结论

- **h31（支持）**：系统广播 extras 的非标形状（`Parcelable[]`、超长数组、嵌套过深）在 `systemBundle` 被抛，
  抛点落在 `onReceive` 派发体内 ⇒ 必然升级为客体进程 FATAL。
- `systemBundle` 同时服务广播与 provider：改语义要同时看两条路径（本轮只测了广播侧）。
- 判据口径固定下来：crash 缓冲出现 `Error receiving broadcast ... in RuntimeReceiverProxy`
  且 `Caused by ... dev.tripoder.runtime.*` ⇒ 门面缺陷，先去修运行时，不要怀疑应用兼容性。

## 6. 未验证

- `BROADCAST_EXTRA_DROPPED` 分支未被执行（见 §2.3）。
- provider 侧的 `systemBundle` 调用点未回归（本轮改动只影响 extras 形状，理论上是放宽，但仍应跑一次
  带 content provider 的应用）。
- 高德登录入口（`未登录` tab，[988,2447][1078,2488]）未点，本轮只到首页。
- 快手登录闭环（「点击登录」徽标）未进入。
