import { definePlan, param } from "@abox/hook";

export default definePlan({
  id: "demo-greeting",
  target: { packageName: "com.example.hookdemo", versionCode: "1", apkSha256: "a".repeat(64) },
  params: {
    message: param.string("你好，ABox", { title: "返回文字", maxLength: 100 }),
    delayMs: param.integer(250, { title: "重试间隔（毫秒）", minimum: 0, maximum: 10000 }),
  },
  setup(ctx) {
    ctx.java.method("com.example.hookdemo.Greeter", "greet", ["java.lang.String"])
      .replace(({ params }) => params.message);

    ctx.native.function("libhookdemo.so", "retry_delay_ms", {
      argumentTypes: [], returnType: "int32", buildId: "fixture-build-001",
    }).replace(({ params }) => params.delayMs);
  },
});
