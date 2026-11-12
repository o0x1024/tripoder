# 判据确认实验：把 installer 临时回答成 com.xiaomi.market（2026-11-12）

- **授权**：用户明确批准（消息 #mc50caa7「批准这次实验」）。这是本项目唯一一次跨过
  `docs/identity-provenance-contract.md` §6.4「不得谎报安装来源」的受控实验。
- **变更**：`GuestIdentity.experimentStoreAttribution=true` →
  `installerPackageName()` 对客体自身返回 `com.xiaomi.market`；
  `installSourceInfo()` 返回同名三字段 + `PACKAGE_SOURCE_STORE`。
- **构建**：`android/product/build-probe-v46`（谎报版）
- **设备**：d861a4fa，客体 `com.ss.android.ugc.aweme` 40.7.0 (400701)

## 结果

| 观测项 | 值 |
| --- | --- |
| 点「一键登录」 | 通过（弹出协议框） |
| 点「同意并登录」后 | 仍然出现「当前应用版本过低或经非官方渠道下载，为了你的账号安全，请升级应用后重试」 |
| `TripoderExperiment` 日志行 | 10 |
| `getInstallerPackageName` 次数 | 8（每次返回 `com.xiaomi.market`） |
| `getInstallSourceInfo` 次数 | 2（每次返回 store 归因） |
| `getPackagesForUid` 次数 | 2 |

诚实版（v45d）同一流程的读取模式为 8 / 2 / 2 —— **次数完全一致，只有返回值不同**。

## 结论

1. **h5 的变体被否证**：判据**不是**「安装来源能否在本地归属到官方渠道」。
   把唯一差异（`installer=null` → `com.xiaomi.market`）替换掉之后，拒绝照旧。
2. 判据不在本地可观测的安装归因平面；它不可由「来源回答」改变，现已收敛为两点（见 [能力矩阵 §3.2](../../guest-provenance-capability-matrix.md)）：
   - **服务端拦截框架**：block 协议下发 `block_mode` / `force_update` / `copywriting`，客户端只做展示与回执；
   - **容器运行痕迹**：宿主 uid 承载客体、包名与运行 uid 不自洽（`ps` 中 aweme 的 uid=10542），不在 Java/Python 层可投影范围。
   （当时还列过「设备/账号风险画像」与「渠道号」两个候选：前者已被原生对照排除——同机同账号在原生环境可正常登录；后者无法在不上商店的前提下构成，属同一族不可投影事实。二者都不再作为方向。）
3. **产品含义**：P2 彻底关闭。此前「再让投影更像官方」的一切投入都是无效方向，
   降级方案（引导在系统环境使用官方登录 + 明确文案）是唯一路径；容器内的验证码登录
   已实测同样被拦（2026-11-12），不能作为替代承诺。

## 回退

实验开关已置回 `false`（`experimentStoreAttribution=false`），代码保留为可复核的
一次实验装置，任何发布构建中恒为关闭；`build-identity-v47` 为诚实版并已装回设备。
