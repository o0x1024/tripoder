# 身份与来源契约 (Identity & Provenance Contract)

- **更新日期**：2026-11-12
- **关联文档**：[当前总体架构](current-architecture.md) · [Guest 应用兼容性分层](guest-compatibility.md) · [P2 来源能力矩阵与降级方案](guest-provenance-capability-matrix.md)
- **关联证据**：[原生 vs 容器 A/B 举证](evidence/douyin-onekey-a2b/README.md) · [v1 首轮取证（结论已作废，仅存实测数据）](evidence/douyin-onekey-identity-fix/README.md) · [渠道判定架构分析](../artifacts/douyin-architecture-fix/README.md)

---

## 1. 第一性原理：一个出口只能回答一个语义

容器最容易犯的错误不是「某个 API 返回了错的值」，而是**同一个 API 在不同调用路径上回答了不同的语义**，或者**把 A 平面的答案通过 B 平面的门面泄漏出去**。

抖音点击「一键登录」后提示「当前应用版本过低或经非官方渠道下载」，就是这一族错误的可观测形式。该提示有三条已被证据钉死的前提：

1. 客体 APK 字节与签名和官方一致（`source-douyin.apk` sha256 `86ee4316…f80c0`，与真机原生安装同签名），所以**不是重签名或版本问题**；
2. 提示在飞行模式下立即复现，说明判定发生**本地且即时**，不依赖服务端 rtlog 回传；
3. 抖音在本地能问的只有一组「安装事实」（包名、uid、版本、签名、安装来源、APK 形态、模块完整性），其中任何一项由容器而非平台回答，整组就自相矛盾。

由此得出架构约束：**身份不是一个字段，而是一组必须由同一主体回答的断言。** 契约的目标不是让某个弹窗消失，而是让「谁在问」决定「谁回答」，并让不可回答的部分如实说不可回答。

---

## 2. 三个平面

| 平面 | 提问者 | 唯一合法答案 | 回答出口 | 典型 API |
| --- | --- | --- | --- | --- |
| **Self**（自述） | 客体自己 | 客体真值 | 客体持有的 `Context` / `PackageManager` 门面 | `getPackageName`、`getOpPackageName`、`getPackageInfo(self)`、`getPackageUid(self)`、`getNameForUid(myUid)`、`getPackagesForUid(myUid)` |
| **Attribution**（归属） | 系统（AMS/PMS/AppOps/storage/media/窗口） | 内核 UID 的真实所有者 = **宿主包名** | 系统调用边界（`IPackageManager` endpoint、窗口 `params.packageName`、服务调用实参、`mOpPackageName`） | `IPackageManager.getPackagesForUid(uid)`、`getCallingUid` 校验、AppOps 归属、存储卷归属 |
| **Provenance**（来源） | 客体自己 | **如实**：这份副本不是商店投递的 | 客体持有的 `PackageManager` 门面 | `getInstallerPackageName(self)`、`getInstallSourceInfo(self)`、`ApplicationInfo.sourceDir` 形态 |

### 2.1 平面判定的唯一规则

> **问句的主语决定平面。** 「我是谁 / 我这份副本从哪来」= Self / Provenance，问句由客体发起，答案必须描述客体；「这个 UID 是谁的」= Attribution，问句由系统发起，答案必须描述宿主。

`getPackagesForUid(myUid)` 是唯一形态上的陷阱：它**看起来**是归属问题，但在客体手上它就是「我是谁」的问句（真机上客体问自己 UID 只会得到自己）。因此该 API 必须按调用层分流，而不是按 uid 数值分流：

- 客体门面（SDK 可见的 `ApplicationPackageManager`）→ **Self**，回答客体包名；
- `IPackageManager` endpoint（框架内部 AppOps/storage 路径）→ **Attribution**，回答宿主包名。

两层都在 `RuntimePackages` 内，但**不是同一个回答**，这正是本契约允许的唯一一处双答案，因为提问者不同。

### 2.2 Provenance 平面的诚实边界

容器的客体副本是「用户选择一个本地 APK 文件后由容器导入的」。它**客观没有**商店安装者。因此：

- `getInstallerPackageName(self)` → `null`（Android 对 sideload 应用的原生答案）；
- `getInstallSourceInfo(self)` → 全 `null` 字段 + `packageSource = PACKAGE_SOURCE_LOCAL_FILE`；
- `sourceDir` → 容器私有路径，**如实呈现**。

**禁止**把它替换成「设备上同名包」的商店来源。那不是投影，而是把 A（真机那份安装）的来源归属于 B（容器这份副本），是伪造第三方渠道校验依据；同时会破坏 `AppOps`/存储/权限对 UID 所有者的解析。

---

## 3. API 归属表与当前实现

