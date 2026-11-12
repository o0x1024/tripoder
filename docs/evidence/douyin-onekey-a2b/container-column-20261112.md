# 容器门面实测（2026-11-12 17:11，dev.tripoder.q6 build-identity-v42）

本地用 Homebrew SDK 重构建并安装后，从 **客体门面** 取到的第一份真实记录。这是 A/B 表的容器列，与 `README.md` 里的原生列并排看。

## 原始记录（逐字，未改写）

```
I/TripoderIdentitySnapshot: facadeAnswer={package=com.ss.android.ugc.aweme,uid=10542,
versionCode=400701,versionName=40.7.0,
signer=5182ae3b1b85337bb182cf24882449f84447ded18e296a747a9f6a0a2622512e,
sourceDir=/data/app/~~UZI_96LXkcEkgzLC-cIoDw==/com.ss.android.ugc.aweme-I_ukds9p9gswwSKtruHn2g==/base.apk,
installer=null,
installSource={installer=null,initiating=com.android.shell,originating=null},
uidPackages=[dev.tripoder.q6]}
hostLeak=uidPackages knownDivergence=sourceDir:container-private
verdict=HOST_IDENTITY_LEAK(uidPackages)
```

设备：`d861a4fa`（Xiaomi madrid / Android 17），宿主 uid=10542，抖音原生 uid=10327。

## 七项事实对照

| 事实 | 真机原生 | 容器门面（实测） | 判定 |
|---|---|---|---|
| package | `com.ss.android.ugc.aweme` | 同 | ✅ 一致 |
| uid | 10327 | **10542**（容器 uid） | 已登记差异（契约 §6.1） |
| versionCode / versionName | 400701 / 40.7.0 | 400701 / 40.7.0 | ✅ 一致 |
| signer | 未取真机摘要 | `5182ae3b…2512e` | 未做断言（缺原生列） |
| **installer** | `com.xiaomi.market` | **`null`** | ✅ v44：来自 `GuestIdentity` 的构造答案 |
| **installSource** | 商店投递 | **`{installer=null, initiating=null, originating=null}`** | ✅ v44：local-file 构造对象（v42 时为 `initiating=com.android.shell`＝平台回答） |
| **uidPackages(10542)** | —（不存在该问法） | **`[com.ss.android.ugc.aweme]`** | ✅ v44 闭合（v42 时为 `[dev.tripoder.q6]`） |
| sourceDir | `/data/app/~~UZI_96…/base.apk` | 同值（投影平台路径） | ⚠️ 登记为 `platform-projection`，见下 |

## 实测结论（v42 首测 → v44 闭合，含对我自己判断的两次更正）

1. **更正一：`installer=null` 在 v42 时并不是我们分支给的。** 我当时写「修正生效」是错的。v42 的 installer/installSource 两条都落到平台：真正的应答点从未进入 `GuestPackageManager` / `RuntimeSelfHookPackages`。
2. **真实应答点已定位**：门面是 `RuntimePackageBridge.construct()` 造出的真 `ApplicationPackageManager`，绑在我们的 IPackageManager endpoint 上，所以每次调用都走 `RuntimePackages.dispatch()` 的 key 分发。`GuestPackageManager`（重写了 `getInstallSourceInfo`）根本不在这条链上。
3. **两处根因**：
   - `getPackagesForUid[int]` 的自有分支直接 `return new String[]{engine.host.getPackageName()}`——注释写的是「Attribution 平面」，但这个 endpoint 只在本进程内被调用，问的人就是客体，于是它拿到的正是宿主包名。
   - endpoint 里**没有** `getInstallSourceInfo` 分支，掉进 `default:` → `physicalCall` → 平台回答真机的商店来源。
4. **更正二：key 格式猜错过一次。** 我第一版写成 `getInstallSourceInfo[class java.lang.String]` 无效；靠 `RuntimeFailThrough` 打出的真实 key 是 `getInstallSourceInfo[class java.lang.String, int]`（多一个 userId 参数）。改对后才生效。
5. **v44 复测结果**：`installer=null`、`installSource` 三个引用字段全 null、`uidPackages=[com.ss.android.ugc.aweme]`、`hostLeak=none`、`verdict=consistent`；`identity_contract.py --file` 由 v42 的 `FAIL(exit 1)` 变为 `PASS(exit 0)`，`records=2 failures=0`。同时 `default:` 里不再出现 `getInst*` 的未翻译 key。
6. **`sourceDir` 仍投影真机路径**（`platform-projection`），不是容器副本路径；客体按该路径能否 `open` 到文件**尚未实测**，不作断言。

## 复现

```bash
# 构建（本机 SDK：/opt/homebrew/share/android-commandlinetools）
python3 android/scripts/build-workbench.py \
  --sdk /opt/homebrew/share/android-commandlinetools --platform android-37.0 \
  --build-tools 37.0.0 --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370 \
  --platform-libc android/product/platform-inputs/libc.so \
  --platform-art android/product/platform-inputs/libart.so \
  --platform-linker android/product/platform-inputs/linker64 \
  --package dev.tripoder.q6 --build-dir android/product/build-identity-v44

adb -s d861a4fa install -r -d --no-incremental android/product/build-identity-v44/tripoder-q6.apk
adb -s d861a4fa shell am start -n dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity
# UI 若显示「运行引擎没有连接 / AUTHORITY_HEALTH_TIMEOUT」，先点「重新连接」，再点抖音
adb -s d861a4fa logcat -d | grep facadeAnswer
```

## 环境备注（与本缺陷无关但会误导诊断）

- `adb install` 默认走 **Incremental Install**（输出 `Performing Incremental Install`）。已改为 `--no-incremental`（`Performing Streamed Install`），排除流式加载干扰。
- `diagnose_aweme.py` 当前在本机稳定失败于 `AUTHORITY_HEALTH_TIMEOUT`（`ControlTestBase.startThroughWorkbench`）。**已用旧构建 `build-fdraw-v41` 做对照：同样失败**，因此与本次代码改动无关，属 supervisor 连接状态问题；产品 UI 的「重新连接」可恢复，恢复后客体重建路径正常。
