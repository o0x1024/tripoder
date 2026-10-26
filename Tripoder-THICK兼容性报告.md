# Tripoder 兼容性测试报告 · THICK 全量

这份报告只回答一件事：**同一批手机应用，在引擎的 THICK 模式（全量适配、全局反优化）下，能自己渲染出前台界面并稳定运行的有多少**，并与 `Tripoder-兼容性测试报告.md` 里的 THIN 结果逐包对照。

## 1. 为什么要清库，以及清库前试过什么

引擎的 activity affinity 租约池只有 8 个 slot：`ActivityAffinityLedger.acquire()` 按 `(space, uidDomain, logical)` 占位，池满即抛 `ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED`；租约只在「删除拥有该组件的 space」时由 `releaseUnused()` 回收。

这形成了一个自锁循环：**一次失败的启动会把该 space fence 成 `RECOVERY_SCOPE_UNKNOWN`，而被 fence 的 space 既不能启动、也不能删除**，于是它占住的租约永远回不到池子；攒够 8 个，后续每次启动都被容量拒绝，而每次被拒绝的启动又 fence 掉一个新的 space。

本轮不是一上手就清库。先在 run2 里用 `--recycle`（判完即删 space）回收租约跑完了大部分应用，但在引擎中途掉线一次之后陷入了上述自锁，剩余包无法推进。此后的解锁尝试与结果：

| 尝试 | 结果 |
| --- | --- |
| `space_delete` 删被 fence 的 space | 拒绝：`RECOVERY_SCOPE_UNKNOWN` |
| `application_remove` 带 `installGeneration` 精确卸载 | 拒绝：同一 fence |
| 换一个全新 space 重新导入同一包 | 导入成功、绕开了 fence，但启动立刻撞 `CAPACITY_EXHAUSTED`，**该次失败又把新 space 一并 fence 掉** |
| `am force-stop` 冷启动引擎后再删 | fence 原样存活 —— 证明它是**持久化**状态而非进程内状态 |

最后一条是关键判别：既然冷启动不能解锁，剩下的只有清库。设备是无 root 的 production build（`run-as` 不可用、`adb root` 被拒、`allowBackup` 未开），既没法只删数据库文件，也没有 `adb backup` 可回滚——**这一步不可逆，是本轮唯一一次由人决定的不可逆操作**。清库前已落逻辑快照（`artifacts/compat-thick-run2/evidence/pre-clear/`：journal 508 行、applications、spaces、capabilities）。必须一并披露：跑测驱动此后又在容量耗尽时自动执行了 3 次同类清空，见第 2 节「跑测中的自愈清空」。

清库时点落在 **run2 journal 最后一行（08:52:41）之后、run3 首条导入（09:31:11）之前**。这不是采信谁的转述，而是两条可复核的痕迹：

- run2 里 `com.tencent.mm` 的导入结果是 `ALREADY`（复用旧安装）；run3 的首条导入就在 09:31:11，它把同一个包记成 `SUCCEEDED`（全新安装）——旧安装已经不在了。
- run2 留下 **31** 个被 fence 的 space。fence 是持久化状态：run2 已用冷启动验证过它跨进程存活，且它连删除都拒绝。run3 里这 31 个包最终都重新导入成功（**31** 个 `SUCCEEDED`，没有一个停在 `SCOPE_FENCED`）。但其中 30 个的导入发生在 09:58 之后，即驱动后续三次自愈清空之后（见第 2 节），所以这条只能证明「到 run3 结束时没有 run2 的 fence 存活」，**不能单独用来定位第一次清库的时点**；第一次清库的时点由上面 `com.tencent.mm` 的 `ALREADY → SUCCEEDED` 与 run2 末行共同界定。

清库后按「设置 → 开启网络 MCP → 导出连接配置 → `adb pull /storage/emulated/0/tripoder/`」重建凭据，`runtime_query status` 返回 `hookReady=true` 后才开始跑。**因此本报告的正式数据集是清库后的 run3**；run2 的 90 条终态判定只作为过程证据保留。

## 2. 方法

```bash
adb -s d861a4fa shell pm clear dev.tripoder.engine    # 清掉 fence / 租约池 / 历史安装
bash scripts/qa/run_thick_run3.sh                     # 初版：round 循环
bash scripts/qa/run_thick_run3b.sh                    # 续跑：pending() 选批 + 容量耗尽自愈
bash scripts/qa/run_thick_run4.sh                     # 收尾：6 个/批，连续 2 代无进展即退出
python3 scripts/qa/thick_report.py --run artifacts/compat-thick-run3 --thin artifacts/compat-run1/journal.jsonl --previous artifacts/compat-thick-run2/journal.jsonl --calibration artifacts/compat-thin-calibration2/journal.jsonl --out Tripoder-THICK兼容性报告.md
```

