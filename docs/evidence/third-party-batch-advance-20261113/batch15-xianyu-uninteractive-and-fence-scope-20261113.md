# 第 15 批：闲鱼登录页「可读不可交互」+ 准入栅栏作用域=包级

日期：2026-11-13（设备 d861a4fa，机上 build-fence-v87）

本批回答上一轮留下的两个悬而未决问题：①解锁后闲鱼的原生登录入口到底能不能输入；②force-stop 造出的未确认行会不会把后续所有应用都挡死。

## 1. 解锁后的现场读数

| 读数 | 值 |
|---|---|
| `isKeyguardShowing` | false |
| `mDreamingLockscreen` | false |
| `mFocusedWindow` | `Window{f8f128 u0 com.taobao.idlefish/com.taobao.idlefish.custom.FishUserLoginActivity}` |
| `topResumedActivity` | `dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP0$F0M2C3 t16633` |

⇒ 设备已解锁、焦点在客体登录页窗口上，上一轮的「锁屏阻塞」消失。

## 2. 闲鱼原生登录页同样不可交互（关键负结果）

在这份现场上按上一轮记录的 bounds 做了一次完整盲操作：

1. `screencap` → **23,419 B 全黑**（只有左上应用图标与右上「100」角标，无任何输入框/按钮可见）；
2. `input tap 600 926`（手机号框中心，bounds 来自第一轮 dump）；
3. `input text` 分三段写入 `153` / `2373` / `8553`；
4. `input tap 1004 1095`（获取验证码）；
5. 等 9 s 后查 `content://sms/inbox`（`date > now-30s`）。

结果：**无新短信、无 FATAL、crash 缓冲为空、guest 主进程 pid 17053 健在、top 未变**。

判据表：

| 观测 | 含义 |
|---|---|
| 整页 23,419 B（与 U4 黑页 23,272/23,287 B 同量级） | `FishUserLoginActivity` 的内容同样没有被绘制 |
| 点击 + 输入后无短信、无崩溃、进程存活 | 触摸与文本输入都没有落到控件上（不是「点错」也不是「必崩的 toast 路径」） |
| dump 仍能读到 5 个控件文本与 bounds | 视图树存在但未合成到屏幕 ⇒ **dump 可读 ≠ 可交互** |

⇒ 修正 h55：闲鱼的登录闭环不能靠「换到原生 `FishUserLoginActivity`」解决。入口可达，但不可交互，属 U4 内容层不绘制同一族缺陷（h54）。

## 3. force-stop 造出未确认行，再启动被栅栏挡住

`am force-stop dev.tripoder.q6` 之后从 Workbench 点「闲鱼」，弹的是准入栅栏原文：

> 应用没有启动 / 有一个更早的操作没有确认结果，暂时挡住了这次操作。请稍后重试 / 知道了

同时 `dumpsys input` 的 `FocusedWindows` 里出现 `com.taobao.idlefish/com.taobao.idlefish.recovery.RecoveryActivity`（我们 recovery 进程起的窗口）。

按既有恢复配方（force-stop q6+q7 → 重开 Workbench → 两次「重新连接」）执行后，**仍被挡**：Workbench 顶部同时显示「启动应用「闲鱼」· 结果未知：操作没有完成（ACTIVITY_DELIVERY_PROCESS_DIED）。请先核对实际状态，勿重复提交」，列表内闲鱼条目为「● 后台运行」。

⇒ 与 gotcha `fence-survives-force-stop` 一致：`ACTIVITY_DELIVERY_PROCESS_DIED` 这类行目前没有放行通道，force-stop 自己就会造出它。

## 4. 栅栏作用域 = 包级（批量推进不受阻）

同一轮里 Workbench 列表的滑动被识别成点击，**误触启动了 QQ**：`top=TaskP0$F1M1C0`，隐私协议弹窗正常。

| 读数 | 值 |
|---|---|
| screencap | 183,784 B（正常画面） |
| dump 文本 | 「用户协议及隐私政策概要 / 不同意 / 同意」全文可读 |
| crash 缓冲 | 空 |
| 进程 | `com.tencent.mobileqq` 16022（uid 10542）+ `:MSF` 17174 |

⇒ 闲鱼被挡的那条栅栏**没有**影响其它包。批量推进仍然可行，被挡的只是「死过一次且留下未确认行」的那个包作用域。

## 5. QQ 作为渲染对照

QQ 在容器内 183,784 B 正常渲染、控件可读、点「同意」（精确 bounds 828,1747）生效（随后 QQ 退到后台，回 Workbench，进程存活、无 FATAL）。

⇒ 同一容器、同一 uid、同一渲染路径下 QQ 正常 ⇒ 闲鱼的黑屏不是容器通用的窗口/合成缺陷，而是 U4 内核（h54）专属。

## 6. 复现与脚本