| API / 事实 | 平面 | 合法答案 | 实现位置 | 状态 |
| --- | --- | --- | --- | --- |
| `getPackageName()` | Self | 客体包名 | `GuestContext:65` | ✅ |
| `getOpPackageName()` | Self | 客体包名 | `GuestContext:63` | ✅ |
| `getPackageUid(self)` | Self | `Process.myUid()` | `RuntimeSelfHookPackages` `getPackageUid` | ✅ |
| `getNameForUid(myUid)` | Self | 客体包名 | `RuntimeSelfHookPackages` `getNameForUid` | ✅ |
| `getPackagesForUid(myUid)`（客体门面） | Self | `[客体包名]` | `RuntimeSelfHookPackages:83` → `RuntimePackages.packagesForUid` | ✅ 本次修正（原为宿主包名） |
| `getPackagesForUid(uid)`（endpoint） | Attribution | `[宿主包名]` | `RuntimePackages.dispatch` 的 `getPackagesForUid[int]` 分支 | ✅ 保留 |
| `getPackageInfo(self)` | Self | 客体归档（含签名） | `GuestPackageManager` | ✅ |
| `getInstallerPackageName(self)` | Provenance | `null` | `GuestIdentity.installerPackageName`（`RuntimePackages.installer` 委托，单一实现） | ✅ 本次修正（原为宿主包名） |
| `getInstallSourceInfo(self)` | Provenance | 全 `null` + `PACKAGE_SOURCE_LOCAL_FILE` | `GuestIdentity.installSourceInfo` | ✅ 本次修正（原为真机商店真值） |
| `getInstallerPackageName(host)` | Attribution | 平台真值 | `RuntimePackages.installer` | ✅ |
| `ApplicationInfo.sourceDir` | Provenance | 容器私有路径（如实） | `RuntimeEngine:194` | ⚠ 已登记的不可投影差异，见 §6 |
| 签名 / `versionCode` / `versionName` | Self | 客体归档真值 | `GuestPackageManager`、`IdentitySnapshot.signer` | ✅ |
| 内核 UID | Attribution | 宿主 UID | 平台决定 | 🔒 不可投影 |

---

## 4. 禁止项（硬约束）

- **R1 门面洁净**：客体持有的任何 `Context` / `PackageManager` / `ApplicationInfo` / `InstallSourceInfo` 回答中，**不得出现宿主包名**。命中即为硬失败，不是告警。
- **R2 平面隔离**：Attribution 的答案不得通过 Self / Provenance 的门面泄漏给客体。需要宿主身份的内部调用必须走 `PackageFrameworkAccess.physical*` / `RuntimePackageBridge` 的物理路径，不得复用客体门面。
- **R3 来源不转述**：Provenance 不得把「设备上同名包」的商店来源或安装者转述为客体副本的来源。
- **R4 路径不伪造**：不得为客体构造 `/data/app/~~…/base.apk` 形态的 `sourceDir` 或 split 布局。
- **R5 单一路径**：同一问句只允许一条可达的回答路径。同一文件内出现「前置 `switch` 命中」+「`fallback` 重复定义」即视为缺陷（影子分支）。
- **R6 默认即登记**：落到 `default: → physicalCall(hostIdentity)` 的 `PackageManager` 方法，必须在 §3 表格中登记其平面与已知偏差；未登记的新 API 视为未评审。
- **R7 跨应用交付不冒充**：客体把 `content://<guest authority>/…` 交给空间外的应用（安装器、分享目标、文件选择器）时，容器**不得**指望外层平台承认这份 URI。该 authority 在外层 PMS 里归属真机同名包（另一个 uid），调用方却是容器 uid；转授链断裂，`startActivity` 在授权阶段中止且无系统日志。需要跨应用文件交付时，只能走宿主的 provider 投影（宿主自己的 authority + 显式读权限），并在 §3 登记；在此之前，这类能力一律按「不支持」对外声明，不允许靠加权限或改返回值伪装可用。

---

## 5. 缺陷登记（2026-11-12 代码审阅）

| # | 现象 | 位置 | 平面错位 | 处置 |
| --- | --- | --- | --- | --- |
| D1 | 客体问「谁安装了我」得到 `dev.tripoder.*` | `RuntimePackages.installer` → `GuestIdentity.installerPackageName` | Provenance → 宿主身份 | 已改为 `null` |
| D2 | `getInstallSourceInfo(self)` 返回真机商店真值（`com.xiaomi.market`） | `GuestIdentity.installSourceInfo` | Provenance → 设备真值（跨副本转述） | 已改为全 `null` + `PACKAGE_SOURCE_LOCAL_FILE` |
| D3 | `getPackagesForUid(myUid)` 返回宿主包名 | `RuntimeSelfHookPackages:83` | Self → Attribution | 已按调用层分流 |
| D4 | `getInstallerPackageName` 存在两条回答路径（前置分支遮蔽 `fallback`） | `RuntimeSelfHookPackages:87` 与 `:156` | R5 影子分支 | 已删除 `fallback` 重复定义 |
| D5 | 诊断快照只记录「平台对真机安装的回答」，没有记录「客体门面的回答」 | `IdentitySnapshot.installer/installSource` | 观测测错平面 | 已新增 `facadeAnswer` 段 + `verdict` |

