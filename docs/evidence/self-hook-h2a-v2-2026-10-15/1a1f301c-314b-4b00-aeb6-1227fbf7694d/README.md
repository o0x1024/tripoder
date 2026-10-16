# H2a v2：自有 ELF 协作域真机通过

日期：2026-10-15。runId：`1a1f301c-314b-4b00-aeb6-1227fbf7694d`。

## 结论和范围

本次普通 non-debuggable 进程内，自研 Hook 实际修改原 `dlsym` 入口地址的代码映射，完成安装、原调用、停用、并发、恢复文件映射、卸载和重载。没有请求 RWX、写脏原 ELF 文件页、改变设备保护策略或加载第三方 Hook 引擎。只有独立探针 `dev.tripoder.selfhookprobe` 被更新和重启。

结论限定于自有 fixture 的专属 4096 字节页、固定 `uint64_t(uint64_t)` ABI、所有调用经协作域登记。任意客体、非协作线程、共享代码页、硬件 BTI/PAC/MTE、ART、异常/信号、第三方卸载通知和产品去 Frida 均未验收。固定地址发布中的真实 syscall 失败/终止分支没有本轮故障注入；发布前拒绝和排空超时已测。一次有界运行不代表长期稳定性或性能结论。

## 身份

- 设备：d861a4fa，Xiaomi madrid，Android 17 / API 37，ARM64，页 4096；完整 fingerprint 与 libart/libc/linker SHA 见设备回执。
- UID 10403，PID 22486，non-debuggable；全新进程 COLD 启动。安装返回 Success，启动 TotalTime 94 ms。
- buildId：`df3c6438-dc50-4cab-991d-cf7032ed9858`。
- APK SHA-256：`a27eb303355ba860d6ba6ca2bd46cc5ba15d2c6ed92e155097c112fc31f3f940`。
- sourceDigest：`a154ec3db85ffd8c757e37f1bd54b8ca0fee78f4c157b47adc32b5ea70eacda7`。
- fixture 前后 SHA-256：`71e223f4177d345697ec30449140e6d5412b1ece25dd56afbb4ace4368d6a9fb`。
- result SHA-256：`11b02b8f16cabebc30951edd49b1ed43aca48d443c3db5864142adb40edee228`。

## 验收数据

| 项目 | 结果 |
|---|---|
| 设备核心 / 合成页 / 真实 Hook 检查 | 92/92、16/16、45/45 |
| 主机基线 | 92 项 ASan/UBSan 核心检查、38 项独立 LLVM 编码对照；沿用源码摘要完全一致的报告，并未虚称本轮重跑 |
| 真实调用 | `f(7)=8` → Hook 后 `108` → 停用后 `8`；原函数最多一次、重入拒绝、errno 保留 |
| 并发 | 4 线程，安装/停用 50 轮，360,209 次调用；189,649 次 Hook 状态，170,560 次停用状态，值/epoch/次数/errno 错误 0 |
| 发布前失败 | 注入拒绝 1 个候选页；入口可执行，epoch/变更次数不变，候选页释放 |
| 排空超时 | 阻塞实际回调，100 ms 返回 BUSY；状态不变，释放回调后可正常停用 |
| 页生命周期 | 105 个 RW 候选、104 个 RX 发布、3 次原文件恢复；临时视图峰值 3，最终临时/匿名执行页均 0 |
| 模块生命周期 | owner close 后用显式观察租约核验恢复页，再释放观察租约，实际卸载；重载 generation 2，旧 generation 1 拒绝 |

入口地址保持 `0x6ce9c88000`，跳板 `0x6ce9c89000`：

| 阶段 | 入口页 | 跳板页 | 中转页 |
|---|---|---|---|
| prepared | 文件 RX，inode 338449，offset 4096 | 匿名 RX | 原文件 RX |
| active | 匿名 RX，真实 B 入口 | 匿名 RX | 原文件 RX |
| disabled | 匿名 RX，原指令 | 匿名 RX | 原文件 RX |
| restored | 原文件 RX，inode 338449，offset 4096 | 原文件 RX，inode 338449，offset 8192 | 原文件 RX |

恢复的 device 均为 `fe:45`。四个快照的 SHA 写入本次 result；核验器逐页比较权限、地址、路径、inode、device 和 offset。最终 maps 不含 fixture，也没有 Frida/Gadget/Gum 映射。完整原页面字节由设备独立断言检查，原包内 fixture 文件 SHA 在执行前后保持一致。

## 证据文件

- [设备回执](device/result.json)、[最终 maps](device/maps.txt)、[prepared](device/maps-prepared.txt)、[active](device/maps-active.txt)、[disabled](device/maps-disabled.txt)、[restored](device/maps-restored.txt)。
- [严格验收核验](verification.json)、[源码与静态检查](source-review.json)、[构建回执](build-receipt.json)、[主机基线](host-report.json)。
- [签名](signature.txt)、[Native 依赖](native-dynamic.txt)、[Native notes](native-notes.txt)、[fixture ELF](fixture-elf.txt)。

核验命令已成功执行：

```sh
rtk proxy python3 android/scripts/verify-self-hook-probe.py \
  --evidence docs/evidence/self-hook-h2a-v2-2026-10-15/1a1f301c-314b-4b00-aeb6-1227fbf7694d \
  --run-id 1a1f301c-314b-4b00-aeb6-1227fbf7694d
```

核验器排他创建 verification.json；重复核验请使用新的临时证据副本，不覆盖本次回执。后续源码变化或本地 build APK 被新构建替换后，这份证据成为历史构建证明，不能继续当作“当前源码”的通过结果。证据目录不归档 APK 二进制，构建回执指向本地 build 输出。

[当前设计](../../../self-hook-page-publication.md)；[任务](../../../../task.md)。下一步优先验证 H3a 固定 libart 的进程内访问、方法身份、线程状态和原调用语义，H2 其余边界继续保持未完成。
