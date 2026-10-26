# 前手机全部应用迁移至 Tripoder 的兼容性测试报告

- 报告日期：2026-10-23
- 数据批次：`compat-run1`（一应用一空间；重点失败项在 `qa2-*` 新空间复测）
- 测试设备：前手机 Xiaomi M154FF（`d861a4fa`），Android 17 / API 37，arm64-v8a
- 测试对象：`dev.tripoder.engine`（Tripoder 应用虚拟化运行时，MCP protocol 2）
- 第三方包总数：**123**（`pm list packages -3` 共 130 个，排除 `dev.tripoder.*` 7 个测试夹具）
- 其中：有 Launcher 入口 **111** 个，无入口的预装组件 **12** 个
- 首轮结果：安装 **104/123** 成功，首屏通过 **54**（占首轮实测 51.9%）
- 复测后结果：复测 **21** 个失败项，**3** 个转为通过；最终安装 **105/123** 成功，启动实测 **105** 个，首屏通过 **57**（54.3%），窗口内无自身 FATAL 的稳定通过 **56** 个

> 逐项核验入口：每个应用的清单/导入/启动/日志记录行号见 **附录 B**；设备清单原始抓取见 `artifacts/compat-run1/evidence/00-device.txt`、`01-pm-list-packages-3.raw.txt`、`03-app-set-123.txt`；汇总数字可用 `python3 scripts/qa/verify_evidence.py` 逐条复算。

> 判定口径：`PASS` = 进程在 18 s 稳定窗口内存活，且应用自己的 Activity 进入前台或 UI 树可读；`稳定通过` 进一步要求窗口内没有该应用自身进程的 FATAL。两者都不等于登录、联网、支付等业务功能可用。

## 1. 结论摘要

| 指标 | 数量 | 说明 |
|---|---:|---|
| 第三方包总数 | 123 | 完整清单，含 12 个预装 data-app 组件 |
| 有 Launcher 入口 | 111 | 可作为“应用”启动 |
| 无 Launcher 入口 | 12 | 输入法、相册/视频编辑插件、系统服务组件 |
| 导入成功 | 105 | 占可启动应用的 94.6% |
| 完成冷启动测试 | 105 | 每个 18 s 稳定窗口，四路信号判定 |
| 首屏通过（复测后） | 57 | 占实测的 54.3%；首轮为 54/104（51.9%） |
| 稳定通过（无自身 FATAL） | 56 | 扣掉“首屏出来后立刻崩”的应用 |
| 未通过/未完成 | 66 | 18 个导入失败 + 48 个启动失败 |

**一句话结论**：123 个第三方包中 105 个成功导入 Tripoder，57 个（54.3%）在冷启动窗口内正常把首屏拉到前台；其余 66 个里，18 个卡在导入契约，48 个卡在启动契约、引擎稳定性或应用自身。微信、淘宝、抖音、小红书、美团、京东、拼多多、Telegram、ChatGPT/Claude/DeepSeek 等主要应用通过；支付宝、Chrome、YouTube、WPS、哔哩哔哩等失败项已定位到具体运行时契约。

失败按责任层拆分：

| 层 | 数量 | 主要问题 |
|---|---:|---|
| 安装契约 | 18 | 包可见性 12、原生库路径规则 2、组件重复声明 2、上传大小上限 1、uses-split 依赖 1 |
| 启动契约覆盖不足 | 28 | launchMode/alias、通知与 URI 契约、ClassLoader 身份、可选库、OEM(MIUIX) 资源等（含少量未归因） |
| 引擎稳定性 | 14 | worker/authority 死亡、恢复围栏、安装产物不一致、slot 崩溃、全局 affinity 容量耗尽 |
| 应用自身 | 6 | 应用进程内崩溃且无运行时归因、无首屏、静默退出、应用代码/split 缺失 |

导入结果分布：

| 导入状态 | 数量 |
|---|---:|
| SUCCEEDED | 105 |
| IMPORT_FAILED | 18 |

启动判定分布（仅已导入并测试的应用）：

| 判定 | 含义 | 数量 |
|---|---|---:|
| `PASS` | 通过 | 57 |
| `START_REJECTED` | 启动被拒 | 28 |
| `CRASH_ON_START` | 启动崩溃 | 17 |
| `PROCESS_GONE` | 进程退出 | 2 |
| `SCOPE_FENCED` | 运行时围栏 | 1 |

## 2. 测试方法

1. **清点**：`adb shell pm list packages -3` 取全部第三方包，记录版本、min/targetSdk、APK 大小、split 数；排除 `dev.tripoder.*` 的 7 个测试夹具。
2. **导入**：通过 Tripoder MCP `application_manage(import_device)` 将原始 APK/split 导入每个应用独立的 `qa-<pkg>` 空间；安装包只在 commit 时进入安装事务，导入阶段不执行 guest 代码。
3. **冷启动**：先把 Workbench 置前，再 `process_start`；首轮等待 18 s 稳定窗口，复测等待 20 s。
4. **四路判定**：① `process_start` 返回 pid/processGeneration；② `application_query(targets)` 仍能枚举到 guest；③ `mCurrentFocus`/`ResumedActivity` 属于 guest；④ logcat crash/main/system 中没有该 pid/包的 FATAL/ANR。四路中任一路径证明“进程活着且自己的界面在前台”才记 `PASS`。
5. **失败复测**：对重点失败项（传输/引擎中断、恢复围栏、导入后未启动等）换新 `qa2-*` 空间或新 operationId 再跑一次；复测证据单独保存在 `retry.jsonl`，不覆盖首轮结果。
6. **运行模式**：全部结果都在引擎默认的 THIN 模式下获得（安装的 `mode` 字段为 `thin`，全程未调用 `set_mode`）；THICK 的既有覆盖与本轮补测结论单独列在第 9 节。

> **空间策略**：本轮不是“一个空间装 123 个应用”，而是一应用一空间。原因见附录 A1：一次 UNKNOWN 启动会围栏整个空间，且没有 MCP 收敛入口；同空间测试会让第一个异常应用污染后续所有应用。修复 A1 后，可以把已验证通过的应用合并到一个空间。

## 3. 逐应用结果

