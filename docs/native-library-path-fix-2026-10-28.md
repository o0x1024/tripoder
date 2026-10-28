# 客体 native 库搜索路径修复与六应用复测（2026-10-28）

承接 [六应用诊断方案](six-app-runtime-diagnosis-2026-10-27.md) 与 [腾讯视频启动问题与 CMB 短名复跑进度](qqlive-startup-investigation-2026-10-28.md)。本文记录一个此前被各应用各自归因的系统性 bug 的定位、修复与复测结果。**这不是 Smoke/Release Gate 验收**：所有运行仍是 `diagnose-six-app-startup.py --guest-only` 的 30 秒启动存活检查，未登录、未执行业务操作、未清数据。

## 结论

六应用此前被分别记录为"招行 SIGSEGV""芒果TV SIGSEGV""其余应用的各种崩溃"，实际上至少有一部分共享同一个根因：**客体第一次加载自己的 native 库时，Tripoder 给它配的 linker 搜索路径本身就是错的**，与应用是谁、崩的是哪个 `.so` 无关。这个根因已经定位、修复，并在六个真实应用上复测。修复后，四个应用（招行、淘宝、腾讯视频、拼多多）在本轮 30 秒冒烟里稳定通过；芒果TV 从"确定性失败"变成"20 次里 19 次通过"；网易云仍然失败，但失败原因已经与这个 bug 无关,指向它自己的 native 防护层。

## 根因

`RuntimeEngine.java` 构造 `RuntimeSharedLibraries` 时传入的是 `applicationInfo.nativeLibraryDir`——这是 `GuestPackageManager` 为客体"自己看自己"伪造的一个**从不存在于磁盘上**的路径（形如 `/data/user/0/<pkg>/code/<uuid>/lib/arm64`，供客体通过 `getApplicationInfo()`/`PackageManager` 读到一个"看起来正常"的值），而不是真正解压了 `.so` 文件的 `info.nativeLibraryDir`（真实路径，由 `RuntimeExecutionCode`/`ArtifactSet` 在安装时物理生成）。

`RuntimeDexLoader.createForGuest()` 恰好把这个传错的值（经由 `sharedLibraries.nativeSearch`）直接配置成客体 classloader 的真实 linker namespace 搜索路径。结果是：**客体第一次 `System.loadLibrary()` 自己的任何 native 库，不论是哪个应用、哪个 `.so`，都会 `dlopen failed: library "...” not found`**——因为 linker 真的去这个虚拟路径找了,而这个目录在磁盘上根本不存在。

### 定位过程

1. 用一次性诊断日志（临时代码，已移除,不在提交里）在真机上同时打印 `info.nativeLibraryDir`（真实目录，`ls` 确认里面确实有 `libCmbShield.so` 等文件）和 `applicationInfo.nativeLibraryDir`（虚拟路径），再用反射调用 `BaseDexClassLoader.findLibrary()` 确认客体当前生效的 classloader 实际在用哪一个——结果证实生效的是虚拟路径。
2. 顺着 `RuntimeDexLoader.createForGuest()` 的实现找到它完全忽略自己的 `libraries` 形参，转而用 `RuntimeEngine.current.sharedLibraries.nativeSearch`，而这个字段的来源正是 `RuntimeEngine.java` 里那一行传参错误。

### 修复

```java
// RuntimeEngine.java
- sharedLibraries=new RuntimeSharedLibraries(host.getPackageManager(),artifacts,parent,info,applicationInfo.nativeLibraryDir);
+ sharedLibraries=new RuntimeSharedLibraries(host.getPackageManager(),artifacts,parent,info,info.nativeLibraryDir);
```

一行改动，已提交（`99b4770`）。

## 复测方法与结果

