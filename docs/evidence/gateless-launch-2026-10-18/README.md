# 去掉 Gateway 的单次调用启动（2026-10-18）

构建：`android/scripts/build-workbench.py` 完整构建（原生库输入未变），设备 d861a4fa。数据库 schema 32 → 33（删除 `controller_launches`）。

| 目录 | 阶段 | 结果 |
|---|---|---|
| `control/` | control（含安装、authority 死亡恢复） | 61 项通过。新增：等 Application 后返回入口、入口是带 ticket 的自有替身且 `NEW_TASK\|MULTIPLE_TASK`、入口占住应用根、`abandonLaunch` 交还后 FAILED `LAUNCH_NOT_DISPATCHED` 且只能交还一次、已签发未启动的入口在 authority 死亡后为 UNKNOWN 并出现在 `launchEntries` |
| `app-actions-ui/` | 工作台点图标启动 | 29 项通过 |
| `hook-ui/` | 项目页启动 + Hook | 11 项通过 |
| `library-ui/` | 应用库 | 14 项通过 |
| `resources/` | 资源与空任务回收 | 4 项通过（本次无可回收任务） |
| `target-wps-wake/`、`target-wps-wake-2/` | WPS 协作真实目标 + wake | **失败**：两次都是 `PROCESS_START_TIMEOUT_UNKNOWN:phase=APPLICATION`。超时时客体主线程在 WPS 自己的 Provider 初始化里（`com.gnet.skin.SkinManager.init` 类加载），不是死锁；Gateway 之前同样阻塞等待 Application 的 `real-app-goal-2026-10-17/wps-first-launch-77`、`-80` 也是同样的超时。wake 路径因此本轮未验证 |
