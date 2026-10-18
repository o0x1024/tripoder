# Tripoder Android

> 2026-10-18：下文描述的 `android/app` 实验应用及其 Gadget/代理构建（`npm run android:build`）已退役并移除，仅作历史记录。当前产品结构见 [../docs/current-architecture.md](../docs/current-architecture.md)。

本文描述历史独立实验应用 `dev.tripoder.hooklab`，其 Gadget 后端不再进入正式产品。2026-10-16 的工作台/Engine 使用 [共享 Runtime 与单一自研核心](runtime/README.md)，当前逐构建状态见 [交接与证据](../docs/self-hook-handoff-2026-10-15.md)。下文保留旧实验的复现方法。

旧实验由 APK 内嵌的 Frida Gadget 在自身普通应用进程中加载标准 TypeScript 编译后的代理。无 Root、Shizuku、ADB 激活或外部 Hook 服务；release 的 `debuggable=false`。0.5.0 仅申请 INTERNET 权限，供用户主动请求 AI；Hook 本身离线执行。

它验证的是未来执行后端的基础能力，尚不是 Android 虚拟机，也不能直接附加到手机上其他应用的进程。

**0.2.0 独立 APK 载荷实验**：现位于“设置 → 开发者诊断 → 独立 APK 与进程诊断”，可在 `:guest` 进程加载单独构建的测试 APK，执行真实 Hook，并验证重启和数据保留。该客体未安装到系统；进程共享宿主 UID。详细边界与复现见 [客体运行器说明](../docs/guest-runtime.md)，实际结果见 [0.2.0 真机验收](../docs/guest-validation.md)。

**0.3.0 工作台与真实组件**：支持手机编辑／检查／应用标准 TypeScript 和自然语言生成，运行独立 APK 的 Activity、Application、XML 与 OpenGL。当前仅验证 Android 17 ARM64；资源加载需要 Android 11+。[底层能力验收](../docs/workbench-validation.md)。

**0.4.0 产品界面**：四栏主页、AI 优先的方案工作台、运行参数表单、文件与环境编辑、应用内浮动工具面板。自检集中到设置中的开发者诊断。[界面实现与验收](../docs/ui-validation.md)。

## 构建

当前 0.5.0 增加三栏主页、项目目录与进程切换、项目 Agent 草稿和对话、独立兼容性探测进程。[项目 UI 验收](../docs/project-ui-validation.md) · [王者荣耀进展](../docs/hok-compatibility.md)。

需要 JDK 17+、Node.js 24+、Python 3，以及 Android SDK：`platforms;android-36`、`build-tools;36.0.0`、`ndk;29.0.14206865`、`cmake;3.22.1`。设置 `ANDROID_HOME` 或在本目录建立不提交的 `local.properties`，内容为 `sdk.dir=实际 SDK 路径`。

从 `tripoder` 目录运行：

```sh
npm ci --ignore-scripts
npm run android:build
```

Gradle Wrapper 固定 9.7.1，AGP 固定 9.1.1；Gadget 固定 17.23.3，Java Bridge 固定 7.0.13。`gadget.lock.json` 固定官方 release 下载地址和实际获取制品的 SHA-256；下载脚本校验压缩文件和解压后的库。代理由 esbuild 打包，Buffer polyfill 一并内嵌，不需要 Node.js 或网络在手机运行。

产物：`app/build/outputs/apk/release/app-release.apk`。当前使用本机自动生成的测试签名，适合研发分发；它仍为非 debuggable release。签名私钥不进入仓库。

## 手机上使用

直接安装 APK 并点击 **Tripoder** 图标，正常启动不会运行自检或重置 Hook。

- **应用**：网格显示已添加应用，末尾“＋ 添加应用”选择本机应用。示例可在 Tripoder 内运行；其他应用显示“待适配”，可建立项目。添加记录不会自动获得运行能力。
- **项目**：新建项目选择目标应用；每个项目包含 Agent／脚本／测试／文件。AI 对话、草稿、配置和测试结果跟随项目保存。
- **Agent**：自然语言生成并检查当前项目的草稿，不自动部署。未接入运行器的应用只能保存需求、检查兼容性。
- **脚本**：main.ts 打开 TypeScript 工作台；检查／应用／回滚／停用作用于当前项目。切换项目会重启客体进程，重启不会自动启用 Hook。
- **测试**：填写 Java／Native 预期值并验证真实调用，结果写入 runs；没有断言时只记录观察。待适配应用执行安装包、类与资源预检，不启动目标游戏。
- **文件**：scripts／tests／config／assets／ai／runs／compatibility；文本限制 64 KiB，元数据只读，客体 runtime 数据有独立入口。
- **设置 → AI 服务**：所有项目共用接口、模型和加密密钥；打开设置不会切换客体项目。已有密钥会自动读取。
- **客体浮动按钮**：打开当前项目、文件、自动化和手柄面板。事件仍限于自己的客体窗口。
- **设置 → 开发者诊断**：宿主、客体、脚本接口和组件自检。自检会临时覆盖测试方案，请在诊断时主动运行。

