# 兼容性报告评估与修复记录（2026-10-23）

接手实施请先读：[Tripoder 兼容性续修交接](compatibility-handoff-2026-10-24.md)。

## 重启后旧任务回收与当前验收（2026-10-24，phase84）

设备在上一轮回归观察期间重启，旧运行没有完成凭证，保留为未验收。重启后系统仍保留任务 9910，却丢弃了 baseIntent 的四个 Tripoder extras；旧 session 已 DEAD，但旧任务票据仍 LIVE。原回收逻辑只从 Intent 取 root ticket，正常停止无法移除该任务，后续启动因 `APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED` 被拒绝。

现在用持久化的已认证 controller root 证明回收身份：唯一 taskId / physical stub 绑定、trusted controller 的 `RECEIVED/CREATE` 回执、candidate / receiver / generation / 原 instance 一致、无 guest source 或结果接收者，以及仍匹配且不可 reparent 的组件 lease。平台现存 extras 仍须一致；同任务的所有 owner 与整个安装的 session 仍须严格 DEAD。复用既有回收围栏、二次平台观测、实际移除和缺席确认；不按 PID、任务号或缺少 extras 单独推断归属。

- 只读保存原任务 9910 的旧行，确认所有证明字段匹配且四个 extras 全缺失。通过正常路径回收后，系统任务实际不存在，票据 RETIRED / `TASK_REMOVED_CONFIRMED`；原启动操作的结果、原因与更新时间均未改写。原安装代和数据代未变化，随后 **31 项真实隔离 Service 场景通过**。证据：[phase84-retained-task-closure.json](evidence/compatibility-closure-2026-10-23/phase84-retained-task-closure.json)。
- **486 项设备契约通过**，包含新增 28 项任务 root 正反例：子 Activity / CONTEXT 来源、重复 root、复用 delivery、冲突 extras、错误组件 lease、可 reparent、重建实例、跨安装、非 DEAD 或缺 session 均不能借用该证明。原业务 UNKNOWN 的内容和更新时间保持不变。另有 702 个固定绑定和 14 项入口检查通过；370 个 Java 源文件与主机检查通过，604 个构建输入摘要一致。
- 当前镜像 `61863549e82207cdd9f445322d60cb9a71230cc31f31b6f392e1588204ba923c` 再次通过 **23 项真实 authority 崩溃恢复检查**：实际 Binder death 和旧进程 ESRCH、一次显式重试、新 controller lease、原 UNKNOWN / 自有计数器不重放，均与 phase82 一致。新代活绑定 Service 正常停止耗时 230ms，实际死亡及 lease 释放确认，当前子进程暂停为 0。证据：[phase84-real-isolated-recovery/result.json](evidence/compatibility-closure-2026-10-23/phase84-real-isolated-recovery/result.json)。
- 同镜像最终 **8 次实际运行全部完成并确认停止**：隔离 Service 31 项、隔离原生 APK 路径 25 项，普通服务两模式各 21 项、核心两模式各 195 项、既有原生库身份两模式各 7 项。核心两模式均实际渲染自有本地 HTML 并执行 JavaScript；8 次正常运行无 Java fatal、native fatal signal 或固定绑定发布失败。真实崩溃测试的预期 SIGABRT 单独记录，不计入这组正常运行。证据：[phase84c-app-gates.json](evidence/compatibility-closure-2026-10-23/phase84c-app-gates.json)。
- 系统 native 目录的 25 项证据仍来自 phase81；phase81→84 的生产差异仅为任务身份 helper 和回收入口，原生发布/隔离/namespace 代码摘要未变。本轮重跑了 APK 路径，未把旧 DIRECTORY 证据冒充为最终镜像上的重测。设备当前 engine 与测试 APK 摘要均与冻结快照一致，604 个构建输入全部匹配。最终汇总：[phase84-final-handoff.json](evidence/compatibility-closure-2026-10-23/phase84-final-handoff.json)。

此 root 证明尚不覆盖重建过的实例或 alias root；缺少相应证明时继续保留资源。中断运行、修复前真实拒绝和新增模型夹具的两次构造失败均保留。跑测器现在对未完成、未确认停止或 instrumentation 超时立即返回失败，不再继续把后续阶段当成同一组成功。

**仍不能宣布所有客体 THICK 通过。** 当前原生 isolated Service 仅接收系统已安装、内容与客体完全一致且不声明共享库依赖图的原包。下一步应接入并验证 Chrome 的 split / shared-library 及 renderer 服务契约；OEM Rust 后端、快手资源对象来源和夸克 SIGKILL 首因继续保留为未完成项。原 Tripoder 安装和数据未替换，当前验收镜像仍为带自有 recovery fault service 的独立 QA 构建。

