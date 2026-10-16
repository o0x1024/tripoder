# H2a：自有 ELF 的真实入口 Hook

日期：2026-10-15。本文保留 H2a 初版文件页写入模型及失败记录。当前代码已采用 [v2 页发布模型](self-hook-page-publication.md)，并通过自有 ELF 协作域验收；H2 整体仍未完成。下文不是当前发布实现。

## 为什么先限定执行域

一个普通 Android 进程可以同时拥有 ART、Binder、Native 和第三方库线程。改写一个对齐的 B 指令并不能证明这些线程不会执行正在改权限的整页，也不能证明它们没有预取旧指令。首批实现只接受自有 fixture 的专属代码页，并要求所有调用进入同一个协作执行域；不把这个协议宣传为任意客体线程暂停能力。

目标是实际加载的 `libtripoderhookfixture.so` 中的函数，ABI 固定为 `uint64_t(uint64_t)`。目标页是 BTI c、ADD、RET 加 NOP 填充；相邻页是预留跳板，再后一页是固定 ABI 的 replacement 中转，另有 RW 回调指针页。代码页各 4096 字节，无其他函数、构造器、析构器、TLS、信号入口或外部调用者。构建清单、APK 中的 fixture SHA 和运行时实际文件 SHA 绑定；目标/跳板完整内容与 inode/device/offset 在变更前复验。

目标直接 B 到库内中转，中转按固定 ABI 使用 caller-saved x16 读取指针并尾跳，保留 x0/LR。不直接假设两个独立 SO 的 ASLR 距离小于 128 MiB。这是当前唯一的固定 ABI 路径，未知签名不使用它。

这一阶段使用已审计的 ELF 跳板保留页，不实现任意目标的近地址分配器。只接受未启用页级 BTI/MTE 的 RX private 映射；发现 `bt`/`mt` 明确拒绝，避免权限恢复时丢失硬件属性。保留入口 BTI c 并不等于完成硬件 BTI/PAC 验收。

## 责任和协议

- `tripoder_owned_hook.h` / `owned_hook.c`：唯一固定槽位、显式模块租约、单调 generation、调用登记、安装/停用、原函数最多一次、关闭。
- `code_page.c`：完整页面身份/内容检查、RX→RW→RX、I-cache 同步；不请求 RWX。变更后状态无法确认时 abort 当前独立进程，不带着未知结果继续运行。
- `fixture.S`：真实 ELF 目标和专属跳板页；无第三方 Hook 引擎。
- `probe_owned.c`：功能、拒绝路径、并发、超时、卸载/重新加载验收。

`open` 显式持有一个 dlopen 引用。首先在未发布、没有调用者的跳板页执行真实 store（值不变但会触发 private COW），再尝试恢复 RX。单纯不写入的权限翻转不能代替这项能力检查。失败则释放这个没有构造/析构器的模块，返回明确状态，不发布 generation，不改目标入口。通过后才准备跳板。`enable` 关闭调用入口登记，等待已登记调用归零，再复验页身份与预期字节、改权限、写 B、同步缓存、恢复 RX。之后发布新 epoch 并重新开放登记。

