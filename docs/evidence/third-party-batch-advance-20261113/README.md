# 全量第三方应用推进 · 第 1 批（`build-facade-v57`，设备 d861a4fa）

对象：`pm list packages -3` 的 **121 个包**（全量清单见 [inventory.md](inventory.md)）。方法沿用 BOSS直聘那一轮：
**先跑真实路径 → 出问题就分层归因（我方承载力缺陷 / 对方本地风控 / 对方服务端判定）→ 只修我方那一层并回归。**

## 1. 先解决「质」的问题：批量的前置条件

推进「所有第三方应用」不是 121 次手工启动，先要满足两条：

1. **装得进去**：容器导入一次最多批量选，实际测试中一次性导入 7 个应用成功（容器 9 → 16 个应用）。
2. **跑得起来到第 N 个**：这是本轮暴露的真问题——见 §3。

## 2. 第 1 批实测（7 个新装入 + 已有 4 个）

| 应用 | 包名 | 结果 | 判定层 |
|---|---|---|---|
| BOSS直聘 | `com.hpbr.bosszhipin` | 一键登录 → 完善资料页（前一轮已闭环） | 可用 |
| 京东 | `com.jingdong.app.mall` | 一键登录 → 个人中心（前一轮已闭环） | 可用 |
| 大众点评 | `com.dianping.v1` | 登录页可达，运营商号码认证页（`153****8553`，天翼账号）渲染正常 | 可用（未点登录） |
| 微信 | `com.tencent.mm` | 启动，进程存活，首屏 | 可用（未登录） |
| QQ | `com.tencent.mobileqq` | 启动到《服务协议/隐私政策》页 | 可用（未登录） |
| 美团 | `com.sankuai.meituan` | 启动到「温馨提示」隐私同意页 | 可用（未登录） |
| 微博 | `com.sina.weibo` | 空槽位下单应用启动**成功**到《用户协议和隐私条款》页；点「同意并继续」后崩溃于**我方广播门面**（§6） | 启动可用 · 登录链路被门面挡住 |
| 快手 / 高德地图 | `com.smile.gifmaker` / `com.autonavi.minimap` | 已装入容器，未启动 | 未测 |
| 抖音 | `com.ss.android.ugc.aweme` | 服务端按运行环境判定，入口已加提示（前一轮结论） | 不可用 |

**结论 1（复用上一轮的判据）**：京东/大众点评与抖音拿到的是**同一套来源回答**（`installer=null`、客体跑宿主 uid），
但抖音拒绝、京东与大众点评正常。⇒ 「容器来源不可用」不是普遍结论，**必须逐应用实测**，不能外推。

## 3. 批量推进的真瓶颈：运行槽位是硬预算

### 现象

装到第 7 个应用、启动第 6 个时：

```
应用没有启动
操作没有完成（PROCESS_CAPACITY_EXHAUSTED）
```

### 定位

`android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java:152`

```java
if(index<0)throw new IllegalStateException("PROCESS_CAPACITY_EXHAUSTED:requiredSlot="+(retained==null?"any":retained));
```

槽位来自 `RuntimeSlot.CAPACITY = 8`（`android/runtime/src/dev/tripoder/runtime/RuntimeSlot.java:16`）。
**这是进程池，不是应用池**：一个应用声明几个进程就吃几个槽位（QQ、美团、京东都是多进程）。所以 4–5 个应用就能吃满 8 个槽位。

### 为什么必须修

- 对「推进所有第三方应用」这个目标，这是**每一次批量操作都会撞到**的墙，不是个别应用的兼容问题。
- 用户侧看到的是内部错误码 + 无出路，和 BOSS直聘那次「传出去平台不会传的异常」属于同一类缺陷：
  **运行时把自己的预算/策略当成用户的死路。**

### 修法（`build-facade-v57`，只动 UI 层，不动槽位算法）

`android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java`

- 启动失败若 message 含 `PROCESS_CAPACITY_EXHAUSTED`，不再直接弹内部码；
- 弹出「运行槽位已满」，列出**当前正在运行的其它应用**，由用户选一个停止，随后自动重试启动目标应用；
- 文案明确「停止不会删除任何数据」；没有可停止的应用时才提示稍后再试；
- **不做静默驱逐**：槽位是真预算，牺牲谁必须由用户决定（平台在低内存时会静默杀后台，但那是平台权力，容器替用户做这个决定会丢未保存状态）。

