# Application 与 LoadedApk 的所有权

2026-10-15。P6 微信初始化暴露的结构问题，独立实现，不使用 VirtualApp 代码。

## 已确认的根因

旧 Application 的 GuestContext 最终包裹物理 RuntimeApplication。Android 的 Application.attach 解包 ContextWrapper 时因此拿到物理 ContextImpl / LoadedApk。客体在 attachBaseContext 中按自身 Context 找到 mPackageInfo 并关联自己的 Application，实际改变了物理引擎 LoadedApk.mApplication。随后 Android 接收物理广播时拿到了客体 Application；其 base 是 GuestContext，平台要求 ContextImpl，发生 ClassCastException。

`wechat-ownership-02` 的实际字段写入记录证明同一 LoadedApk identity `104928556` 从 RuntimeApplication 变成微信 Application；写入发生在客体 attachBaseContext 调用链。mInitialApplication 也在同一阶段变化。仅凭异常曾只能提出假设，现在有前后对象身份与写入栈证据。没有读取账号数据或修改目标 APK。

## 所有权修正

- 物理 RuntimeApplication、LoadedApk 和 ContextImpl 继续由 Android 管理，服务于 manifest 里的物理 Worker、Factory、Receiver 与控制入口。
- 每个不可变客体进程额外拥有一个逻辑 LoadedApk 和真实 ContextImpl。它们绑定已核验的 ApplicationInfo、现有 loader、现有 AppComponentFactory 和 Resources。构造不能再次实例化客体工厂、ClassLoader 或 Application，也不向系统注册虚拟包。
- GuestContext 的真实 base 改为这个逻辑 ContextImpl。Android Application.attach 与客体合法的 Context 解包得到同一个逻辑包对象；GuestContext 仍执行已定义的存储、组件、服务策略。
- 平台能力的委托 Context 单独保留：服务路由和允许的 UI 能力使用原物理 Context，不能从逻辑 base 意外取得另一套身份。逻辑 ContextImpl 的 AppOps attribution 明确使用物理引擎包名和真实 UID；它不伪造系统 UID。
- 进程只有一个客体 Application。ActivityThread.mInitialApplication 的**物理字段**仍指向 RuntimeApplication；客体通过反射得到逻辑 Application 视图，对同一对象的幂等关联允许，null 或另一 Application 明确拒绝。这个映射只针对当前 ActivityThread 的精确字段，不拦截或改写任意反射字段。平台直接读取继续得到物理 Application。内部引擎入口持有不变的 RuntimeApplication 引用。
- attach 和 onCreate 返回后，必需适配器核验物理/逻辑 LoadedApk 不同、物理 Application 未改变、逻辑 Application 指向本客体。违反所有权直接失败，不恢复字段后继续执行，也不退回宿主 Context。

`logical-application-receiver-01` 证明仅分开 LoadedApk 仍不充分：允许修改物理初始 Application 后，Autofill 的 DeviceConfig 内部查询进入客体 Settings 策略，发生明确拒绝。该失败保留，随后补齐上述初始 Application 视图；没有扩大 Settings 权限来掩盖归属错误。

这仍不是防御同 UID 任意反射/Native 的安全沙箱。规则是受支持执行路径的身份与生命周期契约，不是强隔离声明。原生组件路径与 SDK 查询继续各自受控；未来开放更广系统接口必须使用同一所有权模型。

## 验收门槛

自研 P2 Application 在 attach 中解包 Context，验证 package metadata 属于自己，然后关联实际 LoadedApk 和初始 Application。在 onCreate 验证 Application.attach 得到同一个 LoadedApk。全部冷/热 Activity、Service、Provider、Receiver 和并发启动沿用同一 fixture，要求 factory、loader、Application 仍各一次，并且物理广播仍通过 RuntimeFactory。

还须通过原生 ServiceInfo direct-boot flags 对照、完整 P2 回归和微信同版本再次启动。没有执行证据前不得宣布本修正通过，更不能宣布 P6 通过。

