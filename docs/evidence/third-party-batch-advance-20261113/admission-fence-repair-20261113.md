# 准入栅栏：定位到账本行并补上 abort 终态（v61 → v63）

承接 [admission-fence-20261113.md](admission-fence-20261113.md) 的定性分析。那一轮只知道「栅栏是进程级的、被我们自己的降级点燃」；本轮把它定位到**具体账本行**，并按平台语义补上了终态出口，真机验证栅栏消失。

## 1. 判据：栅栏到底锁在哪一行

v61 装机后，用 `adb logcat -s TripoderRecovery:V` 抓 `admission-blocked` 行（`RuntimeMutationGuard.accepting()` 在拒绝前打印，含 prior / priorScope / detail / sessions 四段）。微博「同意并登录」后：

```
admission-blocked command=legacy-or-internal
scope=space/default/package/com.sina.weibo
prior=33ed78cd-f381-4c4d-8abc-b74a2300b3f7
priorScope=space/default/package/com.sina.weibo
caller=7:default14:com.sina.weibo36:fd3cd2fb-…21:com.sina.weibo:remote64:f23c0f8b…
guard=none
detail=PROCESS_DIED_BEFORE_DELIVERY_ACK
sessions=ALLOCATED:1,DEAD:8,RUNNING:1,
```

两件事被这一行说清楚：

1. **fence 的来源是广播投递账本**，不是安装/启动/删除。`detail=PROCESS_DIED_BEFORE_DELIVERY_ACK` 来自 `RuntimeBroker.dead()`：进程死亡时，凡是 `deliveries` 里仍 RUNNING 的投递，一律 `markUnknown`。
2. **`sessions=DEAD:8`**：这个包已经累积了 8 个死掉的后代进程。每死一个进程就可能多一条 UNKNOWN，而 UNKNOWN 只有当人显式 reconcile 才会消失——批量推进里我们每轮都 `force-stop`，等于每轮都在制造栅栏。

随后主进程崩在：

```
android.content.ActivityNotFoundException: ACTIVITY_ROUTE_REFUSED:RECOVERY_SCOPE_UNKNOWN:cc7a2aba-…
  at dev.tripoder.runtime.RuntimeEngine.routeRefusal(RuntimeEngine.java:26)
  at com.sina.weibo.ScreenOrientationBaseActivity.startActivityForResult(ScreenOrientationBaseActivity.java:1293)
  at com.sina.weibo.h$1.run(BackPressCheckHelper.java:89)
```

即上一轮 h27 的负结果在这里第二次重现：换成「平台形状的异常」也救不了——平台在可解析目标上永不抛 ANFE，客体在那个调用点没有 catch。

## 2. 机制：把「从未投递」记成了「结果未知」

`broadcast_deliveries` 建行时 `instance=''`，只有真正的 claim（`BroadcastLedger.claim`）才写入 `instance`。也就是说：

| 行状态 | 客体代码跑过吗 | 正确终态 |
|---|---|---|
| `state='PREPARED'` / `instance=''` | **没有**，从未交付 | FAILED（`BROADCAST_NEVER_DELIVERED`） |
| `instance!=''`（RUNNING/RETURNED…） | 可能跑过，结果未知 | UNKNOWN（需要显式决定） |

而改造前的实现把两类一律写成 UNKNOWN，三处：

- `RuntimeBroker.dead()`：投递未 ack → `markUnknown(PROCESS_DIED_BEFORE_DELIVERY_ACK)`
- `BroadcastLedger.dead()` / `recover()`：目标进程死亡或 authority 启动 → 所有非终态行 `markUnknown(BROADCAST_TARGET_DIED_DELIVERY_UNCONFIRMED)`
- `BroadcastLedger.closePending()`：注册关闭 → `markUnknown(BROADCAST_REGISTRATION_CLOSED_BEFORE_CLAIM)`

第三条的码名自己就写着「before claim」——代码已经知道它从未投递，却仍按「未知」记账。这就是 h26 的机制根：**越认真降级、越频繁重启，UNKNOWN 越多，最后整个包作用域被永久拒。**

## 3. 修：按 claim 判终态 + 历史行 reconcile

v62/v63（`android/runtime/src/dev/tripoder/runtime/`）：

