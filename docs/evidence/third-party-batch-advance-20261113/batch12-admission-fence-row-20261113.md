# 第 12 批附：准入栅栏的账本行定位（闲鱼「应用没有启动」的真正原因）

日期：2026-11-13
镜像：`build-fence-v84`（补 detail 词汇）、`build-fence-v85`（+ 诊断字段）——机上当前为 v85
前置：`batch12-xianyu-taobao-ctrip-20261113.md`（h47 定性：闲鱼被栅栏挡住，非兼容性）

## 1. 目的

h47 只证明「闲鱼被准入栅栏挡住」。本片要回答三件事：

1. 挡住它的是**哪一行**账本；
2. 为什么 `force-stop + reconnect` 之后这一行还活着；
3. 它凭什么被判成「不可放行」。

## 2. 手法

清 main/system/crash 三个缓冲 → force-stop `dev.tripoder.q6`+`q7` → 重开 Workbench → 两次「重新连接」→
点闲鱼行 → 读 `logcat -d -s TripoderRecovery:V`。`diagnose()` 在抛拒绝前一次性打印该行的 detail、
命令、dispatch 与同包会话状态，因此不需要读 DB（宿主非 debuggable、设备无 sqlite3）。

脚本：`/tmp/fence13.py`（v82 上第一次抓到行）；`/tmp/install84_test.py`、`/tmp/install85_test.py`（装机 + A/B）。

## 3. 抓到的行（原文，v85）

```
W TripoderRecovery: admission-blocked command=prepareApplicationLaunch
  ;scope=space/default/package/com.taobao.idlefish
  ;prior=c718b7ad-a647-4062-be85-49525d4fde46
  ;priorScope=space/default/package/com.taobao.idlefish
  ;caller=trusted-workbench
  ;guard=prepareApplicationLaunch
  ;detail=COMPONENT_PREPARATION_UNCONFIRMED
  ;priorCommand=legacy-or-internal
  ;priorDispatch=MAY_HAVE_DISPATCHED
  ;sessions=DEAD:4,
```

同一次 accept 里还有一条被**正确放行**的行，正好构成对照：

```
I TripoderRecovery: inert-delete-unknown operation=4375724f-bdc6-4a09-ae6f-a61489370130;scope=*;new=prepareApplicationLaunch
```

即：`deleteApplication` 的 UNKNOWN 行因为「只删不加、且无未关闭 mutation」被跳过；而闲鱼这一行
（`COMPONENT_PREPARATION_UNCONFIRMED`）落到了最后的 `throw AdmissionRejected`。

## 4. 判决路径（`RuntimeMutationGuard.accepting()`，逐条）

新命令的 guard 是 `prepareApplicationLaunch`，它的 scope 与 prior 的 effectScope **完全相同**，
因此进入 `scope.equals(effectScope)` 分支，逐条判定：

| 分支 | 条件 | 本例 |
|---|---|---|
| fresh start | `guard.explicitStart && permitsFreshStart(...)` | explicitStart 为 false（工作台启动不带显式 start 令牌） |
| 已确认延续 | `permitsAcknowledgedContinuation(...)` | dispatch 是 `MAY_HAVE_DISPATCHED`，不是 `RETIRED_EXECUTION_ACK:` ⇒ 直接出局 |
| 只读 hook 延续 | `guard.command.startsWith("hook:")` | 命令是 launch，不适用 |
| 用户显式删除 | `guard.command=="deleteApplication"` | 不是 |
| **否则** | 打印 `admission-blocked` 并抛 | ← 命中 |

于是问题收敛为：`permitsFreshStart()` 为什么 false。它走 `inert(...,false,true)`，核心是这段 SQL：

```java
String prepared="SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id
  WHERE original.id=? AND original.state='UNKNOWN'
    AND original.detail IN ("+LAUNCH_DETAILS+")
    AND own.scope=? AND own.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch')"
  +inertEntries+live;
```

本例中 `state='UNKNOWN'` ✓、`own.scope` 相等 ✓、`sessions=DEAD:4` 满足 `live` 与「至少一个 DEAD 会话」✓，
**卡住的是两个字符串条件**。

