# 薄壳化改造方案：从「厚重 broker」到「VA 式透传」

> 目标：让 Tripoder 的客体启动速度、运行性能、兼容性向 VirtualApp 级别看齐，同时保留「AI/MCP 控制面 + 可观测性」作为差异化价值。
>
> 结论先行：当前 30s 冷启动与长尾兼容性问题**不是同-UID 托管的架构必然**（VirtualApp 同样同-UID 却接近原生），而是 Tripoder 选择了「厚重重写 + 中心 broker」路线的后果。本文给出逐子系统的「降级为透传」改造清单。

---

## 1. 根因量化（基于真机日志 + 代码审计）

### 1.1 两种设计哲学

| | VirtualApp（薄壳） | Tripoder 现状（厚重） |
|---|---|---|
| 拦截面 | 最小，只改身份参数 | **286 个** framework 成员被 hook |
| 调用路径 | 进程内 → 直连真 `system_server` | 常绕 → 自家 `:authority` 进程 |
| 子系统 | 用真框架 | **~40 个** `Runtime*` 类重写（Provider 就 14 个类） |
| 启动校验 | 装时一次 | **每次冷启动重验整包** |
| 结果 | 快 / 兼容好 / 隔离弱 | 慢 / 兼容差 / 封存强隔离强 |

### 1.2 关键证据（真机，网易云 slot 进程 pid 14664）

启动时间线（以 launch 为 0）：

| 时刻 | 事件 | 耗时 |
|---|---|---|
| +1.4s | `ApkStore.get … integrity=1430ms` 完整性重算 | 1.4s |
| +8.2s | 客体 Application 进程才绑定（slot fork + 无 AOT 加载巨型 multidex + so） | ~6s |
| +9.2s | `RuntimeStorage.preferences` **锁争用 541ms**（多条 init 线程抢同一把锁） | 反复 0.5s+ |
| +17.4s | `AbsCookieStore.getAllCookies` 读 cookie **阻塞主线程 1.5s** | 1.5s |
| +20s | `MainActivity.onCreate` | |
| +21.7s | `LoadingActivity onCreate` 耗时 **4213ms** | 4.2s |
| +25.5s | `Choreographer: Skipped 853 frames` + 单帧 `Davey! duration=9801ms` | |
| ~+30s | 到达登录页 | |

主线程从头到尾几乎被占满 ~20 秒。

### 1.3 结构性根因：`:authority` 独立进程坐在热路径上

- `RuntimeAuthorityService` 代码注释原话：*"Sole persistent authority in its own manifest process"*，且 `onCreate` 强校验 `getProcessName().equals(getPackageName()+":authority")`——它是**独立常驻进程**。
- `RuntimeWire` 定义了 **53 个操作码**（`ACTIVITY_*` / `SERVICE_*` / `PROVIDER_*` / `BROADCAST_*` / `JOB_*` / `PENDING_INTENT` / `APPLICATION_START_HISTORY` …），绝大多数是**每次组件操作**都要跨进程绕 `:authority` 一圈。
- **Provider 单次访问的真实链路**（`RuntimeProviderFacade.invoke`）：
  ```
  brokerCall(PROVIDER_BEGIN)            → IPC 到 :authority
  client.query(content://<transport>/…) → IPC 到 slot 进程的 transport provider
  PROVIDER_FINISH                       → IPC
  ```
  即一次 `ContentResolver.query()` = **2~3 次跨进程**。原生 / VA 只要 1 跳。

**启动期组件访问（provider / activity / service / broadcast）极密集，每个都多 2~3 跳 IPC + 中心 broker 串行化——这就是十几秒「结构性」开销的真正来源。**

---

## 2. 改造清单（按启动收益排序）

