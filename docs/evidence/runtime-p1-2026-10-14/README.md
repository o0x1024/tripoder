# P1 实施与验收结论

2026-10-14。**本轮 P1 的限定安装/空间/恢复门槛通过，P0 回归通过。** P2–P6 未完成，主工作台未切换到新 runtime，真实用户项目未迁移。架构与下一阶段边界见 [整体架构](../../tripoder-architecture.md)、[阶段计划](../../tripoder-roadmap.md)、[P1 设计与审查修正](../../runtime-p1-design.md)。

## 最终证据

| 检查 | 结果 | 证据 |
|---|---|---|
| P1 真实设备断言 | 179/179 | [device-result.json](attempt-06/device-result.json)；[独立收集校验](attempt-06/device-observation.json) |
| 迁移源边界 | 22/22，嵌套在 P1 报告中，不能再加到顶层数量冒充独立总数 | P1 的 migration-source-boundaries |
| P0 真实运行回归 | 57/57，同一 APK | [device-result.json](p0-regression/device-result.json)；[独立收集校验](p0-regression/device-observation.json) |
| 执行契约 JVM | 103/103 | [contract-result.json](host/contract-result.json) |
| 完整制品 JVM | 35/35 | [artifact-result.json](host/artifact-result.json) |
| TypeScript SDK | 35/35 | [sdk-tests.txt](host/sdk-tests.txt) |
| 旧工作台 Java 编译 / 合成文件复制 | 通过 / 12 项通过 | [compile](host/legacy-compile-result.json)、[copy](host/legacy-copy-result.json) |

P1 run ID：`p1-bd7f9cd9298c447c`；P0 run ID：`p0-3c0e826e4f394ff0`。

最终宿主 APK SHA-256：`fcefea680ccd7b1026cc02881067b881923426c1ba9904bea6fe8f2ee962c319`。两个最终报告的源码、客体、宿主摘要均与同一 [构建回执](attempt-06/build-receipt.json) 匹配；该回执包含新 official apksig 输入摘要、Gadget、SDK/NDK 与源码哈希。[最终摘要核验](final-verification.json) 记录工作区源码和主机报告绑定。

设备：`Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.24.0.XEOCNXM:user/release-keys`，API 37、arm64-v8a、4096 字节页。宿主 UID 10398，authority 与实际客体 PID 在报告内。共 21 次确认的真实 authority 进程死亡，均有 fsync 故障位置回执、Binder death、系统 disconnected 与重开后的不同 PID。

## 已验收行为

- 真正跨进程唯一写入者：同进程重复拒绝后，另一进程仍不能取得锁；已关闭 Store 不能重新打开。
- 公开控制 API 使用 FD 完成安装；非空空间不能删除，卸载后空空间可删除。
- 16 组 APK 集合场景：base、完整 feature/config split、ARM64 native；缺 split、签名/包名/版本不一致、循环依赖、非法 ABI/ELF、签名损坏和 ZIP 路径/链接/重复项拒绝。
- 新安装 7 个、升级 3 个、卸载 2 个真实终止断点：SQLite 提交前只保留旧状态且操作 UNKNOWN，提交后完整新状态与 SUCCEEDED 一致。UNKNOWN 请求不会重放；成功重复请求只查询既有结果。
- 存活客体阻止升级/卸载；authority 死亡使实际客体退出，死亡确认后允许新安装代次。
- 制品加载后不产生 oat，完整集合仍可验证；已提取 native 库被破坏后拒绝，重启后保持 CORRUPT，不修补成成功。
- 效果已经发生但 ACK 丢失：数据库重开保留 UNKNOWN，重复请求不会多写一次，依据文件摘要协调后终态再次跨进程重启保持。
- 两个项目、同包不同空间的迁移数据不合并；迁移 4 个终止断点、源变更、源项目身份不符、旧 runnable 假安装均被正确处理；升级使旧引用 STALE。
- P0 复验真实 Application、Activity、XML/asset、JNI、A→B→A、重建/重启、同空间解析、旧回调/代次拒绝及同 UID Native 可读宿主合成标记。系统没有安装 A，宿主 B 诱饵始终未启动。

## 审查修正与失败记录

失败证据保留，最终结论只使用 attempt-06 和 p0-regression。

| 记录 | 发现 | 处理 |
|---|---|---|
| platform-verifier-check / -02 | 安装错误码掩盖根因；封存目录跨父目录 rename 被拒绝 | 稳定 INSTALL_REJECTED；发布目录权限协议 |
| attempt-01 | Binder death 早于系统服务清理，立即重绑踩中 Android 服务竞态 | 同时等待系统 disconnected，失败连接明确释放 |
| attempt-02 / -03 | 文件式 DEX 装载让 ART 写 oat，恢复验证失败 | 内存 DEX 装载，不放宽完整性门槛 |
| attempt-04 | 168 项通过，之后继续审查补充针对性检查 | 不作为最终构建结论 |
| attempt-05 | 同进程重复锁申请失败后关闭 FD，导致实际系统锁释放 | 开锁文件之前登记进程内所有者；跨进程拒绝实测 |
| 源码审查 | 卸载失败状态顺序、迁移项目 ID、迁移后缓存数据路径、关闭后 Store 重开 | 逐项修正；最终设备断言覆盖拒绝与持久状态 |

## 结论的范围

使用独立编写的 Tripoder 代码和官方 SDK/apksig，未使用 VirtualApp 代码；新路径无旧运行器、系统同包应用或 ClassLoader fallback。ADB 只操作两个已授权自研包；未替换现有 Tripoder 工作台，未使用 root、权限授予、系统设置更改或远程注入。

这不是任意 APK、split 执行、完整四大组件、恶意 Native 强隔离、整机断电或商用场景验收。迁移只使用自有合成项目；旧工作台修改完成 Java 编译和文件逻辑测试，未安装更新旧工作台、未做其 UI 真机验收。完整迁移与可信控制端仍在 P5，四款目标应用在 P6。

下一阶段为 P2：多声明进程和 Activity/Service/Provider/Receiver 生命周期，以自有应用先建立真实闭环，再扩展系统语义。不能仅删除平台或组件门禁宣称通用支持。
