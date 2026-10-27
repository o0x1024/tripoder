# 验收证据目录（`docs/evidence/`）

本目录用于存放自动化测试与真机验收脚本（如 `validate-workbench-control.py`、`validate-runtime-probe.py`、`run-self-hook-case.py`）生成的逐构建运行回执与调查报告。

- 每次验收运行均使用独立的子目录（如 `docs/evidence/<task-or-date>/<run-id>/`），绑定具体构建回执（`build-receipt.json`）。
- 历史过期构建的证据包已清理；新的真机验证结果将按需在此生成。
- Git 保留审阅后的报告、构建摘要和验收回执。APK、编译产物、反编译输出、重复源码快照、原始设备 UI/日志不纳入新提交；已有历史文件不受忽略规则影响。
- `compatibility-closure-2026-10-23` 的逐阶段汇总保留在目录根部，子目录中的逐次运行原始归档留在本地。历史报告对这些归档的引用不表示文件随仓库分发。
- 银行验收只记录必要的运行阶段、错误类型与脱敏结论，不保留账户、认证内容或原始银行页面。
