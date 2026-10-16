# H4b / H5：固定产品成员与真实 Java 后端

2026-10-15。用户明确要求全部完成后再停止，持续Goal已处于active。以下是增量验收，**不是产品完整替换完成**。所有执行仅限自有探针和其独立loader/自发本包广播。

## 当前构建

- APK `da3eff64876c063d55bf2a30d2d12a7e9ee23ddfe25ac9174eaf4990020c9c4f`
- buildId `973a4bec-7831-44db-bf23-42c29d6e46c6`
- sourceDigest `f352ed4c5c889e53514d9d12ef82c914dad4f3f469987be80e7da7bdffac58d0`
- 实物：[artifacts/da3eff64](artifacts/da3eff64/)。

| run | 独立验收 |
| --- | --- |
| [c2525314-27d8-407f-b8f7-5f610146c378](c2525314-27d8-407f-b8f7-5f610146c378/verification.json) | 真实RuntimeJavaObservation/RuntimeJavaHookBackend源码，34项，12个方法、2次激活；完整Java标量/void、原异常、无初始化观察/源码准备、拒绝宿主/重复Method/当前未承接native declaration、停用恢复、换symbol ID复用同一Method句柄、失效证据拒绝 |
| [01e29eb2-5095-4007-9acf-bddd760dd339](01e29eb2-5095-4007-9acf-bddd760dd339/verification.json) | F2/F4/F5固定成员32项；六类manager真实构造，64位flags、实际self-broadcast PendingResult/goAsync身份、Provider handle、selection snapshot/back-reference、逻辑ApplicationStartInfo精确long值 |

Java后端测试的控制transport为明确的自有Java-only harness；真正产品审批链尚未连接，Native后端尚未连接，因此对应scope flags和productAccepted都为false。不能把空模块列表或Java-only能力移入产品并声称完整替换。

## 实现

- 既有FrameworkAccess注册表扩展为24个固定boot类根、21个固定字段；Application语义组保持原7类/12字段/9方法。`FixedFrameworkMembers`只通过该注册表枚举和核验精确owner/name/descriptor/static，不进行普通隐藏成员查找，不探测备用签名。
- `ComponentFrameworkAccess`承接F2；`ManagerFrameworkAccess`承接F4/F5；`RuntimeSelfHookFixedFramework`实现两项产品接口。准备与部分失败仍一次性，未实现真实Hook安装器时不开放facade给guest。
- `RuntimeJavaObservation`从RuntimeHookBridge提取为**同一份**观察记录，保存实际Method、defining loader与完整symbol metadata；旧transport和自研Java backend共享，未新增一套按名称重查结果。
- `RuntimeJavaHookBackend`复用SelfHookControl/HookInvocationSession的发布、DRAINING、退休及UNKNOWN语义；Handle按Method身份保留，基础适配已拥有的目标在发布前拒绝。实际prepare/enable只发生在激活的publish，任何发布/退休不确定性走原fail-stop链。
- `JavaHookValues`覆盖boolean/byte/short/int/long/char/float/double/String/null/void。long为BigInteger，char为单UTF16 code unit，float只在ABI处舍入，receiver/Class/Method不暴露给脚本。
- [script-host-report.json](script-host-report.json)：633项主机合同/SDK与Babel oracle，包含新增40项Java类型边界；[runtime-host-report.json](runtime-host-report.json)：原门禁/组件/manager检查与189源码SDK编译；[script-fixture-host-report.json](script-fixture-host-report.json)：14项既有脚本夹具检查。

较早同批构建`c957eec8…`还完成F0/F1回归和使用同一产品codec的脚本JIT/AOT各473项，见`5a3c1073…`、`953caaf1…`、`d6cf97f5…`、`6e551ac0…`各自verification。它们绑定自己的构建，不并入当前两项的通过数。

## 保留失败

`ab09aecf…`使用`BindServiceFlags.of(Long.MAX_VALUE)`时被平台拒绝，因为包含已废弃的BIND_EXTERNAL_SERVICE位。访问器没有吞错或换方法。新夹具使用保留高64位且去掉该禁用位的值，同时明确验证平台仍拒绝禁用位；不能以这个失败推导long读取损坏。

## Native下一步的实物依据

已取得当前固定`linker64`，SHA为`b6dd216b7a775b8f04c825cada286bfe7ddaeecf2c0c10e617287118965abc00`，Build ID `436b6338be03b657e1fc48074ab87b59`。有完整symtab，无需猜offset或依赖debugdata。

静态调查产物见[linker-static](linker-static/)。`rtld_db_dlactivity`/`__dl_rtld_db_dlactivity`为RVA `0x55d80`、8字节，严格为`BTI c; RET`；`__dl__r_debug`在`0x1bcbc0`。正常卸载的RT_CONSISTENT通知在`0x56180`，之后才在`0x561b0`调用soinfo_free、`0x56794`调用munmap。原函数不改寄存器；新relay必须保留现场，不能把ART writer专用leaf直接拿来用。

这是静态文件证据，未绑定运行时r_debug地址、未安装notifier。`load_library`清理分支`0x7a4a8`是否只释放从未公开的新模块正在进一步核验，不能声称完整释放闭包已完成。Native入站ABI、通用安装、真实模块generation/卸载同步失效与JNI native method仍是完整产品切换前置项。
