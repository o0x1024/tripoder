# P3 统一存储契约

2026-10-14。独立实现，不使用 VirtualApp 代码。当前本文是实施契约，未完成真机验收的部分不得计为 P3 通过。

## 身份与范围

安装权威唯一决定 space/package/installGeneration/dataId，ProcessSession 增加不可变 processGeneration。升级保留 dataId；卸载后重装生成新 dataId。工作台和客体不能选择物理根路径。RuntimeStorage 从同一 bootstrap 校验这些身份与物理目录的对应关系，Java 与后续 Native 入口共用这份策略。

P3a 首先实现 Context 文件、SharedPreferences 与真实 Android SQLite。文件错误和损坏明确返回错误，不读取宿主同名文件、不生成空配置掩盖损坏、不删除损坏数据库重建。文件输出支持 PRIVATE 与 APPEND；世界可读、设备保护区及跨 Context 移动不在首批范围，明确拒绝。

SharedPreferences 使用 dataId 下真实 XML 文件，进程内同名共享一个对象。类型、集合副本、clear/remove、commit/apply 与主线程监听保持 SDK 语义。提交队列按内存更新顺序写盘，临时文件 fsync、原子 rename、父目录 fsync 后才报告 commit 成功。损坏输入不当作首次启动。初期仅允许一个存活进程拥有文件；2026-10-15 改造契约见下方多进程一节，旧验收记录保留。apply 的未完成写入接入 Android 生命周期 finisher，显式停止也等待写盘。

SQLite 使用 Android SQLiteDatabase，保留真实事务、锁、WAL/SHM。默认 corruption handler 抛错并保留原文件；用户显式传入的 handler 属于应用自身决策。Context API 的数据库路径归入同一 dataId。直接 SQLiteDatabase.openDatabase、ATTACH 与 Native 原始路径要到 P3b 的统一路径入口才形成闭环，P3a 不宣称封闭这些入口。

路径检查拒绝静态符号链接和越界名称；Java 返回 File 后，不能保证随后由应用发起的任意路径操作受控。检查后再次打开也存在时序窗口，因此不得将该层称为安全隔离。P3b 应使用目录 FD 锚定和 openat 等入口处理相对路径、dirfd、rename 双端、别名及符号链接策略；不以字符串替换实现 VFS。

## 后续层次

### 2026-10-15 偏好多进程事务

微信第六次启动停在 `PREFERENCES_MODE_UNSUPPORTED`。实际 flags 尚未由该旧日志记录；新错误将包含十六进制值。已经确认当前设计完全拒绝 MODE_MULTI_PROCESS，且 MODE_PRIVATE 也排斥第二个进程，无法支持通用多进程应用。

新实现统一使用同一 XML 存储，不增加第二后端：每次读、写、删除在稳定 `.lock` inode 上持真实内核锁，最长等待 5 秒，超时明确失败；进程死亡由内核释放。写入在锁内读最新已提交版本、应用本次 Editor 修改，再执行原子持久提交。不同键的并发写入不相互覆盖；同键以磁盘事务顺序决定，clear 清除该事务可见的值。read-modify-write 并非跨进程原子事务。

MODE_PRIVATE 保持进程内缓存；再次以 MODE_MULTI_PROCESS 获取时，先等待本进程已接受的写入，再持锁重新读取，返回原对象。其他进程提交不会生成虚假本地监听通知。损坏 XML、写盘失败、非法模式仍明确失败；删除不删除锁 inode，避免两个进程使用不同锁。单进程保持一个 writer，不能由文件数扩大线程数。

自研验收增加两个不同 PID 同时提交 50 个不同键，时间区间必须真实重叠；验证 101 个值完整、PRIVATE 缓存保持、MULTI_PROCESS 同对象刷新、原有两空间/重启/升级/损坏/SQLite/Native 检查。`preferences-multiprocess-01` 已通过 280 项（run `p3-88f5850fa5894b75`，Runtime APK `c87dfefa241efc49d3312c0e24bbd785ae2e2280680ee25fe8d80553fb09e142`）；两个空间的首次、重启、升级六轮均实际重叠写入，全部值保留。此结果没有覆盖强行杀死正在提交的进程或文件系统故障注入。

