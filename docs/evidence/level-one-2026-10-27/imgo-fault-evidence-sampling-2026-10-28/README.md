# 芒果TV 启动期 SIGSEGV 的抽样复现（2026-10-28）

承接 [native 库搜索路径修复](../../../native-library-path-fix-2026-10-28.md)。这批运行是临时的、手写的 ad hoc 脚本（不是 `diagnose-six-app-startup.py`），没有走正式证据归档格式，因此单独记录在这里，而不是一个标准的 `summary.json`。

## 背景

`six-app-fix1-imgo/` 这次正式运行里，芒果TV 在装好、启动、Activity 确认之后，进程在 30 秒稳定性窗口内真的死了。`workerExitHistory`（这次新增的诊断能力）给出的真实系统退出原因：

```json
{"pid": 3231, "reason": 2, "status": 11, "importance": 100}
```

`reason=2` 是 `ApplicationExitInfo.REASON_SIGNALED`，`status=11` 是信号号 11（`SIGSEGV`）。这是一次真实的 native 崩溃，不是诊断工具的误报。

## 复现率抽样

为了判断这是不是本次 native 库路径修复后的确定性问题，用临时构建（`android/product/build-fault2`，额外启用 `--fault-evidence`）对芒果TV连续跑了 20 次（每次 `pm clear` 重置引擎状态，`stabilitySeconds=15`）：

| 尝试 | 结果 |
|---|---|
| 1–5 | 通过 |
| 6 | 未通过，但 `native-fault-*.txt` 和 `result-*.json` 均为空/缺失——没留下任何可用诊断痕迹，无法确认是不是同一个 SIGSEGV，也可能是 harness 自身的瞬时问题 |
| 7–20 | 通过 |

20 次里 19 次通过，1 次失败且没有留下诊断证据。这个频率(~5%)和 10-27 原始诊断里"只见过一次"的描述一致，支持"这是一个独立于本次 native 库路径 bug 的、低频 native 崩溃"这个判断，但样本量仍然偏小，不足以给出稳定的复现率估计。

## 没有做到的事

- 没有在这 20 次里再抓到一次带 `TP_FAULT_V1` 记录的真实崩溃，所以没有拿到 PC/LR/故障指令这类精确定位信息。
- 没有验证这个崩溃是否和已安装的招行/淘宝/腾讯视频/拼多多同时运行、设备负载、温度等外部因素相关。
- `native-fault-*.txt` 这次为了绕开 `run-as`（正式签名包不可调试）临时改成写到 `getExternalFilesDir`，验证完已经还原回 `getFilesDir`，不在提交里。
