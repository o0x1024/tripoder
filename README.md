# Tripoder

> **当前状态（2026-10-16）**：最终目标——微信、王者荣耀在 Tripoder 内正常运行，且 Java/Native Hook 正常工作——**尚未达成**。自研 Hook 核心与自有测试 APK 的验收已有真机证据；微信已进入真实 UI 但启动链仍在逐层修复，王者卡在 PendingIntent/Alarm，尚无稳定首屏。当前任务与下一步见 [task.md](task.md)，**现在的进程、包与控制结构见 [docs/current-architecture.md](docs/current-architecture.md)**，文档索引见 [docs/README.md](docs/README.md)。下方按日期倒序的段落是各阶段历史记录，其范围以各自日期为准。

**自研 Hook 切换（2026-10-16）**：正式 Runtime 源码和两套构建入口已改为单一自研核心库，客体首次执行前完成 25 项安装。存储已消除近地址跳转页依赖；自有真机 P0 60、包管理/门面 159、Java/Native P4 105 项通过。完整组件与工作台授权长流程仍在当前验收，具体逐制品状态见 [自研启动链](docs/self-hook-runtime-bootstrap.md) 与 [当前交接](docs/self-hook-handoff-2026-10-15.md)。下方旧 SDK/实验运行器及早期报告保留历史范围。

**应用与项目分离（2026-10-15）**：三列应用网格，单击直接运行，长按进入项目空间、选择运行空间或删除应用；一个应用可关联多个项目，删除应用保留脚本。最终开发包通过 34 项原生真机检查并已安装。[交互与验收记录](docs/evidence/app-actions-2026-10-15/README.md)。

**运行预览（2026-10-14）**：新 Runtime 已接入原生应用／空间／记录／设置四页，可直接运行独立自研计数器，验证触控、数据保存和空间切换。[界面说明](docs/ui-runtime-preview.md) · [真机验收与截图](docs/evidence/ui-preview-2026-10-14/README.md)。此预览沿用 Runtime 测试包；旧工作台已按用户授权卸载。独立 UID 新工作台已接入空间、原始 APK 导入、项目绑定和脚本保存，控制/安装/绑定 38 项及原生 UI 9 项通过；客体启动入口正在实施。微信、王者的原始 APK 已通过真机制品安装及非初始化 DEX 类加载，Application 启动、登录和完整 P1–P6 仍未完成。[最新逐构建证据](docs/evidence/runtime-p2-plus-2026-10-14/README.md)。

项目主线采用独立研发的应用虚拟化方案，参考公开技术思路，不使用 VirtualApp 代码。新的职责划分、复用范围及阶段验收见 [Tripoder 整体架构与改造路线](docs/tripoder-architecture.md)。以下已有能力属于旧 SDK 和自有 APK 实验运行器，不能等同于通用虚拟空间已交付。

2026-10-14：新共享 runtime 的限定 P1 已通过 **179 项真机断言**（内含 22 项迁移源检查），同构建 P0 回归 **57 项通过**；执行契约 **103 项**、制品 **35 项**、SDK **35 项**主机检查通过。已实现完整 APK 集合验签、单一安装权威、显式迁移与真实控制进程死亡恢复。这是历史构建的限定门槛；最新进展见逐构建证据。同 UID 不提供强隔离，P2–P6 尚未完成。见 [P1 审查与验收](docs/evidence/runtime-p1-2026-10-14/README.md)、[阶段方案](docs/tripoder-roadmap.md) 和 [复现说明](android/runtime/README.md)。

Android 上运行 Android 的研发项目。产品目标是：宿主零 Root、零 ADB 激活，在客体中运行微信、钉钉、王者荣耀、原神，并支持用户动态编辑 Java／Native Hook、自然语言生成方案、文件与环境控制、自动化和手柄映射。四款应用须在约定 Hook 测试集开启时验收。

**当前 0.5.0 是受控自有 APK 运行器，不是完整 Android 虚拟机。** 已实现手机上编辑标准 TypeScript、真实 Java／Native Hook、代码热更新与回滚、Chat Completions 接入，以及自有客体的 Application／Activity、资源、OpenGL、文件编辑、Java 设备字段、窗口内自动化和按键映射。只允许与内置摘要一致的自有测试 APK；不能运行任意 APK，也不能附加到系统中独立安装的其他应用进程。

0.5.0 改为应用／项目／设置三栏：应用网格末尾添加本机应用；项目下保存脚本、配置、测试、AI 对话、运行记录与客体数据。项目页面包含 Agent／脚本／测试／文件，切换客体项目会重启运行进程。Agent 当前支持生成并检查草稿，部署和测试仍由用户操作。已开始王者荣耀安装包与入口类／资源预检，尚未启动游戏。

构建和操作见 [Android 应用](android/README.md)，当前 UI 见 [0.5.0 项目界面与验收](docs/project-ui-validation.md)，游戏进展见 [王者荣耀兼容性](docs/hok-compatibility.md)，底层能力与缺口见 [0.3.0 真机验收](docs/workbench-validation.md)。历史 [0.4.0 UI](docs/ui-validation.md)、[0.2.0](docs/guest-validation.md) 和 [0.1.0](docs/android-validation.md) 记录保留。

