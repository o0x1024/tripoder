# Tripoder 兼容性续修交接

接手目标是继续解决两份原始兼容性报告的问题，并在当前镜像上完成逐 App 验收。当前已完成一组可复用的底层修复和自有探针验收；**尚未完成 123 个真实客体的全量 THICK 验收，不能宣称全部兼容。**

本次交接复核了本地构建收据：当前源码的 604 个构建输入与最终验收镜像一致。本次仅整理交接，没有重跑设备测试。设备结果以下述冻结证据为准。

## 接手基线

| 项目 | 值 |
| --- | --- |
| 仓库 | `/Users/like/code/tripoder` |
| 当前分支 | `merge-workbench-engine` |
| 工作区 | 有大量已修改和未跟踪文件，包含此前工作；本轮没有提交或推送 |
| 最后验收设备 | `d861a4fa`，macOS 主机通过 adb 连接 |
| 独立 QA 包 | `dev.tripoder.compat20261023` |
| 原引擎包 | `dev.tripoder.engine`，本轮没有替换或清理其数据 |
| 最终 engine SHA256 | `61863549e82207cdd9f445322d60cb9a71230cc31f31b6f392e1588204ba923c` |
| 最终快照 | `docs/evidence/compatibility-closure-2026-10-23/phase84c-contracts/` |
| 本地当前构建收据 | `android/product/build/build-receipt.json` |
| QA 构建开关 | `--startup-diagnostics --recovery-acceptance`；后者加入自有 nonexported 故障测试服务 |

保留当前工作区和全部证据；不要 reset、clean、清库或用新 space 绕开旧 UNKNOWN。上轮结束时没有正在运行的设备 campaign；接手后仍须重新检查设备、安装摘要和 instrumentation 状态。

## 先读这些记录

1. [最终机器可读验收汇总](evidence/compatibility-closure-2026-10-23/phase84-final-handoff.json)：当前阶段的结论、镜像、运行结果和未完成项。
2. [修复记录顶部](compatibility-repair-2026-10-23.md)：最新阶段在前，后面的历史待办必须与当前源码核对，不能直接当作当前失败清单。
3. [最终八次运行](evidence/compatibility-closure-2026-10-23/phase84c-app-gates.json)：具体运行目录、断言和异常统计。
4. [接续日志末尾](evidence/compatibility-closure-2026-10-23/WORKING.md)：定位更早失败与分析过程；其中中间的 ACTIVE 状态已由末尾最终结果覆盖。
5. 原始需求报告：仓库根目录 `Tripoder-兼容性测试报告.md`、`Tripoder-THICK兼容性报告.md`。

## 已完成的修复与实际验收

### 此前分阶段修复

当前工作区还包含以下更早的修复，迁移代码时需要一起保留：

- PendingIntent / Job 的历史 owner 解析与作用域修正，防止单个 App 的 UNKNOWN 错误扩大成全局围栏；保留原业务 UNKNOWN。启动前增加容量预检，alias 按实际请求分配，而不是按声明数量消耗容量。
- 原始 APK 材料化与索引修正：读取无 launcher 包、区分 `lib/` 下的普通资源和平台原生库、处理重复非 provider 声明及 base 的 `uses-split` 语义，统一跨层上传限制。原包材料化/验签通过不等于应用启动或业务通过。
- [信号交付修复](evidence/compatibility-closure-2026-10-23/phase65-signal-delivery-closure.json)：自有探针验证处理器内系统调用、errno 与信号 mask；[原生库身份修复](evidence/compatibility-closure-2026-10-23/phase66-native-library-closure.json)：统一执行目录和依赖搜索来源，消除无 SONAME 库重复实例导致的 JNI 状态分裂。
- 跑测移除自动清库，深层 XML 改为迭代处理，日志和截图内容先脱敏再持久化。此前的全量历史成功数不能直接沿用为当前镜像的通过率。

完整历史修复、原始 App 症状与当时验证范围见主修复记录和 WORKING 日志；以下是最近实施且已有完整验收的核心能力。

### 真实隔离 Service

接入 THICK、同包、创建型 bound Service 的真实 Android isolated process。每实例拥有独立物理名称和 capability，登记真实 Binder PID/UID 后才发布校验的只读 DEX/APK FD。使用客体 ClassLoader、资源、Context 和原 guest Binder，生命周期绑定到物理 Service 的真实 token。

