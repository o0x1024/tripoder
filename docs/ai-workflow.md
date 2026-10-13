# 自然语言生成 Hook：0.5.0

手机的“方案工作台”已接入兼容 Chat Completions 的 HTTP 服务。完整接口地址、模型可配置。API Key 使用 Android Keystore 中不可导出的 AES-256 密钥，通过 AES-GCM 加密后持久保存在应用私有 `noBackupFilesDir`，下次打开自动读取，界面保持密码掩码。密钥明文不写入偏好设置、代码、报告或 APK。字段禁用页面状态保存和自动填充。

点击“保存配置”或“生成并检查草稿”都会先加密保存。点击“清除密钥”删除加密记录和对应 Keystore 密钥，接口地址与模型保留。每次写入生成随机 IV；接口地址作为附加认证数据，换地址会清空输入框，必须为新服务填写密钥。读取失败或记录被篡改时拒绝解密，不回退明文。加解密均在工作线程运行；卸载应用或清除应用数据后需重新配置。

实现遵循 [Android Keystore](https://developer.android.com/privacy-and-security/keystore) 的应用密钥存储方式；未断言本机密钥一定由硬件保护。当前客体与宿主共享 UID，加密存储并不构成宿主和客体之间的安全隔离。

## 使用流程

1. 首次在“设置 → AI 服务”填写完整接口地址、模型和 API Key，点击“保存配置”。配置由所有项目共用。
2. 创建或打开示例应用项目，在 Agent 页输入需求，点击“发送并生成草稿”。手机发送需求、筛选后的目标签名和 SDK 示例，再检查模型返回的 TypeScript。
3. 草稿、对话和检查记录存入当前项目。到“脚本 → main.ts”查看代码；也可以使用脚本工作台内的“生成并检查草稿”。
4. 检查源码和诊断；点击“应用方案”才激活，页面显示 Java／Native 的真实结果。
5. 点击“打开应用，查看效果”，验证实际 Activity 的结果；也可继续编辑、部署、回滚代码和参数，或停用 Hook。普通进入客体页不再自动重置方案；显式自检会重置测试方案。草稿保存不会自动重新激活。

预填 DeepSeek 地址为 `https://api.deepseek.com/chat/completions`，模型为 `deepseek-flash`；对该官方域名发送 `thinking: {type: "disabled"}`。模型和 API 规则可能变化，依据 [DeepSeek 模型与价格文档](https://api-docs.deepseek.com/quick_start/pricing/) 和 [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)（2026-10-13 查阅）。其他兼容服务不发送这个专有字段。

## 请求和执行边界

- 仅 HTTPS 远程请求，HTTP 仅允许本机 localhost／127.0.0.1。禁止自动跟随重定向。
- 连接 15 秒、读取 60 秒超时；响应最多 128 KiB；源码最多 32768 字符。拒绝非成功 HTTP 状态、错误响应结构、空代码和未完整结束的输出。
- 发送目标包／版本／摘要、Java 方法签名、Native 导出符号／Build ID／已确认 ABI。默认不发送手机序列号、应用数据、账号令牌、完整内存或类加载器路径。
- 模型输出始终是草稿。元数据是数据，不提供额外执行权限；SDK 只能绑定实际观察到的固定目标，不能把编造的方法或未知 Native ABI 当成可用目标。
- 草稿解释器只开放同步 Hook SDK 和少量纯函数，不暴露 Frida、Java、网络、文件或系统全局。使用标准 TypeScript 语法子集，不另造语言语法；详见 [架构](architecture.md)。
- 方案绑定当前目标摘要和进程会话，重启后的旧会话拒绝部署。错误准备保留旧方案；回调失败记录并回退原函数，不意味着 Hook 成功。

项目 Agent 当前是生成与检查助手，没有自主定位方法、拉起任意应用、循环交互测试的工具链。对话记录按项目保存，但模型每次生成使用本次需求、草稿与目标证据，并未发送完整历史对话。生成期间草稿发生变化时，候选文件另存到 ai，保留用户编辑。

现在 AI 只生成 Java／Native Hook 方案。文件、设备字段、自动化与按键映射通过独立 UI 使用，尚未统一为模型可调用 API。也没有通用第三方应用方法发现、完整 TS 类型检查或操作系统级脚本隔离。

## 可复核的验收

`workbench-result.json` 包含真实脚本 20 项检查、手机本地 HTTP 契约 4 项检查、Keystore 加密存储 9 项检查。其 `liveModelVerified: false` 表示这些接口响应来自测试服务器，不能用来证明真实模型可用。

脚本工作台的 `live-ai-result.json` 仅在真实 HTTP 生成成功后写入：服务域名、模型、时间、源码摘要、目标证据；检查通过与部署后分别记录 `draftChecked`、`deployed` 和真实函数调用结果。它不记录密钥或授权请求头。该文件保存在应用外部专属目录，取证时核对宿主 APK 摘要及当前生成时间，拒绝旧结果。项目 Agent 的对话、草稿及检查分别在 ai／scripts／runs 下保存。

0.5.0 项目 Agent 验收见 [项目 UI 验收](project-ui-validation.md)。0.4.0 UI 与密钥保存验收见 [UI 验收](ui-validation.md)。此前真实服务验收结果见 [0.3.0 验收报告](workbench-validation.md)。模型服务失败时仍可手动编辑、检查和部署。
