# H3c 自有 managed 方法与编译调用者证据

最终两次独立冷启动使用相同 APK / Native / 源码：

- JIT：`1b24a997-f589-4234-8bce-577a3323df63`。
- AOT：`f57492b9-60c3-4cf2-83d8-0ffd68de2ddb`。
- APK SHA256：`ca8bad6526754b05012e4daeaca21f81ceceff3f1a2518fec0391d1185fab675`。
- 源码摘要：`4e9ade6ba4ddec4c694949b85447ca81909bed982d8607ad1078312dd5ec4937`。
- 构建 ID：`5626ec63-1a65-4799-8cfe-5870092e9ffe`。

每组：H1 核心 92 / 合成执行 16、H2a 真实 Hook 45、ART 只读 15、Native Field.get 62、owned managed 25 项检查通过。Native Field.get 各一百万次热调用全部拦截；四线程 20 轮切换分别执行 3,685,663 / 3,685,693 次，错误为零。

managed 阶段两个自有 static int(int) 目标使用真实 ART 方法槽位备份，原调用异常身份、调用线程、GC 请求后的访问、Java int 环绕及恢复通过。10,000 次直接反射入口调用全部替换；总计 20,004 次回调与原调用相等，最终关闭。

编译调用者负例在 JIT 与 AOT 均复现：反优化前 10,000 次调用中拦截 0 次、结果错误 10,000 次；针对已知 caller 反优化后拦截 10,000 次、错误 0 次。JIT caller 原入口 `0xd05c4e80`；AOT caller 原入口 `0x6cedb17360`；两组反优化后均为本次 libart 的解释器入口 `0x6d038bc880`。每份 `compilation-verification.json` 保存完整映射和核验器 SHA。

`verification.json` 绑定构建、当前源码、APK、平台和原始结果；`device/` 保存 result / maps / 四阶段 Native 映射。AOT 编译回执在 AOT 目录。`art-managed-symbols.json` 记录本次 libart 的固定函数与字段证据。

历史：`bf68f919...` / `8f83317f...` 仅覆盖第一个自有方法的 23 项检查；`84cbaa0b...` 在反优化绑定处失败，未达到 25 项通过。失败证据保留，修复了 ARM64 带标签 Native 堆指针的 maps 查询。

限制：只证明两个自有方法及一个已知 caller；没有通用自动调用者发现、在途帧迁移、动态类加载、任意 ABI 或产品去 Frida 验收。GC 请求检查不是通用 GC 压力或所有移动路径证明。所有设备操作仅针对独立探针包。
