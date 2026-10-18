# Native Hook 续接：CPU 亲和性与 ART 定向复现

这是用户“继续下一步”后第一阶段的结果，接续 [上一轮改造报告](self-hook-native-upgrade-2026-10-17.md)。原阶段证据位于 [native-core-followup-2026-10-17](evidence/native-core-followup-2026-10-17/)，当时只改探针、采样和验证工具。后续生产修复与状态如下，不能再把“未改生产逻辑”用于当前工作区。

## 后续生产修复与未闭合项

- 真实微信正式链路 `real-app-goal-2026-10-17/product-wechat-managed-jni-33` 已通过 21 项：原 `libwechatxlog.so::__xlogger_Level_impl` 的 C Inline Hook 228 次真实命中，`Xlog.getLogLevel(long)` 的 ART/JNI Hook 7 次命中，均原值转发、零错误、确认停用；无致命异常。此前第 32 轮的功能断言通过，但验收报告重复经 Binder 发送超过事务上限，整轮仍失败；现改为完整证据文件加 SHA-256 小型回执并独立核验。
- 正式王者启动和连续观测通过，仍停在协议页。`product-honor-native-31` 在 Hook 安装前发现目标 `libmarsxlog.so` 尚未加载，不能算 Native 验收通过；用户已选择亲自在手机上阅读并同意协议，尚未确认完成；登录和游戏业务仍待验证。
- `performance-preconditioned-23` 完整保留预先定义的 1 对预热和 5 对正式测量。中位数未出现 >5% 退化，但基线波动约 20%–35%，严格验收仍失败。CPU 5 的频率策略上限 2.7648→2.1888 GHz 与旧核心 C 均值约 181→225 ns 的变化同步。原 5% 门槛未放宽，未过滤尾部样本。
- 用户授权后的 `performance-fixed-mode-40` 已完整执行1对预热+5对测量，关闭模式命令成功返回（mode-control-receipt.json）。仍未通过：基线噪声、C路径中位数退化和频率策略变化均触发原门槛；C均值中位数+21.09%，JNI−3.55%、外部JNI−91.08%、规则−5.88%。固定模式未阻止观测到的CPU5频率上限变化，因此不能宣称性能达标或直接把C差异归因为核心代码。全部原样本保留。
- JNI 原函数传输移除两份临时数组，测量分配约 192 B/call → 120 B/call；尾峰与分配页首次触碰的周期吻合。因果记录：`native-core-rootcause-2026-10-17/jni-allocation-causality.json`。
- JNI 原生线程附着改为线程生命周期租约：已有 JVM 线程始终借用；自建线程 attach-as-daemon 一次，在 pthread 退出时 detach。ABI=4，新增三个线程账目字段；`jni-v4-thread-lease/85773da7-3aeb-441b-97cc-e312baf6fef8` 独立验证 33 项通过。
- `performance-transport-v3` 五轮中位数未出现 >5% 退化，但旧核心基线噪声仍超 5%，因此 `performanceAccepted=false`。该轮尚未包含线程租约优化。后续 ABI4 对照也已执行并留证，最新结论见上方；均未筛掉 GC、缺页或长尾。
- 原 ART abort 的完整 tombstone 已回收；原 guard 的多轮正式控制重放、真实 NativeProbe 的第 2215 槽和 31 秒延迟启用未复现。**历史确定根因未证明**，未放宽入口校验。
- Managed 准备把原四次分离的暂停合为一次一致的元数据/根/发布快照；保留原全部准入条件。helper DEX 复用不可变模板及摘要器，固定元数据和纯字节预计算移到全局解释策略之前，实际存储/入口发布仍在策略之后。
- 后一优化由冷广播预算驱动：helper DEX 生成原占约 4.56 秒；完整冷 Factory 从约 13.3 秒降到约 7.1–7.4 秒，未提高 10 秒门槛。主机独立完整编码比较与 dexdump 为 93 项/36 文件。
- `real-app-goal-2026-10-17/managed-admission-current/18c53fb3-1590-4af2-97af-eb1bb6a79a34/verification.json`：739 项、640 次切换、688 次实际 GC、33 组 Native root map 移动通过，仍明确 `historicalAbortRootCauseProven=false`。

上面目录均在 `docs/evidence/`。用户已将最终目标扩大到真实微信与王者正常运行及 Hook；当前真实应用与最新正式产品验收仍未完成，见 [task.md](../task.md)。以下保留第一阶段历史结果。

## 已解决：性能测试错误地选择 CPU 7

逐核实验对 CPU 0–7 分别用 `tid=0` 与显式 TID 请求亲和性，同时记录 libc 返回值、直接系统调用结果、立即与延迟后的 CPU 掩码和实际 CPU，最后恢复原掩码。两轮结果一致：

- CPU 0–5 能维持准确的单 CPU 掩码和实际执行位置。
- CPU 6 请求返回成功，但实际掩码为 `0x78`；CPU 7 请求返回成功，但实际掩码为 `0xb8`。两者均包含四个 CPU。
- libc 与直接系统调用读回的掩码相同，不能通过更换 libc 调用方式解决。

