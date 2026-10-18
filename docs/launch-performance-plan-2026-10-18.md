# 客体启动性能：根因与实施方案

2026-10-18。状态：**P1 启动链已实施，但性能门槛尚未通过；本轮已修复冷 authority 的 Provider 全空间校验，焦点验证通过、完整回归仍有阻塞（第 8 节）。P2 E1 已实验：单进程覆盖通过，冷重启门槛失败；P3/P4 未实施。** 所有数字只对应各节标注的构建与设备，换构建须重测。

当前进度以第 7、8 节为准：最初的 R3“客体全量哈希”和“哔哩哔哩不是死锁”判断已被后续证据推翻；不能据第 1、2 节的历史观测继续实施。

| 阶段 | 当前实施进度 | 尚未完成的验收 |
|---|---|---|
| P1 启动链 | 四项代码已落地；本轮新增安装时 Provider 索引、移除全空间预热，并修复 Native 物理路径身份 | ≤200 ms 出窗口、DeepSeek ≤原生 2×、哔哩哔哩首屏与 LOAD ≤2 s 尚未通过 |
| P2 Hook 实验 | E1 单进程覆盖通过，冷重启发现声明方法 flags 被缓存映像带入；E2–E4 未做 | 晚加载 caller 101,002 次零绕过、boot-image 索引、定向 deopt、同构建性能对照 |
| P3 执行策略切换 | 未实施；产品仍全局解释执行 | 必须先完成 P2 全部门槛 |
| P4 DEX 落盘 | 未实施 | 客体 AOT 内联覆盖与只读装载验证 |

以下第 1 节构建为最初诊断基线；本轮构建摘要在第 8 节证据回执中。

- 设备：`d861a4fa`（1208×2624，API 37，固定 libart SHA `2d4a3575…fab13`）
- 构建：分支 `merge-workbench-engine` @ `e501a83`，单 APK `dev.tripoder.engine`

## 1. 现象与实测

用户在应用网格单击后长时间无反应，状态栏仅显示“启动应用 · 处理中”，大应用最终变为“未确认（LAUNCH_PREPARATION_UNCONFIRMED）”。

| 场景 | 点击 → 首帧 | 备注 |
|---|---|---|
| DeepSeek 原生冷启动（`am start -W`） | **253 ms** | `LaunchState: COLD`，含进程 fork、Application、首帧 |
| DeepSeek 在 Tripoder | **≈ 5.6 s** | 前 4.4 s 屏幕无任何变化 |
| 哔哩哔哩 在 Tripoder | **30 s 超时失败** | 两次复现一致：`PROCESS_START_TIMEOUT_UNKNOWN` |

### 1.1 DeepSeek 阶段耗时（logcat 原始时间戳）

```
57.488  ACTION_UP（手指抬起）
57.694  Start proc dev.tripoder.engine:slot2          +0.21 s  点击后才 fork
59.742  TripoderBoot SLOT_PREWARMED:ms=1946           +2.05 s  库加载 + 全局解释策略 + 固定 Hook 预留
00.990  LOAD 完成（binder code 1 took 1244ms）         +1.25 s  verify 156 / dexLoader 202 / hooks 706
01.868  START_APPLICATION（code 13 took 878ms）        +0.88 s  客体 Application.attach + onCreate
01.872  prepareLaunch 返回（control code 2 took 4345ms）
01.886  START RuntimeGuestGateway                      ← 第一个窗口
01.924  START stub Activity TaskP2$F0M2C2
03.054  Displayed +1s132ms                              +1.13 s  客体 Activity 首帧
```

原生全过程 253 ms，Tripoder 中仅 Application + 首帧就约 2.0 s（≈8×）。

### 1.2 哔哩哔哩阶段耗时

```
04.418  Start proc slot3
06.888  SLOT_PREWARMED:ms=2399
        verify +2934ms / archiveInfo +4056 / activityIndex +4190 / dexLoader +9316
        indexes +10100 / register +10120 / hooks +11379   ← 仅 LOAD 就 11.4 s
34.386  PREPARE_LAUNCH_FAILED started=true  PROCESS_START_TIMEOUT_UNKNOWN
```

