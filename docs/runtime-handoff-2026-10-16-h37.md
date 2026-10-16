# H37 当前交接：GC root 已验收，历史进程槽复用已验收

更新时间：2026-10-16（Asia/Shanghai）。工作目录 `/Users/like/code/tripoder`。用户本次要求整理交接，由其他人继续；当前没有构建、设备验收或子代理任务运行。

## 最终目标与完成边界

微信 `com.tencent.mm` 和王者荣耀 `com.tencent.tmgp.sgame` 在 Tripoder 内实际正常运行，正常 Java/Native Hook 安装、调用、停用、卸载不导致 panic、Java/Native crash 或 ANR。真实 UI、登录、后台/冷恢复和业务链必须最终验收；fixture、Application ready、Activity创建或15/30秒存活都不能替代业务完成。

**目标尚未完成。** 当前仍有微信启动/Context验收、王者 PendingIntent/Alarm、最新构建P4和正式产品控制验收待做。禁止宿主微信参与客体登录作为替代路径。

## 当前制品与现场

- 已构建、安装、归档的 Runtime APK：`2003244000e98e9e53398c8b3c6afcab796f6de9eea6953f52df0d833fc42a6d`。
- 归档：`docs/evidence/process-slot-ownership-h37/artifacts/20032440/`。473个源输入交接前逐项复核，`changedInputs=[]`。
- Native SO：`a7ba136ffb97feb1abf26b74f33c3facb402d813916069ba8d5af97bbca0b5e8`，与H36 GC压力包内SO完全相同；Java Runtime的H37生命周期变化仍要独立回归。
- 设备 `d861a4fa`，Xiaomi/madrid Android17/API37/arm64/4KiB，Runtime UID10409。交接快照仅主UI PID12652存活，无authority/worker（瞬时快照）。[只读现场及制品核验](evidence/process-slot-ownership-h37/handoff/provenance.json)。
- 正式 Engine/Workbench/control-test 构建已过期，不得称当前产品已验收。未commit/push；保留整个dirty/untracked工作区。

## 已完成与证据

| 范围 | 本次结论 | 证据 |
|---|---|---|
| GC root首次枚举 | 三个独立冷进程，每轮121项通过 | `framework-gc-h36/gc-02-interleaved`、`gc-03-interleaved`、`gc-04-interleaved` |
| 进程槽与Activity资源分离 | 真实跨安装冷/热迟到入口14项通过 | `process-slot-ownership-h37/reuse-04`，run `process-reuse-1792e65598e54702` |
| 实际SQL所有权判定 | 33项host检查通过 | `process-slot-ownership-h37/sql-policy.json` |
| 当前主机回归 | 304/15/85/117/331/8/45/12，SDK230 | `process-slot-ownership-h37/host-04.log` |

上述目录均在 `docs/evidence/`。历史H35仍有Job生命周期/冷恢复15、独立调度/退休69、原生重试对照2、完整P3 358、电源广播/Provider回归242、P4 105通过；它们绑定各自旧构建，不能追认为当前APK全量结果。详细历史见 [H35交接](jobscheduler-handoff-2026-10-16-h35.md)。

### GC root：已完成真实转发路径覆盖

`ArtMethod/ArtField.declaring_class_`是native GcRoot。原始32位值可能仍在from-space，而JNI Class root已经位于to-space；缺的是read barrier，不是单纯扩宽指针。`art_framework.c`的方法、字段都通过固定 `artReadBarrierForRootSlow`（RVA0xa2c4e4）读取真实slot，并独立校验READ_ROOT/MARK_ROOT；不依赖OriginalMethodStorage初始化、不跳过owner/ID验证。

独立oracle来自8个boot JAR、13个DEX，原始DEX parser与SDK dexdump逐类交叉核对，55类/6824个声明；设备逐JAR核SHA。探针在prepareNative前开启真实GC压力，每个ordinal首次读取交错1ms，不使用Java声明缓存。三轮分别924/757/721次GC，28/27/30次根地址修正，8.673/8.693/8.469秒；全部声明匹配，无Java/Native fatal。ApplicationStartInfo实际44个DEX声明+2个合法排除原生槽，不能把46个槽都称为声明。12个真正继承副本、34个clinit排除保持。

汇总：[gc-verification.json](evidence/framework-gc-h36/gc-verification.json)。包 `6369d433…`；probe入口 `--phase framework-gc`。原生确定性反例、ELF10函数体核验在 `framework-declaring-root-2026-10-16/`。无需无故重跑已通过的三轮。

### H37：进程执行权与组件资源分开

新 `ActivityProcessPins` 只在原会话确证DEAD且旧入口永久不可准入时解除执行占用。未关闭的LIVE/RECREATING/UNKNOWN、非DEAD和缺失死亡证据仍保留；component/alias/affinity与原operation/launch UNKNOWN不变。

- `ActivityLedger.closedEntry` 在精确ticket/stub核验后优先拒绝显式fence、generation永久close以及FINISHED/REVOKED/RETIRED；不先碰当前新guest的组件租约。
- `ProcessSupervisor.availableIndex`、`RuntimeBroker.reserve`按不可变space/package/install/process检查外来Activity/Service/Job占用；不能因为旧owner retainedStub相同就绕过另一个owner的新占用。旧owner物理槽映射保留，组件名和affinity不转给新安装。
- `capacity`中`activity`表示执行pin，`activityResources`表示仍保留的Activity资源。进程可用不等于组件/affinity已经退休。
- `ProcessSupervisor.stop/close`对历史DEAD会话查询持久状态，不从ExecutionContract内存表复活旧capability。
- 跨安装热入口暴露了`RuntimeEngine.claimCreation`过早执行本地activityInfos/Intent解析。现先复用`ACTIVITY_ENTRY_STATUS`检查关闭门槛，再解析，再正式claim复查；新协议/备用后端均未增加。