## 4. 边界

- 本轮只覆盖 **7 个新应用 + 4 个已有应用**；121 个里其余 110 个仍是「未测」，`inventory.md` 中一律标 `未测`，不写成「不可用」。
- 微信/QQ/美团只到「启动 + 隐私页」，**没有登录**；能否登录是下一个未知量，不能由「进程活着」推出。
- 槽位修复只解决「第 6 个应用起启动失败」，不解决「多进程应用吃多份槽位」这一结构成本；如果后续批测发现常用组合超 8，
  那是另一个决定（提高 CAPACITY 需要同步生成更多 activity slot 桩，属于 manifest 与构建改动）。
- `PROCESS_CAPACITY_EXHAUSTED` 之外的容量错误（`ACTIVITY_*_CAPACITY_EXHAUSTED`、`SERVICE_*`、`ISOLATED_PROCESS_*`）
  本轮未一并做 UX 兜底。

## 5. 复现

```bash
# 1) 批量装入：Workbench → 添加应用 → 手机应用 → 多选 → 添加所选
# 2) 逐个冒烟：Workbench → 点应用 → 等 12s → dumpsys activity activities（topResumedActivity）
#    并同时抓 logcat -b crash（FATAL）与 logcat -s TripoderFacade:W（门面降级）
# 3) 槽位墙：连续启动第 6 个应用即可复现（v56 及以前表现为 PROCESS_CAPACITY_EXHAUSTED）
```

相关：门面异常契约见 [../facade-exception-contract-nonbroadcast-20261113/README.md](../facade-exception-contract-nonbroadcast-20261113/README.md)，
BOSS直聘闭环见 [../bosszhipin-container-login-20261113/README.md](../bosszhipin-container-login-20261113/README.md)。

## 6. 微博：启动不是兼容性问题，登录被「广播门面缺 PendingResult」挡住

### 6.1 单飞对照推翻了 §3 的读法

第 1 批里微博是第 5 个被启动的应用，失败于 `PROCESS_CAPACITY_EXHAUSTED`。这一轮把容器**清空**（`am force-stop dev.tripoder.q6` / `q7`）
后只点微博一个：

```
topResumedActivity=dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP0$F0M0C0
文本：用户协议和隐私条款 / 欢迎您使用微博！… / 不同意 / 同意并继续
```

⇒ **微博本身能启动**，第 1 批的失败是并发与槽位预算造成（见 [inventory.md](inventory.md) 与 gotcha `slot-budget-is-per-process-not-per-app`），
不是应用兼容性结论。批量推进的正确读法：**先清槽位再判定单应用**。

### 6.2 点「同意并继续」后崩溃：`Call while result is not pending`

```
FATAL EXCEPTION: pool-5-thread-8
Process: com.sina.weibo, PID: 11623
java.lang.IllegalStateException: Call while result is not pending
  at android.content.BroadcastReceiver.checkSynchronousHint(BroadcastReceiver.java:769)
  at android.content.BroadcastReceiver.setResultData(BroadcastReceiver.java:537)
  at com.sina.weibo.PushService.b(PushService.java:433)
  at com.sina.weibo.PushService$MyBroadcastReceiver.a(PushService.java:268)
  at com.sina.weibo.PushService$MyBroadcastReceiver$1.run(PushService.java:180)
```

`checkSynchronousHint()` 只在 `mPendingResult == null` 时抛出：微博的 push 接收器在**自己线程池里**对收到的 receiver 调用 `setResultData`，
说明这条广播在真机上是**有序广播**（AMS 会给 receiver 装上 PendingResult），而容器这次**没有把有序语义带过去**。

这与 BOSS直聘那轮的判据完全同源（[../facade-exception-contract-nonbroadcast-20261113/README.md](../facade-exception-contract-nonbroadcast-20261113/README.md)）：
**运行时不得让客体撞上"平台不会发生"的状态**。区别是这次不是「抛异常」，而是「少了一个平台本会给的对象」。

### 6.3 未做与下一个判别实验

