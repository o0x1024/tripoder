# Tripoder Shared Runtime (`android/runtime`)

本模块实现 Tripoder 的「第一层：统一最小承载底座（Level 1 Skeleton Baseline）」与「第二层：按需扩展能力（Opt-in Capabilities）」，同时用于正式产品包（`dev.tripoder.engine`）与真机验收套件。

权威架构与契约文档见：
- **[总体架构 (`docs/current-architecture.md`)](../../docs/current-architecture.md)**
- **[单运行时与最小底座收敛 (`docs/single-runtime.md`)](../../docs/single-runtime.md)**
- **[Runtime 虚拟化设计 (`docs/runtime-design.md`)](../../docs/runtime-design.md)**
- **[执行契约与状态机 (`docs/execution-contract.md`)](../../docs/execution-contract.md)**
- **[架构梳理与分阶段实施计划 (`docs/implementation-plan.md`)](../../docs/implementation-plan.md)**

---

## 核心边界

1. **安装与物理执行副本**：在设备端使用官方 `apksig` 校验 APK/Split 集合、签名、ABI、Manifest 与制品摘要；[`RuntimeExecutionCode`](src/dev/tripoder/runtime/RuntimeExecutionCode.java) 生成只读运行副本并保留 `oat/odex` 编译缓存，[`RuntimeSharedLibraries`](src/dev/tripoder/runtime/RuntimeSharedLibraries.java) 与 [`RuntimeDexLoader`](src/dev/tripoder/runtime/RuntimeDexLoader.java) 使用真实解压目录 `info.nativeLibraryDir` 建立客体 `PathClassLoader` 的 linker 搜索路径。
2. **8 个预声明槽位与权威控制面**：`:authority` 进程维护唯一 SQLite 状态机与组件租约，`:slot0..7` 每个槽位同一时刻绑定唯一 `(spaceId, packageName, processName, installGeneration)` 不可变应用身份。
3. **Level 1 最小底座（默认开启）**：
   - **Java / Framework**：[`RuntimeSelfHookProduct`](src/dev/tripoder/runtime/RuntimeSelfHookProduct.java) 与 [`RuntimeFixedPreparation`](src/dev/tripoder/runtime/RuntimeFixedPreparation.java) 仅安装 ~220–264 项最小容器骨架绑定（启动期一次发布，100% 保留 ART JIT，移除 12 个系统 Manager 家族的全量固定 Hook），动态广播经 [`RuntimeReceiverProxy`](src/dev/tripoder/runtime/RuntimeReceiverProxy.java) 显式转发（运行期主线程 `toggleBatchNative = 0`），跨组件 `Parcel` 经 [`RuntimeGuestPayload`](src/dev/tripoder/runtime/RuntimeGuestPayload.java) 保真客体 `ClassLoader`。
   - **Native / Libc**：[`self_hook_storage.c`](native/self_hook_storage.c) 默认仅激活 `fixed_libc` 的 10 个核心文件入口（共用 [`path_policy.c`](native/path_policy.c)），默认 `addedSeccompFilters = 0`，不抑制 `debuggerd`。
4. **按需扩展能力（Opt-in）**：动态业务 Hook（`startupHook` / `hook_activate`）、ClassLoader/DEX 快照（[`RuntimeCodeProbes`](src/dev/tripoder/runtime/RuntimeCodeProbes.java)）、被动 TLS 明文抓包、设备画像模拟（[`RuntimeSandboxPersona`](src/dev/tripoder/runtime/RuntimeSandboxPersona.java) / [`RuntimeEnvMock`](src/dev/tripoder/runtime/RuntimeEnvMock.java)）及强对抗沙箱策略（[`syscall_namespace.c`](native/syscall_namespace.c)）仅在显式请求时挂载。

---

## 构建与测试

```sh
# 运行 Runtime 与 Self-Hook 主机回归测试
python3 android/runtime/self-hook-tests/run.py
python3 android/runtime/test/ApplicationProjectsSchemaTest.py
python3 android/runtime/test/CatalogMetadataHostTest.py

# 构建工作台与引擎 APK
python3 android/scripts/build-workbench.py \
  --build-dir android/product/build-level-one \
  --sdk /Users/len/Library/Android/sdk --platform android-37.0 --build-tools 37.0.0 \
  --java-home /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  --ndk /Users/len/Library/Android/sdk/ndk/30.0.16248370 \
  --platform-libc android/runtime-probe/build/device-libc.so \
  --platform-art android/self-hook-probe/build/device-libart.so \
  --platform-linker android/self-hook-probe/build/device-linker64 \
  --package dev.tripoder.engine
```
