# Tripoder 实施与验收状态

更新时间：2026-10-15 07:48。**仍在实施，P2–P6尚未全部完成，微信与王者荣耀未通过业务验收。** 通过项均绑定具体构建，不能把不同构建、原生基线和自研夹具的断言相加作为完整产品验收。

## 已交付

- [整体架构](tripoder-architecture.md)、[分阶段路线](tripoder-roadmap.md)、[执行契约](execution-contract.md)和各运行能力契约已落库。
- 工作台/项目/脚本通过独立控制端接入新引擎；Tripoder不内置AI，外部Agent使用MCP。
- 自主MCP闭环已多轮实测：观察、草稿、设备审批、激活、两次真实方法命中、停用恢复、会话撤销。后续测试不依赖Sentinel。
- 自研运行器已有安装/空间/四类组件的限定切片、文件与包资源、系统服务观察、Java/Native Hook及失败证据；这些不是通用Android兼容性或强安全隔离的承诺。

## 本批新增证据

| 能力 | 通过证据 | 边界 |
|---|---|---|
| 自定义Instrumentation创建 | 原生三种模式各20项；容器75项，见[创建验收](evidence/runtime-p2-plus-2026-10-14/creation-runtime-02/device-observation.json) | 直接构造、super、super后替换、私有主题、真实整数结果；单次launch中途替换Instrumentation已在后续91项中覆盖 |
| Application attach时跨进程Service请求 | 容器13项，实际请求约149.47ms返回，见[Service验收](evidence/runtime-p2-plus-2026-10-14/service-startup-runtime-02/device-observation.json) | 系统真实启动/绑定/Binder/停止；不带AUTO_CREATE已另行完成PASSIVE契约，见下表；完整竞争及UNKNOWN资源生命周期待补 |
| 包域自身ActivityInfo | 原生136项、容器144项，见[包域验收](evidence/runtime-p2-plus-2026-10-14/activity-info-runtime-02/device-observation.json) | 别名、禁用、另一声明进程、元数据、flags和防御复制；AUTO标志和展开组合通过；锁定启动前状态未验证 |
| 产品外部MCP | 18项协议/12项设备，见[MCP验收](evidence/runtime-p2-plus-2026-10-14/service-startup-agent-01/device-observation.json) | 自主客户端真实闭环，不代表目标应用业务或P5全量完成 |
| 缺失Provider语义及Service调度标志 | [包域与Provider容器154项](evidence/runtime-p2-plus-2026-10-14/provider-absence-runtime-01/device-observation.json)、原生146项；[Service容器13项](evidence/runtime-p2-plus-2026-10-14/service-flags-runtime-01/device-observation.json)、原生10项 | 缺失查询返回空、写入/call/batch抛参数异常，权限或执行失败不吞掉；实际绑定AUTO_CREATE + NOT_FOREGROUND + WAIVE_PRIORITY通过 |

| 非创建Service绑定 | [原生12项](evidence/runtime-p2-plus-2026-10-14/service-passive-baseline-01/device-observation.json)、[容器15项](evidence/runtime-p2-plus-2026-10-14/service-passive-runtime-01/device-observation.json) | PASSIVE只保留组件，不启动进程；实际start后绑定原连接，并验证停止/销毁；完整死亡与竞争未验收 |
| Shortcut能力发现 | [原生147项](evidence/runtime-p2-plus-2026-10-14/shortcut-capability-baseline-01/device-observation.json)、[容器159项](evidence/runtime-p2-plus-2026-10-14/shortcut-capability-runtime-01/device-observation.json) | 独立能力门面如实报告pin不支持，其余未实现操作拒绝；未实现桌面Shortcut发布 |
| MCP关闭终态 | [MCP18项/设备13项](evidence/runtime-p2-plus-2026-10-14/service-passive-agent-02/device-observation.json) | 验收工具等待桌面确实收到CLOSED后再停端点；01轮EOF失败证据保留 |

新增专项：Activity SINGLE_TOP原生8/容器18项；数据快照原生11/容器39项；列表原生12/容器43项；同次launch替换Instrumentation原生三种模式各21/容器91项；广播发送端权限原生15/容器48项。每批是独立构建和范围，见[逐构建索引](evidence/runtime-p2-plus-2026-10-14/README.md)，不能相加为产品总通过数。

## 尚未验收完成

1. 微信第32次已进入LauncherUI.onStart；首次失败为带接收权限限制的广播。SINGLE_TOP、类型化extras/数组/列表及启动中替换Instrumentation已修复并有对照证据；权限广播原生15/容器48项通过。第37次两个内部Welcome入口精确关闭，首次收到实际Activity创建完成回执；后台随后因BATTERY_CHANGED未准入退出，整体仍失败。空Receiver过滤器及非sticky电池广播原生19/容器54项、有限嵌套Intent数据原生20/容器60项通过。真实sticky读取/初次回调/goAsync完成及嵌套Intent再次启动已在新构建原生25/容器68项通过；第38次目标正在做连续30秒存活验收。尚未到登录。
2. 王者荣耀仍缺JobScheduler调度账本、真实JobService生命周期及恢复；目前只有JobInfo转换契约15项通过，系统提交数量为0。
3. 历史未附着/未知Activity资源的最终退休未解决，probe六个历史pin阻塞完整P2回归。未清数据、增大槽位或改写UNKNOWN来绕过。
4. URI/PendingIntent、完整Service绑定模式、split运行、长期压力与目标业务场景仍有缺口；具体门槛见路线文档。
5. 同UID进程内Native代码不具备恶意APK强隔离能力，限定的身份/路径适配不能等同独立Android系统。

## 下一步

继续微信固定APK的首屏链路；补冷恢复/关闭入口的回归与资源退休协议，再完成JobScheduler实际调度和王者启动。到登录时由用户接管，之后逐项完成业务与性能验收。所有失败原文、APK/源码摘要和设备回执见[逐构建证据索引](evidence/runtime-p2-plus-2026-10-14/README.md)。

本项目独立实现，未引入VirtualApp代码；能力缺失明确失败，不切换旧后端或宿主应用执行。
