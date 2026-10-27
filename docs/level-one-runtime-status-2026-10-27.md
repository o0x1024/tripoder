# Level 1 实施状态与交接记录

2026-10-27，承接 [六应用诊断与整改方案](six-app-runtime-diagnosis-2026-10-27.md)。按用户要求整理当前工作并提交；**这是未完成验收的实现快照，不是 Level 1 冻结或六应用兼容性通过报告。**

## 当前结论

Manager 固定 ART Hook 的启动依赖已从产品装配链移除；通知、Job、快捷方式转到明确的 Binder/实例边界。Native 存储从全进程 seccomp/SIGSYS 接管改为现有十个固定 libc 文件入口，启动不再安装 SIGSYS 转发补丁或抑制 debuggerd 请求。前一版 Level 1 自研样本在真机通过 19 项检查，固定绑定实测由 1818 降至 220，Manager 固定绑定为 0。

之后又修改了启动 ART 发布策略、移除 Widget 固定绑定，并增加保护页恢复和 Native dump 工装。该版完整构建成功，原生安装的自研样本通过，16 项定向 Android 检查通过；**宿主完整测试期间 ADB 设备 `d861a4fa` 断开，未取回结果**。不能将上一版的宿主通过结果套用到这些后续改动。

六个商业应用的 Level 1 Smoke Gate **尚未开始**。没有完成五次冷启动、三分钟业务交互、五轮前后台切换，也没有证明 ≤500ms 启动 Hook 门槛。

## 实现内容

### 已完成的前置修复

- 无 action 的广播过滤器保留 data/category，可以注册但没有隐式 action 匹配。
- 动态、manifest 和有序最终 receiver 统一经过 `RuntimeReceiverProxy`，移除客体 `onReceive` 的动态 ART 安装入口；共享平台 PendingResult，保留 `goAsync`、有序结果和注销账本。
- 裸 ContextImpl 的七个外部目录入口复用客体存储 owner；服务绑定收敛到 `bindServiceCommon`，带上 Handler/Executor、long flags、isolated instance，并补齐解绑及分组入口。
- `RuntimeGuestPayload` 在交付边界重新封装 Parcel，使已捕获旧 loader 的 LazyValue 在客体 loader 下重新建立，保留 Binder 和延迟解包。
- Level 0 独立宿主不打包 runtime/selfhook 类和 Native core。完成原生/宿主各 5 次冷启动与各 900 帧采样；这只证明自研场景，不代表商业应用性能。[第一批汇总](evidence/six-app-implementation-2026-10-27/summary.json)。

### Manager 与 PackageManager 去依赖

- `RuntimeSelfHookProduct` 不再调用 12 个 Manager 家族的全量安装器；`RuntimeFixedPreparation` 不再检查其全量 inventory、准备 helper 或保留绑定。
- 普通设备服务从物理宿主 Context 获取平台 Manager。其 UID、权限和系统行为由真实 Android 决定；不再声称这些入口仍提供原来的客体专用观测/模拟。
- 通知在 `INotificationManager` 边界复用已有渠道、分组、通知 tag 的空间映射；物理调用使用明确的线程重入标记，避免二次映射。
- JobScheduler 改为实际 Java 子类，继续经过原 authority 调度账本，支持 namespace、schedule/enqueue、查询、取消。
- ShortcutManager 使用受限 Binder 能力代理；现阶段不宣称外部 Launcher 快捷方式已实现。
- IPackageManager 已有显式 endpoint，因此移除了 ApplicationPackageManager/PackageManager 方法的全量替换，只保留原始 Context/ServiceManager 获取入口。
- VPN 隐藏、设备网络状态、电话、定位、电池模拟在非透传配置时明确返回 `DEVICE_MANAGER_OBSERVATION_NOT_INSTALLED`；非默认 syscall 分析策略返回 `SYSCALL_ANALYSIS_POLICY_NOT_INSTALLED`。这些能力需要后续按明确请求重新装配，不能把工具请求接受但不生效视为兼容。
- `managerFixedBindings` 从已安装方法集合计算；不以硬编码零值替代实际装配检查。

### Native 路径、信号和 Park

- `RuntimeSelfHookStorage.installNative` 发布现有 `tp_fixed_libc` 的十个路径适配入口，不再安装 `tp_syscall_namespace` seccomp filter，不再调用 `tp_signal_user_delivery_reserve`，也不再 claim debuggerd 的信号以吞掉 dump 请求。
- **直接客体 SVC 路径不再重写。** libc 文件调用仍走原路径策略；这不是 Linux namespace，也不是完整原始 syscall 隔离。加固库若直接通过 SVC 打开虚拟路径，需要保留首次失败证据，再决定最小承载补齐方式。
- 仍保留客体 Native 模块的信号导入接入，使普通 sigaction 继续参与 ART 信号链。仍保留固定 libc/链接器发布所需的 Park 信号 60 预约；`nativeSignalChain.claims=0` 仅表示新增 ART special-handler claim 为零，不能解释为进程没有任何 Native 修改。
- `tp_park_begin_bounded` 只对已完整释放的 TIMEOUT/INVENTORY 快照重试，最多三次，间隔 1/2ms。结构错误、丢失所有权和不确定发布不重试；原有线程释放失败仍 fail-stop。
- 已添加自有 PROT_NONE 页 + SIGSEGV handler 恢复、handler 内 libc 文件读取、mask/ucontext 保真、fork/exec 返回码和 Java NPE 工装。宿主结果尚未取回，不能认定 Native 整改全部验收完成。

