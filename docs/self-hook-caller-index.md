# H3d DEX 静态调用者索引

日期：2026-10-15。目的：从实际构建的 DEX 找出已验证静态调用链中的 caller，去掉 Native 探针对 caller 类名/方法名的硬编码。

`android/self-hook/java/dev/tripoder/selfhook/dex` 实现独立 DEX 039 读取器。校验文件尺寸、端序、Adler32、SHA-1、映射区段、表/索引/偏移边界及 MUTF-8；按真实指令宽度遍历代码，正确跳过 packed/sparse switch 和 array payload，未知 opcode 明确拒绝。多个方法共用 code_item 是合法产物，代码按偏移缓存解析，方法定义仍分别保留。数组类型的方法引用（如 `[B.clone`）同样是合法引用，不能当作 class definition。

原始规范：[DEX 格式](https://source.android.com/docs/core/runtime/dex-format)和[Dalvik 字节码](https://source.android.com/docs/core/runtime/dalvik-bytecode)。未引入第三方 Hook 或 DEX 解析引擎。输入最大 64 MiB、累计代码解码 8,000,000 code units、单 code_item 调用点 250,000、图边工作量 500,000；超过上限拒绝，不返回截断图。

索引为每个方法保留 DEX SHA / method index / owner / 完整描述符，只对 `invoke-static` 及其 range 变体建立精确反向边，可计算传递静态祖先。虚调用、接口、super/direct 动态语义、MethodHandle 和 invoke-custom 没有被假装解析；计入未解析调用点，完整 Runtime 覆盖标记始终为 false。

独立主机验收用 javac + D8 编译另一份 fixture，验证传递 caller、payload 中伪 invoke 字节不产生边、数组 clone、摘要/格式/越界拒绝等 14 项。再读取真实 probe APK，独立找出 `InlineFixture.plusOne(I)I` 的 caller。

设备侧从实际 APK 读取 classes.dex，按索引解析具体 Method；校验其实际 defining ClassLoader 等于目标 ClassLoader，使用 `Class.forName(..., false, loader)` 不主动初始化类。Native 接收 Method 数组，不再按固定 caller 名称查找；先验证所有候选、去重并建立全局根，随后在同一个 JIT 排空/ART 独占区间反优化这些候选。当前最多 64 个，保留至进程结束。没有把全局同名字符串当作 Runtime 方法身份。

最终 JIT/AOT 各自动找到一个 caller，DEX index 与实际 ArtMethod 的 dex index 相符。两组均从 10,000 次全部绕过变为 10,000 次全部拦截。[证据](evidence/self-hook-h3d-2026-10-15/README.md)。

## 尚未完成的产品边界

- 当前验证范围为自有单 DEX / 单 defining loader 的静态链。解析器可接收多份 DEX，但多 Dex、多 ClassLoader 委托、虚分派和 boot image 尚无完整覆盖证明。
- 验收为了保留负例，先展示入口替换后的遗漏，再反优化。产品发布必须把调用者失效、方法入口变更及 ready ACK 统一到一次发布协议；不能沿用探针顺序对外宣告成功。
- 未来加载的 AOT 调用者、动态代码、MethodHandle、已在执行的栈帧及原方法重入没有因此解决。
- DEX 读取器只提供有明确范围的依赖索引，不替代 ART 验证器或客体安装权威；产品接入应使用现有 ArtifactSet 的不可变 DEX，不另建安装状态。
