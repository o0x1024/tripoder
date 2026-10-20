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

---

## 8. 实施进度与接手指南

> **给接手的 agent**：本节是实施的唯一事实来源。开工前先读完本节；每完成一个步骤，**立即**更新下面的进度表和该步骤的「实施记录」（做了什么 / 改了哪些文件 / 怎么验证的 / 遗留与坑），再开始下一步。不要跳步：每一步都要求「行为不变或有明确收益 + 真机验证 + 文档更新」。

### 8.1 进度总表

状态：`未开始` / `进行中` / `完成` / `阻塞`

| 步骤 | 内容 | 状态 | 是否改变默认行为 |
|---|---|---|---|
| S1 | 模式基础设施：`RuntimeMode`、broker→slot 传递、`RuntimeEngine.mode`、THIN 未实现时 fail-closed | **完成** | 否（纯重构） |
| S2 | 模式选择与持久化：按项目存储模式、控制面命令、工作台入口、MCP 暴露 | **完成** | 否（默认仍 THICK） |
| S3 | 全局无取舍优化：`RuntimeStorage.preferences` 粗锁 → 按文件细粒度锁 | **完成** | 是（两种模式都生效，仅提速） |
| S4 | 启动不重验（模式相关）：THIN 信任已安装产物，THICK 保持每次重验 | **完成** | 仅 THIN |
| S5 | THIN：Provider 透传（authority 改写 + 直连真 provider，去掉 2~3 跳 IPC） | **完成** | 仅 THIN |
| S6 | THIN：Activity 生命周期进程内化（去掉 per-lifecycle broker IPC） | **完成** | 仅 THIN |
| S7 | THIN：Service 绑定透传 | **完成** | 仅 THIN |
| S8 | THIN：Broadcast 真注册 | **完成** | 仅 THIN |
| S9 | THIN：Storage 用真 SharedPreferences + 路径重定向 | **完成** | 仅 THIN |
| S10 | census 对比两种模式，达标后把默认值切到 THIN | **完成** | 是（切默认） |
| S11 | 保留编译、裁剪系统服务 Hook、标准 ClassLoader 与 native 隐藏层减法 | **进行中：首轮已验证，整体原生性能未达标** | 仅 THIN；见 [实施与证据](thin-native-performance.md) |

### 8.2 接手须知（环境、命令、已知的坑）

**仓库与分支**：`/Users/like/code/tripoder`，分支 `merge-workbench-engine`。单 APK 包名 `dev.tripoder.engine`，进程模型：主 UI / `:authority`（独立常驻 broker）/ `:slot0..7`（客体承载）。

**构建（Java + native 全量）**：
```
python3 android/scripts/build-workbench.py --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 --platform-libc android/runtime-probe/build/device-libc.so --platform-art android/self-hook-probe/build/device-libart.so --platform-linker android/self-hook-probe/build/device-linker64
```
产物：`android/product/build/tripoder.apk`、`tripoder-control-test.apk`。**该脚本只构建，不跑 host 测试。**

**Host 测试（不需要设备）**：
- `python3 android/runtime/test/ArtifactSetHostTest.py --sdk /opt/homebrew/share/android-commandlinetools --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`（当前应 48/48 通过）
- 其中 `android.system.Os` 在 host 的 `android.jar` 里是 stub（调用会抛 `Stub!`），用到它的代码无法在 host 测，只能真机验证。

**真机（序列号 `d861a4fa`）**：
- 安装：`adb -s d861a4fa install -r -g android/product/build/tripoder.apk`；control-test 签名不匹配时先 `adb uninstall dev.tripoder.controltest` 再装。
- **观察客体真实运行必须走工作台磁贴**：`am start -n dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity`，点应用卡片。**不要用 `am instrument` 观察长时间运行**——instrument 结束会 `Killing … due to finished instrumentation` 把整个 engine 杀掉，看起来像「客体启动页后掉回桌面」，实为工装假象。instrument 只适合做一次性验收断言。
- 客体日志**能**看到，但要按活着的 slot 进程 pid 过滤（客体进程名被伪装成 `ease.cloudmusic` 之类，按名字抓不到）：先 `adb logcat -c`，再 `logcat -v threadtime -b main -b crash -b system > file`，启动后按 `awk '$3==<slot pid>'` 过滤。
- `dumpsys window | grep mCurrentFocus` 可看当前前台 Activity；`uiautomator dump` 可读 UI 文本；`screencap` 到设备文件再 `pull`（直接 `exec-out` 会被 MIUI 的多显示器警告污染）。

**共享设备与共享工作树的坑**：
- 设备可能被其他 agent 或来源不明的定时任务占用（曾有 DeepSeek 客体被反复拉到前台）。跑真机前先 `pgrep -fl "validate-workbench-control|build-workbench|am instrument"`，并看 `mCurrentFocus`。
- 其他会话可能在改 `android/self-hook/native/*`、`android/runtime/native/*`（native 层）。**本改造全部是 Java，不要动 native**；改动前后用 `shasum -a 256 android/product/build/native/libtripoderselfhook.so` 确认 `.so` 没变（当前 `8b07186f…`）。`.so` 变了说明是别人的 native 改动落地了，不要把由此产生的行为差异算到自己头上；先做「无改动重建」对照再下结论。
- 重装 engine 会杀掉所有客体；残留的旧 UID 客体进程（reparent 到 zygote）是无害的红鲱鱼，别被它的 pid 误导。

**测试对象**：网易云音乐（`com.netease.cloudmusic`，Tinker + 易盾壳）是本改造的主要验收客体：能走到 `LoginActivity` 并稳定渲染即「基本可用」。**不要在测试中登录任何真实账号**（包括微信）。

**census**：批量跑真实 app 的方法与历史结果见 `docs/evidence/guest-compat-census-2026-10-19/` 与 `docs/guest-compatibility.md`；驱动方式是 `TargetLaunchIntegration` 的 `customPackage` 路径（`customSource` 指向设备上 `/data/app/<pkg>/base.apk`，其为全局可读）。

### 8.3 关键代码地图（改造会碰到的位置）

| 关注点 | 位置 |
|---|---|
| broker→slot 启动包（模式的传递点） | `RuntimeBroker.java:82`（组包）→ `RuntimeFactory.java:34` → `RuntimeEngine` 构造函数（读包） |
| 每个操作码的 IPC 定义 | `RuntimeWire.java`（53 个操作码） |
| Provider 单次访问链路 | `RuntimeProviderFacade.invoke`（`PROVIDER_BEGIN` → transport query → `PROVIDER_FINISH`） |
| 启动完整性校验（broker 侧） | `ApkStore.get` → `ArtifactIntegrityCache.get`（日志 `ApkStore.get slow … integrity=…ms`） |
| 已有的「信任快路径」（slot 侧） | `RuntimeEngine` 构造中 `ArtifactSet.loadTrusted(…descriptorLiteFile, artifactStamp…)` vs `loadVerified`：S4 要在 broker 侧也复用这一思路 |
| 存储与 prefs | `RuntimeStorage.preferences`（`synchronized(this)` 粗锁 + 持锁加载）、`PreferencesDisk`、`PreferencesXml` |
| slot 池与复用 | `ProcessSupervisor`（含预热 spare slot：spare 在分配客体前就已 fork，模式要到 LOAD 才知道） |
| 控制面命令入口 | `RuntimeAuthorityService`（`catalog/install/prepareLaunch/stopApplication/launchEntries/…`）、`ControlWire` |

