# 进程级解释执行所有权

2026-10-15。当前ManagedHook核心已使用本策略；产品仍未切换至自研后端。H3m新增明确的启动初始化和稳定AOT在途帧反例，见[证据](evidence/self-hook-h3m-2026-10-15/README.md)。

用户已选择启动边界：在任何客体代码执行前初始化，应用运行期间可以动态添加、更新、启用和停用Hook。任意既有AOT在途帧迁移不再属于本次产品验收范围；启动顺序和初始化后的覆盖仍必须用真实执行证明。

## 为什么需要改变编译策略

实际运行 `eca1662b-ff09-42d6-8b78-9fa085ea7608` 中，Hook激活后才加载的caller在第67,682次调用绕过。目标quick仍指向正确relay，说明入口没有丢失；后续caller内联已经不再经过该入口。逐方法deopt集合只阻止目标本身的编译，不能证明未来caller不会内联它。只保护quick写入也不能解决这个问题。

首版采用覆盖优先：以固定命名ART client持有整个进程的解释执行级别2。它改写后续调用使用的入口，**不能迁移已在栈上的普通AOT编译帧**。产品必须在客体代码首次执行前调用`ManagedHook.initializeProcess()`，不能依靠首次用户Hook激活来建立这个边界。该选择会增加Java执行成本，尚无产品性能验收；它不改变Native代码的执行方式。没有第二种自动选择的编译策略。

## H3m：入口与在途执行是两个不同的事实

自有`InFlightCaller.call`先进入真实AOT代码，在JNI屏障中等待另一线程激活Hook，然后继续调用目标。固定目标入口已指向relay，但恢复的旧帧返回10，下一次新调用返回110，只有新调用进入原函数桥。诊断代码放在独立Activity中，避免增加原调用者方法的大小而改变内联决策。

同一APK的提前初始化对照在进入该调用帧前建立解释租约；虽然方法原先有真实AOT入口，初始化后它使用解释入口进入，新帧在屏障后和后续调用均返回110，原函数桥恰好执行两次。这证明初始化后的新调用路径，不证明旧AOT帧迁移。

[AOSP Runtime::IsAsyncDeoptimizeable](https://android.googlesource.com/platform/art/+/android16-release/runtime/runtime.cc)限定异步反优化能力；[Instrumentation的栈安装](https://android.googlesource.com/platform/art/+/android16-release/runtime/instrumentation.cc)不能仅凭级别2为普通AOT帧补出不存在的反优化状态。源码用于解释机制，设备结论来自固定ART身份下的独立对照。不能通过增加一次DeoptimizeEverything、强制写标志或清理编译缓存冒充修复。

## 提交边界

- `art_interpreter_policy.c` 在prepare阶段仅验证完整函数字节、绑定只读函数地址，不调用DeoptimizeEverything。
- 显式`initializeProcess`必须早于任何Handle准备，且只能尝试一次；失败后该Java入口永久拒绝后续准备。复用相同绑定、中央writer和解释租约，不创建第二份策略状态。
- 初始化在JIT暂停及SuspendAll内检查当前级别必须为0。已有外来instrumentation状态不能被认领。旧独立探针仍能在首次enable调用同一claim原语，以保留晚激活反例和分项测试；这种方式不具有产品启动覆盖资格。
- 调用固定`DeoptimizeEverything(client)`，确认级别准确为2。显式启动只建立进程策略；之后目标enable在同一暂停窗口注册本entry的owner、提交desired/quick/active。
- 后续切换检查租约仍为级别2；观察到不一致时失败终止，不静默修回、重试调用或切换后端。
- disable保留进程租约。relay、helper、备份和owner根均采用当前有界进程生命周期，不能在仍有旧帧时恢复编译或回收。
- 不给声明目标设置CompileDontBother。首个claim之后不再用逐方法deopt-set作为ManagedHook编译权威。

命名client为`tripoder-managed-process-interpreter`。`ManagedHook.processPolicy()`报告绑定、是否已拥有、最后一次暂停窗口观察到的级别、claim次数和是否显式初始化；`preexistingCompiledFramesCovered`及`fullCallerCoverage`均为false。显式初始化标志不是“此前没有客体代码执行”的证明，该责任属于Runtime启动生命周期。

## 固定平台契约

只准入当前已测libart SHA `2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13`：

| 函数 | RVA | 完整字节数 |
| --- | --- | --- |
| GetCurrentInstrumentationLevel | `0x8df858` | 12 |
| DeoptimizeEverything | `0x8e09e4` | 16 |
| ConfigureStubs | `0x8df864` | 160 |

`tests/check_art_interpreter_contract.py`将三份完整函数体绑定到整个固定ELF；进程中另验证文件映射身份和实际字节。不是按Android版本猜测地址或写内部标志位。

## 已通过和仍未完成

[H3k证据](evidence/self-hook-h3k-2026-10-15/README.md)：JIT/AOT各19种managed签名、2,048项检查，移动参数和备份根、400轮并发根观察及原调用次数通过；源码到真实ManagedHook脚本链路JIT/AOT各473项通过。ABI夹具必须在首次enable前观察全部编译入口，不能在已持有解释租约后要求新JIT编译。

当前构建还通过双InMemoryDex loader、101,002次晚加载调用、独立loader目标以及boot `currentApplication`。原调用分配器从真实`Class.classLoader`读取归属，boot真实值为null；不能把公开API返回的BootClassLoader包装对象传入ART分配器。

中央writer已在H3l真实安装；obsolete/AddImageSpace等直接写入仍没有完整闭包。初始化不能迁移已经存在的boot或应用AOT帧。产品需在guest factory/类初始化/构造前建立执行边界，并逐项审计仍在栈上的宿主Framework调用路径。未来AOT/image发布、MethodHandle/JNI变体、caller-sensitive原帧、加载器卸载及产品性能仍须独立验收。
