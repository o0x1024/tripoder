# Tripoder 0.4.0 原生 UI

本版本只完成现有受控自有 APK 能力的产品界面，不表示四款第三方应用的虚拟化已经完成。

## 已实现

| 页面 | 实际行为 |
| --- | --- |
| 应用 | 原生应用首页、内置示例启动、方案入口、四款应用实际安装状态、兼容性说明、SAF 导入 |
| 方案 | AI 工作台入口、本地草稿入口、生成 → 检查 → 应用的操作引导 |
| 工作台 | 自然语言需求、可展开的服务配置与代码、检查／应用／回滚／停用、运行参数表单、真实调用结果 |
| 工具 | 客体文件列表与文本编辑器、Java MODEL／BRAND／MANUFACTURER 字段编辑与恢复 |
| 客体 | 可收起的浮动面板、自动化表单与高级 JSON、手柄映射表单与高级 JSON、文件及方案入口 |
| 设置 | AI 服务入口、运行权限与边界、版本信息、开发者诊断 |

统一原生控件的间距、字体层级、圆角、图标、颜色、触控尺寸和系统栏／键盘留白。资源由 Android View 与矢量绘制组成，不依赖网页或图片截图。独立客体的演示 Activity 保持自身 UI，宿主只增加工具浮层。

正常首页启动不加载宿主诊断代理、不运行自检，也不重置客体方案。通过首页和工具栏进入客体会先准备真实运行时。诊断入口保留原有命令行自检路由。草稿在页面离开时保存。“保存配置”会保存服务地址、模型，并通过 Android Keystore / AES-256-GCM 加密保存 API Key；下次自动读取，支持清除。生成草稿也会先保存配置。密钥记录位于应用私有且排除备份的目录；密文与接口地址绑定，编辑地址会清空已加载密钥。

## 当前边界

- 四款目标应用显示“已安装”只表示系统中检测到包，不表示能在容器里运行。
- 导入仍只接受与内置 SHA-256 一致的自有示例 APK。
- 自动化与手柄事件只作用于当前客体窗口中的资源名，不是全局输入或通用游戏摇杆。
- 文件限客体目录内的 64 KiB 文本；环境配置仅影响当前客体进程的 Java Build 字段。
- 原有真实 AI 接入已在 0.3.0 验证。本轮使用合成测试密钥验证加密存储、重启恢复及清除，不调用真实模型；测试密钥已清除。运行中的明文仅供页面和请求使用，不进入源码或报告。

## 验收

最终 release APK 已安装到当前连接的 Xiaomi M154FF（Android 17 / API 37 / ARM64）并验收。宿主 SHA-256：`8048387de19e564a94fb05ca32339effd21a0c057131eed0934000274d121310`。独立客体仍为原始 1.1 APK，JavaScript 代理与所有 Native 库和 0.3.0 字节一致，见 [打包核对](evidence/android-ui-2026-10-13/packaging.json)。

| 验证 | 结果 |
| --- | --- |
| Release 构建 / lint | 通过；0 个错误、45 个警告（现有硬编码文案及控件等提示） |
| TypeScript SDK | 35 / 35，通过；UI 与凭据修改不影响 SDK 源码 |
| 宿主 Java / Native Hook | 25 / 25 |
| 独立客体 Hook | 31 / 31；进程重启与数据保留通过 |
| 工作台 | 33 / 33：脚本 20、HTTP 契约 4、Keystore 9 |
| 组件、图形、文件、环境、输入 | 18 / 18 |
| 密钥 UI | 7 / 7：保存、完整进程重启恢复、密码掩码、更换地址清空、删除及重启后保持删除、服务配置保留 |

[完整回归摘要](evidence/android-ui-2026-10-13/regression-summary.json) · [密钥 UI 验证](evidence/android-ui-2026-10-13/credential-ui-verification.json) · [最终页面验证](evidence/android-ui-2026-10-13/final-ui-verification.json)

工作台和组件套件完整运行于电脑 ADB 服务停止的 40 秒窗口；APK 自行运行检查。此实验没有关闭手机 USB 调试开关或撤销已有 ADB 授权，不能把它描述成已验收“撤销授权后的首次安装”。APK 运行路径不请求 Root、ADB 激活或外部注入。

界面实操覆盖四栏导航、草稿检查与真实部署、参数 700 → 750 导致 Native 706 → 756、回滚到 706、打开客体保持方案、自动化执行、手柄映射保存、文件写入读取及恢复、设备字段应用与恢复。实体手柄没有在本轮测试。

开发过程的 [原始交互尝试](evidence/android-ui-2026-10-13/ui-development-attempts.json) 和 [布局修复尝试](evidence/android-ui-2026-10-13/ui-checks.json) 保留失败记录：快速 ADB 文本输入曾被输入法重排，改为逐字输入后恢复；缺失密钥校验曾触发输入焦点和系统自动填充弹窗，已取消校验时主动抢焦点并修复折叠行为。密码框聚焦后 UIAutomator 可能不再返回 hint，不能仅凭 hint 缺失认定字段不可见。最终凭据验收直接检查密码节点与重启后的状态。

## 界面截图

- [应用首页](evidence/android-ui-2026-10-13/home.png)、[方案](evidence/android-ui-2026-10-13/plans.png)、[工具](evidence/android-ui-2026-10-13/tools.png)、[设置](evidence/android-ui-2026-10-13/settings.png)
- [AI 工作台](evidence/android-ui-2026-10-13/workbench.png)、[加密密钥配置](evidence/android-ui-2026-10-13/ai-config.png)、[代码编辑](evidence/android-ui-2026-10-13/code-editor.png)、[运行参数](evidence/android-ui-2026-10-13/parameters.png)
- [客体浮动面板](evidence/android-ui-2026-10-13/floating-panel.png)、[自动化](evidence/android-ui-2026-10-13/automation.png)、[手柄映射](evidence/android-ui-2026-10-13/gamepad.png)、[文件](evidence/android-ui-2026-10-13/files.png)、[环境](evidence/android-ui-2026-10-13/environment.png)

本轮只使用合成密钥完成保存测试，配置截图中的掩码来自该测试值；测试结束已清除。此后界面出现用户已保存的配置，已保留，没有再次清除或发送 AI 请求。更早的 UI 开发截图用于展示未变化的表单样式，最终 APK 功能以同一 SHA-256 的回归报告和最终页面验证为准。