> **spare slot 的设计约束**：slot 进程可能在客体分配前就被预热 fork。模式随 LOAD 启动包到达，所以**任何早于 LOAD 的、进程级一次性初始化（hook 安装等）必须与模式无关**；与模式相关的路由要在读到 `mode` 之后才绑定。S5 起每个 THIN 路由都要遵守这一点。

### 8.4 各步骤实施记录

#### S1 模式基础设施（完成）

**目标**：引入模式概念并打通 broker→slot 传递，行为完全不变；THIN 尚无实现，必须 fail-closed，防止有人拿到「标着 THIN 实际按 THICK 跑」的进程。

**设计决定**：
- 本步**不**为各子系统预先抽象路由接口。接口会在 S5 起、第一个 THIN 实现出现时按真实需要引入，避免投机抽象；本步只提供模式值本身。
- 模式的持久化与选择不在本步（见 S2）；本步 broker 侧解析函数恒返回 `THICK`。
- 缺省（启动包没有 `mode` 字段）按 `THICK` 处理；出现**未知值**直接拒绝启动（fail-closed），不降级。

**改动文件**：
- 新增 `android/runtime/src/dev/tripoder/runtime/RuntimeMode.java`：枚举 `THICK("thick")` / `THIN("thin")`；`KEY="mode"`（启动包字段名）、`DEFAULT=THICK`；`parse(String)`（缺省→默认，未知值抛 `RUNTIME_MODE_UNKNOWN:<值>`，大小写敏感，错误信息截断到 32 字符）；`implemented()`（目前仅 THICK 为真）；`requireImplemented()`（THIN 抛 `RUNTIME_MODE_NOT_IMPLEMENTED:thin`）；`resolve(space, package)`（目前恒返回 `DEFAULT`，**S2 在此接入每项目的持久化设置**）。无 Android 依赖，可 host 测试。
- 改 `RuntimeBroker.java`（`attachPeer`，组启动包处）：`input.putString(RuntimeMode.KEY, RuntimeMode.resolve(slot.install.space, slot.install.info.packageName).wire)`。
- 改 `RuntimeEngine.java`：新增 `final RuntimeMode mode`；构造函数里紧接启动期限检查：`mode = RuntimeMode.parse(input.getString(RuntimeMode.KEY)).requireImplemented()`，并输出日志 `TripoderBoot: runtime mode=<wire>`。
- 新增测试 `android/runtime/test/dev/tripoder/runtime/RuntimeModeTest.java` 与运行脚本 `android/runtime/test/RuntimeModeHostTest.py`。

