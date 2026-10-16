# H1 真机通过证据

日期：2026-10-15。运行身份：`788d110d-b812-43ff-a06e-8077b826acba`。用户允许安装后，重试同一份已验证 APK；保留此前独立的安装拒绝记录。

| 项目 | 结果 |
|---|---|
| 主机共享核心检查 | 92/92，ASan/UBSan 通过 |
| NDK LLVM 汇编对照 | 38/38 通过 |
| 独立 APK 安装 | 成功 |
| 设备进程 | non-debuggable，UID 10403，PID 31296，untrusted_app SELinux 域 |
| 平台 | 固定 fingerprint，API 37，ARM64，4096 字节页，libart/libc/linker SHA 一致 |
| 设备共享核心检查 | 92/92 通过 |
| 合成页检查 | 16/16 通过，包括 RW→RX、七个实际指令执行断言、释放 |
| 来源与回执绑定 | 当前源码、主机被测源码、buildId、APK/Native SHA、runId、maps SHA 一致 |
| 探针 Frida/Gadget/Gum 映射 | 无 |
| 真实 ELF Hook / ART / 产品迁移 / 硬件 BTI 强制 | 本阶段未实现或未验证 |

APK SHA-256：`dd5fd2e8a3d4057676f9dc8cf9ebc1b8bf5d4a4e9df0aa8bca4ba8be1da260c7`。

源码集合摘要：`09419ea552f4bcd9eb0832ecc91bea401487b8179b4d45b0c75c5cd07db4d637`。

- [最终核验回执](verification.json)：核验脚本成功退出并排他创建。
- [设备结果](device/result.json)、[完整进程映射](device/maps.txt)。
- [主机检查](host-report.json)、[构建回执](build-receipt.json)。
- [Native 依赖](native-dynamic.txt)、[ELF notes](native-notes.txt)、[签名检查](signature.txt)。

启动命令：`adb -s d861a4fa shell am start -W -n dev.tripoder.selfhookprobe/.ProbeActivity --es runId 788d110d-b812-43ff-a06e-8077b826acba`。返回 `Status: ok`、`LaunchState: COLD`。APK 普通进程执行检查，ADB 仅用于安装、启动和收集结果。

H1 通过仅证明静态规划器和新建匿名代码页执行能力。没有修改活跃 ELF 代码，没有安装真实 Hook，没有验证 ART 或改变现有产品依赖。
