# H2d 固定 libc 十入口 / 两共享页验收

同一 non-debuggable 离线探针 APK：`190a1c5c5125c1b9f639312214ae27a534cfbef40b0621d2785d8c15bbd00c43`。
sourceDigest：`d0f18db705b8b7c5f7b37cae19f1b2848ffec5882348528cf9b8aebf57a70db4`。
buildId：`164b5484-a1d6-4e43-8706-e9fdc09037ed`。

| 验收 | JIT | AOT |
|---|---|---|
| runId | [ecde9c9d-4e3c-4e2f-9a34-174ef12242c4](ecde9c9d-4e3c-4e2f-9a34-174ef12242c4/verification.json) | [a1d9fa96-1f96-4efe-874e-09a3a90d74e4](a1d9fa96-1f96-4efe-874e-09a3a90d74e4/verification.json) |
| libc 事务/生命周期检查 | 24/24 | 24/24 |
| 复用 Runtime 存储策略用例 | 24/24 | 24/24 |
| 四线程真实 libc 调用 | 371,260，无错误 | 311,165，无错误 |
| 精确函数体/改写指令字 | 10/10 | 10/10 |
| 两页统一发布/原文件恢复 | 2/2 | 2/2 |
| 第二页校验失败，两页不变 | 通过 | 通过 |
| 源页、拒绝后页、恢复页完整一致 | 8192 字节 | 8192 字节 |
| 活跃页仅十个指定 B 编码不同 | 通过 | 通过 |
| 全阶段 RX 与 BTI、原 inode/device/offset 恢复 | 通过 | 通过 |
| 临时页/已发布匿名目标页 | 0/0 | 0/0 |
| 显式进程期保留 relay | 1 个 RX|BTI 页 | 1 个 RX|BTI 页 |

每份证据保存主机原始 ELF 符号/Build ID/完整函数体核对报告、两个源页、四阶段 maps/smaps 和实际页字节，以及 APK/源码/Native/编译器/签名/关键区反汇编。主机源页聚合 SHA256 为 `31f1dfe5056c1db7a30a91b15edfc31d5b87e61fa0dd385379db33f90c360f90`；libc 整体 SHA 与固定平台清单相同。

同一构建还重跑 Native H1 92+16、H2a 45、H2b 15、H2c 19、ART 观察 15、Field.get 62、managed 25 项检查；DEX 主机 14 项、编译入口核验通过。JIT/AOT 的 Field.get 各一百万次全拦截；managed caller 精确选择反优化后各一万次全拦截。未变更的 H1 主机 92+38 回执通过源码摘要比对复用。

JIT result SHA：`460bf8ab38570c6570280eacb5d646c3b160799460b0ce361a0e35091336fe18`。
AOT result SHA：`3fbd2443270fcfe1732484a8c8c4f3a562a1e63a8a6e6dbe5bbd9771cea59868`。

[首轮证据收集权限问题](91d164e8-5c9e-42f3-a0f4-68c15dbdd432/README.md)单独保留，未当成完整通过。当前阶段没有 BTI 非法落点故障 Oracle、通用 PAC/MTE/ABI 或任意模块卸载结论；Runtime bootstrap 和其他产品适配尚未迁移，产品仍依赖 Frida。