超时现场：主线程停在 `BiliApp.attachBaseContext → GripperExecute.trigger → runBlocking`，等待 `DefaultDispatcher-worker-*`；工作线程仍在推进（`RuntimeProviderTransport → brokerCall`、`OkHttp api.bilibili.com` 读 socket）。**当时初判为非死锁，后被第 7.1 节推翻**。当时按共享的 30 s 预算（[RuntimeStartupDeadline](../android/runtime/src/dev/tripoder/runtime/RuntimeStartupDeadline.java)）内跑不完：预热 2.4 s + LOAD 11.4 s 后只剩约 16 s 给 Application。

## 2. 根因（按影响排序）

### R1 进程级全局解释执行

[art_interpreter_policy.c](../android/self-hook/native/art_interpreter_policy.c) 在 [`ManagedHook.initializeProcessNative`](../android/self-hook/native/art_managed_entry.c) 中调用固定 `DeoptimizeEverything`，把 instrumentation 级别锁定为 2，且进程生命周期内不释放。结果：客体代码、framework 启动镜像 AOT 代码、Tripoder 自身代码**全部**走解释器，JIT 基本不起作用。

这是 [self-hook-process-interpreter.md](self-hook-process-interpreter.md) 中“覆盖优先”的有意选择，起因是运行 `eca1662b` 中**激活后新加载的 caller 被 JIT 内联**，第 67,682 次调用绕过 Hook。文档当时已注明“会增加 Java 执行成本，尚无产品性能验收”。本次实测即该成本：Java 执行约 5–10×，大应用超时。

### R2 每次点击才 fork slot 进程，“预热”在关键路径上

[RuntimeSlot.onCreate](../android/runtime/src/dev/tripoder/runtime/RuntimeSlot.java) 执行 `loadLibrary` + [`RuntimeFixedPreparation.prewarm`](../android/runtime/src/dev/tripoder/runtime/RuntimeFixedPreparation.java)，但它由 [`ProcessSupervisor.start`](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java) 在点击后 `bindService` 触发，每次启动付出 2.0–2.4 s。

### R3 初判：客体侧每次全量重新校验制品（已推翻，见 7.1）

[`RuntimeEngine`](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java) 构造时调用 `ArtifactSet.loadVerified`，在客体进程（全局解释下）重新哈希整个 APK；权威侧已有 [ArtifactIntegrityCache](../android/runtime/src/dev/tripoder/runtime/ArtifactIntegrityCache.java)，客体侧未复用。哔哩哔哩 `verify` 2.9 s、`archiveInfo` 1.1 s。

### R4 `InMemoryDexClassLoader` 无 vdex/oat

[RuntimeDexLoader](../android/runtime/src/dev/tripoder/runtime/RuntimeDexLoader.java) 每次从内存装载 DEX，没有校验缓存与编译产物；哔哩哔哩 `dexLoader` 5.1 s。

### R5 启动串行化，无启动窗口

[`RuntimeControllerLaunch.prepare`](../android/engine/src/dev/tripoder/runtime/RuntimeControllerLaunch.java) 调用 `processes.ensure(...)`（`awaitApplication=true`），**等客体 Application.onCreate 完成**才返回 capability；[WorkbenchAppActions](../android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java) 拿到后才 `startActivity(RuntimeGuestGateway)`。原生系统在点击约 100 ms 内即显示 starting window，进程与 Application 在其后进行。

## 3. 目标架构（最佳实践）

原则：**启动链对齐原生 Android；Hook 覆盖只对“会被绕过的调用点”精确处理，而不是让整个进程解释执行。** 主流 ART Hook 库（LSPlant/LSPosed、Pine）均采用“入口替换 + 目标不可编译/禁内联 + 对已内联 caller 定向 deopt”；`deoptimizeEverything` 一类全局解释只用于调试场景。

### 3.1 Hook 执行层

| 绕过来源 | 处理 | 状态 |
|---|---|---|
| 目标方法自身入口 | 入口改写到 relay（现有中央 writer） | 已有 |
| 未来 JIT 编译的 caller 内联目标（`eca1662b` 反例） | 目标标记不可编译（`CompileDontBother`，内联器以 `kNotInlinedNotCompilable` 拒绝），或把 JIT 内联上限设为 0 | **待在固定 libart 上实测** |
| 已存在 AOT 代码中的内联（boot image、未来客体 oat） | 按 boot image 指纹**离线生成内联索引**（目标 → 内联它的 caller 集合）；启用 Hook 时对这些 caller 执行既有 `SuspendAll → Deoptimize → ResumeAll` | **待实现** |
| 已在栈上的旧 AOT 帧 | 不覆盖；维持“客体首条代码前安装固定 Hook”边界 | 与现状相同 |