## 隔离原生代码与真实崩溃恢复（2026-10-24，phase78–82）

THICK 同包创建型 bound isolated Service 已接入原生代码。执行来源采用一个准入前确定的系统发布契约：真实 PackageManager 中已安装的原包，版本、当前签名者、完整 base/split 集合及每个 APK 摘要必须与客体安装一致。依据原包 `extractNativeLibs` 标志选择系统 native 目录或原始 APK ZIP，执行失败不切换来源。DEX/资源继续使用校验的只读 FD；原生库使用真实系统发布路径，保持 ART 定义加载器、JNI 与依赖实例一致。

authority 在 broker 锁外解析并验证原包，回锁后重新核对调用者、安装代、数据代及组件才准入；发布 FD 前重验。隔离子进程另行验证系统拥有且非不可信用户可写的 `/data/app` 目录、规范路径、APK/SO 大小和摘要，持有只读 FD，并在 guest 代码执行前建立限定原包根目录的 InMemory ClassLoader namespace。APK 模式还验证实际 ZIP central/local headers、STORED 条目和对齐。没有使用私有文件执行、手工 `dlopen`、补调 `JNI_OnLoad` 或放宽 namespace 到 ALL。

- 自有 probe 经正常系统升级及客体安装升级，**DIRECTORY / APK 两种真实生产路径各 25 项通过**。物理原包 UID 10462 与 QA UID 10460 不同；四个子进程均为 kernel isolated UID，回调 Binder 身份、实际 guest 定义加载器、有/无 SONAME 的 JNI 一次初始化、FindClass、名称/绝对路径及 DT_NEEDED 实例一致性均通过。最后解绑后 session DEAD、lease RELEASED、当前 authority 子进程暂停为 0。两次测试的数据代均为 `f8413d61-fccd-4781-ba6a-1692e8ddb235`。证据：[phase81-real-isolated-native-closure.json](evidence/compatibility-closure-2026-10-23/phase81-real-isolated-native-closure.json)。
- **真实 authority 崩溃恢复 23 项通过**：保持自有 Java isolated Service 活绑定后，QA 专用 fault service 在 fsync 自有计数器并记录操作已开始后对自身执行 SIGABRT；观测实际 Binder death，并用 kernel ESRCH 确认旧 authority、父进程、隔离子进程均不存在。按现行策略使用一次显式重试，恰好新增一次 authority 尝试及新 controller lease；没有宣称自动恢复。
- 原未完成操作恢复为 UNKNOWN；自有副作用计数始终为 1，同操作 ID 和同作用域新 ID 均不能重放。客体没有自动重启；显式启动后产生新隔离代与 lease，随后在仍活绑定时通过正常 controller 停止，220ms 返回并确认真实死亡、资源释放、当前子进程暂停为 0。原 UNKNOWN 的内容与更新时间保持不变，既有数据代 `376f13c3-f34e-4cff-a0e9-b09e801d9458` 保留。证据：[phase82-real-isolated-recovery/result.json](evidence/compatibility-closure-2026-10-23/phase82-real-isolated-recovery/result.json)。
- 镜像 `3b024e8f9ca4440b5c93adc94423107397785cdc5d63cba114736dbd533e961a` 已通过 **458 项设备契约、702 个固定绑定和 14 项入口检查**；369 个 Java 源文件及主机检查通过，603 个构建输入摘要一致。15 项新增 native publication 契约覆盖错误作用域、私有路径、摘要、安装版本、库集合及缺少系统发布等拒绝路径。镜像为独立 QA 包，启用了 `--recovery-acceptance`，仅额外包含自有 nonexported 故障测试服务；原 Tripoder 安装未替换。

保留的负例包括私有 FD 执行 EACCES、无 SONAME memfd ZIP 的依赖失败，以及新增 companion 包安装被设备拒绝（该次未执行 case）。首次原包准入检查还发现系统 base-only 包以空 splitNames / null splitSourceDirs 表示零 splits，已修正零数量判定；非空 splits 的集合与摘要仍严格核对，原失败证据保留。

当前切片要求原包已由系统安装，且不声明 Java/native/static/SDK 共享库依赖图；其他来源和共享库图仍显式拒绝。THIN isolated Service、external/cross-package Service、替换 ClassLoader 和 Chrome renderer 尚未验收，不能据此声称所有客体 THICK 均通过。下一步先完成同镜像普通 Service、核心/WebView、既有 native 身份回归，再按 Chrome 的真实 split / shared-library / renderer 依赖接入并单独验收。OEM Rust 后端、快手资源对象来源证明与夸克 SIGKILL 首因仍待解决。

以下章节保留各阶段当时的能力和验收边界；当前结果以上述新阶段及对应冻结证据为准。

