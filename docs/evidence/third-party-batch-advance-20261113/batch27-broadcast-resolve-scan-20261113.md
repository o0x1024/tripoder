# batch27：BOSS 业务链首步 10.5s 阻塞点定位到「隐式广播解析扫全量安装」

本文件接 `batch26`。batch26 只定性到「容器侧 BROADCAST_ROUTE 10.85s 拖死主线程」，本批把
10.5s 精确钉在**一个函数**上，并顺手关掉了两个真实缺陷。

## 一、结论

**`RuntimeBroadcastBroker.resolve()` 对隐式广播（`intent.getPackage()==null`）会遍历该空间里
全部 COMMITTED 安装（本机 53 个），对每一个执行 `broker.apks.get(space,pkg)`。这一趟 10.499s，
且与「有没有接收者」无关——本次发的是 `com.hpbr.bosszhipin.RECEIVER_LOGIN_SUCCESS`，解析结果为空。**

决定性读数（v98，新增空目标路径界标后第一次拿到）：

```
W TripoderWire: ROUTE_EMPTY decode=0ms resolve=10499ms total=10499ms ordered=false \
  action=com.hpbr.bosszhipin.RECEIVER_LOGIN_SUCCESS
W TripoderWire: SLOW BROADCAST_ROUTE 10499ms pid=13501 uid=10542 keys=[ordered, payload, ...]
```

对照：同一进程里命中目标的广播 `ROUTE_STAGES total=10ms resolve=0ms loop=10ms targets=1`、
`ROUTE_EMPTY ... resolve=1ms total=1ms`（`RECEIVER_REFRESH_USER_CENTER_ACTION`）。
**同样是空目标，`resolve` 可以是 1ms 也可以是 10499ms —— 差别在于这一趟是否要把 53 个安装挨个装载。**

链路后果（与 batch26 完全一致，非新问题）：
`resolve 10.5s` → 客体主线程 `onCreate` 里的 `sendOrderedBroadcast` 不返回 →
`Input dispatching timed out (…WelcomeActivity … Waited 5000ms for FocusEvent)` →
`am_kill [user request after error]`。

## 二、这次顺带关掉的两个缺陷

| 版本 | 改动 | 观测 |
|---|---|---|
| v97 | `ExecutionContract.getSession` 去掉 `synchronized`，`sessions` 改 `ConcurrentHashMap` | 该轮 BOSS 四次轮询全部停在 `TaskP1$F1M0C3`（不再被杀）；`SLOW getSession` 一行未打 ⇒ getSession 本身不慢，但它此前在每次 wire 调用入口排队，是**放大器**而非根因 |
| v98 | `route()` 的**空目标早返回**分支补界标 | 拿到 `action` 与 `resolve` 耗时——这是本批唯一的决定性改动 |

## 三、为什么排查了两轮才看到

`route()` 的结构是：

```
stageStart → decode → resolve → [空目标? 直接 return] → ... → 函数末尾打 ROUTE_STAGES
```

v96/v97 只在函数末尾打界标，于是**空目标广播的耗时永远不落日志**：现象是
「`SLOW BROADCAST_ROUTE` 有、`ROUTE_STAGES` 没有」，很容易被读成「函数体没执行、慢在进入之前」。
v96 我正是这么误判的（当时把它归到 `getSession`）。**插桩必须枚举所有 return/throw 出口**，这条已进 gotchas。

同时被推翻的还有 h68（「10.85s 花在逐目标 prepare + 每目标一次事务」）：正常命中的广播
`admit=8~11ms prepare=1ms start=1~2ms`，逐目标写入根本不是瓶颈。

## 四、修法（按代价排序，均未实施）

1. **接收者索引缓存（首选）**：按 `space + 安装代际签名` 缓存
   「pkg → 组件 → 已编译 IntentFilter」，安装集变化（代际签名变）即失效。命中后 `resolve` 从
   O(安装数 × 装载) 降到 O(索引查找)。语义不变——只是把「每次都重新装载 53 个安装」变成「装载一次」。
2. **权威启动期预热**：authority 起来后后台线程先把索引建好，避免「引擎刚起 + 第一个应用冷启」
   这一最坏组合（本批每次 force-stop 后复测都命中该组合，所以每次都 10.5s）。
3. **语义层（更正确但更大）**：平台 `sendOrderedBroadcast` 对调用方立即返回，投递与结果回传在之后。
   把 `BROADCAST_ROUTE` 的解析/落账挪到 authority 自己的线程池、只在需要结果时回传，可以从根本上
   消灭「客体主线程被 authority 拖死」这一整族 ANR（含 QQ 冷启 6.1–6.4s、本次 10.5s）。

## 七、修法（v99）：把「资格判定」从装载安装里摘出来

### 7.1 为什么能便宜地答这个问题

`manifestTargets` 那个循环里，绝大多数包的执行路径其实只有一步：

```java
ApkStore.BundleInstall install = broker.apks.get(space,pkg);        // 贵：工件树全量摘要
if(intent.getPackage()==null && install.info.applicationInfo.targetSdkVersion>=26) continue;  // 绝大多数在这里就走
```

