# 自研 ARM64 Native Hook 改造与验收

本轮范围是 `RuntimeNativeHookBackend → NativeHook → tp_native_*` 的单一自研后端。业务验收按用户确认沿用 P4 自有应用 Native/mixed 用例。证据根目录：[`evidence/native-core-upgrade-2026-10-16/`](evidence/native-core-upgrade-2026-10-16/)。日期跨日，保留开始日作为证据目录名。

## 实现与设计来源

采用 ShadowHook 的入口/卸载生命周期和错误分层思路、Dobby 的指令级重定位约束思路、Gum 的调用上下文及资源所有权思路；本轮没有引入上述库、复制其实现或增加第二条 Hook 路径。具体实现仍围绕 Tripoder 的固定平台校验、整页发布、模块 generation、审批与 UNKNOWN 协议。

| 阶段 | 原缺口 | 最小实现 | 验收方式 |
|---|---|---|---|
| S0 | 基线、构建与执行证据不容易一一对应 | 保存原源码摘要、旧 APK/SO、receipt、旧设备报告；每次运行使用唯一 ID | 源码/APK/SO/运行 ID 交叉核验 |
| S1 | 模块引用在 C 回调中才获取，入口到 TLS/JNI 之间存在窗口 | 汇编 ingress 在外部调用前获取原模块状态的引用；结果编码完成后释放；每绑定在途计数 | 确定性入口 gate、31 秒迟到 original、停用及卸载、实际退出归因 |
| S2 | bool 错误缺上下文；准备失败资源和发布失败语义混杂 | 同线程 typed error、阶段/errno/planner 分开；未发布 JNI context 可释放；已发布资源保留 | 六阶段故障注入、64 次失败准备、取消/root 计数、提交后真实退出 |
| S3 | 固定近地址范围未纳入被搬移指令的实际约束 | 对目标 B、返回 B、TBZ/CBZ/ADR/ADRP/literal 等范围求交，再选择映射地址 | ASan/UBSan、独立汇编 oracle、真实 ELF 七类前导 |
| S4 | 缺少同源旧新核心对照 | 同一 benchmark 源码、交替安装、原始样本和摘要校验、CPU 亲和性核验 | 五轮对照；不把噪声或时钟粒度解释为提速 |
| S5 | 探针通过不能证明实际产品控制链 | P4、正式 Engine/Workbench、UI、Agent normal/disconnect 分别验收 | 各自绑定当前构建 receipt |
| S6 | 已发布代码尚无完整安全回收证明 | 继续进程生命周期保留；不增加定时或引用归零即释放逻辑 | 条件阶段，不声称已经实现安全回收 |

## 入口和退休协议

`scalar_admission.h` 中的 32 字节记录只保存原 linker observer 的状态指针、generation/LIVE 身份、绑定在途计数地址和测试 gate。没有创建第二个模块生命周期管理器。

1. 目标入口跳转至不可变 relay，再进入固定 scalar slot。
2. 汇编保存参数，验证 generation/LIVE，以原子操作取得模块引用，然后增加该绑定在途计数。之前无 TLS、errno helper、JNI、分配器或 C 调用。
3. C/JNI 回调和最多一次 original 在该引用下执行。
4. scalar 返回值与 errno 编码完成后，汇编减少计数并释放模块引用。

退休沿用线程 park。若观察到 PC 在 relay、slot、尚未取得引用的 common prologue 或 admission leaf，返回 `BUSY`，整批入口保持原状。已取得引用的调用可在入口恢复之后完成，其引用阻止模块卸载。Runtime 保持 `DRAINING`，不把 BUSY 改成成功，也不重装其它后端。

发布过的 relay、original、FDE、回调 context、JNI global root 和 slot 仍保留到进程结束。`in_flight` 是观测数据，不能作为释放依据。任意信号隐藏的旧执行位置、跨桥 longjmp/异常等不在本轮完整安全回收证明范围内。

## 失败与资源协议

新增状态：`ARGUMENT`、`STALE_MODULE`、`CAPACITY`、`ABI`、`IMAGE`、`PREFIX`、`ADDRESS`、`RESOURCE`、`STATE`、`CONFLICT`、`PREIMAGE`、`PARK`、`BUSY`。记录包含阶段、系统 errno、独立的 planner status，以及可用时的 handle/TID/PC。Java 提供结构化资源快照，业务控制结果不暴露裸地址。

- 非法签名、模块身份和不支持的前导尽量在消耗 slot 前拒绝。
- loader 接手前，准备失败释放本次 heap/fd/地址预留；loader 可能已接手的映射不盲目 `munmap`。
- 成功装载但后续校验失败的不可变 image 计入保留账目，不以危险回收换取表面无泄漏。
- JNI context 按准备分配。失败准备和从未发布的 PREPARED 取消会释放 global root/context；已发布的退休 context 保留。
- slot 保持单调发放，失败准备的消耗可见，不重复使用潜在已暴露身份。
- 只有经过证明“没有入口变化，准备已取消”的失败会变成 `HookPublicationRejected`。旧观察/审批 token 失效，需新观察和新 token；预算不返还。
- 已有分区发布或真实页提交之后出现不确定失败，继续 fail-stop。测试在真实提交后写入 witness，再退出 230，验证没有成功 ACK。