**验证（均已执行）**：
- Host：`python3 android/runtime/test/RuntimeModeHostTest.py --java-home <JDK>` → 19/19 通过（覆盖 wire 值稳定性、缺省、往返、未知值/空串/大小写拒绝、错误信息有界、THIN fail-closed、slot 读取路径、resolve 缺省）。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f…`（未碰 native）。
- 真机（`d861a4fa`，update 安装保留绑定，走工作台磁贴）：网易云照常走到 `LoginActivity`（与改动前一致）；slot 进程日志出现 `TripoderBoot: runtime mode=thick`；无 `RUNTIME_MODE*` 错误。**行为未变。**

**遗留与观察**：
- **THIN 的真机负向测试尚未做**：broker 目前永远发 `thick`，真机上没有途径让 slot 收到 `thin`，THIN 的拒绝只由 host 测试覆盖。**S2 一旦能设置模式，必须补一条真机负向用例**：把某项目设为 THIN → 启动应以 `RUNTIME_MODE_NOT_IMPLEMENTED:thin` 失败、且不能有任何进程按 THICK 行为悄悄跑起来。
- **作业进程尚未传递模式**：`RuntimeJobBootstrap`（JobScheduler 的 job 进程）走另一条启动包，本步没有加 `mode`。目前 job 进程只有 THICK，行为不变；待 THIN 子系统涉及 job 时（S5 之后评估）再补。
- **已存在、与本步无关的现象**：重装 engine 后，系统会把旧的客体 Activity 任务恢复到一个全新的 `:slot0`，而该 slot 从未收到 LOAD 包，于是 `RuntimeActivityCreation.begin → RuntimeWire.required` 抛 NPE，日志里出现一条 `FATAL EXCEPTION … Unable to instantiate activity …TaskP0$F0M1C0 … GUEST_ACTIVITY_FAILED`。出现在安装之后的最初几秒，随后正常启动不受影响。**不要把它当成自己改动引入的回归**；如要治理，应让「无 LOAD 包的 Activity 恢复」干净地失败/回桌面而不是崩溃。

#### S2 模式选择与持久化（完成）

**目标**：按项目存储运行模式（默认 `thick`），提供控制面 `setProjectMode` 命令、工作台项目页模式切换入口、以及在 `catalog` / `resolveProject` / `hookProcesses` / `session.get` / `hook.status` 等控制面与 MCP 观测面完整暴露 `mode`，并补齐 S1 要求的真机 THIN 负向测试。

**设计决定**：
- 模式持久化在 `RuntimeStore` 的 `project_refs` 表（schema v33 → v34，新增 `mode TEXT NOT NULL DEFAULT 'thick'`）；项目重绑（`bind`）时自动保留原项目的 `mode`。
- 同一 `(space, package)` 安装若被多个 `COMMITTED` 项目绑定，解析模式通过 `RuntimeMode.resolve(Iterable<String>)` 执行：无绑定项目时取 `DEFAULT`（`thick`）；绑定项目模式一致时取该模式；若多项目模式不一致则直接以 `RUNTIME_MODE_CONFLICT:<m1>:<m2>` 拒绝（在 `setProjectMode` 写入前与 `RuntimeBroker.resolveMode` 启动前双重校验），不做静默覆盖。
- 当目标安装存在运行中会话时，`setProjectMode` 拒绝切换并报错 `PROJECT_PROCESS_RUNNING_STOP_FIRST`，避免运行期槽位与持久化配置分裂。
- 当项目设为 `thin` 并在当前（THIN 尚未实现）发起启动时，slot 进程在 `RuntimeEngine.<init>` 抛 `RUNTIME_MODE_NOT_IMPLEMENTED:thin`；`ProcessSupervisor.awaitReady` 捕获后立即调用 `terminateAfterModeRejected` 回收并等待 worker 断开，`RuntimeControllerLaunch.prepare` 将操作回执记为确定性的 `FAILED`（`detail="RUNTIME_MODE_NOT_IMPLEMENTED"`）而非 `UNKNOWN`，且不残留任何存活客体进程。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/RuntimeMode.java`、`android/runtime/test/dev/tripoder/runtime/RuntimeModeTest.java`：新增 `resolve(Iterable<String>)` 及 5 项冲突/一致性单元测试（Host 测试 24/24 通过）。
- `android/runtime/src/dev/tripoder/runtime/RuntimeStore.java`：DB 版本升至 34，`project_refs` 表增加 `mode` 列与 `oldVersion<34` 迁移。
- `android/runtime/src/dev/tripoder/runtime/ProjectTargetBindings.java`：`bind`/`resolve` 包含并保留 `mode`；新增 `setMode(operation, caller, project, rawMode)`，具备 `ExecutionContract` 幂等回执、运行中进程检查、同安装多项目冲突检查。
- `android/runtime/src/dev/tripoder/runtime/RuntimeBroker.java`：`Slot` 新增 `public final RuntimeMode mode`，在 `reserve` 时从 `project_refs` 解析，在 `attachPeer` 写入 `LOAD` 包，并在 `processEvidence` 中输出 `"mode"`。
- `android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java`、`android/engine/src/dev/tripoder/runtime/RuntimeControllerLaunch.java`：在 `LOAD` 阶段遇到 `RUNTIME_MODE_NOT_IMPLEMENTED:*` 时同步排空并等待槽位进程终止，操作状态记为 `FAILED`（`RUNTIME_MODE_NOT_IMPLEMENTED`）。
- `android/engine/src/dev/tripoder/runtime/RuntimeControllerCommands.java`、`RuntimeControllerHooks.java`、`RuntimeAgentSessions.java`、`android/runtime/src/dev/tripoder/runtime/RuntimeHookBridge.java`：路由 `setProjectMode` 命令；在 `catalog.projects`、`hookProcesses`（MCP `process.get`）、`RuntimeAgentSessions.describe`（MCP `session.get`）、`RuntimeHookBridge` `identity`（MCP `hook.status`）暴露 `mode`。
- `android/workbench/src/dev/tripoder/hooklab/WorkbenchProjectActivity.java`、`WorkbenchAppActions.java`、`UiText.java`：工作台项目详情页「运行与部署」卡片展示当前运行模式并提供「切换模式」弹窗（厚重隔离 `thick` / 薄壳透传 `thin`），补充模式相关错误提示文案。
- `android/control-test/src/dev/tripoder/controltest/ControlInstallChecks.java`、`ControlLaunchChecks.java`：新增真机控制面断言 `set-project-mode-rejects-unknown-mode`、`set-project-mode-persists-thin-with-receipt`、`set-project-mode-restores-thick`、`thin-mode-launch-fails-closed`、`thin-mode-rejection-leaves-no-running-process-and-records-failed`。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s2-control-evidence --phase control` 全部 68 项断言通过（含 `ControlIntegration` 62 项 + `ControlDeathRecoveryIntegration` 6 项），验证了 `thin` 模式持久化回执、启动以 `RUNTIME_MODE_NOT_IMPLEMENTED:thin` fail-closed、回执记为 `FAILED` 且 `hookProcesses` 为空、切回 `thick` 后正常启动。
- 真机工作台磁贴验证（`d861a4fa`）：从工作台点击网易云音乐磁贴，slot 日志输出 `TripoderBoot: runtime mode=thick`，历经 `IconChangeDefaultAlias`、`MainActivity` 正常进入 `TaskP0$F0M0C2`（`LoginActivity`）并稳定保持 Resumed。

**遗留与坑**：
- `ExecutionContract.fail(id, errorCode)` 要求 `errorCode` 满足 `[A-Za-z0-9_-]{1,128}`（不允许冒号 `:`），因此 `RuntimeControllerLaunch` 在记录操作失败回执时必须传 `"RUNTIME_MODE_NOT_IMPLEMENTED"`，而抛给调用方的异常消息仍保留完整 `"RUNTIME_MODE_NOT_IMPLEMENTED:thin"`。

#### S3 全局无取舍优化：`RuntimeStorage.preferences` 粗锁 → 按文件细粒度锁（完成）

**目标**：消除客体启动期多线程并发打开不同 `SharedPreferences` 文件时在 `RuntimeStorage` 实例锁上的全局串行争用（此前真机 `dvm_lock_sample` 实测单次阻塞 500ms～6.6s），同时保持同文件内核文件锁（`PreferencesDisk`）的进程内互斥不触发 `OverlappingFileLockException`。

**设计决定**：
- 将 `RuntimeStorage.preferences` 由 `HashMap` + `synchronized(this)` 改为 `ConcurrentHashMap<String, RuntimePreferences>` + 按文件名细粒度锁 `ConcurrentHashMap<String, Object> preferenceLocks`。
- 已加载的 `SharedPreferences` 直接走 `preferences.get(name)` 无锁快路径返回；仅首次加载某文件时在 `synchronized(preferenceLock(name))` 内执行双重检查与 `new RuntimePreferences(...)`，使不同 `SharedPreferences` 文件完全并行加载，而同一文件的并发首次打开仍严格串行。
- `finisherRegistered` 改为 `AtomicBoolean` CAS 单次注册；`movePreferencesFrom` 按 `System.identityHashCode` 固定顺序获取源与目标同文件名的锁，杜绝死锁；`flush()` 直接快照 `preferences.values()`，不再占用全局 `RuntimeStorage` 监视器。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/RuntimeStorage.java`：`preferences`、`preferenceLocks`、`finisherRegistered`、`preferences(name, mode)`、`movePreferencesFrom`、`flush`。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机（`d861a4fa`）：从工作台点击网易云音乐磁贴冷启动，改动前（pid `25065`）`logcat -b events` 出现 26 条 `RuntimeStorage.preferences` 的 `dvm_lock_sample`（主线程及各 Init 线程争用 513ms～6674ms）；改动后（pid `27964`）全程 `RuntimeStorage.preferences` 的 `dvm_lock_sample` 降为 **0 条**，且正常进入 `TaskP0$F0M0C2`（`LoginActivity`）稳定运行。

#### S4 启动不重验（模式相关）：THIN 信任已安装产物，THICK 保持每次重验（完成）

**目标**：让 `:authority` 进程的 `ApkStore.get` 根据项目配置的运行模式（`RuntimeMode`）分流：当目标安装绑定的项目模式为 `THIN` 时，跳过每次冷启动对整包 APK 签名、ZIP 扫描与全部解压文件 SHA-256 的重算（`ArtifactSet.verify`，网易云实测 ~1.5s），改为走基于 `descriptor.json` SHA-256 + 目录树只读/文件大小校验的信任加载快路径（`ArtifactSet.loadVerified`）；当项目模式为 `THICK` 时，保持原有的每次冷启动全量字节与签名重验。

