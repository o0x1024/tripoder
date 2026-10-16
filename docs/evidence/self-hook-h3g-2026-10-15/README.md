# H3g 原调用存储与在途帧证据

日期：2026-10-15。仅自有包 `dev.tripoder.selfhookprobe`，UID 10403、non-debuggable，固定 API 37 / ARM64 平台。产品进程和第三方应用未参与测试。

## 当前通过构建

- APK SHA：`20dbd17df6cf333b4435c0e8e576a48b5b96aca46b3f13b3b38c0eca953d1277`。
- buildId：`8eb6cfe1-1b90-42f9-86f9-65589a5d14cf`。
- sourceDigest：`ecdbac16b34134922fc741df2aef292572006749032283dce699a966ef69b5fd`。
- 核心源码与构建清单当前一致；主机 92 项 ASan/UBSan 检查、38 项 LLVM 对照、14 项 DEX Oracle 通过。
- 该构建安装后未清理编译缓存；完成 JIT 序列后仅对本包执行明确的 speed AOT 编译。

| 范围 | runId / 核验结果 | PID | 结果 |
|---|---|---|---|
| 入口 JIT | [078ee919-939b-4aeb-bda9-5d30a93861c0](078ee919-939b-4aeb-bda9-5d30a93861c0/verification.json) | 23840 | PASS |
| 映像加载后 Framework | [3f9f62e7-66d8-4c6f-9482-5d57d0e57834](3f9f62e7-66d8-4c6f-9482-5d57d0e57834/verification.json) | 24245 | PASS |
| 重复冷启动入口 JIT | [982746b5-ecfc-417d-bd39-931bcb0075e1](982746b5-ecfc-417d-bd39-931bcb0075e1/verification.json) | 24437 | PASS |
| 主探针 JIT | [5f38605c-3152-4471-af35-fe255734d52d](5f38605c-3152-4471-af35-fe255734d52d/verification.json) | 24688 | PASS |
| Application JIT | [bccf7139-6bb7-4c11-a64f-9553ba972d2d](bccf7139-6bb7-4c11-a64f-9553ba972d2d/verification.json) | 24957 | PASS |
| 入口 AOT | [54020fca-9b1c-4e50-9796-0ed31c58eb18](54020fca-9b1c-4e50-9796-0ed31c58eb18/verification.json) | 25195 | PASS |
| 主探针 AOT | [1d9436b9-bf3f-40cb-bace-eae29ad4a30f](1d9436b9-bf3f-40cb-bace-eae29ad4a30f/verification.json) | 25633 | PASS |
| Application AOT | [d30c3cd4-f5bc-4ffa-bf83-8b8c2fa08279](d30c3cd4-f5bc-4ffa-bf83-8b8c2fa08279/verification.json) | 26122 | PASS |
| 最终 Framework 冷启动 | [621c62b9-fdc8-4692-90da-21d1c619b546](621c62b9-fdc8-4692-90da-21d1c619b546/verification.json) | 26326 | PASS |

三组入口试验各 1,071 项检查；worker 调用分别 223,930 / 206,276 / 260,358 次，原函数次数、旧帧返回、在途停用、42 次切换均正确。核心两组包含固定 libc 的 24+24 项检查、两个共享页、367,144 / 358,919 次并发调用；非协作 Native 调用分别 51,377,037 / 64,422,771 次。Application 两组各一百万次实际编译读写对、四万次并发读取、11 次物理归属检查通过。

## 冷启动独立门槛

- [Hook 后的新进程实际加载 runtime image](cold-start-verification.json)。
- [保留 runtime image 后重复完整 Hook](cold-repeat-verification.json)。
- 两项均绑定同一 APK、不同 PID、运行时间顺序以及真实 `cache/oat_primary/arm64/base.art` 匿名对象映像和文件映射。
- 最终 speed AOT 冷启动通过 Framework 核验，但没有 runtime-image 映射。严格的映像核验器按预期拒绝，见 [拒绝记录](cold-aot-verification-outcome.json)；不把该运行标为映像验收通过。

8 次 GC 请求期间的根关系检查通过，但入口试验 `observedOwnerMoves=0`，移动 GC 更新仍未证明。完整 caller/loader 闭包、通用 ABI、同步和对象语义、模块卸载及 H4–H6 均未完成。

## 保留的历史失败与未执行项

| runId | 状态 | 原因 |
|---|---|---|
| [ed35efa6-672a-4067-995e-b848a85f133f](ed35efa6-672a-4067-995e-b848a85f133f/) | FAIL | 错误地把高 16 位可变热度作为方法身份，触发归属终止；已修正为低 16 位方法索引。 |
| [1c9e156b-7a63-4944-9632-fb2716a70e1f](1c9e156b-7a63-4944-9632-fb2716a70e1f/) | 仅当次观测 | 早期入口试验，没有完整 relay 及冷启动核验。 |
| [518e7c75-edb6-4c86-9ff5-99abfdb338ad](518e7c75-edb6-4c86-9ff5-99abfdb338ad/) | 仅进程内 PASS | 旧声明槽备份；下一进程崩溃，因此不计生命周期通过。 |
| [a35bdc53-1b9d-43a9-a3de-0654f5cd42b9](a35bdc53-1b9d-43a9-a3de-0654f5cd42b9/) | FAIL | ART AddImageSpace 启动崩溃；恢复目录 recovery-device 仅供诊断，不是正式设备验收。 |
| [8ee289e1-98f6-4d5a-9d7d-23c4ae04e78c](8ee289e1-98f6-4d5a-9d7d-23c4ae04e78c/) | FAIL | 独立分配准备阶段拒绝，未修改目标。 |
| [98006710-b31f-4d05-a384-a3993615d37d](98006710-b31f-4d05-a384-a3993615d37d/) | FAIL | Class.dexCache 的声明类型错误；按真实 Object 字段声明修正，未使用替代解析路径。 |
| [075ffe33-1255-4039-be83-41619c796171](075ffe33-1255-4039-be83-41619c796171/) | NOT_RUN | 旧构建冷启动失败后停止该矩阵；目录只有构建输入，不是通过证据。 |
| [2fb79430-95af-444b-849c-1c5aff2cb2e7](2fb79430-95af-444b-849c-1c5aff2cb2e7/) | NOT_RUN | 旧构建冷启动失败后停止该矩阵；目录只有构建输入，不是通过证据。 |
| [9d98f637-559c-4c59-8cbc-0886a34850f8](9d98f637-559c-4c59-8cbc-0886a34850f8/) | NOT_RUN | 旧构建冷启动失败后停止该矩阵；目录只有构建输入，不是通过证据。 |
| [bf70edb5-607d-4b22-90c8-5711a4f7a8f0](bf70edb5-607d-4b22-90c8-5711a4f7a8f0/) | NOT_RUN | 旧构建冷启动失败后停止该矩阵；目录只有构建输入，不是通过证据。 |
| [c3949236-0003-4ec1-96ec-ff17ec1354a4](c3949236-0003-4ec1-96ec-ff17ec1354a4/) | NOT_RUN | 旧构建冷启动失败后停止该矩阵；目录只有构建输入，不是通过证据。 |

实现和剩余边界见 [存储设计](../../self-hook-managed-storage.md)。历史构建的证据只支持其当时源码，不覆盖当前产品迁移。
