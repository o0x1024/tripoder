# H5 Native模块生命周期证据

## 2026-10-16 最新 H6/H7

以下覆盖旧状态描述，但不表示产品整体去Frida完成。Engine/build仍加载Gadget。

| APK | runId | 独立验收 |
|---|---|---|
| `dafa3eec…` | `dc220d83-21a4-4f92-9a25-7a9b4d720013` | 产品Java backend43项、16方法、4 Native声明、2激活 |
| 同上 | `ea3f2813-db3f-4f59-98b3-52662594dc1f` | JNI SDK31项，独立loader，普通/Fast/Critical各32参数、混合GPR/FPR overflow、396原生调用、384并发、67注册 |
| 同上 | `017638f4-3166-4ef3-9048-bea01f95973c` | 复合产品Platform混合19项 |
| 同上 | `18a473e8-2732-4a3d-a841-b92c1e8a12b5` | 构造前Application14项/8客体阶段 |
| `99a5a969…` | `345d8f3b-8851-4d5a-b5ee-abb4244a3ea8` | 首轮JNI SDK30项、395原生调用 |
| 同上 | `27d139b9-91a2-4c07-990c-059e0ae13d19` | Constructor26项回归 |
| `9b43605e…` | `0e395110-0b49-4625-b034-9b24c41234f1` | JNI raw与quick owner50项、实际注册/注销后持续管理入口 |
| `8d21c5ea…` | `1b41ece6-376c-4c60-89a9-39d940a426b4` | JNI寄存器33项/101 Java回调 |
| `7529134a…` | `a4b8ede2-8d28-42b0-af20-6599da76748d` | 构造前Application14项/8阶段 |
| `8a8de27a…` | `0cea1ec5-df14-4d43-8fe6-c83f52c72451` | 1270 manager15项；1262新增prepare6.993秒/publish27.75毫秒 |
| 同上 | `10892234-6f2f-4797-97a7-968cb1673689` | 完整2048 batch容量、132writer事件、2048 owner根观测移动 |

完整APK/native/receipt在对应 `artifacts/<前8位>/`；各run独立verification绑定源码和APK，不能混算。失败 `f687e603…` 的 data==thunk 条件由实际 Critical延迟表证据纠正，失败run本身没有verification。

Native声明设计保留目标 Native/Fast/Critical flags 与 ART 注册data；只拥有quick。原方法ClassExt根备份的generic JNI frame保持声明类与loader，immutable adapter在original实际执行时查询ART当前绑定。未解析替换不强制解析，original找不到实现抛UnsatisfiedLinkError；RegisterNative的最终回调返回指针是调用权威。公共scalar16及200/240-byte帧保持不变，Java SDK32参数私有overflow为208字节。静态二进制与官方源码证据在 `jni-original-static/`，本身不代表产品验收。


## H6 实际固定安装与构造入口

- `4e425042…` / `d9fce2f1-5d85-43fe-807f-b566f0b9eb91`：产品永久安装器，BatteryManager全部8实例方法，13项独立通过。
- `67b2908f…` / `651caf5f-a626-46d4-8308-638aca1a7b71`：六类全部1270入口安装15项通过；1262项32.8秒。
- `b3daf8d9…` / `1974acc8-c8ae-4af4-80f9-420c459aeb4b`：真实Constructor flags、super/final/原异常、direct/reflection/this，23项通过。
- `198af6cd…` / `83b80984-a7ed-4ab0-a1be-296b4f0b3268`：批量整体验证、构造/不同签名方法共享helper loader，26项通过；`69790829-0ead-4e7e-b19e-585fcc218a24`：全部1270入口15项通过，1262项35.2秒，prepare23.3/publish11.9。

