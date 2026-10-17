# Tripoder shared Runtime

这份源码同时用于正式独立 UID Engine 和自有验收 APK。`runtime-probe` 只编排断言，Application 装载、Context、Binder、组件路由、SQLite 和执行状态机均位于本目录。2026-10-16 已切换单一自研 Java/Native 核心，客体首条代码前安装25项/2177绑定；完整组件与控制长流程仍在验收，见 [当前证据](../../docs/self-hook-handoff-2026-10-15.md)。不存在验收专用的第二套装载引擎。

## 边界

- 安装层在设备上使用官方 apksig 验证完整 base/split 集合与签名、ABI、Manifest、ZIP 和所有制品摘要；128 MiB/包、256 MiB/集合、最多 64 包。不支持 v1-only、非 ARM64 库和 ZIP64。运行层目前仍只执行自有单 base APK；没有重写 Dex/Manifest，也不依赖 Tripoder SDK。
- P1 的独立 authority 进程、进程内登记/系统文件锁、永久关闭门禁、SQLite 事务、代次保护与显式项目迁移见 [P1 设计](../../docs/runtime-p1-design.md)。DEX 使用有界 InMemoryDexClassLoader，制品目录仍严格只读。
- `RuntimePlatform` 锁定已测 Xiaomi Madrid Android 17 / API 37 / ARM64 / 4096 页及完整 build fingerprint。其他固件明确拒绝；不能只改门禁就宣布支持。
- 八个 Manifest worker 进程槽，一槽一个不可变应用身份；真实 Application、Activity/Service/Provider/Receiver、XML/asset、JNI。物理组件/任务租约与进程死亡分别处理，不清除 UNKNOWN 来回收槽位。
- Java 文件 Context 按空间归属；十个固定 libc 入口使用同一 C 路径策略，处理已约定绝对/相对路径和FD语义。external files/cache 是空间内目录，不模拟完整共享外部存储；直接 syscall 不在拦截保证内。
- UI、输入、显示、无障碍、Autofill/ContentCapture、UiMode 和已测 OEM parental-control 服务是明确的宿主能力；Settings system/secure/global 仅只读。它们不是独立虚拟系统状态。保留平台家长控制，未关闭或绕过。
- 组件、包域及十类系统服务仅开放已明确实现的语义；未知能力拒绝，支持清单和具体契约见 `docs/runtime-*-design.md`。尚未覆盖完整 Android API 面；直接 Binder/Native/反射绕过不在强安全保证内。
- 同 UID 客体可以通过 Native 读到宿主合成标记。不同进程与目录不构成强隔离。

## 构建和复现

从仓库根目录执行。依赖：Node 24、npm lock 对应编译依赖、JDK、Android SDK platform/build tools 37、NDK r30，以及从已锁定固件取得的 libc/libart/linker64 实物。构建逐文件验证系统二进制、固定模板与导出；缺工具或身份不匹配直接退出。产品不构建、包装或加载 Gadget、旧JS adapter 或独立 tripoderio 库。

本机已验证命令：

```sh
rtk proxy python3 android/scripts/build-runtime-probe.py \
  --sdk /opt/homebrew/share/android-commandlinetools \
  --platform android-37.0 --build-tools 37.0.0 \
  --java-home /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
  --ndk android/vm-probe/build/native/tools/android-ndk-r30 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64

rtk proxy python3 android/scripts/validate-runtime-probe.py \
  --serial d861a4fa --phase p0 --install \
  --evidence docs/evidence/runtime-p0-new-run
```

P1 使用相同构建和验证器，将 `--phase p0` 改为 `--phase p1`，并指定新的证据目录。P1 真正终止自有 authority 进程并重新打开 SQLite，故障注入仅存在于探针子类；公开控制 API 没有故障注入命令。

正式工作台使用 `android/scripts/build-workbench.py` 和同一组 SDK/JDK/NDK/平台参数，生成单一产品包 `android/product/build/tripoder.apk`（工作台 + 引擎，包名 `dev.tripoder.engine`）和 `tripoder-control-test.apk`。它只含自研核心库，探针 JNI 和验收控制器不进入 Engine。`validate-workbench-control.py` 的 `control`、`hook-ui` 与 `validate-agent-control.py` 的 `normal`、`disconnect` 分别验证控制、真实 UI、手机审批与断连回执；它们不等于第三方应用业务或外部模型验收。

NDK 路径仅复用已经下载的工具链，不链接 VM 研究的代码或制品。换机器时显式提供其 SDK/JDK/NDK 路径。

安装的是 `dev.tripoder.runtimeprobe` 与系统同包诱饵 `dev.tripoder.p0.b`；手机需允许安装。不要在系统安装 A，也不要手动运行诱饵 B。验证器保留诱饵启动标记作为反证，遇到标记不会自动删除。已有 `dev.tripoder.hooklab` 不被替换。ADB 只安装、启动并收集本测试的证据；运行过程无需 ADB 激活、Root、授权脚本或远程 attach。

产物在被 Git 忽略的 `android/runtime-probe/build/`：自有 A/B APK、runtime-probe.apk、独立签名报告、源文件与制品摘要。测试签名密钥仅供本地验收，不能用于发布。APK 重建时 ZIP 时间戳可能改变摘要，验证器要求源码、安装包和证据来自同一次构建。

验证器从一次冷启动生成唯一 run ID，并检查宿主/客体 SHA、全部设备断言及宿主诱饵未启动。异常、超时、文件缺失或错误构建都非通过。它只验证本 README 列出的能力，不是 APK 通用性测试器。

执行契约主机检查见 [test/README.md](test/README.md)。设备报告见 [P0 验收](../../docs/evidence/runtime-p0-2026-10-13/README.md) 与 [P1 及 P0 回归](../../docs/evidence/runtime-p1-2026-10-14/README.md)。

## 来源记录

本轮新增 Java/TypeScript/Python/C fixture 源码独立编写，基于 Android SDK API、已连接设备行为和本项目既有类型/接口；没有引入、移植或重命名 VirtualApp 实现。`DeniedPackageManager` 使用本项目既有 PackageManager 子类的 API 签名，方法默认拒绝。

复用的第三方依赖仍独立记账：

| 输入 | 固定信息与证据 |
|---|---|
| 自研 Java/Native 核心 | 源码 `android/self-hook`，产品编译/模板/依赖/导出/系统实物SHA和单份IO策略见 build-receipt.json 的 selfHookNative；只依赖 libc/libm/libdl |
| esbuild / TypeScript | 0.28.2 / 7.0.2；npm lock 和本地包元数据分别为 MIT / Apache-2.0；构建工具 |
| Android apksig | 从 SDK build-tools 37.0.0 的 apksigner.jar 只抽取 com/android/apksig；Apache-2.0，SDK 许可证和 attribution 见 `licenses/`，打包到探针 assets；原始 jar 与抽取 classes 的 SHA 在构建回执 |
| Android SDK/NDK | 构建回执记录 platform/build tools 与 NDK `source.properties`；使用已安装工具链 |

这是研发来源记录，不是发布许可证审计。当前使用开发签名；正式发布需独立审核完整依赖与 notices/SBOM。旧 hooklab 实验运行器及其 Gadget 依赖记录保留在 `android/README.md`，不属于上述正式 Runtime 构建。
