# Native Hook 续接证据

说明与结论：[续接报告](../../self-hook-native-followup-2026-10-17.md)。机器可读结果：[followup-status.json](followup-status.json)。

| 证据 | 结果 |
|---|---|
| `control-reproduction-01/` | 当前正式产品冷启动控制验收 62 项通过 |
| `affinity-diagnostic/`、`affinity-final/` | CPU 0–5 可保持单核；6/7 请求被扩展。最终结果有独立 verifier |
| `performance-cpu5/` | 五轮交替，主要路径 10 万样本，保留原始二进制样本和完整制品 |
| `performance-cpu5-long/` | 五轮交替，Java 路径百万样本；全部中位数变化未触及 5% 退化线，JNI 尾延迟噪声未通过 |
| `managed-admission-01/` | 32 个新类加载器下 608 项、512 次切换通过 |
| `managed-admission-loaded/` | 增加 2304 个已有 Hook，608 项通过 |
| `managed-admission-named/` | 增加真实 JNI 名称解析与 10000 次预调用，738 项通过 |
| `managed-admission-final/` | 739 项、640 次切换、693 次实际 GC；33 个 Native 根映射移动，2337 个备份根仍全部有效 |
| `jni-sdk-regression/` | 31 项通过，包含 32 参数、384 次并发调用与注册变化 |
| `art-original-failure-analysis.json` | 同源码参考构建的寄存器/反汇编推断，不能替代精确失败谓词或原始内存 |
| `production-core-identity.json` | 本轮未改生产核心；Runtime/Product 全量输入一致性也记录在 status 中 |
| `post-benchmark-source-delta.json` | 长采样后只补充了 Managed GC 证据，Native SO 和 benchmark 源码未变 |
| `final-probe-artifacts/` | 最终探针 APK/SO/receipt，APK SHA `4ec4e460e277774db1a76a4af667d938a464673d3f96ec28f227831a83032a01` |

所有结果保留各自源码摘要和构建身份。早期测试的检查数不同，是逐步增加验证条件，不将历史结果改写成最终检查数。完整性能验收和历史 ART 根因仍为 false；不宣告完整 P5/P6、第三方业务或安全回收完成。
