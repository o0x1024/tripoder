# Tripoder 当前任务

更新时间：2026-10-16（Asia/Shanghai）。工作目录：`/Users/like/code/tripoder`。

本文件只写**当前状态与下一步**。历史快照已归档，不要按旧状态倒退：

- H25–H36：[docs/task-history-h25-h36-2026-10-16.md](docs/task-history-h25-h36-2026-10-16.md)
- H25 之前：[docs/task-history-before-h25-handoff-2026-10-16.md](docs/task-history-before-h25-handoff-2026-10-16.md)
- 最近一次详细交接：[docs/runtime-handoff-2026-10-16-h37.md](docs/runtime-handoff-2026-10-16-h37.md)
- 文档总索引：[docs/README.md](docs/README.md)

## 1. 最终目标与完成边界

微信 `com.tencent.mm` 和王者荣耀 `com.tencent.tmgp.sgame` 在 Tripoder 内**实际正常运行**，且正常 Java/Native Hook 的安装、调用、停用、卸载不导致 panic、Java/Native crash 或 ANR。

- 必须真实 UI、登录、后台/冷恢复和业务链验收。fixture、Application ready、Activity 创建、15/30 秒存活都不能替代业务完成。
- 禁止宿主微信参与客体登录作为替代路径。
- **目标尚未完成。** 两个应用都没有达到稳定首屏。

## 2. 当前制品与现场（H37）

- Runtime APK：`2003244000e98e9e53398c8b3c6afcab796f6de9eea6953f52df0d833fc42a6d`，已构建、安装、归档于 `docs/evidence/process-slot-ownership-h37/artifacts/20032440/`。473 个源输入复核 `changedInputs=[]`。
- Native SO：`a7ba136f…`，与 H36 GC 压力包相同。**Native 目录此后又有改动，该 SO 可能已过期，见第 5 节。**
- 设备 `d861a4fa`，Xiaomi/madrid Android17/API37/arm64/4KiB，Runtime UID 10409。
- 正式 Engine/Workbench/control-test 构建已过期，不得称当前产品已验收。
- 工作区未 commit/push，约 212 项 dirty/untracked，且没有任何文件被 git 跟踪。**不要清理或还原。**

## 3. 已验收（各自绑定对应构建，不跨构建追认）

| 范围 | 结论 | 证据目录（`docs/evidence/` 下） |
|---|---|---|
| GC root 首次枚举 | 三个独立冷进程，每轮 121 项通过 | `framework-gc-h36/gc-02..04-interleaved` |
| 进程槽与 Activity 资源分离 | 跨安装冷/热迟到入口 14 项通过 | `process-slot-ownership-h37/reuse-04` |
| 实际 SQL 所有权判定 | 33 项 host 检查通过 | `process-slot-ownership-h37/sql-policy.json` |
| 主机回归 | 304/15/85/117/331/8/45/12，SDK 230 | `process-slot-ownership-h37/host-04.log` |
| 历史（H35 及更早） | Job 生命周期/冷恢复 15、调度 69、完整 P3 358、P4 105 等 | 见 H35 交接，均为旧构建 |

固定适配：25 项真实安装、2177 个 managed 固定绑定，guest 首次执行前完成。存储 Hook 已改为自研核心，不依赖近地址跳转页。

## 4. 阻塞与待办（按顺序）

### 4.1 Context 回归停止点（最先处理）
- `process-slot-ownership-h37/provider-context`（run `provider-identity-104db46a92d54076`，APK 20032440）：整轮在第 19 项 `application-start-failure-is-explicit` 失败，位置 `P2ProbeActivity.testPackageResources`。后面 26 项 PackageContext 检查未执行；无 Java/Native fatal，ANR=false。
- `rejected(...)` 辅助方法只保留匹配 bool，**原始异常没写入报告**。首要假设是第四个并发进程（`:historyfail`）容量不足，**未确认**。先保存原始异常和逐槽原因，再决定是否重排或拆出定向验收。不得降低断言或把整轮改绿。
- 遗留一个未派发票据 `f22fd4de-81bd-426d-8ae2-fb6c5256021d`（generation `c17d52dd…`，space `provider-identity-104db46a92d54076-provider-identity`）：DEAD、closeRequested=0、PREPARED、无 entryFence，仍占 D 槽执行权。须按精确自有测试关闭协议处理，保留 UNKNOWN/租约，不直接改库。应把 Activity 准备推迟到依赖就绪后，避免前置失败继续产生未派发票据。

