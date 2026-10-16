# Tripoder P0 审查、实施与验收结论

验收完成于 **2026-10-14（Asia/Shanghai）**；目录沿用任务起始日期。结论：**本轮限定 P0 通过**，整体产品目标仍按 P1–P6 推进。没有使用 VirtualApp 实现，没有在失败后切换宿主同名应用、旧运行器或 VM。

## 交付与方案修正

整体职责及复用边界见 [架构](../../tripoder-architecture.md)，每阶段的交付、重构和验收门槛见 [路线](../../tripoder-roadmap.md)，状态机及失败语义见 [执行契约](../../execution-contract.md)。这些设计文档先落地，再实现共享 runtime 和独立薄验收入口。

| 原方案缺口 / 实施审查发现 | 本轮处理 | 证据级别 |
|---|---|---|
| Hook 模块清单不能定义应用运行身份与生命周期 | Space/安装代次/进程代次/Broker/操作/回调能力分责；项目与安装分离 | 文档、契约代码、设备运行 |
| 不同进程/虚拟 UID 易被误认为安全隔离 | 明确同 UID 边界；主产品凭据跨 UID 是 P5 前置决策；实测 Native canary 可读 | 真机；强隔离结论为 false |
| 文件发布与 SQLite 被视作一个事务 | staging→publish→commit；只接受已提交且 base 摘要匹配的安装；完整崩溃恢复列为 P1 门槛 | 当前顺序已实现；全面故障注入未验收 |
| 超时可能重放已有副作用 | 操作身份/摘要去重；UNKNOWN 必须查询或有证据协调；持久化失败关闭准入 | 主机 103 检查；设备 SQLite/单次 marker |
| setResult 被误当成结果送达 | 授权与收件分离，A 的 onActivityResult 返回后才记成功 | 真机事件序列 |
| B 死亡与 A 收件 ACK 竞态 | UNKNOWN 接受已认证来源的成功/取消收件证据；不再次执行操作 | 真机两个相反场景 |
| Context 继承方法可能漏到宿主 | 补齐文件 API 的空间归属；未知服务、其他包、Provider 写入、Context 启动重载明确拒绝 | 真机能力门禁检查 |
| 窗口/OEM 依赖未纳入能力表 | 虚拟配置 Context；显式只读 Settings/UI 服务；保留平台家长控制；完整设备指纹门禁 | 真机窗口恢复、重建与返回 |
| Java/Native Hook 错误后可能执行原函数或返回原结果 | SDK 与旧代理改为抛错/终止进程；原函数最多一次；修正自检与输入配置失败语义 | SDK 主机测试、类型/Java 编译；旧代理新失败路径未做真机验收 |

## 最终制品与真机结果

本轮最终证据为 **attempt-10**，不可用此前构建的结果替代。

| 项目 | 实际观测 |
|---|---|
| 设备报告 | [device-result.json](attempt-10/device-result.json)：`passed=true`，**57/57** 断言 |
| 独立采集器 | [device-observation.json](attempt-10/device-observation.json)：源码/制品绑定、诱饵未启动、`passed=true` |
| 构建及签名 | [build-receipt.json](attempt-10/build-receipt.json)：31 个输入文件摘要，A/B 与宿主签名校验结果 |
| run ID | `p0-78381148173946a6` |
| 宿主 SHA-256 | `dcb05e5d5eef26e37c8b9207ef80ba9545672effdf504e5a82c209c0a8e7339a` |
| 自有 A SHA-256 | `f0afd0f783100043ff76010bec40486e469d6c45cc8e1a7b3eae14524a1abe55` |
| 自有 B SHA-256 | `a59929974515639ae9d707b6b6af7cc7e898e8ea96bb2df8874fdcf6306f9913` |
| 平台 | Xiaomi Madrid / Android 17 / API 37 / ARM64 / 4096 字节页；宿主 targetSdk 36，非 debuggable |
| 固件 | `Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.24.0.XEOCNXM:user/release-keys` |
| 真实进程 | 首轮宿主 PID 22621、A PID 24967、B PID 25016；共同 UID 10398 |

通过的真实路径：

1. 签名后未修改的 A/B APK 各自装载 Application、Activity、XML、assets、JNI；A 未安装在 Android 系统，B 系统诱饵始终未被启动。
2. Android 标准 `startActivityForResult` 完成 A→B→A，返回当轮随机 challenge。A 的真实 Activity 重建后仍有正确资源、包名、Application 和 ClassLoader。
3. 两空间的同包数据路径不同、首次启动计数均为 1；返回原空间并重启后计数为 2、数据路径保留、进程代次变化。并非同空间中重新包装同一个 Activity 对象。
4. 重复 callback 与旧进程代次被真实 Binder 入口拒绝；删除空间中的 B 后，即使 Android 系统仍有 B，也只返回 `PACKAGE_NOT_IN_SPACE`。
5. 文件 Context/API 空间归属、配置 Context 身份、未知服务、其他包查询、其他 Provider、Settings 写入、OBB 以及文件名路径穿越检查全部通过。
6. 副作用追加一次 `effect\n` 后故意丢 ACK，重建 authority 得到 UNKNOWN；同请求再次准入返回 created=false，文件未追加第二次；用文件摘要协调后终态与证据可查询。

