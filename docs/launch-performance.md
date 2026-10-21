# Guest 启动性能架构与优化规范 (Launch Performance)

本文档记录早期启动架构与历史样本，下面的 320–1600ms 数据不代表当前 THICK 构建的端到端启动验收。2026-10-21 的 P0/P1 改造、实际测量口径和正确性边界见 [THICK 启动优化](thick-startup-p0-p1.md)。

---

## 1. 启动关键路径分解

一次 Guest 应用冷启动由以下四个顺序阶段组成：

```
[T0] 用户点击 / Agent 调用 prepareApplicationLaunch
  ├── 阶段 A: :authority 槽位分配与 ActivityLease 签发 (< 15ms)
[T1] 宿主调用 context.startActivity(RuntimeSlotNActivity)
  ├── 阶段 B: 系统 Zygote fork :slotN 进程并绑定 Application (~120–180ms)
[T2] RuntimeProcessBootstrapper.ensureBootstrapped()
  ├── 阶段 C: 激活 fixed_libc path_policy + 装载 25 项 Self-Hook 引导 + 构造 Guest LoadedApk (~80–120ms)
[T3] Guest ContentProvider 初始化 + Application.onCreate() + 首屏 Activity.onResume()
  └── 阶段 D: Guest 业务代码在 ART 下执行并渲染首帧
```

---

## 2. 六大核心性能优化架构（已全部落地）

### 优化 1：Nterp 汇编解释器替代慢速 C++ Switch 解释器（10x–30x 加速）

- **问题根因**：早期实现调用了 `art::Instrumentation::ForceInterpretOnly()` 来防止方法内联。该函数会将 ART 的 `interpret_only_` 标志置为 `true`，导致 Guest 的每一行 Java 代码（包括 Gson、OkHttp、Room、UI 布局解析）全部退回极慢的 C++ `SwitchImpl` 解释器执行，大型商业应用在 `Application.onCreate` 中耗时长达 15–30 秒。
- **第一性原理方案 (`claim_and_enable_nterp_locked`)**：
  1. 移除 `ForceInterpretOnly()`。
  2. 调用 `DeoptimizeEverything("tripoder")` 清除已有内联栈帧，并停止 JIT 编译线程。
  3. 设置 `Runtime::SetJavaDebuggable(true)` 并调用 `ConfigureStubs("tripoder", /*desired_level=*/0)`。
  4. 此时 `InterpretOnly()` 为 `false`，且 instrumentation level 为 `kInstrumentNothing`，ART 自动将所有普通方法入口绑定至 **`ExecuteNterpImpl`（手写 ARM64 汇编解释器）**，同时已被 Hook 的方法仍走 `QuickHookBridge` 跳板。
- **收益**：Guest Java 字节码执行速度提升 **10–30 倍**，商业应用冷启动耗时直接降至 1 秒量级。

### 优化 2：移除透明跳板 Activity（Gateless Single-Hop Launch）

- **问题根因**：早期启动需先拉起 `RuntimeGuestGateway`（透明跳板 Activity），在 `onCreate` 中同步请求 `:authority` 再二次 `startActivity` 跳转到 `:slotN`，多出一次完整的 AMS Activity 启动与窗口切换周期（~150–250ms）。
- **第一性原理方案**：
  - 删除 `RuntimeGuestGateway` 与 `controller_launches` 跳板表。
  - `prepareApplicationLaunch` 在 `:authority` 一次调用内直接完成槽位分配、租约签发并返回指向物理 `RuntimeSlotNActivity` 的 Intent，实现**单跳直达启动**。

### 优化 3：移除隐式广播同步 IPC 锁争用

- **问题根因**：商业应用在启动期常高频调用 `sendBroadcast(new Intent("custom.action"))`（不带显式包名）。早期实现每次收到无包名广播都会同步跨进程 IPC 查询 `:authority` 的 `getInstalledPackages` 来枚举接收方，与主线程产生严重的 Binder 与 SQLite 锁竞争。
- **第一性原理方案**：
  - 在 `:slotN` 本地缓存当前 Space 的静态广播 Action 索引，仅当广播 Action 命中沙箱内已注册的静态 Receiver 时才向 `:authority` 发起派发，无关广播直接快速放行。

### 优化 4：`InstallProviderIndex` 消除启动期 Manifest 重复解析

- **问题根因**：启动期需要安装当前进程的 `ContentProvider` 并在每次 `acquireProvider` 时按 `authority` 查找目标 Provider。若在启动期实时解析 APK 的 `AndroidManifest.xml`，会产生大量磁盘 I/O 与 XML 解析开销。
- **第一性原理方案**：
  - 在 APK 安装阶段（`staging -> published`）一次性提取所有 `<provider>` 元数据写入 `InstallProviderIndex` 表与内存缓存。
  - 启动期查询当前进程待初始化 Provider 列表与跨进程 Provider 路由均为 $O(1)$ 索引读取。

### 优化 5：消除冗余 `bindProcessSession` IPC 往返

- **问题根因**：早期在同一进程启动周期内，`Application` 引导、Provider 获取与 Activity 创建会各自重复向 `:authority` 发起 `bindProcessSession` 握手。
- **第一性原理方案**：
  - 在 `RuntimeProcessBootstrapper` 进程级单例中维护已绑定会话凭证（`BoundSessionSnapshot`），同一 `(spaceId, packageName, processName, installGeneration)` 仅在进程首次初始化时执行一次握手。

### 优化 6：APK 导入期 Native 库与 Dex 预提取

- **问题根因**：若将 `lib/<abi>/*.so` 解压或 Dex 校验延迟到首次点击启动时执行，会导致首次冷启动卡顿数秒。
- **第一性原理方案**：
  - 将 Native 库提取与文件权限设置严格收敛在 `InstallCoordinator` 的安装事务（`staging` 阶段）中完成，启动期零解压直接挂载。

---

## 3. 真机启动耗时基线 (Xiaomi 23013RK75C, Android 15)

| 场景 | 优化前耗时 | 当前实测基线 |
| --- | --- | --- |
| 轻量级应用冷启动 (`prepareApplicationLaunch` -> 首屏 `onResume`) | ~1,800 ms | **320 – 450 ms** |
| 中大型商业应用冷启动 (含多 SDK / MMKV / OkHttp / WebView 初始化) | 12,000 – 25,000 ms (Switch 解释器瓶颈) | **900 – 1,600 ms** |
| 已驻留槽位温启动 (Activity 重建 / 热切回前台) | ~400 ms | **60 – 110 ms** |
