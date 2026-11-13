# batch23–25：把「逐个手点」换成幂等覆盖流水线

本文件记录把「按 BOSS直聘 这样推进」从人工点击升级为可复跑流水线的过程与实测。

## 1. 为什么必须换掉人工点击

目标口径是「当前手机里**所有**非系统自带的第三方应用」。`pm list packages -3` 全量 121 个，
扣掉厂商/系统捆绑与小 7 个容器自身包后仍有 **78** 个第三方包（见 `coverage-matrix.md` 口径行）。
人工每个包 20–40 次 dump/点击、且坐标每轮都漂（`workbench-coords-drift-per-round`），
结果是「每轮只能推进 3–4 个且不可复跑」。规模化的瓶颈不是应用兼容性，而是编排的可复现性。

## 2. 流水线（`android/scripts/coverage_pipeline.py`）

每条记录 = 一个包的一次完整推进，写进 `coverage.jsonl`（追加式，可按包名单包重跑）：

| 阶段 | 做法 | 依赖的既有结论 |
|---|---|---|
| 导入 | 调 `android/scripts/import_apps.sh`（清空选择 / 已在此空间 / 只提交 1 个 / wait_list） | `import-apps-script-usage`、`import-result-rows-lazy` |
| tile 认领 | 先 `reconnect()` 回列表页，再翻到底部三屏扫文案，取「尚未映射的那个 tile」写回 `tile-labels.json` | `tile-label-not-app-name`；新导入的排在列表末尾 |
| 启动 | 12 屏回顶 → 精确匹配 tile → tap → 40s 轮询 `topResumedActivity` | `launch-tile-top-reset-12`、`tile-tap-may-miss-once` |
| 同意层 | 候选顺序 `同意并继续/同意/接受/我知道了/全部同意/跳过/开始使用/进入应用`，最多 4 轮 | `consent-tick-only-select-all`（CheckBox 类需单点全选，本流水线只按文案点，勾选类不覆盖） |
| 取证 | 截图 + dump 结构（`nodes/edit/webview/texts/entries`）+ dump 前后数客体进程 | `uiautomator-kills-guest-via-clipboard`：dump 有 EditText 的页会杀客体，所以要对比前后进程数 |
| 判定 | `classify()`：入口 > 同意层 > 空树 > 登录提示 > 无账号 UI | 见 §4 的两处误判 |

`--no-import` 只跑启动+取证（已导入的包用）；`--report` 打印各判定计数。

汇总矩阵由 `android/scripts/coverage_matrix.py --write` 从 `inventory.md + coverage.jsonl + tile-labels.json`
生成 `coverage-matrix.md`，脚本自身不写结论，避免「覆盖了多少」随人工编辑漂移。

## 3. 实测记录

### 3.1 首个包（腾讯视频 `com.tencent.qqlive`，含导入）

```
import: 清空后已选 0 → 已选 1 → 添加所选 1 个应用 -> installs 55
tile 自动认领: 腾讯视频
launch: TaskP1$F1M1C2
consent_taps: 1
page: nodes=88 edit=0 webview=0 guest_procs=[0,0]（未跑 dump 前已无 slot 进程，见 §4 第 2 条）
entries: 微信登录 / QQ登录 / 其他登录方式 / 立即登录 / 未登录
counters: anr=0 admission=0 fatal=0 absent=0 job_err=0
verdict: LOGIN_ENTRY
```

腾讯视频的登录入口是四条并列：`微信登录`、`QQ登录`、`其他登录方式`、底栏 `未登录`。

### 3.2 批量（`--no-import`，已导入但从未实测的包）

每包耗时约 1.5–2 分钟（重连 25s + 回顶 6s + 找 tile 若干屏 + 启动 40s + 同意层与取证）。
原始记录逐行在 `coverage.jsonl`，汇总在 `coverage-matrix.md`。首轮 8 个包的落点：

