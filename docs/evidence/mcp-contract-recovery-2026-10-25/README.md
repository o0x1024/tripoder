# MCP schema、UNKNOWN 恢复与分阶段验收

日期：2026-10-25，Asia/Shanghai。源码修改已完成，保留两仓库原有未提交工作。最终恢复作用域保护 APK 已构建，等待并行真机任务结束后部署；不能将它描述为已在新安装上完成 THICK 回归。

## 已实现

- `agent/tools.mjs` 同源生成公开 `inputSchema.anyOf`、路由分支和 action 元数据。Node、Java 和 Sentinel 使用公开 schema 校验。模型可以看到各 action 的严格必填/允许字段，不再只看到字段并集。旧工具名不增加执行别名，不忽略未知字段，不自动改写错误参数。
- Sentinel 保留模型 schema 的公开分支；隐藏元数据与公开分支缺失/不一致时拒绝派发，发布新目录前也校验一致性。`targets` 不接受 target，`launch_targets` 不接受 limit；这些约束现在对模型和执行端一致。
- HTTP Java `AgentRecovery` 与 stdio `recoveryReceipt` 返回原操作 ID、只读恢复步骤、`replayAllowed=false`、`newIdRetryAllowed=false`、`preserveOriginalEvidence=true`、`pollingDoesNotResolve=true`。查询本身成功但结果未知用 `OPERATION_OUTCOME_UNKNOWN`，不改写原 result/evidence。
- UNKNOWN 查询不推荐重复查询自身。无 entry 不推荐 close；明确停止/死亡等证据后才能重新规划。读取当前 targets 或换新 ID 不会将旧 UNKNOWN 变为确定结果。
- 实际只读测试暴露额外缺陷：`launch_recovery` 的无 entry 分支原先没有核对输入 target 是否属于原操作作用域。已用现有 `operation_scopes` 加入精确所有权校验；错误的新/退休/全局作用域拒绝。原无 entry 日志没有记录可证明的安装代次时，返回 `targetVerified=false`，恢复指引不再借用当前安装补造身份。
- 电脑侧新增 MCP `artifact_verify`，分别记录捕获回执、下载字节/manifest、DEX 内部格式和消费者可用性。所有必需阶段通过才 `taskComplete=true`。`file_pull` COMPLETE 明确为 DOWNLOAD_ONLY；不修改原文件，不自动重算头部或替换派生产物。
- 消费者在电脑侧服务启动时用 `--dex-validator /absolute/dexdump` 显式配置。模型不能传任意执行程序。未配置、缺少捕获回执或工具不可用均如实保留未验收状态，不伪造 PASS。

## 验证

- 生产 Java schema / 文件导出 / 并发超时测试通过；443 个 Java 参数样例通过。
- `python3 android/workbench/test/run-mcp-tests.py`：最终 40 项 Node 测试通过，包括公开/执行 schema 等价性、恢复原 ID 与证据保留、拒绝当前安装冒充原安装、分阶段成功/失败/未验收判定，以及原下载回归。另有纯 Java 原操作作用域校验测试。
- Sentinel Rust MCP 测试通过；显式运行原本忽略的 `tripoder_generated_contract_cases`，443 个跨语言样例通过。另显式运行真实手机 `connected_tripoder_v3_admission_and_install_manifest`，使用当前 default/cmb.pb 安装，只读原生 TLS MCP 契约/清单验证通过，见 `sentinel-native-transport.json`。
- Sentinel Vite + Rust 调试桌面构建完成；最终 Tripoder 诊断 APK 从独立目录构建，避免其他任务覆盖共享产物，摘要见 `final-build.json`。
- 两仓库 `git diff --check` 通过。最终修改文件均低于 2000 行。

早期真实传输验收曾遇到更新后的 SUPERVISOR_INTERRUPTED / ENGINE_NOT_CONNECTED、连接超时，以及原安装被其他任务替换后的代次冲突，均保留为失败/前提变化，不等同参数拒绝。恢复使用产品明确的“重新连接”操作，不重放银行业务命令、不改变旧 UNKNOWN。重复测试只读契约时，显式绑定当前新安装，不用它替代旧故障身份。

