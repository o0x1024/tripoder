# 自研Native导出函数入口

更新于2026-10-16。完整产品迁移仍未完成，Engine和产品构建仍使用Gadget。这里记录已实施的核心与明确的准入范围。

## 调用与生命周期

`NativeHook`的每次prepare都创建不可变activation：C scalar入站槽、Java Context全局根、原调用代码和FDE都保留到进程退出。新版本捕获新Context，旧relay不读取可变`currentDispatch`。

调用链为：客体导出入口 → near ELF relay → 1024个静态Native入站槽之一 → 每调用module lease → JNI/Java Handler → 原调用token → 出站scalar ABI → 原函数主体 → 返回值及errno。

一次publication lease覆盖prepare到入口恢复；独立invocation lease覆盖Java计数之前和编码之后。停用只说明入口恢复，不表示旧回调已经消失。卸载与任何仍需目标映像的lease冲突时，notifier直接exit_group198；不持有客体dlopen句柄来隐藏卸载。

原调用ID是单调、不复用的调用编号，不是地址。TLS栈约束同线程、同一活跃调用、最多一次；错误参数尝试也消耗原调用权。Java层进一步约束Invocation的线程、关闭状态和单次调用。

## 代码页与入口

同一module generation/代码页只有一个page owner。每次批量变更合并所有目标word，在一次park中先检查全部preimage，再提交全部页面；全部线程恢复后验证并完成。任何提交后的不确定性立即终止，不重放。

最后一个word恢复为原始代码时，恢复真实文件映射。底层`tp_code_page_finish`会消耗original_view并清零其claim；稳定page owner在publication lease仍有效时重新认领准确的文件页，使同页尚未发布的prepared句柄和未来activation仍可用。`prepare A/B → publish A → retire A → publish B`已有定向设备验收。

准入迁移一条已证明的指令，并为其生成准确CFI：负向、16字节对齐的SP保存前导（STP X或STR D）、SUB SP，以及原relocator的已批准非栈指令。未知指令拒绝。原调用模板为16字节：BTI、可选PACIASP、搬迁指令、直接B回主体；完整ELF及FDE在加载前写完、封存，加载后代码不再修改。

## Legacy A-key/SP认证

首条PACIASP原位保留，在下一条处发布B。relay先AUTIASP恢复原始LR，再进入入站桥；original模板为BTI/PACIASP/栈前导/B回原函数。relay和original每个PC的RA签名状态均有对应FDE，避免重复签名和错误认证。

这里依据指令流，不推断CPU是否缺少PAuth_LR。Arm DDI0487 M.c §D8.10.4规则R_PLWLR规定，除另有说明外，每条指令后PACM清零。正常分支进入legacy PACIASP，或前置BTI，都建立SP-only语义；relay的BTI和original的BTI同样清除前缀状态。[Arm PSTATE.PACM](https://support.arm.com/documentation/ddi0487/mc/-Part-D-The-AArch64-System-Level-Architecture/-Chapter-D8-The-AArch64-Virtual-Memory-System-Architecture/-D8-10-Pointer-authentication/-D8-10-4-PSTATE-PACM)

这不批准`PACM; PACIASP`、PACIASPPC、B-key或任意中间入口。现有prefix decoder拒绝未知前导。旧`pac_a_sp_plan`的整机profile限制是另一个离线实验接口，不作为这里从正常入口指令流建立语义的替代证明。

## 已有验收

- 原产品`runtime-fixtures/native/hook_target.c`保持原flags独立构建，四个真实STP/STR导出共享同一页。Native32项、2000次并发、原调用副作用一次、停用其他Hook不受影响、真实文件映射恢复及dlclose通过。
- 同源额外BTI/PAC构建通过同一组检查。它是单独命名并记录flags/hash的变体，没有把NOP插入原夹具。
- 25项activation竞态通过：同页prepared跨恢复、旧callback跨新版本、外线程token拒绝、错误原调用消耗权利。
- 只剩invocation lease时的dlclose：匹配系统EXIT_SELF198与Native witness，publication已退休，且无成功result。
- Java/JNI核心19项、400次foreign pthread回调、GC根、精确64位和原调用限制通过。新NativeHookValues复用ScriptValues ABI校验，脚本主机套件671项通过。

运行ID、APK与边界见[H5证据](evidence/self-hook-h5-2026-10-15/README.md)。后续变更的验收不与历史构建混算。

## 当前继续项

新增加的live映像Build ID/可执行段核验，在loader锁内读取真实phdr和note，观察时不借publication lease阻止卸载。多实例路径保留已有primary，拒绝歧义新选择；移除primary后新代次不得复活旧token。park窗口的publication readiness还检查loader一致态和notifier epoch，拒绝停在半次reconcile中的快照。这些增量正在构建和设备验收。

产品NativeObservation/NativeBackend及混合Platform、既有审批/UNKNOWN链、Java native声明的ART/JNI路径仍须继续实现。固定Framework真实安装器、构造期逻辑Application、完整预算和产品性能验收也仍未完成。