- 判定与 THIN 完全相同的四条信号：`process_start` 接受并返回 pid、进程仍在 `application_query targets`、`mCurrentFocus` 属于该包、logcat 无该 pid 的 crash/ANR 块。四条同时满足才记 `PASS`。
- 每次启动前用 `application_manage action=set_mode` 把停着的安装切到 THICK，并从 `application_query action=launch_entries` 读回 `mode` 确认；本轮有 105 个包记录了 `thin->thick` 的切换。
- `--recycle`：每判完一个应用就删掉它的 space，把 affinity 租约还给池子。
- **删 space 前先 `application_stop`**：判完的 guest 常还挂着 WorkManager 作业，直接删会得到 `INSTALL_IN_USE_BY_JOB`；这种删除失败会永久泄漏一个租约，正是上面自锁的起点。
- **分批 + 自愈**：每批 6–10 个包（`BATCH` 逐轮下调），批前用 `runtime_query status` 探活；控制器掉线则重新拉起并按「设置 → 网络 MCP → 导出连接配置」重导凭据，把「引擎掉线」和「应用不兼容」分开记。
- **失败可重试**：`--retry-failed` 让历史上记为 `IMPORT_FAILED` / `SCOPE_FENCED` 的包重新导入，否则续跑会把这些包当成已有结论永久跳过。

### 跑测中的自愈清空（披露）

run3 不是一次连续无中断的会话：驱动在 affinity 池再次耗尽时执行 `adb shell pm clear dev.tripoder.engine` 自愈，共 **3** 次（日志原文 `=== wipe_engine <时间> (affinity pool exhausted)`）：

| # | 时间 | journal 中断 | 证据 |
|---|---|---|---|
| 1 | 09:58:11 | 约 6 分钟（恢复于 10:04:16） | `artifacts/compat-thick-run3/logs/run-thick-run3.log:299` |
| 2 | 10:11:35 | 约 12 分钟（恢复于 10:23:16） | `artifacts/compat-thick-run3/logs/run-thick-run3.log:450` |
| 3 | 10:43:47 | 约 5 分钟（恢复于 10:48:25） | `artifacts/compat-thick-run3/logs/run-thick-run3.log:1063` |

后果与边界：

- 清空会删掉当时全部 space 和安装。`phase=recycle` 里的 `SPACE_NOT_FOUND` 因此不是回收接口失效，而是 space 已被整库清掉；已记录的判定发生在清空之前，不受影响。
- 没有拿已被清掉的安装去启动：303 条 import 记录对应 123 个包，没有任何包第二次 `SUCCEEDED`；launch 记录里 `NO_INSTALLATION` 出现 0 次。即每次清空后只继续处理尚未判定的包，每一条判定都在同段内「导入→启动」完成。
- 清空会连 MCP 凭据一起失效。前两次清空后日志停在 `wipe: MCP still down`；第三次清空后修复一度报 `no new export`；journal 直到 10:48:25 才恢复。这些空档里没有写任何判定。
- 117 条有效判定因此分布在 4 个连续段（09:31–09:58、10:04–10:11、10:23–10:43、10:48–10:55），每段内的安装与启动都在同一段完成。

## 3. 覆盖与结果

- 设备应用总数（THIN 轮盘点）：**123**
- 拿到 THICK 有效判定：**117**
- 安装阶段：`SUCCEEDED` 105，`IMPORT_FAILED` 18
- 驱动收尾计数：`judged=121 left=2`——121 = 117 条有效判定 + 4 条判为 `START_REJECTED` 的引擎侧失败；`left` 的 2 条是 `MODE_SET_FAILED` / `SCOPE_FENCED` 的引擎侧失败。6 条引擎侧失败都不在有效判定内。

| THICK 结果 | 数量 | 占有效判定 |
|---|---|---|
| PASS | 34 | 29.1% |
| START_REJECTED | 31 | 26.5% |
| CRASH_ON_START | 26 | 22.2% |
| IMPORT_FAILED | 18 | 15.4% |
| PROCESS_GONE | 4 | 3.4% |
| NO_FOREGROUND_SCREEN | 4 | 3.4% |

## 4. THIN → THICK 对照