| # | 子系统（代码） | 现在的机制 | VA 做法 | 降级动作 | 收益 | 失去什么 |
|---|---|---|---|---|---|---|
| 1 | **Provider**（`RuntimeProvider*` ×14, `PROVIDER_*`） | 每次访问 2~3 跳 IPC（authority + transport） | 改写 authority，**1 跳直连真 provider** | 客体进程内把 authority 改写成真实的，直接用真 `ContentResolver`；自有 provider 用 stub 承载 | **极高** | provider 层记账/隔离 |
| 2 | **Activity 生命周期**（`ACTIVITY_*`, `RuntimeEngine/Broker`） | claim/created/attached/resumed/destroyed **每事件 IPC** | stub activity + **进程内修 `ActivityThread` 记录**，无 per-lifecycle IPC | 承载在进程内完成，只在"要一个 stub 槽"时跟 broker 打一次 | **高** | 中心 launch ledger |
| 3 | **每次启动重验 artifact**（`ApkStore` `integrity=1430ms`） | 每次冷启动哈希整包 | 装时一次，之后信任 | 验证移到安装期；启动只信任（或增量校验） | **高**（−1.4s/launch） | 运行时防篡改 |
| 4 | **Service 绑定**（`SERVICE_*`, `RuntimeServices/ServiceBroker`） | 每次 bind IPC 绕 broker | stub service + 进程内 | 同 #2 思路 | 中高 | service 记账 |
| 5 | **Broadcast**（`BROADCAST_*`, `RuntimeReceivers/BroadcastBroker`） | register/route/claim 每个 IPC | 用真 `registerReceiver`（改 filter），系统直接投递 | 降级为真实注册 + 身份改写 | 中高 | 广播管控 |
| 6 | **Storage/Prefs**（`RuntimeStorage/PreferencesDisk`，自带锁+fsync） | 自己重写 SharedPreferences，**粗锁串行**（实测 541ms 争用） | 客体用**真 SharedPreferences**，只重定向数据目录 | 去掉重写，只 hook `getDataDir`/路径；若暂不整体替换，至少把粗锁改**按文件名细粒度锁** | 中 | 存储封存 |
| 7 | **`:authority` 在热路径** | 每次组件调用绕它 | 无此物 | **总纲：把 broker 降级成纯控制面**（安装/管理/MCP），不参与每次 framework 调用 | 根治 | 中心化保障 |
| 8 | **AOT** | 首启解释执行 | 标准 oat | 走标准 oat（oat 保留 fix 已帮一半，见 §4） | 中 | — |

---

## 3. 执行顺序

1. **第一刀（零风险、立竿见影）：#3 + #6**
   - #3：把 `ApkStore.get` 的整包完整性校验从**每次启动**移到**安装/首次装载一次**，之后信任（或仅做基于 stamp 的增量校验）。直接 −1.4s/launch。
   - #6：`RuntimeStorage.preferences` 的 `synchronized(this)` 粗锁改为**按文件名细粒度锁**（不同 prefs 文件并行，仅同文件串行），消除启动期多线程 500ms+ 争用。
   - 纯 Java、范围小，可作为「证明方向对」的验证刀。
2. **第二刀（最大收益）：#1 Provider 透传** —— 把 2~3 跳压回 1 跳。
3. **第三刀：#2 / #4 / #5** —— 逐个拆掉 `ACTIVITY_/SERVICE_/BROADCAST_` 的 per-event IPC。
4. **方向校准：#7** —— 上面做完，`:authority` 自然退成控制面，正是 VA+AI 产品该有的形态（broker 只服务 MCP/管理，不拖慢运行）。

---

## 4. 已落地的相关修复（本轮）

- **oat 二次启动解砖 + 元数据**（`ArtifactSet.java` / `ArtifactIntegrityCache.java`）：ART 在封存树里写的 `oat/odex/vdex` 派生缓存不再被不可变性校验拒绝；保留 oat 让第二次起走 AOT（#8 的一半）。
- **验收工装稳定性检查**（`TargetLaunchIntegration.java`）：改为跟踪 launch generation 的整个进程集合（容忍 Tinker/多进程换 pid），不再误报 worker died。
- 注意：`am instrument` 结束会杀掉整个 engine（`Killing … due to finished instrumentation`），所以要观察客体真实运行请走**工作台磁贴**（`am start WorkbenchActivity` + 点卡片），不要用 instrument。

---

## 5. 取舍与建议：混合档位

#1/#2/#4/#5/#7 都在削弱当前的中心化封存/隔离/反检测透明——这些正是 Tripoder 慢、也正是 VA 没有的东西。建议**分档**：

- **默认「薄壳档」**：最小拦截 + 透传真系统 —— 快、兼容好，给 agent（MCP）驱动用。
- **高危「厚重档」**：对加固/反检测样本再开封存/隔离/中心 broker —— 慢是它的固有代价，不做通用默认。

当前问题本质是：**把「高危档」的重保障当成了「默认档」的底座**，于是又慢又不兼容。薄壳化 = 让默认档回到「该交给真系统的交回去，自己只在进程内改几个身份参数」。

