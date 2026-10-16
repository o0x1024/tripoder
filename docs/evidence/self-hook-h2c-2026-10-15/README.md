# H2c 非协作 ELF 调用验收

同一独立 APK SHA256：`42b07a01313bcd7b8dfd7a0df78a22c4cfd061dba450e80d2aab83e48743e30f`。
sourceDigest：`1a7d946a8b943819cf9e90e4b9e808a33ca75bd33e5b78a98d4b4306e0229c16`。
buildId：`60be8d14-7c85-48bc-8f8b-e1eeac006252`。

| 本次构建 | JIT | AOT |
|---|---|---|
| runId | [460fffde-d63d-4cac-a677-a6c84d666ac6](460fffde-d63d-4cac-a677-a6c84d666ac6/verification.json) | [77eddb27-02c1-4e85-ad18-c0b63127df4f](77eddb27-02c1-4e85-ad18-c0b63127df4f/verification.json) |
| H2c 检查 | 19/19 | 19/19 |
| 安装/停用轮数 | 50 | 50 |
| 原入口/替换入口调用 | 44,616,981 / 12,689,173 | 47,215,615 / 11,126,251 |
| 返回值/原调用次数/errno 错误 | 0 | 0 |
| 暂停拒绝、预期字节拒绝、旧回调在途停用 | 全部通过 | 全部通过 |
| 匿名页发布/文件页恢复 | 101 / 2 | 101 / 2 |
| 结束时临时页/匿名页 | 0 / 0 | 0 / 0 |

两组均通过同一源码/APK/Native/平台/maps 核验。设备另运行 H1 92+16、H2a 45、H2b 15、ART 15、Field.get 62、managed 25 项检查。Field.get 的实际编译调用各一百万次全拦截；managed caller 反优化前一万次全绕过，按 DEX 索引反优化后一万次全拦截。独立 DEX 主机 14 项检查及二进制关键区调用检查通过；未变更的 H1 主机 92+38 回执通过源码摘要比对复用。

JIT result SHA：`57e65a3685853695047faf3028f1188adab04a03714d2fe6324e953171742762`。
AOT result SHA：`e3e8a11603d090717a23e5534c13215a14898fa4729bd00e1b469e19def1973e`。

仅在自有独立 APK 中验证，没有修改工作台/引擎/客体或第三方系统，没有网络权限。`dedicatedOwnedPagesOnly=true`、`genericNativeHook=false`、`btiEnforcementTested=false`；产品去 Frida 仍未完成。