失败语义（不做自动回退）：内联索引缺失、boot image 指纹不符、任一 caller deopt 失败，Hook 启用**明确失败**；不得静默切回全局解释或放行。

与 [self-hook-process-interpreter.md](self-hook-process-interpreter.md) 第 29 行“不给声明目标设置 CompileDontBother”冲突，切换前须更新该文档并取得确认。

### 3.2 启动链

1. **立即出窗口**：点击后先启动 Gateway → stub Activity，显示启动页；进程准备、LOAD、Application 在后台；Activity 请求在客体进程内排队至 `application-ready`。
2. **空闲进程池（对应 Android USAP）**：空闲时维持 1 个已完成 `SLOT_PREWARMED`、未绑定任何客体身份的 slot；点击时分配，启动凭证在 LOAD 时下发。
3. **校验结果复用**：客体侧不再全量哈希；由权威侧校验后传递可核对的标识（inode/size/mtime/摘要），或使用 fs-verity 由内核强制完整性（需实测可用性）。
4. **DEX 落盘只读加载**：复用类校验结果；系统是否为其生成编译产物需实测。客体有 AOT 后，其内联同样纳入 3.1 的内联索引。

## 4. 分阶段实施

### P1 启动链（不改变 Hook 语义）

| 项 | 改动点 | 说明 |
|---|---|---|
| P1-1 立即出窗口 | [RuntimeControllerLaunch](../android/engine/src/dev/tripoder/runtime/RuntimeControllerLaunch.java)、[ControllerLaunchLedger](../android/runtime/src/dev/tripoder/runtime/ControllerLaunchLedger.java)、[ProcessSupervisor](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java)、[WorkbenchAppActions](../android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java)、[RuntimeGuestGateway](../android/engine/src/dev/tripoder/runtime/RuntimeGuestGateway.java) | `prepare` 在槽位预留与 capability 签发后即返回（不等 `ready`）；Gateway 立即启动；stub Activity 在客体进程内等待 `application-ready`。失败须在窗口内给出明确错误并关闭，状态仍由执行契约记录 |
| P1-2 空闲进程池 | [RuntimeSlot](../android/runtime/src/dev/tripoder/runtime/RuntimeSlot.java)、[ProcessSupervisor](../android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java) | 引擎空闲时 bind 1 个 spare slot；`bootstrapAbort` token 从 bind extra 移到 LOAD；占用 `CAPACITY=8` 中的 1 个，须在容量报告中显示；系统回收 spare 不影响正确性 |
| P1-3 校验复用 | [RuntimeEngine](../android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java)、[ArtifactSet](../android/runtime/src/dev/tripoder/runtime/ArtifactSet.java)、[ArtifactIntegrityCache](../android/runtime/src/dev/tripoder/runtime/ArtifactIntegrityCache.java) | 客体侧以权威侧签发的文件标识核对，不再全量哈希；标识不符则 LOAD 失败 |
| P1-4 预算拆分 | [RuntimeStartupDeadline](../android/runtime/src/dev/tripoder/runtime/RuntimeStartupDeadline.java) | LOAD 与 Application 分别计时并在超时回执中区分阶段；不延长总等待来掩盖慢 |

### P2 Hook 执行层实验（只做实验，不改产品）

| 实验 | 方法 | 通过条件 |
|---|---|---|
| E1 禁内联 | 在固定 libart 上对目标设 `CompileDontBother`（或 JIT 内联上限 0），关闭全局解释，重放 `eca1662b` 类晚加载 caller 场景 | 101,002 次调用 0 绕过；JIT 仍编译非目标方法 |
| E2 boot image 内联扫描 | 对当前 boot image 用 oatdump 提取 inline info，与固定 2177 个目标求交 | 产出“目标 → caller”索引及指纹；列出命中数 |
| E3 定向 deopt | 对 E2 命中 caller 执行现有 `SuspendAll → Deoptimize → ResumeAll` | H3m 新调用路径对照、P4 Java/Native 矩阵全部通过 |
| E4 性能对照 | 同一构建在“全局解释”与“E1+E3”下测 DeepSeek / 哔哩哔哩 | 见第 5 节性能门槛 |