1. `RuntimeBroker.dead()`：`broadcasts.claimed(op)` 为真才 `markUnknown`，否则 `fail(BROADCAST_NEVER_DELIVERED)`，trace 记为 `delivery-not-delivered`。
2. `BroadcastLedger.dead()` / `recover()`：查询带上 `instance`，按是否 claim 分叉（`settle()` 统一：只结算 RUNNING 的操作）。
3. `BroadcastLedger.closePending()`：`PREPARED`（必未 claim）→ 行置 `REJECTED` + `fail(BROADCAST_REGISTRATION_CLOSED_BEFORE_CLAIM)`。
4. `recover()` 末尾补**历史行**修复：`state='UNKNOWN' AND instance=''` 的投递行、以及 `detail='BROADCAST_REGISTRATION_CLOSED_BEFORE_CLAIM'` 的操作，用 `contract.reconcile(…, FAILED, BROADCAST_NEVER_DELIVERED, …)` 关掉。这是既有 `reconcile` 通道的第一次实际使用：证据（无 claim）就在行里，不需要猜。

## 4. 验证（设备 d861a4fa）

| 版本 | 观察 | 结论 |
|---|---|---|
| v61 | `detail=PROCESS_DIED_BEFORE_DELIVERY_ACK`，主进程 FATAL | 定位到投递账本 |
| v62 | 旧行消失，**新**行 `detail=BROADCAST_REGISTRATION_CLOSED_BEFORE_CLAIM` | 同一类缺陷的第二个出口；也证明 v62 的修复确实生效 |
| v63 | authority 启动日志 `never-delivered-repaired operation=cc7a2aba-cafe-4e92-b148-82af212d82ab-10;ticket=4c5291…`；随后 `admission-blocked` **计数 0**；crash 缓冲**为空**；微博 3 进程存活（4112 主 / 4417 :remote / 5890 :image）；top 停在 `TaskP1$F0M2C2`（微博首页 `home_view_pager`） | 栅栏消失、`ACTIVITY_ROUTE_REFUSED` 崩溃消失 |

构建：`android/product/build-facade-v63/tripoder-q6.apk`，sha256 `b7868a025108e9e0e45b582e741b8179a41a88e3f9f0241180365b075f0f2324`。

> v62 的 APK 是 `86c901980457d2bfb0f0b6253419ca693461ddf9a50287304f4d275af5d2bfd6`，只做了投递侧分叉，装机后暴露出注册关闭侧的同源缺陷；v63 补上后通过。

## 5. 边界（不粉饰）

- **微博登录闭环仍未完成**：本轮没有走到「登录 → 本机号码一键登录 → 同意并登录」的成功终点。v63 之后崩溃与栅栏都已消失，但驱动 UI 时首页给的是信息流内的签到卡，`登录` 文本不在 dump 里（`uiautomator` 只拿到 `main_tab_view_pager` / `home_view_pager` / `ll_check_in_container`），因此**「不再被栅栏打断」已验证，「登录成功」未验证**。
- 只在本设备（Android 16 / MIUI，容器包 `dev.tripoder.q6`）与微博一个应用上验证。其它应用的 UNKNOWN 行来源（provider 回调、activity 结果、hook worker）**未**做同样分叉，仍可能各自制造栅栏；`RuntimeBroker:307 COMPONENT_PREPARATION_UNCONFIRMED`、`ProviderLedger:74 PROVIDER_PROCESS_DIED_DURING_CALLBACK` 是下一批候选。
- 历史行修复的上界是「无 claim」这一类；claim 过的 UNKNOWN 仍然需要显式决策，本轮没有提供产品化入口（仍在 Workbench 之外）。

## 6. 复现

```bash
adb -s d861a4fa install --no-incremental -r android/product/build-facade-v63/tripoder-q6.apk
adb -s d861a4fa shell am force-stop dev.tripoder.q6
adb -s d861a4fa shell am force-stop dev.tripoder.q7
adb -s d861a4fa logcat -b all -c
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
python3 android/scripts/guest_ui.py tap "重新连接"      # 装完新包通常要两次
python3 android/scripts/guest_ui.py tap "微博" --scroll
adb -s d861a4fa logcat -d -s TripoderRecovery:V | grep -E "never-delivered-repaired|admission-blocked"
```

期望：出现 `never-delivered-repaired`，`admission-blocked` 计数 0。

## 7. 对「所有第三方应用」这件事的意义

批量推进的既有工作流（每轮 `force-stop` 清槽位 + 频繁装包）**本身**就是栅栏发生器：进程一死，未 claim 的投递就被记成 UNKNOWN，然后该包后续所有框架调用被拒，表现为「应用凭空崩溃」。这条修好之后，「一个应用崩了」与「应用本身不兼容」才第一次可区分——这是 121 个包逐项推进的前提条件，而不是附带优化。