平台语义参考：[ContextImpl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java) 在显式多进程获取时请求重新载入。Tripoder 的事务合并是自身明确契约，不宣称与平台所有并发竞态相同。未使用 VirtualApp 实现。

P3b：JNI 自己构造逻辑路径，统一映射 open/openat/stat/rename/unlink/目录查询；路径不支持或符号缺失在执行前拒绝。P3c：真实 FD 的 dup/close/fcntl、rename/unlink 后持有、mmap 后关闭、MAP_SHARED/msync、只读制品与跨进程 SQLite/WAL。P3d：复用 Provider 调用身份和操作日志，传递真实 ParcelFileDescriptor，绑定 URI、mode、grant 与代次。

当前同 UID 的 libc Hook 无法阻止恶意代码直接 syscall 或使用宿主物理路径。P3 功能通过不等于强隔离。允许使用宿主硬件服务的策略必须逐项登记，未知系统服务仍拒绝。

## 首批验收

- 同包两个空间同名文件/prefs/数据库互不影响，宿主同名诱饵保持原值。
- stop 后新 PID/新 processGeneration 仍读到原数据；dataId 保持。
- 文件 append、删除、列举、非法模式/名称、静态 symlink 明确表现。
- prefs 全部 SDK 类型、监听主线程、集合防别名、clear/remove、异步后同步提交顺序、损坏拒绝、第二进程缓存拒绝。
- SQLite 事务回滚、持久查询、WAL、错误模式、损坏时不删除重建。
- 每次验收绑定源码摘要、APK、固件、PID/UID 与空间，未覆盖的直接 Native/SQLite 路径单列，不记为完成。

## P3a 第一轮设备结果

`storage-01` 172 / 172 通过，runId `p3-0b6d64ab91d2489d`，APK `49c3184764623535cab1fda5cff76845883cf7ab6772853519e456d8f9bbd005`。两个空间分别执行首次启动、停止后重启和同签名 v1→v2 升级三轮，文件/prefs/SQLite 值保持且互不污染；升级改变安装代次、保留数据目录。六轮跨进程 SQLite WAL 读写均通过，第二进程打开同一 prefs 得到明确所有权拒绝。损坏 prefs XML 与数据库都保留原始字节；宿主同名文件、偏好和数据库诱饵保持原值。

该结果只覆盖 P3a 本文列出的工作负载。尚未验证断电/fsync 失败及偏好写入中崩溃；P3b/c/d、通用文件操作和系统服务策略仍未完成。不是 P3 全量或真实应用验收。

后续审查把每偏好文件一个线程改为每进程一个串行写入队列，避免真实应用偏好文件数量增加导致线程失控；deleteSharedPreferences 对已不存在文件也返回成功，删除排在已接受的 apply 后执行。`storage-02` 190 / 190（`p3-b0113326aa2243cb`），APK `bc6d53679cad476b535d72598a9cdf73bfeb0178328d2c668a777a3974f037e1`。同构建 P2 回归 193 / 193（`p2-storage-regression-01` / `p2-96ef0ed06bfb4154`）。P3 的总阶段状态仍为未完成。

## P3b 接入前的设备入口审查

当前固件 libc SHA-256 为 `b963b539545b64aa8fbcaed3228d04bcb6466bfd47055aa16e4ccdcadfdc461e`，只读提取与 ELF 符号清单见 `evidence/runtime-p2-plus-2026-10-14/native-libc-preflight.json`。open/open64 和 openat/openat64 各自共用地址；本机 open/fopen/opendir 最终经过内部 `__openat`。因此不能按导出名逐个重复替换，也不能仅拦截 JNI 的一两个调用点。

