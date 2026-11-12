# 抖音一键登录窗口实测（2026-11-12，build-identity-v45d）

## 目的

R1 的两处门面泄漏闭合后（v44：`installer=null`、`installSource` 三字段全 null、`uidPackages=[com.ss.android.ugc.aweme]`、
`verdict=consistent`），实际走一次登录，确认「当前应用版本过低或经非官方渠道下载」是否消失；
并抓点击前后的 package-endpoint 读取序列，钉死抖音读的是哪类安装事实、在哪个时刻读。

## 结论（先说结果）

**提示没有消失。** 说明修复泄漏这条路本身不解决该问题——这是本轮最重要的结论，它推翻了
「泄漏 = 根因」这个此前一直默认成立的前提。

## 实测过程

设备 `d861a4fa`，容器包 `dev.tripoder.q6`，客体 `com.ss.android.ugc.aweme`（版本 400701 / 40.7.0）。

| 阶段 | 操作 | 观察 |
|---|---|---|
| 1 | 容器内启动抖音 | 推荐流正常，无任何提示 |
| 2 | 点底部「我」 | 进入登录页：`登录后，体验完整功能` / `153****8553` / `认证服务由天翼数字生活账号提供` / `一键登录` **无提示** |
| 3 | 点「一键登录」 | 弹出**协议确认框**：`用户协议及隐私政策` / `同意并登录` / `不同意`；**14 秒内无渠道提示** |
| 4 | 点「同意并登录」 | **1 秒内**弹出：`应用版本过低` / `当前应用版本过低或经非官方渠道下载，为了你的账号安全，请升级应用后重试` / `立即升级` `暂不升级`；持续 12 秒以上不退 |

由此可以把判定时刻精确定位：**不是点「一键登录」时，而是协议同意后真正发起登录时**。

## 关键数据：抖音到底读了什么、什么时候读的

本轮在 `RuntimePackages.dispatch()` 的三个已翻译来源分支各加一条**不去重**的逐次日志
（`android.util.Log.w("TripoderProvenance", ...)`），重建 v45d 后重跑同一条路径。
关键点是用了不去重的日志，因为 `RuntimeFailThrough.note()` 同 key 只打印一次，
无法区分「启动期读过」和「登录窗口又读了一次」。

阶段标记（`log -t TripPhase`）与读取记录（`logcat -s TripoderProvenance`）对齐后：

```
11-12 17:31:24.851  MARK-A-startup-done        <- 抖音启动完成、推荐流已出
11-12 17:31:37.636  MARK-B-before-onekey       <- 已进入登录页，即将点「一键登录」
11-12 17:31:44.249  MARK-C-before-agree        <- 点完一键登录，即将点「同意并登录」
11-12 17:31:56.439  MARK-D-done                <- 提示已出现
```

| 阶段区间 | 读取次数 |
|---|---|
| 启动期 | **0** |
| 启动后 → 点「一键登录」 | **0** |
| 点「一键登录」→ 点「同意并登录」 | **0** |
| **点「同意并登录」之后** | **12** |

12 次的具体构成（全部落在 `MARK-C` 之后）：

- `getPackagesForUid 10542` × 2
- `getInstallerPackageName com.ss.android.ugc.aweme` × 8
- `getInstallSourceInfo com.ss.android.ugc.aweme` × 2

## 这意味着什么（2026-11-12 定稿：本节推论已被 v46 推翻，只保留观测）

1. **观测（有效）**：抖音确实在点「同意并登录」之后读了这一族事实——`getInstallerPackageName` ×8、`getPackagesForUid` ×2、`getInstallSourceInfo` ×2，**不是** opPackage，也不是别的 API。
2. **观测（有效）**：它拿到的是**已经干净的值**（`installer=null`、`installSource` 全 null、`uidPackages` 是客体自己），依然拒绝登录。
3. ~~推论：拒绝判据是「答案能否证明这次安装来自官方渠道」，全 `null` 在语义上是 sideload、无法归属，与「非官方渠道下载」在风控上等价。~~ **已推翻**：v46 把 `installer` 与 `installSource` 三字段全部答成原生真值 `com.xiaomi.market`（可归属商店）后，提示**照旧**，读取次数与方法分布 8/2/2 **逐项不变** ⇒ 这一族事实**不是判据**，只是被读取的伴随现象。见 [installer-forge-20261112.md](installer-forge-20261112.md) 与 [能力矩阵 §3.2](../../guest-provenance-capability-matrix.md)。

