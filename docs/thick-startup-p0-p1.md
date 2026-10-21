# THICK 启动减法 P0/P1（2026-10-21）

本轮保留 THICK 的组件、包、存储、Manager 能力限制和普通 Hook 调用者处理，削减启动阶段的全局干预及重复发布。已改善同一真机上网易云的进程冷启动，尚未验收达到原生速度。

## 实现

- 固定目标的 helper、备份及元数据在正常 ART 状态下准备，最后统一发布。发布完成后恢复原有 JIT 选项和运行标志，`observedLevel` 返回当前值。
- THICK 初次发布和普通 Hook 首次发布，会反优化并使已绑定 JIT code cache 失效。真实 JIT 调用者检查发现，仅调用 `DeoptimizeEverything` 会让恢复配置重新发布旧内联代码；本轮补齐失效处理，并验证修改与停用恢复。
- Guest Receiver/Service 在交给 Android 前发布实际回调入口，避免每新增一个组件就反优化全进程。入口升级为普通 Hook 时补做调用者失效。任意已有活动帧和全部内联调用者不在保证范围，原有两个 coverage=false 字段继续保留。
- THICK 仅移除无条件调用原方法的 Package/Traffic 包装，固定绑定 **2300 → 2257**。Manager 整类能力限制、身份转换和 UID/流量参数检查继续保留。
- THIN 保留原固定绑定集合和发布顺序。本轮曾在共享发布路径下观察到网易云主动退出 28；将减法限定到 THICK 后，两模式真实 MCP 回归均通过。
- 启动字段反射、文件试读改为显式调试开关，移除厂商监控线程干预。诊断映射扫描逐行检查所有原始输入，再在 native 中选取共享库、core、可疑后端及非 ASCII 路径行；完整输入的摘要、行数和 core 身份检查保留。没有增加启动或 Hook 控制超时。

## 真机对比

设备 `d861a4fa`，API 37；网易云 `9006005`，同一 APK 和安装代次。每轮重启宿主/authority、停止客体并预先打开工作台，再从 MCP `process_start` 调用开始计时。保留应用数据，不清缓存；停止检查可能预热安装校验，所以这组数值不等于从桌面点击起算的完整 authority 冷启动。应用自身缓存和网络状态未做到逐字节一致。

| 指标 | 改造前，3 轮 | 改造后，3 轮 |
| --- | --- | --- |
| 固定适配初始化中位数 | 3791ms | 2759ms |
| 准备并派发启动请求 | 一次 26075ms；另两次 Application 阶段 30 秒超时 | 9327 / 9963 / 10189ms |
| 观测到前台可见主页面 | 0/3 在观测期限内确认 | 11572 / 11987 / 11893ms |
| 固定绑定数 | 2300 | 2257 |

在改造后三轮之后回装原 APK、保留应用数据，再次触发 Application 阶段 30 秒超时。恢复优化 APK 后，准备并派发 **9242ms**，前台页面观测 **11587ms**。这支持改善来自代码，而非仅来自逐轮缓存预热。

前台页面观测要求客体页面有可见节点，且对应真实 Android task 是 `topResumedActivity`；它不是首次输入延迟或网络内容完全就绪时间。三轮样本不用于宣称 P95、整体 FPS 或原生性能等价。

## 验证与产物

- 真机策略检查 **18 项**：真实 JIT code cache 中的调用者、普通 Hook 修改/停用恢复、真实 Android 广播与 PendingResult、入口覆盖升级，以及 native 映射筛选完整性。
- 两模式 MCP **35 项**：实际前台页面、运行模式、加载的 native core 摘要、Hook 返回值 `53 → 135 → 53`、真实命中、停用恢复和项目源代码未被覆盖。
- Application 发布 **9 项**、执行副本 **8 项**；Java 映射检查 **21 项**、native 映射差分 **13 项/18000 行**、主机行为检查、SDK 编译和 Agent 单元测试通过。
- [精简对比与构建身份](evidence/thick-startup-p0-p1-2026-10-21/summary.json)、[策略验证](evidence/thick-startup-p0-p1-2026-10-21/policy-validation.json)、[两模式验证](evidence/thick-startup-p0-p1-2026-10-21/mode-validation.json)。原始日志、截图及 APK 留在忽略的本地产物目录。

通用测量入口是 `agent/startup-benchmark.mjs`。输入至少包含 `space`、`package`、当前 `installGeneration` 和 `expectedActivities`；先通过 MCP 核对安装身份与探针关闭状态。示例：

```sh
rtk proxy node agent/startup-benchmark.mjs \
  --serial d861a4fa --host-package dev.tripoder.engine.globaltest --device-port 29732 \
  --input /path/to/target.json --evidence /path/to/evidence \
  --rounds 3 --authority-cold true --label optimized
```

`--authority-cold true` 会停止指定宿主包的进程；工具超时只查询原操作，不重放启动。仍在关键路径上的执行副本全文校验、ClassLoader/编译缓存以及其余固定适配准备，留待 P2 和后续测量处理。
