# 招商银行单运行时验收，2026-10-27

**结果：启动未通过，银行内 Hook、登录及业务验收未到达。** 自有样本的 Hook 验收不能替代此结果。

## 精确目标

- 原系统 APK：`cmb.pb` 14.4.5 / 1446，SHA-256 `d01869749503636d6c776fe6c1c3f5eac945bbdaf38e43167cfa87305c1cfe1d`。
- 既有客体安装：`default / cmb.pb / d6d45af0-b8d6-4f69-8d76-8505d6c008da`。
- dataId：`dbe3cc66-5d67-48b4-9103-62312c8c0904`，未重装、清空或替换银行数据。
- 原产品：`dev.tripoder.engine`，保留数据覆盖更新，未修改银行 APK。

## 已证实的边界

原正常构建的首次启动操作 `e33c4f78-4765-4916-bdff-d42fffc0fb3a` 返回 `UNKNOWN / LAUNCH_PREPARATION_UNCONFIRMED`。PID 9798 的系统退出记录为 SIGSEGV / status 11。固定 Hook 已完成批量发布（29/29 组、1809 bindings），Application 对象已构造；尚无成功 attach、onCreate 或前台业务证据。

随后先对原安装显式停止、查询原操作与精确恢复信息，再进行了两次独立诊断实验。全部原 UNKNOWN 原样保留，没有关闭不存在的 launch entry，也没有重放原操作。

第二个诊断实验 PID 19226 / TID 19270 的首条信号记录为：

```text
stage=2 (Application.attach 尚未返回)
SIGSEGV code=2 (访问权限错误)
instruction=0xb9400109 (LDR W9, [X8])
pc=libCmbShield.so+0x7af58:rwxp
lr=libCmbShield.so+0x7ba60:rwxp
fault=anonymous+0x2ca10:---p
```

这证明银行库线程读取了一个无访问权限的匿名页。代码页仍可执行，不能据此将原因归为代码页丢失执行权限、网络异常、或保护检测。匿名页的创建者、保护来源和指针来源仍然 UNKNOWN；当前证据不足以实施运行时语义修复。

同版本系统应用正常进入公开首页，检测到“首页 / 我的 / 账户总览”。该对照是正常入口/恢复，未证明冷启动；未执行认证、支付、转账、购买或其他金融操作。UI 原文仅在内存中筛选公开标签，不保存原始 UI、截图、账户或认证内容。

## 最小故障诊断

`build-workbench.py --fault-evidence` 是显式构建诊断开关，默认关闭。每个进程按 generation 写入宿主私有文件 `files/native-fault-<generation>.txt`；使用已有 MCP 文件导出接口读取，快照读取后释放。每个文件最多 32 条、每条 512 字节，只保留启动阶段、信号类别、代码位置、页权限和一条代码指令；不采集数据寄存器、栈、内存转储、路径原文或 UI。指令无法读取时明确记为 unavailable。

观察器始终返回 false，保留 ART/客体信号处理，并恢复 errno。记录到一个信号本身不代表致命退出，必须结合精确进程退出证据。自有信号工装在两个诊断版本下分别通过 3 项实测：pending mask、普通 signal delivery、带受控系统调用的 signal delivery。该工装空间已正常删除。

最终恢复默认构建，`faultEvidence=false / startupDiagnostics=false`。APK SHA-256 `d3331338e193e8a96308dbdadb011ff02d62cc441abcc7c162fd420e34eed41d`；core SHA-256 `d614bbd20f447984596792645d48122efbc40c7a3838c8bf252ae8dc9775c613`。最终二进制的自有 Java/Native Hook 回归单列于 `clean-native-regression/summary.json`，不能视作银行验收通过。

## 下一步

优先恢复原版银行的正常运行：先拆解启动路径中的必要承载修改与可选观测修改，建立业务 Hook、代码观测和抓包均未启用的最小承载基线。现有 `startupHook=false` 仍会初始化 Native 模块监听，而该监听同时负责信号接入等内部语义，不能将其简单视为可直接删除的观测功能，也不能将这处耦合当作已证实的崩溃根因。

围绕已定位的匿名页追踪分配/保护来源与访问指针来源；先用自有受保护页样本验证相应运行时边界，再对同一原版银行安装做定向复测。保持单运行时，不切换旧模式，不用修改页面权限制造成功结果。银行启动通过后，由用户完成真实认证，再验证只读页面和前后台恢复；这些通过后，才逐项启用并验收业务 Hook 与抓包。