## 实际模型会话

会话 `bee14afa-2b54-4af8-ab6a-d7193fb79b45`，标题 `MCP只读schema与产物验收测试`。

8 次模型工具调用与数据库中的工具参数/回执逐字节对应，存在6个真实模型用量请求。参数拒绝次数0：targets、launch_targets、operation_get×2、launch_recovery×2、process_history、artifact_verify。全程没有启动/停止/安装/模式修改或 shell/ADB 工具调用。模型区分 schema 调用通过、产物失败和旧操作 UNKNOWN。

该测试还暴露了上面的无 entry 恢复跨安装绑定缺陷，因此最终源码已增加保护；最终 APK 尚未部署，不能以本轮旧保护版本的读回执声称最终作用域保护已做真机回归。会话元数据见 `model-session.json`，不保存完整聊天或凭证。

## 产物与报告修正

`artifact-acceptance.json` 使用真实捕获回执和本地6,552,947字节快照，配置实际 Android build-tools 37.0.0 dexdump：

| 阶段 | 结果 |
|---|---|
| 捕获 | CAPTURED |
| 下载 | VERIFIED，SHA-256与引擎回执一致 |
| 格式 | FAILED：SHA-1、Adler-32、data节检查失败 |
| 消费者 | FAILED，dexdump退出1 |
| 整体 | FAILED，taskComplete=false |

有效 APK 明文 DEX 对照通过格式检查和 dexdump，退出0，解析179个cmb/shield命名空间类。它不能证明壳厂商或自研归属。

`/Users/like/android/cmb_analysis/REPORT.md` 和 `compare/COMPARE.md` 已重写为证据边界清晰的报告，原文保留为对应 before-evidence-correction 文件。Sentinel 两条相关记忆通过其原生编辑/投影流程更新，ID保持不变：

- `mem-d6e433627ff38b75bd05a9efd2006a1f`
- `mem-cd4ef1c27346081ccf701377bd141609`

两条记录的 lexical_indexed=1、skill_projected=1、last_error=null，旧文另存本地备份。没有修改 Codex 的记忆目录。

## THICK：待设备空闲后独立复现

旧安装为 p6-20261014 / 9cd40a21…，已被其他任务移除。原两个操作仍为 UNKNOWN。更新前历史退出记录为 SIGSEGV/status11，见 `thick-historical-exits.json`；没有故障PC/原生栈，撤回“166MB APK复制超时/OOM”的因果说法，根因保持 UNKNOWN。

两次准备中的诊断因网络初始化失败或原安装代次失效而停止，均在任何 stop/start/mode 修改之前结束，保存在本地 `thick-diagnostic-20261025*` 目录。未操作当前新安装来制造通过。

用户选择：其他任务结束后，在新安装上独立复现。后续步骤：

1. 确认其他真机任务已结束，再安装 `final-build.json` 指向的 APK，核对包名和 SHA-256，明确重连引擎并刷新 MCP 目录。
2. 只读查询当前安装及原始 APK 哈希，显式提供新的完整 target，不能沿用过期代次。
3. 运行下列诊断器；它先取得显式停止的成功回执，再以 THICK 做一次新启动，不重放旧 ID、不切 THIN、不清数据。原 UNKNOWN 保持。新结果仅用于新安装，不作为旧故障已解决的证明。

```sh
node tools/investigate-thick-start.mjs \
  /Users/like/.tripoder/tripoder-connection.json \
  /Users/like/android/cmb_analysis/thick-independent-NEXT_RUN \
  --execute '{"space":"EXPLICIT_SPACE","package":"cmb.pb","installGeneration":"EXPLICIT_CURRENT_GENERATION"}'
```

若仍失败，只取精确新进程的退出与原生故障定位信息；没有确切故障证据则保留 UNKNOWN。不得通过重装客体、换空间、换模式或改写历史状态来制造通过。
