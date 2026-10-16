# H2e 自有Native标量ABI

2026-10-15。run `bbdda59c-fea3-482e-a5aa-c932a9649ae7` 已由独立verifier通过。

- APK SHA256：`43c333112f7a01b6621d1e5f5ac0d6b91502d2753c8f7904bb0fd7f231d4c1f8`。
- 13组真实编译器类型对照、490项设备检查，四线程4,000次TLS errno调用无错误。
- 六类整数/浮点、最多16参数、独立GPR/FPR及栈溢出参数、NaN payload、负零、无穷与void；NDK汇编已经实际执行。
- 主机2,103项ASan/UBSan及独立LLVM BTI/CFI/寄存器审计通过；生成器支持只读一致性检查。
- `hookInstalls=0`、`targetDiscovery=false`、`fullNativeHook=false`。这验证通用标量调用/捕获，不代表任意Native目标Hook完成；指针、结构体、向量和varargs仍拒绝。

[设备与独立验证](bbdda59c-fea3-482e-a5aa-c932a9649ae7/verification.json)。构建APK、Native库和receipt保存于`artifacts/43c33311/`。源码后续变化时不要重用旧receipt声明当前构建通过。
