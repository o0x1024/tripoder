# 网易云音乐真机 MCP 验收

设备 d861a4fa，Android API 37，网易云音乐 9.6.05（9006005），APK SHA-256 `b94c88cdacbf493e76c232cba34db72935c8b6e20059cf7521bf58f65d793f24`。应用启停、探针、对象查询、快照与 Hook 均由独立真实 stdio MCP 客户端执行。安装和系统日志核对使用 ADB。没有执行会员、鉴权、支付或网络响应修改。

## 结果

35 项网易云检查通过，详细回执摘要见 validation.json、summary.json 和 mcp-metadata.json。完整 RPC、界面、APK、反编译内容及导出 DEX 保存在忽略的私有目录。

| 验收项 | 证据 |
| --- | --- |
| 启动前探针 | 沿运行时继承链找到真实的 TinkerApplication.onCreate；首次回调位于 main 线程，阶段 APPLICATION_ATTACHED，单调时间早于 APPLICATION_CREATED 事件 |
| 运行时 DEX | 根 PathClassLoader 登记 20 个真实 ART DEX 对象，与当前 APK 的 DEX 容器数量/大小匹配，登记发生在 Application 构造前 |
| 动态加载器 | 从网易云自身已发现的 71,812 字节 DEX 创建受控 InMemoryDexClassLoader 子加载器，origin=constructor，证明构造探针捕获；子加载器拥有独立 DEX 引用，重复操作不重复创建 |
| 代码快照 | 根 DEX、子加载器 DEX 与业务 classes6.dex 均通过 MCP 分块下载和完整 SHA-256 校验；业务 DEX 为 8,990,464 字节，由 Hook 脚本触发采集 |
| 返回值 Hook | 主动调用客体实际 NeteaseMusicUtils.F(float,float,float,float)，输入 [0,0,3,4]；原值 53，Hook 后 135，停用后恢复 53，命中与原始返回值由回调事件佐证，错误为 0 |
| 恢复 | 已停用所有测试探针和脚本，回归后重新打开网易云首页；新进程 installedHooks=0、scans=0、pendingCaptures=0、loadedBytes=0，Hook 状态 IDLE，JIT/AOT 保留，固定绑定 1021 |

本次页面自然路径只观察到根加载器，受控子加载器实验不计作网易云自然插件或解密路径。快照字节与该版本 APK 的对应标准 DEX 一致，不是方法体重建或完整脱壳验收。返回值实验覆盖客体主动调用路径，不承诺已有 AOT/内联调用者全部迁移。

## 修复和补充

1. 高频方法的事件环会覆盖启动早期记录。新增 firstInvocation，保留首回调运行阶段、线程、进程代次和时间；上下文仅采样一次，热调用继续只维护原有计数。最终验收使用真实 onCreate，避免对高频 getter 持续记录事件。
2. 类内省补充 superClass、接口和实际定义加载器，Agent 可以沿继承链找到实现方法，并识别父加载器委托结果。
3. 新增通用 classloader_create，输入为已发现的 DEX 和明确父加载器；保持应用原加载器、按预算创建直接只读内存缓冲区，不初始化载荷类。动态事件标记发现来源，避免把查询登记当作构造回调。

新工具和字段没有应用名称判断；网易云专用选择器仅存在于验收脚本中。

## 性能和回归

默认 thin 无新增探针、后台扫描或采集线程，preserveCompilation=true、claims=0、fixedBindings=1021。客户端 process_start + target_select 三次耗时：基线 1676/1238/1227 ms，恢复后 1338/1353/1319 ms。样本量及负载没有构成严格原生性能或 FPS 对照，均值和中位数均保留在 validation.json。

修复后的通用探针 37 项、控制面 71 项、原 MCP Hook 20 项、Java 脚本契约 726 项、桌面 MCP 12 项通过；TypeScript 类型检查通过。本轮进程 UID 的 crash 缓冲区未发现 Java/native fatal。源码与 APK 均匹配保存的构建回执。

本轮使用独立 MCP 客户端，没有把结果称为 Sentinel 桌面模型实际调用验收。