---

## 6. 双模式架构：一套运行时，子系统双实现

决策：Tripoder 同时提供**薄壳透传模式**（默认）和**厚重模式**（当前实现，用户可选）。

实现上**不做两套完整系统**，而是**共享核心 + 每个子系统两种实现 + 按客体在启动时选定**。

### 6.1 为什么不做两套完整系统

- 安装、dex 加载、身份伪装、slot 托管等两边都需要的部分会写两份，修复容易只落到一边。
- 测试矩阵翻倍，census 每个应用要跑两遍。
- 两套行为长期分叉后，模式切换带来的差异难以区分是「模式差异」还是缺陷。

### 6.2 结构

```
共享核心（两种模式共用，只有一份）
  ├─ 安装 + 校验（只在安装期做一次）
  ├─ dex 加载 / AOT / Application 引导（含易盾等壳绕过）
  ├─ 最小身份伪装（包名、进程名、数据目录重定向）
  ├─ stub 槽托管
  └─ 观测能力：Hook-as-a-Service、抓包、UI 自动化、MCP（与模式无关）

按子系统可插拔的路由（每个两种实现）
  Provider   : 透传(薄)            | broker(厚)
  Activity   : 进程内(薄)          | ledger IPC(厚)
  Service    : 透传                | broker
  Broadcast  : 真注册              | broker
  Storage    : 真 SharedPreferences + 路径重定向 | RuntimeStorage
  启动校验    : 信任                | 每次重验

厚重模式独有的附加层：封存防篡改、中心记账/策略、深度 /proc 反检测
```

### 6.3 规则

1. **模式按客体（项目）设置，在 slot 进程启动时选定，进程生命周期内不变。** hook 在进程启动时安装，不支持运行中热切换。因为按进程选定，薄壳客体与厚重客体可同时运行在不同 slot 中。
2. **只提供两个预设，不开放逐子系统自由组合。** 自由组合会使测试面不可控；确有需要时再加入少量经过验证的覆盖项。
3. **MCP / 观测能力在两种模式下都必须可用**——它们在客体进程内工作，与路由选择正交。

### 6.4 核心与厚重层的边界

- **最小身份伪装属于核心**：包名、进程名、数据目录重定向。VirtualApp 同样需要这些，缺了它们薄壳模式下大量应用无法启动。
- **壳绕过属于核心**：如 `com.netease.nis.wrapper.MyApplication` → 真实 Application 的替换。否则薄壳模式下网易云等加固应用无法启动。
- **深度反检测属于厚重层**：native `path_policy` 的 comm / maps / readlink 改写、封存不可变校验、中心记账与跨组件策略。

### 6.5 落地顺序（不打断现有功能）

1. **加模式开关，只有厚重一种实现**：引入模式字段与子系统路由接口，行为完全不变。纯重构，风险最低。
2. **两项改动按性质处理**：
   - prefs 细粒度锁（§2 #6 的锁部分）无安全取舍，**全局生效**，不做成模式相关。
   - 启动不重验（§2 #3）有防篡改取舍，**做成模式相关**：薄壳信任，厚重每次重验。
3. **逐个子系统补薄壳实现**，从收益最大的 Provider 开始；每补一个，用 census 对比两种模式的通过率与启动耗时。
4. **薄壳模式在 census 通过率与启动耗时上均追平或超过厚重模式后，才把默认值切到薄壳。** 在此之前默认值保持厚重。
5. 可选：census 记录每个应用在哪种模式下可运行；某模式启动失败时，提示用户切换到另一种。

---

## 7. 与 AI/MCP 产品方向的关系

薄壳化后，`:authority` broker 从「每次调用都绕」退成「纯控制面」，正好承载 MCP 工具面（见 `docs/agent-mcp-design.md`）：

- 同-UID + 进程内 hook 是**观测优势**（零成本 hook / 解密 / 读 view 树，无需 root/跨进程），对「agent 驱动的 App 沙箱」是加分项——所以同-UID 选择对本产品方向是对的。
- broker 专注做：安装/生命周期编排、Hook-as-a-Service、抓包、UI 自动化、功能测试的调度与上报。
- 运行热路径交回真系统 → 启动个位数秒、兼容面大 → agent 才能稳定、快速地在大量真实 app 上跑任务。
