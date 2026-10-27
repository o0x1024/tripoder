# MCP v3 修复与验收（2026-10-23）

这次修复按数据归属区分安装与进程，按调用寿命传递 Java 对象，并按是否可能派发区分拒绝、查询不可用和修改 UNKNOWN。没有新增数据库表、对象全局注册表、自动重放队列、旧执行别名或降级后端。

## 修改

| 问题 | 修复 | 实现 |
| --- | --- | --- |
| 静态 Manifest 被 Hook 进程契约约束 | application_query(action=manifest) 只接受精确安装身份；完整受校验描述覆盖 base/splits、权限和 IntentFilter；声明值、平台默认、UNRESOLVED 分开；分页不静默丢弃 | agent/tools.mjs；InstalledManifestAudit；RuntimeControllerInstallationQueries |
| Thread/Throwable 等对象签名被标量白名单拒绝 | Hook 签名与 guest_invoke JSON ABI 分离；Ref 不可伪造、跨调用/线程失效，原对象传回，支持可赋值返回值；Thread/Throwable 用非虚框架快照，不执行客体 getter/toString | JavaTypes；JavaHookValues；JavaObjectScope；JavaObjectSnapshots；RuntimeJavaHookBackend；TypeScript SDK |
| anyOf 丢失真实原因 | Node/Android/Sentinel 按 discriminator 校验实际分支，返回 issues[].path/code/expected，不回显 source/body；v3 不受支持时拒绝目录/调用 | schema-validation.mjs；AgentSchema；Sentinel schema_diagnostics/mcp_argument_semantics |
| 客体命名空间阻塞产物写入 | 复用 authority 目录 FD、CodeSnapshots 原子写入和既有内部 kernel syscall；先同步字节再发布 COMPLETE，失败给出阶段与 errno | RuntimeCodeArtifacts；code_snapshots.c |
| 存储审计只有安装数据来源，却依赖进程 | 移到 application_query(action=storage)，明确 BOUNDED_LIVE_VIEW/非原子快照，拒绝符号链接与越界根 | RuntimeControllerInstallationQueries；RuntimeSecurityAudit |
| apk_* 查询实际反射运行时且漏 splits | 改为 runtime_classes/resolved_class 的明确语义，补 base/splits 扫描及截断标记 | RuntimeJavaObservation；tool-groups.mjs |
| 只读失败触发修改 UNKNOWN 门 | 共用 HookCommands 分类，读失败为 UNAVAILABLE，不建立修改未知门；真正修改不确定仍 UNKNOWN | SelfHookControl；RuntimeHookOperations；RuntimeControllerHooks |
| 目录读取隐式补建绑定 | 目录纯读，精确目标请求才补建绑定；历史 UNKNOWN 不再阻塞目录读取 | RuntimeControllerCatalog；ApplicationProjects；RuntimeAgentSessions |
| 多空间项目错误扩大为全局作用域 | 使用明确 bindingOperation 解析实际空间；缺失/歧义继续拒绝，不放宽 UNKNOWN | RuntimeMutationGuard |
| 启动配置保存与启用混淆 | startupHook 要求 enabled=true；configure/observe/prepare/activate/hit 保持不同阶段 | RuntimeProbePlans；三端参数检查 |
| HTTP 前置非法值导致 UNKNOWN | URL/headers/GET-HEAD body 在纯检查阶段拒绝；进入 handler/代理/网络后失败仍 UNKNOWN | HttpRequestPreflight；AgentSchema；Node/Rust semantic checks |
| 整数可以在 Java getInt 静默溢出 | Schema 声明安全整数及 int 游标/offset 上限；三端拒绝越界 | describeSchema；artifact_read |
| 启动返回 UNKNOWN 后缺少进程代次入口 | 新增 application_query(action=process_history)，只读现有 sessions，按精确安装查询 DEAD/FAILED，不能把历史 PID 当执行身份 | RuntimeControllerInstallationQueries |
| 无 Activity entry 的准备 UNKNOWN 错误声称可确认关闭 | canAcknowledge=false，保留 NO_ENTRY_TO_FENCE_OUTCOME_UNKNOWN，不伪造结果或清除门禁 | RuntimeControllerEntryClosures |

