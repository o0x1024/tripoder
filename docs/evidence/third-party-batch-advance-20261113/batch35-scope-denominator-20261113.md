# batch35：推进面分母纠正（-3 ≠ 非系统自带第三方）

**日期**：2026-11-13
**触发**：tile 认领 v3 在列表底部反复命中 `日历`、`相机`、`浏览器`、`国家网络身份认证`，
其中 `相机` 点击后 top 落到 `com.miui.securitycenter/SystemAppPermissionDialogActivity`。
若这些格子被计入进度，分母就被污染，任何百分比都不可信。

## 1. 判据

`pm list packages -3` 的语义是「非系统分区安装」，**不是**「非系统自带」。本机两者不等：

- `-3` 全集：120 个
- 其中 APK 路径不在 `/data/app/`（落在 `/product/data-app`、`/system`、`/system_ext` 等镜像分区）：21 个
- 真第三方（路径以 `/data/app/` 开头）：**99 个**

判据只用一条：`pm list packages -3 -f` 给出的 path 前缀。不按包名前缀猜——`com.android.*`
里既有预装（`com.android.calendar`、`com.android.deskclock`）也可能有真第三方，
而 `com.xiaomi.*` 里混着预装与商店下载。

## 2. 被剔除的 21 个镜像预装

```
com.android.calendar        com.android.deskclock      com.android.email
com.android.soundrecorder   com.baidu.input_mi         com.duokan.phone.remotecontroller
com.hyper.gallery.plugin    com.iflytek.inputmethod.miui  com.mi.health
com.miui.calculator         com.miui.cleanmaster       com.miui.compass
com.miui.huanji             com.miui.mediaeditor       com.miui.newmidrive
com.miui.screenrecorder     com.miui.securitymanager   com.miui.voiceassistProxy
com.miui.weather2           com.xiaomi.bestphotos      com.xiaomi.mibrain.speech
```

## 3. 纠正后的进度口径（本次唯一口径）

| 量 | 值 | 来源 |
|---|---|---|
| 分母：真第三方 | 99 | `scope-targets.json` |
| 已建立 tile↔包名映射 | 51 | `tile-labels.json` ∩ 第三方 |
| coverage.jsonl 有判决 | 33 | `coverage.jsonl` ∩ 第三方 |
| 完成度 | **33/99 = 33%** | — |

产物：`scope-targets.json`（`preload_excluded` / `third_party` / `mapped` / `measured_third_party`）。

## 4. 两条同时暴露的副作用

1. **批量导入把预装一起搬进了容器**：容器内真实拉起过 `com.android.calendar`
   （`top=TaskP1$F1M2C0`，logcat 238 hits 主导）。所以「容器内 tile 数」不能当「目标应用数」，
   认领结果必须先按 `scope-targets.json` 过滤。
2. **预装格子的点击会穿透到真机**：`相机` 的 tap 落到 MIUI 权限弹窗。这类 tile 只能记
   `NOLAUNCH`，不得推导为「应用不兼容」。

## 5. 对验收探针的影响（待用户确认）

原提案的分母写的是「全部第三方包（-3，排除容器自身与探针）」，实测证明 `-3` 会多算 21 个
预装。建议改成：**分母 = `/data/app` 下的 99 个包**，分子 = 其中在 `coverage.jsonl` 有
非编排失败判决的包。若用户坚持用 `-3` 作分母，则在报告里同时给两个数（99 与 120）。

## 6. 后续纠正：路径判据单独也不成立（本节推翻 §1–§5 的 99）

§1–§4 用「路径在 `/data/app`」得到 99。随后与 `inventory.md` 按厂商名的口径（81）对账，
两者不重合。逐一核查后得到结论：**任何单一判据都不足以描述「非系统自带第三方」**。