初始 Application 视图补齐后，`logical-application-receiver-02` 54 项定向检查通过，run `receiver-0cd76780bd83448a`，Runtime APK `bb58046d0d36b47c126a8858f3fc63f6a19ecc3853eee0b71fc28275da0673c4`；Java/Native fatal 均未观察到。完整组件与真实应用门槛继续单独验证。

同构建 `logical-application-p2-01` 完整现有组件套件 **509 项通过**，run `p2-75bfe8dd045e4b42`。其中包含新增 direct-boot flags 与 ApplicationInfo 默认进程元数据检查，以及每个 P2 Application 的自研所有权断言。没有把初始化归属修正计作真实微信启动成功。

平台接口参考：[AOSP LoadedApk](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/LoadedApk.java) 与 [AOSP ContextImpl](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/app/ContextImpl.java)。构造签名固定在已验证 Android 37 适配器，签名缺失即构建/加载失败。这里只调用平台接口，未复制第三方容器实现。

## CallerSensitive 反射入口修正（2026-10-15，正在验收）

微信第十八次启动捕获 Field.get 的 IllegalAccessException，调用类异常显示为 java.lang.reflect.Field。独立自研 `reflection-runtime-before-01` 已复现：类内读取自己的 private 字段、未设置 accessible，本应保留调用类，却被拒绝。根因是 managed Field.get/set replacement 调用 original 时经 JNI 重入原 Java 方法，额外的 Field managed 帧改变 ART 的 caller-sensitive 权限检查。

移除这两个 managed replacement。固定 API/ABI 下，从当前 ArtMethod 已校验布局取得真实 libart native entry，使用 native Interceptor.attach。回调仅调用 raw JNI，普通字段原入口、原栈、访问检查和异常均保持。精确的 mInitialApplication jfieldID + 当前 ActivityThread owner 才映射；get 在原访问检查成功后返回逻辑 Application 的新 local ref。set 对合法 Application 类型保留物理 host 同值写入和原访问检查，再拒绝 null 或非本逻辑 Application；错误类型仍交给原函数按原顺序拒绝。全局引用与 native 代码存活至该不可变进程退出。

这不是私有字段权限绕过。真实外部类读取 GuestPackageManager.engine 若未获原生反射权限，仍应失败。需要分别验证类内合法 private、类外非法 private、独立 Field 的 accessible 标记、类型/receiver 异常，以及逻辑初始 Application 与物理 host 不串写。基线第一次因新加了一个夹具 Activity 而旧组件计数断言未更新，保留原失败；不计原生权限验收。

参考：[Android17 Field.java](https://android.googlesource.com/platform/libcore/+/refs/tags/android-17.0.0_r1/ojluni/src/main/java/java/lang/reflect/Field.java)、[ART Field native](https://android.googlesource.com/platform/art/+/refs/tags/android-17.0.0_r1/runtime/native/java_lang_reflect_Field.cc)。没有复制 VirtualApp 代码。

限定验收已完成：`reflection-baseline-03` 原生 91 项通过；`reflection-native-runtime-02` 运行器 23 项通过，APK `f9dc7defc1dff942a802f5a2268d1c6dc252b7f9c38f2c99e31fe1c3e96a3ee5`。覆盖 own private/static、访问标记独立、外部读写拒绝、错误 receiver/type、primitive boxing/widening，以及四线程 4000 轮普通字段 GC 读取；自研 Application 另验证逻辑初始 Application 的幂等写、null/外来对象/错误类型拒绝、未设 accessible 的读写拒绝与四线程 2000 次身份读取。物理 Application 的直接引用仍由适配器检查。

`wechat-first-launch-19` 原调用者已经正确显示为目标类，Application RUNNING 后仍因尝试读取 GuestPackageManager.engine 私有字段而退出。此时异常是正常访问检查；修复不能把字段公开。目标在查找平台 mPM 字段失败后进入其内部字段扫描，暴露了我们的 PackageManager facade 不保留平台对象形态。后续包域改造见 [包查询契约](runtime-package-design.md)，本项反射通过不代表微信或完整 P2 通过。