## 真实隔离 Service 接入（2026-10-24，phase70–77）

已接入 **THICK、同包、纯 Java/DEX、创建型 bound Service** 的真实 isolated backend。Android 创建 nonexported isolated stub，每个实例拥有独立物理名称和 capability；先持久化真实 Binder PID/UID 与 lease claim，再发布校验过的只读 DEX/APK FD。guest 使用独立 ClassLoader、资源和 Context，通过物理 Service 的真实 token 委托生命周期，返回原 guest Binder。

复用既有 sessions / service_leases / service_calls / operations，schema 升至 39，增加进程角色、实例和真实 UID；35/36/37/38 的保留数据迁移均有设备检查。普通进程查找、容量和恢复不再把 isolated 角色当作普通槽位。同实例复用同一代，真实死亡后才释放并允许新代；已运行、失败或身份不明的恢复项仍需真实死亡证明。

恢复另覆盖一个严格的未执行阶段：旧 authority 的 ALLOCATED / pid=-1 / uid=-1，必须与同 owner、同 generation、仍 RESERVED 且未 claim 的 lease 精确匹配。由于 guest FD 发布前必须持久化 ATTACHED / CLAIMED，新 authority 可原子撤销这项未消费的执行权限；不声称未知物理 stub 已死亡，不改原业务 UNKNOWN，并用阶段 CAS 拒绝旧 SQL 回写复活。

真机发现并修复了最后解绑误暂停的问题：AMS 可以在最后解绑后以 `isolated not needed` 结束进程，不等待 `onDestroy`。现在在既有 unbind 请求持久化后、同一 broker 锁内，要求没有 PREPARED bind，且每个 APPLIED bind 都有同 lease / source / connection 的 PREPARED unbind，才关闭该实例的 admission。此时不派发 STOP、不推断 ACK 或死亡；继续允许 onUnbind / onDestroy，真实 Binder death 后才回收。仍有活绑定时的异常死亡继续产生恢复围栏。

- 原 V5、原安装代和原数据代前后对照：修复前 29 项场景虽通过，却新增两条暂停记录；修复后同样 29 项通过，新两个进程均 DEAD、lease 均 RELEASED，新增暂停为 0，旧两条暂停及其身份保留。前例见 `phase75-isolated-pause-negative.json`，后例为 `dev-tripoder-isolatedservicecontract-thick-1792835304`。
- 29 项实际场景包括不同 kernel isolated UID/PID、同实例复用、Application/Service 一次创建、十 DEX 数字顺序、主线程 ClassLoader、FD 资源、已知私有文件的显式 FD 可读而同路径不可访问、PM/存储 facade 拒绝、真实最后解绑死亡及另一实例存活。
- V6 在同数据代正常升级后 **31 项实际场景通过**，新增自有 AppComponentFactory 验证：ClassLoader / Application / Service 各创建一次，Service 创建 Intent 为空，后续 onBind 仍收到逻辑组件 Intent。两实例均确认死亡并释放 lease，当前 authority 暂停为 0。汇总见 `phase77-isolated-service-closure.json`。
- 最终镜像 `1c4ab8ea8fa4bc8743cbd6e1fb01873af54ccfdc2440ec9618d24a860f7c3bdb` 的 **443 项设备契约、702 个固定绑定和 14 项入口检查**通过；366 个 Java 源文件和主机检查通过，599 个构建输入摘要一致。
- 同镜像回归：普通服务两模式各 **21 项**、核心两模式各 **195 项**、既有原生库身份两模式各 **7 项**通过。核心两模式日志均确认实际 WebView 渲染自有本地 HTML 并执行 JavaScript。8 次运行全部正常停止，无 Java fatal 或 native fatal signal；没有据此宣称 isolated guest native 已支持。证据：`phase77-app-gates.json`；本阶段汇总：`phase77-final-handoff.json`。

本阶段尚不支持 guest native code、THIN isolated Service、external/cross-package Service 或替换 ClassLoader；这些请求显式拒绝。未宣称 Chrome renderer 或全部 THICK 应用兼容。下一步是解决隔离环境下原生代码的可执行发布、无 SONAME 的 DT_NEEDED 与 ART/JNI 一致性，再接入 Chromium。真实 authority 崩溃的设备验收也仍需补充；本轮恢复故障检查是设备上的 SQLite / broker 模型。

证据均位于 `evidence/compatibility-closure-2026-10-23/`；以下 phase69 及更早章节是各阶段当时的状态。

## 停止与恢复续修（2026-10-24，phase67–68）

已修复停止调用可无限卡住的根因：原实现先同步调用 worker STOP，之后才开始 15 秒死亡等待。worker 处于内核停止状态时，等待期限根本不会开始。现在每个不可变进程代只派发一次 STOP；调用方从派发前计算总期限，只有真实死亡通知才能释放资源。未附着的外部组件不能仅凭“尚无 Binder”被标成 DEAD。

