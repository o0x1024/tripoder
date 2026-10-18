# 客体应用兼容性清单：从第一性原理枚举

2026-10-18。状态：活文档。证据来自：源码里的拒绝点（约 150 处"未建模即拒绝"）、设备上 15 个已装应用的真机普查（人工点击启动，第 3 节）、`docs/launch-performance-plan-2026-10-18.md` 的启动诊断。参考项目只用来对照"需要覆盖哪些面"（系统服务代理、组件、进程模型、存储重定向、native I/O 重定向，以及它自己列出的反复出现的兼容性主题：加固壳、反篡改/环境检测），没有借用其实现。

## 1. 原理

一个应用能正常运行，当且仅当它依赖的每一份**契约**都被满足。应用只依赖 Android 对一个普通进程做出的承诺，而不关心谁来履行。容器要做的就是在应用与系统之间重新履行这些承诺，并且：

1. **透明**：应用能在原生下做到的事，在容器里得到同样的结果，包括失败的形态。应用代码按 Android 的语义写，例如查询不存在的包只会遇到 `NameNotFoundException`，不存在的系统服务是 `null`；没有哪个应用会为 `UnsupportedOperationException` 写分支。
2. **边界只设在容器自己解释的地方**：权威（authority）进程会解释的数据（身份、权限、组件路由、容量）必须严格校验；客体之间、客体与自己的进程之间传递、权威不解释的数据（自定义 Parcelable、Binder、自己的字节流）必须原样透传。同 UID 下客体本来就不是敌对代码，过度拒绝没有安全收益，只会把兼容问题变成崩溃。例外：会暴露设备标识符的接口（电池序列号、蓝牙地址、IMEI）仍须显式、不得因"只读"而放行。
3. **失败要可见且就地**：无法履行的契约应以应用已知的失败形态出现（异常类型、返回值），而不是杀死整个进程；并在日志和界面里说明原因。

据此，客体进程依赖的契约分为以下层次。状态：✅已满足　⚠部分满足，已知缺口　❌未满足　🔍未验证。

## 2. 契约层与失效方式

### L1 进程身份与生命周期
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| attach 期间后台线程使用同进程 provider（真实 Android 由调用线程就地安装） | ✅ | `RuntimeProviders.ready`；哔哩哔哩 `xpref` 死锁 |
| attach 后"物理身份"不变量：应用在 attach 期间用反射改 `ActivityThread`/`LoadedApk` 的字段 | ✅ | 番茄在 attach 期间替换了自己的 `LoadedApk.mResources`（资源热更新框架的常见做法）。`application_adapter.c` 现在会点名被改的字段（`APPLICATION_POST_ATTACH_IDENTITY:<字段>`），并对客体自己的 `mResources` 跟随而不是拒绝；宿主物理对象的不变量不变 |
| 进程死亡须被及时、可解释地回报 | ⚠ | Gateway 现显示异常类名；缺根因 |
| 同一应用多进程（`:web`、`:push`）的启动顺序与存活假设 | 🔍 | |
| 进程容量固定 8，预热占 1 个，容量报告含 `spare` | ⚠ | 多进程应用可耗尽，会先淘汰预热槽 |
| 冷启动预算（30 s）与整包校验耦合 | ⚠ | 已加后台预热；`RuntimeProviderBroker.acquire` 持锁遍历全空间的设计未改 |

### L2 代码与资源加载
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| 内存 DEX、无 vdex | ⚠ | 方案 P4 |
| 应用自建 ClassLoader（插件、热修复） | 🔍 | 哔哩哔哩 `Tribe` 报 `Unsupported class loader` 后仍能运行 |
| 拆分 APK（split）、多 ABI | ❌ | `RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED` |
| native 库路径、`/proc/self/maps` 自检、`dlopen` 路径 | ⚠ | native 路径策略；见 L13 |
| 资源与主题 | ✅ | 12 个应用界面正常 |