- 未做的判别：在**真机原生微博**上抓同一条广播（`PushService$MyBroadcastReceiver` 注册的 action）是否是 ordered。
  若原生是 ordered，则这是容器**有序广播语义丢失**的结构缺陷，修在 `RuntimeReceivers` / `RuntimeBroadcastBroker`（单目标限制 `ORDERED_BROADCAST_SINGLE_TARGET_REQUIRED` 也要一起复核）。
- 本轮没有改广播门面：判据未取到之前改投递语义，等于拿登录链路做赌注。

## 7. 瓶颈 B 的定性修正：不是「无超时」，而是「120s 预算 + 全程无反馈」

上一版把「点下去 30–50s 卡在『启动应用 · 处理中』且零日志」写成「无超时」。这次让它自己跑完，结论翻转：

- `RuntimeStartupDeadline.TIMEOUT_MS = Long.getLong("tripoder.startup.timeoutMs", 120000L)`——预算存在，是 **120 秒**。
- 约 120s 后 UI 自行给出：`应用没有启动 / 操作没有完成（PROCESS_START_TIMEOUT_UNKNOWN）`。
- 全缓冲区唯一一条 supervisor 失败日志：`ProcessSupervisor.start failed for default/com.dianping.v1/com.dianping.v1 phase=APPLICATION`
  + `IllegalStateException: TimeoutException`。同窗口 `dvm_lock_sample` 显示 `ProcessSupervisor.java:56` / `RuntimeBroker.java:182`
  有 2.5–3.1s 锁等待，`binderTransact code=1` 一次 4396ms。
- ⇒ 真实失效点是 **phase=APPLICATION**（guest 已 attach、LOAD 完成，`START_APPLICATION` 未在预算内到 READY），
  且 **UI 不告诉用户是哪个应用、也不给取消/重试**。观测时只等 30–50s 就 dump，会把它误判成「永久卡住、零日志」。

### 修法（`build-facade-v58`，仍只动 UI 层）

`android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java`

- 启动进度文案带上应用名：`启动应用「微博」 · 处理中`（此前无应用名，多应用连点时极易把失败归因到错误的应用）。
- 启动失败若命中 `PROCESS_START_TIMEOUT` / `ENGINE_CONSTRUCTION_TIMEOUT` / `WORKER_BIND_TIMEOUT`，弹
  「应用没有启动：『X』的启动在 2 分钟内没有完成。容器同时运行的应用越多，启动越慢…」+ **重试** / **先停掉其它应用** / 取消。
- 与 §3 的槽位对话框同一条原则：**预算与策略是容器的，出路必须给用户**。

## 8. 这一轮的边界（不要外推）

- 微博只到《用户协议》页；「同意并继续」之后因 6.2 崩溃，**登录完全未验证**。
- `phase=APPLICATION` 超时的失败样本是**大众点评**（不是微博）：说明「多应用并发时某个 guest 起不到 READY」是**通用**现象，
  但**哪些应用在多少并发下会超时，需要逐应用实测**，不能由 1 个样本断言。
- v58 的对话框**未在真机验证**（改的是 UI 分支，需要再等到一次真实超时才能看到它）。

## 9. 第 2 批（快手 / 高德地图）与广播 extras 族

第 2 批起改为「清槽位 → 单应用 → 隐私页 → 首页 → 登录入口」逐应用推进，每步只记三件套
（`topResumedActivity` / `crash` 缓冲 / `admission-blocked`）：

- **快手**：通过。到「欢迎使用快手」隐私页 → 点同意 → feed（截图有「点击登录」徽标），
  `com.smile.gifmaker` + `:push_v3` 全程存活，crash 空、栅栏 0。
- **高德地图**：v65 在点同意后崩于**我方**广播门面——蓝牙设备发现广播带
  `android.bluetooth.device.extra.UUID_LE`（`Parcelable[]`），`RuntimeSystemBroadcasts.systemBundle`
  直接 `throw IllegalArgumentException`，抛点在 `RuntimeReceiverProxy.onReceive` 派发体内 ⇒ 客体 FATAL。
  v66 把 extras 语义从「校验即抛」改为「丢弃该键 + 记 `BROADCAST_EXTRA_DROPPED`」并补 `Parcelable[]`，
  高德**首页可达**（`查找地点、公交、地铁` / 首页 / 探索 / 对话 / 打车 / 未登录），三进程存活、crash 空、栅栏 0。