Native JNI ABI 升为版本 2，构建配套校验，未提供旧 ABI 兼容路径。

## 地址规划与指令准入

新的纯地址规划器用 checked `__int128` 计算各条指令可接受的 image-base 区间并求交，地址分配只在交集内进行。候选按离 preferred 的距离选择，最多保留 128 个；使用 `MAP_FIXED_NOREPLACE`，只有 `EEXIST` 可以尝试下一个已计算候选，其他系统错误直接失败。

原有固定 16 字节 original 模板保持不变。没有用更长跳板、覆盖更多目标指令或增加 RWX 来掩盖范围不足。

解码器收紧 reserved move-wide、32 位超宽 shift、ADD/SUB shift、load/store size/op 等准入；FP/SIMD 从宽泛类匹配改成明确支持的 S/D 运算/move 和 vector MOVI/EOR mask。新增指令必须同时满足语义、范围和真实执行验证。

## 测试和证据说明

新增主机检查：地址约束 62 项、候选分配行为 6 项、独立 NDK 汇编样本 18 项；原有核心 92 项、LLVM oracle 38 项、prefix 134 项及其汇编 oracle 35 项继续执行。inbound 二进制审计检查 common 108 字节、4 个 FDE 区间、admission/leave 无外部调用和原子操作。

新增设备检查：生命周期/准备失败/真实 JNI 资源合计 122 项，真实 ELF 范围前导 52 项。保留普通导出、PAC、leaf、并发、卸载时退出、JNI、产品 Native/mixed 及 RegisterNatives/SDK 的原有用例。预期退出 198/230 必须结合对应测试意图和 witness，不作为正常业务 crash 忽略。

性能报告保存 C、JNI、外部 pthread JNI、产品规则路径的原始 int64 样本及 SHA。C 每样本测量 64 次调用，报告的分位数是批次摊销延迟，不能冒充单次尾延迟。其他路径每样本一次调用。包括时钟和测试框架开销；不做不透明的基线相减。微基准中的产品规则经过真实 Runtime backend，但未连接正式 broker，其结果不能替代 S5。

最终运行状态、当前构建身份和失败记录索引见 [证据汇总](evidence/native-core-upgrade-2026-10-16/README.md) 及 `delivery-status.json`。历史失败目录不覆盖、不改绿。

## 未通过的门槛与额外发现

**续接结果优先见 [2026-10-17 定向复现报告](self-hook-native-followup-2026-10-17.md)。** 后续逐核诊断确认 CPU 0–5 可保持单核绑定，CPU 6/7 会扩展掩码；旧测试总选 CPU 7，因此先前把限制外推到整个设备的判断已纠正。显式 CPU 5 的两组五轮对照已完成，均值未触及退化线，JNI 尾延迟噪声仍未通过完整门槛。下文保存上一轮结论的来源。

**S4 性能无退化门槛未通过。** 五轮旧新交替的原始报告为 `performance-pairs/comparison.json`：C hook 均值的跨轮中位数为 126.91 → 136.73 ns（+7.73%），完整规则路径为 3421.70 → 3363.23 ns（−1.71%）。但对应旧基线的跨轮极差/中位数分别达 25.68% 和 25.61%，远超 5% 噪声门槛，不能得出确定的提速或无退化结论。

进一步增加每路径 500 ms warmup、等待前台窗口稳定、要求单 CPU 绑定并在结束后再核验。设备上 `sched_setaffinity` 返回成功后，实际 CPU 或返回的亲和性掩码仍不符合要求。旧核心和当前核心都被 `BENCH_AFFINITY_REQUIRED` 拒绝；没有取消门槛或以未绑定测量冒充受控结果。当前手机上的该项验收受运行环境限制，后续需能保持亲和性和稳定频率的测试设备重跑同源对照。

第一次正式 control 运行曾在 Java native 方法激活时出现 `TP_MANAGED_ENTRY_OWNERSHIP`。这是 ART 入口校验，发生在 scalar 回调前。新增诊断只在原校验失败后输出具体阶段和快照，不放宽 guard、不重写未知入口。之后独立 control 复测通过，**原始偶发失败的根因尚未证明**，不能把诊断代码称为修复。保留该失败记录，后续若复现按 stage/slot/quick/flags 追查。

正式 UI 验收工具另修正了两处测试前提：定位控件时请求目标滚入可见区域；复用仓库已有的独立测试操作日志，避免旧用户 UNKNOWN 操作禁用新测试按钮。原用户 pending ID 原样记入报告，不清除其记录。正常与断连 Agent 验收同样使用隔离的自有测试日志；其余产品 UI 行为未改动。

## 维护边界

继续锁定 Xiaomi/madrid Android 17/API 37/ARM64/4 KiB 及指定 libc/libart/linker 摘要。此次通过不能外推到其它 Android 版本、页大小、任意第三方应用或完整 P5/P6。

后续扩展按真实拒绝的指令样本添加 oracle 和真实 ELF 回归。S6 要先独立证明所有潜在执行位置、异步中断、返回地址和 JNI 根的退休协议，再讨论资源释放。当前优先维持可审计的单后端和明确失败边界。
