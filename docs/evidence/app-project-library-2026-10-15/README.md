# 应用与项目分离：原生界面验收

2026-10-15。最终构建 `run-03` 的 **14 项真机检查通过**，已安装到 Xiaomi / Android 17 测试设备。工作台 UID 10401、引擎 UID 10400。源码、设备安装包与构建回执摘要一致；本轮两个 UID 的崩溃日志未发现 Java Fatal Exception 或 Native Fatal Signal。

## 产品行为

- 应用页为深蓝色三列网格，显示从安装 APK 读取的名称、图标及项目数，末尾是添加应用。
- 一个包名显示一张应用卡片；不同空间的安装归于同一应用。点击卡片进入该应用的项目列表。
- 项目页显示全部项目。新建项目必须明确选择应用及空间；提交现有 `bindProject`，不另建关系表或安装数据源。
- 添加页分手机应用与 APK 文件来源。当前每次选择一个应用，角标与底部安装计数同步。安装只添加应用，不自动创建项目。
- 本机来源复制完整 base/split APK 集合，经原有只读 FD 安装接口提交。图标查询只读取引擎内已验证 APK 资源，不启动宿主应用或加载客体代码。
- 项目脚本分别保存；同一空间、同一应用的数据由这些项目共享。独立应用数据需使用不同空间。
- 旧未绑定项目保留在项目总览；升级失效绑定仍需显式重新确认。未知操作不自动重放。

## 证据

- [最终结果：14 项](run-03/library-ui-result.json)
- [设备与安装摘要核对](run-03/device-observation.json)
- [构建回执](run-03/build-receipt.json)
- [最终摘要检查](verification-summary.json)
- [首页最终截图](home-final.png) / [控件树](home-final.xml)
- [应用下的项目](run-03/control-a7109e6c0d194679-application-projects.png)
- [项目总览](run-03/control-a7109e6c0d194679-project-index.png)
- [添加应用与选中角标](run-03/control-a7109e6c0d194679-add-app-selection.png)

`run-01` 保留首次菜单定位失败。产品首页通过直接启动及控件树检查可正常显示；验收改为显式打开工作台再定位控件，`run-02` 的 12 项通过。`run-03` 补充生产导入路径与临时副本释放断言，并修正添加页显示此前绑定消息的问题。各轮绑定各自构建，不相加。`run-03` 自动首页截图仍处于异步图标加载阶段，最终视觉检查使用 `home-final.png`。

## 验收范围

真实原生控件创建两个项目、保存 A 的脚本、确认 B 未改变，并核对两个项目绑定同一安装代次。APK 导入经过工作台生产导入器及引擎落库。添加页的手机应用选择、禁用态和 APK 来源入口已验证；未实际安装本机第三方应用，未验证其 split 集合导入或业务运行，也没有启动微信、王者荣耀。此结果不是完整 P5/P6 验收。

复现：完成 `android/scripts/build-workbench.py` 构建后，运行 `rtk proxy python3 android/scripts/validate-workbench-control.py --serial DEVICE --phase library-ui --install --evidence NEW_DIRECTORY`。验收会新增一个自研计数器安装空间和两个测试项目，保留已有数据。
