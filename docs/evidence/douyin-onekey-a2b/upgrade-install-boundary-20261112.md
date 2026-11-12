# 「立即升级」到 100% 却不弹安装：跨应用安装意图的边界实测

设备 d861a4fa，容器 dev.tripoder.q6（uid 10542），抖音 400701。2026-11-12 20:52 与 21:00–21:05 各一次全程抓取。

## 1. 现象

拦截弹窗底部「立即升级」按钮在点下后变成进度条（`13%正在升级` → `61%正在升级` → 100%），
进度到 100% 后弹窗关闭、回到登录页，**系统安装界面始终不出现**，也没有任何 toast 或错误提示。

## 2. 抖音确实走完了下载、合并、解析

日志（pid 17653 = 容器 slot0 进程）：

```
20:52:10.584  notification_notify: [... Notification(channel=update_notification ... flags=ONGOING_EVENT ...)]
              ↑ 之后每 1–2 秒刷一次，共刷到 20:52:28；20:52:28.774 flags 变 AUTO_CANCEL（下载完成）
21:05:08.676  PackageParser: ... at /data/user/0/com.ss.android.ugc.aweme/external/install_dmt.apk
21:05:08.677  PackageParser: ... in package: com.ss.android.ugc.aweme
```

`install_dmt.apk` 与 `install_dmt.apk.diff` 两个字符串只出现在 `classes46.dex` / `classes42.dex`
（`Lcom/aweme/storage/FileCacheManagerServiceImpl;`、`LX/1QDs;`、`LX/1QE8;`、`LX/1Gv9;`、`LX/0kw9;`），
即抖音的升级是「下载差量补丁 → 本地与 base.apk 合并出 install_dmt.apk → 解析校验 → 请求安装」。
解析阶段全部是 `Unknown element under <manifest>: queries / meta-data / property / uses-native-library`
这类新 schema 兼容告警，**没有解析失败**。

## 3. 安装意图确实发出，并且到了系统

```
21:05:08.733  17653  TripoderActivities: BOUNDARY caller=dev.tripoder.q6
21:05:08.734  17653  TripoderActivities: EXTERNAL start handed off: target=null;action=android.intent.action.VIEW
21:05:08.745   3876  AppStartScenario: notifyScenarioChanged: active=true param=Bundle[{intent=Intent {
                     act=android.intent.action.VIEW
                     dat=content://com.ss.android.ugc.aweme.fileprovider/...
                     typ=application/vnd.android.package-archive
                     flg=0x10000001 xflg=0x4 (has extras) }, callerUid=10542, type=2, state=0}]
```

intent 形状完全正确：抖音自己的 FileProvider content URI、正确的 MIME、
`flg=0x10000001` = `FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION`。
`AppStartScenario` 是 system_server 侧记录，证明这次调用已进入 ActivityTaskManager。

## 4. 但系统没有启动任何 Activity

同一时间窗（21:05:08 – 21:07）全 buffer 检索，三项全为零：

- `events` buffer 无 `am_create_activity` / `wm_*` 新窗口，焦点始终停在 `DYLoginActivity`；
- `packageinstaller` 进程、`InstallStart` / `NewInstallerPrepareActivity` 无任何记录；
- `installd` / `Installing` / `Verification` 无任何记录。

⇒ 失败发生在 **ActivityManager 解析/授权阶段**，不是安装器拒绝、不是下载失败、不是抖音放弃。

## 5. 对照实验（决定性）

同一个 intent，换调用方身份重放：

```
$ adb shell am start -a android.intent.action.VIEW -t application/vnd.android.package-archive \
    -d content://com.ss.android.ugc.aweme.fileprovider/test.apk
Starting: Intent { act=android.intent.action.VIEW dat=content://... typ=application/vnd.android.package-archive }

$ dumpsys window | grep mCurrentFocus
  mCurrentFocus=Window{... com.miui.packageinstaller/com.miui.packageInstaller.NewInstallerPrepareActivity}
$ ps -A | grep packageinstaller
  11830 10140 com.miui.packageinstaller
```

shell（uid 2000，持有安装类权限）→ **安装器立刻可见可交互**；
容器（uid 10542）→ 完全无反应。

intent 形状、MIME、data scheme、category、目标组件是否存在，全部被这次对照排除。
据此只剩下一个候选：**调用方是否被允许发起 APK 安装**（这就是 §6 的假设，已在 §9 被实测推翻，勿当作结论读）。

## 6. 当时的假设（**已被 §9 推翻**）：宿主没有声明 REQUEST_INSTALL_PACKAGES

> **本节作废。** v49 已补齐该权限（`dumpsys` 计数 1、`appops=allow`），现象完全不变。保留本节只为记录当时从「shell 能起、容器不能起」到「缺安装权限」的推理链，勿据此再改代码。

```
$ dumpsys package dev.tripoder.q6 | grep -c REQUEST_INSTALL_PACKAGES     # → 0（v47 及以前）
$ cmd appops get dev.tripoder.q6 REQUEST_INSTALL_PACKAGES
  No operations.  Default mode: default
```