- 主机新增 14 项检查通过，覆盖阻塞调用、64 个并发停止请求、迟到 ACK 与代际隔离。设备模型验证了真实阻塞 Binder 下的期限、去重和外部未附着组件仍保留资源。
- 自有 SIGSTOP fixture 实测在 **15.024 秒**返回 `PROCESS_STOP_UNCONFIRMED`，停止记录为 UNKNOWN，返回时槽位和 Activity 引用仍保留。该次 host 在 instrumentation 结束后才读取 `/proc`，因此不能把它当成期限瞬间的内核状态证明；后续 harness 已将观测移到 API 返回时。证据：`evidence/compatibility-closure-2026-10-23/phase67-stop-deadline-closure.json`。
- 补充设备验收在 **15.044 秒**返回同一类型 UNKNOWN。测试专用的返回后观测屏障期间，主机确认原 PID、UID、内核启动时间和进程代一致，仍为 `T (stopped)`、`TracerPid=0`；槽位 allocated=1、available=7，Activity 引用仍 LIVE、destroyedConfirmed=false。harness 内部 `/proc` 读取失败另行保留，没有用缺失记录推断死亡。证据：`evidence/compatibility-closure-2026-10-23/phase68-stop-deadline-closure.json`。
- phase68 的 **388 项设备契约检查**、359 个 Java 源文件及主机检查、固定框架 702 个绑定与 14 项入口检查通过。制品 SHA-256：`df90966872e2c02180e7dfcd03e3ba58cf30734f56db1ae4c669e49daf3beefe`。测试模型的中间失败与此前固定框架测试超时均保留，最终验收分别使用 `phase68b-contracts` 和 `phase68-fixed-registry`。

另补充了旧外层停止的精确失效证明：核对可信调用者、当前安装摘要及作用域，要求旧停止目标确认死亡，并有之后同安装的显式停止成功。只允许新的显式启动继续；原业务 UNKNOWN 不改写，其他子操作 UNKNOWN 仍逐条围栏。自有模型覆盖错误身份、安装变化、FAILED 代、空历史、独立子操作和新关闭目标等负例。

夸克真实旧记录 `91f2dce4-095b-424c-8e61-1c33ce8feb55` 满足该证明：同安装 12 个历史进程均已 DEAD，已有后续同安装显式停止成功。原安装代和数据代保持不变，两模式的新启动均已实际获准。两模式首代随后仍有精确关联的 SIGKILL，原因 UNKNOWN；启动获准及 harness 完成均不等于夸克兼容通过。证据：`evidence/compatibility-closure-2026-10-23/phase68-quark-before-closure/result.json` 及 `com-quark-browser-{thin-1792829346,thick-1792829389}/result.json`。

实际启动后再次查询，原 UNKNOWN 的调用者、结果、原因、证据、摘要和更新时间全部保持不变，只在既有 dispatch 字段登记精确的新启动 ACK；安装及数据代也未变化。证据：`evidence/compatibility-closure-2026-10-23/phase68-real-stop-admission-closure.json`。当前镜像的核心两模式各 **195 项**、原生库两模式各 **7 项**回归通过，见 `phase68b-app-gates.json`。

跑测日志现先过滤任意 guest 消息与网络签名值再持久化，保留结构化 Runtime 记录、Java 首因和 native 栈帧。本轮对应旧日志已清理并同步摘要引用，清理范围及新摘要见 `phase68-log-privacy-correction.json` 与 `phase68-final-log-privacy-correction.json`；采集器深层 XML 和日志隐私回归共四项通过。

## 真实隔离进程的执行边界（2026-10-24，phase69）

独立自有 probe 的 13 个 case 使用不同的真实 isolated UID/PID。每次回调核对 kernel Binder 身份、challenge、输入摘要，且 owner 私有路径全部拒绝访问。这不是普通 UID 的服务替代。

| ART/JNI 输入 | 实际结果 |
| --- | --- |
| 封存 APK memfd、有 SONAME | 6 项通过：JNI_OnLoad 一次、精确定义类 FindClass、名称/绝对路径和依赖实例一致。 |
| 封存 APK memfd、无 SONAME | 直接 JNI 的前 3 项通过；driver 的 DT_NEEDED 无法通过规范化后的已删除 memfd ZIP 路径找到 leaf，保留 FAIL。 |
| probe 自己经系统安装的只读 native 目录、无 SONAME | 与校验 APK 中四个 SO 的字节摘要核对后，6 项通过。 |
| 同一系统安装目录、有 SONAME | 同样核对来源后，6 项通过。 |