> **D5 的教训**：上一轮把 D1/D2 判为「已修」，正是因为快照测的是 `engine.host.getPackageManager()`（平台对真机安装的回答），而不是客体门面的回答。**观测代码本身也必须遵守三平面契约**，否则会把缺陷测量成通过。

---

## 6. 不可投影清单（Known divergence）

以下事实由内核/平台决定，容器不伪造、也不再作为缺陷追踪：

1. **内核 UID 归属**：客体与宿主共享同一 UID，`/proc/self/status` 的四个 uid 字段恒为宿主 UID。
2. **`sourceDir` 形态**：容器内的副本位于私有目录，但**实测门面回答的是真机路径**（2026-11-12，build-identity-v42：`/data/app/~~UZI_96…/com.ss.android.ugc.aweme-I_ukds9…/base.apk`），即安装元数据投影了平台对同名包的回答，而不是容器内那份副本的路径。两者不一致；客体按该路径能否真正 `open` 到文件**尚未实测**，不作断言。早先本文件写的「sourceDir 是容器私有路径」与实测不符，已按实测更正。
3. **`fs-verity` / APK 摘要与系统安装账本**：平台没有为这份副本建立安装记录。
4. **安装来源**：见 §2.2，如实回答「非商店」。
5. **Binder 调用者 UID**：系统侧可见的 `getCallingUid()` 恒为宿主 UID。
6. **content URI 的 authority 归属**：客体 `FileProvider.getUriForFile` 得到的 `content://com.ss.android.ugc.aweme.fileprovider/…`，在外层 PMS 里归属**真机同名包（uid 10327）**，而交付方是容器 uid 10542；宿主 `dumpsys package … | grep -c 'authority='` = 0，即容器**一个 provider 都没声明**。因此「把客体私有文件交给空间外应用读」这类握手在授权阶段就断（2026-11-12 实测：升级包解析完成 → `EXTERNAL start handed off` → `AppStartScenario` 记录到 intent → 之后零 `InstallStart`、零安装器进程；同一 intent 由 `shell(2000)` 发出则安装器立刻启动）。补 `REQUEST_INSTALL_PACKAGES` 与 `<queries>` 均无效（v49 实测）。

这六项是 P2 能力矩阵的输入：需要它们的校验（渠道、支付风控、部分银行/一键登录）在容器内**不可满足**，只能降级，不能伪造。

---

## 7. 观测与回归

### 7.1 运行时契约快照

进程启动并在客体 `Context` 建立后输出一行（tag `TripoderIdentitySnapshot`）：

```
facadeAnswer={package=…,uid=…,versionCode=…,signer=…,installer=…,installSource={…},sourceDir=…,uidPackages=[…]}
hostLeak=none|installer,uidPackages,…
knownDivergence=sourceDir:container-private
platformAnswer={…}
verdict=consistent|HOST_IDENTITY_LEAK(…)  |  unreadable
```

- `facadeAnswer` **必须**通过客体门面（`guest.getPackageManager()`）取得，禁止用 `engine.host.getPackageManager()` 代答；
- `platformAnswer` 是设备对同名包持有的真值，仅用于差异比对；
- `verdict=HOST_IDENTITY_LEAK` 是硬失败。

### 7.2 问句审计

`TripoderIdentityQuery`（`IdentityAudit`）只记录**问了什么**、不记录答案，用于把「抖音到底读了哪几个事实」从假设变成序列证据。

### 7.3 回归脚本

- `android/scripts/identity_contract.py`：解析 `facadeAnswer` 行并断言 R1/R5；可离线跑（读文件）或在线跑（读 `adb logcat -d`）。
- `android/scripts/diagnose_aweme.py`：跑完抖音启动集成后自动调用上述断言，失败即以非零码退出。
- A/B 举证：同一份 `source-douyin.apk` 在真机原生安装上取同一组事实，与容器 `facadeAnswer` 逐列对照（见 [P2 能力矩阵](guest-provenance-capability-matrix.md)）。

---

## 8. 与能力矩阵的关系

本契约只规定「怎么如实回答」，不承诺「回答完就能通过第三方的渠道校验」。需要官方来源事实的能力归入 **P2**，其可行性结论与产品降级方案见 [P2 来源能力矩阵与降级方案](guest-provenance-capability-matrix.md)。
