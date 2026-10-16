# H3k 进程编译所有权、实际loader和调用者观察

2026-10-15。全部设备运行仅限自有`dev.tripoder.selfhookprobe`；产品仍依赖Frida。

## 已独立通过

| run | APK前缀 | 验证 |
| --- | --- | --- |
| `13450117-ddd6-4ce6-a514-72240b7f8fe7` | eba28a0a | 首次激活持有核心进程解释租约；ABI JIT 2,048项、4参数移动、400轮根观察，262,547次并发调用 |
| `1718ff74-73b1-48eb-9184-16469aa7b5ef` | eba28a0a | ABI AOT 2,048项、4参数移动、400轮根观察，260,109次并发调用 |
| `16ef1c28-011f-4155-affc-8dadec5bafe4` | eba28a0a | 脚本源码到真实ManagedHook，JIT 473项 |
| `d251c6c1-1bac-4a44-b5ff-5ffc568ec604` | eba28a0a | 同脚本链路，AOT 473项 |
| `f641a0b2-6265-4742-bf8a-1f4169ac4456` | fa03e4f5 | prepare不claim；晚加载101,002次全部命中；24轮枚举、28次GC、两个Class移动；独立loader目标1,001次与同名未拥有类1,001次无串拦；boot currentApplication 1,000次与F0物理身份正确；512次异步采样覆盖两种CFA状态 |
| `6a0afe65-1b17-47a5-8559-ae5cd4fc428d` | fa03e4f5 | ABI JIT回归，真实Native调用者观察器在激活前和并发移动GC后核对DEX index、owner和quick |

前三个编译策略完整函数体在`art-interpreter-contract.json`独立绑定固定ELF。lease使用唯一client `tripoder-managed-process-interpreter`，首次enable领取一次，停用后保留到进程退出。构建实物保存于`artifacts/eba28a0a/`和`artifacts/fa03e4f5/`。

## 失败证据

`e929d764-d0d3-430e-b489-36879c8f562a`：14,749次有效展开均无错误，但20,000次尝试只采到保存前状态，整次失败。foreign类的64槽扫描占据采样窗口；调整为只采owned leaf，未改变被测leaf、FDE或验收阈值。后续run真实命中两种状态。静态LLVM仍逐PC覆盖22条指令，不宣称动态逐PC采齐。

## 未通过的范围

真实ART writer interposition、动态代码/FDE发布、全部直接store及未来AOT/image发布仍未完成。boot单方法证明不能代表所有boot方法；caller-sensitive、加载器卸载、产品固定成员/manager安装、控制面Platform和完整产品性能仍未完成。所有完整覆盖与产品验收flag保持false。
