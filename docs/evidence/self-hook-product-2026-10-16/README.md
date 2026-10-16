# 自研Hook产品接入验收（2026-10-16）

当前仍在持续实施，未完成最终收口。此索引只列完整通过的独立运行，不将部分检查数累计成通过。最新执行点见[交接](../../self-hook-handoff-2026-10-15.md)。

剩余任务、并行分工、真机串行依赖与最终门槛见[验收细分清单](../../self-hook-acceptance-plan-2026-10-16.md)。本索引经既有 JSON 与归档实物只读复核；历史通过不代表后续修改的当前源码已全部验收。

范围：固定Android17/API37/arm64固件、自有未改写APK、客体首条代码前初始化。动态Java/Native用户脚本复用同一核心；不声称通用第三方应用、任意既有AOT帧迁移或同UID内核隔离。

| 通过的独立运行 | runId | 检查数 | APK SHA256 |
|---|---|---:|---|
| [p0-single-observation](p0-single-observation/device-observation.json) | p0-b7f3c9c46b2345b8 | 60 | 56672c49824a297c927b081cb8f70c4ff0917d3f4f9918428d34519690fc6bbe |
| [p4-live-admission](p4-live-admission/device-observation.json) | p4-9dac402cbf73431f | 105 | 8dcb82ba1183956bc6caa23d95f7c0e19ede7be05e7b1047d4d5434660f521b2 |
| [packages-policy-codes](packages-policy-codes/device-observation.json) | packages-6bd9279812954cd3 | 159 | c2e094b743634307ff71803cd2512915fcf6647c02decfa159363411cacacf5a |
| [sources-component-reservation](sources-component-reservation/device-observation.json) | sources-e744de383d664768 | 17 | c0de39c033473b7b017349553a95e7b11f8a8b1915f6c46ecc505c4d106c8cb8 |

归档核验：P0、P4、sources 的 artifacts/<SHA前8位>/runtime-probe.apk 与各自 receipt 的完整 SHA256 一致。packages 的 observation/result/receipt 确认 159 项真实完整通过，但本快照未找到 artifacts/c2e094b7 的旧 APK；这是实物归档缺口，不改写旧运行结果。最终当前构建须重跑 packages 并归档，不通过重建伪造旧 SHA。

## 必要验收剩余项

| 工作项 | 当前证据状态 | 下一步 |
|---|---|---|
| Provider 身份与资源 | 独立旧轮仍失败，不能计完整通过 | H24 Runtime b1948a3f 已构建；重跑 nonce/hash/file 资源报告与 Receiver 关闭竞态 |
| creation/failure/service-startup/service-passive/activity-flags/entry/notification | 尚无本次自研产品完整通过回执；完整 P2 不包含这些独立入口 | 依次真实验证，通知临时权限须恢复 |
| P3 存储 | 核心 H14 已有独立证明，实际产品长流程未完成 | 当前源两空间/重启/升级/Java-JNI/FD/symlink/prefs/SQLite 与宿主 decoy |
| 最终当前源 P0/packages/P4/P1/完整 P2 | 前三项只有历史通过；P1、完整 P2 尚未本轮通过 | 同一稳定构建逐阶段，保留确切终态与 APK |
| 正式 control/hook-ui | 包装已切换，尚未当前正式包端到端通过 | 最终 Engine/Workbench/controlTest 重建、归档、安装后测试 |
| Agent normal/disconnect | 手机审批与断连 UNKNOWN 未收口 | 自有 MCP 真实配对、手机审批、断连恢复；无自动重放 |
| 性能与最终交付 | 有分构建样本，尚不能宣称最终性能通过 | 最终包启动 pid/2177/25项/耗时原样留存；源码/ZIP/SO/实际 maps 与文档一致 |

## 当前接入

- Engine只加载libtripoderselfhook.so；25项真实安装、2177固定绑定（1523 manager），完整就绪后才构造guest factory/Application。产品构建清单只允许该核心库，无Gadget、旧JS adapter、探针JNI或第二份IO策略。
- Storage为十个精确libc绝对尾跳，保留BTI、两页一次提交，实时PC核验拒绝覆盖在途旧调用。原近地址分配阻断已移除。
- 原RuntimeBroker/Store、安装、进程/组件租约、审批及UNKNOWN保持单一权威。Activity冷目标保留与Android启动解耦；Provider默认Application和启动deadline已接入；动态Receiver关闭准入当前回归中。

## 保留的失败与边界

失败目录和旧制品均保留，不修改失败回执。完整P2曾依次暴露Activity来源ANR、Provider旧断言/冷依赖计时、默认Application准入及动态Receiver注销竞态；修复后仍须拿到完整P2和正式控制长流程回执。

H20核心main两轮在四个连续Native IO调用者下返回IN_FLIGHT，未发布页面，无worker错误或致命崩溃；这两轮没有通过，不能冒充并发热安装成功。该额外热安装压力超出用户选择的固定Storage首条guest代码前安装范围；H14的24安装/恢复与24IO/408986调用只证明其确切旧构建。当前核心仍须独立核验选定范围与拒绝边界，当前产品路径和P3必须真实通过，不承诺运行中客体存储策略热切换。

正式工作台control/hook-ui、Agent手机审批/断连UNKNOWN以及最终当前源制品仍在收口；本索引不等同于整个Tripoder/P6业务完成。
