# Tripoder Android 工程结构 (`android/`)

本目录包含 Tripoder 的全部 Android 原生工程模块与构建/验证脚本。早期基于 Frida Gadget 的实验模块（`android/app`）已彻底退役移除，当前产品统一使用自研 [`android/runtime`](runtime/README.md) 与 [`android/self-hook`](self-hook/README.md) 核心。

权威文档入口见：
- **[当前总体架构 (`docs/current-architecture.md`)](../docs/current-architecture.md)**
- **[单运行时与最小底座收敛 (`docs/single-runtime.md`)](../docs/single-runtime.md)**
- **[架构梳理与分阶段实施计划 (`docs/implementation-plan.md`)](../docs/implementation-plan.md)**

---

## 模块概览

| 目录 | 职责说明 |
| --- | --- |
| [`engine/`](engine) | 主引擎与恢复进程（`:authority` 与 `:recovery`）控制面实现与数据库持久化 |
| [`workbench/`](workbench) | 原生工作台 UI、应用/空间管理、脚本工作台与手机端 HTTP MCP Server |
| [`runtime/`](runtime/README.md) | 虚拟化沙箱核心库（Level 1 最小承载底座 + 按需探测与系统服务门面） |
| [`self-hook/`](self-hook/README.md) | 自研 ART Java Hook、ARM64 Native Hook、`fixed_libc` 双页重映射与受限 TS 脚本引擎 |
| [`scripts/`](scripts) | 确定性构建脚本（`build-workbench.py`、`build-level-zero.py`）与真机自动化验收脚本（`diagnose-six-app-startup.py`、`validate-runtime-ingress.py`） |