完整栈、判据、边界见 [batch2-kuai-amap-20261113.md](batch2-kuai-amap-20261113.md)。
这条族与 §6 的广播族同源（都是「门面在平台不失败处抛异常」），但落点不同：§6 是 `PendingResult` 生命周期，
这里是 extras 的形状表达力（h31）。

## 10. 第 2 批续（大众点评 / 网易云音乐）

- **大众点评**：**可用**，但要重试一次。首次冷启动卡在 `phase=APPLICATION` 到 120s 预算到期
  （UI：「应用没有启动」）；线程栈证明是**应用自己的两把锁 ABBA 死锁**
  （main 持 `com.dianping.app.o.f` 求 `o.e`，`pool-4-thread-2` 持 `o.e` 求 `o.f`），
  不是门面缺陷、不是槽位容量、不是风控。点「取消」**不清数据**原地重试 → `TaskP1$F0M0C2`，
  `com.dianping.v1` + `:dppushservice` 存活，直达天翼「本机号码一键登录」（掩码 `153****8553`）；
  协议弹窗→同意→滑块（`input swipe` 通过）→**图片字符验证码**（人机，交用户）。
  见 [batch2-dianping-20261113.md](batch2-dianping-20261113.md)。
- **网易云音乐**：**不可用，结构性**。启动在 `StartupTask` 线程崩于
  `java.lang.SecurityException: callingPackage does not match UID`
  （`IStorageManager$Stub$Proxy.getVolumeList` ← `StorageManager.getVolumeList` ←
  `Environment.getExternalStorageDirectory` ← `FileManagerImp.register`）。
  抛出者是 `system_server`：系统服务在核对「包名 ↔ uid」一致性，客体以宿主 uid 10542 运行、自称
  `com.netease.cloudmusic`，必然不一致。仓库里本有 `RuntimeStorageIdentity` 在 Binder 边界重写
  `callingPackage`，但本轮**没有生效**（`TripoderStorageIdentity` 零日志），最可能是框架自身调用点
  未去优化（`preexistingCompiledFramesCovered:false`）。见
  [batch2-netease-storage-identity-20261113.md](batch2-netease-storage-identity-20261113.md)。

一句话差别：大众点评是**应用内部竞态**（容器只是提供了调度，重试即过）；
网易云音乐是**平台身份校验**（C1 事实第一次由 AOSP 服务执行，必须改投影才过）。


## 11. 第 3 批起（微博登录闭环 + 新失效族）

- **微博**：从「登录链路被门面挡住」推进到**可用（登录闭环）**。入口不在首页/「我」页（未登录时「我」页显示的是
  游客名 `用户7169367851`，很容易被误读成已登录），而在 我 → 齿轮 → 账号管理 → 添加账号 → `fl_login_root`。
  短信验证码由运行时侧直接读机内短信取得（`content://sms/inbox`），登录后落地一个**新注册账号**
  （该号未注册过微博，页面提示「未注册手机号验证通过后将自动注册」）。见 [weibo-login-20261113.md](weibo-login-20261113.md)。
- **顺带定位到一个新失效族（比登录本身更重要）**：对容器内**带 EditText 的页面**执行 `uiautomator dump` 会把客体打崩。
  链是 平台无障碍预取 → `TextView.onInitializeAccessibilityNodeInfoInternal` → `canPaste()` →
  `ClipboardManager.hasPrimaryClip()` → `system_server` 的 `ClipboardService.clipboardAccessAllowed` →
  `AppOpsManager.checkPackage(10542, "com.sina.weibo")` → `SecurityException: Package com.sina.weibo does not belong to 10542`
  → 客体主线程 FATAL（微博登录页 4 次复现）。
  这既是**驱动方式问题**（以后这类页面只能 `screencap` + 坐标 tap），也是**结构性缺陷**：
  任何读屏/无障碍服务打开这个应用都会把它打崩。修法候选是「剪贴板门面化」（每 space 私有剪贴板），
  判据写在证据文档 §4 末。