- `/tmp/xy_login1.sh`：盲点手机号框 + 分段 input text + 点获取验证码 + 查短信
- `/tmp/xy_chk1.sh`：ps / crash / sms 三读数
- `/tmp/xy_flow3.sh`：清槽位 → 重连 → 启动闲鱼 → 依次点同意/我知道了/我的 → 盲输入
- `/tmp/fence_scope.sh`：点掉栅栏弹窗 → 滚动找其它应用 → 启动 → 读 top/ps
- `/tmp/tapfind.py`（子串匹配）与 `/tmp/tap_exact.py`（精确匹配）：dump 后按文本点击

**注意**：`tapfind.py` 的子串匹配会命中正文段落（本轮「同意」命中了协议正文里的「同意」二字，点到 604,1336 而不是按钮），点按钮必须用 `tap_exact.py`。

## 7. 边界

- 本轮只验证了闲鱼这一个 U4 页与 QQ 这一条对照；「U4 内容层不绘制」在其它 U4 应用（淘宝内核不同，未测）上是否同样成立未验证。
- `FishUserLoginActivity` 的黑屏读数取自该窗口处于前台且焦点正常的时刻，未区分「窗口自身不绘制」与「被上层 U4 view 覆盖成黑」两种机制。
- 栅栏作用域结论来自一次误触样本（QQ），其它被挡包是否同样只挡自身未测。

## 8. 下一步

1. 给 `RuntimeEffectRecoveryProof` 的 `ACTIVITY_DELIVERY_PROCESS_DIED` 开一条放行通道（与 v86 的 launch-only 细节同思路），否则每 force-stop 一次就永久锁死一个包。
2. 闲鱼登录闭环要么等 U4 内容层的 window 绑定修好（客体探针路线），要么改走「其它登录方式」之外的原生入口（本轮证明原生入口同样不绘制，暂不可行）。
3. 批量推进继续，但每次启动新应用前不要 force-stop 正在运行的客体，改用 Workbench 内切换，避免自造栅栏。

## 9. 追加（同日 22:23–22:24）：栅栏否决项不是 detail，而是 sessions 的 RUNNING

复现时抓到了栅栏的完整行（`logcat -s TripoderRecovery:V`）：

```
admission-blocked command=prepareApplicationLaunch;
scope=space/default/package/com.taobao.idlefish;
prior=c718b7ad-a647-4062-be85-49525d4fde46; priorScope=space/default/package/com.taobao.idlefish;
caller=trusted-workbench; guard=prepareApplicationLaunch;
detail=COMPONENT_PREPARATION_UNCONFIRMED; priorCommand=legacy-or-internal;
priorDispatch=RETIRED_EXECUTION_ACK:f6ef3bf0-589f-4981-991e-3bc87db39a8b;
sessions=DEAD:24,RUNNING:2
```

判读：

- `detail=COMPONENT_PREPARATION_UNCONFIRMED` 与 `priorCommand=legacy-or-internal` **正是 v86 已经放行的那一类**（`RuntimeEffectRecoveryProof:16` 的 `LAUNCH_ONLY_DETAILS` 已含该 detail，v86 实测闲鱼能起）⇒ 本次否决不在 detail 层。
- 剩下唯一没被满足的是 liveness 子句：**`sessions=DEAD:24,RUNNING:2`**。该 scope 下仍有两个 RUNNING 会话，证明要求「无活会话」，于是整条证明不成立。

这两个 RUNNING 会话来自 **force-stop 没清干净的客体子进程**：

| pid | 进程 | uid |
|---|---|---|
| 14492 | com.taobao.idlefish:channel | 10542（容器） |
| 14713 | com.taobao.idlefish:windowChannel | 10542（容器） |

主进程 17053 已被 force-stop 杀掉，这两个子进程存活 ⇒ 会话仍为 RUNNING ⇒ 栅栏永远点不着放行。

**操作配方（已验证）**：`am force-stop dev.tripoder.q6` 连做两次（每次间隔 3 s）可以把这批遗留子进程清掉（ps 前后对比：14492/14713 消失，原生 uid 10328 的闲鱼进程不受影响）。

清除遗留子进程后再从 Workbench 点「闲鱼」：本轮**连 Tripoder 引擎日志都没有产生**（`logcat -d | grep Tripoder` 为空）、也没有新客体进程，`top` 停在 Workbench ⇒ 这次不是栅栏拒绝，而是点击没有进入引擎（与 gotcha `stuck-input-focus-blocks-text-entry` 同族的焦点/命中问题），需下一轮单独复核。

### 结论修正

- 第 3 节「ACTIVITY_DELIVERY_PROCESS_DIED 没有放行通道」需要更精确：真正没有放行通道的是**「scope 下还有活会话」这一条 liveness 判据**，而活会话可以由 force-stop 的遗留子进程长期维持。
- 产品侧两条可能的修法：①force-stop/退休时把该 scope 的会话与其子进程一起收敛（而不是只看主进程）；②`EffectRecoveryProof` 在判活时区分「会话主人已死、仅子进程存活」的情形。
