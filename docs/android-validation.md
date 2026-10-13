# Android Hook Lab 真机验收（2026-10-13）

已构建并安装 `dev.tripoder.hooklab` 0.1.0（ARM64 非调试版）。最终同一 APK 在当前连接的 M154FF / Android 17 / API 37 上完成两轮自检，每轮 **25/25 通过**。SDK 单元测试另有 **26/26 通过**。

## 安装包与运行条件

- APK：[tripoder-hook-lab-0.1.0-arm64.apk](../artifacts/tripoder-hook-lab-0.1.0-arm64.apk)，12,961,276 字节。
- SHA-256：`88bfc7dbf9125645641a9e82c12bec0cbe39ffd9a8a04b85f186340e4bf5f6fe`。
- UID：`10389`；`debuggable=false`；`debuggerConnected=false`。
- 编译后 Manifest 没有 `uses-permission`；签名 v2 校验通过。
- APK 内嵌 Frida Gadget 17.23.3，离线 Script 模式；Java Bridge 7.0.13；没有网络监听、外部 Frida 控制器或 shell 代理。
- 没有执行 Root、Shizuku、权限授予或调试附加操作。
- Native GNU Build ID：`43fd596fb8eb79e127a296f4937bdf7ea73e20bf`。

## 真实 Hook 结果

| 阶段 | Java `greet("phone")` | Native `retry_delay_ms(3)` |
| --- | --- | --- |
| 原始调用 | `original:phone` | `10` |
| 开启 Hook | `hook:phone` | `503` |
| 热更新 | `live:phone` | `903` |
| 拒绝非法参数后 | `live:phone` | `903` |
| 回滚参数 | `hook:phone` | `503` |
| 卸载恢复 | `original:phone` | `10` |

每轮包含预热 20,000 组原始调用、1000 组连续 Hook 调用和重新安装／卸载。最终 Java 与 Native 回调各命中 **1005 / 1005** 次。Native 函数入口机器码在开启时变化，卸载后与原始字节一致。目标 Java/C++ 函数没有 Hook 分支或参数读取逻辑。

另外通过手机界面点击“应用参数并开启 Hook”，看到 `custom:phone`、`903` 和 Native 指令已变化；点击卸载后恢复 `original:phone`、`10` 和原始机器码。[界面验证记录](evidence/android-hook-lab-2026-10-13/ui-verification.json)

## ADB 不参与执行的验证

启动 APK 后由应用安排 20 秒后的自检；电脑停止 ADB 服务约 40 秒。在此期间对本机 5037 端口检查 **79 次**，均无监听。报告的开始、结束时间完全位于该区间内，之后才重新启动 ADB 拉取结果。

此验证没有拔除 USB、关闭手机 USB 调试开关或撤销原有电脑授权。它证明此次 Hook 操作自主发生在 APK 内、无需活动的 ADB 服务；结合普通 UID、非调试包、零请求权限和离线内嵌代理路径，运行不需要 ADB 激活。用户还可用 APK 的“15 秒后自检”按钮在拔掉 USB 后复现。

- [连接状态的完整自检](evidence/android-hook-lab-2026-10-13/device-connected-result.json)
- [停止电脑 ADB 时的完整自检](evidence/android-hook-lab-2026-10-13/device-offline-result.json)
- [电脑侧 ADB 时间及端口记录](evidence/android-hook-lab-2026-10-13/device-offline-host-verification.json)
- [最终手机截图](../artifacts/device-offline/screen.png)

## 检查清单

| 检查 | 结果 |
| --- | --- |
| ordinary-uid | 通过 |
| non-debuggable | 通过 |
| no-debugger | 通过 |
| baseline/Java | 通过 |
| baseline/Native | 通过 |
| enabled/Java | 通过 |
| enabled/Native | 通过 |
| native-machine-code-patched | 通过 |
| hot-update/Java | 通过 |
| hot-update/Native | 通过 |
| invalid-update-rejected | 通过 |
| failed-update-keeps-revision | 通过 |
| after-rejection/Java | 通过 |
| after-rejection/Native | 通过 |
| rollback/Java | 通过 |
| rollback/Native | 通过 |
| 1000-paired-hook-calls | 通过 |
| both-callbacks-hit | 通过 |
| uninstalled/Java | 通过 |
| uninstalled/Native | 通过 |
| native-machine-code-restored | 通过 |
| reinstalled/Java | 通过 |
| reinstalled/Native | 通过 |
| final-original/Java | 通过 |
| final-original/Native | 通过 |

## 范围与复现

这次只验证自有测试应用的两个函数与参数生命周期，**尚未实现 Android 虚拟机，也未验证微信、钉钉、王者荣耀、原神**。多线程并发、在途卸载、通用跨进程 Hook、任意脚本热替换、AI 生成、文件／环境控制和手柄映射仍待实现。这里的短时自检不是性能基准或长期稳定性结论。

构建、版本、源码路径、手机操作和自动验收命令见 [Android 工程说明](../android/README.md)。本机编译因磁盘空间限制使用同版本 NDK 的 ARM64 构建组件子集；项目只声明 ARM64，其他 ABI 尚未验证。后续扩展 ABI 时应安装完整 NDK。