同一份 dumpsys 的可见性段落显示 `com.miui.packageinstaller` 本来就在 `queries via package name` 里，
`forceQueryable=false`、`QUERY_ALL_PACKAGES granted=true` —— 所以这**不是**包可见性问题：
安装器可见，但 Android 只把 `VIEW + application/vnd.android.package-archive` 交给
**持有 REQUEST_INSTALL_PACKAGES 的调用方**。宿主没有该权限，隐式解析结果为空，
`startActivity` 抛 `ActivityNotFoundException`，抖音内部捕获后静默结束 —— 于是「进度 100%，什么都没有」。

这与本仓库既有的边界问题同源：容器的自我报告（虚拟 PMS 里抖音当然"能安装"）与
系统对真实包 `dev.tripoder.q6` 的盖章授权是两回事。

## 7. 修复（已落到 v49）

`android/engine/AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<queries>
  ...
  <intent><action android:name="android.intent.action.VIEW"/>
          <data android:mimeType="application/vnd.android.package-archive"/></intent>
  <package android:name="com.miui.packageinstaller"/>
  <package android:name="com.android.packageinstaller"/>
  <package android:name="com.google.android.packageinstaller"/>
</queries>
```

`build-install-v49/tripoder-q6.apk`（sha256 `a11cd95c0e66fee26410e0a06a98dbf7f5d379392529517d7cab473ccadaa106`）已安装，
`dumpsys package dev.tripoder.q6 | grep -c REQUEST_INSTALL_PACKAGES` → 1，
`cmd appops set dev.tripoder.q6 REQUEST_INSTALL_PACKAGES allow` 后 `cmd appops get` → `allow`。

**验证状态：已补齐（结果为否定）。** 见 §9。

## 8. 这次发现不改变登录结论

即使安装最终成功，装的也只是设备上的原生抖音（覆盖 `/data/app/.../base.apk`），
容器客体仍是同一份 400701 APK；而原生抖音 400701 在同一台设备、同一账号下可以正常登录，
说明拦截判据不是版本。升级路径对「容器内抖音登录」无效。

## 9. v49 端到端复现：权限不是卡点（21:26–21:30）

v49（`REQUEST_INSTALL_PACKAGES` 已声明、`appops=allow`）冷启动重跑同一条路径：

```
21:26:46  点「立即升级」，进度 2% → 10%
21:29:51.071  PackageParser 解析 external/install_dmt.apk（本次为缓存包，3 分钟内到 100%）
21:29:51.132  TripoderActivities: EXTERNAL start handed off: target=null;action=android.intent.action.VIEW
21:29:51.151  AppStartScenario: act=VIEW dat=content://com.ss.android.ugc.aweme.fileprovider/...
              typ=application/vnd.android.package-archive flg=0x10000001, callerUid=10542
21:29:51.154  CoreBackPreview: DYLoginActivity Setting back callback null（交付任务被收束）
```

全窗口复核，仍然三项为零：无 `InstallStart`/`NewInstallerPrepareActivity`、`ps -A | grep packageinstaller` 空、
无 `installd`/`Installing`；焦点回到登录页，弹窗消失。

⇒ **`REQUEST_INSTALL_PACKAGES` 不是卡点**，前一节的假设被这次实测推翻（权限仍应保留，但解释不了现象）。

## 10. 真正的卡点：content URI 的 authority 在外层系统里属于「另一个 uid」

三条可实测的结构事实叠在一起：

1. 抖音交给系统的是**自己的** FileProvider URI：`content://com.ss.android.ugc.aweme.fileprovider/...`
2. 外层 PMS 把该 authority 归属给**真机安装的抖音（uid 10327）**：
   ```
   $ dumpsys package com.ss.android.ugc.aweme | grep fileprovider
     [com.ss.android.ugc.aweme.fileprovider]: ...
   ```
3. 而调用方是**容器 uid 10542**，且宿主 `dev.tripoder.q6` 自己**一个 ContentProvider 都没有**：
   ```
   $ dumpsys package dev.tripoder.q6 | grep -c 'authority='
   0
   ```

于是 `FLAG_GRANT_READ_URI_PERMISSION` 这一步做不成：ATMS 要向安装器授予读权限，就必须承认「发起方有权转授这个 URI」，
而这个 URI 的属主 uid 是 10327，发起方是 10542 —— 授权链断在这里，startActivity 在解析/授权阶段中止，
安装器既不启动也无系统级日志（对照 §5：同样的 intent 由 shell(2000) 发出，安装器立刻起来）。

雪上加霜的是第二层：即使授权成功、安装器起来了，它要读的那个文件在容器私有目录
（`/data/user/0/com.ss.android.ugc.aweme/external/install_dmt.apk`，客体视角路径），
不在真机抖音 FileProvider 的任何 root 里 —— 读到的只会是「文件不存在」。

⇒ 结论：容器**没有 provider 投影平面**时，「客体把私有文件交给外部应用读」这一类握手在结构上不可能完成。
这不是抖音的行为，也不是版本或权限问题，是容器缺一整个平面。

修法（若要做）不是再加权限，而是在容器里补 provider 投影：
宿主声明自己的 FileProvider（`grantUriPermissions=true`），跨应用 hand-off 时把客体的
`content://<guest authority>/<path>` 重写成 `content://<host authority>/<token>` 并落到宿主可读的暂存路径，
再由 ATMS 正常授予。工作量与风险都远超「登录」这条主线所需。

