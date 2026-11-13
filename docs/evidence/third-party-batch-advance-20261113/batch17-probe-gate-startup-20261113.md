# 第 17 批：闲鱼起不来的真因是「启动期观测探针拖慢服务执行」——v88 门控后闲鱼正常冷启

- 日期：2026-11-13 22:47–22:56
- 设备：d861a4fa（已解锁）
- 构建：build-fence-v87（对照）→ build-probe-gate-v88（修后，sha256 `4465875f…`）
- 关联：batch16（`batch16-xianyu-service-anr-20261113.md` 记录了 ANR 现场）

## 0. 结论

**闲鱼在容器内起不来，不是因为栅栏，也不是因为闲鱼不兼容，而是因为容器自己在客体启动期跑的观测探针太贵。**

v88 把 `IdentitySnapshot` 的三组调查期观测（`scheduleMapsAudit` / `scheduleSyscallAudit` / `scheduleBadgerSurface`）关掉后，同一条路径、同一台设备、同一份 Workbench 操作：

| 指标 | v87（有探针） | v88（探针关） |
| --- | --- | --- |
| `ANR in dev.tripoder.q6:slot0` | 两次复现（22:44:33、22:46:25） | **0** |
| `Timeout executing service … RuntimeServiceSlots$S0_0` | 有（waited 20005ms） | **0** |
| `admission-blocked` | 无 | 无 |
| top | `WorkbenchActivity`（客体没起来） | **`TaskP0$F0M2C0`**（客体 Activity 在顶层） |
| 客体主进程 | 无（只剩 `:channel`/`:windowChannel`） | **`com.taobao.idlefish` 30747** |
| crash 缓冲 | 空 | 空 |
| `TripoderIdentity*` 日志行数 | **10,464** | **6** |
| 首屏 | 无 | 隐私同意页（`温馨提示 / 同意 / 不同意`），`screencap` **343,880 B**，`edittext=0 webview=0` |

## 1. 因果链（三轮实测连起来）

```
客体启动
  → RuntimeEngine 在 PROCESS_IDENTITY_READY 之后无条件启动三组观测：
      scheduleMapsAudit()       15s/40s 各 walk 一次 /proc/self/maps
      scheduleSyscallAudit()    连续 90s 每秒读 1024 条 ring buffer 并写 logd
      scheduleBadgerSurface()   25s 后 new DexFile(sourceDir) 遍历全 APK dex 条目
  → 这三组都跑在宿主 authority 侧，闲鱼 APK 有 10 个 dex、120MB，dex 遍历与每秒采样叠加
  → 同一时刻客体自身在 MainProcess.onCreate → BlinkNewEngine.execParallelSync 等并行启动任务，
    并 bind 自己的 com.taobao.orange.service.OrangeApiService（映射到 RuntimeServiceSlots$S0_0）
  → 我们这边抢 CPU（v87 ANR 采样：:authority 74%），客体的服务回调迟迟拿不到执行机会
  → AMS 的「service execution」20s 预算到期 → ANR in dev.tripoder.q6:slot0
  → slot0 被杀 → Workbench 报 ACTIVITY_DELIVERY_PROCESS_DIED
  → :channel / :windowChannel 存活 → 该包 scope 的 sessions 停在 RUNNING
  → 下一次启动才被栅栏的 liveness 子句挡住（batch15/16 看到的现象）
```

即：**栅栏是后果的后果**。batch16 把真因从「栅栏」前移到「服务 ANR」，本批再把真因前移到「我们自己的启动期观测」。

## 2. 代码改动

`android/runtime/src/dev/tripoder/runtime/IdentitySnapshot.java`

```java
/** Per-process observation probes from the identity investigation (badger surface, syscall
 *  audit, maps audit). They walk the whole APK or /proc on every guest process start, which
 *  costs real startup time and logd traffic, so they stay off unless an investigation turns
 *  them on. Nothing in the product path reads their output. */
static final boolean PROBES = false;
```

`android/runtime/src/dev/tripoder/runtime/RuntimeEngine.java`（原 298–300 三行无条件调用）

```java
if(IdentitySnapshot.PROBES){IdentitySnapshot.scheduleMapsAudit();IdentitySnapshot.scheduleSyscallAudit(this);IdentitySnapshot.scheduleBadgerSurface(this, loader);}
```

改动的边界：

- 三组探针**没有删除**，只是统一门控。C2/maps 取证、syscall 审计、badger 表面枚举的复现方式从「改三个调用点」变成「改一个常量」。
- 探针输出不被产品路径消费：`IdentitySnapshot` 的结论早已落到证据文档与能力矩阵，运行期只有日志。
- 与探针无关的 `ReportIdentitySnapshot`（facadeAnswer / installSource 快照）**保留**：它是每进程一次、有界的几行，用于来源取证。

## 3. 复现（A/B 同一脚本）

```bash
D="adb -s d861a4fa"
$D shell am force-stop dev.tripoder.q6; sleep 3
$D shell am force-stop dev.tripoder.q6; sleep 2      # 两次才收干净遗留子进程
$D shell am force-stop dev.tripoder.q7; sleep 2
$D shell logcat -c
$D shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity; sleep 9
# 点「重新连接」×2 → 列表滚动到「闲鱼」→ 点 → 等 45s
```

判据三件套：

```bash
$D shell dumpsys activity activities | grep topResumedActivity
$D shell 'logcat -d' | grep -E 'ANR in dev.tripoder|Timeout executing service|admission-blocked'
$D shell 'logcat -d' | grep -c TripoderIdentity
```

## 4. 边界

- 本批只改了「启动期是否跑观测」，没有动服务执行路径本身。`RuntimeServiceSlots` 的执行预算仍由 AMS 的 20s 决定——如果某个应用把 Application.onCreate 拖过 20s（与容器无关），同样的 ANR 仍会发生。本轮没有给 ServiceSlots 加自己的耗时证据，留作下一步。
- v88 只做了闲鱼单点 + 淘宝/快手回归（见 §5）；26 个应用的宽回归未重跑。
- 首屏只是隐私同意页，闲鱼登录闭环仍受 batch15 结论约束（FishUserLoginActivity 不可交互、U4 内容层不绘制），本批不解登录。

## 5. 回归（v88）

脚本：`/tmp/v88reg.sh`（每应用：force-stop q6/q7 → Workbench → 双次重连 → 点应用 → 40s → top/ps/anr/crash）

| 应用 | top | 结论 |
| --- | --- | --- |
| 淘宝 | `TaskP0$F0M1C0` | 通过：客体 3 进程（`com.taobao.taobao` 31701 / `:sandboxed_privilege_process0` 32333 / `:channel` 32544），无 ANR、无 admission-blocked、crash 空 |
| 快手 | `TaskP0$F0M1C2` | 通过：客体 3 进程（`com.smile.gifmaker` 1995 / `:messagesdk` 3097 / `:push_v3` 3139），无 ANR、无 admission-blocked、crash 空 |

回归 2/2 通过，说明门控没有把「能启动」变成「不能启动」；同时也确认这两家在 v87 上本来就不撞 S0_0 服务 ANR——撞它的是闲鱼这种「启动期自绑定配置服务」的形状。