三组正向 ART/JNI 共 **18 项断言通过**；直接执行 owner 私有文件 FD 的三项 EACCES 结果保留为失败对照。各 case 只执行指定路径，没有在失败后切换来源或手工补调 JNI_OnLoad。v5 的只读堆 ByteBuffer 输入错误、v6 无 SONAME memfd 依赖失败也保留。最终 probe 镜像为 `3ded41887abc048f43e52436b9afcd011a44d32eb05b639fd0874a42727a1ccf`，汇总见 `evidence/compatibility-closure-2026-10-23/phase69-isolated-art-closure.json`。

设备 framework DEX 和 13 次动态观测一致：`AppComponentFactory.instantiateService` 创建时收到空 Intent，真实绑定参数仅在 `onBind` 到达。不能把工厂 Intent 作为隔离 bootstrap 通道。

生产 isolated backend **尚未接入**，Chrome renderer 尚未验收。下一步先做自有普通 bound Service 的完整切片：复用现有 sessions/service_leases/service_calls/operations，持久化进程角色、实例和真实 UID；用 nonexported isolated stub 的 onBind 接收每 lease 独占 capability，登记精确 Binder 身份后再传 FD，使用物理 Service 的真实 token 委托生命周期。恢复检查也必须识别 isolated 角色，不能因其 UID 不同于 host 就误写 DEAD。guest Context 的资源、权限、存储与代码来源需要各自证明；本 probe 的资源叠加测试不代表纯 guest Context 已实现。

同包双实例、旧代/错误 capability 拒绝、真实死亡回收、authority 恢复及普通/WebView 服务回归通过后，再接 Chromium。OEM Rust 后端、快手资源对象来源证明和夸克 SIGKILL 首因仍未收口；没有用 Java 默认实现、共用 UID 或放宽资源身份检查代替。

## 续修验收（2026-10-24，phase66）

本轮继续关联任务，修复了中断回归的 UI 采集器递归溢出，以及点评原生库重复加载导致的 JNI 初始化状态分裂。尚未完成全部应用兼容性收口，不能据此计算新的全量通过率。

### 原生库身份修复

`ApplicationInfo.nativeLibraryDir` 指向经过校验的执行副本，但 ClassLoader namespace 原先仍从封存安装目录搜索依赖。两处文件内容相同、inode 不同；没有 SONAME 的库被绝对路径加载后，`DT_NEEDED` 又从封存目录加载第二份，第二份没有收到原 JNI 初始化。

现在 `RuntimeSharedLibraries` 使用与 guest `ApplicationInfo` 相同的执行目录。声明的系统共享库依赖和原 namespace 权限保持原有判定；没有给目标库注入初始化指针、修改目标 APK 或增加重试路径。前后构建的输入对比确认，仅 `RuntimeEngine.java` 和 `RuntimeSharedLibraries.java` 两个生产源文件变化。

- 同一自有 APK、同一安装代，修复前 THIN/THICK 均有 3 个身份/初始化断言失败；修复后两模式均为 **7/7 通过**，有 SONAME 的对照组保持通过。证据：`evidence/compatibility-closure-2026-10-23/phase66-native-library-closure.json`。
- 点评同一安装代复测：两模式均实际加载 `libmsc_jsi`、`libpicassojsi`，30 秒观察窗结束时首代进程存活，无原 `GetJNIEnv` 崩溃。THICK 显示首页框架；THIN 显示人机验证页，未执行验证交互。首页内容加载、登录和后续业务未验收。证据：`evidence/compatibility-closure-2026-10-23/phase66-dianping-startup-closure.json`。
- 新镜像 SHA-256：`7a3767c6667cf1a5517b44273beeff66f5ae916d13e699db18bfaae0a4fd33a0`。357 个 Java 源文件编译及主机行为检查、353 项设备契约、固定框架绑定及 14 项晚期入口检查通过。新镜像的核心与其他应用回归结果继续保存在 `evidence/compatibility-closure-2026-10-23/`。

### 采集器修复与补跑

`paired_capture.py` 将已经脱敏的 XML 树迭代写出，避免 `ElementTree.tostring()` 对过深界面树递归溢出。1500 层结构/转义往返及深层输入脱敏两项回归通过；没有调整全局递归上限或绕过截图判定。

上次中断的 8 轮测试已在原 phase65 镜像补完：今日头条、极速版、支付宝的两种模式均保持首进程存活，核心契约两模式各 195 项通过。今日头条 THICK 本轮底层 UI 导出失败，仍未标为界面验收通过；其他已查看的页面也只代表启动窗口。证据：`evidence/compatibility-closure-2026-10-23/phase65-resume-app-gates.json`。

### 本阶段后续结果