复用 `sessions / service_leases / service_calls / operations`，schema 为 39，记录进程角色、实例和真实 UID。普通进程查找与容量不再混入 isolated 角色。同实例可复用同一代；真实死亡后才释放并允许新代。

修复最后解绑的误暂停：AMS 可能在最后解绑后直接以 `isolated not needed` 杀进程。只有所有已应用 bind 都有精确对应的持久 unbind、且没有未完成的新 bind 时，才预先关闭该实例 admission；不伪造 STOP ACK 或死亡，仍等待真实 Binder death。

最终自有场景 **31 项通过**，包括两个不同 kernel isolated UID、同实例复用、一次工厂和 Application/Service 创建、空创建 Intent、10 DEX 顺序、FD 资源、私有路径拒绝、真实解绑死亡和另一实例存活。历史误暂停记录保留，新测试当前 authority 暂停为 0。

### 隔离原生代码

建立一个准入前确定的系统原包发布契约：原包必须已由系统安装，版本、当前签名者、完整 base/split 集合及每个 APK 摘要与客体完全一致。按真实 `extractNativeLibs` 标志选择 DIRECTORY 或 APK ZIP；失败不切换执行来源。

authority 在 broker 锁外解析与校验，回锁后重新核对调用者、安装代、数据代和组件；bootstrap 发布前再次校验。隔离子进程独立校验系统拥有的 `/data/app` 路径、文件身份及 APK/SO 摘要，并在执行客体代码前建立限定原包根目录的 InMemory ClassLoader namespace。ZIP 模式校验真实条目布局、STORED 和对齐。DEX/资源仍通过 FD 交付。

DIRECTORY 与 APK 两种布局在 phase81 各 **25 项真实生产路径检查通过**，包括有/无 SONAME、JNI_OnLoad 一次、FindClass 定义加载器、名称/绝对路径及 DT_NEEDED 实例一致。最终 phase84 镜像重跑 APK 路径 25 项通过；DIRECTORY 未在最终镜像重跑，其原生实现源码摘要与 phase81 相同，证据不能混称为同镜像重测。

当前仍显式拒绝未由系统安装的原生客体，以及声明 Java/native/static/SDK 共享库依赖图的隔离原生客体。不要删除此拒绝来制造 Chrome 通过。

### 停止和真实崩溃恢复

停止从派发前计算统一期限，每个不可变进程代只派发一次 STOP；只有真实死亡证明才能释放资源。此前自有 SIGSTOP 场景已验证约 15 秒返回 UNKNOWN，并保留仍存活的槽位与 Activity 引用。不能把 ACK、超时、缺失 `/proc` 或裸 PID 当作死亡。

最终镜像 **23 项真实 authority 崩溃恢复检查通过**：自有服务 fsync 计数器并记录操作已开始后，对自身 SIGABRT；观察 Binder death，kernel ESRCH 确认旧 authority、父进程、隔离进程消失。按现行策略使用一次显式重试，恰好新增一次 authority 尝试及 controller lease；没有宣称自动恢复。

原未完成副作用保持 UNKNOWN、计数始终为 1；同 ID 和同作用域新 ID 均不能重放。没有自动重启 guest。显式新启动得到新隔离代和 lease，仍活绑定时通过正常 controller 停止，最终测试耗时 230ms，确认死亡和释放，当前子进程暂停为 0。原 UNKNOWN 内容及更新时间保持不变。

### 重启后旧任务阻塞

设备在一次回归观察期间重启，系统保留旧最近任务 9910，却丢弃 baseIntent 的四个 Tripoder extras。旧 session 已 DEAD、票据仍 LIVE；原回收只靠 Intent 标记定位根票据，导致正常停止后仍拒绝新启动。

新增持久 controller root 身份证明：唯一 taskId/physical stub、可信 controller 的 RECEIVED/CREATE、candidate=receiver、generation/原 instance 一致、无 guest source 或结果接收者、仍匹配且不可 reparent 的组件 lease。现存 extras 必须一致，所有 owner 及整个安装的 session 必须严格 DEAD。继续使用既有围栏、二次观测、真实任务移除和缺席确认。

