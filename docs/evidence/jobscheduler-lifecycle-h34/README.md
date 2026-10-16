# H34 JobService / 客体 API 证据

当前最终目标未完成，完整 jobs 阶段未通过。详见 [H34 交接](../../jobscheduler-handoff-2026-10-16-h34.md)。

当前已安装 Runtime SHA：`bfa63a747f564cf2a3cf29e5ce3f52e89f0d202a31d6005a9e140be4594e6121`；458 源输入与 receipt 一致。归档 APK、receipt、源码快照及主机报告在 `artifacts/`，交接设备快照在 `handoff-device-state.json`。

- `jobs-01`：缺省图标启动异常与真实 Job start ANR。`anr-409.txt` 保留系统 8000ms deadline 和实际主线程栈，根因是解释器下解析宿主大 Manifest。已修。
- `jobs-04`：物理准备7.719s、冷start1.146s；MIUI拒绝worker重启，夹具result字段错误又导致控制器崩溃。失败及UNKNOWN保留。
- `jobs-05`：开启自启动后，直接终止运行中worker仍未观察第二次start。真实start/finish/cancel链有回执；整轮失败，无意外fatal/ANR。
- `jobs-06`：当前构建，finish(true)的FINISH_RPC_RETURNED=true之后终止worker，120s未见第二次start。物理准备7.791s、冷start1.169s、热24–28ms。整轮失败，无意外fatal/ANR。late retry-state.txt 的unknown发生在夹具超时cancelAll之后，不可用作初始根因。
- `autostart/`：只针对自有Runtime的MIUI自启动开关false→true实际UI记录。
- `platform/`：从固定设备framework.jar/services.jar提取的Job成员和JobServiceContext字节码，含源jar哈希；非网上通用平台假设。
- `host-final.log`：304/15/85/117/319/8/45/12全部通过，SDK sources226。

当前P4、核心完整矩阵、正式产品控制和微信/王者启动均未执行；H33成绩不属于此构建。没有强制运行系统job，也没有加入timer/重放队列来制造冷恢复成功。
