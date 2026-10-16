# 固定 ART Java native 声明调查

本目录是当前固定 `libart.so` 的只读结构证据，SHA256 见 manifest；没有执行 Java native Hook，也不证明全部 writer 闭合。

- `RegisterNative` RVA 0x456080：RuntimeCallbacks 返回后，0x4560e4 起读取最终绑定；普通 data 写入、Critical 延迟表和返回指针都由栈槽 SP+8 驱动。只在函数返回后改 data 会漏首个 lookup 调用。
- `UnregisterNative` RVA 0x85e0f4：恢复 normal/critical lookup stub，Critical 删除延迟绑定。
- `MarkVisiblyInitialized` RVA 0x22e0c0：0x22f214/0x22f21c 从延迟表发布到 ArtMethod.data。
- 普通 native 的 ArtMethod::Invoke 回到 quick；generic JNI 在 0x2c86e0 读取 data，0x2bc7a0 BLR 实现。

实现必须保留 Native/Fast/Critical flags。普通/Fast JNI 的参数包括 JNIEnv 和 jobject/jclass handles；Critical 只有 primitive ABI，缺少普通 JNI 帧，不能直接接现有 Java dispatcher。每次注册需要不可变绑定与真实模块代次、每调用 lease、原调用 token；并发重注册和退役迟到调用不能共享可变 currentOriginal。

反汇编的标题可能显示最近的 dynsym 而非内部真实符号；这里以已合并 GNU debugdata 符号对应的 RVA/size 为准。静态调查之外仍需首次解析、重注册、Unregister 后解析、Critical 延迟发布、同步/pending exception/moving GC 的自有设备验收。