最后一项使用同一个 Android RuntimeStore 重建 authority；它证明真实 SQLite 操作状态与去重流程，**不等于已证明控制进程死亡、SQLite 重新打开或断电恢复**。

## 收件与死亡的证据链

这些是实际 Binder/系统 Activity 路径，不是本地模拟回调：

- 正常：操作 `b0adc79a-0513-49e7-8f6f-e9979cd385f1`，`return-authorized/RUNNING → source-received/SUCCEEDED`。
- B 仅 setResult、不 finish 就退出：`816aeb37-9bdc-4685-a43f-cc5c1362f7dc`，`RUNNING → delivery-unknown/UNKNOWN → source-received/FAILED`；没有提前成功。
- B finish 交付后退出，A 延迟处理返回：`d1e98f41-9171-4efc-8167-ef1d5746defc`，`RUNNING → UNKNOWN → SUCCEEDED`；协调证据包括 A 的进程代次、operation ID、PID 25240 和 RESULT_OK。

## 主机及构建验证

| 验证 | 结果 | 原始记录 |
|---|---|---|
| 执行契约 | **103/103**：身份、租约、去重、持久化故障、未知状态协调、回调、并发准入 | [contract-tests.txt](host/contract-tests.txt) |
| SDK 与草稿 | **35/35**：before/replace/after 异常、原函数次数、类型和准备失败等 | [sdk-tests.txt](host/sdk-tests.txt) |
| 新/旧 TypeScript 代理 | 类型检查通过 | [verification.json](host/verification.json) |
| 旧工作台全部 Java | API 37 / Java release 17 编译通过 | [legacy-java-compile.txt](host/legacy-java-compile.txt) |
| 新 runtime / fixtures / JNI | 构建、签名校验通过，最终制品真机通过 | [build-receipt.json](attempt-10/build-receipt.json) |

主机证据绑定测试及被测代码 SHA。新 Hook 失败语义尚未在旧工作台实机执行；不能将 P0 路由适配器的设备成功等同于旧用户 Hook 全部通过。未提交 Git commit，保留任务开始时已有的 VM/隔离研究与用户修改。

## 未覆盖项及下一阶段门槛

- 没有通用第三方 APK、Service/Provider/Receiver、完整任务栈、任意进程声明、split/升级/权限/URI grant/PendingIntent 验收。
- 没有任意 Native 路径/FD/mmap 拦截。JNI 仅按 Java 给出的路径写读。Native 读取宿主合成标记为 true，`strongIsolationVerified=false`。
- 只读 Settings 和显式 UI/OEM 服务由宿主提供；未虚拟化为独立空间状态。
- 只有单 base APK 摘要门禁和只读 Native 提取；完整制品集合、提取库独立完整性与 TOCTOU 防护、孤儿清理、安装升级故障注入属于 P1。
- 租约/多控制者在契约主机检查中覆盖，尚未接入旧工作台；多 Activity 并发、完整 Binder 载荷类型面、日志配额、Broker 存活替换和多进程发布仍需后续验收。
- 未做控制进程死亡后的数据库重开/存活客体撤销、断电恢复、长时压力或性能基准。57 个断言是受控测试覆盖，不是生产可靠性统计。
- 未迁移主产品或接触用户凭据/应用数据；微信、钉钉、王者荣耀、原神的业务场景仍未通过。

**建议下一步 P1**：先让安装/运行元数据成为唯一权威，并做控制进程崩溃恢复与安装发布各中断点的真机故障注入；再做 P2 通用组件。主产品集成前完成可信控制面与客体运行器跨 UID 的安全设计，不能把本轮同 UID P0 直接升级为强隔离产品。

## 失败历史与复现

保留 attempt-04 到 attempt-08 的原始失败结果：依次暴露窗口包特征查询、OEM Settings、wrapped resolver 的 hidden acquireProvider、OEM 家长控制服务和 UiMode 依赖。均针对具体必需能力修正，不采用捕获异常后自动透传未知接口。attempt-09 的 44 项成功是前一构建的历史结果；attempt-10 增加能力拒绝及死亡后成功协调后成为最终验收。

早期未持久化到本目录的探索不计入最终通过证据。复现步骤及独立研发来源记录见 [runtime README](../../../android/runtime/README.md)。
