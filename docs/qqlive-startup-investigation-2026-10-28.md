# 腾讯视频启动问题与 CMB 短名复跑进度（2026-10-28）

承接 [六应用诊断方案](six-app-runtime-diagnosis-2026-10-27.md) 与 [Application 合同修复](application-context-contract-repair-2026-10-27.md)。
本文记录本轮的实际结果、已排除的假设和未解决的问题。**这不是六应用验收，也不是 Smoke/Release Gate 结论。** 所有运行只做 30 秒启动存活，未登录、未执行业务操作，数据未清除，未重放 UNKNOWN 操作。

## 1. CMB FORTIFY 崩溃的根因假设与复跑

- 旧 QA 宿主 `dev.tripoder.leveloneresumeqa`（29 字节包名）+ 空间 `p6-20261014` 使 CMB 的 `ApplicationInfo.dataDir` 约 117 字节。
- `libCmbShield.so` 把 dataDir 拼进 127 字节缓冲区，触发 `FORTIFY: vsprintf: prevented 133-byte write into 127-byte buffer`，随后 `__fortify_fatal` 中止。
- 使用短名宿主 `dev.tripoder.cmbq`（dataDir 约 105 字节）复跑：12 项检查全部通过，30 个采样全部存活，之后未再出现 FORTIFY。证据：[application-context-9-cmb-shortname](evidence/level-one-2026-10-27/application-context-9-cmb-shortname/summary.json)。
- 边界：这是一次单独复跑，旧 117 字节运行没有在完全相同条件下重跑，“长度是唯一变量”仍是强支持而非严格证明。

## 2. 六应用启动诊断（短名宿主，guest-only）

证据：[six-app-cmbq-startup-1](evidence/level-one-2026-10-27/six-app-cmbq-startup-1/summary.json)。

| 应用 | 结果 | 说明 |
|---|---|---|
| 招商银行 `cmb.pb` | 通过 | 无 FORTIFY、无未捕获异常 |
| 芒果 TV | 通过 | |
| 淘宝 | 通过 | |
| 拼多多 | 通过 | |
| 网易云 | 未通过 | `WORKER_DIED`，原因未知（未在本文范围内继续排查） |
| 腾讯视频 | 未通过 | 见第 3 节 |

运行期发布均为固定 264 绑定、运行期 `toggleBatchNative` 为 0、主线程发布为 0。

## 3. 腾讯视频：问题记录

六次腾讯视频复跑（同一 build，`diag-1` 至 `diag-6`）结果：

| 运行 | 结果 | 证据 |
|---|---|---|
| diag-1 | 30 秒检查全部通过；收尾超时 `HOOK_WORKER_TIMEOUT_UNKNOWN`；同一 worker 期间出现 `SecurityException` | [diag-1](evidence/level-one-2026-10-27/six-app-cmbq-qqlive-diag-1/summary.json) |
| diag-2 | guest 工作进程原生中止（SIGABRT），线程 `Idle Thread-762`，`mprotect` 报 `Out of memory`；harness 报 `DeadObjectException` | [diag-2](evidence/level-one-2026-10-27/six-app-cmbq-qqlive-diag-2/README.md) |
| diag-3 至 diag-6 | 全部通过 | [diag-3](evidence/level-one-2026-10-27/six-app-cmbq-qqlive-diag-3/summary.json)…[diag-6](evidence/level-one-2026-10-27/six-app-cmbq-qqlive-diag-6/summary.json) |

### 3.1 `SecurityException` 的调用栈（diag-1）

- 异常在 `RuntimeSelfHookRawApplicationRoutes.requireCurrentUser` 抛出，经 `RuntimeSelfHookRawApplicationDerived.invoke`（`createContextAsUser` 分支）进入。
- 上层调用者是平台 `android.widget.ToastPresenter.trySendAccessibilityEvent` → `ContextWrapper.createContextAsUser`。
- 栈中还有腾讯的 `MainThreadCrashCatcher`，说明它很可能是在处理更早的主线程异常时弹出 Toast 才触发的。最早的异常仍未知。
- 诊断日志 `CROSS_USER_CONTEXT_REJECTED`（只记录 `UserHandle{N}` 文本）已加入 `RuntimeSelfHookRawApplicationRoutes.java`，但此后的运行中一次都没有触发，被请求的用户值仍未知。

### 3.2 线程爆发（diag-2）

- guest 工作进程 `19284` 在启动约 5 秒内创建到 `Idle Thread-762`，随后 `mprotect` 因内存不足失败，触发 SIGABRT。
- 进程名称和线程名称 `Idle Thread-N` 不在本仓库任何代码中，应来自腾讯自身或其依赖。
- 原生腾讯视频进程在同一设备上约 334 线程，且 12 个线程带 `Idle` 名称；guest 正常运行时约 17–18 线程。这说明“存在大量 Idle 线程”本身不是异常，关键是增长的速度和上限。
- diag-2 与 diag-1 的 `SecurityException` 不是同一次事件：diag-2 的 logcat 里没有拒绝记录。

### 3.3 已排除或尚未支持的假设

- 已排除：启动残留的 UNKNOWN 启动条目（两次运行的 `launchReceipt` 均为 `SUCCEEDED`，未出现 `LAUNCH_TARGET_REJECTED`）。
- 未支持：“运行时自身每次调用创建线程导致爆发”。仓库里创建线程的位置（`RuntimePendingIntentIngress`、`RuntimePendingServiceIngress` 等）都不带 `Idle` 名称，不能解释 diag-2 的线程名。
- 未完成：采样脚本（`scratchpad/sample-fast.sh`）写坏了，只采到自己的 shell 进程，没有有效的线程时间序列。

## 4. 未解决的问题

1. diag-1 的 `SecurityException` 最早的触发异常未知。
2. diag-2 的线程爆发触发条件未知，在 6 次运行中只出现 1 次。
3. 跨用户拒绝路径的被请求用户值未知。
4. 网易云 `WORKER_DIED` 原因未知。
5. 收尾 `HOOK_WORKER_TIMEOUT_UNKNOWN` 与 `DeadObjectException` 说明 harness 对腾讯视频的收尾仍不稳定。

## 5. 下一步需要的条件

- 要定位线程爆发，需要在 guest 内记录线程创建的调用点（名称与栈顶），这要求修改产品代码，需要确认。
- 或者需要一个稳定的复现路径（已知会触发 Toast 或线程爆发的场景），否则只能继续被动采样。
- 诊断日志 `CROSS_USER_CONTEXT_REJECTED` 是否保留，需要决定。

## 6. 本轮改动

- `android/scripts/diagnose-six-app-startup.py`：
  - 新增 `cmb.pb` 的 dataDir 长度守卫（超过 109 字节拒绝），仅限 CMB。
  - 修正 UID 解析：`pm list packages -U` 按前缀匹配，改为精确匹配宿主行。
- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookRawApplicationRoutes.java`：仅新增跨用户拒绝的诊断日志（数值/UserHandle 文本，不记录消息内容）。**该文件在仓库中为未跟踪文件，本次提交未包含它，待决定后单独提交。**
