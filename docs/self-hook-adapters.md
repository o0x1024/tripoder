# 自研 Hook 必要适配迁移清单

2026-10-16。下表左列保留旧代理文件作为职责对照；正式Runtime已由RuntimeSelfHookProduct以单一自研核心同步安装25项/2177绑定，不包装或加载旧代理。P0/包管理/P4有实际产品回执；完整组件、存储与工作台审批长流程正在收口。分项核心不能替代产品验收，最新逐制品状态见[当前交接](self-hook-handoff-2026-10-15.md)。

| 历史入口（android/runtime/agent） | 语义与对象 | 当前承接位置 | 验收依据 |
|---|---|---|---|
| index.ts | 初始化次序、Application.attach、Activity Context、adapterReady、Hook 控制 | RuntimeSelfHookProduct + RuntimeManagedInstaller + ART Core | Application 首条业务代码前全部必要能力就绪；失败停止 |
| application-context.ts / application-ownership.ts | LoadedApk、ActivityThread、Application 的真实/逻辑归属 | RuntimeSelfHookApplication + 固定成员表 + Java/Native ApplicationAdapter；已接Engine真实启动 | self-hook-framework-access.md、self-hook-application-adapter.md；runtime-application-design.md |
| reflection-view.ts | caller-sensitive Field.get/set 原帧、类型与访问检查、JNI 全局引用 | pending null视图在factory/clinit/constructor前发布，再一次提升为guest；Field.get/set保留原反射帧 | 原始异常与访问检查顺序，不引入额外 managed frame；self-hook-reflection-view.md、self-hook-application-adapter.md |
| activity-creation.ts / activities.ts / activity-manager.ts | Instrumentation、实例构造、任务、结果与入口关闭 | RuntimeSelfHookActivities + 原Broker/任务账本 | runtime-activity-construction.md、runtime-activity-design.md |
| services.ts | 启动/绑定、Binder、调度 flags 与重入 | RuntimeSelfHookFixedComponents + 原Service所有者 | runtime-service-startup.md |
| providers.ts | ContentResolver、权限、batch、跨进程身份 | RuntimeSelfHookFixedComponents + 原Provider账本 | runtime-provider-design.md |
| receivers.ts | 广播、sticky、goAsync、完成与权限 | RuntimeSelfHookFixedComponents + 原Broadcast账本 | runtime-receiver-design.md、runtime-system-broadcast-design.md |
| packages.ts | 包域、类加载、资源、组件可见性 | RuntimeSelfHookPackages + 原包域 | runtime-package-design.md |
| storage.ts | 固定 libc 入口 → 已有 path_policy/io_adapter | RuntimeSelfHookStorage + 十固定libc绝对尾跳 + 同一C策略；已在真实25项启动安装 | runtime-storage-design.md；self-hook-fixed-libc.md；errno/FD/符号链接/只读制品 |
| connectivity.ts / wifi-p2p.ts / network-stats.ts | 显式网络能力与未支持能力拒绝 | RuntimeSelfHookManagers/SystemEdges + 原显式系统门面 | runtime-network-design.md |
| telephony.ts / audio.ts / battery.ts / power.ts | 明确支持的系统观察/效果范围 | RuntimeSelfHookManagers/SystemEdges + 原显式系统门面 | 对应 telephony/audio/device-observation 文档 |
| notifications.ts / shortcuts.ts / application-history.ts | 通知、能力发现、历史视图 | RuntimeSelfHookManagers/SystemEdges + 原显式系统门面 | 对应 notifications/shortcut/start-history 文档 |
| failures.ts | 崩溃与退出归因 | Native/Java 诊断 | runtime_crash_evidence.py，所属进程与原始错误 |
| hooks.ts / hook_java.ts | prepare/activate、Java 绑定、版本和停用 | RuntimeHookBridge + RuntimeJavaObservation/HookBackend + 同一AST解释器 | runtime-hook-design.md |
| hook_native.ts / hook_modules.ts | 模块身份、链接器时序、ABI、替换 | RuntimeNativeObservation/HookBackend + Native Core | 加载代次、Build ID、原调用次数、失效 |
| hook_execution.ts + src/draft.ts | before/original/after、异常、受限 AST | 同一规范的 Script Executor | 现有 SDK 测试 + 跨执行器对照 |

复用：RuntimeBroker、RuntimeStore、ApkStore、ExecutionContract、RuntimeHookBridge、工作台/MCP/审批；不复制新的安装/空间/操作状态机。Java/C适配保留原职责和权限范围，固定适配及用户脚本复用同一自研核心；旧Gadget不构成失败后的备用路径。

具体目标签名、ClassLoader、原始访问语义、Hook owner、安装时机、线程限制及失效规则由固定成员表、平台二进制合同和对应能力文档约束；本次构建证据按runId/APK SHA单独记录。与当前 Runtime/系统能力文档矛盾时先修订契约，不以“框架通常如此”自动透传。
