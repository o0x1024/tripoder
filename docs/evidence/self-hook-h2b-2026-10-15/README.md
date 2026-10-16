# H2b 线程暂停原语验收

同一 non-debuggable 独立 APK：`2228d68289900c259a8afaa8747d739c268742dfaeb8343154d9caa196d25bd7`。
sourceDigest：`b78fe2abe1ef2f966aec8df631fca12fbdeb88d0b52119bef5f1f970cd607a8f`。
buildId：`ad92aac7-0f51-4a4d-9000-6e7e506d364a`。

| 证据 | JIT | AOT |
|---|---|---|
| runId | [4d7dc2e7-8d37-4fac-8dc9-bb4dc98a051d](4d7dc2e7-8d37-4fac-8dc9-bb4dc98a051d/verification.json) | [d7729b21-1d19-4764-8cca-e21738880ce9](d7729b21-1d19-4764-8cca-e21738880ce9/verification.json) |
| 暂停原语检查 | 15/15 | 15/15 |
| 普通工作线程暂停 | 20 | 20 |
| 创建压力准入/明确拒绝 | 17/3 | 16/4 |
| 工作线程计数 | 73,940,900 | 95,000,335 |
| 捕获上下文/峰值暂停线程 | 1,238/34 | 1,172/33 |
| 故意屏蔽信号取消/迟到信号/新 epoch 恢复 | 通过 | 通过 |
| ART Field.get 编译调用 | 1,000,000 全拦截 | 1,000,000 全拦截 |
| managed caller 反优化前/后 | 10,000 全绕过/全拦截 | 10,000 全绕过/全拦截 |

每份证据包含本次 APK/源码清单、二进制关键区调用检查及反汇编、DEX 主机 14 项检查、完整 result/maps、基础核验及编译入口核验。未变更的 H1 核心主机 92+38 旧回执通过源码摘要比对复用；当前设备重新运行 92+16+45 项既有 Native 检查、15 项 ART 观察、62 项 Native Field.get、25 项 managed 检查。

JIT result SHA：`9b47e6d501a488b0483f1fb8925bf1f7c4d90c402bd0ece4350dbeff3614c973`。
AOT result SHA：`54ef25c6245bbc1906de836853e1f9d9b03b403cfa8f1ea20388b1436bc2f52b`。

创建压力的超时明确拒绝，采集到的线程信号掩码包含 60；恢复后的四个忙循环线程均继续前进。没有跳过未响应线程。[首次失败记录](b2f87733-bae7-464d-93d3-9b8041e30f21/README.md)保留。

本阶段只证明线程暂停/取消原语。`codePagesModified=false`、`noncooperativeHook=false`；没有在该协议下发布代码页，也没有完成通用 Hook 或产品去 Frida。
