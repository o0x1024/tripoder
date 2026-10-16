# H3e 原始反射帧与逻辑 Application 视图（核心已验收）

日期：2026-10-15。当前产品的 `reflection-view.ts` 需要在 Field.get/set 的原始访问检查之外提供逻辑 Application 身份。这里以自有 Application 字段夹具实现 Native 适配核心，尚未配置真实 ActivityThread，也未接入 Runtime bootstrap。

[JIT/AOT 证据](evidence/self-hook-h3e-2026-10-15/README.md)：两组各通过 76 项检查、一百万次精确读写对和约 1390 万次并发读取，原始物理存储保持不变。

## 不可变身份与原始检查

一次配置固定实例字段的真实 jfieldID、所属对象、物理 Application、逻辑 Application 和确切声明类型；拒绝 static/final 字段、不匹配的 owner/type/物理值及重复配置。Field 对象副本通过 jfieldID 归一，其他 owner、其他字段或其他 ClassLoader 的同名字段不映射。

读取先直接调用 Field.get 的原始 Native 实现，保留现有 managed frame。原始异常保持 pending，不改写；只有原始调用成功且字段/owner 匹配时，确认物理值没有变化后返回逻辑对象。物理值异常则抛出明确的身份错误。

写入先区分类型错误与映射范围：错误值类型保持原参数调用原实现；类型正确的映射写入将参数换成原物理 Application，仍由原 Native 实现执行真实类型/访问检查。原始调用成功后，只有请求写入逻辑对象才允许返回；其他 Application/null 请求抛出身份错误，物理存储保持原对象。没有通过 Java Method.invoke 重新执行原 Field 方法。

## 发布与存活

实际 libart SHA、JNI 方法身份、原始 Native data、完整 flags/quick 由固定平台访问层绑定；get/set 一起 RegisterNatives，核验两者完整状态后才启用映射。停止时先关闭逻辑版本，再恢复原始两项绑定。变更后出现不明状态直接终止自有探针进程。

九个全局根和 callback 代码保留到进程退出；迟到的旧 callback 仍安全。最多 128 次显式转换，不做槽位复用、未知帧回收或无限热更新承诺。此适配不是通用 managed 方法替换，也不改变 ART 的 JIT/AOT 调用者门槛。

## 验收范围

- 私有字段访问、owner/null/类型错误先后、AccessibleObject override。
- 逻辑读取/写入、原物理存储、非法写入与意外物理身份改变。
- Field 对象副本、其他 owner/字段及另一 ClassLoader 的同名类。
- GC 请求和压力后的全局引用、四线程切换、原绑定恢复。
- 实际观测 JIT/AOT 编译入口后，一百万次 Field.set/get 读写对分别精确计数，物理存储不变。

本阶段不声称证明实际 GC 对象迁移、所有 Field primitive 变体、真实 ActivityThread 配置或完整产品替换。
