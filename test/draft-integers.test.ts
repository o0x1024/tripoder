import test from "node:test";
import assert from "node:assert/strict";
import { prepareDraft } from "../src/draft.js";
import { HookSimulator } from "../src/simulator.js";
import type { EvidenceSnapshot } from "../src/evidence.js";

const evidence: EvidenceSnapshot = {
  id: "exact-integers", source: "fixture", processInstanceId: "owned:1", abi: "arm64-v8a",
  target: { packageName: "dev.tripoder.owned", versionCode: "1", apkSha256: "a".repeat(64) },
  capabilities: ["java-hook"], symbols: [{ id: "longValue", kind: "java", loaded: true,
    className: "dev.tripoder.owned.Target", methodName: "value", classLoaderId: "loader-1",
    argumentTypes: ["long"], returnType: "long" }],
};
const source = (expression: string) => `import { definePlan } from "@abox/hook";
export default definePlan({id:"exact-long",target,params:{},setup(ctx){
ctx.java.method("dev.tripoder.owned.Target","value",["long"]).replace(({args})=>${expression});}});`;
function invoke(expression: string, value: bigint) {
  const sim = new HookSimulator(evidence);sim.activate(prepareDraft(source(expression), evidence));
  let originals = 0;
  const result = sim.invoke("longValue", [value], () => { originals++; return 0n; });
  assert.equal(originals, 0);return result.value;
}
test("draft preserves Java long values beyond Number precision", () => {
  assert.equal(invoke("args[0] + 1n", 9007199254740993n), 9007199254740994n);
  assert.equal(invoke("-args[0]", 9223372036854775807n), -9223372036854775807n);
  assert.equal(invoke('BigInt("9223372036854775807")', 0n), 9223372036854775807n);
  assert.equal(invoke("-9223372036854775808n", 0n), -9223372036854775808n);
});
test("draft rejects mixed arithmetic, overflow and unbounded integers without calling original", () => {
  for (const expression of ["args[0] + 1", "args[0] + 1n", "+args[0]", "BigInt(9007199254740992)",
    'BigInt("9" + "9" + "9").toString()', "999999999999999999999999999999999999999999999999999999n"]) {
    const sim = new HookSimulator(evidence);sim.activate(prepareDraft(source(expression), evidence));
    let originals = 0;
    assert.throws(() => sim.invoke("longValue", [9223372036854775807n], () => { originals++; return 0n; }));
    assert.equal(originals, 0);
  }
});
