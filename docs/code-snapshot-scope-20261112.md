# 代码快照的提交范围（2026-11-12 清点）

抖音「非官方渠道」这条线的分析已经收口，剩下的问题是**工作树里这些改动怎么入库**。这份文档是清点结果，
不是结论探讨：它把「选哪个范围」从三个模糊选项变成一份可逐条核对的清单，并记录**哪一类东西绝不能进历史**。

## 1. 工作树实况

| 类别 | 数量 | 说明 |
|---|---|---|
| 已跟踪的改动 | 170 | 集中在 `android/runtime`（82）、`android/self-hook`（27）、`android/workbench`（14）、`android/scripts`（13）、`android/engine`（11）等 |
| 未跟踪的条目 | 278 | `scripts/qa`（122）、`docs/evidence`（52）、`android/runtime`（40）、`android/self-hook`（10）等 |

这些改动来自多轮在途工作（不止抖音这条线），所以「全部入库」不是一次干净的提交，而是把别人的在途状态一起定格。

## 2. 三种范围的精确含义

### ① 抖音来源链路（推荐，9 个文件）

已跟踪、有改动：

- `android/runtime/src/dev/tripoder/runtime/GuestPackageManager.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimePackageBridge.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimePackages.java`
- `android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookApplication.java`
- `android/workbench/src/dev/tripoder/hooklab/WorkbenchAppActions.java`（入口提示）

新增、未跟踪：

- `android/runtime/src/dev/tripoder/runtime/GuestIdentity.java`
- `android/runtime/src/dev/tripoder/runtime/IdentitySnapshot.java`
- `android/scripts/identity_contract.py`（契约断言，v42 FAIL → v44 PASS）
- `android/scripts/identity_ab.py`（原生 vs 容器 A/B 举证）

这 9 个文件自成一个可解释的单元：**来源三平面（Self / Attribution / Provenance）的收敛 + 契约回归 + 入口提示**。
它编译通过的直接证据是 `build-hint-v52`/`build-hint-v53` 都是从这份工作树构建成功的。

### ② 全部源码类改动（约 280 个文件）

不推荐，理由不是「diff 大」，而是**里面混着不属于本仓库的东西**（见 §3）。要收就得先拆分，拆完等价于做 ①。

### ③ 只留 docs

可用但丢失可追溯性：证据文档里反复引用的 `identity_contract.py`、`GuestIdentity.java` 会变成"仓库里不存在"，
下次有人复核 v44 的 PASS 结论时无从复现。

## 3. 绝不能进历史的东西（本次清点的关键发现）

| 路径 | 形态 | 风险 |
|---|---|---|
| `.tmp-ssh/sshcmd.exp` | `expect` 包装器，用法 `<host> <user> <password> <command>` | 生产主机操作工具，与 tripoder 无关；凭据走 argv，任何日志回显都会泄漏 |
| `research_xiangyin_psbc/raw/` | 抓取页面/JSON 原始数据，**20 个文件含真实手机号** | 第三方 PII，与项目无关 |
| `119.29.111.31-服务盘点.md` | 主机资产盘点 | 基础设施信息 |
| `aw.bin` / `aw2.bin` | APK 分片 | 无来源说明的二进制 |
| `build-*/`（仓库根） | `build-workbench.py --build-dir` 传裸名时的落点 | 构建产物，每次构建都变 |

扫描方法（只统计文件数，不打印内容）：私钥头 `BEGIN * PRIVATE KEY` **0** 处、AWS key 形态 **0** 处、
`api_key/secret_key/access_token/password` 赋值形态命中 2 处（`scripts/qa/test_paired_log.py` 及其 `.pyc`，
形态是测试夹具 `password:pr…`，仍需人工确认后才能入库）。

已在 `.gitignore` 补上对应规则（`/build-*/`、`.tmp-ssh/`、`/aw*.bin`、`/119.29.111.31-*.md`、
`/research_xiangyin_psbc/raw/`），让它们**不可能被 `git add -A` 顺手带走**。

## 4. 探针取舍

`RuntimeSelfHookApplication.reportMaps()`（C2 判决用的取证探针）保留，但改为常量开关：

```java
private static final boolean MAPS_PROBE=false;
...
if(MAPS_PROBE)reportMaps();
```

理由：它证明过 C2 平面干净（`maps-path-clean`），删掉等于把那条证据的复现手段一起删掉；但常开会在每次客体启动
多读一次 `/proc/self/maps` 并打日志，属于正常运行的噪声。常量 false 时 javac 直接把分支消掉，零成本。

生成实验开关 `GuestIdentity.experimentStoreAttribution` 提交前已确认为 `false`（v46 谎报实验的产物不得默认开启）。

## 5. 结论

- 采用 **①（9 文件）**：一个能自证、能编译、能被证据文档引用的单元。
- `scripts/qa`（122 个）与 `research_xiangyin_psbc` 等在研究/运维产物，**单独评审后再定**，不与本次快照混提。
- 本轮只做本地提交，推送等确认（推送会把未经评审的源码公开到 `origin`）。
