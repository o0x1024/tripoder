# Native Stripped SO 相对偏移 Hook 真机全链路验收证据 (2026-10-29)

## 1. 验收环境与设备

- **测试设备**: Xiaomi 17 Pro (`d861a4fa`)
- **系统版本**: Android 17 / HyperOS 2 / API 37
- **内核版本**: Linux 6.18.21-android17-0-g4b3f81e
- **目标应用**: 汽水音乐 (`com.luna.music`, versionCode: 100211030)
- **APK SHA-256**: `197fefa94025df5b5ebb129d99a18ca2be3b5d04fe8d16ea8bbf237744b1f1c8`
- **运行代次 / RunId**: `control-ffff3c5c816f4ca9`
- **结果文件**: [`result-control-ffff3c5c816f4ca9.json`](result-control-ffff3c5c816f4ca9.json)
- **稳定运行截屏**: [`control-ffff3c5c816f4ca9-target-stable.png`](control-ffff3c5c816f4ca9-target-stable.png)

## 2. 验收结果矩阵 (22/22 全部通过)

| # | 检查项 | 状态 | 观测值 / 证据说明 |
|---|---|:---:|---|
| 1 | `real-workbench-foreground-startup-connected` | PASS | Workbench 主控前台就绪 |
| 2 | `original-apk-digest-matches` | PASS | 原始 APK SHA-256 吻合，零重打包 |
| 3 | `declared-launcher-policy-supported` | PASS | 主入口 Launcher 策略支持 |
| 4 | `target-application-initialized` | PASS | 目标应用 Application 实例就绪 |
| 5 | `target-first-activity-acknowledged` | PASS | 首个 Activity (`AdSplashActivity`) 成功渲染展示 |
| 6 | `target-stability-observation-is-continuous` | PASS | 前台稳定性观察连续 |
| 7 | `target-original-worker-remains-running-for-30-seconds` | PASS | 目标 Worker 稳定存活 30s+ |
| 8 | `native-offset-module-selected` | PASS | 选定模块 `libalog.so` |
| 9 | `native-offset-rva-valid` | PASS | 目标 RVA `0x3e6c` |
| 10 | `native-offset-observed-count` | PASS | 观察模块数计数为 1 |
| 11 | `native-offset-symbol-loaded` | PASS | 符号装载确认已加载至内存空间 |
| 12 | `native-offset-symbol-offset-present` | PASS | 符号 RVA `0x3e6c` 解析无误 |
| 13 | `native-offset-plan-prepared` | PASS | `ctx.native.offset` 编译通过并生成计划 |
| 14 | `native-offset-hook-activated` | PASS | ARM64 前导指令重定位与 W^X 原子页跳板成功挂载 |
| 15 | `native-offset-status-active` | PASS | Hook 状态处于 `ACTIVE` |
| 16 | `native-offset-hook-disabled` | PASS | Hook 成功卸载，原指令页完整恢复 |
| 17 | `native-sub-name-plan-prepared` | PASS | `ctx.native.function(..., 'sub_3e6c', ...)` 自动提取 RVA 并编译成功 |
| 18 | `native-sub-name-activated` | PASS | IDA 风格计划成功挂载激活 |
| 19 | `native-sub-name-disabled` | PASS | IDA 风格计划成功安全卸载 |
| 20 | `native-offset-unaligned-rejected` | PASS | 非 4 字节对齐 RVA（如 `0x3e6d`）被编译器与底层严格拒绝 |
| 21 | `target-stopped-after-test` | PASS | 测试后清理，目标进程安全停止 |
| 22 | `original-apk-unchanged` | PASS | 原始 APK 未受篡改 |

## 3. 核心修复点

1. **Bionic Linker 真实路径映射对齐**：
   - ART `PathClassLoader` 传给 Bionic Linker 的真实库物理落地目录为 `engine.info.nativeLibraryDir`。
   - 修复 `RuntimeHookBridge.java` 中 `canonicalLibraryPath` 和 `verifiedLibraryArtifact`，使其直接指向 `engine.info.nativeLibraryDir + "/" + name`，彻底恢复了 Linker Notifier `r_debug->r_map` 扫描匹配能力。
2. **导出符号属性键名对齐**：
   - 适配 `RuntimeElfExports.java` 输出的 `"rva"` 属性键名。
3. **Tripoder Hook 会话状态机生命周期对齐**：
   - 严格遵循 `observe -> prepare -> activate -> disable` 状态机；多轮次测试切换时显式重新签发证据观察会话。
