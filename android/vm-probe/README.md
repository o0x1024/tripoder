# 普通 APK 内 ARM64 Android 验证

这是独立实验包 `dev.tripoder.vmprobe`，用于验证普通应用权限下的完整 ARM64 Android 软件模拟。它不替换 Tripoder 产品运行器。预编译引擎的两次 PAC 崩溃已定位到跨线程恢复协程栈；源码修复版已进入 ARM64 Android 内核、init、Zygote 和 SurfaceFlinger。4 线程、12,293 个协程、73,758 次切换的真机回归已通过；启动动画阶段另一处 SIGILL 已取到故障指令，确认是 CMLE 操作码错误并修复。最终版另通过 100 万次并发跳转及 56 组向量／标量比较回归，客体启动动画已显示。完整日志定位了软件模拟超时和 SurfaceFlinger 显示模式断言，已修正精确 60 Hz 面板，并分离软件执行超时与低内存桌面能力冲突，正在真机重新验证，尚未确认完整桌面。完整启动仍须客体属性、系统服务及画面共同验收。原始失败保留在 [预编译引擎实测](../../docs/evidence/android-software-vm-2026-10-13/result.json)，源码实验见 [设备证据目录](../../docs/evidence/android-source-vm-2026-10-13/)。

## 固定执行边界

- QEMU 5.1.0、GLib、Pixman、libffi、gettext 从固定摘要源码构建，通过直接 JNI 入口在普通 APK 内执行 TCG。唯一引擎为 `libtripoder-qemu.so`；构建和运行均不使用旧 Limbo 动态库。宿主不使用 Root、KVM、虚拟机特权、`pm grant`、Shizuku 或 ADB 激活。
- 客体为 LineageOS 23.2 `virtio_arm64only` 完整系统，采用独立内核、系统盘、数据盘和 EFI 变量盘。它与此版本 QEMU 的组合是否可以启动，必须由本次实验回答。
- Manifest 不申请任何权限，包括网络权限。固定 QEMU 参数禁用虚拟网卡，不配置端口转发、共享文件系统、剪贴板、Binder 转发、Intent 转发或宿主包查询。
- 镜像在首次启动时校验 SHA-256 并复制到应用私有目录。只有完成校验的可写磁盘才会在后续启动复用；不把磁盘重置为初始镜像掩盖启动问题。
- 固定镜像的普通 GRUB 启动项追加 `androidboot.seriallogging.logcat_buffer=all androidboot.low_perf=1`。在同一磁盘事务中，将 `/vendor/etc/init/hw/init.low_performance.rc` 的对应动作改为仅设置软件执行超时乘数 50，并保持首次设置向导禁用；恢复默认 RAM、LMK 与 dexopt 策略，以保留镜像声明的桌面能力。下文说明为何不能直接使用上游低内存配置。
- 修改前核对 GRUB 摘要、目录项、FAT 链及 vendor 文件的完整 inode／内容摘要，写入整盘临时副本后原子替换。vendor 配置保持原长度、inode、权限和 SELinux 标签；独立 GPT/FAT/EXT4 读取及整盘字节审计确认仅允许的配置内容变化，原有客体数据保留。证据见 `docs/evidence/android-source-vm-2026-10-13/normal-ram-profile/boot-config-regression.json`。
- watchdog 继续检查并保留终止路径，默认主线程超时为 3000 秒；延长超时不是启动成功证据。PAC、SELinux、宿主权限和客体 ADB 认证保持原状。客体原镜像本身使用 orange verified-boot 状态，本实验没有将它表述为已验证启动的生产镜像。
- 控制使用私有 Unix socket；触摸转换成客体虚拟输入。每 10 秒截取一次客体 framebuffer 用于启动诊断，**不是帧率测量，也不是产品图形渲染方案**。
- 模拟运行最多 60 分钟，截止时结束验证应用自身进程。第一次镜像导入耗时不计入运行时间。

本原型的宿主 UI 不解析客体 Intent。缺失客体应用时，没有调用宿主应用的接口。此配置边界仍需后续负向测试和模拟器安全审查；不能据此宣称已经完成产品隔离验收。