| THIN | THICK | 数量 |
|---|---|---|
| PASS | PASS | 32 |
| START_REJECTED | START_REJECTED | 21 |
| IMPORT_FAILED | IMPORT_FAILED | 18 |
| PASS | CRASH_ON_START | 14 |
| CRASH_ON_START | CRASH_ON_START | 11 |
| PASS | START_REJECTED | 9 |
| START_REJECTED | NO_FOREGROUND_SCREEN | 3 |
| PROCESS_GONE | PROCESS_GONE | 2 |
| CRASH_ON_START | START_REJECTED | 1 |
| CRASH_ON_START | PASS | 1 |
| CRASH_ON_START | PROCESS_GONE | 1 |
| START_REJECTED | PASS | 1 |
| PASS | PROCESS_GONE | 1 |
| CRASH_ON_START | NO_FOREGROUND_SCREEN | 1 |
| START_REJECTED | CRASH_ON_START | 1 |

THIN 基线按 `Tripoder-兼容性测试报告.md` 的最终口径合成：首轮 `compat-run1/journal.jsonl` + `compat-run1-retry/retry.jsonl`（21 个复测包；`retry2` 只复测哔哩哔哩且仍失败）。其中 3 个由非 PASS 转 PASS（`com.google.android.apps.authenticator2`、`com.luna.music`、`org.wikipedia`），按 PASS 计入；若只看首轮，这些会被误记成「THICK 修好了」。

结果与 THIN 不同的包共 **33** 个：

