# batch35：tile 认领改为「试认领」，以及把推进面从 24 个扩到全部 118 个的导入路径

日期：2026-11-16｜设备：d861a4fa｜构建：build-implicit-v99

本轮做了两件事，都针对同一个问题：**「按 Boss 直聘那样推进到手机里所有第三方应用」缺的不是意愿，而是两条判据缺失**——怎么可靠地知道「这一格是哪个应用」，以及怎么把 118 个应用真正放进容器空间。

---

## 1. 现场先坏了一次：宿主被卸载、空间被清空

本轮开始时的一切异常都源于同一件事：

| 读数 | 值 | 含义 |
|---|---|---|
| `pm list packages \| grep tripoder` | 只有 `dev.tripoder.controltest` | **`dev.tripoder.q6`（宿主/Workbench）不在设备上** |
| `am start -n dev.tripoder.q6/...WorkbenchActivity` | `Error type 3 / does not exist` | 不是崩溃，是没装 |
| `mCurrentFocus` | `null` | 因为顶层是原生微信，容器不存在 |
| Workbench 界面（重装后） | 「还没有应用 / 点下方『添加应用』」 | 空间为空：此前导入的 50 个应用随卸载一起消失 |

症状是掩盖性的：`coverage_pipeline.py` 的 `reconnect()` 连续失败但**不报错**，脚本静默空转（`/tmp/b35.log` 0 字节、进程活着、top 停在原生应用）。所以批量流水线的第一道自检必须是「宿住在不在」，而不是「引擎连没连」。

**恢复配方（可复用）**：`adb install --no-incremental -r android/product/build-implicit-v99/tripoder-q6.apk` 会被 MIUI 的 `AdbInstallActivity` 拦成 `INSTALL_FAILED_USER_RESTRICTED`，必须在 install 等待期间 dump 找「继续安装」并点掉（脚本：`/tmp/inst_q6.sh`，实测 `dialog_seen_at=1` → `Success`）。

## 2. 「试认领」：把认领从猜文案换成验对象

batch34 已实测文案启发式的两种失效（探针 tile 被当成新导入的应用；已导入的包其 tile 一直在 baseline 里所以差集恒空）。本轮结论是**这两种失效不是可修补的 bug，而是判据不成立**：tile 文案与应用包名之间不存在可推断的映射。

`android/scripts/coverage_pipeline.py` 因此改为：

- `_unmapped_candidates()`：文案只用于**排序**（差集优先 → 底部未映射 → 全表兜底），不再用于定罪；
- `_probe_tile()`：逐个候选**点它**，只有「起来的是这个包」才认领——判据是 `guest_procs(pkg) > 0` 或 `logcat` 里出现该包名；
- `_launch_tile()` / `_find_tile()`：把「reconnect → 回顶 → 逐屏找 → 点 → 等落点」抽成可复用件，试认领成功后直接复用这次落点，不二次启动；
- 新失败码 `TILE_NOT_CLAIMABLE`（候选全不中）与 `TILE_MISMATCH`（起来了但不是该包）分开记，避免把「认错对象」写成假成功。

为什么要「进程 + 日志」双判据：客体进程名有两种形状——应用包名本身（`com.dianping.v1`）与槽位名（`dev.tripoder.q6:slotN`）。只按包名数进程，会把「进程叫 slot0 但确实是这个应用」误判成没起来。

## 3. 规模化导入：一次提交 118 个不可行，但「子串分批 + 全选」可行

实测（决定性）：

| 读数 | 值 |
|---|---|
| 添加应用页（空搜索） | `已选 118 个` / `添加所选 118 个应用` |
| 该页文案 | **`一次可添加 1–64 个应用`** |
| 源码判据 | `WorkbenchImportBatch:41` 对 `sources.size()>64` 直接抛 `IllegalArgumentException("一次可添加 1–64 个应用")` |
| 点应用格子 | `已选 118 → 118`，**不切换选中**（与 batch22 记录一致；`WorkbenchAddActivity:69` 的 tile 回调本身是对的，但真机点击不生效） |

于是选定唯一的可靠选法：**搜索子串把可见集压到 ≤64，再「全选」**。依据 `WorkbenchAddActivity` 的两条事实：

1. `visible` = 未安装 且（label 或 pkg 含搜索串），「全选」选的就是 `visible`；
2. `if(!batch.active()) selected.removeIf(this::installed)` —— **已安装的会自动从 visible 消失**。

第 2 条使整个流程幂等：重复跑只会推进剩余部分，不需要记住「上次导到哪」。

首批实测：`filter=com.m n=19` → `添加所选 19 个应用` → 40 秒后 `batch done (banner)`，`CATALOG_READY installs=19`。即每包约 2 秒，全部 118 个的安装时间在 5 分钟量级；瓶颈不是安装，而是导航。

导航坑（两次踩到，都值得记）：导入批次结束后**导入页会自己退出**，且「添加应用」格子位于列表最底部、常在屏外——所以 `open_add()` 必须按层识别（导入页 / 列表页 / 引擎断开），并**先向上滚动再找「添加应用」**，否则表现为 `ADD_SCREEN_NOT_FOUND`。

## 4. 边界与未完成

- 本轮**未**在 118 个应用上跑完导入（首批 19 个已验证，其余由 `/tmp/import_batches3.sh` 续跑，幂等可重入）。
- 「试认领」代码已落地并通过编译，但**尚未在真机上验证生效**——按本项目口径，未实测的修改只算「已实现」，不算「已通过」。
- `coverage.jsonl` 里 measured=30 的记录是上一次提交的历史事实，其容器空间已不存在；重跑任何包都必须先重新导入，不能把旧记录当作当前现场。

## 5. 可复现入口

```bash
# 恢复宿主（MIUI 需点「继续安装」）
bash /tmp/inst_q6.sh

# 算分批计划（<=64/批，子串覆盖）
python3 /tmp/plan_batches.py

# 分批导入（幂等，可中断续跑）
bash /tmp/import_batches3.sh
```