> 保留本节的意义：它是「本地归因论」提出时的原始表述，也正是 v46 设计实验所针对的靶子。判据定位以能力矩阵 §1/§3.2 为准。

## 未验证 / 不作断言（2026-11-12 更新：第一条已由 v46 闭合）

- ~~抖音是否只要求 `installer` 非 null 且属于已知商店白名单——未验证。决定性实验是把 `installer` 回答为 `com.xiaomi.market` 再登一次，需明确授权后才可做。~~ **已做（v46，用户已授权）**：把 `installer` 与 `installSource` 全量答成 `com.xiaomi.market` 后提示照旧、读取分布不变 ⇒ 「非 null 且在商店白名单」不是放行条件，本地可归属性被排除。见 [installer-forge-20261112.md](installer-forge-20261112.md)。
- APK 内渠道 meta-data 是否参与——**仍未验证**（但不是可行动方向：APK 字节、签名、版本三项已实测与原生一致，见下节）。
- 服务端是否参与判定——**已确认参与**：拦截框架由服务端下发（`block_mode`/`copywriting`），客户端只做展示与回执。判据定位见 [P2 来源能力矩阵](../../guest-provenance-capability-matrix.md)。

## 复现

```bash
# 构建（本机 SDK：NDK 必须用 30.0.16248370，build-tools 必须用 37.0.0）
python3 android/scripts/build-workbench.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/product/platform-inputs/libc.so \
  --platform-art android/product/platform-inputs/libart.so \
  --platform-linker android/product/platform-inputs/linker64 \
  --package dev.tripoder.q6 --build-dir android/product/build-identity-v45d

adb -s d861a4fa install -r -d --no-incremental android/product/build-identity-v45d/tripoder-q6.apk
# UI：Workbench ->（必要时「重新连接」）-> 抖音 -> 底部「我」->「一键登录」->「同意并登录」
adb -s d861a4fa logcat -d -s TripPhase            # 阶段标记
adb -s d861a4fa logcat -d -s TripoderProvenance   # 逐次来源读取
```

## 构建环境踩坑（本轮新增，值得记住）

- `--ndk` 要**绝对路径**，不是版本号。
- NDK `29.0.14206865` 里没有 `aarch64-linux-android36-clang`，只有 `30.0.16248370` 有；
  用 29 会在 native 阶段报 `FileNotFoundError`。
- `--platform android-36` 会让 `ArtifactSet` 等类报类型不匹配（8 个错误）；
  `--build-tools 36.0.0` 的 `apksig.jar` 是 487215 字节、缺 `isVerifiedUsingV32Scheme`，
  37.0.0 才是 504788 字节、与 v44 一致。**platform + build-tools + ndk 三者都要 37.0.0 / 37.0.0 / 30.x。**

## 追加排查（同日 17:45–17:52）：三条候选被实测排除

1. **APK 字节不同** — 排除。门面 `sourceDir` 指向 `/data/app/~~UZI_96…/base.apk`（359,908,938 B，sha256 `86ee4316…80c0`），
   与原生安装的是**同一份文件**，容器没有私有 APK 副本（`ApkStore` 以 `row.apk` 引用平台安装文件）。
2. **签名/版本不像官方** — 排除。原生 `dumpsys`：`versionCode=400701`、`Signatures: [51:82:AE:3B…51:2E]`；
   容器上报 `signer=5182ae3b1b85…512e`，同值。
3. **代码来源暴露容器私有路径** — 排除。实测另一客体 `com.hundsun.winner.pazq` 快照：客体自述
   `sourceDir=/data/app/~~gtUJ…/base.apk` 与门面回答**同值**（`dataDir` 才是逻辑投影）。

因此三个候选（APK 字节、签名/版本、代码来源路径）全部排除；剩下唯一可见的差异是**安装归因**：`installer=null`、`installSource` 三字段全 null（原生 `com.xiaomi.market`），与「点同意并登录后 1 秒内被拒、拒绝前后共 12 次归因读取（8+2+2）」吻合。

**结论（2026-11-12 定稿，勿读成因果）**：这一差异**当时**是唯一可见差异，但 v46 把它全量谎报成 `com.xiaomi.market` 后提示照旧、读取方法分布不变 ⇒ 安装归因**不是判据**。判据在服务端拦截框架 + 容器运行痕迹，见 [能力矩阵 §3.2](../../guest-provenance-capability-matrix.md) 与 [installer-forge-20261112.md](installer-forge-20261112.md)。本文的 12 次读取与时机仍是有效证据。

