# C/E 判别实验 · 阶段一：把「真实环境 + installer=null」这一格造出来

日期：2026-11-12 23:2x–23:3x
设备：d861a4fa（MIUI，1208×2624）
目的：补上 [抖音风控分析 §4.6](../../douyin-risk-control-strategy-analysis-20261112.md) 2×2 表的**缺格**——真实运行环境 + 无商店归因，用来区分判据落在 C 平面（容器自证痕迹）还是 E/A 平面（无商店分发记录）。

## 1. 造这一格的做法（按推荐跑法①：零侵入 user 0）

用同一份官方 base.apk，通过 PackageInstaller 会话把它装进 **user 999（MIUI 第二空间 XSpace）**：

```
cmd package install-create -r -S 359908938 --user 999     → Success: created install session [89597517]
cmd package install-write -S 359908938 89597517 base <原生 base.apk 路径>  → Success: streamed 359908938 bytes
cmd package install-commit 89597517                       → Success
```

来源 APK：`/data/app/~~UZI_96LXkcEkgzLC-cIoDw==/com.ss.android.ugc.aweme-I_ukds9p9gswwSKtruHn2g==/base.apk`（359,908,938 B，sha256 与 v45d 记录一致 86ee4316…80c0）。

## 2. 结果

| 观测项 | user 0（原生） | user 999（第二空间） |
|---|---|---|
| 安装是否成功 | 是（APK 被替换，见 §3） | 是 |
| uid | 10327 | **99910327**（真实分配，包名与 uid 自洽） |
| `installerPackageName` | **null**（原 com.xiaomi.market） | **null** |
| `firstInstallTime` | 2000-01-01（数据保留） | 2026-11-12 23:28:49（全新） |
| codePath | `/data/app/~~z_ij0CA61jain4y2w89gRg==/…` | 同（APK 共享） |

⇒ 「真实环境 + installer=null」这一格**已经存在**，且环境侧全部真实（uid 自洽、/proc 无容器痕迹、dataDir 属主正确）。

## 3. 必须记录的副作用：user 0 的商店归因也没了

Android 10+ 的 APK 是**按包共享**的，`--user 999` 的替换安装仍然替换了整包的 codePath：

- `lastUpdateTime` 变为 2026-11-12 23:28:49；
- `installerPackageName` 由 `com.xiaomi.market` 变为 **null**（包级字段，跨 user 共享）；
- user 0 的应用数据保留（`firstInstallTime` 仍 2000-01-01），已登录会话仍在（打开即为 可可儿，说明 data 未被清）。

所以「零侵入 user 0」的假设**不成立**：不改 user 0 的数据，但会改它的安装归因。恢复方式只有重新从小米商店安装（只有商店能写回 installer 字段）；**不要**在容器外退出登录，否则在 installer=null 状态下重新登录会再次面对本题。

## 4. 卡在哪一步

缺格要变成判决，需要**发一次与容器完全相同的登录请求**（一键登录 / 同意并登录）。两条路都被挡：

1. user 0 已登录，登录页只给「手机号 + 验证并登录」与「密码登录」；「其他方式登录」（一键登录入口）在勾选协议后仍然点不开，无任何响应（三次重试，23:33–23:37）。
2. 切第二空间：`am switch-user 999` 返回 **`Error: Failed to switch to user 999`**。MIUI 的第二空间由 `com.xiaomi.subscreencenter` 自己的服务切换，不走 AOSP 用户切换，adb 无法代劳。

⇒ 登录提交这一步**必须由人在第二空间里做**（或由人输入手机号/验证码）。

## 5. 判决规则（待执行）

在第二空间里打开抖音 → 我 → 一键登录 → 同意并登录：

- **通过（不弹「非官方渠道」）** ⇒ 判据锁 **C 平面**：installer/渠道归因不参与，容器独有的是 uid 归属与 runtime 痕迹（C1/C2），与来源投影彻底无关；
- **同样弹** ⇒ 判据锁 **E/A 平面**：真实环境下「无商店分发记录」本身就是判据，容器只是恰好也满足它，与容器实现无关。

两种结果都改变了最终结论的落点，但都不改变产品决策：容器内登录不可达。

## 6. 关联

- 参数级排序与 2×2 设计：[douyin-risk-control-strategy-analysis-20261112.md](../../douyin-risk-control-strategy-analysis-20261112.md) §4.5/§4.6
- 三次排除实验（诚实/null、修正、谎报商店）：同目录 `login-window-20261112.md`、`candidate-elimination-v45d.md`
- 客体以宿主 uid 运行的结构事实：`ps` 显示 uid 10542 vs 原生 10327
