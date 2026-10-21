# AI Agent 全局控制面与 MCP

MCP 连接到设备上的 Tripoder 全局入口，不绑定任何空间、应用、项目或进程。每次有作用域的调用都显式携带目标，完整配置与参数示例见 [MCP 使用说明](../agent/README.md)，以 `agent/tools.mjs` 的 `tools/list` 为准。

## 控制链路

MCP stdio → Node.js → ADB forward → 手机 loopback 长度帧（protocol=2）→ 主进程 Binder → authority → 安装或具体客体进程。初始化和工具发现延迟连接设备。全局设置管理入口，关闭入口不停止应用或已激活 Hook。

## 对象与目标

- 空间操作使用 `space`；应用删除使用包名和明确的安装代次清单。
- 安装操作使用 `target: {space, package, installGeneration}`。
- 进程操作使用 `target: {space, package, installGeneration, processGeneration}`。
- 源码操作按包名定位应用唯一项目，写入同时校验当前源码/声明 SHA。
- 操作查询使用原 `operationId`，不可变产物使用原进程代次及产物 ID。

没有连接级的当前目标，也没有单候选自动选择。`target_select` / `session_get` / `session_close` 不再发布。Android 内部为准确的项目绑定与进程保留有界控制上下文，用于准备令牌、回执关联和所有权检查；每次请求都重新解析显式目标，不读取连接选中状态。内部上下文不会限制全局枚举或其他资源的操作。

目标列表从全部有效安装与 RUNNING 进程生成，不要求用户先创建项目。安装与项目初始化可恢复；首次需要该安装的分析绑定时，authority 通过持久幂等操作补齐。

## 能力覆盖

全局状态/资源、空间管理、设备应用发现、原始 APK/splits 导入与升级、安装移除、应用彻底删除、运行模式、源码读写与恢复、启动目标枚举、进程启停、启动入口恢复、Hook 生命周期、Java/Native 观察、ClassLoader/DEX 与代码快照、UI 操作、存储与网络审计、HTTP、客体方法调用和产物读取。

电脑 APK 导入保持原始字节，分块上限 12 KiB、最多 64 个 APK；SHA 与只读 FD 在安装前再次验证。暂存可查询、取消或在安装终结后清理，未知结果不重放。直接从手机导入读取所选系统应用的原始 base/split APK。

## 状态与恢复

运行时修改使用 authority 操作日志；主进程源码/名称等修改使用原子落盘的本地操作日志，同一个 `operation_get` 入口可查询两者。相同操作 ID 改变内容拒绝。进程退出或重启后，原代次拒绝执行；结果未知时保留原操作 ID 对账，不能换 ID 重放。Hook 仍保留观察证据、准备令牌、源码 SHA、所有权和未知结果状态门。

多个 Agent 和手机编辑器可以同时访问。源码 SHA/CAS 避免覆盖；运行时命令通过原控制管线判断并发冲突。全局入口的能力与单次调用的明确作用范围分别检查。

## 验证边界

主机测试覆盖 MCP schema、显式目标隔离、传输、上传与失败不重放、实际 SQLite 迁移 SQL、源码保留/归档/恢复与 CAS。Android 源码须用 API 37 平台及匹配 apksig 编译。真机安装、真实 Hook 命中及停用恢复仍需独立验收，不能由主机测试代替。历史证据按其对应版本解释。
