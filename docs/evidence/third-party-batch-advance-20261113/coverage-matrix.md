# 覆盖矩阵（由 inventory.md + coverage.jsonl 生成）

生成器：`python3 android/scripts/coverage_matrix.py --write`。本表不含人工结论，
「实测」列只来自流水线记录，其余列来自盘点表。

## 状态码

| 码 | 含义 |
|---|---|
| `LOGIN_ENTRY` | 登录入口可达 |
| `LOGIN_PROMPT` | 页面有登录提示（无输入框） |
| `CONSENT_UNRESOLVED` | 停在同意层 |
| `HOME_NO_ACCOUNT_UI` | 已到页面，无账号 UI |
| `PAGE_EMPTY_USE_SHOT` | dump 空树，需截图判读 |
| `NOT_TOP` | 未上顶层 |
| `TILE_NOT_FOUND` | tile 未找到 |
| `NO_TILE_MAPPING` | 未建立 tile 映射 |
| `ENGINE_DOWN` | 引擎未连接 |
| `GUEST_KILLED_BY_DUMP` | dump 杀客体（剪贴板族） |
| `PIPELINE_ERROR` | 流水线异常 |

## 覆盖总览

| 口径 | 数量 |
|---|---|
| `pm list packages -3` 全量 | 121 |
| 扣除厂商/系统捆绑 | 78 |
| 已进流水线（有实测记录） | 9 |
| 尚未实测 | 69 |

## 逐项矩阵