原任务实际回收，票据正常 RETIRED，原操作结果和更新时间、安装代与数据代不变；随后同数据 31 项场景通过。新增 **28 项模型检查**覆盖重复 root、子 Activity、冲突标记、错误 lease、跨安装、非 DEAD、可 reparent 和重建实例等拒绝。当前证明不覆盖重建过的实例或 alias root，不能直接放开。

### 最终验收数量

| 项目 | 最终结果 |
| --- | --- |
| 设备契约与 scratch SQLite 模型 | 486 项通过，不是 486 个 App |
| 固定框架绑定及晚期入口 | 702 个绑定、14 项入口检查通过 |
| 主机编译与行为检查 | 370 个 Java 源文件及主机检查通过 |
| 当前构建输入 | 604 个摘要一致 |
| 真实 authority 崩溃恢复 | 23 项通过，单独记录预期 SIGABRT |
| 隔离 Java Service | THICK 31 项通过 |
| 隔离原生 APK 路径 | THICK 25 项通过 |
| 普通 Service | THIN/THICK 各 21 项通过 |
| 核心能力 | THIN/THICK 各 195 项，含真实 WebView 本地 HTML 和 JavaScript |
| 既有原生库身份 | THIN/THICK 各 7 项通过 |

上述最后五类合计 8 次自有测试应用运行，均完成并确认停止；正常运行无 Java fatal、native fatal signal 或固定绑定发布失败。它们不是 8 个第三方 App 的完整业务验收。

## 源码入口

以下路径相对于仓库根目录。先用 CodeGraph 查结构、调用与影响；已知文件可直接读取。新加未跟踪类曾遇到索引滞后，不要把索引没有结果误当作文件不存在。

| 功能 | 入口 |
| --- | --- |
| Service 路由及准入 | `android/runtime/src/dev/tripoder/runtime/RuntimeServiceBroker.java`、`ServiceLedger.java` |
| 隔离实例、bootstrap、生命周期、恢复 | 同目录 `RuntimeIsolatedBroker.java`、`RuntimeIsolatedService.java`、`RuntimeIsolatedApplication.java`、`RuntimeIsolatedContext.java`、`RuntimeIsolatedCode.java`、`RuntimeIsolatedSessions.java` |
| 原生发布身份与执行路径 | 同目录 `RuntimePublishedNativeCode.java`、`RuntimePublishedCodeFiles.java`、`RuntimeNativeZipLayout.java` |
| 既有共享库图 | 同目录 `RuntimeSharedLibraries.java`，后续优先复用其语义 |
| 真实 Service token 与 loader namespace | `android/self-hook/java/dev/tripoder/selfhook/ServiceInstanceAccess.java`、`ClassLoaderNamespaceAccess.java` |
| 任务 root 与回收 | runtime 同目录 `RuntimeTaskRootIdentity.java`、`RuntimeEmptyTasks.java`、`ActivityLedger.java` |
| 进程停止和业务围栏 | runtime 同目录 `ProcessSupervisor.java`、`RuntimeStoppedEffectProof.java`、`RuntimeEffectRecoveryProof.java`、`RuntimeStore.java` |
| App 停止和卸载 | `android/engine/src/dev/tripoder/runtime/RuntimeControllerApplications.java` |
| 新增契约和恢复测试 | `android/compatibility-test/EmptyTaskReclaimChecks.java`、`IsolatedNativePublicationChecks.java`、`IsolatedRecoveryIntegration.java`、`TaskRootInspection.java` |
| 原生真实探针 | `android/isolated-fd-probe/`，生产路径入口是 `ProductionActivity` / `ProductionService` |
| 自有 Java 隔离 fixture | `android/compatibility-test/isolated-service-fixture/`，`Main` 是 31 项场景，`RecoveryHold` 持续保持真实绑定 |
| 驱动与脱敏 | `scripts/qa/paired_campaign.py`、`paired_capture.py`、`paired_log.py`、`run_isolated_recovery.py` |

phase81→84 的生产输入差异只有新增 `RuntimeTaskRootIdentity.java` 和修改 `RuntimeEmptyTasks.java`。整个 dirty worktree 则包含此前多阶段修复，不能只搬这两个文件当作完整成果。

## 未完成项与首个实施目标