结果保存在应用私有目录和应用外部专属目录 `Android/data/dev.tripoder.hooklab/files/last-result.json`；不包含账号或手机序列号。自检结束会卸载测试 Hook，保留命中计数。

开发时允许通过 ADB 安装、启动、读取本应用结果和截图，但这些步骤不是 APK 的运行依赖。部署不要使用 `adb root`、`pm grant`、`run-as`、外部 Frida attach 或辅助 shell 服务。

部分手机会弹出限时的 USB 安装确认，需要在安装阶段允许此 APK。应用安装后无需这项授权来执行 Hook。自动化验收从 `tripoder` 目录运行：

```sh
python3 android/scripts/verify-device.py --serial YOUR_DEVICE
python3 android/scripts/verify-device.py --serial YOUR_DEVICE --skip-install --offline
```

`--offline` 先安排 20 秒后的应用内自检，停止电脑的 ADB 服务 40 秒，并持续检查本机 5037 端口无监听；之后恢复 ADB 读取结果。它不修改手机 USB 调试开关，也不撤销已有电脑授权。`--skip-install` 适用于已安装当前构建的设备，脚本仍检查 APK 摘要以拒绝旧包结果。

## 实际拦截路径

1. 普通 Java 调用 `TestTarget.greet(String)` 原始返回 `original:phone`。代理通过 Java Bridge 修改该方法的实现，使同一调用返回 `hook:phone`。
2. Java JNI → `libhookbridge.so` → `libhooktarget.so!retry_delay_ms(int)` 原始计算 `base + 7`。Frida `Interceptor.replace` 改写函数入口，替换回调执行 SDK 的方案，结果为 `base + delayMs`。
3. Android 17 上对 Java 目标方法选择性 deopt，避免优化后的原函数调用重新进入 Hook；未对全进程 deopt。
4. `agent/index.ts` 复用 `src/plan.ts`、`src/parameters.ts`。实际安装 APK 摘要、当前进程实例、ClassLoader、ELF GNU Build ID 组成证据；Native 签名来自本项目 C++ 源码确认。
5. 自检先预热普通调用，然后验证启用、热更新、非法参数原子拒绝、回滚、1000 组连续调用、卸载恢复和重新安装，并比较 Native 函数入口机器码及真实回调计数。

目标 Java/C++ 函数没有读取 Hook 参数或开关。控制桥 `AgentBridge.dispatch` 原始实现只返回“代理未就绪”，不会伪造成功结果。Gadget 使用离线 `script` 模式，没有监听端口、外部控制器或 `frida-server`。

## 当前边界

- Android 后端只实现宿主和独立测试 APK 中预先约定目标的同步 `before`／`replace`／`after`、代码与参数更新；不提供任意方法或任意 Native ABI。
- 参数在代理串行控制路径中切换；尚未验证多线程并发、在途卸载、跨进程事务或长期压力稳定性。
- 应用内可修改代码和参数，AI 生成只产生草稿，用户点击部署才激活。支持标准 TypeScript 的受限语法子集，不是完整 TS 类型检查或操作系统沙箱。
- `source=guest-agent` 复用现有 SDK 枚举，实际位置为宿主或 `:guest` 进程；客体载荷可来自独立测试 APK，不代表已经存在客体 Android 系统。
- 未验证微信、钉钉、王者荣耀、原神，不能据此推断第三方应用、游戏保护机制或虚拟化兼容性。

技术依据：[Frida Gadget 离线脚本与 Android 打包](https://frida.re/docs/gadget/)、[Java Bridge](https://github.com/frida/frida-java-bridge)、[AGP 9.1 兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。

## 工作台、组件和断开 ADB 验收

```sh
python3 android/scripts/verify-device.py --serial YOUR_DEVICE --workbench --skip-install --offline --label workbench-v0.5.0
python3 android/scripts/verify-device.py --serial YOUR_DEVICE --component --skip-install --offline --label component-v0.5.0
python3 android/scripts/verify-device.py --serial YOUR_DEVICE --guest --restart --skip-install --label guest-v0.5.0
python3 android/scripts/verify-device.py --serial YOUR_DEVICE --project --skip-install --offline --label project-v0.5.0
```

工作台自检的 HTTP 响应由手机本地测试服务器提供，不代表真实模型连通。真实 AI 验证需在页面填密钥并生成，检查通过后再点击部署；详见 [AI 工作流](../docs/ai-workflow.md)。不要将密钥放入源码或构建参数。

自动化 JSON 仅支持 `tap`／`text` 和客体界面资源名；手柄配置为 Android 按钮键码到资源名的映射，如 `{"96":"action"}`。所有事件分发到自己的客体窗口，没有系统级输入注入权限。文件编辑限制在受控客体数据目录；Java Build 字段限 MODEL、BRAND、MANUFACTURER，不改变 Native 或宿主系统身份。
