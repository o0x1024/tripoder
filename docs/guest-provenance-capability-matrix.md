# P2 来源能力矩阵与降级方案 (Provenance Capability Matrix)

- **更新日期**：2026-11-12
- **关联**：[身份与来源契约](identity-provenance-contract.md) · [Guest 应用兼容性分层](guest-compatibility.md) · [原生 vs 容器 A/B 举证](evidence/douyin-onekey-a2b/README.md)

---

## 1. 结论先行

抖音「一键登录」的提示**不会因为修好身份投影而消失**，这是设计结论而不是未完成项。

原因是一条不可绕过的边界：容器内的客体副本是「用户选择一个本地 APK 文件后导入的」。它**客观没有**商店投递记录、没有 `/data/app` 安装账本、没有独立的 Linux UID。抖音的渠道校验最终由**服务端拦截框架**给出（本地只是执行与回执），而服务端看的是这一族事实与容器运行痕迹，本地投影改不动它。

另有一条**与来源无关**的边界，本轮实测新定位：容器没有 provider 投影平面，因此客体无法把私有文件交给系统外组件（自升级/安装 APK/分享）——这一条是「能力还没做」，补齐后可用，但它**换不回来一键登录**（详见 §3.2、§3.3）。

因此本文把能力分成三级，并把「必须官方来源」的那一级明确登记为**不支持**，用产品降级承接，而不是用伪造承接。

## 2. 三级能力矩阵

| 级别 | 依赖的事实 | 容器能否满足 | 典型能力 | 契约处置 |
| --- | --- | --- | --- | --- |
| **P0** | 不读安装事实 | ✅ 可满足 | 启动、渲染、文件、网络、通知、WebView、普通 OAuth（自建 H5） | 默认底座即可 |
| **P1** | 自述身份：包名、版本、签名、`getOpPackageName`、`getPackageUid`、`getPackagesForUid(自身)` | ✅ 可满足（同一份 APK，签名一致） | 绝大多数的签名校验、`checkSignatures`、指纹风控的设备侧自洽 | 契约 §3 Self 平面，必须一致 |
| **P2** | **来源/归属事实**：installer、`getInstallSourceInfo` 的 `PACKAGE_SOURCE_STORE`、`/data/app` 路径形态、`fs-verity`/APK 安装账本、独立 UID | ❌ **不可满足** | 渠道校验、支付渠道风控、Play Integrity、依赖独立 UID 的 KeyMint/GMS 服务。（⚠️ 抖音一键登录**不在此列**：v46 已证明它的判据不是这一族事实，见 §3.2） | 契约 §6 登记差异；产品降级 |

**关键区分**：P1 是「同一份应用被正确呈现」，P2 是「这份应用被官方渠道投递过」。前者是投影，后者是**已发生的事实**，事实不能被投影。

## 3. P2 明细：能力 / 当前判定 / 原因

| 能力 | 判定 | 原因（对应契约 §6 哪一条） |
| --- | --- | --- |
| 抖音「本机号码一键登录」/ 渠道校验 | 不支持 | **服务端拦截框架判定**（本地安装归因已实测排除，见 §3.2）；契约 §6.1 运行 uid 归属 |
| 支付类 SDK 的「官方渠道」风控 | 不支持 | 同上，且多数额外要求 `PACKAGE_SOURCE_STORE` 或独立 UID |
| Google Play Integrity / SafetyNet 强校验 | 不支持 | 需要平台签名的安装账本与 UID（§6.1、§6.3） |
| 银行安全键盘 / 设备证书 | 不支持 | 需要独立 Linux UID 或 KeyMint 绑定（§6.1） |
| GMS 依赖独立 UID 的组件（如部分 Chimera 模块） | 不支持 | 同上 |
| 运营商网关取号（不依赖来源的部分） | 取决于设备 | 容器不改变蜂窝出口；失败原因需按 P2 之外单独诊断 |
| 普通 OAuth / 短信验证码 / 扫码登录 | 支持 | 不读安装来源。⚠️ **抖音是反例**：其平台风控对登录方式不做区分，容器内验证码登录同样被拦（2026-11-12 实测），见 §3.2 |

### 3.1 实测排除的候选（2026-11-12，设备 d861a4fa）

抖音「当前应用版本过低或经非官方渠道下载」的本地候选已逐条实测，三条被排除：

| 候选 | 结论 | 依据 |
| --- | --- | --- |
| APK 字节与官方不同 | 排除 | 容器门面 `sourceDir` = `/data/app/~~UZI_96…/base.apk`（359,908,938 B，sha256 `86ee4316…80c0`），与原生安装是同一个文件；`ApkStore` 以 `row.apk` 引用平台已安装文件，容器不留私有副本 |
| 签名 / 版本不像官方 | 排除 | 原生 `versionCode=400701`、`Signatures [51:82:AE:3B…51:2E]`；容器上报 `signer=5182ae3b1b85…512e`，同值 |
| 代码来源暴露容器路径 | 排除 | 客体自述 `sourceDir` 与门面回答同值（`com.hundsun.winner.pazq` 实测），说明没有「私有副本路径」可被抓 |

