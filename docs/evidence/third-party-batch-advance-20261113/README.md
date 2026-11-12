# 全量第三方应用推进 · 第 1 批（`build-facade-v57`，设备 d861a4fa）

对象：`pm list packages -3` 的 **121 个包**（全量清单见 [inventory.md](inventory.md)）。方法沿用 BOSS直聘那一轮：
**先跑真实路径 → 出问题就分层归因（我方承载力缺陷 / 对方本地风控 / 对方服务端判定）→ 只修我方那一层并回归。**

## 1. 先解决「质」的问题：批量的前置条件

推进「所有第三方应用」不是 121 次手工启动，先要满足两条：

1. **装得进去**：容器导入一次最多批量选，实际测试中一次性导入 7 个应用成功（容器 9 → 16 个应用）。
2. **跑得起来到第 N 个**：这是本轮暴露的真问题——见 §3。

## 2. 第 1 批实测（7 个新装入 + 已有 4 个）

| 应用 | 包名 | 结果 | 判定层 |
|---|---|---|---|
| BOSS直聘 | `com.hpbr.bosszhipin` | 一键登录 → 完善资料页（前一轮已闭环） | 可用 |
| 京东 | `com.jingdong.app.mall` | 一键登录 → 个人中心（前一轮已闭环） | 可用 |
| 大众点评 | `com.dianping.v1` | 登录页可达，运营商号码认证页（`153****8553`，天翼账号）渲染正常 | 可用（未点登录） |
| 微信 | `com.tencent.mm` | 启动，进程存活，首屏 | 可用（未登录） |
| QQ | `com.tencent.mobileqq` | 启动到《服务协议/隐私政策》页 | 可用（未登录） |
| 美团 | `com.sankuai.meituan` | 启动到「温馨提示」隐私同意页 | 可用（未登录） |
| 微博 | `com.sina.weibo` | **首次被运行槽位上限挡住**，见 §3 | 修后待测 |
| 快手 / 高德地图 | `com.smile.gifmaker` / `com.autonavi.minimap` | 已装入容器，未启动 | 未测 |
| 抖音 | `com.ss.android.ugc.aweme` | 服务端按运行环境判定，入口已加提示（前一轮结论） | 不可用 |

**结论 1（复用上一轮的判据）**：京东/大众点评与抖音拿到的是**同一套来源回答**（`installer=null`、客体跑宿主 uid），
但抖音拒绝、京东与大众点评正常。⇒ 「容器来源不可用」不是普遍结论，**必须逐应用实测**，不能外推。

## 3. 批量推进的真瓶颈：运行槽位是硬预算

### 现象

装到第 7 个应用、启动第 6 个时：

```
应用没有启动
操作没有完成（PROCESS_CAPACITY_EXHAUSTED）
```

### 定位

`android/runtime/src/dev/tripoder/runtime/ProcessSupervisor.java:152`

```java
if(index<0)throw new IllegalStateException("PROCESS_CAPACITY_EXHAUSTED:requiredSlot="+(retained==null?"any":retained));
```

槽位来自 `RuntimeSlot.CAPACITY = 8`（`android/runtime/src/dev/tripoder/runtime/RuntimeSlot.java:16`）。
**这是进程池，不是应用池**：一个应用声明几个进程就吃几个槽位（QQ、美团、京东都是多进程）。所以 4–5 个应用就能吃满 8 个槽位。

### 为什么必须修

- 对「推进所有第三方应用」这个目标，这是**每一次批量操作都会撞到**的墙，不是个别应用的兼容问题。
- 用户侧看到的是内部错误码 + 无出路，和 BOSS直聘那次「传出去平台不会传的异常」属于同一类缺陷：
  **运行时把自己的预算/策略当成用户的死路。**

### 修法（`build-facade-v57`，只动 UI 层，不动槽位算法）

`android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java`

- 启动失败若 message 含 `PROCESS_CAPACITY_EXHAUSTED`，不再直接弹内部码；
- 弹出「运行槽位已满」，列出**当前正在运行的其它应用**，由用户选一个停止，随后自动重试启动目标应用；
- 文案明确「停止不会删除任何数据」；没有可停止的应用时才提示稍后再试；
- **不做静默驱逐**：槽位是真预算，牺牲谁必须由用户决定（平台在低内存时会静默杀后台，但那是平台权力，容器替用户做这个决定会丢未保存状态）。

## 4. 边界

- 本轮只覆盖 **7 个新应用 + 4 个已有应用**；121 个里其余 110 个仍是「未测」，`inventory.md` 中一律标 `未测`，不写成「不可用」。
- 微信/QQ/美团只到「启动 + 隐私页」，**没有登录**；能否登录是下一个未知量，不能由「进程活着」推出。
- 槽位修复只解决「第 6 个应用起启动失败」，不解决「多进程应用吃多份槽位」这一结构成本；如果后续批测发现常用组合超 8，
  那是另一个决定（提高 CAPACITY 需要同步生成更多 activity slot 桩，属于 manifest 与构建改动）。
- `PROCESS_CAPACITY_EXHAUSTED` 之外的容量错误（`ACTIVITY_*_CAPACITY_EXHAUSTED`、`SERVICE_*`、`ISOLATED_PROCESS_*`）
  本轮未一并做 UX 兜底。

## 5. 复现

```bash
# 1) 批量装入：Workbench → 添加应用 → 手机应用 → 多选 → 添加所选
# 2) 逐个冒烟：Workbench → 点应用 → 等 12s → dumpsys activity activities（topResumedActivity）
#    并同时抓 logcat -b crash（FATAL）与 logcat -s TripoderFacade:W（门面降级）
# 3) 槽位墙：连续启动第 6 个应用即可复现（v56 及以前表现为 PROCESS_CAPACITY_EXHAUSTED）
```

相关：门面异常契约见 [../facade-exception-contract-nonbroadcast-20261113/README.md](../facade-exception-contract-nonbroadcast-20261113/README.md)，
BOSS直聘闭环见 [../bosszhipin-container-login-20261113/README.md](../bosszhipin-container-login-20261113/README.md)。
