# 抖音一键登录：原生 vs 容器 A/B 举证

- **采集日期**：2026-11-12
- **设备**：`d861a4fa`（MIUI，宿主 `dev.tripoder.q6` v0.1-dev，targetSdk 36）
- **契约**：[身份与来源契约](../../identity-provenance-contract.md)
- **工具**：`android/scripts/identity_ab.py`（原生列自动采集）、`android/scripts/identity_contract.py`（门面断言）

---

## 1. 为什么要做这张表

「当前应用版本过低或经非官方渠道下载」是一个**聚合提示**：抖音不会告诉外部哪一个字段不匹配。而七个安装事实里有五个只能靠**对照**判断：签名、版本本来就该一致，安装来源、APK 形态、UID 归属本来就**不可能**一致。所以举证的形式必须是一张两列表，而不是一句结论。

## 2. 真机原生安装（本次实测）

同一份官方 APK 在设备上原生安装的客观事实，取自 `dumpsys package` / `pm list packages -f`：

| 事实 | 值 | 采集命令 |
| --- | --- | --- |
| 包名 | `com.ss.android.ugc.aweme` | `pm list packages -f` |
| 安装 UID | `10327`（`appId=10327`） | `dumpsys package` |
| versionCode / versionName | `400701` / `40.7.0` | `dumpsys package` |
| targetSdk / minSdk | `34` / `23` | `dumpsys package` |
| **安装者** | **`com.xiaomi.market`** | `dumpsys package` |
| 代码路径 | `/data/app/~~UZI_96LXkcEkgzLC-cIoDw==/com.ss.android.ugc.aweme-I_ukds9p9gswwSKtruHn2g==` | `pm list packages -f` |
| 签名 | `signatures:[f97ea8ed]`，past signatures 含 `2606a464` | `dumpsys package` |

对照：**宿主 `dev.tripoder.q6` 自身的 `installerPackageName=null`**（adb 安装，无商店投递），UID `10542`（`u0_a542`）。

这一条比任何推断都直接：改造前客体问「谁安装了我」，得到的答案是 `dev.tripoder.q6`——**一个自己都没有安装者的包**。

## 3. 容器内客体门面（A/B 的第二列）

### 3.1 改造前（代码判定路径，非实测）

当前设备上的构建早于 `IdentitySnapshot.logFacade`，因此第二列无法实测。改造前的答案是**确定性**的，路径如下（契约 R5 影子分支）：

```
pm.getInstallerPackageName("com.ss.android.ugc.aweme")
  → RuntimeSelfHookPackages:87   case "getInstallerPackageName(java.lang.String)"
  → RuntimePackageBridge.installer(pkg)
  → RuntimePackages.installer:135  if (engine.packageName.equals(pkg)) return engine.host.getPackageName()
  → "dev.tripoder.q6"
```

同一问题的另一条路径（`fallback()` 的 `case "getInstallerPackageName"`）回答的是**真机商店真值**，但它被上面前置分支遮蔽，从未被执行。

### 3.2 改造后（预期，待重构建实测）

| 事实 | 真机原生安装（实测） | 容器门面（改造前） | 容器门面（改造后） | 判定 |
| --- | --- | --- | --- | --- |
| package | `com.ss.android.ugc.aweme` | `com.ss.android.ugc.aweme` | 同左 | 一致 ✅ |
| uid | `10327` | `10542`（宿主） | 同左 | **登记差异**（契约 §6.1） |
| versionCode/Name | `400701 / 40.7.0` | 归档同值 | 同左 | 一致 ✅ |
| signer | 同 APK 签名 | 归档同值（SHA-256） | 同左 | 一致 ✅ |
| **installer** | `com.xiaomi.market` | **`dev.tripoder.q6`** ❌ | **`null`** | 改为如实「无安装者」 |
| **install source** | 商店投递 | **`com.xiaomi.market`（转述真机）** ❌ | **全 null + `PACKAGE_SOURCE_LOCAL_FILE`** | 改为如实「本地文件导入」 |
| **sourceDir** | `/data/app/~~…/` | 容器私有目录 | 同左 | **登记差异**（契约 §6.2） |
| `getPackagesForUid(10542)` | `[com.ss.android.ugc.aweme]` | **`[dev.tripoder.q6]`** ❌ | **`[com.ss.android.ugc.aweme]`** | Self 平面归位 |

改造前这一列同时存在**两种互相矛盾的谎**：一处说「容器安装了它」，另一处说「小米商店安装了它」。这才是契约要解决的问题——不是让某一列变得好看，而是让同一问句只有一个答案。

## 4. 复现

```bash
# 原生列 + 容器列（容器列需要包含 logFacade 的构建）
python3 android/scripts/identity_ab.py --markdown

# 只取原生列（当前设备即可）
python3 android/scripts/identity_ab.py --native-only

# 门面契约断言（R1：门面回答中出现宿主包名即失败）
python3 android/scripts/identity_contract.py --logcat
```

## 5. 结论与遗留

1. 原生列已实测（本文 §2），可作为长期基线。
2. 容器列**已实测**（v44，见 [container-column-20261112.md](container-column-20261112.md)）。原先「本机无 Android SDK、未执行、唯一未闭环项」的记录已作废：SDK 在 `/opt/homebrew/share/android-commandlinetools`，构建三件套为 `android-37.0` + `build-tools 37.0.0` + NDK `30.0.16248370`。
3. 「一键登录」是否因此变为可用，**不在本表的承诺范围内**，且后续实验已给出否定答案：把 `installer` / `installSource` 全量答成同名真值 `com.xiaomi.market`（v46）后提示照旧、读取分布不变 ⇒ 这一族事实不仅不可满足，**也不是判据**。见 [P2 来源能力矩阵](../../guest-provenance-capability-matrix.md) §3.2。
4. 上一轮把根因判为 `getOpPackageName`，其证据来自运行时**自检探针**（`RuntimeSelfHookApplication` 打印 `opPackage=`）而非抖音的调用序列。本次审阅确认：证据目录中不存在抖音对 `PackageManager` 的调用记录，因此那一轮的「根因」在证据层面是不成立的（修正本身正确，但未被证据支持）。这是契约 §5 D5 的同一类错误：**观测代码测错了平面**。