剩下的唯一差异是**安装归因**（⚠️ 这一差异随后被 v46 实测证明**不是判据**，见 §3.2 ①）：容器答 `installer=null`、`installSource` 三字段全 null，原生答 `com.xiaomi.market`。与读取时机吻合：点「一键登录」后 14 秒内 0 次读取，点「同意并登录」后 1 秒内被拒，拒绝前后读了 12 次（`getInstallerPackageName` ×8、`getPackagesForUid` ×2、`getInstallSourceInfo` ×2）。证据：[login-window-20261112.md](evidence/douyin-onekey-a2b/login-window-20261112.md)。

**推论**：本问题在「不伪造来源」边界内**不可解**，而且原因不止「缺一段历史」——v46 已证明即使把「商店投递」谎报出来，判定与读取分布都不变。判据已定为**容器运行期身份不自洽（C1）**（见 §3.2 末段，2026-11-12 实测闭合），因此不要再投入「让投影更像官方」（既无效、也越界），只走降级。

### 3.2 已证伪的方向（无效方向登记，不要再投入）

以下三条曾被当作「修好即可登录」的候选，均已端到端实测关闭。它们的共同错误是：把**服务端拦截判定**当成**本地可投影事实的读取差异**。

| 方向 | 判定 | 实测依据 |
| --- | --- | --- |
| **① 修好安装归因（installer / installSource）** | ❌ 无效 | v44 三处泄漏全部闭合（`verdict=consistent`、`hostLeak=none`）后提示照旧；v46 更激进地把 `installer` 与 `installSource` 三字段**全部答成 `com.xiaomi.market`**（原生真值），提示**照旧**，且抖音的读取次数与方法分布（8/2/2）与诚实版**逐项一致**。⇒ 本地安装归因平面不是判据。[login-window-20261112.md](evidence/douyin-onekey-a2b/login-window-20261112.md) |
| **② 补安装权限（`REQUEST_INSTALL_PACKAGES`）** | ❌ 无效 | v49 声明该权限 + `queries(VIEW/package-archive)`，`dumpsys` 计数 1、`appops=allow`；重跑「弹窗→立即升级→100%」仍**无** `InstallStart`、**无** packageinstaller 进程、**无** installd。⇒ 权限不是卡点。[upgrade-install-boundary-20261112.md](evidence/douyin-onekey-a2b/upgrade-install-boundary-20261112.md) §9 |
| **③ 用「升级到官方版本」自举通过校验** | ❌ 无效（结构性） | 同一次 v49 实测：`PackageParser` 解析完 `install_dmt.apk` → `TripoderActivities: EXTERNAL start handed off (VIEW)` → `AppStartScenario` 收到 `content://com.ss.android.ugc.aweme.fileprovider/… callerUid=10542`，随后**什么也没发生**。原因见 §3.3：content URI 的 authority 在外层 PMS 归属真机抖音（uid 10327），发起方是容器 uid 10542，且宿主零 provider，转授链在 ATMS 断开。 |

**判据已定（2026-11-12，`c-vs-e-discriminator-clone-profile`）**：同一份 APK（`installerPackageName=null`、`versionName=40.7.0`）在**真实用户环境**（`user 999`，`Type=android.os.usertype.profile.CLONE`，uid `99910327`）里一键登录**成功**（可可儿 / 抖音号 85191533219），无拦截弹窗、logcat 0 命中。保持 `installer=null` 不变、只更换承载方式就不再被拦 ⇒ **判据是容器运行期身份不自洽（C1：客体以宿主 uid 10542 运行，与其自称的包名不一致），不是「服务端无商店分发记录」（E1）**。C1 是同进程虚拟化的结构属性，不能在容器内修；这条线到此为止。证据：[c-e-discriminator-clone-profile-20261112.md](evidence/douyin-onekey-a2b/c-e-discriminator-clone-profile-20261112.md)。

### 3.3 升级/安装自举的断点：provider 投影缺失

这是本轮新发现、且**与来源伪造无关**的一类缺陷，属于「客体与系统外组件之间交付私有文件」的平面：

| 事实 | 观测 | 后果 |
| --- | --- | --- |
| 客体交出的 URI 是它**自己的** FileProvider | `content://com.ss.android.ugc.aweme.fileprovider/…` | — |
| 该 authority 在外层 PMS 归属**真机抖音 uid 10327** | `dumpsys package com.ss.android.ugc.aweme` 中存在该 authority | 交付方 uid 10542 无权转授 → `FLAG_GRANT_READ_URI_PERMISSION` 无法成立 |
| 宿主 `dev.tripoder.q6` **一个 ContentProvider 都没有** | `dumpsys package dev.tripoder.q6 \| grep -c 'authority='` → `0` | 容器无法把客体 URI 改写/代理成自己能转授的形式 |
| 文件本体在容器私有目录 | `/data/user/0/com.ss.android.ugc.aweme/external/install_dmt.apk`（客体视角） | 即使启动成功，安装器读到的也不是这份字节 |
| 对照组：同一 intent 由 shell(uid 2000) 发出 | 安装器**立刻启动** | 说明 intent 形状没问题，问题在调用方的归属与转授权 |