**设计决定**：
- **升级防护**：`ArtifactIntegrityCache.Entry` 新增 `final boolean fullVerified` 标记。若某安装先在 `THIN` 模式下被缓存（`fullVerified=false`），随后用户或控制面切回 `THICK` 模式，或任何调用方以 `trusted=false` 请求（如 `reverify`、升级安装校验），缓存不会命中未全量验证的条目，而是强制执行一次完整的 `ArtifactSet.verify` 并升级为 `fullVerified=true`。此外，`ProjectTargetBindings.setMode` 在切换项目模式时主动调用 `broker.apks.evict(space, pkg)` 清除缓存。
- **元数据完整性补齐**：此前 `ArtifactSet.loadVerified` 仅面向 worker 进程，其 `ApkManifest.fromJsonReader` 跳过了 `application` 下各组件子节点的 `intent-filter` 树，而 authority 侧的 `RuntimeControllerLaunch.launcherComponent`、`ArtifactSet.liteJson`、`RuntimeBroadcastBroker` 需要读取组件声明上的 `intent-filter`。本步在 `ApkManifest.componentElementFromJsonReader` 与 `ArtifactSet.parse` 中完整保留了组件的 `declaration` 及 `intent-filter`（`action`/`category`/`data`）节点，且完全不改变 `descriptor.json` 的磁盘格式与 SHA-256。
- **可观测指标**：`ArtifactIntegrityCache.stats()` 新增 `trustedLoads` 计数器，与 `fullVerifications` 区分，并通过 `resolveProject` 的 `integrity` Bundle 暴露给控制面测试。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/ArtifactIntegrityCache.java`：新增 `get(directory, digest, trusted)`、`Entry.fullVerified`、`trustedLoads` 统计、`evict(directory, digest)`。
- `android/runtime/src/dev/tripoder/runtime/ApkStore.java`：`get(space, pkg)` 解析 `(space, pkg)` 的 `RuntimeMode` 并向 `integrity.get` 传递 `mode == RuntimeMode.THIN`；新增 `resolveMode(space, pkg)` 与 `evict(space, pkg)`。
- `android/runtime/src/dev/tripoder/runtime/ApkManifest.java`、`ArtifactSet.java`：在 `fromJsonReader` 与 `ArtifactSet.parse` 中还原组件 `declaration` 及 `intent-filter` 子树。
- `android/runtime/src/dev/tripoder/runtime/ProjectTargetBindings.java`：`setMode` 切换模式时调用 `broker.apks.evict`，`resolve` 返回 `integrity` 统计。
- `android/control-test/src/dev/tripoder/controltest/ControlInstallChecks.java`：新增真机断言 `thin-mode-resolve-uses-trusted-artifact-load-without-full-reverify` 与 `thick-mode-resolve-performs-full-artifact-verification`。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s4-control-evidence --phase control` 全部 70 项断言通过（含 `thin-mode-resolve-uses-trusted-artifact-load-without-full-reverify` 验证 `trustedLoads` +1 且 `fullVerifications` 不变，切回 `thick` 后 `thick-mode-resolve-performs-full-artifact-verification` 验证 `fullVerifications` +1）。

**遗留与坑**：
- Host 环境的 `android.jar` 中 `android.util.JsonReader` 是 `Stub!`，因此调用 `ArtifactSet.parse` / `loadVerified` 的路径必须在真机上验证，不能放进纯 Host 的 `ArtifactSetTest`。

#### S5 THIN：Provider 透传（authority 改写 + 直连真 provider，去掉 2~3 跳 IPC）（完成）

**目标**：正式开启 `RuntimeMode.THIN`（`implemented() == true`），在 `THIN` 模式下彻底去掉 Provider 启动逐个上报（`PROVIDER_PUBLICATION`）以及单次访问的 4 跳 `:authority` 账本 IPC（`PROVIDER_BEGIN` / `PROVIDER_DISPATCH` / `PROVIDER_FINISH` / `PROVIDER_RECEIVED` / `PROVIDER_CURSOR_ACCESS` / `PROVIDER_RELEASE`）与请求载荷 SHA-256 摘要计算，改为同进程直调、跨进程 1 跳直连目标槽位 `RuntimeProviderTransport`（`v1-thin` / `tripoder.thin`），并对系统设置与外部 Provider 做透传兼容。

**设计决定**：
- **启动零上报**：`ProviderLedger.reserve(Slot)` 与 `local(...)` 在 `slot.mode == RuntimeMode.THIN` 时直接将 `provider_leases.state` 初始化为 `"READY"`；`RuntimeProviders.publish(...)` 在 `THIN` 模式下直接返回（0 次 `PROVIDER_PUBLICATION` IPC）。
- **跨进程 1 跳直连（`v1-thin` / `tripoder.thin`）**：当调用方与目标槽位均为 `THIN` 模式时，`RuntimeProviderBroker.acquire` 直接返回 `thin=true`、`cap=lease`（不写 `provider_acquisitions` SQLite 表）；`RuntimeProviderFacade.invoke` 走 `invokeThin`，对 `query` 直接请求 `content://<physical>/v1-thin/<lease>?uri=<uri>&callerPackage=<pkg>`，对 `getType`/`call`/写操作/批量操作直接调用 `client.call("tripoder.thin", null, envelope)`；目标槽位的 `RuntimeProviderTransport` 通过 `dispatchThin` 进程内完成 `ready(lease)` 与 `RuntimeProviderIdentity` 注入并直调目标 `ContentProvider`，全程 0 次 `:authority` 往返、0 次 SHA-256 `digest()` 序列化；`RuntimeProviderCursor` 在 `thin=true` 时跳过每次游标读取的 `PROVIDER_CURSOR_ACCESS` IPC，关闭时也不发 `PROVIDER_RELEASE`。
- **外部与 Settings 透传**：`RuntimeProviders.Router` 在 `THIN` 模式下对非客体空间的外部 `authority` 透传至 `engine.host.getContentResolver()`；`SettingsReadProvider` 在 `THIN` 模式下放开任意 `content://` URI 读取、允许非写 `call` 方法，并对 `PUT_`/`DELETE_`/`RESET_` 及 `insert`/`update`/`delete` 返回无害空结果而非抛 `PROVIDER_WRITE_UNSUPPORTED`。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/RuntimeMode.java`、`android/runtime/test/dev/tripoder/runtime/RuntimeModeTest.java`：`THIN` 标记为已实现（`implemented() == true`），更新 Host 单测。
- `android/runtime/src/dev/tripoder/runtime/ProviderLedger.java`、`RuntimeProviderBroker.java`：`THIN` 模式租约初始 `READY`、`acquire` 返回 `thin=true` 免账本写入。
- `android/runtime/src/dev/tripoder/runtime/RuntimeProviderFacade.java`、`RuntimeProviderTransport.java`、`RuntimeProviderCursor.java`：实现 `invokeThin` / `dispatchThin` 1 跳直连与薄游标。
- `android/runtime/src/dev/tripoder/runtime/RuntimeProviders.java`、`SettingsReadProvider.java`：`THIN` 模式跳过 `publish` IPC、`Router` 外部 authority 透传、`SettingsReadProvider` 读写兼容。
- `android/control-test/src/dev/tripoder/controltest/ControlLaunchChecks.java`：更新控制面断言为 `thin-mode-launch-prepares-slot-in-thin-mode`（验证 `THIN` 模式下 `prepareLaunch` 成功拉起 `mode="thin"` 的槽位进程）。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s5-control-evidence-2 --phase control` 全部 69 项断言通过。
- 真机工作台磁贴验证（`d861a4fa`）：将网易云音乐项目切换为 `thin` 模式并从工作台磁贴冷启动（`pid=16651`），日志输出 `TripoderBoot: runtime mode=thin`，全程 0 次 `PROVIDER_PUBLICATION` / `PROVIDER_BEGIN` / `PROVIDER_DISPATCH` / `PROVIDER_FINISH` IPC，顺利从启动页进入 `LoginActivity`（`mCurrentFocus=Window{... com.netease.cloudmusic.module.login.LoginActivity}`，`topResumedActivity=TaskP0$F0M0C2`）并稳定保持在前台。