phase66 核心两模式各 195 项通过，百度两模式首进程存活；夸克暴露了停止期限和首代异常退出两个独立问题。停止与恢复修复、当前回归和隔离执行边界已记录在上文。详细接续状态见 `evidence/compatibility-closure-2026-10-23/WORKING.md`。

以下内容保留 2026-10-23 初轮快照；其中待办状态不能代替后续阶段的实际证据。

## 初轮结论（2026-10-23）

已修复两份报告中能够由当前代码和原始证据确认的安装解析、操作作用域、启动前资源判定与跑测破坏性行为。独立 QA 包的 **31 项真机检查通过**；原 Tripoder 的包身份与更新时间保持不变，未清理原应用数据，未替换原安装。

这不是“123 个应用全部兼容”或“THICK 已达到某个新通过率”的结论。当前没有重新进行全部应用冷启动、登录或业务验收；原报告中的 57 / 34 仍是历史样本，不能根据本轮修复直接增加通过数量。

- 原始报告：`Tripoder-兼容性测试报告.md`、`Tripoder-THICK兼容性报告.md`。
- 汇总证据：`docs/evidence/compatibility-repair-2026-10-23/summary.json`。
- 最终真机证据：`docs/evidence/compatibility-repair-2026-10-23/device-06/result.json`。
- QA 包：`dev.tripoder.compat20261023`，APK SHA-256：`689436871b4181f679ad76f8d928870ac3aef15fab40b9bd395abc5c442ce41b`。
- 最终构建的 551 个输入重新核对，`changedInputs=[]`。已有未提交改动保留；本轮未提交、未推送。

## 1. 对报告归因的修正

### THICK 失败需要区分触发条件与根因

逐个检查 THICK 原始日志后，26 个 `CRASH_ON_START` 中有 **13 个**的应用自身异常链包含 `RECOVERY_SCOPE_UNKNOWN`，分布于 4 个历史 operation：

| 历史 operation | 关联崩溃样本数 |
| --- | ---: |
| `2277cd22-6513-4cbc-8a26-f94870fc276e` | 8 |
| `35be01c0-caf2-4543-8af4-e8632a713069` | 1 |
| `qa-start-com-hunantv-imgo-activity-1792722239` | 2 |
| `qa-start-com-tencent-news-1792723706` | 2 |

完整包名、异常与日志路径保存在 `historical-fence-crashes.json`。BBC 的异常由 React Native 包装，内层同样是围栏拒绝。

THIN 的部分 PendingIntent 查询不经过持久化 mutation admission，而 THICK 会经过。因此同一污染状态可以表现为 THIN 成功、THICK 崩溃；模式确实改变了触发路径，但这不能直接证明全局反优化本身导致崩溃。修复作用域后必须重新做配对实验；不能把上述 13 个样本直接改成 PASS，也不能声称它们的其他启动缺口已解决。

### UNKNOWN 与资源不足不是应用缺陷的充分证据

一次真实执行后的不确定结果仍必须保留。应修复的是错误扩大的作用域，以及 guest 尚未执行就已经可以确认的容量拒绝。扩大物理池、删除 UNKNOWN 或清库会掩盖这两类问题。

设备上没有 launcher 的包不等于不存在；含有 Windows 文件或非标准 `.so` 资源的 APK 也不等于 Android ABI 不支持。应先区分“签名保护的包内资源”与“平台会提取的 native library”。

## 2. 已实施的修复