### L3 组件模型
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| 非 Activity 的 `Context.startActivity` | ✅ | 新增 `routeFromContext`；按 Android 要求 `NEW_TASK`；非客体目标交给平台 |
| 启动 Activity 的 flags（`CLEAR_TOP`、`NO_ANIMATION` 等） | ✅ 放宽 | 物理 stub 上由平台任务引擎执行；原神通过。结果请求+`NEW_TASK` 等仍受限 |
| 隐式 Intent 命中客体自己的 `intent-filter` | ✅ | 新增 `GuestIntentResolver`（Android 的 `IntentFilter.match`）；`resolveActivity`/`query*` 同源 |
| 启动 Activity 的 options bundle | ✅ | 不再拒绝，原样传给物理启动 |
| `startForegroundService`、`startForeground`/`stopForeground` | ⚠ | 现为普通服务启动 + 通过客体通知外观展示通知；没有宿主前台优先级 |
| `bindServiceAsUser` / `send*BroadcastAsUser` | ✅ | 当前用户等同普通调用；其他用户抛 `SecurityException`（需要 INTERACT_ACROSS_USERS） |
| `sendOrderedBroadcast(appOp)`、`sendBroadcastWithMultiplePermissions` 多于一个权限 | ⚠ | 无 appOp / 至多一个权限时等同普通调用；其余仍不支持 |
| 有序广播多目标、sticky 广播发送、`sendBroadcastAsUser` | ❌ | `BROADCAST_UNSUPPORTED` |
| 接收器过滤器 `priority` | ✅ | |
| 注册平台动作（如 `MEDIA_BUTTON`）的接收器 | ✅ | 注册成功但不投递，首次记日志 |
| `PendingIntent.getActivity` / `getService` / `getForegroundService` | ⚠ 降级 | 仅 `getBroadcast` 有完整实现。其余现在返回一个"没人接收"的真实令牌并记日志，而不是在创建时抛异常（服务 `onCreate` 里抛会杀进程）。触发它不会有任何效果：通知点击、保活闹钟、小部件跳转都不会起作用。完整设计见第 6 节 |
| Activity 在进程死亡后的投递 | ❌ | 多看曾 `ACTIVITY_DELIVERY_PROCESS_DIED`，修了包可见性后不再复现，根因待确认 |

### L4 进程间通信与数据形态
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| provider `call`/`insert` 等 extras 里的 `IBinder` | ✅ | 原生透传；摘要对 Binder 用占位符；权威自己的 Binder 禁止 |
| provider extras 里的平台 Parcelable / 客体 Parcelable / `Serializable` / `ArrayList<Parcelable>` / `SparseArray` / `CharSequence` | ✅ | `RuntimeProviderFacade.primitive` |
| `sendBroadcast` extras 里的 `IBinder` | ⚠ | 权威按字节存储，无法保存 Binder；现在记日志并不投递，而不是让线程崩溃。设计见第 6 节 |
| 文件描述符 | ❌ | `*_DESCRIPTOR_UNSUPPORTED` |
| extras 大小上限 | ⚠ | 32 KB（provider）、48 KB（广播）；原生上限约 1 MB |

### L5 系统服务与 PackageManager
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| 其他包的 `getPackageInfo`/`getApplicationInfo`/`getActivityInfo`/`getServiceInfo`/`resolveContentProvider` | ✅ | 返回 `NameNotFoundException` / `null`，与"包不可见"一致 |
| `PackageManager` 默认分支抛 `UnsupportedOperationException`（约 90 个方法） | ✅ | 按语义分类：平台自身答案、不可见包为缺席、修改类为 `SecurityException`；新增 `getPackageArchiveInfo`、`getInstalledPackages`、`getPackageUid`、`checkSignatures`、`getApplicationIcon`、`getReceiverInfo`、`getLaunchIntentForPackage` 等 |
| 未建模的 `getSystemService(name)` | ❌ | 明确报 `SYSTEM_SERVICE_UNSUPPORTED`；仅显式列出的服务获得平台能力。AppOps 只供受控 Provider attachInfo 内部使用一次 |
| 管理器外观（连接/电源）中未列出的只读观察方法 | ✅ | 落到平台实现；音频、电池、电话、ActivityManager 保持显式（含设备标识符风险） |
| 衍生 Context（`createDisplayContext`、`createAttributionContext`、`createContext(ContextParams)`、`createDeviceContext`） | ✅ | 保持客体身份、存储和类加载器，只取平台派生的显示/归因/设备；`createContextForSplit` 对任何名字抛 `NameNotFoundException`（客体无 split） |
| 运行时权限请求（相机、麦克风、定位、通知、媒体、联系人、蓝牙、电话状态） | ⚠ | 新增权限桥：宿主清单声明这些权限，`checkSelfPermission`/`checkPermission` 只对客体清单声明过的权限放行，`requestPermissions` 经平台的权限控制器弹出系统对话框（`buildRequestPermissionsIntent`、`getPermissionControllerPackageName` 由权威侧作答），平台启动的结果（`startActivityForResult` 到系统组件）回传不再被当成"未请求的结果"。**没有亲眼看到过系统授权对话框**：美团外卖进入主界面并继续运行，但在我的测试流程里没有触发请求；AppOps 仍未建模 |
| 相机、传感器、定位、蓝牙、NFC | ⚠ | 仅枚举 |

