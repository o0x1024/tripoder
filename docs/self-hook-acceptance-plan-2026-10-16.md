# 自研 Hook：剩余任务与完成门槛

2026-10-16；本清单是持续实施的验收队列，不是完成声明。实时运行点由[交接文档](self-hook-handoff-2026-10-15.md)记录；完整回执由[产品证据索引](evidence/self-hook-product-2026-10-16/README.md)记录。以下快照已逐份读取现有 device-observation、device-result 与 build-receipt；不把失败运行中已执行的检查数相加。

## 范围与当前结论

产品 Engine 已接单一自研 Java/Native 核心，25 项安装、2177 个固定绑定（含 1523 manager）先于 guest factory、类初始化和构造执行；运行中动态 Java/Native Hook 继续使用原控制权威、审批及 UNKNOWN 契约。存储近地址分配阻断已被十个固定 libc 绝对尾跳替代，当前仍需完成全部真实组件与正式控制长流程。

验收限定自有未改写 APK、设备 d861a4fa、固定 Android 17/API 37/arm64 固件、4096 字节页。它不自动授予第三方应用测试范围，不宣称任意已有 AOT 在途帧迁移、通用 APK、P6 业务或同 UID 内核隔离已完成。不得加兼容后端、补造 ACK、自动重放 UNKNOWN 或清理历史数据来换取通过。

## 已有完整运行与制品缺口

| 范围 | 完整通过的独立运行 | 确切证明 | 对最终当前源的要求 |
|---|---|---|---|
| P0 | p0-b7f3c9c46b2345b8；60 项 | A/B Application、Activity、资源/JNI、空间数据、进程死亡、回调及 UNKNOWN | 启动/生命周期已有后续修改，最终构建重跑 |
| packages | packages-6bd9279812954cd3；159 项 | 反射、包管理、元数据、缺失 Provider、manager 类型/权限/真实值 | 该回执完整通过，但旧 APK 未见归档；最终构建重跑并归档 |
| P4 | p4-9dac402cbf73431f；105 项 | Java/Native/mixed 动态脚本、DRAINING、参数快照、真实卸载及系统退出 | 核心/启动已有后续修改，最终构建重跑 |
| sources | sources-e744de383d664768；17 项 | 来源/目标重建、alias、真实远进程结果；来源不等待冷目标安装 | 完整 P2 再覆盖，不再单独重复无关场景 |

P0、P4、sources 的已归档 APK SHA 已独立核对匹配。packages 的 result/observation/receipt 相互匹配，不能因此虚构已归档实物；也不能否认该旧 run 已真实通过。它对应的 SHA 为 c2e094b743634307ff71803cd2512915fcf6647c02decfa159363411cacacf5a。

P2-full-first 的 136 项、P2-component-reservation 的 218 项、providers-default-application 的 206 项以及其它 providers 子轮均是整轮失败；保留为定位证据，不计完整通过。H20 核心 main 两次 IN_FLIGHT 同样未通过。

## 并行分工与互斥边界

| 工作流 | 可同时进行的任务 | 互斥与交付 |
|---|---|---|
| 主代理：真机集成 | 启动已构建制品、单阶段设备验收、失败根因确认 | 唯一设备负责人；同一设备禁止两个安装/force-stop/启动流程并行 |
| 子代理：源码专项 | 在分配文件内修复具体根因，运行独立主机合同 | 每批先约定独占文件；设备运行及立即来源核验期间不改该制品输入 |
| 子代理：控制链准备 | 只读审查 control/hook-ui/Agent 契约、准备精确验收命令及错误定位 | 不启动第二条 ADB 流程、不在后台重建正在验收的包 |
| 子代理：证据收口 | 读取已完成 JSON、归档哈希检查、整理矩阵和文档 | 不修改源码/build；旧失败回执保持原样；不把观察日志当成功回执 |

主机编译、合同验证与证据审计可以在独立输入/输出下并行。整个 Runtime 或正式产品构建会改共享 build 目录，必须由一个负责人串行调度。源码或 fixture 摘要变化后要重建对应制品；docs 的更新不触发无关设备重跑。

## 设备串行队列

