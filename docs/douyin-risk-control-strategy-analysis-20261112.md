# 抖音风控策略全维度分析与最终结论

- 设备 / 客体：`d861a4fa` · 抖音 `400701`（versionCode）· 容器 `dev.tripoder.q6`（v42–v49 构建）
- 触发场景：容器内「我 → 一键登录 → 同意并登录」→ 1 秒内弹「当前应用版本过低或经非官方渠道下载」
- 分析日期：2026-11-12
- 关联：`guest-provenance-capability-matrix.md`（§3 候选排除 / §3.2 已证伪方向）、`identity-provenance-contract.md`（三平面与禁止项）、`guest-alternative-login-paths-20261112.md`（结论的出路）

本文按「检测平面」逐条给判据、实测方法、结论与证据强度，最后收敛成一句可引用的最终结论。证据强度分三档：

- ✅ **已实测**：仓内证据可复核，可复跑
- ◐ **逆向观察**：本会话反编译所得，原始 smali/产物未归档，**不可独立复核**
- ○ **不可验证**：在容器边界外，需要抖音侧数据或开放平台资质

---

## 1. 判定发生在哪：三段式协议（这决定了整份分析的性质）

抖音的拦截不是客户端的本地 `if`，而是「上报 → 下发 → 回执」：

1. **上行查询**：客户端把当前场景与上下文组参上报（◐ 字段：`operate_type` / `ui_click` / `auth` / `verify` / `schema` / `click_type` / `task_id` / `panel_type` / `block_type`）
2. **下行判定**：服务端返回 `block_mode` / `force_update` / `copywriting{title, subtitle, …}`
3. **客户端只做两件事**：按 `copywriting` 渲染弹窗 + 上报 UI 打开/点击结果

管道形状（◐）：`LX/0uuC;` 五段式 —— `LIZJ` 组参查询 → `LIZ` 主线程派发 → `LIZIZ` 判定处理与文案兜底（1131 insns，含目标文案）→ `LIZLLL` UI 结果上报 → `LJ` 解析 `copywriting` 渲染；网络层 `LX/0uqB;`。

文案 id（◐）：`string_id 47007` 对应「当前应用版本过低或经非官方渠道下载」。

> **性质判定**：判据在服务端，客户端任何本地返回值都不可能「说服」它。能改变结果的只有两种方式——改变它看到的上报内容，或改变承载环境。这解释了为什么第 2 节里所有本地平面修正全部无效。

---

## 2. 五个检测平面逐条判决

抖音对「渠道是否官方」的判定只可能落在下面 5 个平面。这是穷举式的分解（客户端可读事实 / 包本体 / 运行环境 / 设备账号 / 服务端数据），不存在第 6 个平面。

| # | 平面 | 抖音可能的判据 | 我们的实测 | 判决 | 强度 |
|---|---|---|---|---|---|
| A | 安装来源事实 | `installerPackageName`、`installSourceInfo`、`getPackagesForUid`、`sourceDir` | v44 三处泄漏全闭合（installer=null、installSource 三字段全 null、uidPackages=客体自身），提示仍在；v46 把三者**全部谎报**为 `com.xiaomi.market`，提示仍在，且读取次数 8/2/2 与诚实版完全一致 | **不是判据** | ✅ |
| B | 安装包本体 | APK 字节、签名、versionCode、渠道字段 | 门面 `sourceDir` 就是真机 `/data/app/~~UZI_96…/base.apk`；sha256 `86ee4316…80c0`、签名 `5182ae3b…512e`、versionCode `400701` 与原生逐项一致；客体自述 `sourceDir` 与门面同值 | **排除** | ✅ |
| C | 运行环境与进程身份 | 包名↔运行 uid 自洽、`/proc` 归属、data 属主 | 容器客体 `uid=10542`（= 宿主），原生 `10327`；单实例实验（先 force-stop 原生）仍弹 | **结构上必然暴露，且无法消除**（见 §3） | ✅ |
| D | 设备 / 账号画像 | 设备风险标记、账号风控、网络/IP | 同机、同账号、同网络下原生登录页正常渲染，无任何拦截 | **排除设备级与账号级** | ✅ |
| E | 服务端渠道分发记录 | 渠道号、商店归因、下架/白名单状态 | **无法从客户端观测**（需要抖音后台数据） | **未分离，但与 C 对结论等价**（见 §4） | ○ |

