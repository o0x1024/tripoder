# 第 8 批（续）：已导入未测 8 个应用的启动与登录入口实测（v70）

设备解锁后（`isKeyguardShowing=false`、`FocusedWindows=客体窗口`）对容器 catalog 里**已导入但从未启动过**的 8 个应用做单应用冷启动取证。结论：**8/8 启动成功、crash 缓冲 0 条 FATAL**；除支付宝外全部停在各自的隐私/协议页，「登录入口」被隐私门槛挡在下一步。

## 1. 方法与可复现入口

脚本 `/tmp/batch_b8_8apps.sh`，每个应用独立一轮：

```
force-stop dev.tripoder.q6 + q7          # 清槽位（否则上一轮 ● 运行中 占满 8 个槽位）
logcat -b crash -c                       # 清崩溃缓冲（不是 `am logcat`，后者不存在）
am start WorkbenchActivity → 点「重新连接」×2
tap <应用中文名> --scroll                 # 按 Workbench 列表精确文本定位
等 34s → 抓 topResumedActivity / ps / crash 缓冲 / uiautomator dump
```

结果原始输出：`/tmp/b8_result.txt`（9 段，末段 `DONE`）。

## 2. 结果

| # | 应用 | 包名 | top（容器 Activity） | 进程 | FATAL | 落点 |
|---|---|---|---|---|---|---|
| 1 | 唯品会 | `com.achievo.vipshop` | `TaskP3$F0M1C2` | 1 | 无 | 「个人信息保护指引」（同意 / 不同意） |
| 2 | 支付宝 | `com.eg.android.AlipayGphone` | `TaskP3$F0M2C2` | 1 | 无 | **直接到登录页**：`login_guide_page_container`「淘宝用户快速登录 / 手机号 / 身份证号 / 邮箱 / 儿童手表 / 注册账号 / 遇到问题」 |
| 3 | 滴滴 | `com.sdu.didi.psnger` | `TaskP3$F0M0C0` | 1 | 无 | 「协议与规则」：地区选择（中国内地 / 中國香港）、同意并开始使用 / 不同意 |
| 4 | 今日头条 | `com.ss.android.article.news` | `TaskP3$F0M0C0R1` | 1 | 无 | 「个人信息保护指引」（同意 / 不同意） |
| 5 | 闲鱼 | `com.taobao.idlefish` | `TaskP3$F0M2C0` | 1 | 无 | 「温馨提示」隐私权政策更新（同意 / 不同意） |
| 6 | 淘宝 | `com.taobao.taobao` | `TaskP3$F0M1C0` | 1 | 无 | 「温馨提示」（同意 / 不同意） |
| 7 | 携程旅行 | `ctrip.android.view` | `TaskP3$F0M0C2` | 1 | 无 | 「个人信息保护指引」：感谢您使用携程旅行（同意并继续 / 不同意） |
| 8 | 哔哩哔哩 | `tv.danmaku.bili` | `TaskP3$F0M1C2` | 1 | 无 | 「用户协议与隐私政策提示」（同意并继续 / 不同意） |

任务号连续递增（t16498 → t16512，步长 2），说明每轮都拿到了干净的槽位与新任务，没有复用上一应用的残留会话。

## 3. 这一批说明了什么

### 3.1 容器承载力对这批应用不构成门槛

八个应用、八轮「清槽位 + 冷启动 + dump」，全部在 34s 内到达各自首屏交互页。没有出现 `PROCESS_CAPACITY_EXHAUSTED`、`PROCESS_START_TIMEOUT_UNKNOWN`、`APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED` 任何一种已知容器侧失败码，也没有出现 `uiautomator dump` 杀客体（本批页面都无 `EditText`，与 `clipboard-crash-only-with-edittext` 的边界一致）。

### 3.2 来源/风控族不是普遍判据（与 h16/h17 同向）

这 8 个应用与抖音、京东拿到的是**同一套来源回答**：`installer=null`、客体以宿主 uid 10542 运行而自称各自包名。抖音在这个事实下拒绝登录，这 8 个应用在此事实下连拒绝的迹象都没有——没有「非官方渠道」类弹窗、没有服务端风控文案、没有门面崩溃。这再次确认 `p2-unfixable-conclusion` 只对**抖音**成立，不能外推（`jd-not-provenance-gated` 的同类证据再 +8）。

### 3.3 登录入口普遍被隐私门槛挡在前面，不是被容器挡住

7/8 的首屏是隐私/协议同意页。这意味着「登录入口实测」必须先穿过一次同意动作——这是应用自身的合规流程，与容器无关。支付宝是例外：它**不起隐私页**，直接渲染 `login_guide_page_container`，登录入口在首屏即可达。

## 4. 边界（不要过度解读）

- 本文件只主张「启动成功 + 首屏可达 + crash 缓冲无 FATAL」。**没有**主张这些应用登录可用：7 个连隐私页都还没穿过去。
- crash 缓冲只抓 `FATAL / Process: / Caused by` 三类行；非致命异常与门面降级日志（`TripoderFacade`）本批未采集，不能据此说「零门面降级」。
- 「进程 1 个」是 34s 时点的观测值。淘宝/支付宝/今日头条这类多进程应用在更晚阶段会拉起子进程（京东实测 5 个、小红书 4 个），本批没有等到那个阶段，所以不能当「单进程应用」的证据。
- 唯品会的同意按钮在 dump 里被截断成 `同`，真实文案需在第二层用候选匹配确认（见 `/tmp/batch_b8_agree.sh`）。
