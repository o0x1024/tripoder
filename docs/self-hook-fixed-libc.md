# 固定 libc 存储入口与两页事务

2026-10-16。当前 Runtime 在客体首条代码前安装十个固定存储入口，与 Java/Native Hook 共用一份 `libtripoderselfhook.so` 和一份路径策略。固定 libc 路径不再需要近地址 relay。平台版本、完整函数体和页面身份不匹配时明确拒绝，没有备用实现。

H14 当前独立验收：APK `7ba280b7f5ac1b50d2532feb26317a19d7f93dbbbd55df4afc59e72c25458cf8`，run `f4f09a4b-8eae-4e20-8a86-cd8c4abdd84f`，24 项安装/恢复检查、24 项真实 IO 和 408986 次并发调用通过；原始页面、暂停快照、安装/恢复结果及独立核验见 [H14证据](evidence/self-hook-h14-2026-10-16/f4f09a4b-8eae-4e20-8a86-cd8c4abdd84f)。Runtime 的跨空间存储长流程另行验收，见 [当前状态](self-hook-handoff-2026-10-15.md)。

[H2d JIT/AOT 证据](evidence/self-hook-h2d-2026-10-15/README.md)是旧近地址实现的历史记录，不代表当前产品存储实现。

## 入口与页所有权

固定 libc SHA256 为 `b963b539545b64aa8fbcaed3228d04bcb6466bfd47055aa16e4ccdcadfdc461e`，Build ID 为 `48c4b18304acf519b26457ccbc3d435f`。主机读取该 ELF 的真实符号表、LOAD/NOTE、每个完整六指令函数体和两个源页；设备先验证整个系统文件的 SHA，再核对已加载模块路径、Build ID、可执行段、完整函数体与文件页身份。未知版本明确拒绝。

| 固定入口 | RVA | 编译后的替换签名 |
|---|---|---|
| __openat | 0xe0a40 | int(int, const char*, int, unsigned) |
| fstatat | 0xe0b40 | int(int, const char*, stat*, int) |
| unlinkat | 0xe0d00 | int(int, const char*, int) |
| mkdirat | 0xe0bc0 | int(int, const char*, unsigned) |
| renameat2 | 0xe0c80 | int(int, const char*, int, const char*, unsigned) |
| __faccessat | 0xe0a80 | int(int, const char*, int) |
| readlinkat | 0xe0c40 | ssize_t(int, const char*, char*, size_t) |
| chdir | 0xe1040 | int(const char*) |
| symlinkat | 0xe0cc0 | int(const char*, int, const char*) |
| linkat | 0xe0b80 | int(int, const char*, int, const char*, int) |

这些入口均是 `BTI c; MOV x8,#syscall; SVC; CMN; B.HS errno; RET`。保留原 `BTI c`，将 +4 至 +20 的四字改成 `LDR x16, PC+8; BR x16; 64位目标地址`，十入口共40字，两个共享页其余字节保持完整。替换目标有 BTI 入口；绝对尾跳只使用 AAPCS64 临时寄存器 x16，没有移动原 syscall 或复制原 trampoline。

主机构建审核157336条指令、9294个符号及1609项RELA，确认没有指向被覆盖窗口内部的静态分支、符号或重定位。独立 NDK assembler oracle 核对完整20字节。全文件摘要门禁将该结论绑定到设备实物，不推断其它 libc 版本。

共用 H2c 的完整页事务实现，来源页和候选页都保留 `VmFlags: bt`。新执行页为 RW 准备后 RX|PROT_BTI；不申请 RWX。MTE 代码页仍拒绝。恢复时，两个原文件 RX|BTI 视图也在同一个闭合线程集合中同址提交，再核验原 inode/device/offset/权限与全部字节。

## 两页提交与在途旧调用

暂停前准备两个完整候选页。暂停后检查闭合线程集合、每个真实 PC 及两个完整 preimage，然后一次提交两页；中途出现不明结果立即终止本进程。只有两页完成并恢复线程后才能返回 ready。

旧 syscall 的恢复 PC 可能位于新 literal 中。首次宽补丁并发测试实际触发 SIGILL，失败证据保留为 `513d39bb-0ec3-4ab7-8aa8-f7279563c8f1`。当前提交和恢复都会拒绝 PC 位于任一入口 +8 至 +20 的快照：不改页面、不改 PC、不重放调用，恢复线程让旧调用自然完成后重新获取完整快照。最多64次且总计1秒，耗尽明确报 IN_FLIGHT。新的观察仍需通过闭合线程集合和完整页核验。

策略根 FD、已编译 callback 及安装所有权保留到进程退出。该路径只在客体执行前启动一次；不能据此声称运行中任意策略可以瞬时切换或任意系统库可卸载。

## 自有环境验收

- 同一构建中重跑既有 H1/H2a/H2b/H2c/ART 测试。
- 使用本探针私有目录和外部专属证据目录，复用 `path_policy.c` / `io_adapter.c`。
- 真实 FD 内容、stat、访问、创建目录、rename、readlink、chdir、unlink，以及既定的 link/symlink 拒绝。
- 只读制品、相对路径、错误 errno、缺失父目录/..，及自有外部测试路径的策略拒绝与停用后恢复。
- 四个 Native 线程在激活前、激活期间和原文件映射恢复后直接调用 libc 入口。
- 第二页的事务预期字节注入一次错误，两个完整页面均未发布，调用者全部恢复；随后独立执行正常激活。
- 保存原始/活跃/恢复后的完整 8192 字节，独立核验只有十个入口的40字绝对跳转编码发生变化；共享页内未替换的 fchmodat 也实际执行。
- maps/smaps 核验页来源、RX 和 BTI；这个阶段不声称已经做过 BTI 非法落点故障 Oracle 或完整 PAC/MTE 验收。

当前产品 bootstrap 已接入此存储实现并完成25项自研安装；产品构建不再打包或加载 Frida。完整组件、跨空间存储和控制长流程的最终验收状态以逐构建证据为准。同UID不构成内核安全隔离，本文不承诺拦截直接syscall或抵御恶意客体。