| 包 | tile | 落点 top | 判定 | 说明 |
|---|---|---|---|---|
| `com.tencent.qqlive` | 腾讯视频 | `TaskP1$F1M1C2` | LOGIN_ENTRY | 微信登录 / QQ登录 / 其他登录方式 / 未登录 |
| `com.baidu.netdisk` | 百度网盘 | `TaskP1$F1M0C2` | LOGIN_ENTRY | 手机号 + 验证码登录 + 其他登录方式 |
| `com.autonavi.minimap` | 高德地图 | `TaskP1$F1M2C0E0` | LOGIN_ENTRY | 无同意层，底栏「未登录」 |
| `com.cmb.pb` | 招商银行 | `TaskP1$F1M1C2` | HOME_NO_ACCOUNT_UI | 两道同意后落首页 |
| `com.quark.browser` | 夸克 | `TaskP1$F1M2C2` | HOME_NO_ACCOUNT_UI | 同意 1 次后落首页 |
| `com.duolingo` | 多邻国 | `TaskP1$F1M1C0E0` | HOME_NO_ACCOUNT_UI | 无同意层，直接引导页 |
| `com.qiyi.video` | 爱奇艺 | — | NOT_TOP | 见 §3.3：UI 拒绘，不是崩溃 |
| `com.baidu.searchbox` | 百度 | `TaskP1$F1M2C1` | 待归属 | 同轮 fatal=1 且 top 回落 Workbench |

除爱奇艺与百度外，其余 6 包的 `anr/admission/fatal/absent/job_err` 全部为 0。

### 3.3 爱奇艺「点了没反应」的定性：UI 拒绘，不是崩溃也不是不兼容

首轮爱奇艺返回 `NOT_TOP`。单包手工复现（点 tile 后每 5s 读一次 top 与客体进程数，共 5 次）：

```
t0..t4  top = dev.tripoder.q6/dev.tripoder.hooklab.WorkbenchActivity   guest_procs = 0
crash = {}   counters = {anr:0, admission:0, fatal:0, absent:0, job_err:0}
Workbench 文本：应用没有启动 / 操作没有完成（APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED）/ 知道了
```

也就是说：引擎根本没起客体进程，Workbench 是按「该包还挂着一条未确认的 Activity 记录」拒绝绘制。
这与容量墙（`PROCESS_CAPACITY_EXHAUSTED`）、准入栅栏（`有一个更早的操作没有确认结果`）都不同，
是第三种拒绝形态，恢复配方是 `force-stop q6 + q7` 后重连再点。流水线的 `NOT_TOP` 分支已内置该恢复。

## 4. 两处判定误判（都是口径错，不是应用行为差异）

1. **同意文案与首页正文撞词**：腾讯视频首页正文自带「同意《用户服务协议》及《基本功能隐私保护指引》」，
   旧 `classify()` 把「出现『同意』」当同意墙，于是已到首页的应用被记成 `CONSENT_UNRESOLVED`。
   改为「先看有没有登录入口（entries 非空 → LOGIN_ENTRY），再看是否命中同意墙专用文案
   （同意并继续/全部同意/接受并继续/我已阅读并同意/不同意）」。
2. **导入页按钮被当成 tile**：`import_apps.sh` 结束时停在「添加应用」页，那里的
   「选择要添加的应用」被当 tile 认领，首轮 qqlive 映射被写错。改为认领前先 `reconnect()` 回列表页，
   并把这类文案放进 `CHROME_LABELS` 黑名单。

两条都写进 `gotchas`，避免后续批次重犯。

## 5. 边界

- 流水线只推进到「应用可用 + 登录入口可达」，**不做登录闭环**：登录闭环需要验证码/密码，
  按既有口径（读机内短信可取码，人机图形码与密码交回用户）单独批处理。
- CheckBox 型同意墙（如 Booking 的 5 条勾选）不能用文案点击通过，本流水线对它只能记
  `CONSENT_UNRESOLVED`；Booking 的勾选配方在 `batch24` 证据里（只点「全选」行右端）。
- `PAGE_EMPTY_USE_SHOT`（dump 空树）必须靠截图判读，不要重试 dump：招商银行/爱奇艺这类页面
  会打印 dumped 但文件里没有 bounds。