### 2.1 A 平面为什么被三次实验锁死

- **诚实版（v44）**：`installer=null` / `installSource` 全 null，语义是「非商店来源」——**这正是被怀疑的答案**，但提示照旧。
- **修正版（v44 前）**：修掉门面自相矛盾（一处答容器包名、一处转述真机商店真值），结果一致。
- **谎报版（v46）**：`experimentStoreAttribution=true`，installer 与 installSource 全部答成 `com.xiaomi.market`，即「看起来就是小米商店装的」。提示照旧，读取次数不变（8 次 installer + 2 次 installSource + 2 次 uidPackages）。

**谎报版是关键**：它把「本地可归属性」这个假设直接证伪。抖音确实读了这些事实（12 次读取全部发生在点「同意并登录」之后），但读了并不等于据此判定——它把值作为上报字段，判定在服务端。

### 2.2 C 平面为什么不可消除

`uid` 是**安装期属性**（PMS 分配），不是运行期属性。容器能让 Java 层返回任何数字，但改不了：`getuid()` syscall、`/proc/self/status`、Binder `callingUid`（内核盖章）、`/data/data` 属主。同进程虚拟化意味着客体只能以宿主 uid 运行——这是结构事实，不是投影缺陷。任何「包归属与运行 uid 一致性」的完整性检测都会命中。

---

## 3. 旁证：升级链路同样断在结构上（与风控无关）

点「立即升级」的完整链路已实测（✅）：

```
下载通知 → PackageParser 解析 external/install_dmt.apk
        → TripoderActivities EXTERNAL start handed off (VIEW)
        → system_server AppStartScenario 收到
          content://com.ss.android.ugc.aweme.fileprovider/... callerUid=10542
        → 无 InstallStart、无 packageinstaller 进程
```

断点是 **provider 投影缺失**：宿主 `dumpsys` 里 `authority=` 计数为 0（容器零 provider），该 content URI 在外层 PMS 归真机抖音（uid `10327`），而调用方是容器 `10542`，ATMS 无法向安装器转授读权限。v49 给宿主补 `REQUEST_INSTALL_PACKAGES` + `queries` 后依旧无进展（✅ 权限不是卡点）。

> 结论：这条路径即使修好 provider 投影，也只是让「升级」能跑通，而升级后的新包仍在 C 平面暴露——换不来登录。

---

## 4. 最终结论

> **抖音「当前应用版本过低或经非官方渠道下载」的判定发生在服务端（三段式 block 协议），触发它的是客户端无法投影的**运行环境事实**（客体以宿主 uid 运行、包名与运行 uid 不自洽、`/proc` 与 data 归属宿主），或**抖音自己的渠道分发记录**（客户端不可见）。本地安装来源事实经诚实版、修正版、谎报版三次实测均不是判据。因此容器内抖音客户端登录不可达，且不会随投影精度提高而改变。**

分解为 5 条可独立引用的断言：

1. 拦截文案由服务端下发（`block_mode` / `force_update` / `copywriting`），客户端只渲染 + 回执。◐
2. 客户端本地来源事实（A 平面）不是判据：全部谎报成商店归因后行为完全不变。✅
3. 安装包本体（B 平面）与原生逐项一致，可排除。✅
4. 设备与账号画像（D 平面）可排除：同机同账号原生登录无拦截。✅
5. 剩余唯一可能与 C（运行环境结构）或 E（服务端分发记录）；两者**都不可由容器改变**，故结论与二者如何分配无关。✅（C 实测）/ ○（E 不可验证）

---

## 4.5 参数级排序：到底哪一个参数

平面结论（§2）只能回答「哪一类」，这一节回答「哪一个」。下面每个参数都注明抖音的读取方式，便于对照我们已实测的值。