**结论**：容器内客体的「自升级 / 安装外部 APK / 分享给外部应用 / 交文件给文件选择器」这一族能力，在 provider 投影平面补齐前**全部不可完成**，且**静默失败**（无异常、无系统日志）。这与 installer 伪造、与安装权限都是**正交**的三件事——不要互相替代。契约侧已登记为 R7 + 不可投影清单第 6 项。

## 4. 降级方案

### 4.1 首选：登录路径降级（零架构成本）

在 SDK 返回「版本过低/非官方渠道」类错误码时，由宿主 UI 给出解释与替代路径，而不是把第三方提示原样抛给用户。

建议文案（产品可直接使用）：

- **抖音一键登录失败**
  「当前抖音运行在 Tripoder 容器中，抖音的平台风控校验无法通过（**这不是版本问题**）。请在系统桌面使用官方版本登录；容器内的替代登录方式仍在评估中——验证码登录已实测同样被拦，不要对用户承诺它可用。」
- **支付方式不可用**
  「该支付方式要求应用来自官方应用商店。请在系统环境完成本次支付。」
- **通用兜底**
  「该功能依赖官方下载渠道，Tripoder 容器内不可用（这不是版本问题）。」

**必须避免**的文案：任何暗示「升级后可用」或「换个版本试试」的表述——那会把架构边界伪装成可修复的缺陷，制造无效工单。

### 4.2 直通模式（结论：不作为容器内能力）

**定义**：宿主侧真实安装官方 APK（由商店或用户安装），容器只承载数据/多开隔离与 UI 投影，不再承载进程与包身份。

**可行性结论：不可行（作为 Tripoder 的能力）**，理由三条：

1. **安装唯一性**：Android 一个包名在同一用户下只有一份安装记录。真实安装即意味着放弃「同一应用多开」——而这是产品的核心价值。
2. **投递不可代办**：`PACKAGE_SOURCE_STORE` 要求安装动作由商店发起。容器可以调起商店，但那是「让用户去装」，不是「容器让校验通过」。
3. **等价于放弃虚拟化**：一旦进程、UID、包身份都在系统里，Tripoder 就退化为「数据多开 + 快捷方式」，与本项目"免重打包、免安装、单 UID"的第一性原理冲突（见 [当前架构](current-architecture.md) §1）。

因此**长期不做**。若未来出现「只需要数据隔离、不需要身份隔离」的产品线，可单独立项，不与本容器混用。

### 4.3 明确不支持清单（对外承诺）

1. 让第三方渠道校验看到「商店投递」来源；
2. 伪造 `/data/app` 形态的代码路径或 split 布局；
3. 伪造 `fs-verity` / 系统安装账本 / APK 摘要链；
4. 为客体提供独立的 Linux UID；
5. 让 `Play Integrity` 一类平台签名的完整性证明通过；
6. 以此为前提的任何「一键登录」类免密流程；
7. 宿主代客体向系统外组件交付 `content://` 文件（安装器、分享目标、文件选择器）——provider 投影平面未补齐前**静默失败**，见 §3.3。

> 第 7 条与 1–6 条性质不同：1–6 是「事实不可投影」，第 7 条是「能力还没做」。补齐 provider 投影后第 7 条可以变成支持，但**它换不来一键登录**（判据是容器运行期身份不自洽 C1，见 §3.2 末段）。

## 5. 判定与回归

| 断言 | 工具 | 失败含义 |
| --- | --- | --- |
| 门面回答中不得出现宿主包名 | `android/scripts/identity_contract.py` | 契约 R1 违反（硬失败） |
| 同一问句只有一条回答路径 | 代码评审（契约 R5） | 影子分支，历史缺陷 D4 |
| P1 事实与原生安装逐列一致 | `android/scripts/identity_ab.py` | 投影错误 |
| P2 事实如实回答「非商店」 | `identity_contract.py` 的 `warn` 行 | 转述了设备真值（历史缺陷 D2） |

`android/scripts/diagnose_aweme.py` 已接入第 1、3 条：跑完启动集成后自动断言，失败即非零退出。

## 6. 对用户的答复模板

> 抖音的一键登录由服务端拦截框架判定（block 协议 + 容器运行痕迹），**不是**「这份安装是否由官方商店投递」决定的——v46 已实测把来源全量答成商店真值也不改变结果。Tripoder 在容器里运行的是从本地文件导入的副本，这一事实无法真实地变成"商店投递"，我们不会伪造它（伪造也会破坏权限、存储与 AppOps 对 UID 所有者的解析）。因此该功能在容器内按设计不支持；替代路径**只保留**「在系统环境使用官方安装」（验证码登录已实测同样被拦，扫码/开放平台授权仍在评估中）。签名、版本、包名这些**能被正确投影**的事实已经全部与官方一致，并有 A/B 举证与回归断言守着。
