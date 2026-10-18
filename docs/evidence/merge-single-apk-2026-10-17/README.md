# 工作台与引擎合并为单一 APK：真机验收（2026-10-17）

设备 `d861a4fa`（Xiaomi，Android 17）。产品包 `tripoder.apk`（`dev.tripoder.engine`）sha256 `945c6f02b9a4987232c7bcb6a6f2a5ec0ddf99196036b977d9d9ebb67d1e45de`。

| 运行 | 结果 | 说明 |
|---|---|---|
| `library-ui-01` | **通过 14 项** | controllerUid = engineUid = 10400 |
| `app-actions-ui-01` | 失败于 `explicit-space-selection-uses-independent-data` | 14:55 未提交的 `WorkbenchAppActions` 改为“单个绑定项目时点击直接按项目启动”，测试仍按“所选运行空间”断言。合并未修改这两处 |
| `app-actions-ui-03` | **通过 21 项** | 产品包 sha256 见 `app-actions-ui-03/build-receipt.json`（含未提交的 `WorkbenchAppActions` 工作）。测试改为先验证空间选择、再绑定项目，并新增 `single-bound-project-launches-its-space`；点击只把 COMMITTED 绑定视为已绑定项目，删除安装后的 STALE 绑定不再接管点击 |
