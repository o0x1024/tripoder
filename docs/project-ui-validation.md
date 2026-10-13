# Tripoder 0.5.0：应用库与项目工作区

2026-10-13。在当前连接的 Android 17 ARM64 手机上完成安装与验收。发布 APK 为非 debuggable release；普通运行无 Root、ADB 激活、Shizuku 或外部 Hook 服务。

制品：[tripoder-0.5.0-arm64.apk](../artifacts/tripoder-0.5.0-arm64.apk)，SHA-256：`490bb45653e779f81f7cbdc4850581547fc24367d3e0aa168d28fd93d3f12b1d`。

## 界面与数据组织

- 首页改成**应用／项目／设置**三栏。应用采用网格列表，最后一项为“添加应用”。已添加应用持久保存；待适配应用不会显示可运行。
- 项目绑定具体应用，拥有 **Agent／脚本／测试／文件**四页。新项目与旧项目的数据相互独立；原来的草稿、需求、环境、输入配置与小型示例运行文件迁移到“示例项目”，旧文件保留，迁移可重试且不覆盖已存在项目文件。
- 全局 AI 设置独立于客体进程，保留此前的 Keystore 密钥加密存储。密钥不会写进项目目录。
- Agent 可保存需求和对话，调用已配置服务生成标准 TypeScript 并进行真实目标检查。当前需要用户到脚本页应用方案，再到测试页验证；自主目标发现和交互测试循环尚未实现。
- 测试页使用当前项目的真实 Java／Native 调用值比较预期。没有断言则记录 `observed`，不会假报通过。部署和测试记录都写入当前项目的 runs。
- 文件页可读写项目文本；project.json 由运行器管理，不能通过编辑器改写；限制路径穿越和符号链接。客体 runtime 数据使用独立文件入口。
- 客体浮动工具按钮携带项目 ID；应用首页记住最近成功启动的示例项目。

```text
files/projects/
  apps.json
  <project-id>/
    project.json
    scripts/main.ts
    scripts/deployed.ts
    tests/expectations.json
    config/environment.json
    config/input.json
    config/parameters.json
    assets/
    ai/prompt.txt
    ai/conversation.json
    runs/
    compatibility/latest.json
    runtime/
```

这些是宿主 UID 内的逻辑项目目录，不是操作系统安全沙箱。切换客体项目会重启 `:guest` 进程；上一个项目的旧客户端不能将请求发到新项目。重启不会自动启用 Hook。

## 真机界面与真实服务

- 应用库和项目列表正常显示，用户原有“AI扫描”和“王者荣耀”记录保留。
- 旧脚本迁入“示例项目”；通过脚本页部署后，测试页观察到 Java `script:PHONE`、Native `706`。
- 全局设置自动读取已加密保存的密钥，没有重新要求输入。
- 最终包界面验证了 `assets/ui-check.txt` 新建保存及离开页面后重新读取；打开全局 AI 设置前后客体 PID 相同，项目未被重启，已有加密密钥仍能读取。
- 通过界面新建 `UI-QA` 项目，输入 `Set Java greeting to project-ai and native return to 713.`；真实模型返回草稿，项目 Agent 检查通过并保存对话与脚本。没有使用本地 HTTP 测试服务器代替这次生成。
- 最终包实测发现第一份草稿返回 `project-aiPHONE`，与固定返回值预期不符，测试页正确记录为失败。澄清为“constant string … without any arguments or suffix”并重新生成、部署后，Java 固定返回 `project-ai`、Native 返回 `713`；断言通过，实际客体 Activity 显示相同结果。失败和通过记录均保留，未把语法检查通过当作行为成功。
- 王者荣耀项目完成安装包和独立进程入口／资源预检；游戏启动状态仍为未通过。详情见 [兼容性记录](hok-compatibility.md)。

截图位于 [ui-v0.5.0](../artifacts/ui-v0.5.0/)：apps、projects、project-agent、script-editor、project-test-hooked、project-agent-ai、file-editor、settings、ai-settings。界面验收期间的构建包括最终包之前的 0.5.0 测试包；各机器可读结果中的摘要用于区分构建，不能将早期结果冒充最终包结果。

最终包的 AI 行为结果见 [断言通过](../artifacts/ui-v0.5.0/project-ai-test.png)、[真实客体界面](../artifacts/ui-v0.5.0/project-guest.png)、[生成的 TypeScript](../artifacts/ui-v0.5.0/ui-qa-ai-generated.ts)。`UI-QA` 为本次保留的示例项目，用户原有项目、应用和密钥均未清空。

## 自动化回归

最终发布包的回归结果见对应 artifacts 中的 result.json 和 host-verification.json。构建与 Android lint 均通过；lint 为 0 个错误、44 个警告。

| 最终包测试 | 结果 | 证据 |
| --- | --- | --- |
| 项目持久化、进程切换、旧客户端拒绝 | 13/13，电脑 ADB 服务停止期间 | [project](../artifacts/project-v0.5.0-offline/result.json) |
| 动态脚本、HTTP 契约、Keystore | 33/33，电脑 ADB 服务停止期间 | [workbench](../artifacts/workbench-v0.5.0-offline/result.json) |
| Activity、OpenGL、输入、文件、环境 | 18/18，电脑 ADB 服务停止期间 | [component](../artifacts/component-v0.5.0-offline/result.json) |
| 独立 APK 与客体 Hook | 31/31，另通过客体进程重启验证 | [guest](../artifacts/guest-v0.5.0-connected/result.json) |
| 宿主 Java／Native Hook | 25/25 | [host](../artifacts/host-v0.5.0-connected/result.json) |

共 120 项设备检查通过。这些检查针对自有示例与项目基础设施，不是王者荣耀功能测试。

项目自检新增加了 13 项真实设备检查，覆盖文件持久化与路径越界、两项目真实 Hook 和进程切换、旧客户端拒绝、运行数据保留、重启不自动启用 Hook，以及默认草稿保留。自检仅创建和删除自身临时 A／B 项目。

`--offline` 会在测试前停止电脑 ADB 服务 40 秒，并检查本机 5037 端口持续关闭，测试结束后才重新连接取证。手机 USB 调试开关和已有授权没有撤销，不应描述为“撤销授权后验证”。

工作台自检中的 4 项 HTTP 契约检查使用本地服务器，`liveModelVerified=false`；真实模型验证以上面的 UI-QA 流程为准。

## 尚未覆盖

当前仍是自有示例客体运行器。王者荣耀和另外三款目标的完整运行、任意 Hook、真正自主 Agent、实体手柄、长时压力、其他 Android／OEM 组合尚未通过验收。添加应用、生成草稿或预检成功都不会改变这个结论。