| # | 包名 | tile | 容器内 | 实测结论 | 落点 top | 客体重启不影响计数 | 备注 |
|---|---|---|---|---|---|---|---|
| 1 | `app.personal.linktools` |  | — | 未测 |  |  | 未测 |
| 2 | `bbc.mobile.news.ww` |  | — | 未测 |  |  | 未测 |
| 3 | `cmb.pb` | 招商银行 | — | 未测 |  |  | 未测 |
| 4 | `cn.cyberIdentity.certification` |  | — | 未测 |  |  | 未测 |
| 5 | `cn.wps.moffice_eng` | WPS Office | — | 未测 |  |  | 未测 |
| 6 | `cn.wps.moffice_eng.xiaomi.lite` |  | — | 未测 |  |  | 未测 |
| 7 | `com.achievo.vipshop` | 唯品会 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；同意按钮 dump 文本被截断为「同」，文本匹配三候选全落空（取证手段问题） |
| 8 | `com.alibaba.android.rimet` | 钉钉 | 启动成功 TaskP2$F1M1C2（fatal=0）；首屏原生同意层「温馨提示」同意/不同意 | 盘点有结论·待补流水线记录 |  |  | [batch24](batch23-coverage-batch-20261113.md) |
| 9 | `com.alicloud.databox` |  | — | 未测 |  |  | 未测 |
| 10 | `com.aliyun.tongyi` | 千问 | 启动成功 TaskP1$F1M1C0E0；tile 文案是「千问」；首屏「欢迎使用 千问」同意层 | 盘点有结论·待补流水线记录 |  |  | [batch24](batch23-coverage-batch-20261113.md) |
| 17 | `com.anthropic.claude` |  | — | 未测 |  |  | 未测 |
| 18 | `com.antutu.ABenchMark` | 安兔兔评测 | 启动成功 TaskP1$F1M0C2；首屏「欢迎您使用 安兔兔评测!」同意层 | 盘点有结论·待补流水线记录 |  |  | [batch24](batch23-coverage-batch-20261113.md) |
| 19 | `com.autonavi.minimap` | 高德地图 | 容器内 | LOGIN_ENTRY（登录入口可达） | TaskP1$F1M2C0E0 | fatal=0 adm=0 anr=0 | 启动成功 TaskP1$F1M2C0E0；无同意层（consent_taps=0），首页底栏「未登录」为登录入口 |
| 20 | `com.baidu.BaiduMap` | 百度地图 | — | 未测 |  |  | 未测 |
| 22 | `com.baidu.netdisk` | 百度网盘 | 容器内 | LOGIN_ENTRY（登录入口可达） | TaskP1$F1M2C2 | fatal=0 adm=0 anr=0 | 启动成功 TaskP1$F1M0C2；登录页「登录百度账号体验完整功能」+ 手机号输入框 + 验证码登录 + 其他登录方 |
| 23 | `com.baidu.searchbox` | 百度 | 容器内 | EXITED_AFTER_LAUNCH（?） | dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity | fatal=1 adm=0 anr=0 | 首次落点 TaskP1$F1M2C1，但同轮 fatal=1 且 top 已回落 Workbench：需按容器/客体栈归 |
| 24 | `com.booking` | Booking.com缤客 | v94：五条 CheckBox 同意墙可通过（单点「全选」行右端），同意后应用自行 killProcess 重启；复启落 Cookie 设置页→接受→HomeActivity，进程健在无异常；首页内容层全黑（同 U4 族，另记） | 盘点有结论·待补流水线记录 |  |  | [batch24](batch23-coverage-batch-20261113.md) |
| 25 | `com.deepseek.chat` | DeepSeek | — | 未测 |  |  | 未测 |
| 26 | `com.dianping.v1` | 大众点评 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 登录页可达（天翼一键登录），未点 |
| 27 | `com.dragon.read` |  | — | 未测 |  |  | 未测 |
| 30 | `com.duolingo` | 多邻国 | 容器内 | HOME_NO_ACCOUNT_UI（已到页面，无账号 UI） | TaskP1$F1M1C0E0 | fatal=0 adm=0 anr=0 | 启动成功 TaskP1$F1M1C0E0；无同意层，直接落引导页（无登录入口文案） |
| 31 | `com.eg.android.AlipayGphone` | 支付宝 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；无隐私页，首屏登录页「淘宝用户快速登录」[364,1731][844,1811]、「手机号」[250,2262 |
| 32 | `com.fenxiang.storyline` |  | — | 未测 |  |  | 未测 |
| 33 | `com.ghostlock.app` |  | — | 未测 |  |  | 未测 |
| 34 | `com.github.android` | GitHub | — | 未测 |  |  | 未测 |
| 35 | `com.google.android.apps.authenticator2` |  | — | 未测 |  |  | 未测 |
| 36 | `com.google.android.apps.bard` |  | — | 未测 |  |  | 未测 |
| 37 | `com.google.android.gm` |  | — | 未测 |  |  | 未测 |
| 38 | `com.google.android.googlequicksearchbox` |  | — | 未测 |  |  | 未测 |
| 39 | `com.google.android.youtube` |  | — | 未测 |  |  | 未测 |
| 40 | `com.hpbr.bosszhipin` | BOSS直聘 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 登录闭环（一键登录→完善资料） |
| 41 | `com.hunantv.imgo.activity` |  | — | 未测 |  |  | 未测 |
| 45 | `com.instagram.android` |  | — | 未测 |  |  | 未测 |
| 46 | `com.jingdong.app.mall` | 京东 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 登录成功（本机号码一键登录→个人中心） |
| 47 | `com.kingsoft.xiezuo` |  | — | 未测 |  |  | 未测 |
| 48 | `com.larus.nova` | 豆包 | — | 未测 |  |  | 未测 |
| 49 | `com.luna.music` |  | — | 未测 |  |  | 未测 |
| 51 | `com.mi.health` |  | — | 未测 |  |  | 未测 |
| 52 | `com.miHoYo.Yuanshen` |  | — | 未测 |  |  | 未测 |
| 53 | `com.microsoft.office.officehubrow` |  | — | 未测 |  |  | 未测 |
| 54 | `com.mihoyo.hyperion` |  | — | 未测 |  |  | 未测 |
| 73 | `com.netease.cloudmusic` | 网易云音乐 | 不可用（结构） | 盘点有结论·待补流水线记录 |  |  | **启动即崩，卡在系统服务身份校验**：`IStorageManager.getVolumeList` → `Secur |
| 74 | `com.openai.chatgpt` | ChatGPT | — | 未测 |  |  | 未测 |
| 75 | `com.pangsheng.pshuochepiao.m` |  | — | 未测 |  |  | 未测 |
| 76 | `com.phoenix.read` | 红果免费短剧 | — | 未测 |  |  | 未测 |
| 77 | `com.pingan.paces.ccms` |  | — | 未测 |  |  | 未测 |
| 78 | `com.qiyi.video` | 爱奇艺 | 容器内 | NOT_TOP（未上顶层） | dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity | fatal=0 adm=0 anr=0 | **启动被 UI 拒绘**：点 tile 后 top 恒为 Workbench、客体重进程数 0、crash 空，Wor |
| 79 | `com.quark.browser` | 夸克 | 容器内 | HOME_NO_ACCOUNT_UI（已到页面，无账号 UI） | TaskP1$F1M2C2 | fatal=0 adm=0 anr=0 | 启动成功 TaskP1$F1M2C2；同意 1 次后落首页，无登录入口文案 |
| 80 | `com.sankuai.meituan` | 美团 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动到隐私同意页 |
| 81 | `com.sankuai.meituan.takeoutnew` |  | — | 未测 |  |  | 未测 |
| 82 | `com.sdu.didi.psnger` | 滴滴 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；同意协议后首页可达，登录入口「立即登录」[929,2281][1131,2358] |
| 83 | `com.sina.weibo` | 微博 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 可用：短信验证码登录闭环（新号 `用户7169367851`，读机内短信取码）；登录页 `uiautomator dum |
| 84 | `com.smile.gifmaker` | 快手 | 已装入 | 盘点有结论·待补流水线记录 |  |  | **可用**（v67）：同意隐私后不再崩，feed 完整可用；登录入口（左上「登录」胶囊/左下「点击登录」）点击无反应， |
| 85 | `com.ss.android.article.lite` |  | — | 未测 |  |  | 未测 |
| 86 | `com.ss.android.article.news` | 今日头条 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；同意隐私后首页可达，登录入口右上「登录」[1099,2378][1187,2424] |
| 87 | `com.ss.android.ugc.aweme` | 抖音 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 不可用：服务端按运行环境判定，入口已加提示 |
| 88 | `com.taobao.idlefish` | 闲鱼 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；「同意」点击命中但仍停在同意/不同意（二次确认层） |
| 89 | `com.taobao.taobao` | 淘宝 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；「同意」点击命中但仍停在同意/不同意（二次确认层） |
| 90 | `com.taou.maimai` |  | — | 未测 |  |  | 未测 |
| 91 | `com.tencent.mm` | 微信 | 已装入 | 盘点有结论·待补流水线记录 |  |  | **启动可用 / 登录卡账号侧**：WelcomeActivity→MobileInputUI，手机号可输入；「用短信验 |
| 92 | `com.tencent.mobileqq` | QQ | 已装入 | 未测 |  |  | 进程存活（mobileqq + :MSF + soterserver），未测登录 |
| 93 | `com.tencent.news` | 腾讯新闻 | — | EXITED_AFTER_LAUNCH（?） | dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity | fatal=0 adm=0 anr=1 | 未测 |
| 94 | `com.tencent.qqlive` | 腾讯视频 | 容器内 | LOGIN_ENTRY（登录入口可达） | TaskP1$F1M1C2 | fatal=0 adm=0 anr=0 | 启动成功 TaskP1$F1M1C2；首页登录入口四条：微信登录 / QQ登录 / 其他登录方式 / 底栏未登录（con |
| 95 | `com.tencent.tmgp.WePop` |  | — | 未测 |  |  | 未测 |
| 96 | `com.tencent.tmgp.osgame` |  | — | 未测 |  |  | 未测 |
| 97 | `com.tencent.tmgp.sgame` | 王者荣耀 | 已装入 | 盘点有结论·待补流水线记录 |  |  | **可用**：SGameActivity + `:xg_vip_service` 双进程，渲染到开区页（已有「微信440 |
| 98 | `com.tongcheng.android` |  | — | 未测 |  |  | 未测 |
| 99 | `com.twitter.android` |  | — | 未测 |  |  | 未测 |
| 100 | `com.x8bit.bitwarden` |  | — | 未测 |  |  | 未测 |
| 110 | `com.xingin.xhs` | 小红书 | 已装入 | 盘点有结论·待补流水线记录 |  |  | **可用（feed），登录未闭环**：两道隐私同意→登录页→「一键登录」无响应→「其他号码登录」→手机号 1532373 |
| 111 | `com.xs.fm` | 番茄畅听 | — | HOME_NO_ACCOUNT_UI（已到页面，无账号 UI） | TaskP1$F1M2C2 | fatal=0 adm=0 anr=0 | 未测 |
| 112 | `com.xunmeng.pinduoduo` | 拼多多 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 可用：手机号+短信验证码登录闭环（`153****8553`，个人中心/我的订单/多多钱包可达） |
| 113 | `com.zhihu.android` | 知乎 | 已装入 | 未测 |  |  | **启动可用**：知乎 TaskP3$F0M2C2，无 FATAL；到「个人信息保护指引」页，dump 正常。登录未测 |
| 114 | `ctrip.android.view` | 携程旅行 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；同意后页面已变，未产出登录类文案（需再取证） |
| 118 | `org.telegram.messenger` | Telegram | — | 未测 |  |  | 未测 |
| 119 | `org.videolan.vlc` | VLC | — | 未测 |  |  | 未测 |
| 120 | `org.wikipedia` | 维基百科 | — | 未测 |  |  | 未测 |
| 121 | `tv.danmaku.bili` | 哔哩哔哩 | 容器内 | 盘点有结论·待补流水线记录 |  |  | 启动成功；点「同意并继续」后客体 FATAL（tv.danmaku.bili PID 28536），top 回落 Wor |
