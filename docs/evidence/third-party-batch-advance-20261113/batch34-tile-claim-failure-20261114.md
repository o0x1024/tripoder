# 第 34 批：tile 认领的两次失效 —— 「按文案猜包名」这条路不成立

日期：2026-11-14 04:45–05:05
设备：d861a4fa（build-implicit-v99）
目标：验证「需先导入 32 包」那条队列能否走 导入→启动→取证 的自动闭环

## 1. 结论先说

**批量流水线的最后一个未闭环环节是 tile 认领，而它当前用的判据（按文案猜包名）在原理上不成立。**
本轮实测到两种不同的失效模式，两种都导致「测出来的是别人的页面却被记成这个应用的落点」。

| # | 失效模式 | 观测 | 原因 |
|---|---|---|---|
| 1 | 探针被当成新应用 | `bbc.mobile.news.ww` 的 tile 认成 `StorageProbe`，verdict 记成 `HOME_NO_ACCOUNT_UI` | 容器自带的两个探针（`Broadcast contract`/`StorageProbe`）在 labels 里永远没有对应包，所以「未被映射的文案」集合里恒有它们 |
| 2 | 差集为空 | 修掉探针后，两包都变成 `NO_TILE_MAPPING` | 已导入过的包：baseline 已含其 tile；新导入的包：新 tile 不一定出现在「底部三屏」这一假设位置上 |

两种模式的共同点：**文案 → 包名 这条映射不是可观测事实，只是启发式约定**。只要依赖它，就会周期性产出「看起来成功、其实测错对象」的记录。

## 2. 本轮已落地的修复（防脏数据，未解决认领本身）

### 2.1 探针黑名单

```python
PROBE_LABELS = {"Broadcast contract", "StorageProbe"}
# is_tile_label(): ... or t in PROBE_LABELS -> False
```

### 2.2 认领改「导入前后差集」而非「末尾约定」

```python
baseline = set(tile_texts_bottom()) if not tile else set()   # 导入前
...
fresh = [t for t in tiles if t not in mapped and t not in baseline]
```

### 2.3 第二道防线：自动认领必须核对启动出的包

```python
if rec.get("tile_discovered") and "TaskP" in launched:
    rec["pkg_procs"] = guest_procs(pkg)
    if rec["pkg_procs"] == 0:
        rec["verdict"] = "TILE_MISMATCH"      # 测度作废，不再是 HOME_NO_ACCOUNT_UI
```

这条是**最重要的一条**：它把「认领错了」从「静默的假结论」变成「显式的失败码」。即使认领仍然不可靠，也不会再污染矩阵。

### 2.4 清理已产生的脏数据

- `coverage.jsonl` 删掉 1 条（`bbc.mobile.news.ww` / `tile=StorageProbe` / 假 `HOME_NO_ACCOUNT_UI`），剩 35 条。
- `tile-labels.json` 删掉错误映射 1 条，剩 48 条。
- `coverage_matrix.py` 状态码表新增 `TILE_MISMATCH`。

矩阵重生成后回到 `measured=30`（未因脏数据虚增）。

## 3. 正确的修法（下一步，尚未实现）

第一性原理：**tile 认领要的可观测事实是「点它启动的是哪个包」，不是它写什么字。**

```
candidates = 所有未映射文案（差集优先，其次全表）
for c in candidates:
    tap(c) → 等 6s
    if guest_procs(pkg) > 0:        # 按包名数，不依赖文案
        认领成功，break
    else:
        退避：force-stop 宿主，重连
仍未命中 → TILE_NOT_CLAIMABLE
```

代价：最坏要为每个包试 N 个候选。但可以用两条信息收敛：
- 导入前后 `installs` 计数差（确认真的新增了 1 个）
- 候选按「该格是否比其他格更可能」排序（新导入的通常靠后）

这条修法把认领从「猜」变成「验」，与 batch30（先验证测量仪器）和 §2.3（先验证测度对象）是同一个原则的第三次应用。

## 4. 本批的复现与边界

```bash
# 失效复现
python3 android/scripts/coverage_pipeline.py bbc.mobile.news.ww com.anthropic.claude
# → 两条 NO_TILE_MAPPING（修复后）；修复前第一条会写 tile=StorageProbe + HOME_NO_ACCOUNT_UI
```

- 本批**没有**为 BBC / Claude 拿到有效落点，两包仍待测（不计入 measured）。
- 未验证「试认领」方案（§3），也未验证 `TILE_MISMATCH` 分支的真实触发（因为修掉探针后就没再误认）。
- 导入路径本身是通的（`ensure_import` 无报错、installs 有增长），失败点在认领。