**遗留与坑**：
- **切勿在 `RuntimeSelfHookProviderCalls.acquire` 对未知 authority 调用 `original.call`**：客体的 `ContentResolver` 是 `ContentResolver.wrap(router)` 生成的 `ContentResolver$1`，而 Android 框架中 `ContentResolver$1.acquireProvider` 的原始实现忽略 `authority` 参数直接返回 `router.getIContentProvider()`。若在 `route.acquire` 返回 `null` 时调用 `original.call`，会导致任何不存在的 `authority`（含 `acquireExistingProvider`）都返回非空的 `router` 句柄，既破坏客户端对不存在 Provider 返回 `null` 的判断，又绕过 `RuntimeProviderHandles` 引用计数。外部透传应仅在 `Router` 与 `SettingsReadProvider` 层处理。
- 网易云音乐在 `LoginActivity` 启动阶段会通过同进程的 `TreasureProvider` 高频读写大体量 `SharedPreferences` XML（`PreferencesXml.read` 每次同步解析），同时每次 Activity 与 Service 生命周期仍同步等待 `:authority` SQLite 事务——这两处分别将在接下来的 **S6（Activity 生命周期进程内化）**、**S7（Service 绑定透传）** 与 **S9（Storage 用真 SharedPreferences）** 中彻底消除。

#### S6 THIN：Activity 生命周期进程内化（去掉 per-lifecycle broker IPC）（完成）

**目标**：在 `THIN` 模式下，将 Activity 启动与生命周期回调从「每一步都同步阻塞等待 `:authority` SQLite 事务与全量 Intent SHA-256 哈希」收敛为「仅在 `startActivity` 路由（`ROUTE`）分配物理槽位时访问一次 `:authority`，后续创建、生命周期、重入与销毁全部在槽位进程内完成」。

**设计决定**：
- **区分控制面根入口（controller root）与客体内部路由（guest-routed Activity）**：
  - 控制面根入口（`prepareLaunch` / `prepareApplicationLaunch`）在调用方 `startActivity` 前创建，需继续支持控制面的 `abandonLaunch`、`expireEntries` 与 `RuntimeControllerWake`（依赖根 ticket 的 `taskId` 与 `state="LIVE"`）。因此在 `THIN` 模式下，根入口在 `claimCreation` 时执行一次 `ACTIVITY_CLAIM`（同时在单事务内直接将 ticket 置为 `LIVE` 并提交 `launches.received`），在 `attach` 时执行一次 `ACTIVITY_ATTACHED` 记录 `taskId`，在销毁时上报 `ACTIVITY_DESTROYED`；跳过 `ACTIVITY_ENTRY_STATUS`、`ACTIVITY_CREATED`、`ACTIVITY_RESUMED` 及载荷 SHA-256 校验。
  - 客体内部发起的 `startActivity`（例如网易云音乐 `IconChangeDefaultAlias` → `MainActivity` → `LoginActivity`）：在 `ROUTE` 事务内直接将新 ticket 初始化为 `state="LIVE"`、`instance=ticket`、`resourceProof="OWNED_TASK_ATTACHED"`、继承源 Activity 的 `taskId`，并同事务提交 `launches.enter` 与 `launches.received`，同时在 `ROUTE` 返回包与目标 `Intent` extra（`tripoder.thinRoute=true` 等，在 `RuntimeActivityIntent.visible` 中对客体剥离）中携带完整的调用方元数据（`callerKind`/`callerPackage`/`callerComponent`/`callerPhysical`/`expectsResult`）并缓存在进程内 `thinTicketStubs`。目标 Activity 的 `claimCreation`、`activityAttached`、`activityCreated`、`activityResumed`、`newIntent`/`newIntentComplete`、`activityStartResult` 及无 `startActivityForResult` 回调的 `activityDestroyed` **100% 在进程内完成，0 次 `:authority` IPC、0 次 Intent 序列化与 SHA-256 摘要计算**。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/ActivityLedger.java`：`prepareWithCommit` 在 `THIN` 模式下对客体路由直接初始化 `LIVE` 并同事务完成 `enter`+`received`；`claim` 在 `THIN` 模式下对控制面根入口同事务完成 `LIVE` 与 `received`；`source` 与 `destroyed` 兼容进程内实例标识。
- `android/runtime/src/dev/tripoder/runtime/RuntimeBroker.java`：`routeActivity` 在返回包中附带 `activities.caller(delivery, result)` 元数据。
- `android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java`：新增 `thinTicketStubs` 与 `thinControllerRoots`，在 `THIN` 模式下实现 `route` / `routeFromContext` / `claimCreation` / `leasedRecord` / `activityAttached` / `activityCreated` / `activityResumed` / `activityDestroyed` / `newIntent` / `newIntentComplete` / `activityStartResult` 的进程内快路径。
- `android/runtime/src/dev/tripoder/runtime/RuntimeActivityIntent.java`：`visible(Intent)` 剥离 `tripoder.thinRoute` 及调用方元数据 extra，保持客体可见 Intent 干净。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s6-control-evidence --phase control` 全部 69 项断言通过。
- 真机工作台磁贴验证（`d861a4fa`）：从工作台点击网易云音乐磁贴以 `thin` 模式启动（`pid=24849`），历经 `IconChangeDefaultAlias`（`TaskP0$F0M1C0E0`）→ `MainActivity` → `LoginActivity`（`TaskP0$F0M0C2`），子 Activity 生命周期全程在进程内完成，顺利进入并稳定保持在 `mCurrentFocus=Window{... com.netease.cloudmusic/com.netease.cloudmusic.module.login.LoginActivity}`。

**遗留与坑**：
- 在 `ActivityLedger.retireDeadUnattached` 的 `activity_tickets t JOIN sessions s` 查询中，`package` 列在两张表都存在，必须写明表别名 `t.package=?`，否则触发 SQLite `ambiguous column name: package`。

#### S7 THIN：Service 绑定透传（完成）

**目标**：在 `THIN` 模式下，消除 Service 启动、绑定、解绑与生命周期回调（`onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onRebind`/`onDestroy`）对 `:authority` 的逐事件阻塞 IPC（`SERVICE_ACK`、`SERVICE_CLAIM`、`SERVICE_EVENT` 及 `unbind` 的 `SERVICE_ROUTE`）与请求 Intent SHA-256 哈希/白名单校验，改为在首次 `SERVICE_ROUTE` 分配物理槽位后全流程进程内运行，并支持隐式本包 Service 解析与外部 Service 透传。