下面各项均是当前目标的必要验收；子阶段用于快速定位，最终完整 P2 仍须取得同一次完整回执。每次使用新 evidence 目录与新 runId，不覆盖旧目录。

| 顺序 | 任务与 validator phase | 当前状态 | 成功门槛 / 依赖 |
|---|---|---|---|
| A1 | provider-identity | 旧轮失败；H24 新包 b1948a3f 已构建，待运行 | 真实 multiprocess 双 PID/实例、引用确认；PackageResources 完整有效 nonce/hash/file receipt；Receiver 注销与 claim 竞态不致命 |
| A2 | creation | 未在本次自研产品证据下通过 | guest Instrumentation 的 direct/super/super-replace；返回对象、构造前身份与精确结果 |
| A3 | failure | 未在本次自研产品证据下通过 | 原 Throwable/handler、Field 访问和 Application 所有权；仅允许精确绑定的预期 fixture uncaught |
| A4 | service-startup | 未在本次自研产品证据下通过 | Application attach 内真实 Service 请求、真实独立进程、结果与绑定语义 |
| A5 | service-passive | 未在本次自研产品证据下通过 | 非自动创建绑定不启动目标；取消保留可退役；后续真实启动才交付 |
| A6 | activity-flags | 未在本次自研产品证据下通过 | SINGLE_TOP 等真实实例选择、Activity/广播/粘性回调和销毁收据 |
| A7 | entry | 未在本次自研产品证据下通过 | 未附着入口关闭、旧 Intent 与 authority 重启后仍拒绝；不误回收物理租约 |
| A8 | notification + --allow-notification-test-grant | 未在本次自研产品证据下通过 | 自有真实系统通知记录取消/边界；临时权限前后状态与恢复均确认 |
| A9 | p3 | 未在本次自研产品证据下通过 | 两空间、重启/升级保留、Java/JNI 同字节、FD/路径/symlink/只读制品、prefs/SQLite WAL/并发及宿主 decoy 不变 |
| A10 | p0 | 有历史完整通过，待最终源回归 | 最新核心、Application/启动、回调死亡及 UNKNOWN 路径整体通过 |
| A11 | packages | 有历史完整通过，待最终源归档 | 同 A10 当前构建完整回执，补齐当前 APK 实物 |
| A12 | p4 | 有历史完整通过，待最终源回归 | Java/Native 控制与真实退出，实际 pid/uid/process/time/reason/status 精确匹配 |
| A13 | p1 | 未在本次自研产品证据下通过 | 单写者/FD 拒绝、实际安装提交/故障恢复/死亡、UNKNOWN 不重放、迁移与完整性 |
| A14 | 完整 p2 | 尚未完整通过 | 容量、任务/模式/结果/来源/别名/冷恢复、Service、Provider、Receiver/退休同一 run 完整通过 |
| B1 | 正式产品重建/归档 | 旧包不代表最终源 | Runtime/fixture 源稳定后构建 Engine/Workbench/controlTest，绑定 SHA；验收期间冻结输入 |
| B2 | workbench control --install | 未在当前自研正式包下通过 | 分 UID、项目/安装代次/进程所有权、租约、真实 authority 死亡与恢复，三份终态结果完整 |
| B3 | workbench hook-ui | 未在当前自研正式包下通过 | 用户实际界面准备/批准/启停、字段与状态一致；保留截图和回执 |
| B4 | Agent normal | 未在当前自研正式包下通过 | 自有 MCP 配对、手机实际审批、观测/准备/启停及真实调用，不含私钥/token 证据泄露 |
| B5 | Agent disconnect | 未在当前自研正式包下通过 | 真实断连、查询 UNKNOWN/恢复语义、不得自动重放侧效应 |
| C1 | 最终独立收口 | 等 A/B | 下列完成标准全部成立，文档与实际制品相符 |

执行模板：

```sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/NEW-DIRECTORY --phase PHASE
rtk proxy python3 android/scripts/validate-workbench-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/NEW-DIRECTORY --phase control --install
rtk proxy python3 android/scripts/validate-agent-control.py --serial d861a4fa --evidence docs/evidence/self-hook-product-2026-10-16/NEW-DIRECTORY --phase normal
```

