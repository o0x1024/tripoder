import type { EvidenceSnapshot } from "@abox/hook";

// Synthetic symbols for a nonexistent demo application. No WeChat/game methods are claimed here.
export const evidence: EvidenceSnapshot = {
  id: "fixture-001",
  source: "fixture",
  target: { packageName: "com.example.hookdemo", versionCode: "1", apkSha256: "a".repeat(64) },
  processInstanceId: "fixture-process-001",
  abi: "arm64-v8a",
  capabilities: ["java-hook", "native-hook"],
  symbols: [
    { id: "greet", kind: "java", loaded: true, className: "com.example.hookdemo.Greeter", methodName: "greet",
      classLoaderId: "main", argumentTypes: ["java.lang.String"], returnType: "java.lang.String" },
    { id: "delay", kind: "native", loaded: true, moduleName: "libhookdemo.so", symbol: "retry_delay_ms",
      buildId: "fixture-build-001", argumentTypes: [], returnType: "int32", signatureSource: "confirmed" },
  ],
};