| # | 应用 | 包名 | 版本 | targetSdk | 大小 | 导入 | 启动判定 | 根因/证据 |
|---:|---|---|---|---:|---:|---|---|---|
| 1 | WPS Office 小米版 | `cn.wps.moffice_eng.xiaomi.lite` | 4.4.0 | 35 | 17 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:cn.wps.moffice_eng.xiaomi.lite |
| 2 | 元梦之星 | `com.tencent.tmgp.WePop` | 1.46.2 | 30 | 1504 MB | 未导入 | `IMPORT_FAILED` | IllegalArgumentException:UPLOAD_FILE_SIZE |
| 3 | 内容中心 | `com.miui.newhome` | 11.1.09.2254 | 35 | 31 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.miui.newhome |
| 4 | 垃圾清理 | `com.miui.cleanmaster` | 1061.0.260825.release | 36 | 16 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.miui.cleanmaster |
| 5 | 小爱语音 | `com.xiaomi.mibrain.speech` | 1.6.5 | 35 | 31 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.xiaomi.mibrain.speech |
| 6 | 小米云盘 | `com.miui.newmidrive` | 1.12.0.9.12 | 34 | 6 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.miui.newmidrive |
| 7 | 小米画报 | `com.mfashiongallery.emag` | 25200034-CAROUSEL | 35 | 19 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.mfashiongallery.emag |
| 8 | 屏幕录制 | `com.miui.screenrecorder` | 4.16.2.9.1 | 36 | 5 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.miui.screenrecorder |
| 9 | 平安证券 | `com.hundsun.winner.pazq` | 10.7.2.1 | 33 | 263 MB | 未导入 | `IMPORT_FAILED` | IllegalStateException:java.io.IOException:ABI_OR_LIBRARY_UNSUPPORTED |
| 10 | 快手 | `com.smile.gifmaker` | 14.8.40.50567 | 30 | 179 MB | 未导入 | `IMPORT_FAILED` | IllegalStateException:java.io.IOException:BASE_DEPENDENCY_INVALID |
| 11 | 最美证件照 | `com.xiaomi.bestphotos` | 1.0.16 | 35 | 44 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.xiaomi.bestphotos |
| 12 | 火车票抢票 | `com.pangsheng.pshuochepiao.m` | 1.1.0.F | 33 | 88 MB | 未导入 | `IMPORT_FAILED` | IllegalStateException:java.io.IOException:COMPONENT_COLLISION |
| 13 | 百度网盘 | `com.baidu.netdisk` | 13.34.3 | 31 | 382 MB | 未导入 | `IMPORT_FAILED` | IllegalStateException:java.io.IOException:ABI_OR_LIBRARY_UNSUPPORTED |
| 14 | 百度输入法小米版 | `com.baidu.input_mi` | 10.6.125.158 | 35 | 84 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.baidu.input_mi |
| 15 | 相册插件 | `com.hyper.gallery.plugin` | 5.4.2.6 | 36 | 24 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.hyper.gallery.plugin |
| 16 | 腾讯视频 | `com.tencent.qqlive` | 9.04.60.32352 | 33 | 154 MB | 未导入 | `IMPORT_FAILED` | IllegalStateException:java.io.IOException:COMPONENT_COLLISION |
| 17 | 视频编辑 | `com.miui.mediaeditor` | 2.10.40.5.7 | 36 | 206 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.miui.mediaeditor |
| 18 | 讯飞输入法小米版 | `com.iflytek.inputmethod.miui` | 8.1.8523 | 36 | 57 MB | 未导入 | `IMPORT_FAILED` | NameNotFoundException:com.iflytek.inputmethod.miui |
| 19 | BoC Pay | `com.bochk.bocpay` | 3.1.24 | 35 | 126 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 20 | Chrome | `com.android.chrome` | 151.0.7922.71 | 36 | 271 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_LAUNCH_MODE_UNSUPPORTED |
| 21 | Gmail | `com.google.android.gm` | 2026.10.12.1003320221.Release | 37 | 123 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.UnsupportedOperationException: URI_GRANT_NOT_IMP |
| 22 | Google | `com.google.android.googlequicksearchbox` | 17.66.12.ve.arm64 | 37 | 315 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 23 | Instagram | `com.instagram.android` | 452.1.0.53.64 | 37 | 245 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.RuntimeException: java.io.IOException: error rem |
| 24 | Microsoft 365 | `com.microsoft.office.officehubrow` | 16.0.20527.20022 | 35 | 297 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 25 | WPS Office | `cn.wps.moffice_eng` | 26.9.1 | 36 | 184 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_POST_ATTACH_I |
| 26 | YouTube | `com.google.android.youtube` | 21.42.243 | 37 | 138 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 27 | 下载管理 | `com.android.providers.downloads.ui` | 126.07.02.806006 | 35 | 31 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[ |
| 28 | 中银 Connect | `com.bocconnect.prd` | 1.1.25 | 35 | 66 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 29 | 中银香港 | `com.bochk.bn.aos` | 1.2.0 | 36 | 33 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 30 | 中银香港企业版 | `com.bochk.igtb` | 1.0.58 | 36 | 97 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 31 | 中银香港按揭 | `com.bochk.mortgage.android.hk` | 4.4.8 | 36 | 73 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 32 | 主题商店 | `com.miui.themestore` | 45.0 | 35 | 2 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 33 | 今日头条 | `com.ss.android.article.news` | 18.6.0 | 33 | 194 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 34 | 今日头条极速版 | `com.ss.android.article.lite` | 18.6.0.0 | 33 | 145 MB | 成功 | `CRASH_ON_START` | 1792687086.222 12695 12695 E AndroidRuntime: FATAL EXCEPTION: main |
| 35 | 哔哩哔哩 | `tv.danmaku.bili` | 9.14.0 | 35 | 210 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 36 | 多看阅读 | `com.duokan.reader` | 8.4.19 | 35 | 74 MB | 成功 | `CRASH_ON_START` | 1792686379.041 19899 19899 E AndroidRuntime: FATAL EXCEPTION: main |
| 37 | 多邻国 | `com.duolingo` | 6.102.4 | 36 | 181 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.UnsupportedOperationException: NOTIFICATION_CAPA |
| 38 | 天气 | `com.miui.weather2` | 18.0.0.25-R | 36 | 62 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.ClassNotFoundException: Didn't find class "andro |
| 39 | 夸克浏览器 | `com.quark.browser` | 10.18.5.1160 | 30 | 177 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 40 | 安全中心 | `com.miui.securitymanager` | 21.0 | 36 | 5 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 41 | 小爱同学 | `com.miui.voiceassistProxy` | 1.6.1 | 33 | 1 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 42 | 小米健康 | `com.mi.health` | 3.59.7 | 35 | 123 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[ |
| 43 | 小米商城 | `com.xiaomi.shop` | 5.54.0.20260811.r1 | 30 | 275 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.ClassNotFoundException: Didn't find class "com.x |
| 44 | 小米换机 | `com.miui.huanji` | 4.6.1.8 | 36 | 39 MB | 成功 | `CRASH_ON_START` | Caused by: android.content.res.Resources$NotFoundException: File res/OO.xml from drawable resource ID #0x7f080131 |
| 45 | 小米社区 | `com.xiaomi.vipaccount` | 6.8.0 | 35 | 41 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathL |
| 46 | 小米视频 | `com.miui.video` | v2026101190(MiVideo-UN) | 33 | 90 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathL |
| 47 | 小米遥控器 | `com.duokan.phone.remotecontroller` | 7.2.5M | 34 | 33 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: org.apache.commons.logging.LogFactory |
| 48 | 录音机 | `com.android.soundrecorder` | 8.1.5-779121d13 | 35 | 25 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathL |
| 49 | 扫一扫 | `com.xiaomi.scanner` | 15.9.78 | 35 | 81 MB | 成功 | `SCOPE_FENCED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: RECOVERY_SCOPE_UNKNOWN:67 |
| 50 | 指南针 | `com.miui.compass` | 17.1.4.1 | 35 | 5 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: org.apache.commons.logging.LogFactory |
| 51 | 支付宝 | `com.eg.android.AlipayGphone` | 12.12.30.8000 | 35 | 178 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "org.apache.http.client.HttpRequestRetryHandler" on path: |
| 52 | 日历 | `com.android.calendar` | 18.0.1.30 | 36 | 15 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[ |
| 53 | 时钟 | `com.android.deskclock` | 18.28.0 | 35 | 17 MB | 成功 | `START_REJECTED` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 54 | 爱奇艺 | `com.qiyi.video` | 17.9.5 | 36 | 101 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:com.tencent.tinker.loader.TinkerRuntimeException: Tinker E |
| 55 | 王者荣耀 | `com.tencent.tmgp.sgame` | 12.1.1.1 | 30 | 669 MB | 成功 | `CRASH_ON_START` | java.lang.IllegalStateException: RECOVERY_SCOPE_UNKNOWN:67fabff0-bf58-4992-945a-ab876e728945 |
| 56 | 百度 | `com.baidu.searchbox` | 15.77.0.10 | 35 | 169 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.IllegalStateException: ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 57 | 相册 | `com.miui.gallery` | 5.4.2.23-0920-cn | 36 | 99 MB | 成功 | `CRASH_ON_START` | 1792687518.205 21036 21036 E AndroidRuntime: FATAL EXCEPTION: main |
| 58 | 网易云音乐 | `com.netease.cloudmusic` | 9.6.05 | 33 | 178 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: PHYSICAL_APPLICATION_INIT |
| 59 | 网络身份认证 | `cn.cyberIdentity.certification` | 1.2.40 | 35 | 69 MB | 成功 | `START_REJECTED` | IllegalStateException:android.os.DeadObjectException:null |
| 60 | 腾讯新闻 | `com.tencent.news` | 8.0.40 | 34 | 105 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_POST_ATTACH_I |
| 61 | 芒果 TV | `com.hunantv.imgo.activity` | 9.4.3 | 30 | 291 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_OWNERSHIP_VIO |
| 62 | 计算器 | `com.miui.calculator` | 17.3.0 | 35 | 9 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathL |
| 63 | 豆包 | `com.larus.nova` | 15.2.0 | 35 | 389 MB | 成功 | `START_REJECTED` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.NullPointerException: Attempt to read from field |
| 64 | 通义 | `com.aliyun.tongyi` | 7.4.0.3048 | 30 | 121 MB | 成功 | `PROCESS_GONE` |  |
| 65 | 邮件 | `com.android.email` | V13_20260821_d3 | 36 | 29 MB | 成功 | `CRASH_ON_START` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathL |
| 66 | 钉钉 | `com.alibaba.android.rimet` | 9.0.2.2 | 33 | 321 MB | 成功 | `PROCESS_GONE` |  |
| 67 | BBC News | `bbc.mobile.news.ww` | 2026.9.0 | 36 | 146 MB | 成功 | `PASS` |  |
| 68 | BOSS 直聘 | `com.hpbr.bosszhipin` | 14.170 | 33 | 135 MB | 成功 | `PASS` |  |
| 69 | Bitwarden | `com.x8bit.bitwarden` | 2026.8.1 | 37 | 130 MB | 成功 | `PASS` |  |
| 70 | Booking.com | `com.booking` | 69.2.0.1 | 37 | 310 MB | 成功 | `PASS` |  |
| 71 | ChatGPT | `com.openai.chatgpt` | 1.2026.279 | 37 | 211 MB | 成功 | `PASS` |  |
| 72 | Claude | `com.anthropic.claude` | 1.261020.20 | 37 | 50 MB | 成功 | `PASS` |  |
| 73 | DeepSeek | `com.deepseek.chat` | 2.6.1 | 36 | 21 MB | 成功 | `PASS` |  |
| 74 | Gemini | `com.google.android.apps.bard` | 1.0.971139365 | 37 | 5 MB | 成功 | `PASS` |  |
| 75 | GhostLock | `com.ghostlock.app` | 1.1 | 37 | 60 MB | 成功 | `PASS` |  |
| 76 | GitHub | `com.github.android` | 1.280.0 | 37 | 40 MB | 成功 | `PASS` |          1792686443.583 27149 27258 E AndroidRuntime: FATAL EXCEPTION: WM.task-4          1792686443.583 27149 27258 E A |
| 77 | Google 身份验证器 | `com.google.android.apps.authenticator2` | 7.2 | 37 | 8 MB | 成功 | `PASS` |  |
| 78 | LinkTools | `app.personal.linktools` | 2.11.34 | 35 | 46 MB | 成功 | `PASS` |  |
| 79 | QQ | `com.tencent.mobileqq` | 9.3.65 | 34 | 374 MB | 成功 | `PASS` |  |
| 80 | Storyline | `com.fenxiang.storyline` | 1.5.21 | 36 | 27 MB | 成功 | `PASS` |  |
| 81 | Telegram | `org.telegram.messenger` | 13.0.2 | 36 | 49 MB | 成功 | `PASS` |  |
| 82 | VLC | `org.videolan.vlc` | 3.7.1 | 36 | 94 MB | 成功 | `PASS` |  |
| 83 | WPS 协作 | `com.kingsoft.xiezuo` | 5.55.0 | 35 | 331 MB | 成功 | `PASS` |  |
| 84 | Wikipedia | `org.wikipedia` | 50612-r-2026-10-19 | 37 | 51 MB | 成功 | `PASS` |  |
| 85 | X（Twitter） | `com.twitter.android` | 12.36.1-prod.01 | 36 | 199 MB | 成功 | `PASS` |  |
| 86 | 京东 | `com.jingdong.app.mall` | 16.0.50 | 35 | 85 MB | 成功 | `PASS` |  |
| 87 | 全球上网 | `com.miui.virtualsim` | 9.1.9.0 | 35 | 26 MB | 成功 | `PASS` |  |
| 88 | 原神 | `com.miHoYo.Yuanshen` | 7.1.0_48052158_48145775 | 36 | 490 MB | 成功 | `PASS` |  |
| 89 | 同程旅行 | `com.tongcheng.android` | 11.5.0 | 30 | 102 MB | 成功 | `PASS` |  |
| 90 | 唯品会 | `com.achievo.vipshop` | 9.84.7 | 33 | 98 MB | 成功 | `PASS` |  |
| 91 | 大众点评 | `com.dianping.v1` | 11.72.12 | 30 | 86 MB | 成功 | `PASS` |  |
| 92 | 安兔兔评测 | `com.antutu.ABenchMark` | 12.0.1(OB-1) | 36 | 150 MB | 成功 | `PASS` |  |
| 93 | 小米小游戏 | `com.xiaomi.tinygame` | 1.50.3 | 34 | 23 MB | 成功 | `PASS` |  |
| 94 | 小米有品 | `com.xiaomi.youpin` | 5.32.1 | 30 | 143 MB | 成功 | `PASS` |  |
| 95 | 小米游戏中心 | `com.xiaomi.gamecenter` | 13.52.1.300 | 34 | 81 MB | 成功 | `PASS` |  |
| 96 | 小米钱包 | `com.mipay.wallet` | 6.115.0.5772.2754 | 34 | 100 MB | 成功 | `PASS` |  |
| 97 | 小红书 | `com.xingin.xhs` | 9.49.1 | 36 | 174 MB | 成功 | `PASS` |  |
| 98 | 平安口袋银行 | `com.pingan.paces.ccms` | 8.9.0 | 33 | 276 MB | 成功 | `PASS` |  |
| 99 | 微信 | `com.tencent.mm` | 8.0.79 | 34 | 272 MB | 成功 | `PASS` |  |
| 100 | 微博 | `com.sina.weibo` | 16.9.3 | 35 | 178 MB | 成功 | `PASS` |  |
| 101 | 抖音 | `com.ss.android.ugc.aweme` | 40.7.0 | 34 | 343 MB | 成功 | `PASS` |  |
| 102 | 招商银行 | `cmb.pb` | 14.4.5 | 34 | 159 MB | 成功 | `PASS` |  |
| 103 | 拼多多 | `com.xunmeng.pinduoduo` | 8.26.0 | 34 | 25 MB | 成功 | `PASS` |  |
| 104 | 携程旅行 | `ctrip.android.view` | 8.96.4 | 30 | 98 MB | 成功 | `PASS` |  |
| 105 | 汽水音乐 | `com.luna.music` | 21.1.0 | 30 | 191 MB | 成功 | `PASS` |  |
| 106 | 淘宝 | `com.taobao.taobao` | 10.66.30 | 33 | 74 MB | 成功 | `PASS` |  |
| 107 | 滴滴出行 | `com.sdu.didi.psnger` | 8.0.14 | 35 | 73 MB | 成功 | `PASS` |  |
| 108 | 番茄免费小说 | `com.dragon.read` | 7.3.7.33 | 35 | 128 MB | 成功 | `PASS` |  |
| 109 | 番茄畅听 | `com.xs.fm` | 6.7.1.32 | 34 | 104 MB | 成功 | `PASS` |  |
| 110 | 疯读小说 | `com.phoenix.read` | 7.3.9.32 | 35 | 139 MB | 成功 | `PASS` |  |
| 111 | 百度地图 | `com.baidu.BaiduMap` | 22.0.0 | 33 | 217 MB | 成功 | `PASS` |  |
| 112 | 知乎 | `com.zhihu.android` | 11.10.0 | 34 | 175 MB | 成功 | `PASS` |  |
| 113 | 笔记 | `com.miui.notes` | 3.2.5.1 | 36 | 81 MB | 成功 | `PASS` |  |
| 114 | 米家 | `com.xiaomi.smarthome` | 11.8.705 | 36 | 228 MB | 成功 | `PASS` |  |
| 115 | 米游社 | `com.mihoyo.hyperion` | 2.116.0 | 32 | 129 MB | 成功 | `PASS` |  |
| 116 | 美团 | `com.sankuai.meituan` | 12.66.404 | 30 | 87 MB | 成功 | `PASS` |  |
| 117 | 美团外卖 | `com.sankuai.meituan.takeoutnew` | 8.83.2 | 30 | 63 MB | 成功 | `PASS` |  |
| 118 | 脉脉 | `com.taou.maimai` | 6.7.56 | 34 | 85 MB | 成功 | `PASS` |  |
| 119 | 腾讯手游（osgame） | `com.tencent.tmgp.osgame` | 1.1.1 | 30 | 24 MB | 成功 | `PASS` |  |
| 120 | 闲鱼 | `com.taobao.idlefish` | 7.28.50 | 33 | 115 MB | 成功 | `PASS` |  |
| 121 | 阿里云盘 | `com.alicloud.databox` | 6.14.0 | 33 | 153 MB | 成功 | `PASS` |  |
| 122 | 音乐 | `com.miui.player` | 4.46.2.1 | 36 | 159 MB | 成功 | `PASS` |  |
| 123 | 高德地图 | `com.autonavi.minimap` | 17.00.0.2005 | 35 | 186 MB | 成功 | `PASS` |  |

### 3.1 复测对照

首轮失败项在全新 `qa2-*` 空间复测，共 **21** 个：**3** 个转为 `PASS`，其余维持失败。下表只列复测过的应用，复测结论覆盖首轮。

| 应用 | 包名 | 首轮 | 复测 | 复测空间 | 变化 |
|---|---|---|---|---|---|
| BoC Pay | `com.bochk.bocpay` | `START_REJECTED` | `START_REJECTED` | `qa2-com-bochk-bocpay` | 同一判定、同一原因 |
| 中银 Connect | `com.bocconnect.prd` | `START_REJECTED` | `START_REJECTED` | `qa2-com-bocconnect-prd` | 同一判定、原因变化 |
| 中银香港 | `com.bochk.bn.aos` | `START_REJECTED` | `START_REJECTED` | `qa2-com-bochk-bn-aos` | 同一判定、同一原因 |
| 中银香港企业版 | `com.bochk.igtb` | `START_REJECTED` | `START_REJECTED` | `qa2-com-bochk-igtb` | 同一判定、同一原因 |
| 中银香港按揭 | `com.bochk.mortgage.android.hk` | `START_REJECTED` | `START_REJECTED` | `qa2-com-bochk-mortgage-android-hk` | 同一判定、原因变化 |
| 主题商店 | `com.miui.themestore` | `NO_FOREGROUND_SCREEN` | `START_REJECTED` | `qa2-com-miui-themestore` | 判定改变 |
| 今日头条 | `com.ss.android.article.news` | `START_REJECTED` | `START_REJECTED` | `qa2-com-ss-android-article-news` | 同一判定、原因变化 |
| 哔哩哔哩 | `tv.danmaku.bili` | `START_REJECTED` | `START_REJECTED` | `qa2-tv-danmaku-bili` | 同一判定、原因变化 |
| 天气 | `com.miui.weather2` | `START_REJECTED` | `START_REJECTED` | `qa2-com-miui-weather2` | 同一判定、原因变化 |
| 安全中心 | `com.miui.securitymanager` | `NO_FOREGROUND_SCREEN` | `START_REJECTED` | `qa2-com-miui-securitymanager` | 判定改变 |
| 小爱同学 | `com.miui.voiceassistProxy` | `NO_FOREGROUND_SCREEN` | `START_REJECTED` | `qa2-com-miui-voiceassistproxy` | 判定改变 |
| 扫一扫 | `com.xiaomi.scanner` | `START_REJECTED` | `SCOPE_FENCED` | `qa2-com-xiaomi-scanner` | 判定改变 |
| 相册 | `com.miui.gallery` | `START_REJECTED` | `CRASH_ON_START` | `qa2-com-miui-gallery` | 判定改变 |
| 网易云音乐 | `com.netease.cloudmusic` | `START_REJECTED` | `START_REJECTED` | `qa2-com-netease-cloudmusic` | 同一判定、原因变化 |
| 网络身份认证 | `cn.cyberIdentity.certification` | `START_REJECTED` | `START_REJECTED` | `qa2-cn-cyberidentity-certification` | 同一判定、同一原因 |
| 豆包 | `com.larus.nova` | `START_REJECTED` | `START_REJECTED` | `qa2-com-larus-nova` | 同一判定、同一原因 |
| 通义 | `com.aliyun.tongyi` | `PROCESS_GONE` | `PROCESS_GONE` | `qa2-com-aliyun-tongyi` | 同一判定、同一原因 |
| 钉钉 | `com.alibaba.android.rimet` | `PROCESS_GONE` | `PROCESS_GONE` | `qa2-com-alibaba-android-rimet` | 同一判定、同一原因 |
| Google 身份验证器 | `com.google.android.apps.authenticator2` | `SCOPE_FENCED` | `PASS` | `qa2-com-google-android-apps-authenticator2` | 复测通过 |
| Wikipedia | `org.wikipedia` | `SCOPE_FENCED` | `PASS` | `qa2-org-wikipedia` | 复测通过 |
| 汽水音乐 | `com.luna.music` | `IMPORT_FAILED` | `PASS` | `qa2-com-luna-music` | 复测通过 |

## 4. 无法导入的应用与根因

### 4.1 包可见性（12 个）

**机制**：引擎 `<queries>` 只声明 MAIN/LAUNCHER，Android 11+ 包可见性过滤使 `getApplicationInfo()` 看不到没有启动入口的包。

| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |
|---|---|---|---:|---|---|
| WPS Office 小米版 | `cn.wps.moffice_eng.xiaomi.lite` | 4.4.0 | 17 MB | `journal.jsonl:169` | NameNotFoundException:cn.wps.moffice_eng.xiaomi.lite |
| 内容中心 | `com.miui.newhome` | 11.1.09.2254 | 31 MB | `journal.jsonl:213` | NameNotFoundException:com.miui.newhome |
| 垃圾清理 | `com.miui.cleanmaster` | 1061.0.260825.release | 16 MB | `journal.jsonl:208` | NameNotFoundException:com.miui.cleanmaster |
| 小爱语音 | `com.xiaomi.mibrain.speech` | 1.6.5 | 31 MB | `journal.jsonl:239` | NameNotFoundException:com.xiaomi.mibrain.speech |
| 小米云盘 | `com.miui.newmidrive` | 1.12.0.9.12 | 6 MB | `journal.jsonl:214` | NameNotFoundException:com.miui.newmidrive |
| 小米画报 | `com.mfashiongallery.emag` | 25200034-CAROUSEL | 19 MB | `journal.jsonl:202` | NameNotFoundException:com.mfashiongallery.emag |
| 屏幕录制 | `com.miui.screenrecorder` | 4.16.2.9.1 | 5 MB | `journal.jsonl:217` | NameNotFoundException:com.miui.screenrecorder |
| 最美证件照 | `com.xiaomi.bestphotos` | 1.0.16 | 44 MB | `journal.jsonl:237` | NameNotFoundException:com.xiaomi.bestphotos |
| 百度输入法小米版 | `com.baidu.input_mi` | 10.6.125.158 | 84 MB | `journal.jsonl:179` | NameNotFoundException:com.baidu.input_mi |
| 相册插件 | `com.hyper.gallery.plugin` | 5.4.2.6 | 24 MB | `journal.jsonl:198` | NameNotFoundException:com.hyper.gallery.plugin |
| 视频编辑 | `com.miui.mediaeditor` | 2.10.40.5.7 | 206 MB | `journal.jsonl:212` | NameNotFoundException:com.miui.mediaeditor |
| 讯飞输入法小米版 | `com.iflytek.inputmethod.miui` | 8.1.8523 | 57 MB | `journal.jsonl:199` | NameNotFoundException:com.iflytek.inputmethod.miui |

**修复建议**：在 `android/engine/AndroidManifest.xml` 增加 `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>`（或为这批组件逐项声明 `<queries><package .../></queries>`），让引擎的 `PackageManager` 能看到这些 data-app 组件。

### 4.2 原生库路径规则（2 个）

**机制**：`ArtifactSetZip` 要求 `lib/<已识别ABI>/lib*.so`；APK 里的 `lib/Windows/...dll`、`lib/arm64-v8a/test.so` 被整包拒绝。

| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |
|---|---|---|---:|---|---|
| 平安证券 | `com.hundsun.winner.pazq` | 10.7.2.1 | 263 MB | `journal.jsonl:197` | IllegalStateException:java.io.IOException:ABI_OR_LIBRARY_UNSUPPORTED |
| 百度网盘 | `com.baidu.netdisk` | 13.34.3 | 382 MB | `journal.jsonl:180` | IllegalStateException:java.io.IOException:ABI_OR_LIBRARY_UNSUPPORTED |

**修复建议**：放宽 `ArtifactSetZip.scan()`：只对已知 ABI 目录（arm64-v8a 等）做 `.so` 认证；未知 `lib/...` 目录按不透明资源保留（Android 本身不会解压），并去掉文件名必须以 `lib` 开头的限制。可恢复百度网盘、平安证券。

### 4.3 组件重复声明（2 个）

**机制**：manifest 同名的非 provider 组件声明了不同属性；Android 取最后一条，运行时直接拒绝。

| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |
|---|---|---|---:|---|---|
| 火车票抢票 | `com.pangsheng.pshuochepiao.m` | 1.1.0.F | 88 MB | `journal.jsonl:224` | IllegalStateException:java.io.IOException:COMPONENT_COLLISION |
| 腾讯视频 | `com.tencent.qqlive` | 9.04.60.32352 | 154 MB | `journal.jsonl:147` | IllegalStateException:java.io.IOException:COMPONENT_COLLISION |

**修复建议**：在 `ArtifactSet` 组件合并循环实现 Android 的 take-last 语义（同名非 provider 后者覆盖前者），并为每个组件保留独立的 slot 映射。可恢复腾讯视频、火车票。

### 4.4 上传大小上限（1 个）

**机制**：单文件上限 1 GiB、总量上限 4 GiB；该 APK 为 1.5 GB。

| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |
|---|---|---|---:|---|---|
| 元梦之星 | `com.tencent.tmgp.WePop` | 1.46.2 | 1504 MB | `journal.jsonl:233` | IllegalArgumentException:UPLOAD_FILE_SIZE |

**修复建议**：把 `WorkbenchAgentImports.begin()` 的单文件上限从 1 GiB 提高（设备可用空间允许 2 GiB），或改为分片直传/边读边装；可恢复 1.5 GB 的《和平精英》WePop。

### 4.5 uses-split 依赖（1 个）

**机制**：base manifest 声明了 `<uses-split>`，运行时对任何 split 依赖都拒绝整包。

| 应用 | 包名 | 版本 | 大小 | 记录 | 证据 |
|---|---|---|---:|---|---|
| 快手 | `com.smile.gifmaker` | 14.8.40.50567 | 179 MB | `journal.jsonl:153` | IllegalStateException:java.io.IOException:BASE_DEPENDENCY_INVALID |

**修复建议**：在 `ArtifactSet` 建模 `usesSplits`：split 未随包提供时按“可选/按需”处理，不因 base 的 `<uses-split>` 拒绝整包。可恢复快手。

## 5. 启动失败的应用与根因

### 5.1 OEM 框架依赖 (MIUIX)（9 个）

**机制**：预装 data-app 依赖 HyperOS 的 `miuix` 框架类/资源（如 `SymbolDrawable`、`miuix_appcompat_*`），guest 未注入 OEM 框架。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 下载管理 | `com.android.providers.downloads.ui` | 126.07.02.806006 | `CRASH_ON_START` | `journal.jsonl:295` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.tripoder |
| 小米健康 | `com.mi.health` | 3.59.7 | `CRASH_ON_START` | `journal.jsonl:316` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.tripoder |
| 小米换机 | `com.miui.huanji` | 4.6.1.8 | `CRASH_ON_START` | `journal.jsonl:323` | Caused by: android.content.res.Resources$NotFoundException: File res/OO.xml from drawable resource ID #0x7f080131 |
| 小米社区 | `com.xiaomi.vipaccount` | 6.8.0 | `CRASH_ON_START` | `journal.jsonl:348` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.trip |
| 小米视频 | `com.miui.video` | v2026101190(MiVideo-UN) | `CRASH_ON_START` | `journal.jsonl:328` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.trip |
| 录音机 | `com.android.soundrecorder` | 8.1.5-779121d13 | `CRASH_ON_START` | `journal.jsonl:296` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.trip |
| 日历 | `com.android.calendar` | 18.0.1.30 | `CRASH_ON_START` | `journal.jsonl:292` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.theme.symbol.SymbolDrawable" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.tripoder |
| 计算器 | `com.miui.calculator` | 17.3.0 | `CRASH_ON_START` | `journal.jsonl:320` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.trip |
| 邮件 | `com.android.email` | V13_20260821_d3 | `CRASH_ON_START` | `journal.jsonl:294` | Caused by: java.lang.ClassNotFoundException: Didn't find class "miuix.smooth.SmoothContainerDrawable2" on path: DexPathList[[zip file "/data/app/~~8nV-c_tefUCwjaLQ4T6unA==/dev.trip |

> 注：小米换机不是“类缺失”，而是 miuix 资源 `miuix_action_icon_back_light`（`0x7f080131`）在 artifact 中解析到 `res/OO.xml` 后加载失败；属于资源打包/路径映射问题与 OEM 资源依赖叠加，单独修 miuix 注入不足以恢复。

**修复建议**：这类 `/product/data-app` 预装应用不是自包含 APK：把 HyperOS 的 `miuix` 框架 jar/资源注入 guest，或明确将“预装 OEM 应用”排除在可迁移集合之外；它们不适合作为第三方应用兼容性样本。

### 5.2 引擎 worker 中断（6 个）

**机制**：`:authority` binder 或 slot worker 死亡，属于引擎稳定性问题，需复测。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| BoC Pay | `com.bochk.bocpay` | 3.1.24 | `START_REJECTED` | `retry.jsonl:14` | IllegalStateException:android.os.DeadObjectException:null |
| 中银 Connect | `com.bocconnect.prd` | 1.1.25 | `START_REJECTED` | `retry.jsonl:10` | IllegalStateException:android.os.DeadObjectException:null |
| 中银香港 | `com.bochk.bn.aos` | 1.2.0 | `START_REJECTED` | `retry.jsonl:12` | IllegalStateException:android.os.DeadObjectException:null |
| 中银香港企业版 | `com.bochk.igtb` | 1.0.58 | `START_REJECTED` | `retry.jsonl:16` | IllegalStateException:android.os.DeadObjectException:null |
| 中银香港按揭 | `com.bochk.mortgage.android.hk` | 4.4.8 | `START_REJECTED` | `retry.jsonl:18` | IllegalStateException:android.os.DeadObjectException:null |
| 网络身份认证 | `cn.cyberIdentity.certification` | 1.2.40 | `START_REJECTED` | `retry.jsonl:4` | IllegalStateException:android.os.DeadObjectException:null |

**修复建议**：收集 `:authority`/slot 的 tombstone/Java 栈；对 DeadObjectException/WORKER_DIED 做一次自动重连+重试，而不是把它记成应用失败。

### 5.3 activity-alias 容量（6 个）

**机制**：`RuntimeActivitySlots.ALIASES=2`，声明超过 2 个 alias 的应用无法启动。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| Google | `com.google.android.googlequicksearchbox` | 17.66.12.ve.arm64 | `START_REJECTED` | `journal.jsonl:311` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| Microsoft 365 | `com.microsoft.office.officehubrow` | 16.0.20527.20022 | `START_REJECTED` | `journal.jsonl:281` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| YouTube | `com.google.android.youtube` | 21.42.243 | `START_REJECTED` | `journal.jsonl:256` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 夸克浏览器 | `com.quark.browser` | 10.18.5.1160 | `START_REJECTED` | `journal.jsonl:334` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 时钟 | `com.android.deskclock` | 18.28.0 | `START_REJECTED` | `journal.jsonl:293` | IllegalStateException:ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |
| 百度 | `com.baidu.searchbox` | 15.77.0.10 | `CRASH_ON_START` | `journal.jsonl:298` | Caused by: java.lang.IllegalStateException: ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED |

**修复建议**：把 `RuntimeActivitySlots.ALIASES` 从 2 提升为按 manifest 动态分配（或至少 16），并保持 alias→target 的一一映射。

### 5.4 activity-affinity 容量（5 个）

**机制**：`RuntimeActivitySlots.AFFINITIES=8` 是跨空间全局唯一的 SQLite 租赁表；导入的应用越多，未释放的 affinity 槽位越多。已排除残留 guest 进程（targets=0）并在全新 qa3 空间复现，属于引擎容量上限，不是单个应用不兼容。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 主题商店 | `com.miui.themestore` | 45.0 | `START_REJECTED` | `retry.jsonl:30` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 今日头条 | `com.ss.android.article.news` | 18.6.0 | `START_REJECTED` | `retry.jsonl:38` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 哔哩哔哩 | `tv.danmaku.bili` | 9.14.0 | `START_REJECTED` | `retry.jsonl:42` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 安全中心 | `com.miui.securitymanager` | 21.0 | `START_REJECTED` | `retry.jsonl:28` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |
| 小爱同学 | `com.miui.voiceassistProxy` | 1.6.1 | `START_REJECTED` | `retry.jsonl:32` | IllegalStateException:ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED |

**修复建议**：把 `RuntimeActivitySlots.AFFINITIES` 从 8 提升为按 space/uidDomain 分区的动态池（或至少 256），并在 space 删除/安装移除时调用 `ActivityAffinityLedger.releaseUnused()`。8 个全局 slot 无法支撑多应用迁移；即使 `application_query targets` 为空，旧 `activity_components` 仍会占着 lease。

### 5.5 启动后身份/ClassLoader 改写（3 个）

**机制**：应用在 attach 后改写 ClassLoader（WPS 插件化、芒果 TV），触发 native L1 身份不变量。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| WPS Office | `cn.wps.moffice_eng` | 26.9.1 | `START_REJECTED` | `journal.jsonl:282` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_POST_ATTACH_IDENTITY:logical.mClassLoader |
| 腾讯新闻 | `com.tencent.news` | 8.0.40 | `START_REJECTED` | `journal.jsonl:339` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_POST_ATTACH_IDENTITY:logical.mClassLoader |
| 芒果 TV | `com.hunantv.imgo.activity` | 9.4.3 | `START_REJECTED` | `journal.jsonl:313` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: APPLICATION_OWNERSHIP_VIOLATION:logical.mClassLoader |

**修复建议**：WPS 在 `Application.attach` 后替换 `mClassLoader`，芒果 TV 触发 ownership 校验；需要在 native L1 允许受控的 ClassLoader 替换，或把插件 ClassLoader 建模为子加载器。

### 5.6 可选系统库缺失（3 个）

**机制**：guest 依赖 `org.apache.http.legacy`、`commons-logging` 等可选库，类加载器未注入对应 jar。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 小米遥控器 | `com.duokan.phone.remotecontroller` | 7.2.5M | `CRASH_ON_START` | `journal.jsonl:305` | Caused by: java.lang.ClassNotFoundException: org.apache.commons.logging.LogFactory |
| 指南针 | `com.miui.compass` | 17.1.4.1 | `CRASH_ON_START` | `journal.jsonl:321` | Caused by: java.lang.ClassNotFoundException: org.apache.commons.logging.LogFactory |
| 支付宝 | `com.eg.android.AlipayGphone` | 12.12.30.8000 | `CRASH_ON_START` | `journal.jsonl:248` | Caused by: java.lang.ClassNotFoundException: Didn't find class "org.apache.http.client.HttpRequestRetryHandler" on path: DexPathList[[zip file "/data/user/0/com.eg.android.AlipayGp |

**修复建议**：guest manifest 声明 `uses-library org.apache.http.legacy`（required=false）时，把 `/system/framework/org.apache.http.legacy.jar` 加入 guest ClassLoader；`commons-logging` 同理提供兼容 jar。

### 5.7 应用崩溃（无归因栈）（2 个）

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 今日头条极速版 | `com.ss.android.article.lite` | 18.6.0.0 | `CRASH_ON_START` | `journal.jsonl:336` | 1792687086.222 12695 12695 E AndroidRuntime: FATAL EXCEPTION: main |
| 相册 | `com.miui.gallery` | 5.4.2.23-0920-cn | `CRASH_ON_START` | `retry.jsonl:26` | 1792687518.205 21036 21036 E AndroidRuntime: FATAL EXCEPTION: main |

### 5.8 应用代码/split 缺失（2 个）

**机制**：APK 的 base 中缺少启动所需的类（动态功能/split 未随包提供，或被厂商裁剪）。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 天气 | `com.miui.weather2` | 18.0.0.25-R | `START_REJECTED` | `retry.jsonl:34` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.ClassNotFoundException: Didn't find class "androidx.core.app.CoreComponentFactory" on path: DexPathList[[zip |
| 小米商城 | `com.xiaomi.shop` | 5.54.0.20260811.r1 | `START_REJECTED` | `journal.jsonl:345` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.ClassNotFoundException: Didn't find class "com.xiaomi.shop.mishop" on path: DexPathList[[zip file "/data/use |

**修复建议**：先确认设备上的安装是否完整（`pm path` 对比 split 列表）；若 base 本身不含启动类，需要连同该应用的 dynamic feature/split 一起导入，而不是把它记成运行时缺陷。

### 5.9 恢复围栏（2 个）

**机制**：该空间存在 UNKNOWN 操作，整个空间被围栏；换新空间复测。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 扫一扫 | `com.xiaomi.scanner` | 15.9.78 | `SCOPE_FENCED` | `retry.jsonl:40` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: RECOVERY_SCOPE_UNKNOWN:67fabff0-bf58-4992-945a-ab876e728945 |
| 王者荣耀 | `com.tencent.tmgp.sgame` | 12.1.1.1 | `CRASH_ON_START` | `journal.jsonl:341` | java.lang.IllegalStateException: RECOVERY_SCOPE_UNKNOWN:67fabff0-bf58-4992-945a-ab876e728945 |

**修复建议**：为每个应用使用全新空间（`qa2-*`）或在启动前清理该空间的 UNKNOWN 操作；不要把围栏记成应用不兼容。

### 5.10 进程退出（2 个）

**机制**：进程在稳定窗口内消失，且日志中没有可归属该包的 Java fatal。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 通义 | `com.aliyun.tongyi` | 7.4.0.3048 | `PROCESS_GONE` | `retry.jsonl:8` |  |
| 钉钉 | `com.alibaba.android.rimet` | 9.0.2.2 | `PROCESS_GONE` | `retry.jsonl:6` |  |

**修复建议**：抓取 `am_kill`/`lmkd`/tombstone，区分 LMK、加固 SDK 自杀与运行时主动结束。

### 5.11 启动模式未建模（1 个）

**机制**：`RuntimeComponents` 只接受 launchMode 0–3，Chrome 使用 `singleInstancePerTask`(4)。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| Chrome | `com.android.chrome` | 151.0.7922.71 | `START_REJECTED` | `journal.jsonl:255` | IllegalStateException:ACTIVITY_LAUNCH_MODE_UNSUPPORTED |

**修复建议**：扩展 `RuntimeComponents` 接受 `singleInstancePerTask`(4)，并在 `RuntimeActivitySlots` 中为它分配独立任务根。

### 5.12 Intent/URI 契约缺口（1 个）

**机制**：guest 启动请求携带 URI grant，运行时未实现该 grant 语义。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| Gmail | `com.google.android.gm` | 2026.10.12.1003320221.Release | `START_REJECTED` | `journal.jsonl:310` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.UnsupportedOperationException: URI_GRANT_NOT_IMPLEMENTED |

**修复建议**：实现 `Intent` 的 URI grant 传递（ClipData/FLAG_GRANT_*），或对该类启动降级为无 grant 并记录告警。

### 5.13 文件权限/路径映射（1 个）

**机制**：运行时把 guest 的 lib 目录映射到宿主 `/data/user/0/<pkg>/lib-compressed`，删除写权限时失败。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| Instagram | `com.instagram.android` | 452.1.0.53.64 | `START_REJECTED` | `journal.jsonl:275` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.RuntimeException: java.io.IOException: error removing /data/user/0/com.instagram.android/lib-compressed writ |

**修复建议**：Instagram 的 `lib-compressed` 目录不应映射到宿主真实 `/data/user/0/<pkg>`；修正 `RuntimeStorageFacade` 的写权限回收路径。

### 5.14 槽位进程崩溃（1 个）

**机制**：`dev.tripoder.engine:slotN` 发生未捕获异常或被应用终止，需完整 tombstone/栈。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 多看阅读 | `com.duokan.reader` | 8.4.19 | `CRASH_ON_START` | `journal.jsonl:273` | 1792686379.041 19899 19899 E AndroidRuntime: FATAL EXCEPTION: main |

**修复建议**：抓取 `dev.tripoder.engine:slot0` 的完整 Java 栈与 tombstone；对加固 SDK 的主动杀进程，需要 L13 的隔离/信号处理。

### 5.15 通知 API 缺口（1 个）

**机制**：`RuntimeSelfHookManagerExtras` 未实现 `setNotificationDelegate`。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 多邻国 | `com.duolingo` | 6.102.4 | `START_REJECTED` | `journal.jsonl:284` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.UnsupportedOperationException: NOTIFICATION_CAPABILITY_UNSUPPORTED:setNotificationDelegate(java.lang.String) |

**修复建议**：在 `RuntimeSelfHookManagerExtras` 补齐 `setNotificationDelegate(String)` 等通知代理方法，至少提供无操作实现，避免启动期直接抛异常。

### 5.16 Tinker/电话 API（1 个）

**机制**：Tinker 初始化调用 `getDataNetworkTypeForSubscriber`，运行时电话能力未覆盖。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 爱奇艺 | `com.qiyi.video` | 17.9.5 | `START_REJECTED` | `journal.jsonl:269` | IllegalStateException:java.util.concurrent.ExecutionException:com.tencent.tinker.loader.TinkerRuntimeException: Tinker Exception:getDataNetworkTypeForSubscriber |

**修复建议**：补齐 `TelephonyManager.getDataNetworkTypeForSubscriber` 的 guest facade（返回宿主网络类型或 UNKNOWN），可让爱奇艺越过 Tinker 初始化。

### 5.17 物理应用身份变化（1 个）

**机制**：重新导入后物理 Application 的初始化代与旧进程不一致，运行时拒绝复用旧进程；需要先停止旧进程再启动。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 网易云音乐 | `com.netease.cloudmusic` | 9.6.05 | `START_REJECTED` | `retry.jsonl:36` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.IllegalStateException: PHYSICAL_APPLICATION_INITIAL_CHANGED |

**修复建议**：在 `process_start` 前检测 installGeneration 是否变化；变化时先 `application_stop` 旧进程并等待死亡，或让启动路径自动替换旧物理 Application，避免把“旧代未清”记成应用失败。

### 5.18 运行时初始化缺陷（1 个）

**机制**：guest context 的 `ApplicationInfo` 为空，`getTheme()` 直接 NPE；属于运行时引导缺陷。

| 应用 | 包名 | 版本 | 判定 | 记录 | 证据 |
|---|---|---|---|---|---|
| 豆包 | `com.larus.nova` | 15.2.0 | `START_REJECTED` | `retry.jsonl:22` | IllegalStateException:java.util.concurrent.ExecutionException:java.lang.NullPointerException: Attempt to read from field 'int android.content.pm.ApplicationInfo.targetSdkVersion' o |

**修复建议**：修复 guest `ContextImpl` 的 `ApplicationInfo` 绑定（为每个 guest 提供非空 `ApplicationInfo`，补 `targetSdkVersion`），避免 `getTheme()`/资源解析阶段 NPE。

## 6. 通过但存在后台异常

以下应用首屏正常，但稳定窗口内自身进程有 FATAL/异常日志；不影响本次“首屏通过”判定，但业务功能可能受影响：

| 应用 | 包名 | 首条异常 |
|---|---|---|
| GitHub | `com.github.android` |          1792686443.583 27149 27258 E AndroidRuntime: FATAL EXCEPTION: WM.task-4          1792686443.583 27149 27258 E AndroidRuntime: Process: com.github.android, PID: 27149       |

## 7. 修复优先级建议

| 优先级 | 问题 | 影响面 | 建议 |
|---|---|---|---|
| P0 | 包可见性 `<queries>` 过窄 | 12 个无启动入口组件全部 NameNotFound | 增加 `QUERY_ALL_PACKAGES` 或逐包 `<queries>` |
| P0 | 全局 activity-affinity 仅 8 个 slot | 导入多个应用后，今日头条/哔哩哔哩等 5 个应用被拒 | 按 space/uidDomain 分区并扩容到 256+，删除/失败回滚时释放 lease |
| P0 | 原生库路径规则过严 | 百度网盘、平安证券被整包拒绝 | 只校验已知 ABI 目录，未知 `lib/` 资源保留 |
| P1 | 组件重复声明未 take-last | 腾讯视频、火车票 | 实现同名组件后者覆盖 |
| P1 | `<uses-split>` 一律拒绝 | 快手 | 允许可选/未提供的 split |
| P1 | 单文件 1 GiB 上限 | WePop (1.5 GB) | 提高上限或流式安装 |
| P1 | 引擎 `:slot0/:authority` 稳定性 | 钉钉、头条、网易云等被误判 | 收集 tombstone，自动重连+重试 |
| P2 | launchMode/alias 容量 | Chrome、YouTube、Office | 支持 mode 4，动态 alias 槽位 |
| P2 | 通知/电话 API 缺口 | Duolingo、爱奇艺 | 补 facade/无操作实现 |
| P2 | 可选系统库/存储映射 | 支付宝、Instagram、WPS | 注入 http legacy jar、修正 lib-compressed 路径 |

## 8. 证据索引与复现

- 首轮逐应用日志：`artifacts/compat-run1/journal.jsonl`
- 首轮 logcat：`artifacts/compat-run1/logs/`
- 首轮失败截图：`artifacts/compat-run1/shots/`
- 复测：`artifacts/compat-run1-retry/retry.jsonl`；隔离复测：`artifacts/compat-run1-retry2/retry.jsonl`
- 空间现状（2026-10-23 复核 `runtime_query(action=spaces)`）：保留 `qa-*` 123 个、`qa2-*` 22 个、`qa3-*` 1 个；105 个成功导入的应用仍可复核，未做卸载清理。
- 额外隔离复测：`artifacts/compat-run1-retry2/retry.jsonl`（哔哩哔哩，全新 `qa3-*` 空间，用于验证 affinity 容量）。
- 清点/导入/启动脚本：`scripts/qa/device_campaign.py`；复测：`scripts/qa/retry_launch.py`；报告：`scripts/qa/final_report.py`
- 设备清单原始抓取（含设备序列号、系统版本、`pm list packages -3` 命令与输出、sha256）：`artifacts/compat-run1/evidence/00-device.txt`、`01-pm-list-packages-3.raw.txt`、`02-fixtures-7.txt`、`03-app-set-123.txt`、`SHA256SUMS.txt`
- 逐应用记录行号：本报告 **附录 B**（清单行/导入行/启动行/首因日志行/截图）；`python3 scripts/qa/verify_evidence.py` 会重新读取这些记录并复算全部汇总数字

复现命令：

```bash
python3 scripts/qa/device_campaign.py --out compat-run1 --wait-ms 18000
python3 scripts/qa/retry_launch.py --run compat-run1 --out compat-run1-retry --only <失败包名> --fresh <需换空间的包名>
python3 scripts/qa/final_report.py --run compat-run1 --retry compat-run1-retry
```

## 9. 运行模式覆盖（THIN / THICK）

引擎有 THIN（默认；固定绑定 1021、保留编译）与 THICK（全量适配、全局反优化）两种运行模式，对已停止的安装可用 `application_manage action=set_mode` 切换。本次 123 应用矩阵**只在 THIN 下执行**：

- 抽查 10 个安装的 `application_query action=launch_entries`，`mode` 全部为 `thin`；`artifacts/compat-run1/journal.jsonl` 的 104 条首轮 launch 记录里没有 `modeChanged`/`mode` 字段，说明跑测期间从未调用 `set_mode`。
- 因此本报告第 3~7 节的 57 个 PASS、48 个启动失败、18 个导入失败，都是 THIN 模式下的结论。

THICK 的既有证据是一次单应用双模式验收：`docs/evidence/thick-startup-p0-p1-2026-10-21/mode-validation.json`（宿主 `dev.tripoder.engine.globaltest`，应用 `com.netease.cloudmusic`，35 项检查中 thin-* 15 项、thick-* 17 项，`passed=true`）。它证明 THICK 链路可用，但只覆盖 1 个应用，不能外推到矩阵。

本轮补测 THICK 未能产出判定（`--mode thick`，2026-10-23）：

| 应用 | 动作 | 实际结果 | 记录 |
|---|---|---|---|
| `com.tencent.mm` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:1` |
| `com.eg.android.AlipayGphone` | 切 THICK | `RECOVERY_SCOPE_UNKNOWN`（该 space 早已被 fence） | `artifacts/compat-thick-pilot/journal.jsonl:2` |
| `com.xingin.xhs` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:3` |
| `tv.danmaku.bili` | 切 THICK 后启动 | `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` | `artifacts/compat-thick-pilot/journal.jsonl:4` |
| `com.tencent.mm` | THICK 复测 | `SCOPE_FENCED` | `artifacts/compat-thick-pilot2/journal.jsonl:1` |

这是附录 A 第一条的连带后果：8 个全局 affinity 槽位已被 105 次导入占满，`application_query targets` 为空也不释放，新模式下的启动会先被容量拒绝；失败启动再把对应 space fence 成不可再用。本次补测因此有 3 个安装（`com.tencent.mm`、`com.xingin.xhs`、`tv.danmaku.bili`）停留在 `thick`：它们的 space 已被 fence，`set_mode thin` 也被拒绝（`artifacts/compat-run1/evidence/08-mode-restore.txt:1-5`），恢复需要清除引擎数据或换设备。

结论：**THICK 在矩阵规模上尚未验证**。补测需要在干净引擎状态（清除引擎数据或换设备）上执行 `python3 scripts/qa/device_campaign.py --phase all --mode thick`；脚本已支持该开关。

## 10. 局限

- 只验证冷启动与首屏；未覆盖登录、联网、支付、推送、相机/定位等业务链路。
- 运行模式只覆盖 THIN；THICK 仅有 1 个应用的历史验收与 5 条被容量拒绝的补测记录（见第 9 节）。
- 测试设备为 Android 17/HyperOS 4.0；不同 ROM/Android 版本的系统 API 与权限行为可能不同。
- 无启动入口的 12 个组件属于系统预装 data-app，不应按“用户可启动应用”统计；它们进入报告是为了保证清单完整。
- 依赖 GMS 的海外应用（YouTube、Instagram 等）在无 GMS 的 guest 中即使越过启动门，功能也可能降级。

## 附录 A：运行时缺陷（引擎侧，与应用无关）

下面几条不是从代码推断的，而是在本轮实测中被触发、可独立复现的运行时问题；它们会直接表现为“应用装不上/用不了”，但不应该记到应用兼容性头上。

### A1（严重）一次 UNKNOWN 启动会围栏整个空间，且没有 MCP 收敛入口

在 `default` 空间启动 `com.miui.notes` 时，投递进程在回执前死亡，操作停在 `UNKNOWN / ACTIVITY_DELIVERY_PROCESS_DIED`。此后同一空间内任何包的写操作都被拒绝，连新的导入也不行：

```text
application_manage(import_device, space=default, package=com.x8bit.bitwarden)
  -> IllegalStateException: RECOVERY_SCOPE_UNKNOWN: qa-start-com-miui-notes-1792684863
```

同一个 APK 换到新空间 `compat-a` 导入，1.9 秒成功。根因是 `RuntimeMutationGuard.enter()` 只用控制 Bundle 顶层的 `space`/`package` 计算 scope，而启动链路把包名放在嵌套的 `target` 里，scope 落成 `space/<space>`；`accepting()` 的 `startsWith` 重叠判定于是把同空间的其他包全部当成冲突。实测 `operation_get`、`application_stop`、`launch_manage(close)`、强制停止并重启引擎、重建同名空间都无法把该 UNKNOWN 变成终态；UI 的“恢复”入口也要求 `entryInert && open==0 && live==0`，带 taskId 的入口永远不满足。

**影响**：一个应用启动时留下 UNKNOWN，整个空间就“坏了”。本轮因此采用一应用一空间；否则第 1 个 UNKNOWN 会让后面所有应用都无法导入/启动。

**建议**：① 启动命令把 `target.package` 带进 guard scope；② 给 UNKNOWN 一条有证据的收敛路径（依据 activity_tickets/sessions 的死亡事实 reconcile 成 FAILED，或提供显式确认）；③ 历史 `space/<x>` 记录只阻断该空间内已知目标的继续写。

### A2（中）`space_manage create` 的 name 上限 24 字符，但校验发生在创建之后

用 `qa-com-x8bit-bitwarden` 建空间返回 `INVALID_TOOL_ARGUMENTS: /name STRING_LENGTH_INVALID (1..24)`，随后 `import_device` 报 `SPACE_NOT_FOUND`，容易被误读成“导入失败”。建议前移参数校验，或让 `import_device` 对缺失空间返回明确的 `SPACE_MISSING`。

### A3（中）`process_inspect(action=diagnostics)` 不接受 `includeInvisible`

`ui_tree` 接受该参数，`diagnostics` 返回 `INVALID_TOOL_ARGUMENTS: /includeInvisible UNKNOWN_PROPERTY`。自动化取证阶段会因此拿不到诊断信息；建议四个 action 的参数保持一致。

### A4（观察）同一应用在不同空间里的结论可能不同

`com.zhihu.android` 在早期 pilot 的 `default` 空间首轮被判 `PROCESS_GONE`，在全新空间 `qa2-com-zhihu-android` 复测时 7.7 秒正常把 `MainActivity` 拿到前台并存活到窗口结束（本批 123 应用矩阵中知乎最终为 `PASS`）。说明首轮的“进程退出”至少有一部分是上一轮残留状态/槽位竞争，不是应用本身不兼容。本报告因此把“首轮判定”和“复测结论”分列，复测结果覆盖首轮。

### A5（严重）全局 activity-affinity 只有 8 个 slot，导入多个应用后必然耗尽

`RuntimeActivitySlots.AFFINITIES=8`，而 `ActivityAffinityLedger` 的 `slot INTEGER UNIQUE` 是**跨 space 全局唯一**的；`releaseUnused()` 只删除没有被 `activity_components.affinityLease` 引用的 lease，停止进程/切换前台都不会释放。于是导入的应用越多，`ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED` 越容易出现。

本轮复测后段（第 14 个应用起）有 5 个应用集中报这个错，包括今日头条和哔哩哔哩。为了排除“应用自身声明太多 affinity”，在同一台设备上先把 `application_query(targets)` 清空（返回 0 个 target），再用全新 `qa3-tv-danmaku-bili` 空间单独复测哔哩哔哩，仍然在 `process_start` 阶段报同一错误（证据：`artifacts/compat-run1-retry2/retry.jsonl`）。这说明失败是引擎的全局容量，不是这几个应用不兼容。

**影响**：8 个全局 slot 无法支撑“把前手机所有应用都装进来”的场景；用户侧会看到一批本来能启动的应用在导入若干应用后突然全部启动失败，且没有可见的释放入口。

**建议**：① 把 affinity 池按 space/uidDomain 分区，至少提升到 256；② 在 space 删除、安装移除、启动失败回滚时调用 `releaseUnused()`；③ 容量耗尽时返回可重试错误并提示“清理旧空间”，而不是把它记成应用失败。

## 附录 B：逐应用记录索引（全部 123 个，可独立核验）

`文件:行` 均为原始记录的物理行号（从 1 起，可直接 `sed -n 'Np'` 打开核对）。清单记录 → `artifacts/compat-run1/evidence/03-app-set-123.txt`；导入/启动记录 → `artifacts/compat-run1/journal.jsonl`，复测应用指向 `artifacts/compat-run1-retry/retry.jsonl`；首因日志 → `artifacts/compat-run1/logs/*.logcat.txt` 中第一条 `Caused by`（无则退到第一条 `FATAL EXCEPTION`）；截图 → `artifacts/compat-run1/shots/`。

| # | 包名 | 清单记录 | 导入记录 | 启动记录 | 判定 | 根因位置 | 首因日志 | 截图 |
|---:|---|---|---|---|---|---|---|---|
| 1 | `bbc.mobile.news.ww` | `evidence/03-app-set-123.txt:2` | `journal.jsonl:166` | `journal.jsonl:286` | `PASS` | — | `—` | `—` |
| 2 | `com.hpbr.bosszhipin` | `evidence/03-app-set-123.txt:45` | `journal.jsonl:195` | `journal.jsonl:312` | `PASS` | — | `—` | `—` |
| 3 | `com.x8bit.bitwarden` | `evidence/03-app-set-123.txt:105` | `journal.jsonl:158` | `journal.jsonl:278` | `PASS` | — | `—` | `—` |
| 4 | `com.bochk.bocpay` | `evidence/03-app-set-123.txt:26` | `retry.jsonl:13` | `retry.jsonl:14` | `START_REJECTED` | §5.2 | `—` | `—` |
| 5 | `com.booking` | `evidence/03-app-set-123.txt:29` | `journal.jsonl:163` | `journal.jsonl:283` | `PASS` | — | `—` | `—` |
| 6 | `com.openai.chatgpt` | `evidence/03-app-set-123.txt:79` | `journal.jsonl:135` | `journal.jsonl:258` | `PASS` | — | `—` | `—` |
| 7 | `com.android.chrome` | `evidence/03-app-set-123.txt:12` | `journal.jsonl:132` | `journal.jsonl:255` | `START_REJECTED` | §5.11 | `—` | `—` |
| 8 | `com.anthropic.claude` | `evidence/03-app-set-123.txt:17` | `journal.jsonl:136` | `journal.jsonl:259` | `PASS` | — | `—` | `—` |
| 9 | `com.deepseek.chat` | `evidence/03-app-set-123.txt:30` | `journal.jsonl:137` | `journal.jsonl:260` | `PASS` | — | `—` | `—` |
| 10 | `com.google.android.apps.bard` | `evidence/03-app-set-123.txt:41` | `journal.jsonl:192` | `journal.jsonl:309` | `PASS` | — | `—` | `—` |
| 11 | `com.ghostlock.app` | `evidence/03-app-set-123.txt:38` | `journal.jsonl:190` | `journal.jsonl:307` | `PASS` | — | `—` | `—` |
| 12 | `com.github.android` | `evidence/03-app-set-123.txt:39` | `journal.jsonl:157` | `journal.jsonl:277` | `PASS` | — | `—` | `—` |
| 13 | `com.google.android.gm` | `evidence/03-app-set-123.txt:42` | `journal.jsonl:193` | `journal.jsonl:310` | `START_REJECTED` | §5.12 | `—` | `—` |
| 14 | `com.google.android.googlequicksearchbox` | `evidence/03-app-set-123.txt:43` | `journal.jsonl:194` | `journal.jsonl:311` | `START_REJECTED` | §5.3 | `—` | `—` |
| 15 | `com.google.android.apps.authenticator2` | `evidence/03-app-set-123.txt:40` | `retry.jsonl:19` | `retry.jsonl:20` | `PASS` | — | `—` | `—` |
| 16 | `com.instagram.android` | `evidence/03-app-set-123.txt:50` | `journal.jsonl:155` | `journal.jsonl:275` | `START_REJECTED` | §5.13 | `—` | `—` |
| 17 | `app.personal.linktools` | `evidence/03-app-set-123.txt:1` | `journal.jsonl:165` | `journal.jsonl:285` | `PASS` | — | `—` | `—` |
| 18 | `com.microsoft.office.officehubrow` | `evidence/03-app-set-123.txt:58` | `journal.jsonl:161` | `journal.jsonl:281` | `START_REJECTED` | §5.3 | `—` | `—` |
| 19 | `com.tencent.mobileqq` | `evidence/03-app-set-123.txt:97` | `journal.jsonl:154` | `journal.jsonl:274` | `PASS` | — | `—` | `—` |
| 20 | `com.fenxiang.storyline` | `evidence/03-app-set-123.txt:37` | `journal.jsonl:189` | `journal.jsonl:306` | `PASS` | — | `—` | `—` |
| 21 | `org.telegram.messenger` | `evidence/03-app-set-123.txt:120` | `journal.jsonl:134` | `journal.jsonl:257` | `PASS` | — | `—` | `—` |
| 22 | `org.videolan.vlc` | `evidence/03-app-set-123.txt:121` | `journal.jsonl:160` | `journal.jsonl:280` | `PASS` | — | `—` | `—` |
| 23 | `cn.wps.moffice_eng` | `evidence/03-app-set-123.txt:5` | `journal.jsonl:162` | `journal.jsonl:282` | `START_REJECTED` | §5.5 | `—` | `—` |
| 24 | `cn.wps.moffice_eng.xiaomi.lite` | `evidence/03-app-set-123.txt:6` | `journal.jsonl:169` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 25 | `com.kingsoft.xiezuo` | `evidence/03-app-set-123.txt:52` | `journal.jsonl:200` | `journal.jsonl:314` | `PASS` | — | `—` | `—` |
| 26 | `org.wikipedia` | `evidence/03-app-set-123.txt:122` | `retry.jsonl:1` | `retry.jsonl:2` | `PASS` | — | `—` | `—` |
| 27 | `com.twitter.android` | `evidence/03-app-set-123.txt:104` | `journal.jsonl:156` | `journal.jsonl:276` | `PASS` | — | `—` | `—` |
| 28 | `com.google.android.youtube` | `evidence/03-app-set-123.txt:44` | `journal.jsonl:133` | `journal.jsonl:256` | `START_REJECTED` | §5.3 | `—` | `—` |
| 29 | `com.android.providers.downloads.ui` | `evidence/03-app-set-123.txt:15` | `journal.jsonl:176` | `journal.jsonl:295` | `CRASH_ON_START` | §5.1 | `logs/com-android-providers-downloads-ui.logcat.txt:4` | `com-android-providers-downloads-ui.png` |
| 30 | `com.bocconnect.prd` | `evidence/03-app-set-123.txt:24` | `retry.jsonl:9` | `retry.jsonl:10` | `START_REJECTED` | §5.2 | `—` | `—` |
| 31 | `com.bochk.bn.aos` | `evidence/03-app-set-123.txt:25` | `retry.jsonl:11` | `retry.jsonl:12` | `START_REJECTED` | §5.2 | `—` | `—` |
| 32 | `com.bochk.igtb` | `evidence/03-app-set-123.txt:27` | `retry.jsonl:15` | `retry.jsonl:16` | `START_REJECTED` | §5.2 | `—` | `—` |
| 33 | `com.bochk.mortgage.android.hk` | `evidence/03-app-set-123.txt:28` | `retry.jsonl:17` | `retry.jsonl:18` | `START_REJECTED` | §5.2 | `—` | `—` |
| 34 | `com.miui.themestore` | `evidence/03-app-set-123.txt:73` | `retry.jsonl:29` | `retry.jsonl:30` | `START_REJECTED` | §5.4 | `—` | `—` |
| 35 | `com.jingdong.app.mall` | `evidence/03-app-set-123.txt:51` | `journal.jsonl:130` | `journal.jsonl:253` | `PASS` | — | `—` | `—` |
| 36 | `com.ss.android.article.news` | `evidence/03-app-set-123.txt:91` | `retry.jsonl:37` | `retry.jsonl:38` | `START_REJECTED` | §5.4 | `—` | `—` |
| 37 | `com.ss.android.article.lite` | `evidence/03-app-set-123.txt:90` | `journal.jsonl:229` | `journal.jsonl:336` | `CRASH_ON_START` | §5.7 | `—` | `com-ss-android-article-lite.png` |
| 38 | `com.tencent.tmgp.WePop` | `evidence/03-app-set-123.txt:100` | `journal.jsonl:233` | `—` | `IMPORT_FAILED` | §4.4 | `—` | `—` |
| 39 | `com.miui.virtualsim` | `evidence/03-app-set-123.txt:75` | `journal.jsonl:221` | `journal.jsonl:329` | `PASS` | — | `—` | `—` |
| 40 | `com.miui.newhome` | `evidence/03-app-set-123.txt:67` | `journal.jsonl:213` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 41 | `com.miHoYo.Yuanshen` | `evidence/03-app-set-123.txt:57` | `journal.jsonl:204` | `journal.jsonl:317` | `PASS` | — | `—` | `—` |
| 42 | `com.tongcheng.android` | `evidence/03-app-set-123.txt:103` | `journal.jsonl:236` | `journal.jsonl:342` | `PASS` | — | `—` | `—` |
| 43 | `tv.danmaku.bili` | `evidence/03-app-set-123.txt:123` | `retry.jsonl:41` | `retry.jsonl:42` | `START_REJECTED` | §5.4 | `—` | `—` |
| 44 | `com.achievo.vipshop` | `evidence/03-app-set-123.txt:7` | `journal.jsonl:170` | `journal.jsonl:289` | `PASS` | — | `—` | `—` |
| 45 | `com.miui.cleanmaster` | `evidence/03-app-set-123.txt:62` | `journal.jsonl:208` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 46 | `com.duokan.reader` | `evidence/03-app-set-123.txt:34` | `journal.jsonl:152` | `journal.jsonl:273` | `CRASH_ON_START` | §5.14 | `—` | `com-duokan-reader.png` |
| 47 | `com.duolingo` | `evidence/03-app-set-123.txt:35` | `journal.jsonl:164` | `journal.jsonl:284` | `START_REJECTED` | §5.15 | `—` | `—` |
| 48 | `com.dianping.v1` | `evidence/03-app-set-123.txt:31` | `journal.jsonl:187` | `journal.jsonl:304` | `PASS` | — | `—` | `—` |
| 49 | `com.miui.weather2` | `evidence/03-app-set-123.txt:77` | `retry.jsonl:33` | `retry.jsonl:34` | `START_REJECTED` | §5.8 | `—` | `—` |
| 50 | `com.quark.browser` | `evidence/03-app-set-123.txt:84` | `journal.jsonl:227` | `journal.jsonl:334` | `START_REJECTED` | §5.3 | `—` | `—` |
| 51 | `com.antutu.ABenchMark` | `evidence/03-app-set-123.txt:18` | `journal.jsonl:178` | `journal.jsonl:297` | `PASS` | — | `—` | `—` |
| 52 | `com.miui.securitymanager` | `evidence/03-app-set-123.txt:72` | `retry.jsonl:27` | `retry.jsonl:28` | `START_REJECTED` | §5.4 | `—` | `—` |
| 53 | `com.miui.voiceassistProxy` | `evidence/03-app-set-123.txt:76` | `retry.jsonl:31` | `retry.jsonl:32` | `START_REJECTED` | §5.4 | `—` | `—` |
| 54 | `com.xiaomi.mibrain.speech` | `evidence/03-app-set-123.txt:108` | `journal.jsonl:239` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 55 | `com.miui.newmidrive` | `evidence/03-app-set-123.txt:68` | `journal.jsonl:214` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 56 | `com.mi.health` | `evidence/03-app-set-123.txt:56` | `journal.jsonl:203` | `journal.jsonl:316` | `CRASH_ON_START` | §5.1 | `logs/com-mi-health.logcat.txt:30` | `com-mi-health.png` |
| 57 | `com.xiaomi.shop` | `evidence/03-app-set-123.txt:110` | `journal.jsonl:241` | `journal.jsonl:345` | `START_REJECTED` | §5.8 | `—` | `—` |
| 58 | `com.xiaomi.tinygame` | `evidence/03-app-set-123.txt:112` | `journal.jsonl:243` | `journal.jsonl:347` | `PASS` | — | `—` | `—` |
| 59 | `com.miui.huanji` | `evidence/03-app-set-123.txt:65` | `journal.jsonl:211` | `journal.jsonl:323` | `CRASH_ON_START` | §5.1 | `logs/com-miui-huanji.logcat.txt:30` | `com-miui-huanji.png` |
| 60 | `com.xiaomi.youpin` | `evidence/03-app-set-123.txt:114` | `journal.jsonl:245` | `journal.jsonl:349` | `PASS` | — | `—` | `—` |
| 61 | `com.xiaomi.gamecenter` | `evidence/03-app-set-123.txt:107` | `journal.jsonl:238` | `journal.jsonl:343` | `PASS` | — | `—` | `—` |
| 62 | `com.mfashiongallery.emag` | `evidence/03-app-set-123.txt:55` | `journal.jsonl:202` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 63 | `com.xiaomi.vipaccount` | `evidence/03-app-set-123.txt:113` | `journal.jsonl:244` | `journal.jsonl:348` | `CRASH_ON_START` | §5.1 | `logs/com-xiaomi-vipaccount.logcat.txt:4` | `com-xiaomi-vipaccount.png` |
| 64 | `com.miui.video` | `evidence/03-app-set-123.txt:74` | `journal.jsonl:220` | `journal.jsonl:328` | `CRASH_ON_START` | §5.1 | `logs/com-miui-video.logcat.txt:30` | `com-miui-video.png` |
| 65 | `com.duokan.phone.remotecontroller` | `evidence/03-app-set-123.txt:33` | `journal.jsonl:188` | `journal.jsonl:305` | `CRASH_ON_START` | §5.6 | `logs/com-duokan-phone-remotecontroller.logcat.txt:17` | `com-duokan-phone-remotecontroller.png` |
| 66 | `com.mipay.wallet` | `evidence/03-app-set-123.txt:60` | `journal.jsonl:206` | `journal.jsonl:319` | `PASS` | — | `—` | `—` |
| 67 | `com.xingin.xhs` | `evidence/03-app-set-123.txt:115` | `journal.jsonl:128` | `journal.jsonl:251` | `PASS` | — | `—` | `—` |
| 68 | `com.miui.screenrecorder` | `evidence/03-app-set-123.txt:71` | `journal.jsonl:217` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 69 | `com.pingan.paces.ccms` | `evidence/03-app-set-123.txt:82` | `journal.jsonl:226` | `journal.jsonl:333` | `PASS` | — | `—` | `—` |
| 70 | `com.hundsun.winner.pazq` | `evidence/03-app-set-123.txt:47` | `journal.jsonl:197` | `—` | `IMPORT_FAILED` | §4.2 | `—` | `—` |
| 71 | `com.android.soundrecorder` | `evidence/03-app-set-123.txt:16` | `journal.jsonl:177` | `journal.jsonl:296` | `CRASH_ON_START` | §5.1 | `logs/com-android-soundrecorder.logcat.txt:4` | `com-android-soundrecorder.png` |
| 72 | `com.tencent.mm` | `evidence/03-app-set-123.txt:96` | `journal.jsonl:124` | `journal.jsonl:247` | `PASS` | — | `—` | `—` |
| 73 | `com.sina.weibo` | `evidence/03-app-set-123.txt:88` | `journal.jsonl:142` | `journal.jsonl:265` | `PASS` | — | `—` | `—` |
| 74 | `com.smile.gifmaker` | `evidence/03-app-set-123.txt:89` | `journal.jsonl:153` | `—` | `IMPORT_FAILED` | §4.5 | `—` | `—` |
| 75 | `com.xiaomi.scanner` | `evidence/03-app-set-123.txt:109` | `retry.jsonl:39` | `retry.jsonl:40` | `SCOPE_FENCED` | §5.9 | `—` | `—` |
| 76 | `com.ss.android.ugc.aweme` | `evidence/03-app-set-123.txt:92` | `journal.jsonl:127` | `journal.jsonl:250` | `PASS` | — | `—` | `—` |
| 77 | `cmb.pb` | `evidence/03-app-set-123.txt:3` | `journal.jsonl:167` | `journal.jsonl:287` | `PASS` | — | `—` | `—` |
| 78 | `com.xunmeng.pinduoduo` | `evidence/03-app-set-123.txt:117` | `journal.jsonl:131` | `journal.jsonl:254` | `PASS` | — | `—` | `—` |
| 79 | `com.miui.compass` | `evidence/03-app-set-123.txt:63` | `journal.jsonl:209` | `journal.jsonl:321` | `CRASH_ON_START` | §5.6 | `logs/com-miui-compass.logcat.txt:42` | `com-miui-compass.png` |
| 80 | `ctrip.android.view` | `evidence/03-app-set-123.txt:119` | `journal.jsonl:246` | `journal.jsonl:350` | `PASS` | — | `—` | `—` |
| 81 | `com.eg.android.AlipayGphone` | `evidence/03-app-set-123.txt:36` | `journal.jsonl:125` | `journal.jsonl:248` | `CRASH_ON_START` | §5.6 | `logs/com-eg-android-AlipayGphone.logcat.txt:42` | `com-eg-android-AlipayGphone.png` |
| 82 | `com.android.calendar` | `evidence/03-app-set-123.txt:11` | `journal.jsonl:173` | `journal.jsonl:292` | `CRASH_ON_START` | §5.1 | `logs/com-android-calendar.logcat.txt:30` | `com-android-calendar.png` |
| 83 | `com.android.deskclock` | `evidence/03-app-set-123.txt:13` | `journal.jsonl:174` | `journal.jsonl:293` | `START_REJECTED` | §5.3 | `—` | `—` |
| 84 | `com.xiaomi.bestphotos` | `evidence/03-app-set-123.txt:106` | `journal.jsonl:237` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 85 | `com.luna.music` | `evidence/03-app-set-123.txt:54` | `retry.jsonl:23` | `retry.jsonl:24` | `PASS` | — | `—` | `—` |
| 86 | `com.taobao.taobao` | `evidence/03-app-set-123.txt:94` | `journal.jsonl:126` | `journal.jsonl:249` | `PASS` | — | `—` | `—` |
| 87 | `com.sdu.didi.psnger` | `evidence/03-app-set-123.txt:87` | `journal.jsonl:140` | `journal.jsonl:263` | `PASS` | — | `—` | `—` |
| 88 | `com.pangsheng.pshuochepiao.m` | `evidence/03-app-set-123.txt:80` | `journal.jsonl:224` | `—` | `IMPORT_FAILED` | §4.3 | `—` | `—` |
| 89 | `com.qiyi.video` | `evidence/03-app-set-123.txt:83` | `journal.jsonl:146` | `journal.jsonl:269` | `START_REJECTED` | §5.16 | `—` | `—` |
| 90 | `com.tencent.tmgp.sgame` | `evidence/03-app-set-123.txt:102` | `journal.jsonl:235` | `journal.jsonl:341` | `CRASH_ON_START` | §5.9 | `logs/com-tencent-tmgp-sgame.logcat.txt:1` | `com-tencent-tmgp-sgame.png` |
| 91 | `com.dragon.read` | `evidence/03-app-set-123.txt:32` | `journal.jsonl:151` | `journal.jsonl:272` | `PASS` | — | `—` | `—` |
| 92 | `com.xs.fm` | `evidence/03-app-set-123.txt:116` | `journal.jsonl:150` | `journal.jsonl:271` | `PASS` | — | `—` | `—` |
| 93 | `com.phoenix.read` | `evidence/03-app-set-123.txt:81` | `journal.jsonl:225` | `journal.jsonl:332` | `PASS` | — | `—` | `—` |
| 94 | `com.baidu.searchbox` | `evidence/03-app-set-123.txt:23` | `journal.jsonl:181` | `journal.jsonl:298` | `CRASH_ON_START` | §5.3 | `logs/com-baidu-searchbox.logcat.txt:30` | `com-baidu-searchbox.png` |
| 95 | `com.baidu.BaiduMap` | `evidence/03-app-set-123.txt:20` | `journal.jsonl:141` | `journal.jsonl:264` | `PASS` | — | `—` | `—` |
| 96 | `com.baidu.netdisk` | `evidence/03-app-set-123.txt:22` | `journal.jsonl:180` | `—` | `IMPORT_FAILED` | §4.2 | `—` | `—` |
| 97 | `com.baidu.input_mi` | `evidence/03-app-set-123.txt:21` | `journal.jsonl:179` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 98 | `com.miui.gallery` | `evidence/03-app-set-123.txt:64` | `retry.jsonl:25` | `retry.jsonl:26` | `CRASH_ON_START` | §5.7 | `—` | `com-miui-gallery.png` |
| 99 | `com.hyper.gallery.plugin` | `evidence/03-app-set-123.txt:48` | `journal.jsonl:198` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 100 | `com.zhihu.android` | `evidence/03-app-set-123.txt:118` | `journal.jsonl:143` | `journal.jsonl:266` | `PASS` | — | `—` | `—` |
| 101 | `com.miui.notes` | `evidence/03-app-set-123.txt:69` | `journal.jsonl:215` | `journal.jsonl:324` | `PASS` | — | `—` | `—` |
| 102 | `com.xiaomi.smarthome` | `evidence/03-app-set-123.txt:111` | `journal.jsonl:242` | `journal.jsonl:346` | `PASS` | — | `—` | `—` |
| 103 | `com.mihoyo.hyperion` | `evidence/03-app-set-123.txt:59` | `journal.jsonl:205` | `journal.jsonl:318` | `PASS` | — | `—` | `—` |
| 104 | `com.netease.cloudmusic` | `evidence/03-app-set-123.txt:78` | `retry.jsonl:35` | `retry.jsonl:36` | `START_REJECTED` | §5.17 | `—` | `—` |
| 105 | `cn.cyberIdentity.certification` | `evidence/03-app-set-123.txt:4` | `retry.jsonl:3` | `retry.jsonl:4` | `START_REJECTED` | §5.2 | `—` | `—` |
| 106 | `com.sankuai.meituan` | `evidence/03-app-set-123.txt:85` | `journal.jsonl:129` | `journal.jsonl:252` | `PASS` | — | `—` | `—` |
| 107 | `com.sankuai.meituan.takeoutnew` | `evidence/03-app-set-123.txt:86` | `journal.jsonl:228` | `journal.jsonl:335` | `PASS` | — | `—` | `—` |
| 108 | `com.taou.maimai` | `evidence/03-app-set-123.txt:95` | `journal.jsonl:231` | `journal.jsonl:338` | `PASS` | — | `—` | `—` |
| 109 | `com.tencent.tmgp.osgame` | `evidence/03-app-set-123.txt:101` | `journal.jsonl:234` | `journal.jsonl:340` | `PASS` | — | `—` | `—` |
| 110 | `com.tencent.news` | `evidence/03-app-set-123.txt:98` | `journal.jsonl:232` | `journal.jsonl:339` | `START_REJECTED` | §5.5 | `—` | `—` |
| 111 | `com.tencent.qqlive` | `evidence/03-app-set-123.txt:99` | `journal.jsonl:147` | `—` | `IMPORT_FAILED` | §4.3 | `—` | `—` |
| 112 | `com.hunantv.imgo.activity` | `evidence/03-app-set-123.txt:46` | `journal.jsonl:196` | `journal.jsonl:313` | `START_REJECTED` | §5.5 | `—` | `—` |
| 113 | `com.miui.mediaeditor` | `evidence/03-app-set-123.txt:66` | `journal.jsonl:212` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 114 | `com.miui.calculator` | `evidence/03-app-set-123.txt:61` | `journal.jsonl:207` | `journal.jsonl:320` | `CRASH_ON_START` | §5.1 | `logs/com-miui-calculator.logcat.txt:30` | `com-miui-calculator.png` |
| 115 | `com.iflytek.inputmethod.miui` | `evidence/03-app-set-123.txt:49` | `journal.jsonl:199` | `—` | `IMPORT_FAILED` | §4.1 | `—` | `—` |
| 116 | `com.larus.nova` | `evidence/03-app-set-123.txt:53` | `retry.jsonl:21` | `retry.jsonl:22` | `START_REJECTED` | §5.18 | `—` | `—` |
| 117 | `com.aliyun.tongyi` | `evidence/03-app-set-123.txt:10` | `retry.jsonl:7` | `retry.jsonl:8` | `PROCESS_GONE` | §5.10 | `—` | `com-aliyun-tongyi.png` |
| 118 | `com.android.email` | `evidence/03-app-set-123.txt:14` | `journal.jsonl:175` | `journal.jsonl:294` | `CRASH_ON_START` | §5.1 | `logs/com-android-email.logcat.txt:6` | `com-android-email.png` |
| 119 | `com.alibaba.android.rimet` | `evidence/03-app-set-123.txt:8` | `retry.jsonl:5` | `retry.jsonl:6` | `PROCESS_GONE` | §5.10 | `—` | `com-alibaba-android-rimet.png` |
| 120 | `com.taobao.idlefish` | `evidence/03-app-set-123.txt:93` | `journal.jsonl:230` | `journal.jsonl:337` | `PASS` | — | `—` | `—` |
| 121 | `com.alicloud.databox` | `evidence/03-app-set-123.txt:9` | `journal.jsonl:171` | `journal.jsonl:290` | `PASS` | — | `—` | `—` |
| 122 | `com.miui.player` | `evidence/03-app-set-123.txt:70` | `journal.jsonl:216` | `journal.jsonl:325` | `PASS` | — | `—` | `—` |
| 123 | `com.autonavi.minimap` | `evidence/03-app-set-123.txt:19` | `journal.jsonl:139` | `journal.jsonl:262` | `PASS` | — | `—` | `—` |

> 读法：`PASS` 行的正面证据是该 `journal.jsonl` 行里的 `focus` 字段（`mCurrentFocus=` 指向应用自己的 Activity）与 `alive/foreground/uiTreeNodes`；失败行的 `首因日志` 指向属于该应用进程的崩溃块，`—` 表示该应用没有留存 logcat，此时以该行 `journal.jsonl` 的 `error`/`fatals`/`focus` 与 `shots/` 截图为准。`IMPORT_FAILED` 无启动记录，12 个无入口组件既无启动记录也无截图，均在对应 §4/§5 小节给出原始错误串。