**设计决定**：
- **路由与分配一次性完成**：`ServiceLedger.reserve` 在 `slot.mode == RuntimeMode.THIN` 时直接将 `service_leases` 行初始化为 `state="LIVE"`、`instance=id`；`RuntimeServiceBroker.route` 在 `THIN` 模式下跳过 `ExecutionContract` 操作记账与 `service_calls` 表写入，仅返回物理槽位映射（`lease`/`stub`/`component`）。
- **零 `SERVICE_ACK`、零 `SERVICE_CLAIM`、零 `SERVICE_EVENT`、零 `unbind` IPC**：
  - `RuntimeServices.route` 在 `THIN` 模式下跳过 `RuntimeServiceIntent.digest(intent)`（同时解除 Intent extras 类型/大小与 `RuntimeServiceBinding.validate(flags)` 白名单限制），将 `stub -> lease` 缓存在进程内 `thinStubs`，并在物理 Intent extra 中附带 `tripoder.thinService=true` 与 `tripoder.serviceComponent`（在 `instantiate` 与 `visible` 中对客体剥离）。
  - `RuntimeServices.ack` 在 `THIN` 模式下直接返回（0 次 `SERVICE_ACK` IPC）。
  - `RuntimeServices.instantiate` 在 `THIN` 模式下直接从 `thinStubs` 或物理 Intent extra 读取 `lease` 与 `component`，跳过 `SERVICE_CLAIM` IPC。
  - `RuntimeServices.lifecycle` 在 `THIN` 模式下直接校验进程内 `identity(service)` 后返回，跳过每次 `onCreate`/`onStartCommand`/`onBind`/`onUnbind`/`onDestroy` 的 `SERVICE_EVENT` IPC。
  - `RuntimeServices.unbind` 在 `THIN` 模式下直接调用 `binding.base.unbindService(binding)`，0 次 `:authority` IPC。
- **隐式与外部 Service 兼容**：在 `THIN` 模式下，当 `startService`/`stopService`/`bindService` 传入隐式 Intent 时，先通过 `RuntimeBridge.resolveOwnService(intent)` 解析本包声明的 Service；对非本包显式 Service 启动/停止尝试透传 `base.startService`/`base.stopService` 并捕获异常返回安全空值。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/ServiceLedger.java`：`reserve` 与 `claim` 在 `THIN` 模式下直接置 `LIVE`。
- `android/runtime/src/dev/tripoder/runtime/RuntimeServiceBroker.java`：`route` 在 `THIN` 模式下免除 `ExecutionContract` 与 `service_calls` 写入。
- `android/runtime/src/dev/tripoder/runtime/RuntimeServices.java`：新增 `thinStubs` 缓存与 `snapshotForMode`，在 `THIN` 模式下实现 `instantiate` / `visible` / `lifecycle` / `start` / `stop` / `bind` / `unbind` / `route` / `ack` / `physical` 的进程内快路径。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s7-control-evidence --phase control` 全部 69 项断言通过。
- 真机工作台磁贴验证（`d861a4fa`）：从工作台点击网易云音乐磁贴以 `thin` 模式启动（`pid=28697`），日志中 `MainProcessPlayService`（`S0_0`）、`IPCCommunicationAndroidService`（`S0_1`）、`WowIPCService`（`S0_2`）、`VideoPlayService`（`S0_3`）均顺利完成 `bindService` / `instantiate` / `unbindService`，全程 0 次 `SERVICE_CLAIM` / `SERVICE_EVENT` / `SERVICE_ACK` IPC，顺利进入并稳定保持在 `mCurrentFocus=Window{... com.netease.cloudmusic/com.netease.cloudmusic.module.login.LoginActivity}`。

#### S8 THIN：Broadcast 真注册（完成）

**目标**：在 `THIN` 模式下，将动态 `BroadcastReceiver` 的注册、注销、广播发送与回调投递从 `:authority` 中心账本（每次 `BROADCAST_REGISTER` + `BROADCAST_REGISTRATION` + `BROADCAST_STICKY_VIEW` + `BROADCAST_ROUTE` + `BROADCAST_CLAIM` / `BROADCAST_SYSTEM_CLAIM` + `BROADCAST_EVENT` 多跳 IPC 与白名单限制）降级为直接向系统 `engine.host` 真注册、身份改写与系统直接投递，全程 **0 次 `:authority` broker IPC**。

**设计决定**：
- **动态 `registerReceiver` / `unregisterReceiver` 真注册（0 次 broker IPC）**：
  - 在 `THIN` 模式下，`registerThin` 自动将 `flags` 规范化为包含 `Context.RECEIVER_EXPORTED` 或 `Context.RECEIVER_NOT_EXPORTED`（若客体传 `0` 则默认补齐 `Context.RECEIVER_NOT_EXPORTED` 以兼容 Android 14+ targetSdk 36 的平台强校验）。
  - 当 `receiver == null`（sticky 广播查询）时，先查进程内 `appStickyFor(filter)`，未命中则直接调用 `engine.host.registerReceiver(null, filter, null, scheduler, physicalFlags)` 并设置客体 `ClassLoader`，解除 `THICK` 模式仅允许单 action 且必须在 `RuntimeSystemBroadcasts.STICKY` 白名单内的限制，0 次 broker IPC。
  - 当 `receiver != null` 时，安装 `RuntimeBridge.installReceiverAdapter(receiver)` 后记录到 `thinReceivers` 集合，并直接调用 `engine.host.registerReceiver(receiver, filter, null, scheduler, physicalFlags)`。由此天然支持同一 `BroadcastReceiver` 实例注册多个 `IntentFilter`（解除 `RECEIVER_MULTIPLE_FILTERS_NOT_IMPLEMENTED`）、任意 `category`/`dataScheme`/`dataType`/`dataAuthority` 过滤器与全部系统广播监听，0 次 broker IPC。
  - `unregister` 在 `THIN` 模式下从 `thinReceivers` 移除并直接调用 `engine.host.unregisterReceiver(receiver)`，0 次 `BROADCAST_REGISTRATION` IPC。
- **隐式/本包广播直接发送与接收回调进程内完成（0 次 broker IPC）**：
  - `send` 在 `THIN` 模式下对非显式组件广播（`logical.getComponent() == null`）走 `sendThin`：将 `package` 为空或等于客体包名的广播改写为 `engine.host.getPackageName()`（限制在同 UID 宿主内投递），支持 `Bundle options`、任意客体自定义 `Parcelable` / `Binder` extras，直接通过 `engine.host.sendBroadcast` / `sendOrderedBroadcast` 交由系统 AMS 投递，0 次 `BROADCAST_ROUTE` IPC；显式组件广播（指向静态 manifest `<receiver>`）继续复用 stub 路由以支持冷启动拉起。
  - `before` / `after` / `asynchronous` / `finished` 在 `THIN` 模式下对 `thinReceivers` 走进程内快路径：校验 `ownPackageEvent`、将物理包名还原为客体包名、注入 `engine.loader`作为 `ExtrasClassLoader`，并压入 `thin=true` 的 `Invocation`，全程跳过 `BROADCAST_CLAIM` / `BROADCAST_SYSTEM_CLAIM` / `BROADCAST_EVENT` IPC。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/RuntimeReceivers.java`：新增 `thinReceivers`、`Invocation.thin`、`thinFlags`、`registerThin`、`sendThin`，并在 `register` / `unregister` / `send` / `before` / `after` / `asynchronous` / `finished` 中接入 `THIN` 模式真注册与零 IPC 投递快路径。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s8-control-evidence --phase control` 全部 69 项断言通过。
