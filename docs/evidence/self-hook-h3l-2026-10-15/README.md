# H3l 原位置保存栈帧与统一CFA

2026-10-15。当前代码在entry+8替换STP x20,x19，原PACIASP和FP/LR栈帧保存均留在原位置；leaf不改变SP，一个FDE覆盖全部23个PC，CFA始终SP+32。见[入口设计](../../self-hook-entry-ownership.md)。H3k的entry+4和三段FDE为历史，不作为fallback。

当前构建：APK `dfb7b7cc334e0ce475c3d1e5e99b7d7685d81fd94b591fc20e41200a36fd5b12`，buildId `2b299105-56ee-423a-a99d-e3ecb04d98a9`，sourceDigest `e9713d7b9b719f4b3507ba36c2ac26326b15026ee0c3dcaea51e9d7bca4df3b6`。

| run | 已独立验收 |
| --- | --- |
| `922891da-4373-4b1f-827f-0abc82039792` | 自有入口40万调用/10万desired发布；512次实际异步展开；加载器查询23个PC均为同一FDE；完整文件页恢复及封存ELF字节绑定 |
| `a9ffc782-06e6-4c38-9c58-4b367481049a` | 真实中央ART writer，JIT编译target/caller后激活；132次原writer事件、127405次调用无错，入口保持owner relay |

组合运行`84950608-3ce3-4ad0-be39-440462d6950f`失败于自有入口发布夹具，未独立通过，正在诊断；不能将其前置loader检查当成整体验收通过。

所有运行仅限自有探针。中央writer已安装，完整direct-store闭包、未来AOT/image、caller-sensitive及产品适配仍未完成，产品仍依赖Frida。

历史失败保留在H3k：`74fa61bb...`有5016次无错误展开，但20,000次尝试未命中旧保存后单指令状态，整次失败。当前从结构上消除leaf中的CFA变化，保留512次真实采样、准确返回点及逐PC静态/运行时FDE核验，不假造PC或关闭认证。