以上各run独立核验，实物同名前8位目录。全部manager仅证明真实入口安装与BatteryManager自有对象功能，未执行系统Binder业务；未证明完整产品语义/启动/性能验收。`7d7c81f5…` 一次暂停批量发布已独立通过：构造26项 `0ed1606e-3b9e-4b14-a906-b5dfe76b026d`、1270入口15项 `63274e62-0cad-4ec8-9af9-313baf1e8e8a`、混合19项 `0c439a62-43dc-44c0-a3ea-382b6a2cf54a`。1262项prepare23.779秒/publish28.4毫秒，总23.828秒；只是固定安装夹具计时，不是产品性能验收。Java native静态writer调查归档于[jni-declarations-static](jni-declarations-static/README.md)。

最新增量（2026-10-16）：

当前已核验构建 APK `464d0fcc4ced33d934f5a7a3074c0245f05565fdd581c71a389028df3b001ed8`，buildId `4635610f-cd2a-4cf9-9cd2-358c273d022e`，sourceDigest `4e922e4c6d5e0e572b147712142415ec7bad566c67a81f2a108c381a9d398c7b`；实物在 `artifacts/464d0fcc`。

| 独立 runId | 实际范围 | 检查数 |
|---|---|---:|
| `4a767dec-5967-45e4-a004-63ddc416ecd7` | 产品复合Platform，Java/Native同一计划与会话、完整证据分区、统一参数与停用 | 19 |
| `bbb6b169-ef9e-461e-9670-3d3392786cc9` | 6个自然C叶子导出、MOV/ALU/FP/SIMD/RET、3168字节SO末页恢复 | 30 |
| `d9626df8-2ad5-4cd3-855e-8ee20feb31c5` | 4096项自有路径目录、真实notifier与末槽边界 | 10 |
| `325c4351-35ac-40f5-b495-f280efdaaa3d` | 空目录仍实际安装notifier、不伪造模块/事件 | 8 |
| `fa40e926-44d9-4993-8ed3-b10a67bf5ba8` | JNI核心400 foreign调用、FPCR/FPSR隔离、事件与原调用token | 25 |
| `0c2d4fbf-1cd2-4f30-bed3-b8c458798157` | 产品Native后端、4导出/2激活/400 foreign调用、BigInt/参数更新/恢复/失效 | 31 |

每个run都有独立 `verification.json`，未把这些范围合并为产品验收。Runtime SDK检查195源码通过，脚本主机671项；Engine启动、真实审批authority与完整基础安装仍未切换。

此前 `fd5d4f59…` 的JNI23项 `5dc1c2f6-8c4c-4193-a35f-12892ec14f48` 与modules48项 `0d520f51-024e-4662-84aa-8f74c49a9d71` 已独立通过；`9a901742…` 的product-native31项 `e5dcb0eb-ee8f-4ee2-8719-75530b88c84b` 已通过。该构建JNI25项raw run `1dce08fe-f093-440e-b787-9a2cdc2e9e6b` 因核验前源码改变，保留 scope-note，不追认为独立通过；上表新运行取代它。


- `1882276f…` / run `06021271-de39-4f8f-b737-957d7fa56552`：入站78项、13编译器类型组、4000嵌套原调用、13次真实栈展开、1024槽末槽和超预算拒绝独立通过。
- `10359aeb…`：普通export run `1442f99f-2a51-4e85-b92c-3435eca4eea7`、PAC run `38a3c459-5005-484e-ab2b-66cb7025bff2`各32项/2000并发通过。四个原产品导出未改前导；变体flags另记。
- `9892d8ac…`：修复shared page restore消耗claim后，race run `b4f6dc59-6db1-4c7f-97cd-d722dedfe63f`独立25项通过；inflight death run `a1ce82a2-0448-477c-a88e-a2d88965a6c6`只剩invocation ref1且publication退休，PID20580/EXIT_SELF198与witness匹配。普通export `6767aa28-db87-4241-ac7c-28034d464255`、PAC `01a60f44-1eb1-421f-96d9-f275bc349276`各32项回归通过。
- `c1affa3c…` / `ca800193-1701-4ecc-be7f-1d2fc4b85e10`：实际Java/JNI Native核心19项、4个foreign pthread共400调用、402次Add callback及once拒绝、外线程/过期token、GC、取消prepare、恢复和卸载独立通过。
- 上述实物均在各自`artifacts/<sha前8位>`；live Build ID核验、多实例歧义和publication epoch后续已通过，上方按实际构建分别记录。细节见[Native入口设计](../../self-hook-native-export.md)。