Runtime 首次使用新包时加 --install；notification 显式加自有权限测试参数。完整 P2 整批 collector 为 1200 秒，不改变产品单次启动原 30 秒上限或业务操作时限。其它 phase 不因慢而随意扩预算。

完整 P2 已包含 providers、provider-identity、receiver、sources，修复后可先独立定位，后由完整 P2 证明组合成立。完整 P2 **不包含** creation/failure/service-startup/service-passive/activity-flags/entry/notification；因此不能只跑完整 P2 就跳过 A2–A8。library-ui/app-actions-ui 仅当相应用户界面变更或现有必需断言缺口触发再跑，避免无关重复。artifact phase 使用第三方微信/游戏 APK；target 与 Agent sentinel 同样超出本轮自有 fixture 范围，不加入此队列。

这里“完整 P2”指该 validator 的自有 fixture 整轮，不等于整个 Tripoder 的通用 P2 完成。现有 fullP2Accepted、fullP5Accepted、businessScenariosVerified、sentinelClientVerified 等更大范围声明保持其真实值；不得为了自研替换收口把它们机械改成 true。Agent normal/disconnect 证明自有 MCP 客户端与手机链，不证明 Sentinel 客户端或模型调用。

## 性能证据与拒绝边界

H20 精确核心构建 6ba7f7cf 的 manager 1523/15 与 4096 容量独立通过；后者 57.038 秒包括 GC、调用、写者及报告，**不是产品启动耗时**。H15 六个 Runtime 进程的 11.326–14.837 秒与 H21/H23 的启动采样只能分别绑定各自旧构建，不能混成最终当前包的 p95 或跨热状态提速结论。

剩余性能收口需要从最终当前构建的原始 bootstrap 日志保存每个实际采到的 pid、bindings=2177、25/25 完成和 durationNs；报告样本数/范围与最大值。串联冷 Provider 依赖必须在同一权威原 30 秒截止点内真实完成；来源主线程不得等待远端 Hook 导致 ANR。已有日志采样未证明全部进程覆盖，不补写未采到的样本。若未设数字化稳态阈值，记录真实调用吞吐/延迟与工作负载，不宣称无退化或自行创造性能达标线。

H14 原构建完成 24 安装/恢复 + 24 IO、408986 并发调用；H20 main 的两轮 IN_FLIGHT 没有修改页面，也没有通过并发热安装。该额外压力是在四个连续 Native IO 调用者运行中激活固定 Storage，超出用户选择的首条 guest 代码前安装范围；不得宣称热安装通过，也不得把它混成普通冷安装失败。当前核心仍须独立核验其实际选定范围和拒绝边界，产品 P3 必须完成。若修改固定 libc 发布代码，还必须补新核心安装/恢复证明，不能拿 H14 的旧 APK 追认。

## 最终完成标准

1. A1–A14 与 B1–B5 的必要阶段有完整终态成功，不能靠不同失败运行拼接；重跑以实际受影响的代码为依据，不重复无关昂贵测试。
2. 每份回执绑定 runId、当前 APK/fixture SHA、设备/UID/PID/代次、来源摘要；当前源码核验在执行前和结果收口时成立。保留失败目录和真实 UNKNOWN。
3. 最终 APK、Native SO 和 receipt 归档，归档 SHA 与 ZIP 内 SO 一致；Engine/Runtime 仅一份自研核心、一份 IO 策略，fridaInputs 为空；DEX/脚本输入、DT_NEEDED/导出与实际进程加载映射都不残留产品 Gadget/Frida 后端或 probe JNI。
4. 无未解释的 Java/Native fatal、ANR 或缺失终态。刻意 fail-stop 只按该轮精确 PID/UID/进程/时间的系统退出证据接受，不伪造成功业务结果。
5. 手机临时通知权限恢复已确认；未使用 root、清库、删除历史数据、第三方 APK 或外部 Sentinel 配置变更作为通过条件。
6. 记录最终启动性能及实际能力边界；更新 task.md、交接、README/架构与产品证据索引，避免历史“未切换/正在运行”文字被误当当前状态。

下一步：先以 H24 的新制品完成 A1，再由唯一设备队列推进 A2–A14；子代理同步做主机专项修复、控制链准备和证据核验。任何单阶段通过只关闭该行，不关闭整个替换目标。
