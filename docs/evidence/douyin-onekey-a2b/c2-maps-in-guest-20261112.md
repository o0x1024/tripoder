# C2 取证：客体进程自身的 /proc/self/maps（2026-11-12）

**问题**：参数排序里唯一的「△」是 C2——「客体的 /proc/self/maps 里能看到宿主 runtime 的痕迹」。
本轮把它从推断变成实测：**由客体进程自己读自己的 maps 并落盘**。

**为什么必须由进程自己读**：`adb shell`（uid 2000）读客体（uid 10542）的 `/proc/<pid>/maps` 返回
`Permission denied`（见 `c2-maps-external-probe`）。外部不可取证，反面证明该文件受 uid 隔离保护，
因此「maps 里有没有痕迹」只能由客体自身回答。

## 方法与产物

| 项 | 值 |
|---|---|
| 构建 | `build-maps-v51`（`python3 android/scripts/build-workbench.py --package dev.tripoder.q6 …`） |
| 产物 sha256 | `bb2de974f2d74a6747121b498e85ff7d57680488ca6e021e26f3a937522fb901`（`tripoder-q6.apk`） |
| 探针位置 | `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookApplication.publishApplication()` 之后一次 `reportMaps()`（tag `TripoderMaps`，有界：只统计 + 至多 8 行样本，一次性） |
| 为什么在这里 | 该点即客体 `Application` 的发布点，执行它的进程**就是客体自身**（本次 pid 12010 / uid 10542） |
| 设备 | `d861a4fa`，user 0，Workbench → 应用 → 抖音 |

## 原始输出

```
11-13 00:33:17.737 12010 12010 I TripoderMaps: stage=after-onCreate total=6021 hostPathLines=0 otherAppPathLines=121 tripoderAny=0 apkLines=60
11-13 00:33:17.737 12010 12010 I TripoderMaps:   archive: 792e325000-792ec00000 r--s 0000f000 fe:57 184544  /data/app/~~fOOV7irzXO_mP1JxACdbQQ==/com.google.android.webview-BLeZOS2pXRCXZc7GyBMjNQ==/base.apk
11-13 00:33:17.737 12010 12010 I TripoderMaps:   archive: 7936413000-793fc10000 r-xp 00dd4000 fe:57 184544  /data/app/~~fOOV7irzXO_mP1JxACdbQQ==/com.google.android.webview-BLeZOS2pXRCXZc7GyBMjNQ==/base.apk
11-13 00:33:17.737 12010 12010 I TripoderHardened: Kept guest-published initial application com.ss.android.ugc.aweme.app.host.AwemeHostApplication
11-13 00:33:17.739 12010 12216 D nativeloader: Load /data/app/~~z_ij0CA61jain4y2w89gRg==/com.ss.android.ugc.aweme-Lj7odB_wdtPnGwY-39BgLg==/lib/arm64/libttmplayer.so using class loader ns clns-13 (caller=/data/app/~~z_ij0CA61jain4y2w89gRg==/com.ss.android.ugc.aweme-Lj7odB_wdtPnGwY-39BgLg==/base.apk!classes11.dex): ok
```

字段含义：`total` 映射条数；`hostPathLines` 含 `dev.tripoder` 的条数；`tripoderAny` 含 `tripoder`
（大小写不敏感）的条数；`otherAppPathLines` 含 `/data/app/` 的条数；`apkLines` 含 `.apk`/`.zip` 的条数。

## 判决

**C2 = ❌（路径级）**：6021 条映射里，`tripoder` 出现 **0** 次，`dev.tripoder` 出现 **0** 次；
所有被映射的应用代码都是客体自己的真实包路径
（`/data/app/~~z_ij0…/com.ss.android.ugc.aweme-…/base.apk`、webview APK）。

因此「容器痕迹」**不以文件映射路径的形式**暴露给客体。C 平面里能自证的只剩
**C1：运行 uid 与包名归属不自洽**（本进程 uid=10542，而 `com.ss.android.ugc.aweme` 的真机 uid=10327）。

## 边界（必须与结论一起引用）

- 只测了 **路径字符串**。未覆盖：匿名/`memfd` 映射、线程与优先级分布、`smaps` 私有段、native hook 库的
  尺寸与符号形状。若抖音用 memfd 检测或线程指纹，本实验不含。
- 该 ❌ 只对「路径可见性」成立，不能反推「客体完全无痕迹」。
- 探针是**诊断用**：只在 guest 启动时打一行有界日志，不改变任何门面返回值或行为。

## 复现与环境恢复

1. `adb -s d861a4fa install --no-incremental -r build-maps-v51/tripoder-q6.apk`
2. `adb shell am force-stop dev.tripoder.q6`（必要时同时 `q7`）→ 打开 Workbench → 应用 → 抖音
3. `adb logcat -d | grep TripoderMaps`
4. 已知坑：`AUTHORITY_INITIALIZATION_FAILED:ORPHAN_PROCESS_STILL_LIVE` 时需同时 force-stop
   `dev.tripoder.q6` 与 `dev.tripoder.q7`，重开后再点「重新连接」，第二次点击通常即可进入应用列表。
