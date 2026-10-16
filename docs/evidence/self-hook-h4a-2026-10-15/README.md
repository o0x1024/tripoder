# H4a：产品容量、固定成员与证据发布

2026-10-15。用户已选择“客体代码执行前初始化，运行期间动态Hook”。完整产品替换仍未完成；所有设备运行仅限自有`dev.tripoder.selfhookprobe`。

## 本批实现

1. 同一FrameworkAccess注册表清点六类manager的真实声明：177/168/495/334/8/88，共1270个实例方法，均非native/abstract。完整descriptor/flags的SHA固定在`android/self-hook/manager-inventory.json`。清点没有开放这些方法的通用boot Hook准入。
2. Java/helper DEX、Native entry、原调用存储/ClassExt根、writer注册表和汇编leaf同步扩到2048。Java在准入前核对已加载Native容量；停用不回收进程租约，超预算拒绝。根报告改为暂停前堆分配及按已用槽位计算缓冲区。
3. F1 Service组实现：固定`mActivityManager`/`mToken`和`stopServiceToken`元数据，原线程JNI调用、token身份、boolean/int和原异常保持。产品FrameworkCalls委托及准备绑定已接通。设备夹具使用自有Proxy，没有调用真实系统stop。
4. 所有探针结果统一采用永久独占claim、临时文件完整写入/fsync、同目录ATOMIC_MOVE发布。`result.json`存在才是完成信号；失败不切换发布方式，不覆写旧结果。

设计：[产品容量与固定成员](../../self-hook-product-capacity.md)。源码、构建输入、APK与Native身份见逐run回执；所有`productAccepted`仍为false。

## 当前构建

- APK：`e6941e9b75600b277a545ca49721cfa368db758e5e8dc48a9c3f058673c144c3`
- buildId：`a5e729f6-9792-471b-8416-7bd2bc50e9a4`
- sourceDigest：`5f15982efee92217f530c9923b10225edf5b479954ff70e3e6e4a5ea7a4e12d0`
- 实物：[artifacts/e6941e9b](artifacts/e6941e9b/)。安装使用非增量streamed install。

<!-- CURRENT_MATRIX_START -->
当前构建11组独立验收全部通过。每项仍只证明表内范围，产品未切换。

| 场景 | 执行模式 | runId | 结果范围 |
| --- | --- | --- | --- |
| abi | aot | [4eac2879-666c-4b88-ad25-3e9fe66d03cc](4eac2879-666c-4b88-ad25-3e9fe66d03cc/verification.json) | 19种签名/2048项、移动GC与400轮根观察 |
| abi | jit | [01a949d6-ec21-419c-bfb8-840f766b619f](01a949d6-ec21-419c-bfb8-840f766b619f/verification.json) | 19种签名/2048项、移动GC与400轮根观察 |
| application | jit | [dd7e02ae-1348-4393-88f1-c7ba4f200c16](dd7e02ae-1348-4393-88f1-c7ba4f200c16/verification.json) | 100万对真实字段读写、409次固定JNI物理检查 |
| capacity | native | [5a262935-8b1a-4837-ab38-db905c3f78e9](5a262935-8b1a-4837-ab38-db905c3f78e9/verification.json) | 2048目标、4096次Hook调用、2048次停用后调用；移动根和最后一槽132次writer事件 |
| coverage | jit | [3bdd2527-8ea9-418f-b446-a2962dcdebc4](3bdd2527-8ea9-418f-b446-a2962dcdebc4/verification.json) | 动态loader/boot、类移动、entry发布与异步展开组合 |
| framework | jit | [646b6e4d-dcd9-41b9-9f41-288833964c91](646b6e4d-dcd9-41b9-9f41-288833964c91/verification.json) | 原Application组7类/21成员及物理身份 |
| inventory | native | [884d37e5-62e3-47c3-a4c6-191990d31238](884d37e5-62e3-47c3-a4c6-191990d31238/verification.json) | 六类manager共1270实例方法，完整声明hash与固定清单一致 |
| script | aot | [ed0bb8e1-e115-4122-aa99-86bd0f23c46a](ed0bb8e1-e115-4122-aa99-86bd0f23c46a/verification.json) | 473项动态Hook脚本检查 |
| script | jit | [aa91ccc6-cfde-43d1-8f2a-06c615beeb14](aa91ccc6-cfde-43d1-8f2a-06c615beeb14/verification.json) | 473项动态Hook脚本检查 |
| service-token | native | [c4845277-0d49-4402-ae73-100664a3d645](c4845277-0d49-4402-ae73-100664a3d645/verification.json) | 23项、6次调用、2线程、原异常与token身份；自有Proxy |
| startup | aot | [70a6b171-a2c1-401e-987b-6ee2a1f3a5f8](70a6b171-a2c1-401e-987b-6ee2a1f3a5f8/verification.json) | 进入帧前建立策略，两次均命中且原调用恰好两次 |
<!-- CURRENT_MATRIX_END -->

## 主机检查

- [entry-ownership-host.json](entry-ownership-host.json)：2066项ASan/UBSan、80万次并发选择、35项LLVM oracle。
- helper独立DEX解码22份，检查51项，并明确核对slot 63/64/2047的实际class与const编码；两个汇编leaf独立核对2048槽位。
- [runtime-host-report.json](runtime-host-report.json)：293项bootstrap、11 loader、85组件、117边界、215 manager策略、45 typed；167个SDK源码编译。仅主机，不是产品验收。
- [atomic-publication-host.json](atomic-publication-host.json)：6个并发发布者只有1次提交，未读到部分结果；重复发布及预存部分文件拒绝。

## 历史构建和失败记录

- `4cf416de…`的`dfeb86c0…`、`d07729d9…`的`b483be27…`均已独立验证2048个不同目标、16声明类、4096次Hook调用和2048次停用后原调用。2048个备份的owner根均观察到地址变化（对应16个唯一Class），移动后关系全部正确；最后一槽通过132次真实writer事件。停用后剩余容量仍为0。后者压力总耗时106761ms，包含全量准备/调用/GC/停用，不是产品启动性能通过。
- `30b399d6…`被系统报告WARM，未通过冷启动条件。runner增加真实进程停止证明，并只清空自有探针task，避免旧根Activity重建后消耗一次性状态；没有清理应用数据。
- `f6ca93ca…`在安装后未确认进程停止，未运行目标测试；失败保留。后续独立run已确认停止再执行，未把旧失败改成通过。
- `ce9688d8…`/`13aadf9b…`设备ABI结果通过，但旧verifier写死50项helper检查，新增负slot检查后实际51项，报`HOST_HELPER_ORACLE`。已同步预期并增加独立slot编码核对，用新构建重跑，不追认旧run。
- `ac407a21…`采集到了空的`device/result.json`；设备随后写完70988字节，单独保存在`late-result.json`，原空文件保持不动。根因是先createNewFile再写入。当前改为统一原子发布；失败后补读的文件不算该run验收通过。

## 尚未完成

F2/F4/F5固定成员、完整必要入口的真实安装器、Runtime客体首次执行门禁、构造期逻辑身份、产品控制/审批链、Native目标/模块生命周期及性能仍待完成。Engine当前仍使用Frida；本批源码和探针通过不能作为移除产品依赖的依据。