### 4.2 微信
- 复用现有空间 `target-3be31e2dd7434859-wechat`，不新建空间、不占用新 affinity。
- 已越过：Package Context、telephony 只读查询（H36）。已进入真实 UI `LauncherUI`/`WeChatSplashActivity`。
- **H36 记录的下一个首因**：`PRODUCT_MEMBER_MISSING: Context$BindServiceFlags.getValue()J`。设备上该方法不是声明方法。**源码里 `ComponentFrameworkAccess.java:24` 仍用 `getValue()J`，未见修复；H37 之后也未复测微信，不能假定已闭合。**
  下一步：枚举 owner 17 的实际成员/字段，找提取 long 的真实途径，改访问路径，补公共 SDK fixture 覆盖 `bindService(BindServiceFlags)`。不加 flag、不返回 null、不发往宿主。
- 最近一次微信测试（`framework-gc-h36/wechat-context-01`）在 Matrix 绑定额外服务时 `PROCESS_CAPACITY_EXHAUSTED` 并自行 `exit(1)`。
- 之后逐一修复实际首个启动错误；不把死亡后的恢复错误当首次根因。

### 4.3 王者荣耀
- 当前已知阻塞：WorkManager 创建 PendingIntent 崩溃。王者 targetSdk=30，但平台读取宿主进程的 `Compatibility.isChangeEnabled(160794467)`，补 flag 或返回 null 都不成立。
- 需要自研广播 PendingIntent/Alarm 真实路由：持久 creator 独立于 session、`NO_CREATE` 查真实系统、mutable fill-in 不改物理路由、真实 Alarm 与冷投递验收。见 [docs/runtime-pending-intents-plan.md](docs/runtime-pending-intents-plan.md)。
- 之后才是 Unity/Native/图形启动（`libunity`、`libil2cpp`、`libGameCore` 及 `libtprt`/`libpandora`/`libpuerts` 依赖）、首屏、登录后资源加载、输入、性能。见 [docs/hok-compatibility.md](docs/hok-compatibility.md)。

### 4.4 全量回归与正式产品
- 当前源 P4（普通 Java/Native/mixed 安装、调用、停用、卸载、退出归因）与必要生命周期回归。
- 正式 Engine/Workbench 重建与控制验收（`control`、`hook-ui`、Agent `normal`/`disconnect`）。
- 与第 5 节的 Native 验收合并为**同一批**，不单独重复。

### 4.5 两应用最终验收
- 微信/王者 UI、登录、后台/冷恢复、业务场景，确认正常 Hook 无 panic/crash/ANR。

### 长期开放项（不阻塞上面顺序，但会反复咬人）
- **item-3**：未附着的 UNKNOWN ticket（PREPARED/CLAIMED、attach 前进程死亡、无 taskId）永久占槽。退休需要真实 Android task 证据，当前无公共 API；不清库、不扩容、不改写 UNKNOWN。每次完整 P2 和微信反复 attach 前死亡都会累积。
- **MIUI 自启动**：服务重启被小米策略拒绝，plain adb 无法授予，需设备端手动开启。非代码缺陷。
- Job authority 实际死亡恢复、取消 UNKNOWN 收口、组件/affinity 资源真正退休：未完成。

## 5. 并行工作：动态 Native 导出 Hook 改造（另一个 agent）

范围：动态 Native 导出函数 Hook。沿用 `RuntimeNativeHookBackend → NativeHook → tp_native_*`；不改 ART 方法 Hook、libc 路径策略、linker 的业务语义；单一后端、无 fallback、无 RWX。

| 阶段 | 内容 | 与本任务关系 |
|---|---|---|
| S0 P0 | 固定基线与证据格式、覆盖矩阵 | 先做 |
| S1 P0 | 生命周期与入口窗口（在途计数、迟到入口、卸载/同地址重载） | 对应“正常 Hook 不崩溃” |
| S2 P0 | 准备/发布失败分类与资源账目 | 同上 |
| S3 P1 | 近地址分配与指令准入 | **须由真实拒绝样本驱动；动共享 `near_address.c`/`a64_relocate.c` 会影响固定 libc 和 ART，需联合回归** |
| S4 P1 | 实测热点优化 | 与最终目标无关，后置 |
| S5 P1 | 同源码同构建的产品链验收 | 与 4.4 合并 |
| S6 P2 | 已发布资源安全回收 | 单独立项 |

