import { HookError, parameterJsonSchema, preparePlan } from "@abox/hook";
import { HookSimulator } from "@abox/hook/simulator";
import { createGenerationContext } from "@abox/hook/ai-context";
import plan from "./basic.hook.js";
import { evidence } from "./evidence.js";

const runtime = new HookSimulator<typeof plan.params>(evidence);
const first = runtime.activate(preparePlan(plan, evidence));
const initial = runtime.invoke("greet", ["world"], () => "original");
runtime.updateParameters({ message: "运行中修改成功", delayMs: 500 });
const updated = runtime.invoke("greet", ["world"], () => "original");
const native = runtime.invoke("delay", [], () => 1000);
let rejected: string | undefined;
try { runtime.updateParameters({ delayMs: -1 }); }
catch (error) { if (!(error instanceof HookError)) throw error; rejected = error.code; }
runtime.rollback(first);
const restored = runtime.invoke("greet", ["world"], () => "original");

console.log(JSON.stringify({
  notice: "LOCAL SIMULATION ONLY — no APK, Android agent, AI request or real process hook was executed",
  initial, updated, native, rejectedParameterUpdate: rejected, restored,
  status: runtime.inspect(), parameterFormSchema: parameterJsonSchema(plan.params),
  aiDraftContract: createGenerationContext("把示例 greet 返回值设为可修改的文字", evidence).outputContract,
}, null, 2));
