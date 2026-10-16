# H3j：调用者精度、动态类观察与入口owner集成

2026-10-15。仅使用自有 `dev.tripoder.selfhookprobe` 与自有DEX/Native夹具；没有安装或测试第三方应用。**产品仍依赖Frida，完整调用者/未来发布/入口写入闭包未完成。**

## 已通过的分项

| 范围 | 报告 | 验证边界 |
| --- | --- | --- |
| 动态类观察 | [ea064c7c](ea064c7c-8f2a-42df-971f-29850bc5723b/verification.json) | 约21844类；两个动态loader同名类、两个Class实际移动、24次GC期间枚举；不初始化目标类 |
| 入口选择及动态类观察 | [37b5085f](37b5085f-1ab1-42a9-8b7b-e4488e81414c/verification.json) | 自有预链接leaf40万调用、10万状态发布、原CAS记账及SP；没有实际ART patch |
| owner接入ABI JIT | [ecc37cfd](ecc37cfd-85a7-47e0-8756-258d476a58ee/verification.json) | 2048项、400轮GC根观察；19个owner直接引用现有entry状态，提交在同一暂停窗口 |
| 脚本实链JIT | [ec22d84d](ec22d84d-484e-4908-b3c1-4dd99d225b40/verification.json) | 473项源码→真实ManagedHook控制检查，仅四个自有target和直接caller |
| 脚本实链AOT | [a561c7a6](a561c7a6-4f3d-4b03-8aa0-ff3823670370/verification.json) | 同构建473项及实际编译入口，原调用计数504/100/100/101 |
| 加入脚本后的ABI回归 | [d8ef86da](d8ef86da-a5df-4b17-b2d6-66dc11209af4/verification.json) | 2048项、400轮根观察，静态同签名不再串入无关caller |

这些报告属于不同的明确构建，不互相继承验收。APK/SO/回执已保存在各 `artifacts/<sha-prefix>/` 中。脚本JIT/AOT构建为 `41511601b968eaa7a51a69da3fb2c5764657b291e5953f8e5b2e06f426fb9e55`；本轮后续同步展开构建需单独验收。

## 实现内容

- DexCallerClosure：resolved static/direct按准确owner匹配；virtual及未解析继承static保留保守候选。新增真实D8对照17项通过；既有多DEX/loader55项回归通过。核心增加实际signature展开工作预算，避免只限制去重边数。
- LoadedClassInventory：将既有C++观察器链接进独立探针；65536预算、根池登记、类锁内无JNI回调、返回前完整Class根保存。预算1时拒绝部分结果。动态DEX来自APK固定asset并绑定SHA，不接受外部输入。
- 动态观察没有新类发布前阻断能力，`futureClassPublicationCovered=false` 保持。实测Class移动及GC枚举不能替代任意CheckJNI/发布并发闭包。
- 入口owner：参见 [机制及门槛](../../self-hook-entry-ownership.md)。新leaf保留原PAC签名及返回地址，避免主动解签/重签；状态注册后由现有ManagedHook entry提供唯一desired值。
- 脚本：源码准备无Hook副作用、重复token/SHA拒绝、精确long/float、原异常身份、4线程调用、参数CAS、在途旧参数快照、DRAINING与退休、before/after错误不重试均有设备检查。报告保留 `productBackend/fullCallerCoverage/authorityApprovalPath=false`。
- Native scalar夹具已修复pthread_join失败后可能释放worker栈状态的问题：失败直接结束自有进程；NDK严格编译通过。scalar汇编设备执行和完整ABI安装仍未完成。

## 下一实施点

入口真实代码提交/异步展开及直接writer → 未来类发布门禁与boot/guest loader → 产品Platform及其余固定成员/manager安装。先证明能力再切换产品；不能将已加载快照、owner注册或自有脚本夹具等同于完整Frida替换。
