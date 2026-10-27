# 招商银行：真实进程身份修正后的验收

**正常启动仍未通过；登录和只读业务验收未到达。** 本次不启用银行业务 Hook、启动探针或抓包。

## 构建与目标

- 已安装 APK SHA-256：`82d48b91ffb8b2e685ed1fd4768030019aa00c0baa199b9d7a4cabd0eff37449`。
- Native core SHA-256：`a63d7025a771daa2c62785111ff20a02377e3159e668982a3ef5854c7308a0c6`；自有样本验证实际加载摘要匹配。
- 当前工作区源文件与构建回执逐项一致；诊断开关关闭。
- 原版银行 APK：`cmb.pb` 14.4.5，SHA-256 `d01869749503636d6c776fe6c1c3f5eac945bbdaf38e43167cfa87305c1cfe1d`。
- 目标：`default / cmb.pb / d6d45af0-b8d6-4f69-8d76-8505d6c008da`。
- 原 dataId：`dbe3cc66-5d67-48b4-9103-62312c8c0904`；安装、数据及原始 UNKNOWN 均保留。

## 银行实测

本次为修改构建后的独立回归。先核对原安装、工具关闭状态、历史 DEAD/UNKNOWN、显式停止回执和恢复信息，再发起新验收操作；没有重放历史操作或关闭不存在的 launch entry。

操作 `236dc995-f453-47cb-9811-798200211d81` 返回 `UNKNOWN / LAUNCH_PREPARATION_UNCONFIRMED`。对应进程：

```text
processGeneration=f5b9728af25c7e9e0faec595174cb3078973c266bd41e0360878aff9e3ba1bc0
PID=8071
NATIVE_LIBRARY_RUNTIME_INSTALLED
固定发布完成：29/29 组，1809 bindings
PROCESS_IDENTITY_READY
APPLICATION_CONSTRUCTED_BEFORE_ATTACH
系统退出：SIGNALED / status 11（SIGSEGV）
工具控制器初始化次数：0
```

真实命名已通过内核 cmdline 读回验证，Application 已构造；没有 attach 完成或 Application 发布证据。系统没有提供可读取的崩溃 trace。本次不把先前诊断构建的 `libCmbShield.so+0x7af58` 当作当前 PC，也不将退出归因为网络或加固检测。

`TripoderHardened` 在此前记录的 `NoSuchFieldException` 被捕获后启动继续；它先于真实命名与 Application 构造，当前没有证据把它当作本次原生故障根因。

没有进行认证或金融操作，没有保存原始银行 UI、截图、账户信息或认证内容。

## 最新二进制回归

- 自有样本真实 argv0、主线程 comm、AMS slot 与 5 项信号/保护页行为：见上一阶段 `process-identity-after.json`，实际 core 与本次一致。
- 自有 Java/Native 回归 16 项通过：`native-regression/summary.json`。
- 显式 startupHook 通过：顺序为 Native 必需边界 → 真实进程身份 → 工具控制器 → Application 构造；`early:startup` 撤销后恢复 `guest-original:startup`。见 `explicit-startup-hook.json`。
- procfs 真实 FD 契约单列于 `proc-descriptor/result.json`。构建、工装与银行验收分别记录。

QA 使用 USB 标准输入在手机本地调用原 MCP HTTP 服务，保留原 Host、会话和协议验证；不新增运行时后端、监听服务或失败重试。该结果不证明电脑到手机的 LAN 直连可达。

## 下一步

先定位这次 SIGSEGV 的访问地址、所属映射及地址形成过程，再决定承载层的最小修复。原始代码页的额外文件映射是待验证的调查点，尚未证明与银行退出相关；不能用更换 Hook 库、修改页面权限或切换旧模式制造成功结果。银行启动通过后，再由用户完成认证并继续只读页面及前后台恢复验收。