验收只永久关闭两个已死自有测试generation，未关闭微信/王者安装或清数据：

- D：`284d22cee3dc2f976ead73537480ca831e2e0f6ad45f62a85dda9d6d5658200d`，space `provider-identity-d52491857c3c4760-provider-identity`。
- G：`725954b1fe461aa7be13f3049daceb4de953301a48220550e0141cbf3b3c3281`，space `provider-identity-3ff792bb3ca04c34-provider-identity`。

两者现在均DEAD/closeRequested=1，ticket/UNKNOWN/组件/affinity快照保持。不同安装使用同一物理process slot后，旧cold/hot Intent均真实返回RESULT_CANCELED，新PID和身份保持，旧Application没有再启动；旧owner也不能抢占正在运行的新owner。reuse-04无Java/Native fatal，ANR=false。

失败证据保留：reuse-01为adb JSON引号传输失败（无关闭）；reuse-02误查历史内存Session（无关闭）；reuse-03已成功关闭D，随后旧热Intent引发新进程ACTIVITY_SESSION_MISMATCH（真实缺陷，已修）。不能把reuse-03 fatal列为预期崩溃。URL-safe Base64仅用于shell参数安全传输。

**不要原样重跑旧closure plan**：原始plan假定D/G都开放，resume-plan假定仅D已关闭；现两者均已关闭。需要重测时依据真实状态与reuse-04的snapshot生成精确计划，不选任意旧条目、不批量关闭其他安装。

## 最新停止点：Context完整回归仍失败

最新 `docs/evidence/process-slot-ownership-h37/provider-context`，run `provider-identity-104db46a92d54076`，当前APK20032440。

- 已越过之前的多进程容量崩溃，多进程Provider及引用回执检查通过。
- **整轮在第19项 `application-start-failure-is-explicit` 失败**，位置 `P2ProbeActivity.testPackageResources`；没有执行后面的26项PackageContext检查。无Java/Native fatal，ANR=false。
- 该断言用`rejected(...,"OWNED_APPLICATION_START_FAILURE")`，但辅助方法只保留匹配bool，原始异常未写报告。当前顺序先启动main、same-space peer、other-space main，再启动`:historyfail`；第四个并发进程容量不足是需要核实的首要假设，不能把它写成已确认根因。应先保存原始异常和逐槽原因，再决定重排不需要并发的失败历史测试，或拆出必要的Context定向验收；不能降低断言或将整轮改绿。
- 本次又留下一个未派发PackageResourcesActivity票据：`f22fd4de-81bd-426d-8ae2-fb6c5256021d`，generation `c17d52dd258fa80daed1a8cbd23a9ae11414861d5e0e3b7af4e647bf2d89f66e`，space `provider-identity-104db46a92d54076-provider-identity`。交接时DEAD、closeRequested=0、PREPARED、taskId=-1、无entryFence，仍占D执行权。必须按精确自有测试关闭协议处理，保留UNKNOWN/租约；不要直接改库。应将Activity准备推迟到依赖准备就绪，避免测试前置失败继续产生未派发票据。

## 后续实施顺序

1. 处理上述精确停止点及本次自有测试的未附着入口；完成同包Context边界验证。其实现已包含资源模式空DEX loader/applicationContext=null、INCLUDE_CODE当前loader/Application、独立主题，跨包/未实现flags拒绝；不声称Activity窗口token配置继承完整支持。
2. **复测原微信安装**，避免不断新建空间/占用affinity。space=`target-3be31e2dd7434859-wechat`。最近微信测试 `framework-gc-h36/wechat-context-01` 在Matrix绑定额外服务时PROCESS_CAPACITY_EXHAUSTED并自行exit(1)；H37通过后还没重新跑微信。逐一修复实际首个启动错误，不把死亡后的恢复错误当首次根因。
3. 接通王者真实广播PendingIntent/Alarm，见 [实施边界](runtime-pending-intents-plan.md)。固定王者targetSdk30，平台进程级Compatibility导致S+ mutability检查误入；只加flag/返回null/改宿主全局策略均不能完成能力。持久creator必须独立于session，NO_CREATE查真实系统，mutable fill-in不得改物理路由，真实Alarm和冷投递须验收。
4. 当前源P4（普通Java/Native/mixed安装、调用、停用、卸载和退出归因），必要生命周期回归；正式Engine/Workbench重建与控制验收。
5. 最终微信/王者UI、登录、后台/冷恢复及业务场景，确认正常Hook无panic/Java/Native crash/ANR。Job authority实际死亡、取消UNKNOWN收口、真正组件/affinity资源退休仍是独立未完成项。

```sh
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-wechat-dir> --phase target --target-package com.tencent.mm --target-space target-3be31e2dd7434859-wechat
rtk proxy python3 android/scripts/validate-runtime-probe.py --serial d861a4fa --evidence docs/evidence/process-slot-ownership-h37/<new-p4-dir> --phase p4
```

上述命令不是要求跳过停止点。修改源码/fixture后必须重建；当前包源一致可用于未改源诊断。一次仅一个设备安装/启动负责人；验收期间冻结构建输入。保留失败目录及全部业务数据；不使用root/run-as，不删UNKNOWN，不扩槽绕过。所有shell以rtk开头；CodeGraph MCP未暴露，CLI索引可用但有滞后。超过2000行按职责拆分。