拟在 Android Application 启动前，以确定的 libc 摘要和真实符号建立必需入口集合，再接入独立 NDK 编译的 Native 路径策略。优先覆盖本机系统调用薄封装 `__openat`、fstatat、unlinkat、mkdirat、renameat2、readlinkat 与访问检查；每个签名逐一验证，缺失即拒绝启动。运行期 C 层处理路径/errno，不让每次文件访问都回入 JavaScript 或 Binder。内部路径解析使用真实目录 FD，避免向内核提交未验证的字符串拼接结果。

逻辑数据目录与别名来自同一 RuntimeStorage 身份；code/artifacts 只读；平台只读文件与明确的设备节点单独列出，未知私有路径拒绝。Java 返回的 File、JNI 构造的逻辑绝对路径、cwd 与 dirfd 均须进入同一解析规则。符号链接、rename 双端、relative `..`、已删除目录 FD 必须明确覆盖或拒绝，不允许先改写一部分入口再声称完整 VFS。

此段仅是入口审查和下一批契约，尚无 Native Hook 实现或验收结论。直接 syscall 的同 UID 越界仍需保留负面证据；禁止用 libc 拦截通过取代强隔离验证。

### 独立 C 策略测试

已独立实现 `path_policy.c` / `io_adapter.c`，NDK `-Wall -Wextra -Werror` 编译通过。`native-policy-01` 在设备 shell UID 2000 的本任务临时目录中通过 25 项真实内核测试：两个逻辑别名共享字节、目录 FD 相对读、缺失父目录与 symlink 不被 `..` 文本归一化抹去、根越界/制品写入/未知私有路径拒绝、末尾斜杠不绕过 symlink、dup 后关闭原 FD、rename/unlink 后已打开 FD 保持、mmap 后关闭 FD 与 msync/munmap。所有路径和文件均属于自研测试目录。

该测试显式记录 `hookInstalled=false`、`guestExecutionVerified=false`，当前 Native 策略尚未链接到 Runtime APK，不代表 Application 进程中的 libc 拦截通过。相对 dirfd 首段 `..` 当前明确拒绝；外部文件、完整平台能力与所有路径别名、直接 syscall、已有/跨进程传入 FD 仍须后续处理。源码与测试程序摘要见独立 `result.json`。

### 首次运行器内接入

下一构建将同一 C 策略封装为 `libtripoderio.so`，在 guest Application 之前装载；Gadget 校验设备 libc SHA、符号地址与签名集合后替换 10 个内核入口薄封装。别名地址不重复 Hook，路径解析/errno 在 C 层完成；不经 JavaScript/Binder 做逐文件调用。Native 配置来自 RuntimeStorage 的 raw/canonical data/artifact 路径及 package，不能切换身份。制品只读，受控逻辑私有目录对应真实目录 FD，未知私有路径返回 EACCES，未实现的链接创建返回 EOPNOTSUPP。

`storage-native-01` **274 / 274**，runId `p3-478190c00bed4220`，APK `277c94c3d9a0a9779ac3f715cbebee1c4d5e1095f9d4dd31c74900b19780fd6d`。两个空间的首次、重启和升级六轮均在真实 guest 进程中执行：JNI 只收到逻辑包名和数据，自行构造 `/data/data/<package>` 与 `/data/user/0/<package>`；真实 mkdir/open/openat/rename/unlink、跨别名读取、只读代码拒绝、dup 后关闭、已 unlink 的 FD、mmap 后关闭与 msync 通过。Java 从 Context 目录读到相同字节。此前 prefs/SQLite/宿主诱饵断言全部仍通过。

这仍不等于 P3 完成：未涵盖外部资源路径、传入 FD/URI 授权、完整 FD provenance、全部系统文件能力、所有 libc/path 调用和直接 syscall；relative dirfd 初始 `..` 明确拒绝。当前 `/proc` 的文件内容打开禁止 root/cwd/fd magic-link 路径，完整元数据与授予的 FD 能力仍在后续范围。对真实应用和强隔离均没有通过结论。
