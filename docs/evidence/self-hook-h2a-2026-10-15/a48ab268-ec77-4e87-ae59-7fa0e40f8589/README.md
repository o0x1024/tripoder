# H2a 平台门槛未通过

运行身份：`a48ab268-ec77-4e87-ae59-7fa0e40f8589`。普通 non-debuggable / untrusted_app，API 37 / ARM64 / 4096 字节页。

| 项目 | 实际结果 |
|---|---|
| H1 共享核心 | 92/92 通过 |
| H1 匿名合成页 | 16/16 通过 |
| 当前核心对应主机检查 | 92/92，LLVM 对照 38/38 |
| 未发布 ELF 专属页 RX→RW | 成功，验证无 X |
| 同值 volatile store 后恢复 RX | 失败，errno=13 / EACCES |
| 对外状态 | TP_OWNED_EXECMOD_DENIED；整体 passed=false |
| 目标入口改写 / generation 发布 | 均未发生 |
| 失败清理 | 模块已卸载，最终 maps 无 fixture |
| 真实 Hook / 50 轮并发 / ART | 未执行，不得表述为通过 |

这不是安装权限问题。APK 已安装并冷启动；拒绝发生在普通应用自身的 mprotect。日志未保留对应 AVC，证据只能定位到 EACCES，不能进一步断言具体 SELinux 规则。

- [设备结果](device/result.json)、[进程 maps](device/maps.txt)。
- [明确拒绝的证据核验](rejection-verification.json)：`acceptancePassed=false`，源码、APK、Native/fixture SHA、平台和 maps 一致。
- [构建回执](build-receipt.json)、[主机结果](host-report.json)、[fixture ELF](fixture-elf.txt)。
- [设备 ART 符号清点](art-symbol-inventory.json)：仅 ELF 导出，不证明 namespace/ABI/锁或 ART Hook 可行。
- [前次精确崩溃诊断](../f5e6f43e-f5a7-4396-a9de-a2f959bf71a4/latest-crash-observation.txt)：`TP_PAGE_RESTORE_RX errno=13`；最终版本已前置检查并安全释放未发布模块。

APK SHA-256：`20ba242000dc6a1bcbe5c1562272488cffa8771bf5cf4882057e7cfe59f56126`。

源码摘要：`86638d2bf3cdf83cd21199e5487bb15dda12fc4306559f07d6ea4a65fa41c05d`。

下一步是重审代码页所有权和发布方式，先建立满足当前平台权限的可验证模型，再执行同一套真实 Hook 验收。没有启用 RWX、修改系统策略或改用其他 Hook 后端。
