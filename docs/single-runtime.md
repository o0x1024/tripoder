# 单运行时收敛（2026-10-26）

Tripoder 不再提供 THIN/THICK 选择。所有普通客体使用同一套组件、存储、设备服务和 Hook 发布规则。环境模拟继续通过现有 persona/env_mock 工具显式配置；未配置的观测交给真实平台。

## 产品目标与实施顺序（2026-10-27 调整）

第一目标是原版应用在 Tripoder 中正常运行：启动、真实用户认证、只读页面使用及前后台恢复。业务 Java/Native Hook、代码观测和抓包是后续按需能力。应用启动成功、工具可用、工具启用后应用仍稳定，需要分别验收。

统一运行时的启动路径应只保留承载客体必需的身份、组件、存储、类加载、JNI 和信号语义。其必要性必须逐项证明，不能将当前全部固定 Hook 都视作不可删减的前提。正常运行仍可能需要内部适配 Hook；关闭业务脚本不等于没有运行时修改。

观测能力的目标结构是显式请求时启用，并在修改前完成支持性检查；不支持时明确拒绝该工具请求。不能用尝试注入、失败后忽略异常继续执行来代替能力分离，已经发生不确定发布的操作仍保留现有 UNKNOWN 和恢复语义。继续保留单运行时，不增加旧模式切换或失败重试后端。

已将启动所需的 Native 库/信号边界明确为 `NATIVE_LIBRARY_RUNTIME`，由固定的 `RuntimeNativeLibraryCatalog` 安装一次。`RuntimeNativeObservation` 消费同一份已就绪目录，不再安装监听器；工具控制器在首次明确请求时同步创建并一次发布，初始化失败不重复尝试。显式 startupHook 仍在 Application 构造前执行。

旧 `tp_project_module` 表的唯一读取入口属于已不可达的 procfs 文本快照实现，现已连同该死路径删除。真实 procfs FD 行为保持；必要的信号导入接入仍保留。该阶段构建的原生库 BSS 相对清理前减少 16,922,632 字节，这是映像尺寸变化，未测量常驻内存收益。

真实进程身份通过固定 registry 的 `Process.setArgV0` 在现有主线程 FutureTask 中建立，随后读取内核 cmdline 验证，再执行显式启动工具和客体 factory。移除了 LOAD/Binder 线程的 `PR_SET_NAME` 以及仅供其使用的参数；主线程 comm 随 Android 标准命名入口设置。Binder PID/UID、generation 和 AMS slot 身份继续分别校验。

自有样本已验证真实 argv0/主线程 comm 与客体一致、AMS 仍报告宿主 slot；最新产品的 Java/Native 16 项回归以及真实身份→显式启动 Hook→Application 构造顺序通过。招商银行依然启动失败，因此这些承载修正和工具契约不能视为银行验收通过。

## 执行规则

- 安装、原始 APK/Split、执行副本、ClassLoader、Application 和组件身份转换共用现有实现。普通客体仍使用宿主 UID，不能据此声明系统级强隔离。
- Activity、Service、Provider、SharedPreferences 保留当前平台承载路径。广播、Job、Alarm、跨客体授权以及操作恢复继续使用各自必要的 authority 账本。
- 固定 Java Hook 在客体代码执行前准备并批量发布，执行一次初始调用者失效，再恢复 JIT 与运行标志。普通脚本 Hook 仍执行其调用者失效和生命周期规则。已有活动帧与全部内联调用者不在保证范围。
- 音频、电源、Wi-Fi/P2P、网络统计沿平台服务执行。网络、电话、电池、定位使用现有 facade 保留环境模拟入口；未被模拟的 API 调用原平台 manager，平台异常直接传播。电话回调注册/注销使用平台 API；网络回调保留 flags 并由平台限制配额。
- Native 路径和直接系统调用使用同一策略。procfs 文件使用真实内核 FD，不维护旧文本快照；进程名通过 Android 入口设置真实 argv0/comm。不存在失败后换模式的路径。

## 持久化与接口

schema 41 对 schema 35–40 的 `project_refs` 执行 `DROP COLUMN mode`，保留其余列、索引和每条绑定的状态。schema 34 及更早版本继续运行各版本的显式迁移；v34 原始绑定归档不修改。安装/dataId、项目源码、操作回执、UNKNOWN、恢复 fence 与 checkpoint 不被重写。

历史操作结果中已有的 mode 字段保留为原始证据。当前绑定/进程/status 输出不再包含运行模式。`setProjectMode`、`setApplicationMode`、MCP `application_manage.set_mode` 已删除，旧请求明确拒绝，不执行映射或重放。

工作台不再显示模式徽标、切换菜单或项目模式按钮。旧双模式设计、测量和应用报告作为历史记录保留。

## 验证入口

