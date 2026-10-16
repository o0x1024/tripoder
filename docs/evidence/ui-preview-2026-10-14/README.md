# Tripoder 运行预览：真机验收

2026-10-14。结论：**原生 UI 与自研客体体验闭环通过；不代表 P1–P6 全部完成或微信、王者荣耀可运行。**

最终 APK SHA-256：`aba9aed3b7a4d2e3ec216c6a7c3ca1c7a9b93ae9c91276f78d147d72fc12b44c`。

设备：Xiaomi / Android 17 API 37 / ARM64 / 4096 字节页。沿用 `dev.tripoder.runtimeprobe` 更新安装，名称改为 **Tripoder · 运行预览**；原 Tripoder 工作台未覆盖、未清数据。未使用 VirtualApp 代码，未切换旧运行器、软件渲染或本机应用作为 fallback。

## 交付界面

- [首页真机截图](screens/home-accepted.png)：真实可点击入口、当前空间和待接入应用。
- [客体真机截图](screens/guest-accepted.png)：真实独立 APK 的计数、重建和返回按钮。
- [运行记录截图](screens/records-two-spaces.png)：3 条独立完成记录，旧失败记录仍保留。
- [空间状态](screens/spaces-accepted.png)、[设置](screens/settings.png)、[真实环境](screens/environment.png)。

截图为手机原始画面。`preview-ui-device.py` 从当前控件树读取边界后点击，拒绝操作其他前台应用；不是通过直接调用按钮回调或改写计数文件完成验收。

## 当前构建证据

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 实际 UI、身份、制品、记录与数据核对 | 16 / 16 | [ui-verification.json](ui-verification.json) |
| 客体存储与 Native 路径回归 | 274 / 274 | [P3 设备报告](p3-regression/device-result.json)，run `p3-4e2b166f1be34ce9` |
| C 路径策略与真实内核 FD 语义 | 28 / 28 | [native-policy/result.json](native-policy/result.json)；shell 执行，不冒充客体 Hook 验收 |
| 构建输入一致性 | 140 个源文件摘要匹配 | [build-receipt.json](build-receipt.json) |

实际完成记录：

| 场景 | 客体 PID | 计数 | 观察 |
| --- | --- | --- | --- |
| 空间 04 首次运行 | 10381 | 3 | 连续点击 3 次，真实 recreate 1 次后仍为 3 |
| 空间 04 再次运行 | 13942 | 3 | 新进程、新代次、同一数据目录，计数保留 |
| 空间 05 首次运行 | 15984 | 0 | 另一空间、另一数据目录，从 0 开始 |

控制界面 PID 为 25876，均不同于客体。每条记录验证真实包名、挑战值、PID、客体 APK 摘要、宿主 APK 摘要，并在 Activity 完成回执和进程停止后写入 SUCCEEDED。原始记录：[首次](space04-first.json)、[重开](space04-reopen.json)、[另一空间](space05-first.json)。空间页随后观察到 0 个活动客体进程。

点击微信只打开 Tripoder 自己的「等待适配」对话框：[证据](screens/wechat-unavailable.xml)。没有启动本机微信，也未请求用户登录。

## 失败与修复留存

1. 第一构建 `b8514753…`：旧 P0 验收在结束后保留单写者锁，新界面明确失败：[首页](screens/home.png)。补上 P0 验收 finally 关闭存储。停止旧测试进程后验证新 authority 可以正常连接。最终构建没有重跑完整 P0，因此不把该清理路径描述为已通过完整 P0 回归。
2. 第一构建创建了客体控件树但画面黑屏：[像素](screens/guest-observed.png)、[GPU 日志](guest-gpu-failed.txt)。策略明确授权已验证设备 `/dev/kgsl-3d0` 后，真实硬件渲染正常；没有放开 `/dev` 子树。
3. 第二构建 `f205fe1f…`：点击触发 `SYSTEM_SERVICE_UNSUPPORTED:audio`：[崩溃日志](guest-click-failed.txt)。只实现登记客体窗口的 CLICK 音效能力，完整 Audio 服务仍拒绝。系统 CANCELLED 的 [回执](space02-first.json) 显示已返回而非成功。
4. 第三构建 `685782ad…`：计数与重建完成，但固定证据文件名导致 `PROBE_REPORT_ALREADY_PUBLISHED`：[页面](screens/records-final.png)。旧导出副本没有新结果，保留为 [failed-export-stale-copy.json](failed-export-stale-copy.json)，不能当作空间 03 的完成证据。最终修复使用唯一运行 ID 文件和原子 latest 指针，导出错误不再改变已持久完成的运行结果；连续 3 条真实完成回执验证通过。

早期 `*.capture-stdout.bin` 保留 Android screencap 多显示器警告污染 stdout 的原始字节；对应 PNG 只移除了 PNG 签名前的文本。最终截图改为设备 PNG 文件传输，未编辑画面内容。

## 验收边界与下一步

本次验收覆盖触控、滚动、导航、空间创建与切换、真实 Activity、重建、重启持久化、记录和错误展示。未清除失败空间或历史 UNKNOWN 状态来取得通过结果。原试验空间 01 和 03 的未确认 UI 记录仍阻止在该空间重启；正常体验请选择已验收空间 04 或 05。

当前构建没有重跑完整 P0/P1/P2；既有历史证据按其原 APK 摘要保留。没有验证其他设备、大字体/无障碍完整流程、长期稳定性或完整音频能力。当前是同 UID 的逻辑数据分区，强隔离仍为 false。

下一步按既定路线补齐通用组件与任务语义、控制面和运行面边界，再把项目/脚本工作台接入；微信、王者的真实运行和登录验收仍待完成。[UI 结构与实现边界](../../ui-runtime-preview.md)。
