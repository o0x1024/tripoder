# 启动性能续实施证据：Provider 冷启动

2026-10-18，设备 d861a4fa / API 37。范围：安装时 authority 索引、锁外目标 APK 校验，以及 Native 模块物理路径身份修复。产品继续全局解释执行；不代表 P2–P4 性能方案完成。

| 证据 | 结果 | 边界 |
|---|---|---|
| [host-checks.json](host-checks.json) | 11 组主机行为检查、277 个 SDK 源文件编译通过 | 不是设备验证 |
| [provider-index-03](provider-index-03/device-observation.json) | 35 项通过 | 两轮真实 ContentResolver 路由；冷缓存只验证两个命中 APK；无关损坏安装不阻塞；含索引迁移/回滚/卸载检查 |
| [provider-native-p4-01](provider-native-p4-01/device-observation.json) | Java/Native Hook 105 项通过 | 此处 p4 是既有 Hook 验收阶段，不是启动性能方案 P4 DEX 落盘 |
| [provider-index-01](provider-index-01/device-result.json) | 12 项索引检查通过，启动失败 | 暴露物理/逻辑 canonical 路径混用；已修复并由后续焦点及 Hook 验收验证 |
| [provider-index-02](provider-index-02/device-result.json) | 完整 providers 套件失败 | 当前 AppOps 服务透传与旧 attach-only 断言不一致；断言未删除，策略未修改 |
| [provider-product-control-01](provider-product-control-01/device-observation.json) | 产品控制失败 | 早期构建 worker 启动失败 |
| [provider-product-control-02](provider-product-control-02/device-observation.json) | 产品控制失败 | schema 32、安装/升级/重绑检查通过后，系统包替换通知触发未实现的 notifyPackagesReplacedReceived，worker 崩溃 |
| [product-source-change-rejection.txt](product-source-change-rejection.txt) | 重跑在启动前被拒绝 | WorkbenchOperations.java 被并发修改，已安装产品包不再对应当前完整源码 |

[source-audit.json](source-audit.json) 以已归档的实际测试回执为基准，单独核对探针与产品源码。构建目录后来被并发重建，不能拿最新 build-receipt 替代已测试的回执。最终核对发现探针 fixture `storage_probe.c`、多处 Workbench/控制测试源码及重建 fixture APK 已变化；Runtime 实现输入仍匹配已测试回执。每个设备目录保留其独立构建回执；不能将旧包的成功结果套用到更新后的源码。一次中途产品构建也被 SOURCES_CHANGED_DURING_BUILD 拒绝，没有作为验收构建。

复现焦点验收：先按项目构建参数重建 runtime-probe，再执行：

```sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --phase provider-index --install --evidence NEW_DIRECTORY
```

测试在自有 fixture 空间中将一个无关安装标成 CORRUPT，并清空该验收 authority 的完整性缓存；不修改用户产品安装数据。两轮查询场景耗时 8304 / 8319 ms，包含两个远程进程冷启动，不是单次索引查询延迟。未测 DeepSeek/哔哩哔哩五次冷启动中位数，未达成性能方案的首帧门槛。

下一步：明确实现系统包替换通知回执，统一 AppOps 契约并恢复完整 Provider/产品回归，然后进入 P2 E1 禁内联实验；覆盖与性能证据齐备后才切换产品执行策略。


## 后续根因修复与 E1（本轮）

- [contracts-providers-07](contracts-providers-07/device-observation.json)：**478 项通过**，包含 AppOps attach-only、Provider 全流程和系统服务/元数据契约。
- [contracts-package-replaced-02](contracts-package-replaced-02/device-observation.json)：**6 项通过**，实际 worker 主线程执行真实 ActivityThread 包替换处理函数，并向物理 PM 回执空集合/宿主包集合，原 PID/generation 存活。OEM 安装器 `--dont-kill` 的失败保留在 contracts-package-replaced-01，未当作通过。
- [contracts-product-control-01](contracts-product-control-01/device-observation.json)：固定构建产品控制 **58 项通过**。之后最终整包重验先被并发 Agent 修改的摘要门禁拒绝，重建又因并发 UI 改造的 WorkbenchAddActivity/WorkbenchLibraryUi 接口未对齐而编译失败，见 [构建日志](contracts-product-build-blocked.txt)。不声称最新完整工作区产品已验收。
- [contracts-host-checks.json](contracts-host-checks.json)：主机行为及 SDK 编译记录。PreferencesWaitTest 的线程状态采样竞态改为同步真实读取尝试，失败也释放线程；本轮代码没有扩大启动总预算。
- [E1 两轮证据](e1-no-inline/README.md)：首轮 level=0、真实 JIT caller/control、101,002 次零绕过通过独立验证；相同 APK 冷重启在启用前读取到旧 CompileDontBother，ArtMethod 位于缓存 base.art，**E1 整体门槛仍失败**。产品解释策略未切换。

[本轮机器可读摘要](contracts-summary.json)。其余 contracts-providers-01 至 06 保留各阶段失败与校准证据，不用最后成功覆盖历史失败。

最终源审计见 [contracts-runtime-source-audit.json](contracts-runtime-source-audit.json)：RuntimeHookClaims.java 在归档回归后被并发修改，因此本轮成功结果只针对归档构建，不代表当前完整工作区全部改动已验收。E1 使用独立构建，两个结果都保留精确 sourceDigest 与 APK SHA。