### L6 存储
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| `getExternalFilesDir` 类型（`""`、多级 `a/b`） | ✅ | |
| `moveSharedPreferencesFrom`、`moveDatabaseFrom` | ✅ | 同一数据实例内按字节移动；平台上下文返回 `false` |
| 非 `MODE_PRIVATE` 的文件/偏好模式 | ❌ | `FILE_MODE_UNSUPPORTED`、`PREFERENCES_MODE_UNSUPPORTED` |
| MediaStore、SAF、共享外部存储、`/sdcard` 硬编码 | ⚠ | 客体不会得到共享存储路径 |
| 硬编码 `/data/data/<pkg>` | ⚠ | native 路径策略 |

### L7 对应用呈现的身份
| 失效 | 状态 | 说明 |
|---|---|---|
| 包名、签名、installer、安装时间 | ✅ | |
| UID 检查 | ⚠ | `getPackageUid` 现支持自身；`getPackagesForUid` 仅本 UID |
| 环境检测（容器、root、Hook） | ❌ | 见 L13 |

### L8 原生层
JNI、`dlopen`、直接系统调用被重定向的路径：⚠；应用自带的 Hook 库（ShadowHook、ByteHook）与引擎的 Hook 并存：⚠ 哔哩哔哩加载成功。

### L9 UI 与窗口
窗口令牌归属、对话框、子窗口 ✅；WebView ⚠（`WEBVIEW_*_UNSUPPORTED`）；多窗口/画中画 🔍；Gateway 中转页文字固定 ⚠。

### L10 网络与安全配置
`network_security_config`、证书、明文流量：由平台按宿主包解释。🔍

### L11 后台工作与系统事件
JobScheduler ⚠、AlarmManager ⚠、前台服务 ⚠（见 L3）、通知渠道与分组 ⚠、系统广播 ⚠、开机自启 ❌。

### L12 容器自身的运行属性
冷 authority 首次访问整包校验并持有全局锁 ⚠（已加后台预热）；全局解释执行使 Java 慢 5–10 倍 ❌（方案 R1/P3）；启动失败的可观测性 ⚠。

### L13 反篡改与环境检测（应用主动自杀）
| 失效 | 状态 | 说明 / 证据 |
|---|---|---|
| `libmsaoaidsec.so`（OAID/移动安全联盟 SDK 内置的安全检测库）发现 Hook 后故意跳入无效地址 | ❌ | 安兔兔、哔哩哔哩：`SIGSEGV`，`pc` 在 `<anonymous:6faac00000>`，栈全在该库，两个进程的故障地址相同 |
| 其他商业加固/风控 SDK 的 `/proc/self/maps`、libc 入口字节、系统调用特征检查 | 🔍 | 另一会话的分析见 `runtime-multi-app-and-stealth-analysis.md` |

这一层的修复属于 native Hook 的隐蔽性工作线，不在 Java 兼容层内解决。

## 3. 真机普查（2026-10-18，设备 d861a4fa，人工点击启动，等待 25–30 s）

| 应用 | 初次 | 现在 | 主要修复 |
|---|---|---|---|
| DeepSeek、WPS协作、美团外卖、脉脉、拼多多、携程旅行、Bitwarden、BOSS直聘 | 到达界面 | 到达界面 | |
| 多看 | `ACTIVITY_DELIVERY_PROCESS_DIED` | 到达界面 | 包可见性语义 |
| 美团 | 回到工作台（Cronet native 崩溃） | 到达界面 | 连接管理器只读观察放行 |
| 原神 | 回到工作台（flags 被拒） | 到达界面（隐私弹窗） | Activity flags |
| 大众点评 | `DeadObjectException` | 到达界面 | 平台动作接收器注册；`getSystemService` 透传 |
| 小米文档查看器（WPS定制） | 弹出组件选择 | 同 | 非故障（无单一启动入口） |
| 番茄免费小说 | `APPLICATION_POST_ATTACH_IDENTITY` | 到达界面（欢迎页） | 物理身份不变量跟随客体自己的 `mResources`（L1） |
| 安兔兔评测 | `WORKER_DIED` | 同 | `libmsaoaidsec` 自杀（L13） |
| 哔哩哔哩 | 30 s 超时 | 越过多个失效点后，同样被 `libmsaoaidsec` 终止 | 同进程 provider 死锁、flags、extras、PackageManager 等（L13 剩余） |

13 / 15 个应用能进入界面；剩余 2 个（安兔兔、哔哩哔哩）被 `libmsaoaidsec` 的自杀终止，属于 native 隐蔽性工作线，而不是 Java 兼容层。

## 4. 修复记录

