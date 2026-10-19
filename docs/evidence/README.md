# 验收证据目录（`docs/evidence/`）

本目录用于存放自动化测试与真机验收脚本（如 `validate-workbench-control.py`、`validate-runtime-probe.py`、`run-self-hook-case.py`）生成的逐构建运行回执、截图与日志。

- 每次验收运行均使用独立的子目录（如 `docs/evidence/<task-or-date>/<run-id>/`），绑定具体构建回执（`build-receipt.json`）。
- 历史过期构建的证据包已清理；新的真机验证结果将按需在此生成。