也就是说，**为了问一句 targetSdk，付出了整棵工件树的校验代价**。而 targetSdk 是密封描述符
（`descriptor.json` 的 `parts[0].manifest.children[uses-sdk]`）里的静态事实，读前几 KB 就能答。

### 7.2 改动

1. `ApkStore.declaredTargetSdk(space,pkg)`：先按 `space|pkg|generation` 命中进程内缓存；
   未命中则用 `android.util.JsonReader` 流式扫 `descriptor.json`，读到 `packageName` 与
   `uses-sdk/@android:targetSdkVersion` 立即返回（**不摘要、不校验**），并把结果按代际缓存。
   任何不确定（文件缺失/形状不认识/解析失败）返回 `null`。
2. `manifestTargets`：仅对 `intent.getPackage()==null && pkg != sender` 的情况先问这一步，
   `declared>=26` 直接 `continue`；`null` 或不满足则回落到原路径 `apks.get` + 原判据。

语义上这是**纯减法**：`declaredTargetSdk` 只能让原本会被跳过的包更早被跳过；答不出来就退回旧行为，
所以不存在「本来该投递的被丢掉」。发件人自己那一格始终用已装载的 `sender`，不受影响。

### 7.3 读数（v99，2,331,891 B，sha b0913d32d36fe40f）

同一台设备、同一路径（冷启 BOSS → 点「我要找工作」），与 v98 对照：

| 读数 | v98 | v99 |
|---|---|---|
| 慢广播 | `SLOW BROADCAST_ROUTE 10499ms` | **一行都没有** |
| 空目标解析 | `ROUTE_EMPTY resolve=10499ms` | `resolve=1ms` / `resolve=0ms` |
| ANR | `am_anr Input dispatching timed out` | **无** |
| `am_kill … user request after error` | 有（进程被杀） | **无** |
| 点「我要找工作」后 top（6 次轮询 ×5s） | 1/2 次回落 Workbench | **6/6 停在 `TaskP1$F1M0C3`** |

而且业务链**真的往前走了**：落点页文本为

```
完善资料、快速找工作 / 在线简历将会向boss展示，平台将保护您的隐私
你是学生吗？ 是 / 不是
希望在哪里工作 长沙
期望的职位是（可多选） 选择期望职位
猜你想找 Java / 项目经理/主管 / 运维工程师
城市热招 普工/操作工 / 服务员 / 店员/营业员 …
```

即 `ChangeIdentityActivity`（身份选择）之后进入了**在线简历完善页**。这条链路此前从未越过的
就是这个 10.5s 阻塞，现在越过了。

### 7.4 剩下没做的

- 「在线简历保存」（填完学生/城市/期望职位 → 保存）与「投递」「沟通」仍未实测，见 batch29。
- 缓存只按代际失效，没有做 authority 启动预热：本机每次 `force-stop` 后第一次隐式广播仍要
  扫一遍描述符（实测这已经够快，但 53 个包的描述符 IO 在冷页缓存下还没单独测过）。

## 五、边界与限制

- 本轮所有复测都在 `force-stop q6/q7` 之后做的，这既清空了槽位也**清空了进程内索引**，
  所以测到的是最坏情况；日常使用中 authority 常驻，第 2 个应用之后未必有 10.5s。
  但「第一个应用」与「每次装上/卸载应用后」都必然命中。
- v97 那次「未被杀」不能当作修复证据：同一条路径在 v98 再次 ANR 并被 `am_kill`
  （`03:37:19 am_anr` → 后续 `am_proc_died`）。差异更像时序抖动而非版本差异。
- ~~BOSS 业务链本身仍未跑通：`我要找工作` 之后要等这一步广播，等不到就整进程被杀。~~
  **已被 v99 推翻**（见 §7.3）：同一条 `我要找工作` 之后 6/6 次轮询停在 `TaskP1$F1M0C3` 并渲染出
  在线简历完善页。剩下的是「简历保存 / 投递 / 沟通」尚未实测。
- 一个必须记住的测度纪律：**批量流水线与应用内交互测度不能并行**。本轮有一次 v99 复测被判作废，
  原因是同时跑着 `coverage_pipeline.py`，它每包都 `force-stop dev.tripoder.q6`，
  `events` 里表现为 `am_kill [… stop dev.tripoder.q6 due to from process:<pipeline pid>]`，
  脚本侧只会看到「top 一直 Workbench」。看到 `am_kill` 先读 `from process:<pid>` 再归因。

## 六、复现脚本

- `/tmp/boss96.py`、`/tmp/boss97.py`、`/tmp/boss98.py`：冷启 BOSS → 取落点 → 点「我要找工作」→ 每 5s 读 top → 抓 `ROUTE_EMPTY / ROUTE_STAGES / SLOW`。
- `/tmp/warm.py`：先起 `百度` 把 authority 预热，再起 BOSS（用于区分「冷 authority」与「广播本身」）。
- 脚本本体：`android/scripts/boss_business_chain.py`（冷启 + 落点 + 简历入口，写 `/tmp/boss_chain.json`）。