| 项 | 改动点 |
|---|---|
| 同进程 provider 死锁 | `RuntimeProviders.ready` |
| 包可见性（其他包为缺席） | `GuestPackageManager`、`GuestPackageInfo`、`GuestActivityInfo`、`RuntimePackages`（`PackageAbsent`） |
| PackageManager 默认分支 | `RuntimeSelfHookPackages.fallback`、`RuntimePackageBridge.launchIntent/receiverInfo` |
| 隐式 Intent 解析 | `GuestIntentResolver`；`RuntimeSelfHookPackages`、`RuntimeSelfHookActivities` |
| 非 Activity `startActivity`、非客体目标透传 | `RuntimeEngine.routeFromContext`、`RuntimeBroker.routeActivity`（`fromContext`）、`RuntimeSelfHookActivities`、`GuestContext` |
| Activity flags、options | `RuntimeActivityIntent.FLAGS`、`RuntimeSelfHookActivities` |
| `moveSharedPreferencesFrom`/`moveDatabaseFrom`、`getExternalFilesDir` | `RuntimeStorage`、`GuestStorageContext` |
| 接收器 `priority`、平台动作注册 | `RuntimeBroadcastIntent` |
| extras 数据形态（Binder、Parcelable、Serializable、列表） | `RuntimeProviderFacade.primitive/digest`、`RuntimeReceivers.send` |
| `getSystemService` 未建模服务 | `GuestContext` |
| 管理器只读观察 | `RuntimeSelfHookManagerPolicy.observation` |
| 前台服务 | `GuestContext`、`RuntimeSelfHookPlatformCallbacks.foreground`、`RuntimeBridge.serviceForeground` |
| 夹具期望（旧"拒绝"契约） | `CapabilityChecks`、`PackageInfoChecks`、`SystemServiceChecks`（改为 `NameNotFoundException`/`null`） |

验证：真机 `validate-workbench-control` 的 `control`(59)、`app-actions-ui`(20)、`hook-ui`(11)、`library-ui`(14) 全部通过；15 个应用的人工点击普查见上。**未跑**：`validate-runtime-probe` 全套（夹具已改，需要重建探针 APK），`runtime/test` 单元测试。

## 5. 构建说明（并发编辑，**重要**）

同一工作树里另一个会话（native Hook 的隐蔽性工作：`/proc` 虚拟化、`readlink` 路径改写、`readPhysicalProc`、进程名参数）在 02:31–02:38 又改了 `android/self-hook/native/*`、`android/runtime/native/*`、`RuntimeSelfHookStorage.java`、`RuntimeNativeMappingEvidence.java`，并新增了 `near_address_test.c`。结论：

1. **用当前工作树正常构建，会得到一个所有应用都启动失败的 APK。** 我实测过：native 与 Java 一起取当前工作树（只跳过本机必然失败的 `check_native_upgrade.py`）构建、安装后，8 个应用无一能启动，统一报 `HOOK_MODULE_CANONICAL_ROOT_CHANGED`。原因在 `path_policy.c` 新加的 `tp_unresolve`，它把 `readlink` 返回的客体制品路径改写成虚拟路径（`/data/app/~~tripoder/<pkg>`），而 `RuntimeHookBridge` 用 `getCanonicalPath()` 与真实根目录做相等校验。
2. 正常构建链在 native 校验阶段就会失败：`near_address_test.c` 在当前 macOS 上断言失败（`mmap` 提示地址的行为），且 `near_address.c` 用了在 macOS 上已弃用的 `syscall`。
3. 我没有修改他们的文件。设备上现在装的 APK 是这样来的：native 库在隔离目录里构建（`near_address.c`、`art_framework.c`、`io_adapter.c`、`path_policy.h` 用与当时那次成功构建所记录的 SHA-256 对得上的还原版本；`code_page.c`、`self_hook_storage.c` 用 HEAD，它们当时本来就等于 HEAD；`art_access.c` 用工作树版本；`path_policy.c` 取工作树版本并去掉 `/proc` 虚拟化块与制品路径改写，**该文件的旧内容无法精确还原**，SHA 对不上），Java 侧把那两个 Java 文件换成 HEAD 版本。
4. 因此：**我的 Java 改动已经在这个"旧 native 配对"的构建上跑过真机回归，但没有在"正常构建链 + 他们的新 native"上验证过。** 他们的工作合并之前，需要先解决第 1、2 点；之后用正常构建链重新跑一遍 `validate-workbench-control` 全部阶段和 `validate-runtime-probe`。
5. 我自己对 native 的唯一改动是 `android/self-hook/native/application_adapter.c`（番茄的身份不变量诊断与 `mResources` 跟随），该文件不在另一个会话的改动范围内；它已在真实工作树里，也编进了设备上的 native 库。
6. 为了让这件事可以复原，我把当前设备上用的 native 库和之前的实验库分别留在 scratchpad 的 `native-keep/` 里（不属于仓库）。