### P3 切换执行策略

E1–E4 全部通过后：`initializeProcess` 不再调用 `DeoptimizeEverything`；Hook 启用路径接入内联索引与定向 deopt；更新 [self-hook-process-interpreter.md](self-hook-process-interpreter.md)、[self-hook-runtime-bootstrap.md](self-hook-runtime-bootstrap.md)。

### P4 DEX 落盘

[RuntimeDexLoader](../android/runtime/src/dev/tripoder/runtime/RuntimeDexLoader.java) 改为只读文件加载；若产生客体 AOT，先把其 inline info 纳入索引再启用。

## 5. 验收门槛

**覆盖（P3 前必须全部通过）**
- 101,002 次晚加载 caller 调用 0 绕过
- H3m 在途帧对照（新调用路径）
- P4 Java/Native 全矩阵、固定 25 项 / 2177 绑定安装回执
- E2 内联索引对 2177 目标的命中全部有定向 deopt 证据

**性能（同设备、冷启动、各 5 次取中位数）**

| 指标 | 门槛 |
|---|---|
| 点击 → 第一个窗口可见 | ≤ 200 ms（P1-1 后） |
| DeepSeek 点击 → 客体首帧 | ≤ 原生 2×（原生 253 ms） |
| 哔哩哔哩 | 稳定进入首屏，不触发 `PROCESS_START_TIMEOUT_UNKNOWN` |
| LOAD 阶段 | 哔哩哔哩 ≤ 2 s（现 11.4 s） |

## 6. 风险与边界

- 同 UID 不提供强隔离，P1-3 的校验复用不降低现有安全边界，但须保证标识不可被客体在 LOAD 前替换。
- 空闲进程占用一个槽位与常驻内存；系统可能回收，回收后退化为现状的冷 fork，不属于错误。
- P1-1 改变执行契约中“capability 签发 ⇢ Application 就绪”的先后，须同步更新 [execution-contract.md](execution-contract.md) 并重跑启动、恢复、入口关闭相关测试。
- P3 是对既有用户决策（启动前建立全局解释策略）的变更，须经确认后执行。
- 已在栈上的旧 AOT 帧在任何方案下都不覆盖，此边界不变。

## 7. 实施记录（P1，2026-10-18）

测试目标：哔哩哔哩（诊断）、DeepSeek（端到端）。设备与构建同第 1 节。

### 7.1 对第 2 节根因的修正
- **R3 不成立**：客体侧 `loadVerified` 本就不哈希 APK。哔哩哔哩 `verify` 2.7 s 是解释执行下解析 883 KB 描述符 JSON（其中 25,623 条资源 entries 占 2.2 s）；`dexLoader` 里 33 个 DEX 各开一次 `ZipFile`，每次解析中央目录（约 5 s 空白）。DEX 复制加哈希并行，仅几十毫秒/个。
- **哔哩哔哩 30 s 超时是死锁，不是慢**：它在 `attachBaseContext` 的 `runBlocking` 里等工作线程，工作线程访问同进程的 `tv.danmaku.bili.provider.xpref`，该 provider 要等主线程在 `Application.attach` 之后统一安装。真实 Android 对同进程 provider 返回 `canRunHere`，由调用线程就地安装。