以下保留此前 QEMU／完整 Android 后端的探索记录；该路线冻结，其历史证据不计入应用虚拟化主线的完成度。

完整 Android 隔离后端正在验证。当前运行器的跨应用请求可能进入宿主应用，不能保证微信授权留在客体。独立普通 APK 已改为从源码构建原生引擎，修复跨线程复用协程栈导致的 PAC 异常，真机协程回归已通过。客体已执行内核、init、Zygote 和 SurfaceFlinger；后续 SIGILL 已定位为 CMLE 指令编码错误并修复，客体已显示启动动画。完整日志随后定位了软件模拟超时与显示模式契约问题，已修正精确 60 Hz 虚拟屏幕，并将软件执行超时与低内存策略分离，正在重新验证完整启动。证据、后端判断及下一阶段门槛见 [隔离后端可行性](docs/isolation-backend-feasibility.md)，复现步骤见 [软件虚拟机原型](android/vm-probe/README.md)。产品尚未切换隔离后端或修复登录链路。

## 本地运行

需要 Node.js 24+、npm。

```sh
npm ci --ignore-scripts
npm test
npm run demo
```

`demo` 使用虚构测试应用和人工构造的元数据，演示返回值替换、参数热更新、非法参数拒绝和回滚。不会调用 ADB、启动手机应用、连接模型或修改真实进程。

## 标准 TypeScript 方案

```ts
import { definePlan, param } from "@abox/hook";

export default definePlan({
  id: "greeting",
  target: {
    packageName: "com.example.hookdemo",
    versionCode: "1",
    apkSha256: "a".repeat(64), // 仅为示例；真实部署须来自目标 APK
  },
  params: { message: param.string("你好", { title: "返回文字" }) },
  setup(ctx) {
    ctx.java.method("com.example.hookdemo.Greeter", "greet", ["java.lang.String"])
      .replace(({ params }) => params.message);
  },
});
```

没有新增语言语法。`definePlan`、`param` 和 Hook 操作均是普通库 API。完整 Java／Native 示例见 [examples/basic.hook.ts](examples/basic.hook.ts)。

动态参数通过 **每次回调的 `params` 快照**读取，避免一次调用的前后阶段混用不同版本。`parameterJsonSchema` 生成 JSON Schema。Android 工作台可编辑当前运行参数，类型和范围仍由执行后端校验。

## 已实现

- 参数类型、范围、默认值与未知字段检查。
- 目标包、版本、APK 摘要、进程实例与证据快照绑定。
- Java 方法、重载、ClassLoader、返回类型的证据匹配。
- Native 导出符号、Build ID、已确认签名与能力检查。
- 同步 `before`（返回新参数数组）、`replace`、`after`（返回新结果）。
- 单进程模拟器的版本切换、在途调用快照、参数更新、历史回滚。
- 明确的调用失败：before／replace 失败不执行原函数，after 失败不返回原结果，也不重放原函数。
- 有界事件和历史记录，区分等待目标、可模拟、模拟命中和模拟错误。
- AI 所需的筛选目标上下文、可配置 Chat Completions 地址／模型、Android Keystore 加密保存密钥、生成后检查和手动部署。
- 基于 Babel AST 的标准 TypeScript 子集解释器：不使用 eval，限制语法、调用步骤和数据大小。
- 手机编辑代码、检查、真实热部署、显式代码与参数回滚、错误记录。新的 Java 失败抛出异常，Native 失败终止所属进程。
- 独立自有 APK 的摘要校验、只读存储、专用 ClassLoader 与 JNI 加载。
- `:guest` 进程内真实 Hook、Binder 控制、系统文件选择器导入、显式重启及数据保留。
- 自有 APK 的 Application、Activity、XML 资源、OpenGL 绘制与像素回读。
- 客体数据文本编辑、客体进程 Java Build 字段设置／恢复、窗口内点击／文本自动化、手柄按钮到视图的映射。

## 尚未完成的核心目标

- 通用第三方 APK 引擎：系统包身份、组件路由、Service／Provider、多进程、权限、Split APK、完整数据路径和 GPU 兼容。
- 微信、钉钉、王者荣耀、原神在 Hook 开启时的完整功能与性能验收。首页记录用户添加的应用；安装包检查和入口加载预检不代表容器运行兼容性。
- 任意 Java 类／对象／构造器、Native 指针／结构体／地址 Hook、动态加载监听、多线程卸载和跨进程发布。
- 完整 TypeScript 运行时／类型检查、操作系统级脚本隔离；当前客体共享宿主 UID。
- Native／系统设备身份虚拟化、通用游戏画面坐标／多点触控／摇杆映射。当前实体手柄尚未验证。

SDK 的 Node.js 模拟器可以执行普通 JavaScript 回调，不能直接 import 或 eval 不可信模型源码。Android 工作台通过 `src/draft.ts` 的受限 AST 解释器处理草稿；解释器的检查也不是操作系统隔离证明。

本次失败契约已更新实现和自检断言；新的 Java 异常传播、Native 失败终止尚需真机验收，历史报告不能代替当前制品验证。

详见 [架构与执行契约](docs/architecture.md)、[AI 工作流](docs/ai-workflow.md)、[设备基线](docs/device-baseline.md)。