| 项目 | 当前明确缺口 | 接手动作 |
| --- | --- | --- |
| Chrome / renderer | 隔离原生共享库图及 split、renderer 服务组合尚未接通并验收 | 读取真实 manifest、完整 APK 集合、声明库与服务属性；先明确实际所需契约，复用现有共享库解析和身份状态 |
| 原生发布范围 | 未系统安装的原生客体尚无已验收可执行发布路径 | 保留明确拒绝；不要改成私有 FD 执行或 memfd 失败后换来源 |
| 其他隔离服务形态 | THIN、external/cross-package、替换 ClassLoader 尚不支持 | 分别建立 owner、生命周期与权限契约，不按普通同包 bound Service 推断 |
| OEM Rust 后端 | typed ABI、spawner 及 AMS 注册/归属尚未实现并验证 | 依据实机二进制和实际调用证明实施；Java 启动路径不能代替此后端 |
| 快手资源对象 | 已验证 APK/资源表与实际 native 资源对象的来源关系未闭环 | 证明真实 backing APK 和实际加载表的联合身份，不能仅按标签或整包摘要放行 |
| 夸克退出 | 停止与恢复准入已修复；首代 SIGKILL 原因仍 UNKNOWN | 按精确进程代关联退出原因、系统事件和首因；不要直接判成应用自杀或风控 |
| 重建或 alias 任务根 | 当前持久 root 证明不覆盖 | 补真实 root attachment 证明及负例，再讨论放行；不修改旧行制造匹配 |
| 全量业务验收 | 当前镜像尚未完整重跑原报告 123 包 | 建逐 App 矩阵，分别记录支持、启动、首屏、稳定性、停止/恢复、登录/业务和未测原因 |

建议首个实施目标：**完成 Chrome 所需的共享库依赖和 renderer 服务契约的一条最小完整路径。** 先盘点实际 split、库图、进程和服务属性，不假定只需删除一个拒绝条件。利用自有 fixture 证明依赖闭包、ART/JNI 身份、真实隔离 UID、多实例和死亡回收，再测试原始 Chrome APK。冷启动成功、WebView 测试通过或 renderer 出现均不能单独算 Chrome 通过；至少验证页面加载与 JavaScript、renderer 身份和生命周期、停止/重开及新增异常。

若需新增状态，先检查现有 `sessions / leases / calls / operations / ArtifactSet` 能否表达。不得新增 fallback、默认 Java 替代、伪成功返回或关闭身份校验。文件超过 2000 行按功能拆分。

## 构建与复跑

以下命令在仓库根目录执行。所有 shell 命令使用 `rtk`。SDK、NDK、JDK 为最后验收的本机路径；换机器先核对。证据目录必须使用新的未存在目录。

### 本地构建

```sh
rtk proxy python3 android/scripts/build-workbench.py \
  --startup-diagnostics --recovery-acceptance \
  --package dev.tripoder.compat20261023 \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64

rtk proxy python3 android/runtime/self-hook-tests/run.py \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --android-jar /opt/homebrew/share/android-commandlinetools/platforms/android-37.0/android.jar \
  --apksig-jar android/product/build/apksig.jar
```

### 设备契约和测试工具

安装器、validator、campaign 必须串行；每条命令真实退出后才能运行下一条。它们共用 QA `.tests` 包，会互相替换 instrumentation。示例目录 `next-contracts`、`next-fixed-registry` 必须改为本轮唯一新名称。

```sh
rtk proxy adb devices -l

rtk proxy python3 scripts/qa/validate_compatibility_repair.py \
  --serial d861a4fa \
  --native-publication-apk docs/evidence/compatibility-closure-2026-10-23/phase81-native-production-fixture-v11/fixture.apk \
  --evidence docs/evidence/compatibility-closure-2026-10-23/next-contracts

rtk proxy python3 scripts/qa/validate_fixed_component_registry.py \
  --serial d861a4fa \
  --evidence docs/evidence/compatibility-closure-2026-10-23/next-fixed-registry

rtk proxy python3 scripts/qa/build_paired_harness.py --serial d861a4fa
```

