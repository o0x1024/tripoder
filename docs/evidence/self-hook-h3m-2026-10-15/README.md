# H3m：AOT 在途帧反例与显式启动策略

2026-10-15。所有设备执行仅限自有`dev.tripoder.selfhookprobe`、普通non-debuggable进程。**产品仍使用Frida，完整替换未完成。**

## 结论

目标quick入口正确，不代表已经在栈上的AOT代码会重新经过入口。稳定夹具先进入AOT方法、停在JNI屏障，再由另一线程激活Hook。旧帧恢复返回10，新调用返回110，原函数桥只记录新调用一次。这里的独立验收通过表示反例已验证，不表示该调用覆盖通过。

同一APK在进入该帧前调用新API`ManagedHook.initializeProcess()`后，入口从实际AOT地址变为解释桥，屏障后调用和新调用都返回110，原函数桥恰好两次。这个启动边界有效；既有AOT帧迁移仍未实现。API不能自行证明客体此前没有执行，产品生命周期必须在factory、类初始化、构造及回调之前执行它。

原H3l失败`f80635f6…`不能被重分类为通过。增加诊断后的独立writer运行`be08fe23…`虽通过17597次调用，但诊断代码会改变调用者编译/内联；因此新增小型`InFlightCaller`与独立观察Activity，避免依赖该偶然通过。

## 当前构建与逐次结果

- APK：`ff16b5932a076369f182ca1bb0a1c2ef9b1be043b39f1a87f5542151f889e8d6`
- buildId：`df4e57f5-3809-4751-b614-3991561e95f7`
- sourceDigest：`6ad4038a2d1c9204c1f64eaaa3d3906eb383c618910aeea7af3fc64ea51fb224`
- 实物：[artifacts/ff16b593](artifacts/ff16b593/)，保存APK、Native库和构建回执。
- AOT编译和JIT前置模式分别记录在`aot-ff16b593.json`和`jit-setup-ff16b593.json`；未清除应用数据。每次方法状态另由实际quick地址和映射核验，编译命令成功不代替实际入口证据。

| runId | 模式 | 独立验收范围 |
| --- | --- | --- |
| [d6ec181b-140f-4176-b9aa-e402e38b3d6f](d6ec181b-140f-4176-b9aa-e402e38b3d6f/verification.json) | AOT，晚激活 | 旧帧返回10，新调用110；反例成立；产品/迁移验收均false |
| [dfb9febc-feec-4d8f-88b4-fc848952cd76](dfb9febc-feec-4d8f-88b4-fc848952cd76/verification.json) | AOT，提前初始化 | 进入帧前已是解释入口；两次均110，两个原调用；仅新进入帧通过 |
| [1d49a85e-68c0-405b-966d-ce93ef15cddf](1d49a85e-68c0-405b-966d-ce93ef15cddf/verification.json) | writer AOT | 132次真实writer事件；8869次调用无错误；整页/封存ELF/FDE/BTI核验 |
| [54611b9c-1240-481e-9763-d335653a7c6f](54611b9c-1240-481e-9763-d335653a7c6f/verification.json) | writer JIT | 132次真实writer事件；25730次调用无错误；相同独立页面核验 |
| [6ce6dd30-5e94-4538-b109-048db4fa1e4a](6ce6dd30-5e94-4538-b109-048db4fa1e4a/verification.json) | ABI AOT | 19种签名/2048项；4对象移动；400轮根观察；683826次并发调用无错误 |
| [bc160636-9448-49b2-98a9-ff0f07bf9afd](bc160636-9448-49b2-98a9-ff0f07bf9afd/verification.json) | ABI JIT | 19种签名/2048项；4对象移动；400轮根观察；364704次并发调用无错误 |
| [fd656e5d-d0e6-4ca8-96fb-19c215e51a2f](fd656e5d-d0e6-4ca8-96fb-19c215e51a2f/verification.json) | 脚本 AOT | 473项真实ManagedHook脚本检查 |
| [c78659b2-003f-4fef-af65-26fe0641e947](c78659b2-003f-4fef-af65-26fe0641e947/verification.json) | 脚本 JIT | 473项真实ManagedHook脚本检查 |
| [ff45a1dd-d93a-4674-8571-5f564287a7b5](ff45a1dd-d93a-4674-8571-5f564287a7b5/verification.json) | 组合coverage JIT | 动态loader/boot目标、24轮类观察、2个Class移动及32次GC；中央writer与自有entry发布共存 |

各行只能证明对应构建和场景，不能相加为完整产品验收。所有`fullCallerCoverage`、`completeEntryWriterClosure`和`productAccepted`继续保持false。

## 代码与主机验收

- `ManagedHook.initializeProcess`是一次性、早于Handle准备的显式策略变更；准备本身不认领策略。失败禁止后续准备；重复或晚于Handle准备调用明确拒绝。
- Native绑定复用相同ART、中央writer和解释租约；显式初始化后再enable不会增加claim次数。没有新Hook后端、偏移猜测或自动策略切换。
- `processPolicy()`明确输出`preexistingCompiledFramesCovered=false`与`fullCallerCoverage=false`。
- Runtime安装表新增首项`PROCESS_EXECUTION_POLICY`；主机门禁293项、loader11、组件85、边界117、manager策略215及typed45项，166个SDK源码类型检查通过，详见[runtime-host-report.json](runtime-host-report.json)。Engine尚未连接这张安装表。
- [验收器篡改检查](verifier-mutation-checks.json)14项通过：错误返回值/原调用次数，以及伪造提前初始化、既有帧迁移、完整覆盖或产品完成，均拒绝。

## 历史诊断

`0b3a17e3…`与`900a3d0a…`仅完成一次调用后即采样，实际仍是运行时入口，明确失败于`ACTUAL_AOT_PAIR_REQUIRED`。夹具改为有界预热并逐次检查真实映射；不是将预热次数当编译证据。`be382610…`首次复现旧AOT帧返回10；当前构建的成对实验和独立验收取代该单次诊断作为本批证据。

## 下一步与范围决策

用户已明确回复“我选第一种”：产品采用客体执行前初始化的边界，之后仍支持动态Hook。任意既有AOT帧迁移不再作为本次产品完成要求；上面的反例保留，不能改写为已支持。

下一阶段将该顺序接入Runtime真实启动、证明客体首次类初始化及factory/构造均在边界后，再迁移固定Framework成员、基础安装器和控制面。直接store/未来image、必要Framework调用路径、caller-sensitive原帧、加载器寿命和性能仍是独立门槛。任何必需项未通过都不能移除产品依赖并声明完成。
