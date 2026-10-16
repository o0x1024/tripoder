# H3f 固定框架成员与逻辑 Application

所有设备操作仅针对自有 `dev.tripoder.selfhookprobe`；APK non-debuggable、无网络权限、无 Frida 输入与映射。没有安装、启动或修改工作台、引擎或第三方应用。

当前验收构建：

- APK SHA256：`fdb5d874553a0a24953df2eb74baa66e8da3de89bf72846393ab957c0d660080`
- sourceDigest：`441255a7e32eec6abdad3846d6911022d854e13f9e1f290bf1b2281e4551ac32`
- buildId：`11d2ac61-3291-40d4-b6a1-4bdc2ac5cd05`
- 平台：固定 Xiaomi madrid / API 37 / ARM64 / 4096 字节页；libart、libc、linker SHA 绑定在每个 run 的平台回执。

| 入口 | runId | 当前构建结果 |
|---|---|---|
| 固定 ART 注册表 | [7a76d834-3b8f-4bbd-9f69-bc8c3a6dd6e1](7a76d834-3b8f-4bbd-9f69-bc8c3a6dd6e1/verification.json) | 7 类 / 20 成员；16 项普通 JNI ID 校准；真实物理 Application 归属通过 |
| 逻辑 Application JIT | [57ba635a-2ca3-42f3-9d03-a4554748c0c8](57ba635a-2ca3-42f3-9d03-a4554748c0c8/verification.json) | 实际 JIT 入口；一百万次读写对、四万次并发读取、11 次物理归属检查通过 |
| 逻辑 Application AOT | [c8add8b3-74ed-4cb7-b69a-c92f5e341ff2](c8add8b3-74ed-4cb7-b69a-c92f5e341ff2/verification.json) | 同 APK speed 编译回执；实际 base.odex 入口；相同检查通过 |
| 主探针 JIT 回归 | [c6e34401-8a16-4ffb-8e9a-c8f2f04413b4](c6e34401-8a16-4ffb-8e9a-c8f2f04413b4/verification.json) | H1–H3e 全部通过；非协作 ELF 47,027,447 次调用、固定 libc 370,254 次调用无错误 |
| 主探针 AOT 回归 | [ff859299-25f7-40c3-bf11-c208ce61ed7f](ff859299-25f7-40c3-bf11-c208ce61ed7f/verification.json) | H1–H3e 全部通过；非协作 ELF 48,572,823 次调用、固定 libc 356,218 次调用无错误 |

Application 两个 run 都验证独立 DexClassLoader、factory / Application 构造 / attach / onCreate 各一次、真实 ContextImpl 解包、逻辑 LoadedApk 关联、初始 Application 的幂等写与两种拒绝、关闭视图后的物理 host 读取。视图保留 9 个引用，适配器保留 18 个引用，框架注册表另保留 7 个 boot Class 引用，均存活至进程退出。不是安全沙箱或可释放性证明。

主探针分别包含 92 核心、16 合成执行、45 协作 Hook、15 线程暂停、19 非协作 Hook、24+24 libc、15 ART 观察、62 Field.get、25 managed、76 反射视图检查，及 14 主机 DEX 检查。每次百万次拦截均以实际编译入口与精确计数核验；compiled caller 在选择性反优化前的遗漏仍是预期对照，不能声称通用内联覆盖已完成。

## 保留的发现与中间记录

- `ae2f6ec6-cb3f-4244-8388-632a293b3d26`：普通 JNI 首次在 mDefaultClassLoader 被拒绝；保存对应 hidden-api 系统日志。
- `544956ad-25b0-4a54-9ac7-341a8904e7e4`：完整普通 JNI 清点为 16/20；另三个拒绝是 mAppComponentFactory、LoadedApk 构造器、三参数 createAppContext。没有写入框架字段。
- `b9764291-d342-4a0f-ad16-685881b094dd`：主机 DEX 测试因错误编译范围失败后的部分构建记录，没有设备验收；测试脚本已按 DEX 功能范围拆清。
- `2b014ecb-37cf-4e64-a620-c84db298b87e`：准备门禁拒绝，未通过。
- `f1b819c8-b201-4fdb-a80f-dfd92682d6d7`：只读 VMA 数量超出固定表容量，被明确拒绝；随后按地址校验需要合并相邻可读区间。
- `f924533f-5422-4936-b474-fe09c8c91376`、`a8df3d49-1001-4109-ade1-3c21695154c1`：JNI ID 往返失败，阻止进入框架访问；低层索引编码器使用条件错误，已由唯一的固定 kPointer 表示替换。
- `65aab644-b251-42fe-bb6e-171c5de588e6`：前一构建固定注册表通过；当前构建以上表为准。
- `84787c0c-d3c2-415d-ad86-8205d003a76b`：首个逻辑 Application 正向观察通过，之后补充了五项逻辑元数据归属复验和严格验证器；不代替当前构建验收。

设计：[固定框架访问](../../self-hook-framework-access.md)、[Application 适配](../../self-hook-application-adapter.md)。所有成功回执仅证明独立探针能力，产品仍依赖 Frida；H4–H6 未完成。