## 验证结果

- TypeScript：44 项通过。
- Java 脚本/对象/JNI 基础访问/受限编译器：787 项检查通过，包含 GC、null、身份、过期/跨线程引用、恶意 getter、原方法一次、HTTP 前后派发分类。
- Android 生产 Schema/HTTPS/MCP 主机链：443 组正反用例与 25 项集成测试通过；静态清单覆盖权限/splits/完整 Filter/未解析值/默认值/分页/SHA。
- Sentinel 对同一份生成契约：443 组检查通过；目录版本拒绝与 action 私有契约测试通过。
- Hook/Workbench 恢复：21/10 项通过；主机测试不是 Android IPC/目标应用业务验收。
- Sentinel 含 db-sqlite 的 cargo check 与 cargo build 通过，debug/sentinel-ai 已重建。
- Sentinel 原生 TLS MCP 到手机：4 个检查点通过，验证真实目录、模型 Schema 的进程必填项、派发前精确路径、安装层 Manifest。stdio 默认私有配置验证 36 工具、v3 目录。
- 所有本次触及的源码文件均不超过 2000 行。两仓库 git diff --check 通过。未提交/推送，工作区其他修改保留。

## 真机

设备为实际 Xiaomi/madrid Android 17/API37 ARM64。安装 APK 的 SHA 与冻结源码构建回执匹配，见 installation.json、summary.json；原始完整 build receipt 位于 android/product/build/build-receipt.json。ADB 只用于安装、启动工作台及导出配置，核心验收通过认证 LAN HTTPS MCP 执行。

签名不同曾阻塞覆盖升级；用户明确授权后，已卸载重装 Tripoder 和测试组件。系统安装的银行 App 保留。验收使用显式专用空间，避免与历史失败操作混淆；旧 UNKNOWN 未自动重放或改成失败。

自有 APK 的 28 项真机检查全部通过：应用停止时 Manifest/存储可读，代次过期被拒绝，动态工具漏进程代次给出精确路径；Thread/Throwable 参数与对象返回正常，原方法恰好一次、对象身份一致、恶意 override 0 次；读查询失败不锁控制；6 份快照绑定安装/进程/源码 SHA，进程故意退出后仍可读取并通过 SHA 校验。未来 probe 在故障注入前明确停用，故意退出的原修改 UNKNOWN 保留。见 owned-verified.json。

com.bocconnect.prd 实际原始 base/splits 导入后，静态审计读到 261 条声明、29 条权限、147 个组件；安装层/字段契约检查通过。启动仍以 DeadObjectException 结束，原操作 UNKNOWN，历史进程为 DEAD。新的进程历史入口可以发现失败代次并读取校验通过的 watchdog.stack，栈含 uyf.s.run(SourceFile:27)。没有得到 exception.caught/exception.handle 的 Throwable 快照，因此没有把银行对象回调命中或业务启动算通过。见 bank-verified.json；其中 passed=false 是真实保留的业务启动门，不是参数白名单报错。

下一步应沿 Application/native 初始化到 uyf.s 看门狗链定位最初中断原因。DeadObjectException 是进程死亡的控制面结果，当前证据不足以确认更早的根因。不得用进程 DEAD 推断原修改失败，也不得换 operationId 重放。

## 使用与边界

- 新目录为 v3、serverInfo 0.4.0。旧 Manifest/Storage Hook 执行路径已移除。
- 手机当前连接凭证保存在 /Users/like/.tripoder/tripoder-connection.json（0600），未纳入证据或 Git。可导入的 stdio 启动配置为 /Users/like/.tripoder/tripoder-mcp-v3.json；其中没有嵌入凭证。
- Sentinel 桌面可执行文件已重建，但未中断用户现有桌面会话，桌面 GUI/旧会话续跑未验收。应加载新构建并使用新连接配置刷新目录。
- 没有把真实模型自动选工具准确率、Hook ACTIVE、fixture 成功或原进程死亡当作银行业务成功。当前后端的既有调用覆盖边界没有被放宽。
- 早期失败门汇总见 retained-failure-gates.json，原始日志保存在 summary.json 指向的任务临时目录。