原生 publication 检查还依赖 `android/isolated-fd-probe/build/receipt.json`、当前系统安装的自有 probe，以及冻结的旧版本与未安装 companion 输入。V11 原包摘要为 `e70c1d5a78031ae862a5e91065fc6a48d2bbbb24134258ddeda734b2a8725e20`。若 probe 已升级，应同步验证输入和构建收据，不能用旧 V11 冒充当前版本。新增 companion 曾被设备安装策略拒绝，该次没有执行测试；生产路径探针不依赖安装这个 companion。

### 已验收基线的运行方式

以下示例只适用于设备仍安装最终 phase84 镜像，且 `build_paired_harness.py` 已完成。若修改并构建了新镜像，参数必须换成新 validator 生成、与设备摘要一致的快照目录，不能继续引用旧 phase84。

```sh
rtk proxy env TRIPODER_PAIR_MODES=thick \
  python3 scripts/qa/paired_campaign.py \
  docs/evidence/compatibility-closure-2026-10-23/phase84c-contracts \
  dev.tripoder.isolatedservicecontract

rtk proxy env TRIPODER_PAIR_MODES=thick \
  TRIPODER_PAIR_COMPONENTS='{"dev.tripoder.isolatedfdprobe":"dev.tripoder.isolatedfdprobe.ProductionActivity"}' \
  python3 scripts/qa/paired_campaign.py \
  docs/evidence/compatibility-closure-2026-10-23/phase84c-contracts \
  dev.tripoder.isolatedfdprobe

rtk proxy python3 scripts/qa/paired_campaign.py \
  docs/evidence/compatibility-closure-2026-10-23/phase84c-contracts \
  dev.tripoder.servicecontract dev.tripoder.closurefixture dev.tripoder.nativelibrarycontract
```

真实崩溃复跑仅用于自有 QA：它会升级同一自有 fixture、停止其进程，对自有 authority SIGABRT，并保留新产生的 UNKNOWN 和自有计数器。应先确认没有其他 QA guest 工作，并使用新的证据目录。

```sh
rtk proxy python3 scripts/qa/run_isolated_recovery.py \
  --serial d861a4fa \
  --snapshot docs/evidence/compatibility-closure-2026-10-23/phase84c-contracts \
  --fixture docs/evidence/compatibility-closure-2026-10-23/phase79-isolated-service-fixture-v7b/fixture.apk \
  --evidence docs/evidence/compatibility-closure-2026-10-23/next-real-isolated-recovery
```

V7b 自有 fixture 摘要为 `cb70afa3bacbf71a4a81c5606b38c3a29017413307b4872aefacf98ed4bcdbe0`。最后验收时，Java fixture 数据代为 `376f13c3-f34e-4cff-a0e9-b09e801d9458`，原生 fixture 数据代为 `f8413d61-fccd-4781-ba6a-1692e8ddb235`；它们用于核对保留数据，不用于直接写数据库。

## 继续实施时的验收规则

1. 先保存分支、dirty 文件范围、当前构建/安装摘要及逐 App 状态，再修改源码；不要覆盖其他工作的未提交内容。
2. `passed` 和实际断言、停止确认、退出首因、session/lease 状态必须一起检查。`complete`、进程出现、无 Java crash 或 WebView 通过均不能替代目标 App 验收。
3. 观测窗口：自有核心与隔离探针为 120 秒，普通场景为 30 秒。设备断连、重启或 instrumentation 超时保留为未完成；host adb observer 退出不证明 Android 测试已结束，先确认状态再安装其他工具。
4. 不清数据、不清 journal、不删除旧失败证据、不直接重写 UNKNOWN，不按裸 PID 结束未知进程。恢复只依精确 owner、generation 和真实死亡证明。
5. 正常日志先经 `paired_log.py` 脱敏再持久化；保留结构化运行时记录和首因，禁止保存账号、验证码、签名值或金融页面敏感内容。金融 App 只做已授权的只读导航，生物识别交给用户。
6. 复用当前探针和业务状态；真实未支持的契约明确拒绝。每次修复后用同镜像完成必要回归，再建立新的冻结证据目录。

当前全部已确认结果、未完成项和复跑入口都保存在仓库内，不依赖 `/tmp`。严格汇总脚本已冻结在 `evidence/compatibility-closure-2026-10-23/phase84-host/collect-phase-gates.py` 和 `finalize.py`；它们绑定旧阶段和本机路径，接手后应先读再用于新阶段，不能覆盖原结果。