### 7.2 已实施
| 项 | 改动 | 实测 |
|---|---|---|
| P1-4 | `ProcessSupervisor`：超时错误带阶段 `PROCESS_START_TIMEOUT_UNKNOWN:phase=BIND\|LOAD\|APPLICATION` | 总预算仍 30 s |
| P1-3（重定义） | 权威侧下发精简描述符侧文件（`launch-lite/`，仅保留顶层 DEX）+ 文件树戳摘要；客体 `ArtifactSet.loadTrusted` 只核对戳与文件摘要。DEX 装载共用一个 `ZipFile` | 哔哩哔哩 LOAD 12.3 s → 5.5 s；`verify` 2.7 s → 0.5 s |
| 死锁 | `RuntimeProviders.ready`：同进程、非主线程提前访问时在调用线程安装，原子认领，主线程不重复安装 | 哔哩哔哩越过 attach；DeepSeek 无回归 |
| P1-1 | `prepare` 在槽位预留与 capability 签发后即返回；`RuntimeGuestGateway` 后台 `awaitReady` 后 `consume`；capability 有效期自启动截止时间起算；失败/销毁走 `ControllerLaunchLedger.abandon`（UNKNOWN） | 点击 → Gateway 窗口 0.34 s（原 4.4 s）；契约见 `execution-contract.md` |
| P1-2 | `ProcessSupervisor` 维持一个无身份预热 worker（最高空闲 index，至少保留一个空闲槽）；启动选中同 index 再绑一次，经新增 wire `ABORT_CONFIGURE` 补发中止凭证；其他分配找不到槽位时先淘汰它；`capacity()` 增加 `spare` | DeepSeek 点击 → 客体首帧 7.9 s → 4.7 s |

### 7.3 验收状态
- 真机 `validate-workbench-control`：`control`（59）、`app-actions-ui`（20）、`hook-ui`（11）、`library-ui`（14）通过。`library-ui` 首轮一次脚本编辑器滚动定位失败（`library-ui-03` 保留），重跑通过（`library-ui-04`）。
- 为适配 P1-1，`ControlLaunchChecks` 与三处 UI 集成测试改为通过 `hookProcesses` 等待 worker 就绪（`ControlTestBase.awaitWorker`）；原“prepare 返回时已有运行中 worker”的断言已改为“已预留槽位”。
- 未达标：点击 → 第一个窗口 0.34 s（门槛 ≤ 200 ms）；DeepSeek 首帧 4.7 s（门槛 ≤ 原生 2× ≈ 0.5 s，需 P2/P3）；哔哩哔哩仍不能到首屏，见 7.4。

### 7.4 当时记录的哔哩哔哩阻塞（非性能）
越过死锁后当时观察到两处启动期崩溃。本节是历史记录；本轮核对发现现有源码已接受空/嵌套 external type 和非零 receiver priority，因此不能继续将其标成“尚未修改”；当前业务首屏仍需新构建实测：
1. `getExternalFilesDir` → `RuntimeStorage.name` 抛 `INVALID_FILE_NAME`。
2. `registerReceiver` 带 `priority=990` 的过滤器被 `RuntimeBroadcastIntent.filter` 拒绝（`RECEIVER_FILTER_UNSUPPORTED`）。

### 7.5 测量注意（历史预热条件）
authority 进程冷启动后第一次 `ApkStore.get` 会对整个 APK 做完整校验并持有 broker 锁（DeepSeek 约 17 s）。性能测量不要 `force-stop` 整个引擎；用应用长按菜单“强行停止”只停客体。

### 7.6 “一直停在正在启动空间中的应用…”的原因与缓解（2026-10-18）
冷 authority（系统回收、重装 APK、`force-stop` 后）下，客体首次解析 provider（哔哩哔哩的 `tv.danmaku.bili.provider.xpref`）时，`RuntimeProviderBroker.acquire` 在持有 broker 锁的同时遍历同空间**全部**安装并调用 `ApkStore.get`；校验缓存为空，每个 APK 整包重新哈希（0.5–4 s，27 个安装合计约 24 s）。期间客体的 `recordStart` 等调用拿不到 broker 锁，共享的 30 s 启动预算耗尽，Gateway 报 `PROCESS_START_TIMEOUT_UNKNOWN:phase=APPLICATION`。P1-1 之后窗口先出现，所以用户看到的是等待页而不是原来的“处理中”。

缓解：`ProcessSupervisor.warmArtifacts` 在 authority 启动 1 s 后用低优先级后台线程预先校验所有已提交安装（日志 `ARTIFACTS_WARMED`，本机 27 个约 24 s）。语义不变（仍整包校验，只是提前）。**未解决**：预热完成前的启动仍会等待；`acquire` 持锁遍历全空间的设计未改（根治需要把 authority→provider 的索引放到安装时建立，或遍历移出锁）。


## 8. 本轮继续实施：冷 authority 的 Provider 路由

