# H3e Native Field.get/set 身份视图验收

同一独立 APK SHA256：`d3b3ded77defc06424e232b28d762a3230082473b024f93093b56242b4388ad7`。
sourceDigest：`ee0a81f2ed376dee2f173419ca4dd1d845381f5ccda1bd04890fc7dbde8c0b14`。
buildId：`114ef08e-c25b-4cfc-afbe-14c6f5c3daa3`。

| 验收 | JIT | AOT |
|---|---|---|
| runId | [2052e6c6-4af5-440a-b9df-665723c58d5d](2052e6c6-4af5-440a-b9df-665723c58d5d/verification.json) | [0e72c8e6-734c-4e0c-afca-18ad35c412b0](0e72c8e6-734c-4e0c-afca-18ad35c412b0/verification.json) |
| 身份与访问检查 | 76/76 | 76/76 |
| 已编译入口下 Field.set/get | 各 1,000,000 次精确映射 | 各 1,000,000 次精确映射 |
| 原物理存储 | 不变 | 不变 |
| 四线程 20 轮切换读取 | 13,897,471，无错误 | 13,898,806，无错误 |
| 原始访问/类型异常 | 9 次保持 | 9 次保持 |
| 显式身份拒绝 | 4 次 | 4 次 |
| 转换次数/进程期全局根 | 42/9 | 42/9 |

实际采样 hotReadWrite 的 JIT/AOT 入口在百万读写前后均满足请求的编译类型；没有以预热次数代替编译证明。私有访问、AccessibleObject override、错误先后、Field 对象副本、其他 owner/字段、另一 ClassLoader 同名类、GC 请求与压力、原绑定恢复均通过。映射写入计数独立核对为 `1,000,000 + primeRounds × 10,000 + 4`。

同一 APK 还通过全部既有 Native/ART/DEX/libc 检查；固定 libc 两组分别有 367,558 / 380,929 次并发调用无错误，完整共享页、BTI 和原文件映射核验通过。

JIT result SHA：`a9477f9ee4f78a626082e7d93270c7daf635fce0db8850cc996643d68c2d249e`。
AOT result SHA：`2a081c86a307d57ae7c29007e8d04390e0ffd07fb33d9b7998b5beee3ddb8123`。

仅配置自有 AppIdentityFixture 的 Application 字段；`realActivityThreadConfigured=false`、`genericJavaHook=false`、`gcMovementProven=false`。真实框架成员访问、Context/LoadedApk 构造与 Runtime 产品接入仍是下一阶段。