- `python3 android/runtime/test/ApplicationProjectsSchemaTest.py`：新库、v34 归档与 v40 模式列移除，验证绑定/数据身份、UNKNOWN 和恢复记录不变。
- `python3 android/runtime/test/CatalogMetadataHostTest.py`：生产 SQL、绑定身份、并发目录读取与旧代次拒绝。
- `python3 android/workbench/test/run-catalog-tests.py`：单应用项目及多空间目录行为。
- `node --test agent/schema.test.mjs`：公开 schema 与实际分派一致，旧模式 action 在分派前拒绝。
- `android/runtime/self-hook-tests/run.py`：Hook/系统服务策略行为与 SDK 编译，包含平台查询、电话回调与显式 persona 分派。
- Android `SingleProjectMigrationIntegration`：真实 SQLiteOpenHelper v40→41、v34→41 及源码恢复；使用独立 fixture 数据库。
- Android `RuntimePolicyIntegration`：真实 JIT 调用者失效、固定批量发布、回调和停用恢复。
- Android `ControlIntegration`、`HookUiIntegration`、`TaskPresentationIntegration`：组件控制、真实 Hook 命中/撤销与工作台入口。
- `agent/runtime-acceptance.mjs --connection ABSOLUTE_JSON ...`：精确安装的真实前台、加载的 native core、Hook 改值及撤销恢复。输入不再接受 modes/finalMode。

构建、主机断言与真机结果分别记录；完成构建不能作为任意第三方应用、登录、业务或设备认证验收。

## 验收状态（2026-10-27）

主机已通过：schema 测试 3 项、生产目录/绑定 22 项、工作台目录 8 项、TypeScript SDK 49 项、Java MCP schema 715 个用例及完整 HTTP/stdio 测试 42 项。Hook/系统服务主机行为检查与 SDK 编译通过。

真机通过 142 项：QA 的真实 SQLiteOpenHelper 迁移 20 项、统一 ART/JIT 发布策略 18 项、完整控制面 62 项、工作台 Hook UI 11 项、MCP Java/Native 15 项；原产品最终二进制追加回归 16 项。Native 原始调用、改值和停用恢复为 `16 → 1016 → 16`；Java 实际返回值修改和恢复也通过。验收使用原版自有 APK，不改写目标应用。

原 `dev.tripoder.engine` 已保留数据覆盖更新，APK 摘要与最终构建一致；UID、数据目录、首次安装时间保持不变。实际 MCP 返回 schema 41、hookReady=true，原有 4 个安装可读取；模式 action、安装元数据中的 mode 字段、THIN/THICK 工具描述和工作台模式标签均已移除。首次显示 AUTHORITY_HEALTH_TIMEOUT，显式重连一次后恢复就绪，过程保留在证据中。

QA 与产品原生库分别构建，摘要不同。因此追加了原产品 Java/Native 回归，并核对实际加载的 core SHA 与最终构建一致，不以源码相同替代最终二进制验收。

10 月 26 日的安装拒绝记录以及本轮失败记录均保留。仪器测试结束会中断验收进程，后续测试用 `--retry-engine-once` 明确执行一次工作台重连，不清数据或重放业务操作。工装中不存在的绑定回执应返回 PROJECT_NOT_BOUND，已校正旧断言，同时保留精确拒绝和 FAILED 回执验证。

独立验收包已停止，数据和操作证据保留；临时 ADB 端口转发已移除，原产品的 MCP 设置保持原状。原产品中新建的自有测试空间已在验证通过后正常删除，原有安装数量恢复为 4；既有项目源码按原有规则保留。[真机与产品更新证据](evidence/single-runtime-2026-10-27/summary.json)。这些结果覆盖自有样本与运行时契约，不代表任意加固应用或第三方登录/业务验收。

完整 MCP 测试还修复了工具目录超过旧 512 KiB 响应预算的问题：目录响应有独立 1 MiB 上限，普通工具结果仍为 512 KiB；有效目录、超限目录和普通响应超限测试均通过。

## 招商银行定向验收（2026-10-27）

`cmb.pb` 14.4.5 在单运行时内启动未通过。固定 Hook 已发布，Application 已构造；attach 尚未返回时，银行库线程在 `libCmbShield.so+0x7af58` 读取匿名 `PROT_NONE` 页并以 SIGSEGV 退出。匿名页和访问指针的来源仍未确认，银行内 Hook、登录与业务流程未到达。系统安装的同版本应用可显示公开首页；这不是冷启动或登录验收。

新增显式构建诊断 `--fault-evidence`，默认关闭，仅记录有界的信号与代码位置元数据。自有信号交付工装通过，最终产品已恢复关闭诊断的构建。原始 UNKNOWN、安装与数据身份均保留。[定向验收及下一步](evidence/cmb-single-runtime-2026-10-27/README.md)。

最新进程身份修正构建（APK `82d48b91…37449`）追加实测：银行已经完成真实命名和 Application 对象构造，但 attach 返回前仍以 SIGSEGV / status 11 退出，工具控制器创建次数为 0，登录与只读业务未到达。该次操作保持 UNKNOWN；未清空或重装银行数据。上文 `libCmbShield.so` 的代码偏移来自先前诊断构建，不作为本次 PC 证据。[最新构建验收记录](evidence/cmb-process-identity-2026-10-27/README.md)。
