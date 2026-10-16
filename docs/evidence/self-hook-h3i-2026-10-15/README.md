# H3i：冷启动根验收、并发读屏障与 F0 固定 JNI

2026-10-15。仅自有 `dev.tripoder.selfhookprobe`、既定 API37/ARM64 设备。**产品仍使用 Frida，完整调用者覆盖与产品替换未完成。** 本轮未清除探针数据、编译缓存或 profile；测试完成后仅 force-stop 自有探针。

## 当前构建

- APK SHA256：`9af55148c9a458686f30e37ffc73aa737b5690989b557b08434594c2d3c5147d`
- buildId：`730e941e-c9af-4c8e-a949-18462ae57712`
- sourceDigest：`339e1a64c004dd3c827396a710162614a59f529fd40b3cf3da08e2d41b6a8de3`
- Native SHA256：`1f251deac75f36dae4ba028bdb227e78a0e673f0f9d747eadce2f1b876dce51e`
- 固定 libart：`2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13`
- 保留的 APK/SO/构建回执：[artifacts/9af55148](artifacts/9af55148/)。各次独立 verifier 都在对应源码仍与 receipt 一致时执行；历史报告不能用于后续源码。

## 当前构建验收

| 场景 | runId / 报告 | 结果 |
| --- | --- | --- |
| ABI JIT | [5111430e](5111430e-f1f1-4b33-bf42-d4eabd8726a2/verification.json) | 2048项、19签名；505296 worker调用无错误；4参数和19个owner槽移动；全运行10次读屏障地址修正 |
| ABI cold / 实际 AOT image | [3f8a9d94](3f8a9d94-94a2-4456-aea3-326afc296d34/verification.json) | 2048项；720089 worker调用；4参数移动；19个owner槽未移动且全部有自有映像归属证明 |
| 同 APK 冷启动聚合 | [cold-start-aot-image.json](cold-start-aot-image.json) | 两个独立PID、同构建、时间顺序、映像文件/实际方法位置及先前owner移动证明通过 |
| ABI AOT speed | [bb6e63e2](bb6e63e2-feaf-4043-862e-f087c9bb50de/verification.json) | 2048项；627981 worker调用无错误；实际speed回执绑定同APK |
| Application JIT | [6550f89f](6550f89f-48bc-48fc-b542-f181d9dd0a6b/verification.json) | 委托selected loader、完整生命周期、一百万字段读写对、40000并发逻辑读、409固定JNI物理检查 |
| Application AOT | [96e29015](96e29015-bd7f-42ad-b0c3-d55428483a3b/verification.json) | 同上；其中400次物理检查来自4个工作线程 |
| 固定框架成员 | [70b60526](70b60526-2307-4178-92df-b24f822e3a45/verification.json) | 7类、21成员、17普通JNI校准、实际物理归属通过 |
| 共享主探针 AOT | [04519344](04519344-f628-484a-8e77-3f1cbefc372f/verification.json) / [编译入口](04519344-f628-484a-8e77-3f1cbefc372f/compilation-verification.json) | 固定libc 24+24检查、373348调用；非协作Native 66723226调用；ART/反射/原调用回归通过 |

三次 ABI 运行均增加400轮、每轮19个槽的根关系观察，后台线程每批保留2 MiB分配压力；对应期间实际GC次数为37/33/34。cold压力窗口观测到38次读屏障地址修正，400轮根关系均成立；JIT全运行的10次修正发生在该压力窗口之前。数组容器的57次移动统计是槽位观察数，不是57个不同对象。

本轮聚合证明的是实际 **AOT app image** 冷启动；不将它表述为新的 `cache/oat_primary` runtime-image 序列化证明。后者仍保留H3g历史证据范围。

## 根因与修复

### 1. 冷启动不移动的 Class 被误拒绝

旧验收无条件要求owner地址发生移动。映像内Class无需移动，因此会把设备正确结果拒绝。现在 Native 在一次SuspendAll内记录所有槽的owner、target/backup owner及三个container根；独立Python验收重新计算移动数量和根关系。

cold未移动owner必须落在真实自有app image匿名区，且VMA名与准确自有base.art文件映射绑定；中间截断名称只接受与该文件两端匹配。参数实际移动、container移动、原返回引用身份不变的门槛保持；聚合还要求同构建早先运行实际证明owner移动。17个独立正负验收测试包含外国映像、缺文件、重复映射、错根、假计数、无container移动等拒绝。