## 准备与构建

镜像来源和摘要记录在 [dependencies.lock.json](dependencies.lock.json)，原生源码及 NDK 记录在 [native/dependencies.lock.json](native/dependencies.lock.json)。构建产物另有摘要回执；原生输入变化后必须重新构建，APK 打包器拒绝陈旧产物。下载缓存、磁盘、APK 和本地测试签名密钥位于忽略的 `build/`，不提交仓库。

```sh
rtk proxy python3 android/scripts/prepare-vm-probe.py --download
rtk proxy python3 android/vm-probe/native/prepare.py --download
rtk proxy python3 android/vm-probe/native/build-dependencies.py
rtk proxy python3 android/vm-probe/native/build-engine.py --configure
rtk proxy python3 android/scripts/build-vm-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
```

启动配置的磁盘修改回归使用锁定的原始镜像，可独立运行：

```sh
rtk proxy python3 android/vm-probe/tests/verify_boot_config.py \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
```

它独立解析 GPT／FAT 文件内容，并逐字节检查整盘未越过允许修改范围；也覆盖重复执行和未知配置拒绝写入。

## 软件模拟器启动配置的依据

完整日志在 `run-1791896888502` 捕获了 watchdog 主线程终止：系统仍依次启动显示、存储、区域设置、语法和应用休眠服务，在 `AppHibernationService` 构造期间超过 60 秒。之前包管理器已完成 188 个应用的数据核对；主线程持续推进，未出现原生引擎故障。堆栈及服务序列见 `docs/evidence/android-source-vm-2026-10-13/full-log-diagnostic/watchdog-main-stack.txt`。

