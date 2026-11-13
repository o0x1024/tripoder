# 第 33 批：再推 4 个包（王者荣耀 / 今日头条 / 网易云音乐 / 豆包）

日期：2026-11-14 04:30–04:42
设备：d861a4fa（build-implicit-v99）
流水线：`python3 android/scripts/coverage_pipeline.py --no-import <pkg...>`（durable job，同轮 poll 到退出）

## 1. 结果 4/4 有落点事实

| 包 | tile | 落点（top） | verdict | consent_taps |
|---|---|---|---|---|
| com.tencent.tmgp.sgame | 王者荣耀 | TaskP1$F1M2C2 | `HOME_NO_ACCOUNT_UI` | 0 |
| com.ss.android.article.news | 今日头条 | TaskP1$F1M0C0R1 | `LOGIN_ENTRY` | 0 |
| com.netease.cloudmusic | 网易云音乐 | TaskP1$F1M1C0E0 | `LOGIN_ENTRY` | 2 |
| com.larus.nova | 豆包 | WorkbenchActivity | `NOT_TOP` | 0 |

counters 全 0（anr/admission/fatal/absent），无崩溃、无栅栏。

矩阵口径（`coverage_matrix.py --write`）：
`third_party=78, measured=30, concluded=31, pending=48, records=31, runnable=16, needs_import=32`
上一轮 `measured=26`，本批 +4。

## 2. 两个值得单独记的读数

### 2.1 网易云音乐：从「冷启必崩」到「登录入口可达」

此前的定性是：冷启崩在系统存储服务对 `callingPackage ↔ uid` 的一致性校验（`IStorageManager.getVolumeList` 抛 `SecurityException: callingPackage does not match UID`），属于 C1 身份事实在 AOSP 服务侧的强制。
本轮在 v99 上**不再复现**，直接到达登录入口（`consent_taps=2` 表示过了两层同意）。

⇒ 说明「opPackage 自报宿主包名」这条修法对该族是持续有效的，不是一次性侥幸。标记为已闭环，不再作为待查项。

### 2.2 豆包 `NOT_TOP`：与既有结论一致，不是新失败

豆包落点是 Workbench 自身，即启动物未上顶层。这与已建立的结论一致：其 manifest 里的 `com.ss.android.lark.appcontext…LifecycleContentProvider` authority 在外层 PMS 仍归真机 uid，客体（10542）打开时被拒，表现为「应用没有启动」。
本轮没有引入新证据，不改变该判定的类级性质（需容器提供 provider authority 投影才能修，属能力缺口而非单包缺陷）。

## 3. 本批对覆盖主线与可达主线各自贡献

- **覆盖主线**：78 个非捆绑第三方包中已测 30（38%），待测 48（其中 16 有 tile 立即可跑，32 需先导入）。
- **可达主线**：网易云音乐这一例把「系统服务身份校验族」从「已修但未在真实业务页验证」升级为「真实登录页可达」。

## 4. 复现与边界

```bash
python3 android/scripts/coverage_pipeline.py --no-import \
  com.tencent.tmgp.sgame com.ss.android.article.news \
  com.netease.cloudmusic com.larus.nova
python3 android/scripts/coverage_matrix.py --write
```

- 全程 `--no-import`（4 包都有 tile），不含导入路径。
- `HOME_NO_ACCOUNT_UI` / `LOGIN_ENTRY` 只是「落点事实」，不含登录成功；王者荣耀的账号体系在游戏内二级页，未进入。
- 豆包未做深入归因（沿用 batch22 的类级结论）。
