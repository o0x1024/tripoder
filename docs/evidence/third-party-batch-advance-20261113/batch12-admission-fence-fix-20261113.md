# 第 12 批附二：准入门栅栏的修法与 A/B（v84 → v86）

日期：2026-11-13
前置：`batch12-admission-fence-row-20261113.md`（行定位与两处缺陷）

## 1. 改了什么

`RuntimeEffectRecoveryProof.inert()` 里那条 `prepared` 查询，原来是「detail 在清单里 **且** 命令属于 launch 族」。
现在按证据强度分层：

| 细节 | 含义 | 是否看命令列 |
|---|---|---|
| `COMPONENT_PREPARATION_UNCONFIRMED`、`LAUNCH_PREPARATION_UNCONFIRMED`、`ACTIVITY_DELIVERY_PROCESS_DIED`、`ACTIVITY_DELIVERY_RECOVERED_UNKNOWN` | 只可能由启动路径写出，描述一次「什么都没交付」的准备 | **不看** |
| `BROKER_RECOVERY` | 既可能是 launch，也可能是 install/stop | 仍要求 launch 族命令 |

其余条件一字未动：仍需 `inertEntries`（该操作的全部 activity 条目可证明惰性）、
无活会话、以及「其后有一次成功 stop」或「至少一个 DEAD 会话」。

理由：客体自发起（无命令守卫）的操作，账本命令列是占位符 `legacy-or-internal`，
任何 `command IN (...)` 过滤都不认它 —— 于是「一次失败的准备」被升级成「该包永久不可启动」。
把命令列从「唯一判据」降级为「歧义细节的附加判据」，是在不放松交付证据的前提下消除这个死角。

同时（v84 起）：`COMPONENT_PREPARATION_UNCONFIRMED` 提为常量并由同一常量构造白名单，
写入方 `RuntimeBroker:307` 也引用它，消除写入/读取两侧的字面量漂移。

## 2. A/B

同一台机器、同一条路径（force-stop q6/q7 → Workbench → 两次「重新连接」→ 点闲鱼行 → 读 TripoderRecovery）：

| 镜像 | 结果 | `admission-blocked` | crash |
|---|---|---|---|
| v82（基线） | 弹「应用没有启动 / 有一个更早的操作没有确认结果」 | 1 | 0 |
| v84（只补 detail 词汇） | **仍被挡**，prior 同一行 | 1 | 0 |
| v86（分层证明） | **启动成功**，top=`TaskP0$F0M2C0` | **0** | 0 |

v86 的日志形态也变了：`inert-delete-unknown …;new=legacy-or-internal` 连续出现 6 次，
说明客体自发起的调用现在会带着占位命令正常进入 `accepting()` 并被放行，不再被自己挡住。

## 3. 判据（以后遇到同类现象怎么读）

`admission-blocked` 行末尾三段就是判决书：

```
…;detail=<写入方给的细节>;priorCommand=<账本命令列>;priorDispatch=<MAY_HAVE_DISPATCHED|RETIRED_EXECUTION_ACK:…>;sessions=<同包会话状态计数>
```

- `priorCommand=legacy-or-internal` ⇒ 该行来自客体自发起路径，**不能**依赖命令过滤；
- `sessions` 里没有任何 `DEAD`/`FAILED` ⇒ 真的可能有活进程，此时拒绝是对的；
- `priorDispatch=MAY_HAVE_DISPATCHED` ⇒ 不能用「已确认延续」这条捷径，只能走惰性证明。

## 4. 边界

- 只验了闲鱼一例；**未做回归扫描**（淘宝/携程/小红书/微博等是否因放宽而出现新的放行不当）。
  下一轮第一件事应是宽回归：至少 4 个应用各冷启一次，确认 `admission-blocked` 保持 0 且 crash 保持 0。
- 未读 DB，因此 `inertEntries` 是否真的在本例为真，只能由「结果放行」反推；若某天出现「放行后同一操作又被投递」，说明惰性判据有洞。
- 分层只覆盖 launch 细节。`PROVIDER_*`、`TASK_REMOVAL_UNCONFIRMED`、`HOOK_WORKER_OUTCOME_UNCONFIRMED` 等
  仍在各自的白名单里，未复核是否也存在占位命令死角（`gap-fence-other-exits` 仍是开放项）。

## 5. 复现

```
bash /tmp/build86.sh                                  # 构建 v86（三个 platform 参数缺一不可）
python3 /tmp/install86_test.py                        # 装机 + 冷启闲鱼 + 读 admission-blocked
```