- 真机工作台磁贴验证（`d861a4fa`）：从工作台点击网易云音乐磁贴以 `thin` 模式启动（`pid=2120`），全程 0 次 `BROADCAST_REGISTER` / `BROADCAST_REGISTRATION` / `BROADCAST_ROUTE` / `BROADCAST_CLAIM` / `BROADCAST_SYSTEM_CLAIM` / `BROADCAST_EVENT` IPC，无任何 `RECEIVER_*` 或 `BROADCAST_*` 异常，顺利进入并稳定保持在 `mCurrentFocus=Window{86f5085 u0 com.netease.cloudmusic/com.netease.cloudmusic.module.login.LoginActivity}`（`topResumedActivity=TaskP0$F0M0C2`）。

#### S9 THIN：Storage 用真 SharedPreferences + 路径重定向（完成）

**目标**：在 `THIN` 模式下，停用自实现的 `RuntimePreferences` + `PreferencesDisk`（每次读写争抢 `.lock` 文件锁）+ `PreferencesXml`（每次写操作重读/重序列化整份 XML 并双重 `Os.fsync`）以及单线程 `guest-preferences` 执行器 + `QueuedWork` Activity 暂停阻塞屏障，改为通过平台原生 `ContextImpl.getSharedPreferences(File, int)` 直接在重定向后的客体数据目录（`storage.root/shared_prefs/<name>.xml` 及 `device-protected` 子视图）上实例化真 `SharedPreferencesImpl`，并放宽 `THIN` 模式下的历史遗留文件/目录/数据库模式位检查。

**设计决定**：
- **复用固定框架表 `ContextImpl.getSharedPreferences(File, int)`（不碰 native）**：
  - `android.app.ContextImpl` 已在 `FixedFrameworkMembers` 固定注册表（`owner=3`）中。在 `PackageFrameworkAccess` 中绑定 `FixedFrameworkMembers.method(3, "getSharedPreferences", "(Ljava/io/File;I)Landroid/content/SharedPreferences;", false)` 并暴露 `PackageFrameworkAccess.sharedPreferences(Context, File, int)`。
  - 在 `THIN` 模式下，`RuntimeStorage.preferences(name, mode)` 先通过 `file("shared_prefs", name + ".xml")` 完成文件名校验、目录创建与符号链接/路径越界检查，随后将 `mode` 规范化为 `MODE_PRIVATE` 或 `MODE_MULTI_PROCESS`（兼容传入 `MODE_WORLD_READABLE`/`MODE_WORLD_WRITEABLE` 的旧版 SDK），直接调用 `PackageFrameworkAccess.sharedPreferences(host, target, physicalMode)` 返回平台原生 `SharedPreferencesImpl`。
  - 平台 `ContextImpl` 按 `File` 对象维护进程内 `sSharedPrefsCache`，因此普通存储视图与 `createDeviceProtectedStorageContext()` 视图天然隔离在不同 `File` 键下，异步后台加载、内存 Map 缓存、批量 `apply()` 合并写盘均由系统框架原生承担，不再向 `QueuedWork` 注册 `RuntimeStorage::flush` 阻塞主线程。
- **`deletePreferences` 与 `movePreferencesFrom` 兼容**：
  - `THIN` 模式下 `deletePreferences` 先清空内存视图并提交，再删除目标 `.xml` 与 `.bak` 文件。
  - `THIN` 模式下 `movePreferencesFrom` 将源 `SharedPreferences` 快照写入目标 `SharedPreferences` 并清理源文件与备份。
- **放宽 `THIN` 模式下的文件/目录/数据库模式参数校验**：
  - `RuntimeStorage.output`、`RuntimeStorage.database` 与 `GuestStorageContext.getDir` 在 `THIN` 模式下不再对非 `MODE_PRIVATE`/`MODE_APPEND` 历史遗留标志位抛 `FILE_MODE_UNSUPPORTED` / `DATABASE_MODE_UNSUPPORTED`，同时物理文件权限仍保持 `0600`/`0700`。`THICK` 模式保持全部严格校验不变。