| 问题 | 根因与修复 | 验证边界 |
| --- | --- | --- |
| PendingIntent / Job 跨应用围栏 | `pending-install:<generation>`、`job-install:<generation>` 不是 session 的长度前缀 identity，旧解析将其归为 `*`。现在用安装及历史 session 中的持久化 owner 解析作用域。历史内部全局记录仅在 owner 能证明时按该 owner 参与冲突判断；原 UNKNOWN 与记录本身不改写。 | 真实 Android SQLite 验证不同应用能 admission，同一 owner 与其 space 删除仍被拒绝；owner 缺失或显式 global 仍全局围栏。 |
| 容量拒绝留下新 UNKNOWN | 原 `started=true` 发生在 `processes.begin()` 之前；affinity / alias 也在 Application 已执行后才检查。现在先进行只读容量检查，`begin()` 返回后才标记已启动；最终分配继续在原事务中复核。 | 满池预检不增加资源，已有 lease 可复用；并发情况下仍以最终事务为准，已经启动后的不确定性不降级。 |
| 声明多个 alias 就拒绝启动 | 原来把声明数当成占用数。现在只为实际请求的 alias 分配入口；仍保留 alias → target 的独立映射和实际占用上限。 | 真机签名 fixture 声明 12 个 alias，预检通过；物理 `ALIASES=2` 未扩容，第三个实际保留入口仍拒绝。 |
| 无 launcher 包无法导入 | 宿主包可见性只声明 launcher 查询。Engine 增加 `QUERY_ALL_PACKAGES`，用于读取完整安装集合。 | 真机读取并验证 WPS 小米版原始 APK；其他 11 个无入口包尚未逐一迁移验收。 |
| `lib/` 内资源造成整包拒绝 | 原扫描器要求每个 `lib/` 条目都是受支持 ABI 下的 `lib*.so`。现在每个条目仍做路径、CRC、签名与摘要检查，仅提取所选 ARM64 ABI 下的平台 native 命名条目；其他条目保留为 APK 资源。 | 平安证券、百度网盘原始 APK 真机 materialize 与完整 verify 通过；不把 `test.so` 或 Windows DLL 当作 Android 已提取的库。 |
| 重复非 provider 组件拒绝安装 | 现在使用最后一条组件身份，保留各声明的 IntentFilter 和签名 manifest 原始声明；有效进程集合不再包含被覆盖声明的旧进程。平台解码索引相应采用最后一条非 provider 身份，provider authority 歧义仍拒绝。 | 腾讯视频、火车票抢票原始 APK 真机 materialize、verify、平台组件索引构建通过；签名 fixture 检查 flags、进程、filter 与 descriptor 重读。 |
| 快手 base 的 `uses-split` 被当成必需父依赖 | 实机 `pm path` 只有 base，base manifest 声明 `video`。Android split 依赖图中的 base 没有父依赖；现在按此语义处理。feature / config 的缺失、环、身份及签名检查保留。 | 快手原始 APK 真机完整验证及组件索引通过；必需 feature 缺失等负例继续通过拒绝检查。 |
| 上传限制跨层不一致 | 单 APK 统一为 2 GiB，集合为 4 GiB。同步修改 authority、Workbench UI、手机 MCP staging、电脑 Transport、MCP schema；电脑端在 dispatch 前检查集合大小。 | Node 边界回归、真机内置 Java schema 的 2 GiB 元数据 / 大偏移 / 超限拒绝通过；尚未实际传输并启动 1.5 GB 元梦之星。 |
| 跑测自动清库 | `run_thick_run3b.sh` 与 `run_thick_run4.sh` 删除自动 `pm clear` 路径。容量不足时保留 journal 和设备状态，以退出码 2 结束。 | Shell 语法检查通过；本轮未运行旧全量 campaign。 |