| # | 参数 | 抖音怎么读到它 | 原生真值 | 容器内值 | 可投影？ | 判决 |
|---|---|---|---|---|---|---|
| C1 | 运行 uid 与包名归属不自洽 | `getuid()` / Binder `callingUid` 对比 `getPackageUid("com.ss.android.ugc.aweme")` | `10327` / `10327` | `10542` / `10327` | 否（uid 是安装期属性） | **最强嫌疑 ✅实测差异** |
| C2 | 宿主 runtime 的可读痕迹 | 扫 `/proc/self/maps`：出现 `/data/app/~~…/dev.tripoder.q6…/lib*/libruntime.so`、`libselfhook*.so`、宿主 dex | 无 | 必有 | 否（去掉即去掉容器） | **并列最强 ○未取样本** |
| C3 | 私有目录语义 | `stat("/data/user/0/<pkg>")`、属主、可写性 | 属主 `10327` | 客体跑在 `10542`，路径与属主自相矛盾 | 否 | 高 |
| C4 | 代码来源一致性 | `getSourceDir()` / `ApplicationInfo.sourceDir` vs 进程实际加载的 APK/DexFile 路径 | 一致 | 门面答真机路径，进程实际加载宿主 APK + hook | 部分 | 中高 |
| C5 | 包可见性完整性 | `getInstalledPackages` 条数、`queries` 解析结果 | 完整 | 被容器裁剪 | 可改善、不可完备 | 中 |
| A1/A2 | `installer` / `installSource` 三字段 | `getInstallerPackageName` / `getInstallSourceInfo` | `com.xiaomi.market` | 已实测三态（null / 修正 / **谎报商店**） | 是 | **已排除 ✅** |
| B1 | APK 字节 / 签名 / versionCode | 读 `base.apk`、`getPackageInfo` | `86ee4316…`、`5182ae3b…`、`400701` | 逐项一致 | 已满足 | **已排除 ✅** |
| C6 | 双实例（同包名两进程） | `ps`/包列表 | — | 单实例实测仍弹 | — | **已排除 ✅** |
| D1 | 设备 / 账号画像 | 设备指纹、账号风控 | — | 同机同账号原生通过 | — | **已排除 ✅** |
| E1 | 渠道分发记录 | 服务端：该设备/账号是否曾从官方渠道获得此包 | 有 | 无（容器内不发生分发） | 否（客户端不可见） | **并列最强 ○不可验证** |

**如果必须只指一个参数：C1**。理由：它是唯一「纯 Java 层就能读到、且我们已拿到硬差异」的（`10542` vs `10327`，`container-uid-forensics`）；C2 需要 native 扫描、E1 需要抖音后台数据，取证成本都更高。

但要诚实——**C1 / C2 / E1 不是互斥选项，而是同一次判定里同时成立的事实**：客体确实以宿主 uid 运行（C1）、`/proc/self/maps` 里确实有宿主 runtime（C2）、服务端确实看不到这次安装的分发记录（E1）。抖音的判据很可能吃的是这三者的**组合**，而不是其中任何一个单独值。这也解释了为什么「只把某一个字段答对」永远不会改变结果。

## 4.6 判别实验：唯一能把 C 与 E 分开的设计

现有三次实测构成一张 2×2 表，**缺一格**：

| | 商店归因（installer=com.xiaomi.market） | 无归因（installer=null） |
|---|---|---|
| **真实环境** | ✅ 通过（`native-login-entry-control`） | **? 缺这一格** |
| **容器环境** | ❌ 拦截（`installer-forge-v46`） | ❌ 拦截（v44 诚实版） |

补上缺的那一格就能定案：**在真实环境里把官方 APK 侧载安装**（installer 变 null，环境仍是真的），登录一次：

- 若**通过** → installer 完全不参与判定，判据锁在 **C 平面**（C1/C2）。容器不可达，且与我们是否答对来源无关。
- 若**同样拦截** → 判据锁在**「无商店分发记录」**这一事实（E/A 平面），与容器无关；说明「官方 APK 侧载」对任何人都走不通。

两种结果都改变结论的**措辞**（锁 C 还是锁 E），都不改变「容器内不可达」。

两种跑法，按侵入性排序：

