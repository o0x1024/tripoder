# Tripoder 原生运行预览

2026-10-14。用户要求完善 UI/UX 并查看真实运行效果。本次把新 Runtime 从自动验收页接入一个可直接操作的原生 Android 入口。

## 信息结构

- **应用**：一个明确的自研应用体验按钮、当前空间、微信与王者荣耀的待接入状态。
- **空间**：创建与切换真实 Runtime 空间；显示真实安装记录与活动进程数。最多 8 个预览空间。
- **记录**：真实启动关联、客体返回、结束确认。只有身份校验、Activity 回执与进程停止都完成才显示「已完成」。系统返回 CANCELLED 不证明用户主动退出或应用正常结束，只显示「已返回，未提交计数结果」。
- **设置**：实际环境、数据边界和当前开放能力。普通启动不运行自动化验收。

使用同一套间距、字重、卡片、可点击区域与底部导航。正文与按钮以 sp/dp 排版，页面可滚动，处理系统栏与刘海安全区域。控件树与截图共同验收；控件存在不等于像素已经显示。

## 实际执行路径

`PreviewActivity → PreviewClient → P1Authority → RuntimeCatalog / ApkStore / ProcessSupervisor / ActivityLedger → 独立 CounterActivity APK → 实际 onActivityResult → completeActivity → stopProcess`。

体验应用无 Runtime 依赖，不被合并进宿主 DEX，也不安装成系统应用。通过生产 FD 安装协议验证并导入签名 APK。首次运行创建空间；后续运行校验该空间中的制品摘要。来源或版本不一致直接停止，不替换已有空间数据。

空间与安装信息由 authority 查询已有 SQLite 表，界面不建立另一套空间真相。UI 回执保存在私有 `preview-records`，与执行契约记录分开：PREPARING / RUNNING / RECEIVED / UNKNOWN 均不表示成功，未确认的记录阻止同空间再次启动。不能自动重放副作用，也不会回退到旧工作台或本机应用。

独立示例「空间计数器」使用正常 Android Activity、SharedPreferences.commit 和 recreate：点击实际保存，重新打开读取同空间数据；其他空间使用另一个数据目录。详情保留实际 PID、UID、路径、空间、安装摘要与挑战值。

## 从像素和触控验收发现的缺口

1. P0 验收协调器结束后未关闭 RuntimeStore，可能让后续 UI authority 无法取得唯一写锁。验收 finally 在停止 worker、生成报告后释放数据库和文件锁。
2. 原 Native 路径策略拒绝 GPU 设备，造成控件树存在但 Vulkan 画面黑屏。仅给当前已固定验证平台开放 `/dev/kgsl-3d0`，保留 Android UID/SELinux 检查，不开放 `/dev` 子树，不切换软件渲染。
3. Android 默认按钮点击经 ViewRoot 请求音效，GuestContext 的完整 Audio 服务尚未实现。新增受控窗口点击音效能力：保留 View 的启用/附着判断，只允许登记客体窗口的 CLICK，经平台 AudioManager 按系统偏好执行。客体 Context 仍不能取得通用 AudioManager；其他音效明确拒绝。

## 发布与边界

应用名 **Tripoder · 运行预览**，沿用 `dev.tripoder.runtimeprobe` 测试签名和包名。原 `dev.tripoder.hooklab` 工作台、项目和密钥不迁移、不清空。本轮不是 P5 工作台整合完成。

当前运行环境仍固定为已验证的 Xiaomi / Android 17 / ARM64。UI 逻辑分区不是恶意 Native 强安全隔离；完整 Audio、通用 GPU 平台适配、项目脚本整合、微信和王者登录仍未验收。没有使用 VirtualApp 代码。

构建继续使用 `android/scripts/build-runtime-probe.py`。真机证据与失败留存见 [UI 验收记录](evidence/ui-preview-2026-10-14/README.md)。
