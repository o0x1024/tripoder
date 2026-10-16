# 应用单击运行与长按管理验收

2026-10-15，同一最终开发构建通过 **20 项新交互检查 + 14 项一对多界面回归，共 34 项原生真机检查**，开发包已安装到连接设备。没有提交或推送 Git。

## 交互

- 单击应用图标：按安装记录直接启动真实 MAIN/LAUNCHER 入口，不创建项目，不选择某个项目代替应用。
- 长按：进入项目空间、选择运行空间、删除应用；长按本身不触发启动。
- 多个空间：用户选择运行空间并记住，后续单击直接运行。
- 删除：明确确认所选空间，停止其进程，验证任务属于该安装后移除保留任务，再删除应用及数据。项目、脚本和其他空间保留；旧绑定标为 STALE。
- 最后一个安装删除后，应用网格不再由残留项目绑定生成图标，项目仍能从项目页进入。

## 证据

- 新交互：[20 项结果](run-14/app-actions-ui-result.json)，运行 `control-fd73d586cebb4fe7`。
- 一对多回归：[14 项结果](library-regression/library-ui-result.json)，运行 `control-5f75f2c5cff646e7`。
- [设备与安装校验](run-14/device-observation.json)、[构建来源](run-14/build-receipt.json)。232 个来源文件摘要与当前文件一致，安装 APK 摘要与构建回执一致。
- 两轮引擎崩溃日志均无 Java fatal / native fatal，补充的 [工作台崩溃日志](run-14/workbench-crash.txt) 为空。
- 设备为 Xiaomi Android 17；工作台 UID 10401，引擎及客体 UID 10400。该结果不代表客体之间具有独立系统 UID 隔离。

| 制品 | SHA-256 |
| --- | --- |
| workbench | `dd39ae9e742ebe88d97c145490ebb6d439f9889fc77187aba8b21b7d6821f45b` |
| engine | `dce93558730102d5bb0c40acc7618fa2b2d2dc63781e027eab54422219c26583` |
| controlTest | `a2e425a2242a329ab87904a93891c9c0ed3a4166924ad09b5ca718ccb43d5660` |

## 截图

[长按菜单](run-14/control-fd73d586cebb4fe7-long-press-menu.png) · [直接启动](run-14/control-fd73d586cebb4fe7-direct-launch.png) · [删除确认](run-14/control-fd73d586cebb4fe7-delete-confirmation.png) · [保留项目](run-14/control-fd73d586cebb4fe7-retained-project.png)

## 范围与保留状态

本轮使用独立、自有的 launcher counter APK，经真实工作台界面、签名 Binder、一次性 Gateway 和客体 Activity 执行；验证退出后重启、空间数据独立、后台停止删除和脚本保留。没有启动宿主中的同名应用，也没有验证微信或王者等第三方业务。无 launcher 或多个 launcher 明确拒绝，不改成启动任意 Activity。运行中已有根任务的重复点击仍受原 Runtime 的根任务限制，本轮没有增加任务重新置前台功能。

设备原有用户待确认操作 `03415541-485f-400c-a499-e3a08c4530aa` 仍保留。测试在独立的持久操作日志中运行，没有清除用户原日志或修改未知结果；因此普通工作台中的新操作仍会受到原待确认记录限制。下一步应针对原项目查询回执与入口/资源证据，再通过已有恢复流程处理，不能重放未知启动。

早期未通过的轮次完整保留，不计入验收。修正了测试截图/页面切换时序，以及失败回执格式和后台保留任务导致卸载拒绝两个实现问题。最终产品不包含临时线程诊断；验收截图由系统 screencap 保存。