本批跨2026-10-15/16，持续Goal仍active，完整产品去Frida未完成。没有把Native引用验收记为Native入口安装或产品验收。

当前构建APK `c5797f2171c2df3da04099ad3f94adc6979ac8b2a4c3c5da9d0a4d73467c920b`，buildId `90ef86ab-e8e7-48ba-9d78-b6cff73c7a3d`，sourceDigest `83aaa819db16ae269749bba212fd6279c455c73852be88407d95d4f055a8dddf`，实物在`artifacts/c5797f21`。

- `ffb6f163-8842-4ce5-bc81-8c902140b314`：独立验证47项通过；4线程400次load→snapshot→acquire→实际call→release→close无错误。7次加入/移除，32次真实notifier调用。重载新generation、旧generation拒绝、重复dlopen不改变generation、无效ELF与缺失重定位符号失败均验证。
- `fc293d26-5e6b-4610-9916-cb7c0c3669a2`：独立退出验证通过。Native在19项前置检查后持有generation1/ref1，fsync活动证据后dlclose。系统ApplicationExitInfo匹配PID4967、UID10403、reason1/EXIT_SELF、status198；不存在成功result.json。证明的是活动Native引用遇卸载同步终止，尚未发布通用Native Hook。
- 汇编完整寄存器对照：83条指令的独立sealed ELF relay，31个GPR、32个128bit SIMD、NZCV/FPSR/FPCR与errno均保留。83个真实PC可查询唯一FDE；尚无本relay的异步展开采样验收。
- 主机2000轮acquire/removal CAS竞态，移除和获取只允许一个方向胜出。二进制critical-path call allowlist包含notifier、scan、初次parked publication、lifetime函数，没有JNI/allocator/TLS/stdio/dl*调用。

固定loader文件为`/apex/com.android.runtime/bin/linker64`，实际`dl_iterate_phdr`名称为`/system/bin/linker64`。只接受这个已观测名称，另外核对Build ID、真实DT_DEBUG、完整源页、映射inode；不是多路径fallback。notifier只改BTI后RET一字，近地址ELF在发布前封存、加载并验证FDE；初始一致快照和入口发布在同一个park窗口内。

`native/modules/module_lifetime.*`以同一原子state保存generation/live/refcount，acquire与removal由同一CAS排序，旧代次永不复活。当前4096个精确artifact路径（此段早期构建为128）为进程内不变清单；不通过dlopen持有客体，不轮询弥补卸载。

## 保留的失败与早期通过

- `41ac25ad...` / `1410dcbd...`：最初错误地将物理文件路径当成dlpi_name，身份门禁明确拒绝。后者记录实际名称与三个身份位，修正固定契约后新构建重跑。
- `4be82397...`：较早597d3ef0构建44项独立通过，无400轮并发；实物已归档。
- `2e323093...`：ceb39470构建47项通过，120次通知/29次加入移除；实物已归档。
- `40208f55...`：系统虽记录EXIT_SELF198，但Native witness创建为0600导致adb pull拒绝。该run保留采集失败，不计验收通过。改为自有外部证据惯用0644后，用新构建、新run完成上面的验收。

## 边界及后续

静态正常卸载/早期Read失败/晚期链接回滚证据在H4b/linker-static。已发布soinfo的普通卸载和回滚先CONSISTENT通知再soinfo_free；CFI AddLibrary发布后失败为静态覆盖，尚无可控设备触发。任意应用munmap、内存破坏、非标准loader及任意回调闭包不由此证明。

已完成入站scalar ABI、真实export安装/原调用/FDE和Java/JNI核心分项；产品Native observation/backend与复合Platform已分项通过；仍需Java native声明及既有审批/UNKNOWN链。真实基础安装器、启动边界/逻辑Application构造期和产品性能也未完成。Engine/build仍依赖Gadget。