## 5. 两个缺陷（都是词汇表不一致，不是逻辑推理错误）

### 5.1 写的人写了一个读的人不认识的字面量

`RuntimeBroker:307` 在自增 Activity 路由抛异常时写 `"COMPONENT_PREPARATION_UNCONFIRMED"`；
而 `prepared` 的 detail 白名单最初是
`LAUNCH_PREPARATION_UNCONFIRMED / BROKER_RECOVERY / ACTIVITY_DELIVERY_PROCESS_DIED / ACTIVITY_DELIVERY_RECOVERED_UNKNOWN`，
**不含它**。⇒ 这类行永远无法被证明惰性。

v84 已修：把字面量提成 `RuntimeEffectRecoveryProof.COMPONENT_PREPARATION_UNCONFIRMED` 常量，
`LAUNCH_DETAILS` 引用它，写入方也改用同一常量（避免再次漂移）。

### 5.2 真正的否决项：无守卫路径的占位命令

v84 装上后 A/B 结果：**仍然被挡，prior 还是同一行**。v85 把诊断字段补全后看到
`priorCommand=legacy-or-internal`。

来源：`accepting()` 里 `guard==null` 时命令被写成 `"legacy-or-internal"`。guard 为 null 的情形是
**客体自己发起的调用**（不是工作台命令包裹的那一层），`route()` 正是这样被调度到的。
于是该行同时具备：正确的 detail、正确的 scope、足够的 DEAD 会话证据，
但因为命令是占位符，`own.command IN ('prepareApplicationLaunch',…)` **恒 false**。

⇒ **占位符命令是任何恢复证明的死角。** 这也解释了 h47 的「force-stop 之后仍点火」：
`recover()` 只把行状态改成 UNKNOWN，不改变它的命令列；只要命令列是占位符，重启多少次都不会被放行。

## 6. 结构修法（下一轮）

不要继续往 `IN (...)` 里加字面量——那是同一种漂移的重复。按证据强度分层：

1. **launch-only 细节**（`COMPONENT_PREPARATION_UNCONFIRMED`、`LAUNCH_PREPARATION_UNCONFIRMED`、
   `ACTIVITY_DELIVERY_PROCESS_DIED`、`ACTIVITY_DELIVERY_RECOVERED_UNKNOWN`）：这些只可能由启动路径写出，
   因此**不应再看命令列**，只凭 `inertEntries + 无活会话 + 至少一个 DEAD/停止证据` 判定。
2. **歧义细节**（`BROKER_RECOVERY`）：既可能是 launch 也可能是 install/stop，保留 `command IN (launch 族)` 过滤。
3. **顺带**：`accepting()` 在 `guard==null` 时写占位符，使「无守卫路径」的操作在账本里失去自述能力；
   建议改为记录真实来源（如 `caller-identity` 派生的命令名），否则第三个消费方还会踩同一坑。

## 7. 边界

- 只验证了闲鱼一例、一条行（`c718b7ad`）；`sessions=DEAD:4` 说明同包还累积了 4 个 DEAD 会话，
  但本轮没有逐条展开。
- 未读 DB：无法确认该行有无 `activity_launches` 条目，因此**没有**排除 `inertEntries` 也可能为 false。
  若分层修法上线后仍被挡，下一个怀疑对象就是 `inertEntries`。
- v84/v85 只证明「补 detail 不够」，**没有**证明分层修法有效；机上现为 v85（等价 v82 + 诊断字段 + 未生效的 detail 扩充）。
- 未复现「先出现一次真崩溃再触发栅栏」的因果链：`c718b7ad` 当初为何抛异常（是 bind 失败还是 prepare 失败）本轮没有栈可查。

## 8. 复现

```
python3 /tmp/fence13.py            # 抓行（v82）
sed 's/build-fence-v84/build-fence-v85/' /tmp/install84_test.py > /tmp/install85_test.py
python3 /tmp/install85_test.py     # 装机 + 冷启闲鱼 + 读 admission-blocked 全字段
```

构建：`bash /tmp/build84.sh`（参数含 `--platform-libc/--platform-art/--platform-linker`，缺一即只打 argparse 报错）。
