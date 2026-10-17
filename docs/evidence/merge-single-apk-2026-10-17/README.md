# 工作台与引擎合并为单一 APK：真机验收（2026-10-17）

设备 `d861a4fa`（Xiaomi，Android 17）。产品包 `tripoder.apk`（`dev.tripoder.engine`）sha256 `945c6f02b9a4987232c7bcb6a6f2a5ec0ddf99196036b977d9d9ebb67d1e45de`。

| 运行 | 结果 | 说明 |
|---|---|---|
| `control-01`、`control-02` | 失败（44 项通过后） | 连接、空间、安装、绑定、客体启动均通过。Hook 激活时客体 `:slot3` 原生 abort：`TP_MANAGED_ENTRY_OWNERSHIP stage=4 slot=2271 quick=…5d0 expected=…710`，两次数值相同 |
| 合并前对照 | 同样失败（46 项通过后） | 15:02 构建的双 APK（`tripoder-engine.apk` 3781c2cb…、`tripoder-workbench.apk` 555741f6…）配合还原的合并前 control-test，同一位置、同一 abort 数值。**该 abort 不由合并引入**；15:02 以后源码仅合并改动有变化，10:02 `control-82` 通过后的差异在 `runtime/src` 与 15:01 重建的夹具 `a.apk` |
| `library-ui-01` | **通过 14 项** | controllerUid = engineUid = 10400 |
| `app-actions-ui-01` | 失败于 `explicit-space-selection-uses-independent-data` | 14:55 未提交的 `WorkbenchAppActions` 改为“单个绑定项目时点击直接按项目启动”，测试仍按“所选运行空间”断言。合并未修改这两处 |

`TP_MANAGED_ENTRY_OWNERSHIP` 的根因未证明；以上只说明它与合并无关，不是修复。
