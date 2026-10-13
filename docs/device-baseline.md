# 首批设备基线

来源：2026-10-13 本会话已授权的 ADB 只读查询。以下是设备属性上报与包管理器结果，不是厂商规格核验或 Hook 可行性结论；后续测试应重新读取版本和可用资源。不保存设备序列号。

| 项目 | 记录 |
|---|---|
| 上报名称／型号 | Xiaomi 18 Pro Max／M154FF |
| 代号 | madrid |
| 系统 | Android 17，API 37 |
| 固件 | OS4.0.24.0.XEOCNXM |
| SoC／GPU | Qualcomm SM8975／Adreno 850 |
| ABI | arm64-v8a，无 32 位 ABI |
| MemTotal | 15424304 kB，约 14.7 GiB |
| 页大小 | 4096 字节 |
| AVF feature | true |
| SELinux／启动状态 | Enforcing／green／locked |

虚拟化权限定义：MANAGE_VIRTUAL_MACHINE 为 signature|development|preinstalled，USE_CUSTOM_VIRTUAL_MACHINE 为 signature|development。声明支持 AVF 不代表普通安装 APK 自动获得这些权限。开发期允许用 ADB 读取信息，不改变最终产品零 ADB 激活的要求。

| 应用 | 本次查询 |
|---|---|
| 微信 com.tencent.mm | 8.0.79，versionCode 3200，ARM64 |
| 王者荣耀 com.tencent.tmgp.sgame | 12.1.1.1，versionCode 1201010103，ARM64 |
| 钉钉 com.alibaba.android.rimet | 未发现 |
| 原神国服／国际服常见包名 | 未发现 |

未查询用户消息、账号、游戏存档或登录令牌。SDK 测试使用完全独立的虚构应用与符号，不包含四款目标应用的内部方法假设。
