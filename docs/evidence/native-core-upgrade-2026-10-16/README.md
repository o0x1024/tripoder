# Native 核心改造证据

本轮从 2026-10-16 开始，2026-10-17 收尾。设备 `d861a4fa`，Xiaomi/madrid Android 17/API 37/ARM64/4 KiB。实现说明见 [改造报告](../../self-hook-native-upgrade-2026-10-17.md)，机器可读结论见 [delivery-status.json](delivery-status.json)。

**后续进展**：[续接证据](../native-core-followup-2026-10-17/README.md) 已确认当前设备 CPU 5 可保持单核，并完成两组新的五轮性能对照和 739 项 ART/GC 定向检查。下文为本轮原始快照，关于需要更换设备才能绑定 CPU 的判断不再适用；完整性能与 ART 根因仍未闭合。

**代码改造与约定自有应用功能验收完成；全部门槛尚未通过。** 性能无退化结论受设备调度与噪声限制；历史一次 ART 入口所有权 abort 的根因未证明。S6 已发布资源回收按原计划保持条件延期。本目录不宣告完整 P5/P6 或微信/王者业务完成。

## 最终功能结果

| 验收 | 结果 | 当前证据 |
|---|---|---|
| Native/JNI/mixed 设备矩阵 | 14/14 用例通过 | [final-matrix-v4/results.json](final-matrix-v4/results.json) |
| 入口生命周期、准备故障、JNI 资源 | 122 项通过 | 矩阵 `native-upgrade` |
| 七类真实 ELF 前导与范围约束 | 52 项通过 | 矩阵 `native-ranges` |
| 页提交后的故障、在途卸载 | 分别核验预期退出 230、198 | 矩阵 `native-commit-death`、`native-inflight-death` |
| Runtime P4 | 105 项通过 | [runtime-p4-final/device-result.json](runtime-p4-final/device-result.json) |
| 正式控制链 | 62 项通过 | [product-control-final/device-observation.json](product-control-final/device-observation.json) |
| 真实 Hook UI | 11 项通过 | [product-hook-ui-final/device-observation.json](product-hook-ui-final/device-observation.json) |
| Agent normal | 13 设备 + 18 MCP 项通过 | [agent-normal-final/device-observation.json](agent-normal-final/device-observation.json) |
| Agent disconnect | 9 设备 + 16 MCP 项通过 | [agent-disconnect-final/device-observation.json](agent-disconnect-final/device-observation.json) |
| 脚本契约 | 692 项通过 | [final-host/script-contract.log](final-host/script-contract.log) |
| 共享 libc 准入 | 471 项通过 | [final-host/fixed-libc-admission.json](final-host/fixed-libc-admission.json) |
| 核心与 LLVM oracle | 92 + 38 项，ASan/UBSan 通过 | [final-host/core-host-report.json](final-host/core-host-report.json) |
| 地址、allocator、汇编 oracle | 62 + 6 + 18 项通过 | [final-host/native-upgrade-host.json](final-host/native-upgrade-host.json) |
| Prefix 与汇编 oracle | 134 + 35 项通过 | [final-host/native-prefix-upgrade.json](final-host/native-prefix-upgrade.json) |
| 汇编 ingress | 108 字节 common、4 FDE、无外部 admission 调用 | [final-host/inbound-upgrade-audit.json](final-host/inbound-upgrade-audit.json) |

四个最终正式产品运行都核验了 worker 实际核心映射、源码身份和 bootstrap；未观察到 Java/Native fatal。Agent normal/disconnect 不等同于真实 Sentinel 客户端或模型工具调用验收。资源计数、构建与原始结果可在对应 run 目录复核。

## 构建身份

制品和完整 receipt 保存在 [final-artifacts/](final-artifacts/)。[source-identity-verification.json](source-identity-verification.json) 核验 Probe 438、Runtime 477、Product 393 个输入，均 `changedInputs=[]`。

| 制品 | SHA-256 |
|---|---|
| 独立探针 APK | `1caa8b049b353c5ce0dc5c372e1cf4ac7a8ee57ba181c5c6d764de8c081e4719` |
| Runtime APK | `8e3d9e2bbd362b3068963f0865c95239bf052a211acdda300b56ec40bd02b704` |
| Engine APK | `587236b457c2577b0c3b49f0921430cf6d8296fc3cade90ec00a592886ef3551` |
| Workbench APK | `effab557d0dd9f388ed7055d75351d095d3a6e9debc6ef41dadfe9dafbdfd125` |
| ControlTest APK | `353c214345377e1c061064ce70f918ff81112980d007d2ffe2d5abbdb8b25da6` |
| Engine 内 Native SO | `9b7eba09d98dbf9d48ba97f910c4288cc4ffb57b62f2895cf871159d7c7e9391` |

使用开发测试签名。经用户授权，旧独立探针 APK 和可读取报告先归档，再卸载重装独立探针；Engine/Workbench 保留数据更新。没有提交或推送本轮改动。

## 性能结论

[performance-pairs/comparison.json](performance-pairs/comparison.json) 保存五轮旧新交替、每条路径原始样本与摘要。C 是 64 次批次摊销延迟；JNI/产品规则是单次采样，均含时钟与测试开销。

| 指标：均值的跨轮中位数 | 旧核心 | 新核心 | 观测变化 |
|---|---:|---:|---:|
| C hook | 126.91 ns | 136.73 ns | +7.73% |
| JNI hook | 863.22 ns | 951.81 ns | +10.26% |
| 外部线程 JNI hook | 7191.09 ns | 7320.10 ns | +1.79% |
| 产品规则路径 | 3421.70 ns | 3363.23 ns | −1.71% |

旧基线跨轮噪声超过 5%，所以这些数值不能证明提速或无退化。随后严格亲和性复测在 `performance-pinned*` 目录中被拒绝；当前核心的独立复核见 [performance-current-affinity/](performance-current-affinity/)，错误为 `BENCH_AFFINITY_REQUIRED:-6000`：请求绑定成功后，实际掩码仍非要求的单 CPU。保留拒绝，不降低检查标准。下一步应在能保持 CPU 亲和性和频率的环境中执行同源五轮对照；无需因此增加外部 Hook 引擎。

## 失败证据与边界

- `product-control/`：历史 `TP_MANAGED_ENTRY_OWNERSHIP` abort。新增诊断后的 `product-control-diagnostic/`、`product-control-repeat/`、`product-control-final/` 三次 control 均通过，但原始根因未证明。
- `product-hook-ui/`：验收滚动定位失败；`product-hook-ui-v2/`：旧用户 pending 操作使进程选择按钮禁用。最终工具复用独立测试日志、保留原 pending ID，完整 UI 流程通过。
- `final-matrix-v3/`：性能对照后仍装着旧基准探针，新增 Activity 无法启动；恢复当前探针后的 `final-matrix-v4/` 为完整当前结果。
- `final-matrix/`、`final-matrix-v2/` 及其它早期目录为各自制品的历史证据，不能替代最终身份核验。
- 已发布的代码、FDE、slot 和 JNI context 继续进程生命周期保留。不得以在途计数归零直接回收，也不把本轮用例视为所有异步执行情形的安全证明。

所有历史失败目录原样保留。[changed-source-audit.json](changed-source-audit.json) 记录源码检查；未通过的门槛在 `delivery-status.json` 中明确为 false。