### 2. SuspendAll 不等于并发 GC 完成

中间构建 `c8fd1729…` 的 [d358b0f4](d358b0f4-6fe8-4ff2-befe-3c4155fea9f0/device/result.json) 在第10次分配拒绝：JNI强根已经指向新地址，而ArtMethod/ClassExt中的原始引用仍可能指向旧地址。原实现直接比较原始引用，错误地把这个并发复制阶段认定为所有权丢失。该运行失败被完整保留，没有改成通过。

当前 `art_method_storage.c` 对owner、ClassExt、container与DexCache引用使用固定ART根读屏障，`art_managed_entry.c` 的owner关系检查采用同一语义。校验没有放宽，比较的是ART确认的引用身份。额外完整函数字节契约：

- `artReadBarrierForRootSlow`：RVA `0xa2c4e4`，72字节。
- `art::ReadBarrier::Mark`：RVA `0x207210`，1424字节。
- [固定 ELF 反汇编](read-barrier-disassembly.txt)，构建/进程内均验证完整字节与libart身份。

Native原始内存访问需要ART的读屏障；暂停mutators并不能代替它。屏障可能执行ART内部标记/复制，因此独占区约束应写“无显式JNI/分配/I/O”，不能宣称ART内部绝无分配。

### 3. AOT 文件归属判断过窄

中间构建 `5383997f…` 的 [0a130de4](0a130de4-f5b0-4969-bd6d-e7045b713649/device/compilation-AbiFixture-bool.json) 实际入口位于自有 `/data/dalvik-cache/arm64/...@base.apk@classes.dex`，原判断只识别相邻base.odex。Java与独立Python现在都从进程实际映射的自有base.apk推导准确代码文件名，拒绝其他包、旧安装代次、deleted或无文件映射。各10个Java/Python正负路径检查通过；不改变严格JIT/AOT要求，不切换后端。

### 4. F0 与 loader 身份

固定注册表从11字段扩展到12字段，第11项为静态 `ActivityThread.sCurrentActivityThread`，原0..10索引不变。ApplicationAdapter的物理检查和构造线程读取改为JNI固定字段，去除对managed currentApplication/currentActivityThread getter的依赖。

PhysicalApplication已进入独立探针Native构建；普通JNI全局根支持跨线程物理检查。Java验证boot定义采用准确 `Class.forName(name,false,null)` 身份，不假定公开getClassLoader一定返回null。selected loader用实际类解析身份验证，允许委托给defining loader；自有夹具已覆盖该情况。

这些修复尚未安装产品完整Framework Hook，也未解决factory/Application构造期间的逻辑身份视图。

## 主机与证据流程

- 17个根证据检查、10+10个编译路径检查通过。
- PhysicalApplication：79 C mock检查，15 HotSpot CheckJNI场景/53断言通过。
- 产品适配：281+11+85+117+215+45检查、166源SDK37编译通过。第一次调用误用了不存在的SDK apksig.jar路径；改用已有 `android/runtime-probe/build/apksig.jar` 后通过，没有改产品代码来绕过编译。
- 构建中的helper 50检查/22 dexdump、closure 9检查、固定ART/libc字节契约及park binary audit通过。
- 主探针首次独立核验拒绝了runner带入的旧APK DEX oracle。已保留 `dex-host-report-stale-at-launch.json`，对准确当前APK重跑14项oracle，并保存 `supplemental-oracle.json`；设备结果未修改。runner新增启动前当前APK oracle门禁，后续不再先跑设备才发现此问题。
- `artifacts/` 还保留5383997f、c8fd1729、8435077d中间构建；这些结果属于历史，不是当前构建验收。

## 下一步

1. 修复exact static caller边的owner精度，增加不相关类同签名D8对照，再集成loaded-class观察器与实际GC/CheckJNI。
2. 完成未来类发布和系统入口写入所有权；PAC平台准入、动态FDE和异步展开仍需独立证明。
3. 接入自有script fixture和产品Platform；完成其余固定成员/真实installers/manager。
4. Native scalar夹具先修pthread_join失败路径的worker生命周期，再审计汇编/CFI/BTI并执行自有设备对照。
5. 全部产品门槛满足后统一切换Engine/TS/构建入口并移除Frida；当前不能提前ready。