### 8.1 根因与改动

Provider 解析需要的是“当前空间中谁声明了该 authority”，完整 APK 校验只用于确认选中的目标可执行。旧实现把两者混在持锁的全空间遍历中，导致无关 APK 大小、损坏或缓存状态进入每次 Provider 请求的关键路径。后台 `warmArtifacts` 不能保证用户点击前完成，也会额外抢占启动 I/O。

- 新增 `InstallProviderIndex`：安装提交事务同时写入 `install_provider_authorities`，唯一键为 `(space, authority)`，通过安装 generation 外键绑定生命周期；升级替换、卸载删除由同事务及外键级联完成。
- 安装冲突检查复用此索引；同包重复 authority 合并，禁用声明仍保留安装占位，跨包冲突仍拒绝。
- `RuntimeProviderBroker.acquire` 锁内认证和索引查询，锁外只对命中 APK 调用 `ApkStore.get`；回锁后再次认证并核对索引与安装代次，再按真实 enabled/exported/process 元数据路由。无索引返回不存在，无旧路径回退。
- 数据库 schema 31 → 32：在升级事务中从既有 `descriptorSha` 认证的描述符提取 authority，不打开 APK；迁移失败使整个升级回滚并明确报 `PROVIDER_INDEX_MIGRATION_FAILED`。索引仅用于定位，不替代命中 APK 的完整校验。
- 删除启动后全安装后台预热。性能测试须包含真正冷 authority，不能把“等待预热完成”当作验收前提。

### 8.2 回归暴露的启动阻塞

首轮 `provider-index-01` 的 12 项索引检查通过后，在 `HOOK_CONTROL` 安装阶段出现 `HOOK_MODULE_CANONICAL_ROOT_CHANGED`：`RuntimeHookBridge` 在存储 Hook 安装前保存物理根路径，之后 `File.getCanonicalPath()` 使用的 readlink 返回已虚拟化的客体路径，二者不再属于同一个路径视图。

修复复用现有 `tp_openat` 路径策略，以只读 `O_PATH` 打开制品、核对实际 FD 为普通文件，并直接从 `/proc/self/fd` 取得物理身份。Native allowlist 与后续 ELF 检查统一使用物理路径，仍与保存的物理根路径严格比较；客体 readlink 视图不变，没有放松摘要检查或增加路径候选回退。首轮产品控制测试也在 worker 死亡后失败，证据保留于 `provider-product-control-01`。

### 8.3 验证

SDK 编译 277 个源文件、现有主机行为检查通过。

- `provider-index-01`：12 项真实 SQLite/描述符检查通过，随后在旧物理/逻辑路径混用处失败。
- `provider-index-02`：路径修复后通过客体 LOAD、Application 与 Provider 初始化；完整 providers 套件在 `provider-attach-does-not-expose-appops-to-guest` 失败。当前 `GuestContext` 将未建模服务交给平台，旧套件和 Provider 文档仍要求 AppOps 仅供 attach 内部使用；这是独立的服务策略/验收冲突，本轮不改变该策略也不修改失败断言。
- 新增独立 `provider-index` 阶段，以真实客体 ContentResolver 访问本地、别名、远程及跨包 Provider，验证缺失和 nonexported 路由；清空验收 authority 的完整性缓存，并在同空间保留无关 CORRUPT 安装，要求完整校验次数只增加两个（两个实际命中 APK）。这不替代完整 Provider 套件。
- [`provider-index-03`](evidence/launch-performance-2026-10-18/provider-index-03/device-observation.json)：独立冷缓存验收 **35 项通过**。两轮均只新增 2 次 APK 完整校验，无关 CORRUPT 安装未参与；覆盖本地/别名、远程、跨包、缺失、nonexported、跨空间和宿主 decoy 零命中。两轮查询场景耗时 8304/8319 ms，含远程客体进程冷启动，不是单次索引查询耗时，也不是产品首帧指标。
- [`provider-native-p4-01`](evidence/launch-performance-2026-10-18/provider-native-p4-01/device-observation.json)：Java/Native Hook **105 项真机检查通过**；对应上文物理路径身份修复，不是性能方案 P4 DEX 落盘已实现。
- 一次产品重建被 `SOURCES_CHANGED_DURING_BUILD` 拒绝；发现 Native `path_policy.c` 在构建期间被并发修改。该构建没有有效回执，后续必须使用源码一致的重建产物。