| 包名 | THIN | THICK | 记录 |
|---|---|---|---|
| `bbc.mobile.news.ww` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:179` |
| `com.alicloud.databox` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:205` |
| `com.android.providers.downloads.ui` | CRASH_ON_START | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:213` |
| `com.anthropic.claude` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:69` |
| `com.dianping.v1` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:281` |
| `com.duokan.reader` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:119` |
| `com.eg.android.AlipayGphone` | CRASH_ON_START | PROCESS_GONE | `artifacts/compat-thick-run3/journal.jsonl:29` |
| `com.fenxiang.storyline` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:284` |
| `com.github.android` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:144` |
| `com.google.android.apps.bard` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:305` |
| `com.kingsoft.xiezuo` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:344` |
| `com.mipay.wallet` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:549` |
| `com.miui.player` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:583` |
| `com.miui.securitymanager` | START_REJECTED | NO_FOREGROUND_SCREEN | `artifacts/compat-thick-run3/journal.jsonl:584` |
| `com.miui.themestore` | START_REJECTED | NO_FOREGROUND_SCREEN | `artifacts/compat-thick-run3/journal.jsonl:586` |
| `com.miui.virtualsim` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:609` |
| `com.miui.voiceassistProxy` | START_REJECTED | NO_FOREGROUND_SCREEN | `artifacts/compat-thick-run3/journal.jsonl:610` |
| `com.openai.chatgpt` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:46` |
| `com.qiyi.video` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:109` |
| `com.sankuai.meituan` | PASS | PROCESS_GONE | `artifacts/compat-thick-run3/journal.jsonl:34` |
| `com.ss.android.article.lite` | CRASH_ON_START | NO_FOREGROUND_SCREEN | `artifacts/compat-thick-run3/journal.jsonl:640` |
| `com.ss.android.article.news` | START_REJECTED | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:85` |
| `com.ss.android.ugc.aweme` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:30` |
| `com.taou.maimai` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:643` |
| `com.tencent.mobileqq` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:121` |
| `com.tencent.tmgp.osgame` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:646` |
| `com.twitter.android` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:143` |
| `com.x8bit.bitwarden` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:146` |
| `com.xiaomi.gamecenter` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:664` |
| `com.xiaomi.smarthome` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:680` |
| `com.xiaomi.youpin` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:695` |
| `org.videolan.vlc` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:150` |
| `org.wikipedia` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:148` |

## 5. 逐包明细

| # | 包名 | THICK | THIN | 记录 |
|---|---|---|---|---|
| 1 | `app.personal.linktools` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:177` |
| 2 | `bbc.mobile.news.ww` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:179` |
| 3 | `cmb.pb` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:182` |
| 4 | `cn.cyberIdentity.certification` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:185` |
| 5 | `cn.wps.moffice_eng` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:152` |
| 6 | `cn.wps.moffice_eng.xiaomi.lite` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:517` |
| 7 | `com.achievo.vipshop` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:186` |
| 8 | `com.alibaba.android.rimet` | PROCESS_GONE | PROCESS_GONE | `artifacts/compat-thick-run3/journal.jsonl:73` |
| 9 | `com.alicloud.databox` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:205` |
| 10 | `com.aliyun.tongyi` | PROCESS_GONE | PROCESS_GONE | `artifacts/compat-thick-run3/journal.jsonl:206` |
| 11 | `com.android.calendar` | START_REJECTED（引擎侧失败，未计入） | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:208` |
| 12 | `com.android.chrome` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:40` |
| 13 | `com.android.deskclock` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:209` |
| 14 | `com.android.email` | START_REJECTED（引擎侧失败，未计入） | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:211` |
| 15 | `com.android.providers.downloads.ui` | START_REJECTED | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:213` |
| 16 | `com.android.soundrecorder` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:234` |
| 17 | `com.anthropic.claude` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:69` |
| 18 | `com.antutu.ABenchMark` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:236` |
| 19 | `com.autonavi.minimap` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:74` |
| 20 | `com.baidu.BaiduMap` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:78` |
| 21 | `com.baidu.input_mi` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:534` |
| 22 | `com.baidu.netdisk` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:519` |
| 23 | `com.baidu.searchbox` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:239` |
| 24 | `com.bocconnect.prd` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:240` |
| 25 | `com.bochk.bn.aos` | START_REJECTED（引擎侧失败，未计入） | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:258` |
| 26 | `com.bochk.bocpay` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:259` |
| 27 | `com.bochk.igtb` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:260` |
| 28 | `com.bochk.mortgage.android.hk` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:261` |
| 29 | `com.booking` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:154` |
| 30 | `com.deepseek.chat` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:71` |
| 31 | `com.dianping.v1` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:281` |
| 32 | `com.dragon.read` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:116` |
| 33 | `com.duokan.phone.remotecontroller` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:282` |
| 34 | `com.duokan.reader` | PASS | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:119` |
| 35 | `com.duolingo` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:176` |
| 36 | `com.eg.android.AlipayGphone` | PROCESS_GONE | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:29` |
| 37 | `com.fenxiang.storyline` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:284` |
| 38 | `com.ghostlock.app` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:285` |
| 39 | `com.github.android` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:144` |
| 40 | `com.google.android.apps.authenticator2` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:304` |
| 41 | `com.google.android.apps.bard` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:305` |
| 42 | `com.google.android.gm` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:306` |
| 43 | `com.google.android.googlequicksearchbox` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:307` |
| 44 | `com.google.android.youtube` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:43` |
| 45 | `com.hpbr.bosszhipin` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:327` |
| 46 | `com.hunantv.imgo.activity` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:329` |
| 47 | `com.hundsun.winner.pazq` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:520` |
| 48 | `com.hyper.gallery.plugin` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:521` |
| 49 | `com.iflytek.inputmethod.miui` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:522` |
| 50 | `com.instagram.android` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:122` |
| 51 | `com.jingdong.app.mall` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:35` |
| 52 | `com.kingsoft.xiezuo` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:344` |
| 53 | `com.larus.nova` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:358` |
| 54 | `com.luna.music` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:112` |
| 55 | `com.mfashiongallery.emag` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:523` |
| 56 | `com.mi.health` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:544` |
| 57 | `com.miHoYo.Yuanshen` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:545` |
| 58 | `com.microsoft.office.officehubrow` | MODE_SET_FAILED（引擎侧失败，未计入） | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:705` |
| 59 | `com.mihoyo.hyperion` | START_REJECTED（引擎侧失败，未计入） | PASS | `artifacts/compat-thick-run3/journal.jsonl:548` |
| 60 | `com.mipay.wallet` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:549` |
| 61 | `com.miui.calculator` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:551` |
| 62 | `com.miui.cleanmaster` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:540` |
| 63 | `com.miui.compass` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:553` |
| 64 | `com.miui.gallery` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:555` |
| 65 | `com.miui.huanji` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:578` |
| 66 | `com.miui.mediaeditor` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:569` |
| 67 | `com.miui.newhome` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:570` |
| 68 | `com.miui.newmidrive` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:571` |
| 69 | `com.miui.notes` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:581` |
| 70 | `com.miui.player` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:583` |
| 71 | `com.miui.screenrecorder` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:574` |
| 72 | `com.miui.securitymanager` | NO_FOREGROUND_SCREEN | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:584` |
| 73 | `com.miui.themestore` | NO_FOREGROUND_SCREEN | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:586` |
| 74 | `com.miui.video` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:608` |
| 75 | `com.miui.virtualsim` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:609` |
| 76 | `com.miui.voiceassistProxy` | NO_FOREGROUND_SCREEN | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:610` |
| 77 | `com.miui.weather2` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:613` |
| 78 | `com.netease.cloudmusic` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:111` |
| 79 | `com.openai.chatgpt` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:46` |
| 80 | `com.pangsheng.pshuochepiao.m` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:602` |
| 81 | `com.phoenix.read` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:616` |
| 82 | `com.pingan.paces.ccms` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:618` |
| 83 | `com.qiyi.video` | PASS | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:109` |
| 84 | `com.quark.browser` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:621` |
| 85 | `com.sankuai.meituan` | PROCESS_GONE | PASS | `artifacts/compat-thick-run3/journal.jsonl:34` |
| 86 | `com.sankuai.meituan.takeoutnew` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:622` |
| 87 | `com.sdu.didi.psnger` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:76` |
| 88 | `com.sina.weibo` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:81` |
| 89 | `com.smile.gifmaker` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:516` |
| 90 | `com.ss.android.article.lite` | NO_FOREGROUND_SCREEN | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:640` |
| 91 | `com.ss.android.article.news` | CRASH_ON_START | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:85` |
| 92 | `com.ss.android.ugc.aweme` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:30` |
| 93 | `com.taobao.idlefish` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:641` |
| 94 | `com.taobao.taobao` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:6` |
| 95 | `com.taou.maimai` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:643` |
| 96 | `com.tencent.mm` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:5` |
| 97 | `com.tencent.mobileqq` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:121` |
| 98 | `com.tencent.news` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:627` |
| 99 | `com.tencent.qqlive` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:515` |
| 100 | `com.tencent.tmgp.WePop` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:637` |
| 101 | `com.tencent.tmgp.osgame` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:646` |
| 102 | `com.tencent.tmgp.sgame` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:660` |
| 103 | `com.tongcheng.android` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:661` |
| 104 | `com.twitter.android` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:143` |
| 105 | `com.x8bit.bitwarden` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:146` |
| 106 | `com.xiaomi.bestphotos` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:656` |
| 107 | `com.xiaomi.gamecenter` | START_REJECTED | PASS | `artifacts/compat-thick-run3/journal.jsonl:664` |
| 108 | `com.xiaomi.mibrain.speech` | IMPORT_FAILED | IMPORT_FAILED | `artifacts/compat-thick-run3/journal.jsonl:658` |
| 109 | `com.xiaomi.scanner` | SCOPE_FENCED（引擎侧失败，未计入） | SCOPE_FENCED | `artifacts/compat-thick-run3/journal.jsonl:706` |
| 110 | `com.xiaomi.shop` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:679` |
| 111 | `com.xiaomi.smarthome` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:680` |
| 112 | `com.xiaomi.tinygame` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:682` |
| 113 | `com.xiaomi.vipaccount` | CRASH_ON_START | CRASH_ON_START | `artifacts/compat-thick-run3/journal.jsonl:685` |
| 114 | `com.xiaomi.youpin` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:695` |
| 115 | `com.xingin.xhs` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:31` |
| 116 | `com.xs.fm` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:114` |
| 117 | `com.xunmeng.pinduoduo` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:38` |
| 118 | `com.zhihu.android` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:83` |
| 119 | `ctrip.android.view` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:696` |
| 120 | `org.telegram.messenger` | PASS | PASS | `artifacts/compat-thick-run3/journal.jsonl:44` |
| 121 | `org.videolan.vlc` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:150` |
| 122 | `org.wikipedia` | CRASH_ON_START | PASS | `artifacts/compat-thick-run3/journal.jsonl:148` |
| 123 | `tv.danmaku.bili` | START_REJECTED | START_REJECTED | `artifacts/compat-thick-run3/journal.jsonl:86` |

## 6. 跑测自身的可靠性

- space 回收（affinity 租约归还）：尝试 **141** 次，成功 **42** 次，失败 **99** 次。失败码 Top：`RECOVERY_SCOPE_UNKNOWN` × 66，`SPACE_NOT_FOUND` × 31，`INSTALL_IN_USE_BY_JOB` × 1，`INSTALL_IN_USE_BY_TASK_COMPONENT` × 1
- 回收失败不代表判定无效：判定发生在回收之前，失败只影响后续包能否拿到租约。
- 失败码里的 `SPACE_NOT_FOUND` 由第 2 节的 3 次自愈清空造成（space 被整库清掉），不代表回收接口本身失效。
- 同一包、同一模式、两次独立运行（`compat-thick-pilot3` 与本轮）重叠 3 个，其中 **2** 个结果不同：`com.github.android` PASS→CRASH_ON_START，`tv.danmaku.bili` PASS→START_REJECTED。这说明单次判定存在抖动，`CRASH_ON_START` 类是「这一次启动崩了」，不是「永远崩」。
- **校准**：候选是 24 个「THIN 基线 PASS、THICK 失败」的包；本轮抽测其中 10 个，在当前引擎上重跑 THIN 模式：**8** 个复现 PASS（失败可归因于 THICK），**2** 个在 THIN 下也失败（说明是引擎状态/时间抖动，不是模式差异）。另有 1 个校准运行本身撞上引擎侧失败，无法判定。

| 包名 | THIN 基线 | THICK | THIN 复测 | 归属 |
|---|---|---|---|---|
| `bbc.mobile.news.ww` | PASS | CRASH_ON_START | CRASH_ON_START | 引擎状态/抖动 |
| `com.alicloud.databox` | PASS | CRASH_ON_START | PASS | THICK 导致 |
| `com.anthropic.claude` | PASS | CRASH_ON_START | PASS | THICK 导致 |
| `com.dianping.v1` | PASS | START_REJECTED | PASS | THICK 导致 |
| `com.github.android` | PASS | CRASH_ON_START | CRASH_ON_START | 引擎状态/抖动 |
| `com.openai.chatgpt` | PASS | CRASH_ON_START | PASS | THICK 导致 |
| `com.sankuai.meituan` | PASS | PROCESS_GONE | PASS | THICK 导致 |
| `com.tencent.mobileqq` | PASS | START_REJECTED | PASS | THICK 导致 |
| `com.twitter.android` | PASS | CRASH_ON_START | PASS | THICK 导致 |
| `org.videolan.vlc` | PASS | CRASH_ON_START | PASS | THICK 导致 |

## 7. 结论与局限

- 本轮 THICK 下 **34/117** 个应用能自行渲染出前台界面且未崩溃。
- 判定是「冷启动 + 固定观察窗」的一次采样，不是逐帧 UI 比对；同一包重复跑可能得到不同结果（见第 6 节的重复运行对照）。
- THICK 会全局反优化，启动明显更慢；观察窗对超大应用可能偏紧，超时会被记成 `NO_FOREGROUND_SCREEN` 而不是 `PASS`，这类需要更长窗口才能定论。
- 引擎控制器中途死亡是本轮最大的噪声源：它为**后续**的包留下 `MODE_SET_FAILED`，这些行在报告里被显式排除在有效判定之外，不计入 PASS，也不计成不兼容。
- run3 期间驱动自愈清空 3 次；117 条有效判定分布在 4 个连续段，跨段的安装状态不共享（见第 2 节）。
- 校准（第 6 节）：8/10 个「THIN 基线 PASS、THICK 失败」的样本在 THIN 下复现 PASS，另外 2/10 个在 THIN 下同样失败——后者的差异不能记在 THICK 头上。
- 校准样本只有 10/24 个候选；未复测的 14 个「THIN PASS、THICK 失败」仍可能混有引擎抖动，不能按 8/10 的比例外推。

## 8. 记录索引

- THICK 判定：`artifacts/compat-thick-run3/journal.jsonl`
- THIN 判定：`artifacts/compat-run1/journal.jsonl`（首轮）+ `artifacts/compat-run1-retry/retry.jsonl`（21 个复测包，按最终结果覆盖）
- 跑测日志（副本）：`artifacts/compat-thick-run3/logs/run-thick-run3.log`（run3 / run3b）、`artifacts/compat-thick-run3/logs/run-thick-run4.log`（run4）；原件为 `/tmp/compat-thick-run3.log`、`/tmp/compat-thick-run4.log`，run4 收尾于 `judged=121 left=2`。
- 校准 journal：`artifacts/compat-thin-calibration2/journal.jsonl`（11 个包：10 个有效 + 1 个引擎侧失败）
- 逐包启动后截图：`artifacts/compat-thick-run3/shots/`（34 张：`CRASH_ON_START` 26 + `PROCESS_GONE` 4 + `NO_FOREGROUND_SCREEN` 4）
- 清库前的过程证据（非正式数据集）：`artifacts/compat-thick-run2/journal.jsonl`

