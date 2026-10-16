# H2b Native 线程暂停协议（原语已验收）

日期：2026-10-15。当前 H2a 依赖调用方主动进入协作域，不能拦截任意 Native 调用者。独立进程中的暂停原语已完成 JIT/AOT 验收，尚不据此发布代码页。[证据](evidence/self-hook-h2b-2026-10-15/README.md)。

采用一个明确保留的实时信号；只向 `/proc/self/task` 中属于自身 PID 的 TID 发送带 epoch 的信号。已有信号处理器、资源预算不足或线程不响应时明确拒绝，不挑选其他信号或后端。处理器和固定槽位保留到独立探针进程退出，避免迟到信号指向已释放代码。

处理器仅使用锁自由原子和直接系统调用，保存 PC/SP/LR 后停在 futex；处理器期间屏蔽其他可屏蔽信号，不运行 JNI、Java、脚本、分配器或日志。协调者等待已知线程暂停后，用预打开目录 FD 和固定缓冲区重新枚举线程，直到线程集合封闭；新线程也必须暂停，未知/复用身份或超时均不准进入发布阶段。

暂停后的关键区只能使用预先准备的数据、原子和经过审查的直接系统调用。不能直接复用现有 code_page 的完整发布函数：其中的 maps/smaps 读取、stdio、分配与故障日志可能等待被暂停线程持有的锁。后续需要把候选页准备、最小提交和恢复后的核验分开。

释放通过 epoch + futex 唤醒，等待已进入处理器的线程退出协议。超时取消必须释放已暂停线程，迟到的旧 epoch 直接忽略。不能因计数为零就释放 handler 或槽位。冻结线程仍可能持有已有函数栈帧/返回地址，因此暂停原语本身不证明跳板可回收、模块可卸载或 ART 方法类型可改变。

验收先覆盖 Native 忙循环、创建/退出线程、阻塞保留信号导致的拒绝、取消后的迟到信号和多轮恢复。全程只操作 `dev.tripoder.selfhookprobe`，不暂停产品进程。该阶段完成前不改代码页，也不宣称非协作 Hook 已完成。

## 已实现的边界

- 固定保留信号 60；已有 handler 时拒绝初始化，每次暂停前重新检查保留权。要求进程内其他组件不并发改写这个保留信号的 disposition；检查本身不是跨组件原子所有权协议。
- 256 个静态槽位、1024 个 epoch、最多 16 次线程集合扫描、100ms 准入期限。epoch 与状态通过同一个 64 位原子 CAS 发布，取消不能与旧 handler 错配到新槽位。
- 原生工作线程无需调用 enter/leave；处理器记录真实 PC/SP/LR。候选线程全部暂停后重新枚举，只有没有新线程的闭合集合才准入。
- 超时先取消未进入的槽位，再唤醒已进入的 handler。取消后的信号按 epoch 忽略，计数按 epoch 分开；恢复过程 1 秒仍不能结束则直接退出独立进程，避免将不明状态交还调用者。
- 每次构建检查关键区 ELF 反汇编中的直接调用白名单和间接跳转；暂停后路径没有 libc/JNI/分配器/日志调用。该检查只证明调用集合，控制流和生命周期仍由源码审查负责。

## 线程创建时的拒绝是必要结果

首次压力测试要求所有线程创建场景都能暂停，实测遇到超时。增加取消前的只读 `/proc/self/task/TID/status` 后，JIT/AOT 均捕获 `SigBlk=fffffffffffbfeff`，其中包含信号 60。AOSP Bionic 的 `pthread_create` 在 clone 前屏蔽信号，子线程等待启动握手后才恢复信号掩码；暂停创建者可能使子线程暂时无法到达恢复掩码的位置。这里的 AOSP 顺序用于解释现场，不能替代 OEM libc 的全部实现证明。

因此创建压力验收允许两种显式结果：所有线程确认暂停后准入；或遇到未响应线程拒绝本次操作，并验证全部普通工作线程继续前进。不会忽略阻塞线程、切换信号或自动重复同一次发布。本次 JIT 为 17 次准入/3 次拒绝，AOT 为 16 次准入/4 次拒绝；各另外通过 20 次普通暂停、1 次故意阻塞取消和 1 次取消后新 epoch。长期屏蔽信号的第三方线程会使该进程的发布被拒绝。

## 规范依据

- [Linux rt_tgsigqueueinfo](https://man7.org/linux/man-pages/man2/rt_tgsigqueueinfo.2.html)：带值的单线程信号。
- [Linux futex](https://man7.org/linux/man-pages/man2/futex.2.html)：原子比较与等待。
- [Linux async-signal-safety](https://man7.org/linux/man-pages/man7/signal-safety.7.html)：信号上下文限制。
- [AOSP Bionic pthread_create](https://github.com/aosp-mirror/platform_bionic/blob/main/libc/bionic/pthread_create.cpp)：启动握手与信号掩码顺序。