产品控制 `provider-product-control-02` 在真实数据库 schema 32、安装、升级及项目重绑检查通过后，因系统 `ActivityThread.handleDispatchPackageBroadcast → IPackageManager.notifyPackagesReplacedReceived` 未被虚拟包端点实现而导致 worker 崩溃；未消费启动凭证因进程死亡失败。此处与 Provider 索引无关，但属于产品启动可靠性的真实缺口，未以延长预算或忽略异常掩盖。

不重复安装的产品重跑在执行前被 `SOURCE_CHANGED_SINCE_BUILD:android/workbench/src/dev/tripoder/hooklab/WorkbenchOperations.java` 拒绝；不能宣称当前完整产品源码已验收。归档测试回执与当前源码的核对还发现探针 fixture `storage_probe.c` 在测试后被并发修改；Runtime 实现输入仍匹配已测试回执，但不能声称当前全部探针输入原样通过。探针/产品源码核对与完整结果见 [本轮证据总表](evidence/launch-performance-2026-10-18/README.md) 和 [source-audit.json](evidence/launch-performance-2026-10-18/source-audit.json)。

尚未重测 DeepSeek/哔哩哔哩五次冷启动中位数；不据焦点测试宣称第 5 节性能门槛达标。Provider 路由之外（例如广播解析和目标 APK 首次完整校验）的耗时不在此索引修复的证明范围内。

### 8.4 下一步顺序

1. 先在 `RuntimePackages` / `RuntimePackageBridge` 补齐包替换通知的明确框架回执路径，并统一 `GuestContext.getSystemService` 的 AppOps 服务策略与完整 Provider 验收契约；独立 Provider 冷缓存验证已通过，不能替代这两个缺口。
2. P2 先做 E1 晚加载 caller 禁内联实验，再生成 E2 boot-image 内联索引并做 E3 定向 deopt；实验不改变产品全局解释策略。
3. E1–E4 覆盖与性能门槛全部通过后才进入 P3；P4 只读 DEX 装载放在覆盖策略闭环后。当前不能将 P1 的窗口提前等同于客体首帧性能达标。


## 9. 启动契约根因修复与 E1 准备（2026-10-18 续实施）

### 9.1 能力边界

- AppOps 根因：GuestContext 在显式服务分发之后把所有未知服务交给物理 Context，绕过 attach-only 能力边界。删除该通用透传，未建模服务明确报 SYSTEM_SERVICE_UNSUPPORTED。平台 Provider attachInfo 仍通过受控上下文取得一次 AppOps；客体 onCreate、委托 Provider 和后续查询不能取得它。
- Manager 原方法根因：已准入的音频/网络/电源平台操作在虚拟 facade 上执行，私有辅助方法再次被当成客体操作拦截。现在只对已经通过既有策略准入的操作选择真实 manager，以原参数调用，保留系统异常；不增加 getContext 等私有方法白名单。主机新增接收者身份、异常保真、错误物理对象拒绝检查。
- ActivityManager 契约恢复：删除没有任务/服务账本支撑的假空列表，未实现任务查询继续拒绝。已实现的进程内存观测按安装内 PID 做真实查询；验收分别检查自身 PID 的真实 PSS 与 PID 1 的越权拒绝。
- Shortcut 契约恢复：原实现没有 Launcher 入口状态，却返回空列表或写入成功，与 `runtime-shortcut-capability.md` 矛盾。删除假成功分支，只保留 pinSupported=false，其他未实现操作明确拒绝。
- TrafficStats 契约恢复：方法名通配 `setThreadStats*` 错误包含 `setThreadStatsUid`，把标签与统计归属身份混为同一能力。改为精确标签/套接字操作集合；跨 UID 查询和 UID 改写继续拒绝。
- WakeLock 测试校准：创建 WakeLock 已有明确的平台路由，测试改为验证创建后未持有锁；不 acquire。reboot、越权包查询等原拒绝断言保留。

### 9.2 包替换通知

