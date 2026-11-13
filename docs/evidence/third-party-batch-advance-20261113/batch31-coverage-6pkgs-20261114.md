# 第 31 批：6 个未测包的一次性覆盖（ChatGPT / Telegram / VLC / 维基百科 / 红果 / 唯品会）

日期：2026-11-14 04:00–04:12
设备：d861a4fa（build-implicit-v99 已装机）
流水线：`python3 android/scripts/coverage_pipeline.py --no-import <pkg...>`（含 batch30 的 dump 保护）

## 1. 结论（6/6 有落点事实，无一例崩溃/栅栏/缺席）

| 包 | tile | 落点（top） | verdict | consent_taps |
|---|---|---|---|---|
| com.openai.chatgpt | ChatGPT | TaskP1$F1M1C0 | `HOME_NO_ACCOUNT_UI` | 0 |
| org.telegram.messenger | Telegram | TaskP1$F1M2C0E0 | `HOME_NO_ACCOUNT_UI` | 0 |
| org.videolan.vlc | VLC | TaskP1$F1M0C3 | `HOME_NO_ACCOUNT_UI` | 4 |
| org.wikipedia | 维基百科 | TaskP1$F1M0C2 | `HOME_NO_ACCOUNT_UI` | 0 |
| com.phoenix.read | 红果免费短剧 | TaskP1$F1M1C2 | `HOME_NO_ACCOUNT_UI` | 1 |
| com.achievo.vipshop | 唯品会 | TaskP1$F1M1C2 | `LOGIN_ENTRY` | 0 |

全部 6 包的 counters 均为 `anr=0 / admission=0 / fatal=0 / absent=0`。

覆盖口径变化（`coverage_matrix.py --write` 输出）：
`third_party=78, measured=26, concluded=31, pending=52, records=27, runnable=20, needs_import=32`。
上一轮为 `measured=20 / pending=58`，本轮 +6。

## 2. 本批的两处方法学收获

### 2.1 nohup 起批次会随轮次作业一起死

首轮把 6 包写成 `nohup ... &` 丢到后台跨轮等待，结果只落了 ChatGPT 与 Telegram 两条记录就无声停止：
`/tmp/b31.log` 仅 2 行、`pgrep -f coverage_pipeline` = 0、无 stderr。原因是该批次挂在被取消的 shell 作业的进程组上。

改用 shell 的 durable job（`run_in_background=true` 拿 `background_task_id`，同一轮里 `poll` 到退出）后，剩余 4 包一次跑完，退出码 0。

**结论**：批量推进必须在同一轮内 poll 到收敛，不能靠「丢后台、下一轮再看」。

### 2.2 `HOME_NO_ACCOUNT_UI` 不是一个「没事」的结论，要按应用族读

ChatGPT / Telegram / VLC / 维基百科 的首页确实没有账号 UI（VLC 需先过 4 次同意点击、红果 1 次）。
但这些页面的「无账号 UI」有两种成因，本轮无法只靠 dump 区分：
- 真·免登录应用（VLC、维基百科）；
- 首启登录入口藏在下一级（ChatGPT 的侧栏、Telegram 的 Settings）。

后续若要为这些包补登录入口，须单独走「点开抽屉/侧栏再 dump」的导航，而不是复用本批的首页判定。

## 3. 复现入口

```bash
python3 android/scripts/coverage_pipeline.py --no-import \
  com.openai.chatgpt org.telegram.messenger org.videolan.vlc \
  org.wikipedia com.phoenix.read com.achievo.vipshop
python3 android/scripts/coverage_matrix.py --write
```

## 4. 边界

- 本批全部走 `--no-import`（6 包在 Workbench 里已有 tile），因此不含导入路径的验证。
- `consent_taps>0` 只说明点到了同意类控件，不代表同意层已彻底通过；VLC 的 4 次点击需后续单独复核。
