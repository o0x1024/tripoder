# 独立 isolated UID / FD 能力探针

仅测试此 APK 自己创建的只读 SO、DEX 和资源 APK。每个 case 使用新的真实 `isolatedProcess=true` 实例；回调同时验证内核 Binder UID/PID、challenge 和文件摘要。读取、R 映射、RX 映射、FD dlopen/函数调用、显式 FD 依赖、内存 DEX 执行、FD 资源读取分别保留结果。直接打开 owner 私有路径必须失败。

不改变权限、不清应用数据，不改走路径或普通 UID。进程退出或观测超时记 UNKNOWN。成功仅证明这些自有制品的底层能力，不证明 Chromium renderer、目标 APK 或完整隔离已实现。

v7 把 ART/JNI 的有、无 SONAME 分成独立进程对照。`ART_JNI_APK_*` 使用封存 MFD_EXEC APK：SO 不压缩且按 16 KiB 对齐，公开的三参数 InMemoryDexClassLoader 从直接只读缓冲区读取 DEX，并以同一 APK FD 的 ZIP 目录搜索原生库。`ART_JNI_PUBLIC_*` 独立测试本 probe 自己的系统安装 native 目录；每个 SO 先与同一校验输入中的字节摘要核对。每个 case 中 Payload 自己调用 System.load / System.loadLibrary，六项断言验证 JNI_OnLoad、FindClass 定义类与依赖实例。不在失败后更换来源或转用手工 dlopen。

v5 的只读堆缓冲区被本机 ART 拒绝；v6 真正进入 JNI，但无 SONAME 的 memfd ZIP 依赖未解析。旧失败保留。ProbeFactory 与 onBind 参数是否存在另行报告，不作为 JNI 加载成功条件。这些测试没有接入生产 guest 执行或代替 Chromium 验收。

构建：`rtk proxy python3 android/isolated-fd-probe/build.py`。脚本只构建，安装和启动必须按设备测试流程依次执行。每次结果保存在该探针应用自己的 external files，旧文件保留。
