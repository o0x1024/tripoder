# 第 22 批：把推进面从「Workbench 里的 24 个」扩到「手机上所有非系统第三方应用」

- 日期：2026-11-13 23:30–（进行中）
- 设备：d861a4fa（已解锁）
- 版本：build-nested-v92（未改代码，本轮只做覆盖面）
- 关联：`inventory.md`（121 个 `-3` 包全量盘点）、`batch21-v91-wide-regression-20261113.md`

## 1. 为什么覆盖面是真正的缺口

batch21 的结论是「6 个已知应用在 v92 上无回归」。但目标是对**所有**非系统第三方应用推进，而这一句话有两个独立的量：

1. **广度**：手机上有多少个包需要推进；
2. **深度**：每个包推进到哪一步才算「推进过」。

先算清第一个量。以 `pm list packages -3` 为准（121 个包），按 `inventory.md` 的「厂商/系统捆绑」列剔除厂家预装（小米生态链/输入法/画报等 40 个），剩 **81 个** 非捆绑第三方包。但 Workbench 首页实际只有 **30 个格子**（28 个应用 + Broadcast contract / StorageProbe 两个探针），`logcat` 的 `CATALOG_READY installs=35` 是安装条目数而非应用数。

⇒ **「Workbench 里有」≠「手机上有」**：24 个应用已推进过，其余 50+ 个从未进入容器。这就是验收意见里「未达到所有范围」的准确含义。

## 2. 结论先行：广度可以脚本化，深度不能

第一性推导：

- 广度是**重复劳动**，每一包的动作完全同构（force-stop → Workbench → 重连 → 添加应用 → 按包名搜索 → 全选 → 提交 → 回列表 → 找到新格子 → 点 → 取证）。同构动作的正确解法是脚本 + 幂等键（包名），不是人工逐个。
- 深度不是同构的：每个应用的落点不同（隐私同意层 / 登录页 / 首页 feed / 崩溃），判定条件也不同。深度只能靠应用侧的 dump/截图逐项判读。

所以本轮把**广度做成流水线**，深度仍然逐应用判读，并且把两者的结果分开落盘，避免「跑过 50 个包」被误读成「50 个都可用」。

## 3. 流水线：`/tmp/batch22.py`

每包一次完整闭环，幂等键 = 包名：

```
force-stop q6 → force-stop q7 → am start Workbench
  ↓ 重连直到列表真的出来（「运行引擎没有连接」消失 且 出现「N 个应用」）
基线：把列表滚 3 屏，记下所有可见 tile 文本
  ↓ 点「添加应用」→ 点搜索框 → input text <包名> → ESC 收键盘
点「全选」→ 读动态文案「添加所选 N 个应用」→ 点它 → 等 16s
  ↓ BACK 回列表 → 重新连接（让 catalog 重建）
diff：滚动列表，找 baseline 之外的新 tile —— 它就是刚导入这个包的格子
  ↓ 点它 → 40s 内每 4s 读 topResumedActivity
取证：top / ANR / admission-blocked / FATAL / TripoderStarts ABSENT + screencap
  ↓ 追加一行 JSON 到 /tmp/batch22.jsonl
```

三个关键设计点（都是踩出来的）：

1. **用包名搜索而不是中文名**：`输入 com.openai.chatgpt` 精确命中 1 个条目，不依赖 label。这解决了「不知道某包在中文列表里叫什么」的问题，也是流水线能泛化的前提。
2. **用 diff 找格子而不是猜坐标**：导入后新格子出现在列表尾部，但列表顺序与滚动位置每轮都漂（见 gotcha `workbench-coords-drift-per-round`）。对 baseline 做集合差，就能在不认识 label 的前提下点到正确的格子。
3. **重连的退出条件必须是「列表真的在」**：最初写成「点 3 次『重新连接』就返回」，结果引擎停在 `SUPERVISOR_INTERRUPTED` 时脚本照样往下走，于是「添加应用」入口不存在，全都报 `ADD_SCREEN_NOT_FOUND`。改成断言「『运行引擎没有连接』消失 且 出现『N 个应用』」后一次通过。

## 4. 已完成的实测（v92，无代码改动）

| 包 | 导入 | tile | top | anr | adm | fatal | absent |
|---|---|---|---|---|---|---|---|
| `cmb.pb` 招商银行 | OK（installs 31→32） | 招商银行 | TaskP0$F0M1C2 | 0 | 0 | 0 | 0 |
| `com.quark.browser` 夸克 | OK（→33） | 夸克 | TaskP0$F0M2C2 | 0 | 0 | 0 | 0 |
| `com.qiyi.video` 爱奇艺 | OK（→34） | 爱奇艺 | TaskP0$F0M1C0E0 | 0 | 0 | 0 | 0 |
| `com.xs.fm` 番茄畅听 | OK（→35） | 番茄畅听 | 待测 | — | — | — | — |

结论：**三个新应用全部落到客体 Activity**，`fatal/admission-blocked/ABSENT` 全 0。这是「覆盖面扩张不会引入新的运行时缺陷」的第一个直接读数（此前所有宽回归都只跑已知应用，等于一直在同温层里自证）。

## 5. 一个必须记住的取证边界

`uiautomator dump` 对这批页面并不可靠：招商银行、爱奇艺这类应用 dump 会打印「UI hierchary dumped to /sdcard/g.xml」但文件里没有 `Bounds`（空树）。此时**不要**在 dump 上重试，直接 `screencap` + 看图判定落点。已写入 gotcha `guest-dump-may-be-empty-use-shot`。

与此相对，**Workbench 自己的页面 dump 一直可靠**，所以流水线的控制面（找入口、找格子）可以完全靠 dump，只有客体内容面需要退到截图。

## 6. 边界

- 本轮只判「落点 + 无崩溃」，**不代表**该应用的登录/业务链路可用；深度结论仍在各应用自己的证据文档里，且仍以「BOSS直聘式」的逐项推进为准。
- 流水线的失败是**包名级可重跑**的：任一行 `verdict != LAUNCHED` 都保留原始 JSON（含 import 阶段的错误码），可单独重跑该包，不需要重跑整批。
- 剩余 50 个非捆绑包队列已生成在 `/tmp/queue.txt`；下一批起按 5–6 个一段推进，每段结束把结果并入本文件的覆盖矩阵。
