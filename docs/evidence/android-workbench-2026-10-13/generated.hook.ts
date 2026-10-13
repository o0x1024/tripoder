import { definePlan, param } from "@abox/hook";

export default definePlan({
  id: "fixture-greet-uppercase-delay-scaled",
  target,
  params: {
    message: param.string("script:", { description: "Prefix for the uppercased greeting" }),
    delayMs: param.integer(700, { minimum: 0, maximum: 10000, description: "Native additive delay offset" })
  },
  setup(ctx) {
    ctx.java
      .method(symbols.greet.className, symbols.greet.methodName, symbols.greet.argumentTypes)
      .replace(({ args, params }) => {
        const original = String(args[0]);
        return params.message + original.toUpperCase();
      });

    ctx.native
      .function(symbols.delay.moduleName, symbols.delay.symbol, {
        argumentTypes: symbols.delay.argumentTypes,
        returnType: symbols.delay.returnType,
        buildId: symbols.delay.buildId
      })
      .replace(({ args, params }) => Number(args[0]) * 2 + params.delayMs);
  }
});