`ActivityThread.handleDispatchPackageBroadcast(PACKAGE_REPLACED, ...)` 会通知系统 PackageManager 哪些物理 LoadedApk 已处理。原虚拟包端点遗漏了该通知，UnsupportedOperationException 逃逸至主线程并杀死 worker。现在用固定 Framework member 绑定精确 `notifyPackagesReplacedReceived(String[])`，转发给启动时保存的物理 endpoint；不改写包名、不换 UID、不吞异常、不做通用 PM 透传。

机制核对：[ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ActivityThread.java)、[PackageManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/PackageManagerService.java)、[ComputerEngine](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/ComputerEngine.java)。AOSP 用于解释机制；固定 API37 的绑定与执行以设备回执为准。

初次系统 `--dont-kill` 主 APK 替换仍被 OEM 终止所有进程，保留于 `contracts-package-replaced-01`，没有宣称 worker 存活。验收随后改为仅在 probe 中，从真实 worker 主线程调用固定 ActivityThread 处理函数，并经过实际 PackageManager Binder 回执；生产控制接口不暴露此注入能力。

### 9.3 回归记录

- `contracts-package-replaced-02`：6 项通过，空集合及物理宿主包集合回执均被记录，PID 与 generation 不变。这是实际框架处理函数注入验证，不是 OEM 安装路径的存活证明。
- `contracts-product-control-01`：58 项通过，包含真实安装、升级、启动凭证到期、authority 死亡恢复及 Java/Native Hook 控制。
- `contracts-providers-01/02/03` 保留逐轮失败：AppOps 断言已通过；后续分别暴露 manager 私有辅助调用、WakeLock 旧断言、Shortcut 假成功问题。没有删除这些失败证据。
- 主机 PreferencesWaitTest 原瞬时线程状态断言存在竞态，失败后未释放等待线程；改为对真实 Future.get 尝试同步并在 finally 释放。主机检查重新通过。
- 验证器保存启动测试时读取的构建回执，避免测试结束时误复制已被重建覆盖的回执。

最终 `contracts-providers-07` 完整 Provider/系统服务回归 **478 项通过**，包含全部 AppOps、身份、权限、资源和能力边界断言。源文件 SDK 编译 278 个通过。固定构建的产品控制 58 项通过；最终源审计另发现 RuntimeHookClaims.java 在设备回归后被并发修改（见 contracts-runtime-source-audit.json），归档结果不能扩展到当前全部工作区。随后最终产品重验受并发工作台/Agent 修改影响，先出现源码摘要变化，再出现 WorkbenchAddActivity 引用缺失 UI 方法的编译错误，未将这些未通过构建作为验收产物。

E1 在上述 Runtime 回归后以独立探针实施，结果见第 10 节。


## 10. P2 E1 实验结果：单进程可行，冷重启仍阻断

复用未修改的 `OwnedIntHook` 和固定 ART，产品全局解释策略未变。实验仅为静态 int(int) 目标，不代表通用 ManagedHook 或 P3 已通过。

- 首轮新进程：instrumentation level 在 before / prepare / active / disable 全为 **0**；晚加载 caller 与无 Hook 对照方法均有真实 **RX JIT code-cache** 入口；**101,002 次调用零绕过**，callbacks/originals 精确相等，停用后原调用正确。
- 同一 APK 第二次冷启动：未 prepare/enable Hook、目标调用计数为 0 时，初始 flags 已是 **0x12280009**，与首轮停用后的值一致（首轮初始为 **0x10280009**）。目标 ArtMethod 位于 `cache/oat_primary/arm64/base.art` 映像区，探针报 **E1_PREEXISTING_COMPILE_DONT_BOTHER**。没有清标志、清缓存或启用回退。
- 独立校验分别确认首轮成功和冷重启反例；**E1 整体门槛尚未通过**。第二轮在 flags 门禁处退出，未采集其 instrumentation level，不能推定为 0。

[两轮证据与复现](evidence/launch-performance-2026-10-18/e1-no-inline/README.md)。下一步先解决声明 ArtMethod 标志进入运行时映像的所有权/恢复边界，或验证不写入声明方法持久状态的进程级 JIT 内联控制，再重跑 E1 冷启动。不得据首轮覆盖成功直接切换 P3；E2 内联索引、E3 定向 deopt、E4 性能对照均未完成。
