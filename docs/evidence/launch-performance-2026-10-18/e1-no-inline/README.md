# P2 E1：禁内联假设与冷重启反例

固定设备 d861a4fa / API37；libart SHA `2d4a3575e5b71b537d89c176ac82ad3e32a56a3d43161009de3e728c962fab13`。两轮同 APK `4a35ee556944b4515dd2215d12d97aa675f261efd53cfb522020e98f5bebfe9e`，不同 PID，无手工编译缓存清理、编译设置修改或产品策略切换。

| 观察 | 首轮（PID 24426） | 冷重启（PID 27229） |
|---|---|---|
| 目标初始 flags | 0x10280009，bit25=0 | 0x12280009，bit25=1 |
| 全局 instrumentation level | before / prepared / active / disabled 均为 0 | 在 flags 门禁处拒绝，未观察 level |
| 晚加载调用 | 101,002 次，零绕过；callbacks=originals=101,002 | 0，未 prepare/enable Hook |
| JIT 正证据 | LateCaller.call 与无 Hook 的 local(Object) 均为 RX dalvik-jit-code-cache | 未进入本阶段 |
| 禁用后 | 原调用返回正确，callbacks 不增加 | 未进入本阶段 |
| ArtMethod 所在位置 | 首轮在普通运行时方法区 | cache/oat_primary/arm64/base.art 映像区 |
| 结论 | 该固定 ART、owned int(int) 场景的单进程可行性通过 | 冷重启前置条件失败，E1 整体门槛未通过 |

[首轮结果](8edb9ea8-ff9c-4ff0-9e48-67cf32ee4b2d/device/result.json) · [独立校验](8edb9ea8-ff9c-4ff0-9e48-67cf32ee4b2d/verification.json) · [冷重启结果](d8fe2514-27e2-4e75-bd51-ff7b92c87409/device/result.json) · [反例独立校验](d8fe2514-27e2-4e75-bd51-ff7b92c87409/restart-verification.json)。反例校验通过只表示失败证据成立，不表示 E1 通过。

实现复用未改动的 OwnedIntHook，传入一个真实 knownCaller；两个 LateCaller 的独立 InMemoryDexClassLoader 都在激活后创建，lateCallersKnownAtPrepare=0。单独的只读 observer 校验固定 ART getter 并在暂停窗口读取 level；没有申请全局解释租约。既有产品核心单独构建，实验 JNI 仅在观察库中，未增加产品运行模式。

冷重启的初始 flags 与首轮停用后的 flags 完全一致，且目标 ArtMethod 落在运行时缓存映像中。这是声明方法标志跨进程带入的实测反例；没有清除 bit25、删除映像或自动改用全局解释。尚未证明如何安全恢复这些声明方法，也未测试通用 ManagedHook、boot AOT caller 或性能目标。

下一步先解决映像持久化的所有权/恢复问题，或审计只影响当前进程的 JIT 内联控制，再重复 E1 冷启动验收；不能直接进入 P3。E2–E4 仍未完成。

复现（构建需要显式 SDK/JDK/NDK/key 与三个 platform 输入，见 build-self-no-inline.py）：

```sh
rtk proxy python3 android/scripts/run-self-no-inline.py --serial d861a4fa --evidence-root docs/evidence/launch-performance-2026-10-18/e1-no-inline --install
rtk proxy python3 android/scripts/run-self-no-inline.py --serial d861a4fa --evidence-root docs/evidence/launch-performance-2026-10-18/e1-no-inline
```

每次生成新 UUID 目录。对首轮执行 verify-self-no-inline.py；对上述冷重启反例执行 verify-self-no-inline-restart.py --first FIRST --restart SECOND。保留失败状态，不清理缓存以制造干净重跑。