### 最新 ART 启动发布改动：待真机确认

首个 Level 1 样本虽然只剩 220 个绑定，批量发布仍约 1349.6ms，`hooks` 累计阶段约 1953ms。耗时主要仍在全局 caller 失效/框架 stub 重配置，不是单纯由绑定数量决定。

最新代码在客体执行前发布固定方法入口，启动不再做全局 caller 失效；明确请求普通分析 Hook 时才执行 caller 失效。它保留普通 JIT，并取消未证明必要的 Widget 固定绑定与已失效的反射安装尝试。

此改动**不保证预先编译/内联的框架调用者全部经过新入口**。需要验证所有保留的承载入口，并跑 `RuntimePolicyIntegration -e entryBootstrap true` 证明后续分析 Hook 的真实改值、编译调用者失效及撤销恢复。该参数检查已加入工装，但尚未运行通过。源码预计减少的绑定数不作为设备实测值。

## 验证分层

| 构建/阶段 | 已取得的证据 | 不能据此认定 |
|---|---|---|
| 第一批前置修复 | 403 源 SDK 编译、自研 Android 19 项、Level 0 10 次冷启动/1800 帧 | Manager 裁剪、六应用通过 |
| Level 1 首版 | 404 源 SDK 编译/主机回归；原生自研 APK；Android 16+3 项；固定绑定 220、Manager 0、运行期发布 0；libc 10 入口、Park 一次成功 | ≤500ms；保护页/exec/Native dump；商业应用 |
| 最新启动优化版 | 完整 Java/Native 构建；带 Native 检查的原生自研 APK 显示 PASS；Android 定向 16 项 | 完整宿主测试、保护页/Native dump 的宿主通过、后续分析 Hook 回归 |
| 提交前最终本机构建 | 修正工装参数读取、使用实际安装集合计算 Manager 计数；Java/Native 构建通过，尚未安装 | 该最终 APK 的任何宿主验收 |
| 六应用 Smoke Gate | 尚未执行 | 任一商业应用恢复正常、Smoke/Release Gate 通过 |

[Level 1 首版宿主回执](evidence/level-one-2026-10-27/ingress-attempt-1/GuestIngressIntegration-result.json)、[最新原生自研样本](evidence/level-one-2026-10-27/ingress-attempt-2/native-fixture.json)、[最新定向 16 项](evidence/level-one-2026-10-27/ingress-attempt-2/RuntimeIngressIntegration-result.json)、[中断状态与最终构建摘要](evidence/level-one-2026-10-27/summary.json)。

当前验证使用独立 `dev.tripoder.leveloneqa`，没有覆盖安装 `dev.tripoder.engine` 或 `dev.tripoder.faultqa`，没有清除六应用原有数据。设备断开时最后一轮测试及 QA 进程是否已退出尚未确认；重新连接后先读取进程、引擎状态和原 operation，不自动重放 UNKNOWN。

## 恢复工作顺序

1. 连接设备，确认最新 APK/core 与构建回执一致，先取回 `ingress-attempt-2` 未回收的 instrumentation 结果/Native dump（若存在），记录中断后真实状态。
2. 在新进程上重新执行最新自研完整入口与 Native 契约，读取真实绑定/发布/Park 计数；单独跑 `RuntimePolicyIntegration` 的 `entryBootstrap=true` 路径，检查真实 JIT 调用者、停用恢复和 compiler 状态。
3. 对源码中的现有 Manager 策略旧测试保持边界认识：它们通过不等于新 Binder 边界已覆盖全部旧 API。继续补验证通知跨空间/权限/取消、Job 多 namespace/执行及裸 Context 路由。
4. 锁定唯一 Level 1 构建后，对六应用核对 APK/Split、版本、初始状态、刷新率、网络及热状态。分别执行原生对照和 5 次独立冷启动、3 分钟核心交互、5 轮前后台；保存每次失败和未到达阶段，不自动重试覆盖。需要用户认证/风控操作时记录阻断，不伪造首页或起播。
5. 按文档判定 Smoke Gate；未达标的应用优先补显式承载边界。之后才考虑按组加回分析能力及 Release Gate。

复现命令（全部构建/证据目录应保持隔离）：

```sh
rtk proxy python3 android/scripts/build-workbench.py \
  --build-dir android/product/build-level-one \
  --sdk /Users/len/Library/Android/sdk --platform android-37.0 --build-tools 37.0.0 \
  --java-home /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  --ndk /Users/len/Library/Android/sdk/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.leveloneqa

rtk proxy python3 android/scripts/validate-runtime-ingress.py \
  --receipt android/product/build-level-one/build-receipt.json \
  --output docs/evidence/level-one-2026-10-27/resume-1 \
  --serial d861a4fa --retry-engine-once
```

`--retry-engine-once` 只允许一次明确的工作台重连，不重放客体操作。主机编译及 Python 语法/差异检查通过；最后的构建身份与 source 摘要以汇总 JSON 为准。原始日志、二进制和来源压缩快照保留在忽略的本机输出目录，不随本次提交推送。
