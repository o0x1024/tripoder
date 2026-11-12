# 抖音一键登录「非官方渠道」取证 v1（2026-11-11 首轮）

> **状态：首轮的结论与修复建议全部作废，仅保留可复用的实测数据。** 本目录的原始 logcat 仍是原始证据（`full-logcat-onekey.txt`、`guest-logcat-full.txt`、`guest-18613-logcat.txt`）；下面的推理已被后续实验逐条推翻，不要再据此立项。

## 被推翻的四条（逐条给出推翻依据）

| 首轮结论 | 现状 | 推翻依据 |
|---|---|---|
| 根因是 `getOpPackageName()` 返回宿主包名 | ❌ 作废 | 该结论的证据来自运行时**自检探针**（`RuntimeSelfHookApplication` 打印 `opPackage=`），不是抖音的调用序列；证据目录中不存在抖音对 `PackageManager` 的调用记录。这是契约 §5 D5 的同类错误：**观测代码测错了平面** |
| 抖音把 opPackage 与自身包名对比，不符即判「非官方」 | ❌ 作废 | v45d 用调用窗口抓到的 12 次读取全部落在 `getInstallerPackageName` / `getPackagesForUid` / `getInstallSourceInfo`，**没有一次**读 opPackage；且 opPackage 修正后提示照旧 |
| `installer` 应投影成设备真值 `com.xiaomi.market` | ❌ 作废，且方向相反 | 现行契约 R1/R2 要求门面**不得转述设备真值**：容器答 `installer=null`、`installSource` 三字段全 `null`。首轮表格记的「已修（待回归）」实为缺陷 D2 |
| 飞行模式弹窗复现 ⇒ 判据在本地、可排除服务端 | ❌ 作废 | 弹窗文案由服务端下发（block 协议 `copywriting`），飞行模式下客户端只是重放已拿到的结果，不能反推判定位置。原生对照 + v46 之后判定为服务端拦截框架 + 容器运行痕迹 |

## 仍然有效的实测（可继续引用）

| 判定字段 | 原生抖音（设备真值） | tripoder 客体 | 至今成立的结论 |
|---|---|---|---|
| versionCode / versionName | 400701 / 40.7.0 | 400701 / 40.7.0（同一份 APK） | 一致 ⇒「版本过低」是伪因 |
| APK 签名 SHA-256 | `5182ae3b…512e` | `5182ae3b…512e` | 一致 ⇒ 不是签名不符 |
| 进程 uid | 10327（`u0_a327`） | 10542（宿主 uid） | 结构性不自洽，属契约 §6 不可投影清单 |

## 现行结论从这里看

- [P2 来源能力矩阵](../../guest-provenance-capability-matrix.md)：§1 结论先行、§3.2 已证伪方向、§4.1 降级路径
- [login-window-20261112.md](../douyin-onekey-a2b/login-window-20261112.md)：12 次读取与拒绝时机
- [installer-forge-20261112.md](../douyin-onekey-a2b/installer-forge-20261112.md)：谎报实验（判据不在本地归因）
- [upgrade-install-boundary-20261112.md](../douyin-onekey-a2b/upgrade-install-boundary-20261112.md)：升级链路断点与 provider 投影缺失
