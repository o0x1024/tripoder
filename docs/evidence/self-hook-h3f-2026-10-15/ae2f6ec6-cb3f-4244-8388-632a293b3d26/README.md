# 普通 JNI 框架成员查找未通过

runId：ae2f6ec6-cb3f-4244-8388-632a293b3d26。APK SHA256：6daf38fffb4146d2b5e71a1af76b6c4b59e665c4098fedb2393d44febdab028a。

普通 non-debuggable 进程、Target SDK 37、UID 10403、PID 30926。七个类加载成功，前七个成员解析成功；查找 `LoadedApk.mDefaultClassLoader:Ljava/lang/ClassLoader;` 时抛出 NoSuchFieldError。当前进程日志明确记录 `api=blocked ... using JNI: denied`，因此这次错误是非 SDK 访问拒绝，不能据此断言字段在设备上不存在。

没有进行 LoadedApk/Context 构造、框架字段写入、方法绑定或隐藏 API 豁免设置。该路径未通过，H3f 和产品迁移仍未完成。下一步应清点完整依赖，并审查固定 ART 访问层所需的明确能力，不能跳过必要字段或以宿主上下文透传作为成功。