## 6. 尚未解决的设计

### 已实现（第二阶段，2026-10-18 晚）
- **PendingIntent activity / service**：令牌指向引擎里的蹦床组件，权威按账本取出逻辑 Intent 后交给客体进程 `startActivity`/`startService`（`RuntimePendingDispatch`、`DISPATCH_PENDING=52`）。服务种类与 activity 种类都在真机上看到 `PENDING_DISPATCHED:kind=…`（activity 的目标是 `<activity-alias>` 声明的启动入口）。无法建模的目标得到与未建模种类相同的惰性令牌，而不是让调用方崩溃。
- **`<activity-alias>`**：`getLaunchIntentForPackage`、隐式解析、PendingIntent 目标解析都把别名当 activity。意图过滤器随精简描述文件（`filters`）带到客体，客体不再需要完整清单树。
- **广播里的 Binder、通知发布、权限请求桥、前台服务、TrafficStats、AUDIO 控制类观察、Cursor 关闭**：均已实现；通知与权限系统对话框尚未在真机上端到端看到。
- **权威进程冷启动**：`BroadcastLedger.recover` 原来对每个历史代各扫一次投递表（O(代数×投递数)），累积后冷启动超过 25 s，表现为控制测试的"工作台连接超时"和启动卡在"正在启动空间中的应用"。改为一次集合式 SQL，冷启动 BROKER_READY 约 1 s。`TripoderBoot` 现在记录 `BROKER_READY` 与超过 200 ms 的恢复步骤。

- **应用自发粘性广播**：`sendStickyBroadcast`/`removeStickyBroadcast` 按 Android 的权限规则（需声明 `BROADCAST_STICKY`）在客体进程内保存每个 action 的最新一条，`registerReceiver` 命中时返回。范围是单个进程（同一应用的其他进程看不到），已通过回归但未被真实应用触发。
- **前台宿主优先级**：客体运行在引擎自己的 `:slotN` 进程里，可见 Activity 与前台服务（`startForeground` 经 `RuntimeBridge.serviceForeground` 落到宿主通知）已经使进程获得系统给的前台优先级；没有额外的缺口需要补。
- **主机侧测试**：`runtime/test` 的 `ExecutionContractTest` 与 `ArtifactSetHostTest`（47 项）通过；后者因生产类现在引用 Android 类型而无法编译，已改为带 `android.jar` 编译并去掉 `ArtifactIntegrityCache` 对 `ApkStore` 的依赖。

### 仍未解决
AppOps 外观（需要扩展 native 固定清单）、split APK、`libmsaoaidsec` 反篡改（属于另一条隐蔽性工作线）、`validate-runtime-probe` 全套（需要用 NDK 构建链重建探针 APK，而该构建链依赖当前损坏的 native 树）、在"正常构建链 + 另一会话的新 native"上的验证（见第 5 节）。

## 6.1 早期设计记录（已按上文实现）

### PendingIntent 的 activity / service 种类
现状：令牌是真实的 `PendingIntent.getBroadcast`，指向权威进程里的 `RuntimePendingIntentIngress`，逻辑内容与版本记在账本里（支持 update/cancel/no-create 与按安装代的退役）。
设计：
- `getActivity`：物理令牌指向权威进程里的一个蹦床 Activity（带版本）。启动时按账本取出逻辑 Intent，走与 `routeActivity` 同一条"权威侧、无源 Activity"的路径（`fromContext` 已具备），拿到 stub 与 ticket 后再起物理 Activity。蹦床必须是 Activity，因为后台启动 Activity 的豁免只给 PendingIntent 的发送方。
- `getService`/`getForegroundService`：同理，蹦床是 Service。
- 账本不需要新列：用 `routeAction` 前缀区分种类；`identity()` 的键前缀也要加种类，避免与广播令牌撞键。
- 需要修改 `get`/`lookup`/`absent`/更新路径中写死的 `PendingIntent.getBroadcast`，以及 `sendPending` 对自身令牌的发送。
- 必须同步更新 `runtime-probe` 的 `pending` 阶段，并重建。

### 广播里的 Binder
权威按字节保存广播负载。需要把 Binder 与字节分开：客体在发送请求里把 Binder 放进一个单独的 Bundle（Binder 事务可以携带），权威在内存里按票据保存，投递时一起交给接收方，接收方把它放回 extras。进程重启后丢失，符合 Binder 的生命周期。
