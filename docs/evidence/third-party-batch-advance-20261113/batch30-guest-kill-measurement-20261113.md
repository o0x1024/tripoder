# batch30：`GUEST_KILLED_BY_DUMP` 是测量口径缺陷，不是 dump 杀了客体

时间：2026-11-14 03:55–04:10（设备 d861a4fa，build-implicit-v99）
触发：batch29 的 6 包里有两条记录判成 `GUEST_KILLED_BY_DUMP`（支付宝、美团），矩阵里这两包因此
既不算"已测"也拿不到落点结论 —— 相当于自我制造的假阴性。

## 1. 现场读数

同一台设备、同一 build，把美团从 tile 冷启后单步复现（/tmp/b30a.py）：

```
tile com.sankuai.meituan 美团
reconnect True
hit True
top dev.tripoder.q6/dev.tripoder.runtime.generated.TaskP1$F1M1C2  procs 0
DUMP before=0 after=0 nodes=68 xml=59142
--- crash buffer ---            (空)
top_after dev.tripoder.q6/...TaskP1$F1M1C2
```

三件事同时成立：

1. **dump 是成功的**：68 个节点、59 KB XML，页面文本可读；
2. **进程没有消失**：`ps -A` 里当时有 `com.sankuai.meituan`、`com.sankuai.meituan:PinProcess`、
   `com.sankuai.meituan:dppushservice` 三个进程；
3. **`guest_procs()` 数出来是 0**：它只数 `dev.tripoder.q6:slot` 这一个模式。

## 2. 根因

容器里客体进程名有两种形状：应用包名本身（美团三进程如上）与槽位名 `dev.tripoder.q6:slotN`
（QQ/微博等）。旧判据 `dump_killed_guest = before>0 and after==0` 数的是后者，于是：

* `before` 命中的是**上一包批量残留**的 slot 进程（batch29 是连续 6 包，前几包留下的 slot 还没被
  下一轮 `reconnect()` 的 force-stop 收干净）；
* `after` 为 0 是那些残留 slot 被 reap 的结果，与本包的 dump 无关。

两条旧记录的自证：支付宝 `guest_procs=[4,0]` 而 `top` 仍是 `TaskP1$F1M2C2`、`nodes=9` 且文本是
「淘宝用户快速登录 / 手机号 / 身份证号 / 邮箱 / 儿童手表 / 注册账号」；美团 `guest_procs=[1,0]`
而 `nodes=57`、文本是「清控湖山雅居 / 衡湘君高压锅盖码饭 / 搜索 / 老友回归」。**页面既然抓下来了，
客体就没死**。

## 3. 修法（android/scripts/coverage_pipeline.py）

| # | 改动 | 判据 |
|---|---|---|
| 1 | `guest_procs(pkg=None)` 支持按包名数 | 给了 pkg 就数 `pkg in ps 行`，否则退回数 `:slot` |
| 2 | `snapshot(label, pkg)` 用包名数 | 前后两次读数都按被测包名 |
| 3 | 「被杀」改成双条件 `dump_unavailable` | `(not x) and before>0 and after==0`：**结构没抓到** 且 **进程确实没了** |
| 4 | verdict 改名 `DUMP_UNAVAILABLE` | 旧码 `GUEST_KILLED_BY_DUMP` 在矩阵里标注「已作废」 |
| 5 | 矩阵词表补两行 | `coverage_matrix.py` 的 `VERDICT_CN` |

保留的旧结论：dump 在**含 EditText 的页面**确实会经无障碍预取 → `TextView.canPaste` →
`ClipboardManager.hasPrimaryClip` → AppOps 包名/uid 校验杀死客体（微博 `fl_login_root`、拼多多手机号页
两处实测）。所以修法不是"以后都 dump"，而是"**只有抓不到结构时才算被杀**"，EditText 页仍应
按截图判读。

## 4. 复测

`python3 android/scripts/coverage_pipeline.py --no-import com.eg.android.AlipayGphone com.sankuai.meituan`

| 包 | 旧记录（batch29） | 复测（batch30） | 变化 |
|---|---|---|---|
| com.eg.android.AlipayGphone | `GUEST_KILLED_BY_DUMP`（procs[4,0]，nodes=9） | **LOGIN_ENTRY**（TaskP1$F1M2C2，登录页：淘宝用户快速登录/手机号/身份证号/邮箱/儿童手表/注册账号） | 假阴性 → 真结论 |
| com.sankuai.meituan | `GUEST_KILLED_BY_DUMP`（procs[1,0]，nodes=57） | **HOME_NO_ACCOUNT_UI**（TaskP1$F1M1C2，首页可达：搜索/老友回归/新人 1 元喝奶茶） | 假阴性 → 真结论 |

## 5. 方法论沉淀

* 判"某进程是否死于某种观测手段"时，**仪器本身要先被验证**：本次的仪器是 `ps | grep :slot`，
  它在多进程应用上天然漏主进程；
* 落点事实（`top` 是否 `TaskP*`、页面是否抓到了文本）比进程计数更硬 —— 前两者与"页面真的在前面"
  直接同义，进程计数只是代理指标；
* 同类坑的通用形式：**"数字变了" 不等于 "因果成立"**，尤其在批量流水线里，上一包的状态会泄漏到
  下一包的读数。

## 6. 边界

* 本轮只修正了测量与判读口径，没有改动容器运行时；v99 的构建与已推送证据（9a4d079b / d4b4d1ce）
  不受影响；
* 矩阵里 `GUEST_KILLED_BY_DUMP` 两条旧记录保留在 `coverage.jsonl` 里（追加式，不删原始测量），
  由复测记录覆盖同一包的判定。
