# H1 首批证据

日期：2026-10-15。运行身份：`67663c4a-9e21-473a-94f1-bfcd9990d92b`。

| 项目 | 结果 |
|---|---|
| 主机共享核心检查 | 92/92，通过 ASan/UBSan |
| NDK LLVM 独立汇编对照 | 38/38 通过 |
| 独立 APK 构建、打包清单、Native 依赖、签名 | 通过 |
| 手机安装 | 拒绝：INSTALL_FAILED_USER_RESTRICTED |
| 设备核心检查、合成页执行、运行时 maps | 未运行 |
| 真实 Native Hook / ART / 产品迁移 | 未实现 |

APK SHA-256：`dd5fd2e8a3d4057676f9dc8cf9ebc1b8bf5d4a4e9df0aa8bca4ba8be1da260c7`。

源码集合摘要：`09419ea552f4bcd9eb0832ecc91bea401487b8179b4d45b0c75c5cd07db4d637`。

- [主机检查](host-report.json)：独立执行身份、被测源码摘要、编译器和每项汇编对照。
- [构建回执](build-receipt.json)：输入源码清单、构建身份、APK/Native SHA 与打包条目。
- [Native 依赖](native-dynamic.txt)、[ELF notes](native-notes.txt)、[开发签名验证](signature.txt)。
- [安装拒绝记录](installation.json)：ADB 退出码 1，设备没有启动本次探针。

没有 `device/result.json` 或 `verification.json`，不能将本目录表述为设备验收通过。允许安装后应创建新 runId 和新证据目录，保留本次失败记录。