每个调用先在互斥锁内登记，再离开锁并执行 ISB，之后进入真实目标。登记覆盖跳板入口之前的窗口，并持续到完整回调/原函数返回。修改线程的缓存清理与执行线程的 ISB 配合；不能用等待若干毫秒代替。该设计依据 [Arm 关于跨线程自修改代码的说明](https://developer.arm.com/community/arm-community-blogs/b/architectures-and-processors-blog/posts/caches-self-modifying-code-working-with-threads)。

回调中的原函数通过私有跳板执行，按线程记录并在调用前消耗一次机会。重复原调用、回调内安装/关闭、重复安装、旧 generation、活跃状态卸载均明确拒绝。调用包装器保持实际目标/回调返回后的 errno。递归入口、异常跨跳板、longjmp、取消线程和信号场景未作为支持能力开放。

修改时停止接纳新调用，最多等待 100 ms 排空。超时只返回 BUSY，保持原字节、epoch 和所有权。该状态是明确拒绝，不切换后端。实例最多 1024 次变更，只有一个固定跳板页，不提供无限热更新承诺。

`disable` 在相同协议内恢复真实入口。`close` 只允许停用状态，排空调用后清空跳板，再释放模块引用；不使用 NODELETE，不长期 pin 模块。固定槽位的同步对象在进程期保留，旧 generation 无法操作下一次加载；不因地址重用而复活旧句柄。这里验证的是模块显式租约，不是任意库被第三方卸载时的通知协议。

## 验收

保留 H1 的核心和合成页检查，新增以下真机检查：

1. 未安装时 `f(7)=8`；安装后 `f(7)=108`，回调原调用恰好一次；停用后恢复为 8。
2. 观察真实 ELF 入口 B 与保留 BTI；每次写入确认 RW 无 X、恢复 RX 无 W，并核对完整页。
3. 阻塞真实回调，停用等待超时返回 BUSY，epoch/变更次数保持原值，随后释放并正常停用。
4. 四个调用线程并发，安装/停用 50 轮；逐次比较结果、原调用次数、epoch 对应状态和 errno。
5. 拒绝重复安装、回调内变更、活跃卸载及旧句柄；关闭后 `dl_iterate_phdr` 确认模块消失，重载生成新 generation。
6. 当前源码、主机源码、两个 ELF、APK、runId、平台和 maps 关联，保留新的证据目录。

## H3 需要尽早回答的问题

非 debuggable ART 方法拦截仍是关键风险：必须验证实际设备的运行时结构/符号、应用方法与 boot class、原始 caller-sensitive 栈帧、GC/JNI 引用、锁和线程状态、JIT/AOT/内联证据以及选择性反优化。公开 ART TI 的 debuggable 限制不能绕过；Native 协作域不能当作 ART SuspendAll，ART SuspendAll 也不能当作所有 Native 线程停止。

已对设备实际 libart（SHA 与平台门禁相同）做只读动态符号清点：可见 SuspendAll/ResumeAll、ScopedSuspendAll、ArtMethod::Invoke、Instrumentation::Deoptimize 等符号。这只证明 ELF 导出，不证明普通应用 linker namespace 能访问，也不证明结构偏移、调用 ABI、锁或原帧语义正确；没有调用这些入口或安装 ART Hook。

## 真机结果和下一步

最终 runId：`a48ab268-ec77-4e87-ae59-7fa0e40f8589`。普通 non-debuggable / untrusted_app 进程中：

- H1 核心 92/92、匿名合成页 16/16 仍通过。
- 文件映射页 RX→RW 成功，执行同值 volatile store 后 RW→RX 返回 errno 13 / EACCES。
- `TP_OWNED_EXECMOD_DENIED` 在发布前返回；目标入口未改、generation 未发布、模块已实际卸载，最终 maps 无 fixture。
- 整体 `passed=false`，未执行真实 replacement/call-original/disable 或 50 轮并发验收。相关代码仅通过构建，不能算功能验收通过。
- 精确的 SELinux AVC 没有被本次日志保留，结论限定为该固定平台上的 mprotect EACCES，不据此断言具体策略规则。

此前失败记录也已保留：首次准备阶段终止、跨 SO 分支超范围的明确拒绝、增加精确诊断后定位到恢复 RX 失败。最终前置能力检查把同一限制转成可对账的明确失败。

[最终证据与身份核验](evidence/self-hook-h2a-2026-10-15/a48ab268-ec77-4e87-ae59-7fa0e40f8589/README.md)。

下一步必须先重审代码页的所有权和发布模型，再扩展 Hook 功能。研究方向是运行时自有代码页承载与原模块的地址/映射/生命周期契约；先验证该模型能否满足固定平台权限和“不修改活跃未知页”的要求，再回到同一套 H2 验收。不能悄悄改用 RWX、关闭系统保护或切回其他 Hook 后端，也不能将匿名页可执行直接当作原 ELF 入口可替换的证明。

在这个门槛解决前，不将现有 Runtime 适配迁到本接口。产品仍使用原有后端，研究探针没有后端 fallback 路径。