原测试选择“允许掩码中编号最大的 CPU”，因此总选择 CPU 7。**上一轮“当前手机无法做受控亲和性测试”的判断范围过大，现已纠正。** 测试改为显式指定 CPU，当前固定平台默认 CPU 5；不在失败后自动换核。请求不被满足时仍拒绝，C 采样与外部线程在测量前后检查亲和性，整轮结束再次核验。

原始与独立验证结果见 [affinity-final](evidence/native-core-followup-2026-10-17/affinity-final/0fc553cd-3b2b-429c-b05f-21e2a15be402/verification.json)。

## 性能对照：均值没有触及退化线，JNI 尾延迟仍不稳定

保留两套协议的全部结果，各自使用完全相同的四份 benchmark 源文件，对旧、新核心交替执行五轮，没有选取其中某一轮作为最终结果。

- [第一套 CPU 5 对照](evidence/native-core-followup-2026-10-17/performance-cpu5/comparison.json)：主要路径 10 万样本，外部 JNI 1 万样本。
- [较长 CPU 5 对照](evidence/native-core-followup-2026-10-17/performance-cpu5-long/comparison.json)：Java/JNI/规则路径 100 万样本，C 10 万个 64 调用批次，外部 JNI 10 万样本。每路径 warmup 500 ms。

较长协议的“均值跨轮中位数”如下，单位 ns，包含时钟与测试开销：

| 路径 | 旧核心 | 新核心 | 观测变化 |
|---|---:|---:|---:|
| C Hook | 181.495 | 177.928 | −1.97% |
| Java → JNI → Handler/original | 1188.132 | 1047.271 | −11.86% |
| 外部 pthread → JNI | 10881.158 | 10739.474 | −1.30% |
| 产品规则路径 | 7155.523 | 7021.977 | −1.87% |

C 基线均值跨轮极差/中位数为 0.22%，较第一轮未绑定 CPU 时的 25.68% 明显收敛。C 的均值/P95/P99 均满足原有噪声与退化检查。所有比较指标的中位数都没有超过 5% 退化线。

**完整性能验收仍为 false。** 较长协议中 JNI 基线 P95/P99 波动为 9.29%/13.05%，外部 JNI P99 为 5.47%，仍超过原 5% 噪声门槛；新核心部分尾延迟也有波动。因此不能用平均值改善替代尾延迟验收，也不能把这组结果扩展为任意设备/应用的性能保证。C 分位数描述批次摊销延迟，不是单次调用尾延迟。

基准执行后只增加了 Managed 压力测试的实际 GC 证据；四份 benchmark 源码和 Native SO 未变化，见 [post-benchmark-source-delta.json](evidence/native-core-followup-2026-10-17/post-benchmark-source-delta.json)。

## ART：缩小范围，但尚未证明根因

正式控制链在本轮再次冷启动通过 62 项。独立探针新增了下列定向条件：

1. 先安装 2304 个 Managed Hook，接近正式 Runtime 的注册规模。
2. 新建 32 个独立类加载器，覆盖未初始化、已初始化、已实际调用的普通 JNI 方法；在 GC 压力下反复准备、激活、original 和停用。
3. 增加与原 `canRead(String):boolean` 相同 JNI ABI 的方法，经真实符号名解析和 10000 次调用后再安装 Hook，继续在 GC 中切换。
4. 核验实际 GC 统计和每个备份根的前后地址，避免把 `System.gc()` 请求次数当作 GC 已完成的证据。

最终 [739 项检查通过](evidence/native-core-followup-2026-10-17/managed-admission-final/cfb15af4-aa4c-474e-ae98-bbf4cf0f4ddf/verification.json)：640 次目标状态切换、693 次实际 GC、2337 个备份根全部仍有效，33 个新增 Native 根映射都观察到移动。没有触发原始 abort。

另将历史崩溃的保存寄存器与同源码参考构建的反汇编对照：绑定索引为 2215，寄存器分配符合第一轮前像检查，不符合后面的 owner 注册或入口写入循环。这支持“在前像检查阶段失败”的判断，详见 [原始失败分析](evidence/native-core-followup-2026-10-17/art-original-failure-analysis.json)。由于缺少那次失败的完整二进制/内存快照，这只是范围收窄，**不能确定是身份、GC 根、quick entry 还是 flags 的哪一个谓词失败**。

没有为消除一次未复现错误而自动归一化 ART 入口、放宽所有权 guard 或增加替代路径。现有生产诊断继续保留 stage/slot/quick/expected/flags；下一次真实失败应优先保存这些字段，再决定修复点。

## 后续边界

CPU 选择阻塞已关闭。剩余工作是 JNI 尾延迟的稳定验收和原始 ART 失败的确定根因；本轮未把它们标记为完成。S6 已发布代码/FDE/context 回收继续按原计划延期。生产核心与上一轮已通过 P4、正式 UI 和 Agent 验收的源码一致，见 [production-core-identity.json](evidence/native-core-followup-2026-10-17/production-core-identity.json)。