1. **零侵入（推荐先试）**：装进 MIUI 第二空间 `user 999`（`pm list users` 已确认存在，`pm get-max-users=4`）。真实 uid 分配、真实 `/proc`、真实 data 目录，且不动 user 0 的商店归因。
   ```
   adb -s d861a4fa pull /data/app/~~UZI_96LXkcEkgzLC-cIoDw==/com.ss.android.ugc.aweme-I_ukds9p9gswwSKtruHn2g==/base.apk /tmp/aweme-400701.apk
   adb -s d861a4fa install --user 999 --no-incremental /tmp/aweme-400701.apk
   adb -s d861a4fa shell dumpsys package com.ss.android.ugc.aweme --user 999 | grep -E "installerPackageName|versionCode"
   ```
   局限：登录要在第二空间里操作，可能需你手动切换/解锁。

2. **低侵入**：覆盖安装到 user 0（`adb install -r --no-incremental`），数据保留、版本相同、无降级。
   代价：user 0 的 `installer` 从 `com.xiaomi.market` 变成 `null`，且**只能靠重装自小米商店恢复**。

前置条件（已核）：`base.apk` 可被 shell 读取（`head -c 4` 得到 `50 4b 03 04`，359,908,938 B），版本 `400701`。

## 5. 这条结论的边界（防止过度外推）

1. 只在抖音 `400701` + `d861a4fa` 上实测成立；抖音升级后协议字段与判据可能变化。
2. **不推广到其它 app**：只看 `installer` 的风控实现大量存在（矩阵 §2 的 P1 族），那些是容器**能**满足的。
3. **C 与 E 未分离**：我们没有抖音后台数据，不能断言判据只在 C。但二者对「能不能登录」的影响完全相同，所以分离与否不改变结论，只改变解释措辞。
4. **未验证**：抖音 Web 端（`www.douyin.com`）是否走不同判据——这是替代路径 B 的唯一放行条件，见 `guest-alternative-login-paths-20261112.md` §5。
5. **未验证**：容器内 WebView 的内核指纹是否触发 Web 端风控。

---

## 6. 可行动项（按性价比排序）

| 优先级 | 动作 | 理由 |
|---|---|---|
| 1 | 停止一切「让副本更像官方」的投影投入 | A/B 平面已被三次实验排除，继续投入是纯损耗（矩阵 §3.2） |
| 2 | 产品侧改文案 + 去掉「立即升级」死路 | 该按钮 100% 无法交付安装（§3），留着只损伤体验 |
| 3 | 提供「官方 Web 版」入口（替代路径 B） | 唯一「不离开 Tripoder 又能登录」的路径，零资质成本 |
| 4 | 若目标是「用抖音身份登录 Tripoder」→ 走开放平台 OAuth | 正规通道，与容器无关 |
| — | 不建议：继续深挖 C 平面 | 需改造 `/proc`、uid 归属、内核 Binder 凭证，属契约红线与工程不可为 |

---

## 7. 证据索引

| 实验 | 文件 | 支撑的断言 |
|---|---|---|
| onekey-login-window-v45d | [`evidence/douyin-onekey-a2b/login-window-20261112.md`](evidence/douyin-onekey-a2b/login-window-20261112.md) | 断言 1、2；12 次读取的时序 |
| installer-forge-v46 | [`evidence/douyin-onekey-a2b/installer-forge-20261112.md`](evidence/douyin-onekey-a2b/installer-forge-20261112.md) | 断言 2（谎报版证伪） |
| candidate-elimination-v45d / ab-native-baseline | [`evidence/douyin-onekey-identity-fix/README.md`](evidence/douyin-onekey-identity-fix/README.md) | 断言 3 |
| native-login-entry-control | 同上「有效实测」节 | 断言 4 |
| container-uid-forensics / single-instance-container-login | 同上 | 断言 5（C 平面实测） |
| upgrade-install-boundary / v49-install-permission-e2e | [`evidence/douyin-onekey-a2b/upgrade-install-boundary-20261112.md`](evidence/douyin-onekey-a2b/upgrade-install-boundary-20261112.md) | §3 旁证 |

> 反向阅读顺序：先看本文 §4 拿到结论，需要复核时按上表回到证据文档；证据文档里的原始测量一律保留，被推翻的结论已就地标注「已作废」。