- 两个交互事实顺带确认：`input text` 写长数字串会截断（要 3–4 位分段并校验）；
  启动「已有未确认 Activity 记录」的应用会被 UI 拒绘为
  「应用没有启动 / 操作没有完成（`APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED`）」，
  强停 q6/q7 + 连点两次「重新连接」即可清台账。

## 12. 第 3 批（美团 / 拼多多）

- **美团**：可用（已登录）。首页 feed 完整（外卖/团购/酒店民宿/闪购/看病买药、老友回归1分购、双拼饭 ¥13.3），
  「我的」页是**完整登录态**：`zoQ666342288` / 实名待认证 / 普通会员成长值 80/500、
  收藏/浏览记录/红包卡券/美团币、订单（全部/待收货/待评价/退款售后）、
  钱包（美团借钱 12.5万额度、月付、我的卡额度 9.98万、可用额度 7000元）、开发票/入驻美团/我的店铺。
  见 `meituan-profile-logged-in-20261113.png`。**踩坑**：底部导航中间的悬浮「小团」气泡会吃掉 `我的` 的点击，
  第一次点落到商家详情页；要打「我的」最右侧安全区（约 x=1063）或先等气泡消失。
- **拼多多**：可用（登录闭环）。手机号 + 短信验证码，落入「个人中心」完整登录态（`153****8553`）。
  见 [pinduoduo-login-20261113.md](pinduoduo-login-20261113.md)。
- **剪贴板崩溃族有了第二个样本，且分界更清楚**：拼多多**登录页**（无 EditText）dump 正常，
  一进**手机号页**（有 EditText）dump 即 FATAL
  `SecurityException: Package com.xunmeng.pinduoduo does not belong to 10542`。
  ⇒ 判据是「目标页有没有输入框」，不是「哪个应用」。操作口径因此定稿（见 §12 上一条文档的 §5）：
  无输入框用 dump 驱动，有输入框只用 screencap + 坐标 + 分段输入。

## 13. 第 4 批（v67：快手 / 平安证券 / 王者荣耀）

构建从 `build-facade-v66` 走到 `build-facade-v67`。只改了一处运行时判定：
`RuntimeExternalServices` 对 oneway（`flags!=0`）的 transact **不再抛**
`EXTERNAL_SERVICE_REQUEST_MUST_BE_SYNCHRONOUS`，改为原样转发并记一次
`EXTERNAL_SERVICE_REQUEST_ASYNC_PASSED` 降级码。详见 [v67](v67-async-external-service-20261113.md)。

| 应用 | 结果 | 判据 |
|---|---|---|
| **快手** `com.smile.gifmaker` | **可用（feed）** | v66 上「同意并继续」必崩（我方断言，栈落到 `AppOpsManagerNoteOpBatching` 的 HandlerThread）；v67 后 crash=0、`TaskP3$F0M1C2` 前台、feed 完整（30.1万赞/2.5万评论/分享/全屏观看）。登录入口未定位（左上「登录」胶囊与左下「点击登录」两次坐标点击无反应） |
| **平安证券** `com.hundsun.winner.pazq` | **未启动（客体原生崩溃）** | `SIGSEGV` 写 `0x16`，栈 `#00 art::JNI SetObjectField` + `#01 <anonymous:JIT>`，**没有一帧 `dev.tripoder.runtime.*`**；Provider 全部初始化成功后才崩 ⇒ 客体自身 JNI 用错 jobject/jfieldID，不是门面缺陷 |
| **王者荣耀** `com.tencent.tmgp.sgame` | **可用** | `SGameActivity` + `:xg_vip_service` 双进程；渲染到开区页，账号条已是「澎湃 微信440区 火鹰船长」，可直接「开始游戏」 |

两条新口径：

1. **oneway 不得抛**：平台对 oneway transact 从不校验线程、从不失败；我们把「需要回写 Parcel」当成平台规则抛出去，
   结果是平台自己的 `Handler` 线程把整个客体打死。这是 h27 那条不变量的第 N 个实例。
2. **dump 有两个互不相同的失败条件**：页面有 `EditText` → 剪贴板 `checkPackage` 把客体杀掉；
   页面持续动画（短视频 feed、游戏）→ 只报 `could not get idle state.`，客体存活。
   后者不要重试 dump，改截图 + 坐标驱动。
