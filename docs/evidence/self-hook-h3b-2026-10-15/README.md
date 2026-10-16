# H3b 固定平台 Field.get 验收

同一源码与 APK 的两次独立冷启动：

| 模式 | runId | 热调用 | 四线程混合切换调用 | 结论 |
| --- | --- | ---: | ---: | --- |
| JIT | `91446bcc-e4ac-4124-b6e3-279493f46ed1` | 1,000,000 | 3,573,192 | 通过 |
| AOT | `1ba18d1d-f5f8-463b-a245-63b1732ff63a` | 1,000,000 | 3,456,027 | 通过 |

APK SHA256：`101c0fb16451a28028d5398f832a79756fbfebd340fa1b7e441ea1e3e716fcb0`。
源码摘要：`05407b6546feae32441f7d132bab72134b5e7c3af6f1e5b32e67a1183bd44e40`。

每组均通过 H1 92+16、H2a 45、ART 只读 15、Native Field.get 62 项检查。每组 42 次 RegisterNatives 变更，20 轮并发启停，最终停用，6 次原异常按原访问语义保留。所有热调用的替换计数与返回值一致；原字段储值保持 17。四个全局引用保留到进程结束，未声称通用回收。

`verification.json` 绑定源码、APK、平台、maps 和结果 SHA；`compilation-verification.json` 单独核验热段前后实际 quick entry 的映射。JIT 前后均为代码缓存；AOT 前后同为本包 base.odex，入口 `0x6cedb26860`。AOT 编译命令及产物路径保存在 `aot-compile.txt`。入口观测有 10 秒上限；指定状态未出现即失败，不切换模式。

此前 `8a88bd11...`、`a97e0588...`、`6422c783...` 的热调用值虽正确，前置入口仍是解析跳板，严格 AOT 核验拒绝。这些历史结果保留，不算 AOT 门槛通过。最终版本通过反射进入所采样方法，并先确认指定编译入口出现，再开始测量。

范围仅为当前 non-debuggable 独立进程中原生 Field.get。普通 managed 方法、全部反射变体、内联覆盖、产品 Runtime 和去 Frida 均未继承此结论。验证时没有停止或更新产品包。