平台语义依据：[AOSP SplitDependencyLoader](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/split/SplitDependencyLoader.java)、[AOSP ComponentResolver](https://android.googlesource.com/platform/frameworks/base/+/0c36369480790826dffd7f2e1679a33fa0edb427/services/core/java/com/android/server/pm/resolution/ComponentResolver.java)、[AOSP NativeLibraryHelper](https://android.googlesource.com/platform/frameworks/base/+/3c4076a/core/jni/com_android_internal_content_NativeLibraryHelper.cpp)。其中原始 APK 的设备验证是本轮直接证据，平台源码用于核对语义。

## 3. 验证结果

| 验证 | 结果 |
| --- | --- |
| 独立 QA 包、真实 Android SQLite 与原始设备 APK | 31 项通过，`device-06/result.json` |
| 签名 APK / split / ZIP / descriptor 主机回归 | 58 项通过 |
| 新作用域策略主机回归 | 20 项通过 |
| 既有 recovery / execution contract / service recovery | 31 / 113 / 16 项通过 |
| Runtime / Engine / Workbench / self-hook SDK 编译 | 322 个 Java 源文件通过 |
| 既有 bootstrap、loader、component、boundary、manager、pending 等主机回归 | 通过，详细输出见 `host/sdk.log` |
| TypeScript SDK | 44 个测试通过 |
| Java MCP / HTTPS host harness | 443 个 schema 用例通过；Node 25 个测试通过 |
| Node 导入限制与 schema 专项 | 5 个测试通过 |
| `git diff --check`、跑测 shell 语法、修改的 Python 脚本语法 | 通过 |

六个原始 APK 的导入检查使用生产 `ArtifactSet.materialize()`、`ArtifactSet.verify()`，并用设备 `PackageManager` 解码后调用生产 `RuntimeManifestIndex.resolve()`。它们没有被修改或重新签名，也没有在这些检查中执行 guest 代码。这不是 MCP 正式安装事务、首屏或业务验收。

真机检查采用单独 QA package，所有人工构造的 UNKNOWN / affinity / alias 记录写入该 QA 应用独有的 scratch SQLite；没有向原引擎注入故障记录。`device-01` 是 harness 编译失败，`device-02` 是手机安装限制；均保留。`device-03` 至 `device-05` 是中间构建；最终结论只绑定 `device-06`。

## 4. 初轮待办清单（历史快照，2026-10-23）

| 项目 | 当前评估 | 下一步 |
| --- | --- | --- |
| affinity 只有 8 个，以及真实 UNKNOWN 的 owner 回收 | 本轮阻止了确定性拒绝制造新围栏，也修正了内部作用域；没有增加并发任务容量。真正属于该 owner 的 UNKNOWN 仍会阻止冲突操作。 | 复用现有 entry closure / task / process 死亡证明，完善 owner 级显式收口和资源回收；保留业务 UNKNOWN，不清库。 |
| worker / authority / slot 死亡 | DeadObjectException 是死亡后果，不是根因。报告中的中银系列、网络身份认证等不能据此判成应用自身失败。 | 同一构建采集退出原因、完整首因栈 / tombstone、authority 与 worker 时间线；mutation 不做自动重放。 |
| Chrome `singleInstancePerTask` | mode 4 的任务根语义仍未建模。 | 联动生成物理 manifest profile、task ledger、start/result/recovery；不能只放开枚举。 |
| WPS / 腾讯新闻 / 芒果 TV ClassLoader 身份变化 | 仍有启动期 ownership 不变量冲突。 | 记录 attach 前后 loader、LoadedApk 和 Application 的真实所有权，再实现受控 guest loader 变更；不直接关闭身份检查。 |
| OEM MIUIX 资源、可选系统库 | 既有日志只能证明加载失败，不能证明全部都是厂商依赖；资源解析错误也要单独分辨。 | 根据原始 manifest 的 uses-library、物理库身份和完整资源路径建立受限依赖；不注入任意 host loader。 |
| Gmail URI grant | grant 的授权来源、接收方和生命周期仍缺。 | 实现 ClipData / flags / provider owner 与回收链，不删除 grant 标志继续启动。 |
| Instagram 文件权限映射 | 仍需精确到物理路径与失败 syscall。 | 在当前制品上记录路径映射、chmod/fchmod/errno，修复归属与权限语义，不忽略失败。 |
| 多邻国通知 delegate、爱奇艺电话 API | 报告仅指出缺方法，未提供足以证明正确返回值的调用证据。 | 补齐真实参数、权限和 caller 身份；实现受限能力，不返回空成功或伪造网络类型。 |
| 豆包 ApplicationInfo / theme NPE | 初始化期绑定问题尚未复现到根因。 | 跟踪 ContextImpl / LoadedApk / ApplicationInfo 在 getTheme 前的绑定顺序，修正初始化。 |
| 网易云物理 Application 代际冲突 | 需要精确安装代与物理进程生命周期证据。 | 停止旧代并确认死亡后重新开始，不能覆盖正在使用的物理身份。 |
| 天气 / 小米商城代码缺失 | ClassNotFound 不足以断言“应用代码/split 缺失”。 | 比较设备完整 APK 集合、dex 定义、声明库与 guest loader 实际输入。 |
| THICK 接收器多 filter、VLC connectivity、抖音 WebView provider | 原始日志显示了更具体的独立契约缺口。 | 在围栏污染修复后的配对复测中保留完整新首因，再逐条实现相应 owner 绑定能力。 |
| 通义 / 钉钉等静默退出与未归因崩溃 | 仍为 UNKNOWN 原因。 | 采集 ApplicationExitInfo、am_kill / LMK / native / Java 首因；不直接归责为应用自杀。 |

## 5. 建议的后续顺序

1. 用最终 QA 制品对 ChatGPT、Claude、阿里云盘、Twitter、VLC、多邻国、YouTube 做同设备、同安装代、同观察窗的 THIN / THICK 配对启动，确认清除围栏污染后的真实首因。保留长启动与引擎侧失败的独立分类。
2. 先收口真实 UNKNOWN 的 owner 资源与进程死亡原因，再处理通知、接收器、connectivity、可选库等高频契约。Chrome mode 4、URI grant、ClassLoader 和 OEM 资源各自建立独立验收。
3. 原应用保留数据升级验证通过后，重新跑 123 包全量 campaign。不得从本轮 31 项定向检查外推新的全量通过率；登录、联网、支付等业务能力单独验收。

可复跑命令：

```sh
rtk proxy python3 android/engine/test/run-recovery-tests.py
rtk proxy node --test agent/schema.test.mjs agent/import-limits.test.mjs
rtk proxy python3 android/workbench/test/run-mcp-tests.py
rtk proxy python3 scripts/qa/validate_compatibility_repair.py --serial d861a4fa --imports --evidence <新的独立证据目录>
```

最后一条命令要求 `android/product/build/build-receipt.json` 指向当前源码的 `dev.tripoder.compat*` 独立构建。验证器会检查全部构建输入和 APK 摘要，拒绝拿过期制品验收。
