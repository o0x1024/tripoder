# SDK 与执行契约

## 边界

用户脚本使用标准 TypeScript。SDK 模拟器运行编译后的 JavaScript；Android 0.3 工作台通过 AST 解释标准 TS 的受限子集，不 eval 或 import 草稿源码。`@abox/hook` 暴露接口；第三方客体代理后端待实现。`android/` 已接入自有测试 APK 的窄范围真实后端，复用方案定义和参数校验。`HookSimulator` 仍仅验证单进程的同步语义，不提供 Android 权限、Native 指令改写、真实内存访问或脚本安全隔离。

当前源码按 SDK、模拟器、AI 上下文分开导出，后续可拆成独立包。参数及证据对象在准备时复制并冻结。元数据的 source／signatureSource 是来源标签，不是密码学认证；未来代理连接必须独立验证会话归属。

## 目标绑定

方案明确指定 packageName、versionCode（十进制字符串）、APK SHA-256。证据包含快照 ID、进程实例 ID、ABI、后端能力、符号及加载状态。激活时再次比较快照，不允许对重启后的进程复用旧准备结果。

真实代理需要定义 Split APK 集合摘要；当前模型只包含单个示例 APK 摘要，不宣称完整覆盖安装包集合。

Java 选择器按类、方法、参数类型匹配；多 ClassLoader 或返回类型歧义必须显式选择。Native 选择器需要导出符号、Build ID、参数和返回类型；签名来源为 unknown 时拒绝准备。v0.1 不自动反推 C/C++ ABI。

loaded=false 表示已索引但未加载，准备结果保持 pending；本原型不会监听类／库加载。后端提供新快照后须重新准备。

## 同步调用语义

每个目标允许一个 before 和一个 after，或者一个 replace，禁止重复修改阶段。before 返回新参数数组；原函数执行一次；after 返回最终结果。replace 返回结果且不执行原函数。

| 情况 | 模拟器行为 |
|---|---|
| before 失败或参数类型错误 | 用初始参数调用原函数一次，跳过 after，记录 Hook 失败 |
| replace 失败或返回类型错误 | 回退原函数一次，记录 Hook 失败 |
| 原函数抛异常 | 原样传播，不重试，不调用 after |
| after 失败或返回类型错误 | 保留原结果，不重复执行原函数，记录 Hook 失败 |
| 回调返回 Promise | 记录失败；不能取消该 Promise 已启动的操作 |

所有回退都只针对当前调用。回调中发生的网络、文件或业务副作用无法自动撤销。真实 Native 崩溃更不能由 JS 异常处理器可靠恢复。

## 热更新与回滚

准备新方案不修改模拟器的当前版本。参数校验或准备失败保留旧版本。一次调用捕获 rules 和 params 的同一版本；期间发生的更新仅影响后续调用。

回滚恢复历史代码和参数，生成新的递增版本号。历史保留最近 32 版；事件保留最近 128 条，当前版命中统计独立累计。回滚不恢复应用数据、脚本捕获的外部可变状态，也不撤销已完成请求。

这里的切换依赖单线程、同步 JS 模拟；未来跨线程真实代理需要读侧生命周期管理与资源回收机制，不能直接移植一个变量赋值来证明安全。跨进程部署应逐进程报告 prepare/activate/ack，不能承诺全局同一瞬间切换。

## 真实后端的接入门槛

- 证明原 APK 无需宿主 Root／ADB 授权即可在自管客体执行，并保持所需签名与身份语义。
- 报告当前进程、类加载器、符号、ABI、库版本与实际支持能力。
- 受控注册 Hook、验证原函数 trampoline、处理并发／递归／卸载与延迟加载。
- 区分脚本载入、真实命中、行为改变、功能验收和性能验收。
- 脚本引擎不暴露宿主文件系统、密钥或未授权进程能力；另行验证时间／内存预算和失控恢复。
- 重现本地测试覆盖的更新、回退和原函数最多一次语义，再增加线程／进程及 Android E2E。

自有测试 APK 已选择 Frida Gadget 17.23.3 的离线 Script 模式，使用 Java Bridge 7.0.13 和 Native Interceptor。普通应用进程中的实测不能代替第三方客体加载、ART 多线程、图形兼容或跨进程验证；通用适配器仍需逐项满足以上门槛。

v0.2 已增加独立测试 APK 的 `:guest` 进程、专用 DexClassLoader、JNI 库加载和 Binder 控制。APK 字节保持不变，Hook 在客体执行进程内生效。宿主和客体仍共享 UID，系统包身份与完整 Android 组件语义尚未虚拟化，详见 [客体运行器](guest-runtime.md)。

## v0.3 手机草稿与组件

`src/draft.ts` 用 Babel TypeScript parser 读取语法；只允许显式列出的节点，拒绝循环、赋值、动态导入、异步、类、构造器与原型访问。使用词法环境与开放函数白名单解释表达式和回调。类型标注可解析，但没有完整 TypeScript 类型检查。限制源码 32768 字符、AST 8000 节点／80 层、一次调用 3000 步／64 层、字符串 8192 字符、集合 64 项。结构性语法拒绝会覆盖未执行分支；回调中的未知标识符可在真实调用时失败并回退，不能把“检查通过”当成所有分支已证明正确。

代理将代码、准备后的规则与参数一起切换。调用开始时捕获快照，真实 Java／Native 两个固定函数支持 before／replace／after。Android 代理目前只保留上一版代码和参数；SDK 模拟器的 32 版历史不等于手机实现。卸载恢复原函数入口，错误记录最多 20 条。仍未验证在途多线程卸载或跨进程事务。

`AppComponentFactory` 在一个已声明的非导出 Activity 槽位中创建客体真实 Activity，`ResourcesLoader` 加载独立 APK 的资源，客体 Application 使用限定 Context。生命周期控制器同时注册到宿主和客体 Application；工具在 post-created 时添加，避免被客体 setContentView 覆盖。真实 Activity 的 OpenGL 直接使用宿主系统图形栈，没有完整虚拟 GPU。

活动系统身份仍为宿主，Activity Context 并未全面虚拟化；只有已实现的客体 Application Context 对文件、缓存、数据库名称和偏好设置做限定。Java `getApplication` 重定向不会凭空提供 PackageManager、Service／Provider、通知、权限或跨进程语义。资源包 ID 合并也可能冲突，当前只验证单一自有 fixture。

`GuestFiles` 是有界文本编辑器，拒绝路径穿越和符号链接。它不是针对恶意同 UID 代码的隔离边界。设备字段只改客体进程 Java Build 对象。窗口自动化通过 MotionEvent 分发和 EditText 写入；手柄映射只处理 SOURCE_GAMEPAD 的按钮事件。点击结果必须等待 View 异步 performClick 回调后判断。
