# H3d 自动静态 caller 选择验收

同一构建的两次独立冷启动：

- JIT：`76ced051-352b-4ba3-be3e-1ec5ce14546b`。
- AOT：`bf8191d2-84a7-49e4-8ee5-7ef641bdeb8d`。
- APK SHA256：`f4c3ccffa2027b5ccca97a308c5b64a7db40982b0df33d0c39095b8346b21493`。
- 源码摘要：`0e902eadc00f677b65ee5906a5a98d6d9a4b691d2962ae7158a14338c140658f`。
- 构建 ID：`b494cd66-9960-44d1-8159-fc96a6aa7683`。

同一份解析器通过主机 14 项检查，并在设备读取 APK DEX 后自动找到一个 caller；方法描述符、DEX SHA、DEX method index 与实际 ClassLoader 均核验。Native 不再硬编码 caller 名称。

两组均通过 H1 92+16、H2a 45、ART 只读 15、Field.get 62、managed 25 项检查。Field.get 各一百万次热调用全部拦截，四线程切换调用分别 3,488,416 / 3,620,294 次，无错误。

编译 caller 的反优化前后对照：各 10,000 次；前置拦截 0、错误 10,000；反优化后拦截 10,000、错误 0。调用者 method index 为 105；前置 quick entry 位于 JIT / 本包 base.odex，后置为固定 ART 解释器入口。`verification.json` 与 `compilation-verification.json` 保存完整原始结果绑定。

`dex-host-report.json` 绑定解析器、独立 Oracle、测试脚本和当前 APK 的摘要；源码输入也在 APK build receipt 中。源码改变后这些回执仅是历史证据，不能作为后续构建通过。

`80ef01f7...` 保留了首次新版主机拒绝合法数组 clone 引用的失败构建，未运行设备验收。当前版本已增加独立回归。

完整 Runtime 覆盖仍为 false；这里只证明单 DEX / 单 loader 的静态依赖选择。未来 AOT 类加载、动态/虚调用者、在途帧、统一发布与产品迁移均未完成。
