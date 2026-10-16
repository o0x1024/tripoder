# 固定ART中央入口所有权

2026-10-15。当前核心已真实安装固定`UpdateEntryPoints`，采用原位置保存栈帧、单一CFA的近地址ELF。完整直接写入闭包和产品替换仍未完成。H3j的预链接leaf、H3k的entry+4/三段FDE是历史验证，不是当前自动选择路径。

## 从原函数职责确定补丁点

固定libart SHA为`2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13`。`UpdateEntryPoints`（RVA `0x514d8c`）为自由函数，x0是ArtMethod，x1是请求入口。它负责比较、CAS和旧JIT代码zombie记账。拦截只替换请求参数，不删除原逻辑。

| 原位置 | 指令与处理 |
| --- | --- |
| entry+0 | PACIASP，原位置保留 |
| entry+4 | STP FP,LR,[SP,-32]!，原位置保留 |
| entry+8 | STP x20,x19,[SP,16]，此处单条B进入近地址leaf |
| leaf | 有界64槽精确查询、acquire读取owner的desired，只修改x1 |
| leaf末尾 | 重放STP x20,x19,[SP,16]，B返回entry+12 |

leaf不执行BL、PAC或AUT，不修改SP、x16/x17/x18或SIMD。FP/LR已经在原位置保存；x19/x20仍保持原值，因此leaf内所有PC均可用同一`CFA=SP+32`、原签名LR及x19/x20 same_value规则展开。原函数在entry+12继续，使用其原FDE中的保存槽规则。

这使当前路径无需把一个极短的“STP后、B前”PC当作随机信号采样门槛。没有扩大信号采样范围、移动真实PC或取消认证。旧+4设计曾通过真实展开，但再次出现20,000次尝试未采中另一状态的运行；保留失败记录后，改为消除leaf中的CFA变化。

未知前导、分支越界、完整页或函数不符均拒绝。固定整个.text扫描没有进入被替换entry+8的直接B/BL；这不是任意动态指针的通用控制流证明。传统classic PAC规划器的unknown/PAuth_LR/B-key拒绝规则没有放宽。

## 代码、展开信息与页面一起拥有

`near_entry_leaf.c`只加载构建时嵌入的模板。先在直接B范围内排他保留地址，再在文件字节中填入回跳及registry地址，随后对memfd加15号完整封存集合（WRITE/GROW/SHRINK/SEAL）。通过`android_dlopen_ext`在保留区加载真实ELF，动态加载器识别GNU_EH_FRAME；代码可被调用前，实际NDK查询全部23个PC均落入唯一FDE。

没有TEXTREL、依赖或init/fini；加载后不改执行代码。模板、映射、fd及FDE保留到进程退出，最多16次租约尝试。加载器失败后的保留区所有权不作猜测，不盲目munmap未知映射。

`entry_branch_page.c`复用现有完整页身份、RW候选转RX、非协作Native线程暂停、直接syscall提交及cache同步。仅entry+8的一个字改变，其他4092字节保持。实际源ART页无BTI，发布页保持无BTI；新ELF启用BTI。验证要求源页、保留原文件视图和发布页属性准确一致，不能从PAC指令推断源页有BTI。MTE代码页仍拒绝。

## 一个owner，一份状态

registry直接指向现有entry中的原子desired，不回调C或Java，不复制active/disabled权威。不可变policy及其Method/Class/loader根保留到进程退出。已拥有但desired非法时不能透传写入。

prepare只准备代码和根；首次enable先发布中央writer，然后在JIT暂停及SuspendAll中领取进程解释租约，注册owner并提交desired/quick/active。初次writer提交早于任何managed目标被拥有，随后的JIT排空与mutator暂停关闭旧writer在途窗口。后续全部目标复用同一中央安装和原子视图。

报告中`writerInterpositionInstalled=true`仅表示中央writer已安装；`completeEntryWriterClosure=false`和`fullCallerCoverage=false`仍保留。当前唯一编译策略见[进程解释租约](self-hook-process-interpreter.md)，它解决未来内联，不能代替writer闭包。

## 实际验证及边界

[H3k证据](evidence/self-hook-h3k-2026-10-15/README.md)已包括真实中央writer的64次Reinitialize、64次UpdateMethodsCodeImpl和4次全局JIT cache失效；原函数均执行。`InvalidateAllCompiledCode`属于JitCodeCache，不能传Instrumentation对象。最新统一CFA构建见[H3l证据](evidence/self-hook-h3l-2026-10-15/README.md)。

当前自有入口测试执行40万次调用、10万次desired发布、512次实际异步展开，完整文件页恢复；整份封存ELF及原页/发布页逐字节独立比较。展开在被采样的自有无锁Native worker上执行，observer仅落盘。当前设备观察到线程PAC签名域不同，不能让observer认证worker栈；测试不修改key或enable状态，也不宣称任意信号调用者都安全。

仍须完成：

1. obsolete method直接store `0x862eec`、AddImageSpace visitor `0x60d1c8`、JIT cache特定JNI路径的`0x8f6aa4`和vendor HyperArt的触达/归属证明。
2. 未来AOT/image发布、MethodHandle/JNI及在途帧完整矩阵，不能从类快照或中央writer推导全部完成。
3. caller-sensitive原帧与全部固定产品成员/安装器、加载器卸载和产品性能验收。
4. 所需能力全部验收后，接入既有Broker/操作身份/审批/UNKNOWN和Engine，并移除产品Frida。当前不发布产品READY。