先验证了镜像上游的 [init.low_performance.rc](https://github.com/LineageOS/android_device_virt_virt-common/blob/lineage-23.2/configs/init/init.low_performance.rc)。[Build.HW_TIMEOUT_MULTIPLIER](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.2/core/java/android/os/Build.java) 明确用于比硬件慢得多的软件模拟环境；[Watchdog](https://github.com/LineageOS/android_frameworks_base/blob/lineage-23.2/services/core/java/com/android/server/Watchdog.java) 只对默认检查超时应用该乘数，自定义检查仍使用自己的阈值。必须继续观察服务推进及最终桌面，不能用延长后的存活时间替代验收。

镜像内实际配置也已从锁定 QCOW2 的 vendor/ext4 只读提取，内容与上述设置一致，保存为 `docs/evidence/android-source-vm-2026-10-13/software-profile/image-init.low_performance.rc`。复查命令是 `rtk proxy python3 android/vm-probe/tests/read_vendor_config.py`，不挂载或写入原镜像；真机串口已记录该 init 动作执行。

精确 60 Hz 面板轮随后推进到 phase 600，但 SystemUI 两次因 `Can't create root task for unsupported windowingMode=5` 崩溃：低内存属性使框架关闭多窗口支持，镜像编译时的 tablet desktop/freeform 声明仍然开启。软件执行慢与内存容量是两个独立条件，不能用低内存模式代替软件模拟配置。

当前 `GuestSoftwareProfile` 因此在已校验的私有磁盘中派生仅含超时和原有向导设置的配置，保留正常 RAM 与桌面能力；不修改 Task 检查、不强制开启多窗口、不关闭安全机制。配置文件以注释补齐原长度，避免修改文件系统元数据。原始下载镜像不变；原始、前轮日志配置和前轮低内存配置分别按确切摘要迁移，未知内容拒绝启动。仅追加 `androidboot.hw_timeout_multiplier` 不可替代该修改：本镜像没有将 `ro.boot.hw_timeout_multiplier` 转为 `ro.hw_timeout_multiplier` 的 init 桥接。

不传 `--download` 时只使用并验证本地缓存。构建要求 JDK 17+ 和已安装 SDK，生成 `android/vm-probe/build/vm-probe.apk`。验证包 minSdk 33、targetSdk 36、`debuggable=false`、仅包含 `arm64-v8a`；当前不对其他 Android 版本作运行承诺。依赖许可随 APK 的 `assets/licenses` 保存；本实验不替代将来公开分发所需的完整源码和镜像许可审查。

## 部署与取证

以下 ADB 命令只用于开发部署、输入固定的验证动作和取回证据，不向运行器授予额外能力。手机系统的安装确认需要用户在手机上完成。

```sh
rtk proxy adb install --no-incremental android/vm-probe/build/vm-probe.apk
rtk proxy adb shell am start -W -n dev.tripoder.vmprobe/.VmActivity
rtk proxy adb push android/vm-probe/build/media/. /sdcard/Android/data/dev.tripoder.vmprobe/files/import/
```

此时可直接在手机点击「准备并启动客体」。每次运行在应用外部专属目录生成 `run-<时间戳>/`；`latest-run.txt` 给出目录名。客体磁盘在应用内部目录运行，不从导出目录直接挂载。当前大镜像用开发工具预置，尚未实现面向用户的离线安装包交付或镜像导入流程。

必须先启动应用创建 `import` 目录，再传入镜像。不要在安装前由 ADB 创建该子目录：本机实测该目录会归属 shell，普通应用无法访问。不能通过额外权限授权代替正确的文件部署。

自动取证入口只接受四个固定动作，不接受任意宿主命令或 QEMU 参数：

```sh
rtk proxy adb shell am start -W -n dev.tripoder.vmprobe/.VmActivity --es probeAction start
rtk proxy adb shell am start -W -n dev.tripoder.vmprobe/.VmActivity --es probeAction inspect
rtk proxy adb shell am start -W -n dev.tripoder.vmprobe/.VmActivity --es probeAction snapshot
rtk proxy adb shell cat /sdcard/Android/data/dev.tripoder.vmprobe/files/latest-run.txt
```

使用实际返回的目录名执行 `adb pull`，保存 `run.json`、`qemu.txt`、`serial.txt`、`console.txt`、`guest-logcat.txt` 和 `screen.ppm`。报告中的 `emulator-running` 只表示 QMP 可响应；`fullAndroidBootVerified` 不会因这一状态自动改为 true。

若原生进程提前崩溃，部分日志和画面可能未生成，`run.json` 也可能停留在 `starting`。需要合并同一 PID 的系统退出记录与 native crash 记录判断终态，不能把旧状态当作仍在运行。预编译版本两次崩溃栈均为 `libc.so siglongjmp+428 ← qemu_coroutine_switch+100`。源码诊断捕获了不同保存线程和恢复线程；修复将协程池限制在线程内，首次执行时才初始化栈，并在任何跨线程恢复前直接失败。PAC、目标 SDK 和普通应用权限边界均保持不变。

停止可点击手机按钮，或发送固定的 `probeAction stop`。若模拟器无响应，开发侧可明确执行 `adb shell am force-stop dev.tripoder.vmprobe` 结束本次实验。重开应用后可使用原客体数据盘再次启动。

## 分阶段通过条件

1. 启动：客体 `uname` 为 ARM64，`sys.boot_completed=1`，Activity、Package、SurfaceFlinger 服务存在，`pm path android` 返回客体系统路径，画面进入 Android 桌面。随后断开电脑，从图标启动、停止和重启，验证数据持久化。这些结果齐备后才确认普通 APK 路线能在本机运行完整 Android。
2. 边界：在客体安装自有双应用样例，验证组件、回调与缺包行为；宿主同包应用不能参与解析。无网络原型不等于产品网络隔离已验证，联网能力须单独设计和测试。
3. 图形：以客体 GLES／Vulkan 测试的帧时间、渲染器名称、CPU、内存、温度与输入延迟判断性能，不以截图刷新率代替客体帧率。当前配置未实现硬件 GPU 转发。
4. Hook：先对客体自有 APK 验证 Java 与 Native Hook，随后验证进程重启及卸载。当前 `user` 镜像不自动拥有任意客体进程注入权限；该能力需要明确的客体侧方案，不得引入宿主提权。
5. 第三方登录：前述条件通过后，再在同一客体安装微信与王者荣耀，由用户使用测试账号完成登录闭环。

产品接入前必须停用现有同 UID 游戏执行路径，后端失败时直接拒绝启动。这个实验包尚未改变现有产品行为。
