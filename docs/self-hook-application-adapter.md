# 自研 Application 适配

2026-10-16 产品补充：已验证Manifest未声明Application或明确声明android.app.Application时，默认实例的Class精确为平台Application.class，选定客体loader必须归属当前制品并解析到此同一Class。自定义Application仍须由当前制品loader定义；不接受其它boot类、host实例、外来loader或解析不一致。物理Host与独立逻辑LoadedApk/Application实例的所有权检查不因此改变。


2026-10-16。最新 H6 已在 factory / guest clinit / 构造执行前建立逻辑视图，产品 Engine 的完整 bootstrap 尚未接入。

APK `7529134a…` 的 run `a4b8ede2-8d28-42b0-af20-6599da76748d` 独立14项通过：8个客体执行阶段；前期 currentApplication 为 null、processName 为客体名称，attach/onCreate 为唯一已创建 guest。物理 host 字段由直接JNI复核始终保持原归属。旧 Application JIT run `0aa5d88b-9cb5-4fe0-9bef-5b081c9e1194` 同构建通过百万读写对/409直接JNI核验。

`ApplicationAdapter.begin(host,members)` 必须先于 guest factory/类初始化/构造；可在准备线程调用。`create(...)` 要求 begin 已成功，并在主线程执行唯一 guest 构造。`RuntimeSelfHookApplication.prepare` 使用真实 RuntimeManagedInstaller 安装 currentApplication 与 getProcessName 两个入口，尚未由 Engine 的25项安装计划调用。

H3i增量：固定注册表已12字段，增加静态 `sCurrentActivityThread`。ApplicationAdapter的物理线程与归属读取已改成固定JNI字段，独立PhysicalApplication核心已链接探针，避免依赖managed getter。selected loader以解析得到的实际类身份验证，支持委托给不同的defining loader。新构建JIT/AOT各409次固定JNI物理检查、400次跨线程检查及委托loader生命周期通过，见 [H3i证据](evidence/self-hook-h3i-2026-10-15/README.md)。下文H3f数据保留为历史。

以下 H3i/H3f 为历史证据；当时存在的构造前窗口已由上述 begin 阶段修复。它们不代表最终产品 Engine 接入。

`android/self-hook/java/dev/tripoder/selfhook/ApplicationAdapter.java` 与 `native/application_adapter.c` 使用 [固定框架成员注册表](self-hook-framework-access.md)。每进程只允许一次构造和一次 attach，调用限定 Android 主线程；固定 probe 生命周期结束可以关闭反射视图，全部代码及引用仍保留至进程退出。

## 对象与顺序

1. 保留物理 host Application、ActivityThread 和物理 LoadedApk，先用直接 JNI 字段读取验证其归属。
2. 配置 H3e 的精确初始 Application 反射视图。物理字段保持 host；只有当前 ActivityThread 的 `mInitialApplication` 在原生反射访问检查成功后映射至逻辑 guest。
3. 分别复制声明和构造用 ApplicationInfo。构造副本清除客体 factory 名称，避免构造器再次实例化客体工厂；credential dataDir 明确设为逻辑 dataDir。
4. 调用固定 LoadedApk 构造器，绑定已有 loader、default loader、resources、factory、声明 ApplicationInfo 和 guest Application。只写新对象。
5. 调用固定三参数 createAppContext，AppOps 包名仍来自真实 host；设置逻辑 ContextImpl 的 outer Context 为 guest。
6. attach 前最多解包 16 层 ContextWrapper，必须得到本次逻辑 ContextImpl。调用原始 Application.attach，不重写客体字节码；attach 后及 onCreate 返回后直接 JNI 复验物理/逻辑所有权。

检查还覆盖逻辑 LoadedApk 的两项 loader、resources、factory 和 ApplicationInfo。该路径不能借物理 host Context 通过就绪门禁，也没有备用后端。它不是防御同 UID 任意 Native/反射代码的隔离边界。

## 自有验证

`LogicalApplicationFixture` 与 `LogicalFactoryFixture` 由独立 DexClassLoader 从自有探针 APK 加载；它们不依赖 runtime 业务代码或 Frida。夹具在 attach 中正常解包 Context，取得逻辑 LoadedApk，幂等关联自己的 Application；同时验证初始 Application 的逻辑读取、幂等写及 null/其他 Application 写入拒绝。onCreate 再验证 Application.attach 记录了同一个逻辑 LoadedApk。

同一 APK `fdb5d874553a0a24953df2eb74baa66e8da3de89bf72846393ab957c0d660080`：

| 路径 | runId | 结果 |
|---|---|---|
| JIT | `57ba635a-2ca3-42f3-9d03-a4554748c0c8` | 一百万次真实字段读写对、四万次并发读取、11 次物理归属核验通过 |
| AOT speed | `c8add8b3-74ed-4cb7-b69a-c92f5e341ff2` | 相同检查通过，hot caller 入口实际位于本 APK 的 base.odex |

两次 factory、Application constructor、attach、onCreate 均为一次；两次非法身份写入均拒绝。视图关闭后反射重新读取物理 host。18 个适配引用、9 个反射视图引用和 7 个框架 Class 引用按预算保留；没有将视图停用当作可释放对象的证明。GC 请求后身份读取通过，不声称观测到了对象移动。

同构建的 H1–H3e JIT/AOT 回归也通过：92 核心、16 合成执行、45 协作 Hook、15 暂停、19 非协作 Hook、24+24 固定 libc、62 Field.get、25 managed、76 反射视图检查及 14 主机 DEX 检查。完整证据见 [索引](evidence/self-hook-h3f-2026-10-15/README.md)。

仍须迁移 Runtime 自有 Bridge 的直接调用、其他组件/系统服务入口、通用 ART 方法/调用者覆盖、脚本与控制面。当前探针不证明产品去 Frida、全部组件或第三方应用业务通过。所有设备操作仅限 `dev.tripoder.selfhookprobe`。