| 口径 | 数量 | 失效原因 |
|---|---|---|
| `pm list packages -3` | 120 | 含 21 个路径在系统镜像的预装 |
| 路径在 `/data/app` | 99 | 含 24 个**商店更新后被搬到 `/data/app` 的一方应用**：`com.miui.gallery`、`com.miui.player`、`com.miui.video`、`com.miui.notes`、`com.miui.themestore`、`com.xiaomi.shop`、`com.xiaomi.gamecenter`、`com.mipay.wallet`、`com.android.chrome`、`com.google.android.gm`… |
| 厂商前缀排除（`com.miui.`/`com.xiaomi.`/`com.mi.`/`com.android.`/`com.google.`/`com.duokan.`…） | 73 | 反向过度排除：`com.google.android.apps.authenticator2`、`com.google.android.apps.bard` 这类很可能是**用户自己装的**，按前缀一律剔除是错的 |
| `inventory.md` 的「捆绑=是」 | 81 | 名字判断，与路径判据的分歧正是上面两类 |

三种口径给出 73 / 81 / 99 三个答案，差距 26 —— 这个量级足以让「完成百分比」完全失去意义。
**当前进度不能以任何一个为分母宣布。** 唯一还能判定「预装 / 用户安装」的判据在安装期事实上：
预装的 `firstInstallTime` 全部落在设备首次开机那一刻（同一时间戳，无论之后是否被商店更新），
而用户自己安装的包有各自不同的时间。判据应当是**按 `firstInstallTime` 聚类**，而不是按
路径或包名前缀。

执行条件：该取证每包一次 `dumpsys package`，共 120 次；必须在测度批结束后做（测度批正在
逐包 force-stop 驱动，并发 adb 会污染其读数）。产出后本文件的 §3/§5 数字需一并重算。

## §7 分母收成单一来源，分子口径与验收探针同源（代码层，已验）

§1–§6 只解决了「哪个数字对」的一半：即使候选清单写对了，生成器仍在读别的来源。
`android/scripts/coverage_matrix.py` 的分母原先走 `inventory.md` 的「捆绑」列（算得 78），
与 `scope-targets.json`（73）是两套独立来源 ⇒ **同一份现场每次重算都可能换一个分母**。

改动两处，都是「让错误无法发生」而不是「这次算对」：

1. **分母只读 `scope-targets.json`。** 文件缺失直接 `SystemExit` 报错，刻意不提供回退到
   inventory 捆绑列的分支——回退就是上一个矛盾数字的来源。实跑确认 `scope 73 ⊂ inventory 121`，
   因此逐项表仍能带上 inventory 的 `n`/备注/证据列，只是**是否算进分母**由 scope 说了算。
2. **分子排除「没测到」的码。** 原先任何 coverage 记录都算已测，于是
   `TILE_NOT_CLAIMABLE`/`TILE_MISMATCH`/`ENGINE_DOWN`/`TILE_NOT_FOUND`/`PIPELINE_ERROR`/
   `NO_TILE_MAPPING` 这些「流水线跑过但没得到判决」的记录也在抬高进度。现在这六个码归入
   `NON_MEASURED`，单独计数，逐项表里标「未测 ·」，与验收探针的分子定义同源。

实跑读数（13:00 现场，测度批进行中）：

```
records=41  third_party=73  measured=33  pending=40  unmeasured_attempted=3
可立即跑 17    需先认领 23
```

顺带修掉一处会误导执行的文案：待推进队列原先写「无 tile ⇒ 需先导入 ⇒ 跑含导入的流水线」，
但导入面已到 117/118——**没有映射的真实含义是还没认领出这个包对应哪个格子**，不是没导入。
按旧文案执行会白跑一遍导入（每包 ~40s×2 且 force-stop 宿主，污染同期测度）。

仍未解决、也不打算用代码替人决定的：分母的**数值**要等 §6 的 `firstInstallTime` 聚类产出。
在聚类定案前，`measured=33` 只是「33 个已测到」，不是一个可以对外宣布的完成度。