用短包名宿主（`dev.tripoder.cmbq2` 系列,规避已知的 dataDir 长度问题,见 [CMB FORTIFY 复跑](qqlive-startup-investigation-2026-10-28.md#1-cmb-fortify-崩溃的根因假设与复跑)）重新构建,逐个用 `diagnose-six-app-startup.py --guest-only --package <pkg>` 单独跑六个应用（逐个跑是因为 engine 进程在同一次脚本调用里跨应用重启会触发 `SUPERVISOR_INTERRUPTED`——这是设计内的安全拦截,不是 bug,见下文"熔断"一节）。六应用安装清单（APK 路径/sha256/launcher 组件,从设备上已装的六个原版应用现场采集）：[six-app-inventory-cmbq2-2026-10-28.json](evidence/level-one-2026-10-27/six-app-inventory-cmbq2-2026-10-28.json)。

| 应用 | 修复前 | 修复后 | 证据 |
|---|---|---|---|
| 招商银行 `cmb.pb` | 失败（dlopen） | **通过**：装、启动、Activity 确认、10 秒稳定性全部成功 | [six-app-fix1-startup-1](evidence/level-one-2026-10-27/six-app-fix1-startup-1/summary.json) |
| 淘宝 `com.taobao.taobao` | 未触达（级联于上游失败） | **通过** | [six-app-fix1-taobao](evidence/level-one-2026-10-27/six-app-fix1-taobao/summary.json) |
| 腾讯视频 `com.tencent.qqlive` | 未触达 | **通过** | [six-app-fix1-qqlive](evidence/level-one-2026-10-27/six-app-fix1-qqlive/summary.json) |
| 拼多多 `com.xunmeng.pinduoduo` | 未触达 | **通过** | [six-app-fix1-pinduoduo](evidence/level-one-2026-10-27/six-app-fix1-pinduoduo/summary.json) |
| 芒果TV `com.hunantv.imgo.activity` | 失败（dlopen） | 装、启动、Activity 确认全部成功；30 秒稳定性窗口内进程死于真实 `SIGSEGV`（`workerExitHistory` 给出 `reason=2 status=11`）。20 次独立抽样里 19 次完全通过，详见 [imgo-fault-evidence-sampling-2026-10-28](evidence/level-one-2026-10-27/imgo-fault-evidence-sampling-2026-10-28/README.md) | [six-app-fix1-imgo](evidence/level-one-2026-10-27/six-app-fix1-imgo/summary.json) |
| 网易云音乐 `com.netease.cloudmusic` | 失败（dlopen） | 装、启动均成功，`Application.onCreate()` 阶段栈溢出（`StackOverflowError: stack size 8188KB`）崩溃，见下文 | [six-app-fix1-netease](evidence/level-one-2026-10-27/six-app-fix1-netease/summary.json) |

修复前的级联证据（一个应用的 dlopen 失败把 engine 搞挂，后续应用全部背锅）：[six-app-cmbq2-startup-1](evidence/level-one-2026-10-27/six-app-cmbq2-startup-1/summary.json)（六个应用全部 `passed:false`）、[six-app-cmbq2-startup-2-noCmb](evidence/level-one-2026-10-27/six-app-cmbq2-startup-2-noCmb/summary.json)（去掉招行，剩下五个仍然全部失败——证明这不是招行一家的事）。

## 芒果TV：确认是独立、低频的真实问题

`workerExitHistory`（本次新增的诊断能力,见下文）给出芒果TV 这次崩溃的真实系统退出原因 `reason=2`（`REASON_SIGNALED`）`status=11`（`SIGSEGV`），和 10-27 原始诊断记录的 `libmsaoaidsec.so+0x20d10` SIGSEGV 吻合。20 次独立抽样（[详见](evidence/level-one-2026-10-27/imgo-fault-evidence-sampling-2026-10-28/README.md)）里 19 次完全通过,1 次失败但没留下诊断证据。这支持"这是一个独立于本次修复的、低频 native 崩溃"的判断,但样本量仍然偏小,继续定位需要 debuggerd 级别的符号化,这次没有做。

## 网易云：定位到机制,但没有继续往下查

反编译 `com.netease.nis.wrapper.MyApplication`（网易云壳 Application）确认：这个壳通过反射构造一个"真正的"业务 Application 实例 `newApp`，并在自己的 `onCreate()` 里转发调用 `newApp.onCreate()`。崩溃栈显示的是同一个字节码位置反复调用自己几百层直到栈溢出——结合源码，最合理的解释是 `newApp` 最终又反射解析回了 `MyApplication` 自身，形成自调用死循环；决定走哪条分支的关键判断（`MyJni.g()`/`MyJni.l()` 等）是 `native` 方法，逻辑在网易自己的防护库里，没有继续拆。

一个具体、可验证的疑点：`RuntimeEngine.java` 里客体看到的 `ApplicationInfo.sourceDir`/`publicSourceDir` 是 Tripoder 自己的工件目录路径（`info.sourceDir=apk.getCanonicalPath()`），不是系统真实安装路径（`/data/app/~~.../base.apk`）。如果网易云的壳用这个字段做"是否跑在预期物理位置"的反篡改校验，这个校验在 Tripoder 下永远通不过，壳就会永远认为"还没到真正的层"而递归下去——这能完整解释观察到的现象，但未经 native 反汇编证实，仍然是假设。即使证实，可行的修复（伪装 `sourceDir`）牵动整个客体身份/安全模型，需要专门的设计评审，不是这次顺手能改的，所以没有继续动手。

## 本轮新增的诊断能力

- **`exitHistory` 只读 controller 命令**（`RuntimeControllerCommands`/`RuntimeControllerInstallationQueries`/`RuntimeExitHistory.queryForController`）：让已经观察到 `WORKER_DIED` 的测试脚本能查到客体的真实系统退出原因，而不是只看到"原因未知"。鉴权方式：信任来自"会话记录为 DEAD + 当前已提交安装匹配"，不要求（已经死掉的）客体自证身份。已用真实 `kill -9` 在真机验证过。接入点在 `TargetLaunchIntegration` 的两处——`awaitWorker` 超时、30 秒稳定性循环检测到进程消失——这两处都是**已经拿到 processGeneration 之后**才会死的场景；在 `prepareLaunch` 调用本身内部就死掉（拿不到 processGeneration）的场景仍不覆盖。
- **`diagnose-six-app-startup.py` 的级联熔断**：一旦检测到 `real-workbench-foreground-startup-connected` 失败且 `observed=="SUPERVISOR_INTERRUPTED"`（engine 自身的恢复策略拒绝接管上一个进程——这是设计内行为,不是 bug）,立即停止,把所有还没跑的应用标记为 `NOT_ATTEMPTED_UPSTREAM_ENGINE_FAILURE`,不再让它们每个都背一个和自己无关的"未通过"。已用真实触发场景验证。

## P0：四个已通过应用的 5 次独立冷启动（2026-10-28 当日晚些时候）

对招行、淘宝、腾讯视频、拼多多四个应用各自做了 5 次独立冷启动（每次 `pm clear` 重置引擎状态，单次脚本调用覆盖全部四个应用，依赖上一节的 harness 自动重连）：

| 轮次 | 招行 | 淘宝 | 腾讯视频 | 拼多多 |
|---|---|---|---|---|
| 1 | 通过 | **失败** | 通过 | 通过 |
| 2–5 | 通过 | 通过 | 通过 | 通过 |

20 次里 19 次通过。淘宝第 1 轮的失败是一次真实的未捕获异常（`workerExitHistory` 给出 `reason=4` 即 `REASON_CRASH`），栈顶在 `RuntimeWebView.notifyDexLoad`——这是处理 Android 标准 `IPackageManager.notifyDexLoad` 回调（和 WebView 本身无关，只是类名历史遗留）的路径白名单检查，栈的下半段显示触发方是淘宝自己的安全框架（`com.alibaba.wireless.security.framework`）动态创建一个新的 `PathClassLoader` 加载插件。源码里已有注释说明这条路径此前因为类似原因放宽过一次（"Alibaba SecurityGuard libsgmain.so, Tinker ... 这类自报告不该变成崩溃"），这次大概率是同一机制下还没被覆盖的另一个路径模式。

之后追加 6 次专门复现（带完整 logcat，不经过脚本侧的消息过滤）都没有再命中，没能拿到被拒绝的具体路径，因此没有去动 `RuntimeWebView.notifyDexLoad` 的白名单逻辑——不确认具体路径之前不想猜着改。这是一个低频问题（这次统计口径下约 1/12），记录下来，下次撞上时留全量 logcat 再定位。

证据：[round-1](evidence/level-one-2026-10-27/six-app-smoke5-round-1/summary.json)（淘宝失败）、[round-2](evidence/level-one-2026-10-27/six-app-smoke5-round-2/summary.json)–[round-5](evidence/level-one-2026-10-27/six-app-smoke5-round-5/summary.json)（全部通过）。

仍然要强调：这只是 5 次冷启动，不是完整 Smoke Gate（还差 3 分钟核心交互、5 轮前后台切换）——现有测试代码只会装、启动、看 Activity 创建和存活，不会模拟真实用户操作，补齐"交互"这一项需要新写测试代码，这次没有做。

## P0 续：5 轮前后台切换（2026-10-28 当日晚些时候）

[六应用诊断方案](six-app-runtime-diagnosis-2026-10-27.md) 定义的 Smoke Gate 还包含"5 轮前后台切换"。这部分不需要模拟业务交互（和"3 分钟核心交互"不同），用已有的 `wake` 机制（按 HOME、从 workbench 唤醒目标应用、校验还是同一个进程）循环 N 次即可，所以补上了：

- `TargetLaunchIntegration.java` 新增 `-e bgFgCycles N` 参数，循环 N 次 `wakeRunningApplication`，每轮检查名加 `-cycle-<n>` 后缀，任何一轮失败整个测试就失败。
- `diagnose-six-app-startup.py` 新增 `--bg-fg-cycles N`，默认 0（不变更现有行为）。

过程中发现并修了一个真实的测试代码 bug：`wakeRunningApplication` 原来要求重新唤醒时前台焦点字符串字面包含 `"WorkbenchActivity"`，但测试一开始 `startThroughWorkbench` 已经导航到 `WorkbenchProjectActivity`，这个任务栈的栈顶在后续唤醒时仍然是 `WorkbenchProjectActivity`——不包含 `"WorkbenchActivity"` 这个子串，于是检查必然失败。改成匹配 `enginePackage()+"/dev.tripoder.hooklab.Workbench"` 前缀，同时覆盖两个 Activity。

对招行、淘宝、腾讯视频、拼多多各跑了 5 轮前后台切换（单次 `--bg-fg-cycles 5`调用），全部通过（招行单独验证 30 项检查全过，另外三个一次调用一起过）。证据：[six-app-bgfg-check-2](evidence/level-one-2026-10-27/six-app-bgfg-check-2/summary.json)（招行）、[six-app-bgfg-check-3](evidence/level-one-2026-10-27/six-app-bgfg-check-3/summary.json)（淘宝/腾讯视频/拼多多）。

距离完整 Smoke Gate 还差"3 分钟核心交互"——这部分需要真的点页面，依赖每个应用自己的业务 UI，不是能复用现有机制补的,留给下一轮。

## 还没做的事

- 四个"通过"的应用只做了一次 30 秒冒烟,不是 [六应用诊断方案](six-app-runtime-diagnosis-2026-10-27.md) 里定义的 Smoke Gate（5 次独立冷启动、3 分钟业务交互、5 轮前后台）,更不是 Release Gate。
- 网易云的根因假设未经 native 反汇编证实。
- 芒果TV 的 SIGSEGV 样本量只有 20 次,且这批抽样的诊断文件用的是临时外部存储路径（已还原）,不是正式证据格式。
- 没有重新验证招行 FORTIFY（[腾讯视频启动问题与 CMB 短名复跑进度](qqlive-startup-investigation-2026-10-28.md)）之外，这次短名宿主下其余应用是否还有别的待发现问题——这次只做了一轮 30 秒冒烟，没有反复压测。
