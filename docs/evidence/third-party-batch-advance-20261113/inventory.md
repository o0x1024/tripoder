# 第三方应用盘点（设备 d861a4fa，`pm list packages -3` 全量）

共 121 个 `-3` 包。表中「厂商/系统捆绑」列标记的是 `-3` 里仍属厂家预装的包（小米/小米生态链/输入法/画报），它们不是用户自装的第三方应用，推进优先级最低。

| # | 包名 | 厂商/系统捆绑 | 容器状态 | 实测结论 | 证据 |
|---|---|---|---|---|---|
| 1 | `app.personal.linktools` |  |  | 未测 |  |
| 2 | `bbc.mobile.news.ww` |  |  | 未测 |  |
| 3 | `cmb.pb` |  |  | 未测 |  |
| 4 | `cn.cyberIdentity.certification` |  |  | 未测 |  |
| 5 | `cn.wps.moffice_eng` |  |  | 未测 |  |
| 6 | `cn.wps.moffice_eng.xiaomi.lite` |  |  | 未测 |  |
| 7 | `com.achievo.vipshop` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 8 | `com.alibaba.android.rimet` |  |  | 未测 |  |
| 9 | `com.alicloud.databox` |  |  | 未测 |  |
| 10 | `com.aliyun.tongyi` |  |  | 未测 |  |
| 11 | `com.android.calendar` | 是 |  | 未测 |  |
| 12 | `com.android.chrome` | 是 |  | 未测 |  |
| 13 | `com.android.deskclock` | 是 |  | 未测 |  |
| 14 | `com.android.email` | 是 |  | 未测 |  |
| 15 | `com.android.providers.downloads.ui` | 是 |  | 未测 |  |
| 16 | `com.android.soundrecorder` | 是 |  | 未测 |  |
| 17 | `com.anthropic.claude` |  |  | 未测 |  |
| 18 | `com.antutu.ABenchMark` |  |  | 未测 |  |
| 19 | `com.autonavi.minimap` |  | 已装入 | 未测 | 20261113 import-batch1 |
| 20 | `com.baidu.BaiduMap` |  |  | 未测 |  |
| 21 | `com.baidu.input_mi` | 是 |  | 未测 |  |
| 22 | `com.baidu.netdisk` |  |  | 未测 |  |
| 23 | `com.baidu.searchbox` |  |  | 未测 |  |
| 24 | `com.booking` |  |  | 未测 |  |
| 25 | `com.deepseek.chat` |  |  | 未测 |  |
| 26 | `com.dianping.v1` |  | 容器内 | 登录页可达（天翼一键登录），未点 | 20261113 smoke-batch1 |
| 27 | `com.dragon.read` |  |  | 未测 |  |
| 28 | `com.duokan.phone.remotecontroller` | 是 |  | 未测 |  |
| 29 | `com.duokan.reader` | 是 |  | 未测 |  |
| 30 | `com.duolingo` |  |  | 未测 |  |
| 31 | `com.eg.android.AlipayGphone` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 32 | `com.fenxiang.storyline` |  |  | 未测 |  |
| 33 | `com.ghostlock.app` |  |  | 未测 |  |
| 34 | `com.github.android` |  |  | 未测 |  |
| 35 | `com.google.android.apps.authenticator2` |  |  | 未测 |  |
| 36 | `com.google.android.apps.bard` |  |  | 未测 |  |
| 37 | `com.google.android.gm` |  |  | 未测 |  |
| 38 | `com.google.android.googlequicksearchbox` |  |  | 未测 |  |
| 39 | `com.google.android.youtube` |  |  | 未测 |  |
| 40 | `com.hpbr.bosszhipin` |  | 容器内 | 登录闭环（一键登录→完善资料） | 20261113 bosszhipin-container-login |
| 41 | `com.hunantv.imgo.activity` |  |  | 未测 |  |
| 42 | `com.hundsun.winner.pazq` | 是 | 已装入 | **启动崩溃（客体原生层）**：SIGSEGV 写 0x16，栈 `art::JNI SetObjectField` + `<anonymous:JIT>`，无运行时时；Provider 全部初始化后才崩 | [v67](v67-async-external-service-20261113.md) §6 |
| 43 | `com.hyper.gallery.plugin` | 是 |  | 未测 |  |
| 44 | `com.iflytek.inputmethod.miui` | 是 |  | 未测 |  |
| 45 | `com.instagram.android` |  |  | 未测 |  |
| 46 | `com.jingdong.app.mall` |  | 容器内 | 登录成功（本机号码一键登录→个人中心） | 20261113 jd-container-login |
| 47 | `com.kingsoft.xiezuo` |  |  | 未测 |  |
| 48 | `com.larus.nova` |  |  | 未测 |  |
| 49 | `com.luna.music` |  |  | 未测 |  |
| 50 | `com.mfashiongallery.emag` | 是 |  | 未测 |  |
| 51 | `com.mi.health` |  |  | 未测 |  |
| 52 | `com.miHoYo.Yuanshen` |  |  | 未测 |  |
| 53 | `com.microsoft.office.officehubrow` |  |  | 未测 |  |
| 54 | `com.mihoyo.hyperion` |  |  | 未测 |  |
| 55 | `com.mipay.wallet` | 是 |  | 未测 |  |
| 56 | `com.miui.calculator` | 是 |  | 未测 |  |
| 57 | `com.miui.cleanmaster` | 是 |  | 未测 |  |
| 58 | `com.miui.compass` | 是 |  | 未测 |  |
| 59 | `com.miui.gallery` | 是 |  | 未测 |  |
| 60 | `com.miui.huanji` | 是 |  | 未测 |  |
| 61 | `com.miui.mediaeditor` | 是 |  | 未测 |  |
| 62 | `com.miui.newhome` | 是 |  | 未测 |  |
| 63 | `com.miui.newmidrive` | 是 |  | 未测 |  |
| 64 | `com.miui.notes` | 是 |  | 未测 |  |
| 65 | `com.miui.player` | 是 |  | 未测 |  |
| 66 | `com.miui.screenrecorder` | 是 |  | 未测 |  |
| 67 | `com.miui.securitymanager` | 是 |  | 未测 |  |
| 68 | `com.miui.themestore` | 是 |  | 未测 |  |
| 69 | `com.miui.video` | 是 |  | 未测 |  |
| 70 | `com.miui.virtualsim` | 是 |  | 未测 |  |
| 71 | `com.miui.voiceassistProxy` | 是 |  | 未测 |  |
| 72 | `com.miui.weather2` | 是 |  | 未测 |  |
| 73 | `com.netease.cloudmusic` |  | 不可用（结构） | **启动即崩，卡在系统服务身份校验**：`IStorageManager.getVolumeList` → `SecurityException: callingPackage does not match UID`（system_server 抛）。三条投影修法（Binder 重写 / 静态字段注入 / 动态代理）均因 `@hide` 接口在 targetSdk 33 下反射被剥而不可达；下一步只能走 FixedRuntimeHooks 给 `android.os.Environment` 加钩子 | [batch6](batch6-storage-identity-hidden-api-20261113.md) |
| 74 | `com.openai.chatgpt` |  |  | 未测 |  |
| 75 | `com.pangsheng.pshuochepiao.m` |  |  | 未测 |  |
| 76 | `com.phoenix.read` |  |  | 未测 |  |
| 77 | `com.pingan.paces.ccms` |  |  | 未测 |  |
| 78 | `com.qiyi.video` |  |  | 未测 |  |
| 79 | `com.quark.browser` |  |  | 未测 |  |
| 80 | `com.sankuai.meituan` |  | 容器内 | 启动到隐私同意页 | 20261113 smoke-batch1 |
| 81 | `com.sankuai.meituan.takeoutnew` |  |  | 未测 |  |
| 82 | `com.sdu.didi.psnger` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 83 | `com.sina.weibo` |  | 容器内 | 可用：短信验证码登录闭环（新号 `用户7169367851`，读机内短信取码）；登录页 `uiautomator dump` 会崩（无障碍→剪贴板→包名/uid 校验） | [weibo-login-20261113.md](weibo-login-20261113.md) |
| 84 | `com.smile.gifmaker` |  | 已装入 | **可用**（v67）：同意隐私后不再崩，feed 完整可用；登录入口（左上「登录」胶囊/左下「点击登录」）点击无反应，未获登录态 | [v67](v67-async-external-service-20261113.md) §3 |
| 85 | `com.ss.android.article.lite` |  |  | 未测 |  |
| 86 | `com.ss.android.article.news` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 87 | `com.ss.android.ugc.aweme` |  | 容器内 | 不可用：服务端按运行环境判定，入口已加提示 | douyin-risk-control-analysis-20261112 |
| 88 | `com.taobao.idlefish` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 89 | `com.taobao.taobao` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 90 | `com.taou.maimai` |  |  | 未测 |  |
| 91 | `com.tencent.mm` |  | 已装入 | **启动可用 / 登录卡账号侧**：WelcomeActivity→MobileInputUI，手机号可输入；「用短信验证码登录」「更多」点击无响应（无门面降级日志）；新设备短信登录需好友辅助验证且密码未知 ⇒ 交回用户 | [v67](v67-async-external-service-20261113.md) §9 |
| 92 | `com.tencent.mobileqq` |  | 已装入 | 进程存活（mobileqq + :MSF + soterserver），未测登录 | [v67](v67-async-external-service-20261113.md) §10 |
| 93 | `com.tencent.news` |  |  | 未测 |  |
| 94 | `com.tencent.qqlive` |  |  | 未测 |  |
| 95 | `com.tencent.tmgp.WePop` |  |  | 未测 |  |
| 96 | `com.tencent.tmgp.osgame` |  |  | 未测 |  |
| 97 | `com.tencent.tmgp.sgame` |  | 已装入 | **可用**：SGameActivity + `:xg_vip_service` 双进程，渲染到开区页（已有「微信440区 火鹰船长」账号，可直接「开始游戏」） | [v67](v67-async-external-service-20261113.md) §7 |
| 98 | `com.tongcheng.android` |  |  | 未测 |  |
| 99 | `com.twitter.android` |  |  | 未测 |  |
| 100 | `com.x8bit.bitwarden` |  |  | 未测 |  |
| 101 | `com.xiaomi.bestphotos` | 是 |  | 未测 |  |
| 102 | `com.xiaomi.gamecenter` | 是 |  | 未测 |  |
| 103 | `com.xiaomi.mibrain.speech` | 是 |  | 未测 |  |
| 104 | `com.xiaomi.scanner` | 是 |  | 未测 |  |
| 105 | `com.xiaomi.shop` | 是 |  | 未测 |  |
| 106 | `com.xiaomi.smarthome` | 是 |  | 未测 |  |
| 107 | `com.xiaomi.tinygame` | 是 |  | 未测 |  |
| 108 | `com.xiaomi.vipaccount` | 是 |  | 未测 |  |
| 109 | `com.xiaomi.youpin` | 是 |  | 未测 |  |
| 110 | `com.xingin.xhs` |  | 已装入 | **可用（feed），登录未闭环**：两道隐私同意→登录页→「一键登录」无响应→「其他号码登录」→手机号 15323738553→验证码页；机内短信码已取到。卡点在容器侧：`input.app` ANR 后窗口焦点悬空（`FocusedWindows` 空、`mFocusedApp` 停在 subscreencenter），`input text` 全部丢弃；另有自建 `app_process` 子进程 `com.xingin.tiny.daemon.e` 自杀循环（主进程不受影响）。恢复配方：灭屏/亮屏一次可让客体重回 focused app | [batch8](batch8-xhs-login-20261113.md) |
| 111 | `com.xs.fm` |  |  | 未测 |  |
| 112 | `com.xunmeng.pinduoduo` |  | 容器内 | 可用：手机号+短信验证码登录闭环（`153****8553`，个人中心/我的订单/多多钱包可达） | [pinduoduo-login-20261113.md](pinduoduo-login-20261113.md) |
| 113 | `com.zhihu.android` |  | 已装入 | **启动可用**：知乎 TaskP3$F0M2C2，无 FATAL；到「个人信息保护指引」页，dump 正常。登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 114 | `ctrip.android.view` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
| 115 | `dev.tripoder.controltest` |  |  | 未测 |  |
| 116 | `dev.tripoder.q6` |  |  | 未测 |  |
| 117 | `dev.tripoder.q7` |  |  | 未测 |  |
| 118 | `org.telegram.messenger` |  |  | 未测 |  |
| 119 | `org.videolan.vlc` |  |  | 未测 |  |
| 120 | `org.wikipedia` |  |  | 未测 |  |
| 121 | `tv.danmaku.bili` |  | 已装入 | 已导入容器（v70，`CATALOG_READY installs` 21→31），启动/登录未测 | [batch7](batch7-app-import-pipeline-20261113.md) |
## 口径

- 容器状态：`容器内` = 已装入 Tripoder 并在应用列表可见；空 = 未装入。
- 实测结论只写跑出来的东西；`未测` 不是「不可用」。
- 证据列给出 `docs/evidence/` 下的目录名或本轮批次名。