协调约定：
- 对方负责 `android/self-hook/native/**`、`NativeHook.java`、`RuntimeNativeHookBackend.java` 及 Native 探针；本线负责 Context、微信、王者等 Java 层 Runtime 文件。
- **避免同时改** `ActivityLedger`、`RuntimeReceivers`、`RuntimeBroadcastBroker`、`RuntimeSystemBroadcasts`、`RuntimeSelfHook*Managers*`；动之前先确认对方是否在改。
- 最近两小时内 `native_hook.c`、`near_address.c`、`scalar_inbound*`、`linker_notifier.*`、`module_lifetime.c` 等已被修改。**这些改动是否已包含在 APK 20032440 中未知**，S0 须对整个工作区固定源码摘要。
- 一台设备同一时间只有一个安装/启动负责人；验收期间冻结构建输入；失败证据不覆盖。
- S3 之前先拿到微信/王者稳定启动证据作回归基线；目标库中被拒绝的真实函数前导反馈给 S3。

## 6. 续接命令

所有 shell 以 `rtk` 开头。一次只有一个设备负责人；证据目录必须新建且唯一，不覆盖失败目录。

~~~sh
# 微信复测（复用已核验安装）
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-wechat-dir> --phase target --target-package com.tencent.mm --target-space target-3be31e2dd7434859-wechat
# 当前源 P4
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-p4-dir> --phase p4
~~~

其余阶段（`p0`/`p1`/`p2`/`p3`/`packages`/`creation`/`failure`/`entry`/`notification` 等）同模板，替换 `--phase`。正式控制：

~~~sh
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence <new-dir> --phase control --install
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence <new-dir> --phase hook-ui
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence <new-dir> --phase normal
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence <new-dir> --phase disconnect
~~~

重建（Runtime 与正式产品使用同一组显式参数；正式产品脚本为 `build-workbench.py`）：

~~~sh
rtk proxy python3 android/scripts/build-runtime-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64
~~~

Runtime 重建会重编 fixture，时间戳/SHA 变化，正式包也要跟着绑定新 fixture；不安装旧包冒充当前源。

主机关键检查：

~~~sh
rtk proxy python3 android/self-hook/tests/check_fixed_libc_admission.py
rtk proxy python3 android/runtime/self-hook-tests/run.py --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home --android-jar /opt/homebrew/share/android-commandlinetools/platforms/android-37.0/android.jar --apksig-jar /opt/homebrew/share/android-commandlinetools/build-tools/37.0.0/lib/apksigner.jar
rtk proxy node android/runtime/script-tests/run.mjs
~~~

设备故障先读终态 result/error/原始 owned-crash/bootstrap-log，再修根因，不放宽断言。已过且无相关改动的测试不为凑数量重跑。

## 7. 纪律与边界

- 仅自有 APK 做 P0–P4 类验收；微信/王者验证已获用户明确授权（覆盖旧阶段“不运行第三方测试”的限制）。其它安全/数据保留边界继续有效。
- 不使用 root、`run-as`；不清库/清缓存/删除 UNKNOWN 或旧业务数据；不扩槽绕过；不加 fallback。
- 已授权设备 `d861a4fa`，锁定 Xiaomi Android17/API37/arm64/4096 页固件。
- 正式 Engine/Workbench 使用独立 UID；guest 与 Engine 同 UID，不宣称内核级隔离、通用 APK 或 P6 完成。
- 单个源文件超过 2000 行按职责拆分；以第一性原理定位根因。
- CodeGraph MCP 未暴露，`.codegraph/` CLI 索引可用但有滞后。
- 不要把某一阶段通过后把 `fullP2Accepted`/`fullP5Accepted`/P6 等标志机械改为 true。

## 8. 资料入口

- [当前产品证据索引](docs/evidence/self-hook-product-2026-10-16/README.md)
- [启动链](docs/self-hook-runtime-bootstrap.md)、[存储绝对入口](docs/self-hook-fixed-libc.md)、[迁移清单](docs/self-hook-adapters.md)
- [验收矩阵](docs/self-hook-acceptance-plan-2026-10-16.md)（A1 以已 236 通过为准，矩阵旧快照待更新）
- [共享 Runtime 构建说明](android/runtime/README.md)
- [Job 实施计划](docs/jobscheduler-implementation-plan.md)、[H35 Job 交接](docs/jobscheduler-handoff-2026-10-16-h35.md)