**改动文件**：
- `android/self-hook/java/dev/tripoder/selfhook/PackageFrameworkAccess.java`：新增 `sharedPreferences(Context, File, int)` 固定成员调用入口。
- `android/runtime/src/dev/tripoder/runtime/RuntimeStorage.java`：保存 `host` 与 `runtimeMode`，在 `THIN` 模式下将 `preferences` / `deletePreferences` / `movePreferencesFrom` 路由至平台 `SharedPreferencesImpl`，并放宽 `output` / `database` 的模式位检查。
- `android/runtime/src/dev/tripoder/runtime/GuestStorageContext.java`：在 `THIN` 模式下放宽 `getDir` 的模式位检查。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s9-control-evidence --phase control` 全部 69 项断言通过。
- 真机工作台磁贴验证（`d861a4fa`）：从工作台点击网易云音乐磁贴以 `thin` 模式启动（`pid=9484`），日志确认客体调用已走 `android.app.SharedPreferencesImpl`，`RuntimePreferences` / `PreferencesXml` / `PreferencesDisk` 命中数为 **0**，顺利进入并稳定保持在 `mCurrentFocus=Window{1ad6fd5 u0 com.netease.cloudmusic/com.netease.cloudmusic.module.login.LoginActivity}`（`topResumedActivity=TaskP0$F0M0C2`）。

#### S10 census 对比两种模式，达标后把默认值切到 THIN（完成）

**目标**：在真机 `d861a4fa` 上对 `docs/evidence/guest-compat-census-2026-10-19/` 中的 11 款主流商业应用进行 `THICK` 与 `THIN` 双模式对比评测（对比通过率、启动准备耗时 `prepareLaunchMs`、首帧 Activity 确认耗时 `firstActivityAckMs` 及全流程墙钟耗时），并在确认 `THIN` 模式全面达标且优于 `THICK` 后将 `RuntimeMode.DEFAULT` 切换为 `THIN`。

**设计决定**：
1. **`TargetLaunchIntegration` 模式参数与分阶段耗时采集**：
   - 支持 `-e mode <thick|thin>` 参数，并在 `resolveProject` 之前先执行 `setProjectMode`，确保冷启动时 `ApkStore.get` 直接按目标模式的完整性加载策略（`THIN` 信任快路径 vs `THICK` 全量重验）执行。
   - 记录 `prepareLaunchMs`、`workerReadyMs`、`firstActivityAckMs` 与 `totalLaunchMs` 到结果 JSON。
2. **`stopApplication` / `uninstallApplication` 槽位回收完整性修复**：
   - 在连续批量运行 census 时发现：`RuntimeControllerApplications.stop` 未调用 `broker.activities.retireDeadTasks` 与 `broker.jobBroker.cancelForUninstall` + `RuntimeJobRetirement.reconcile`，导致客体进程停止后残留 `OWNED_TASK_ATTACHED` Activity 票据与已调度的 `job_components` 持续钉住物理槽位（引发 `PROCESS_CAPACITY_EXHAUSTED`）。在 `stop()` 与 `uninstallOne()` 中补齐回收逻辑后，连续运行全部 11 款商业应用 22 轮测试零槽位泄漏。
3. **同一安装绑定多项目时的模式继承与冲突防护**：
   - 在 `ProjectTargetBindings.bind` 中，当向已有绑定项目的同一 `(space, package)` 安装绑定新项目时，自动继承该安装上已绑定项目的运行模式（若已有项目模式不一致则立即以 `RUNTIME_MODE_CONFLICT` 拒绝），避免全局默认模式切换后在同一安装上产生隐式模式冲突。
4. **默认模式切换为 `THIN`**：
   - `RuntimeMode.DEFAULT` 由 `THICK` 切换为 `THIN`；`RuntimeStore.onCreate` 中 `project_refs.mode` 默认列值设为 `'thin'`；`WorkbenchProjectActivity` 模式选择弹窗标注 `薄壳模式 (thin)` 为当前默认。

**11 款商业应用真机 Census 双模式对比结果（设备 `d861a4fa`，证据目录 `/tmp/s10-census-compare/`）**：

| 应用 | 包名 | THIN 结果 | THICK 结果 | THIN `prepareLaunchMs` | THICK `prepareLaunchMs` | THIN `firstActivityAckMs` | THICK `firstActivityAckMs` | THIN 总耗时 | THICK 总耗时 |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| 网易云音乐 | `com.netease.cloudmusic` | **PASS (9/9)** | **FAIL**（首帧超时） | 22548 ms | — | **815 ms** | 超时 (>25000 ms) | **31.79 s** | 59.89 s |
| 今日头条 | `com.ss.android.article.news` | **PASS (9/9)** | **FAIL**（启动超时） | 21526 ms | — | **156 ms** | — | **31.25 s** | 52.84 s |
| 大众点评 | `com.dianping.v1` | **PASS (9/9)** | PASS (9/9) | **14641 ms** | 19418 ms | **64 ms** | 738 ms | **22.54 s** | 37.54 s |
| 抖音 | `com.ss.android.ugc.aweme` | **PASS (9/9)** | PASS (10/10) | 19987 ms | 16002 ms | **71 ms** | 419 ms | **29.02 s** | 54.44 s |
| 爱奇艺 | `com.qiyi.video` | **PASS (9/9)** | PASS (10/10) | **16265 ms** | 16558 ms | **34 ms** | 275 ms | **24.15 s** | 31.94 s |
| 哔哩哔哩 | `tv.danmaku.bili` | **PASS (9/9)** | PASS (9/9) | **19036 ms** | 19585 ms | **43 ms** | 142 ms | **26.96 s** | 29.85 s |
| 小红书 | `com.xingin.xhs` | **PASS (9/9)** | PASS (9/9) | **10506 ms** | 10830 ms | **38 ms** | 247 ms | **18.50 s** | 20.93 s |
| 百度地图 | `com.baidu.BaiduMap` | **PASS (9/9)** | PASS (9/9) | **8831 ms** | 9180 ms | **42 ms** | 95 ms | **17.52 s** | 19.77 s |
| 京东 | `com.jingdong.app.mall` | **PASS (9/9)** | PASS (9/9) | **8379 ms** | 8569 ms | **41 ms** | 114 ms | **16.18 s** | 17.29 s |
| 滴滴出行 | `com.sdu.didi.psnger` | **PASS (9/9)** | PASS (10/10) | 7296 ms | 5333 ms | **64 ms** | 142 ms | **15.22 s** | 18.49 s |
| 高德地图 | `com.autonavi.minimap` | **PASS (9/9)** | PASS (10/10) | 7870 ms | 4079 ms | **37 ms** | 89 ms | **15.99 s** | 19.10 s |

- **通过率对比**：**`THIN` 11/11 (100%)** vs **`THICK` 9/11 (81.8%)**。此前在 `THICK` 模式下因高频 Provider/Storage/Lifecycle/Service/Broadcast IPC 阻塞而超时的 **网易云音乐** 与 **今日头条** 在 `THIN` 模式下均一次性稳定通过。
- **首帧 Activity 确认延迟（`firstActivityAckMs`）**：在双方均通过的 9 款应用上，`THIN` 模式首帧确认耗时全面大幅下降（从 `THICK` 的 `89~738 ms` 降至 `THIN` 的 **`34~71 ms`**，提速 **2.2× ~ 11.5×**）。

**改动文件**：
- `android/runtime/src/dev/tripoder/runtime/RuntimeMode.java`：`DEFAULT = THIN`。
- `android/runtime/src/dev/tripoder/runtime/RuntimeStore.java`：`project_refs.mode` 默认列值改为 `'thin'`。
- `android/runtime/src/dev/tripoder/runtime/ProjectTargetBindings.java`：`bind` 在同一安装已有绑定项目时继承其运行模式并校验冲突。
- `android/engine/src/dev/tripoder/runtime/RuntimeControllerApplications.java`：`stop` 与 `uninstallOne` 补齐 `retireDeadTasks` 及 Job 取消与槽位对账回收。
- `android/workbench/src/dev/tripoder/hooklab/WorkbenchProjectActivity.java`：默认模式显示与弹窗文案切换为 `thin`（当前默认）。
- `android/runtime/test/dev/tripoder/runtime/RuntimeModeTest.java`：更新默认模式断言为 `THIN`。
- `android/control-test/src/dev/tripoder/controltest/ControlInstallChecks.java`：适配新建项目默认 `thin` 模式并覆盖 `thin` ↔ `thick` 双向切换与完整性校验计数断言。
- `android/control-test/src/dev/tripoder/controltest/TargetLaunchIntegration.java`：支持 `-e mode` 参数与启动分阶段耗时统计。

**验证（均已执行）**：
- Host：`RuntimeModeHostTest.py` 24/24 通过；`ArtifactSetHostTest.py` 48/48 通过。
- 构建：`build-workbench.py` 成功；`libtripoderselfhook.so` sha256 仍为 `8b07186f18cc507a268e50c354be850615863bad5dbfff97f903430807cb881e`。
- 真机控制面集成测试（`d861a4fa`）：`validate-workbench-control.py --serial d861a4fa --evidence /tmp/s10-control-evidence --phase control` 全部 69 项断言通过。
- 真机默认模式验收（`d861a4fa`）：不传 `-e mode` 参数启动网易云音乐（`com.netease.cloudmusic`），确认自动以默认 `projectMode="thin"` 运行并 9/9 断言全部通过。

