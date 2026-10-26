import test from "node:test";
import assert from "node:assert/strict";
import { prepareDraft, draftTemplate } from "../src/draft.js";
import { HookSimulator } from "../src/simulator.js";
import type { EvidenceSnapshot } from "../src/evidence.js";

const evidence: EvidenceSnapshot = {
  id: "draft-test", source: "fixture", processInstanceId: "1:draft-test", abi: "arm64-v8a",
  target: { packageName: "test.fixture", versionCode: "1", apkSha256: "a".repeat(64) },
  capabilities: ["java-hook", "native-hook"], symbols: [
    { id: "greet", kind: "java", loaded: true, className: "test.Greeter", methodName: "greet", classLoaderId: "one", argumentTypes: ["java.lang.String"], returnType: "java.lang.String" },
    { id: "delay", kind: "native", loaded: true, moduleName: "libtest.so", symbol: "delay", buildId: "abc", argumentTypes: ["int32"], returnType: "int32", signatureSource: "confirmed" },
  ],
};
const script = (body: string) => `import { definePlan } from "@abox/hook";
export default definePlan({ id:"test", target, params:{}, setup(ctx) {
ctx.java.method(symbols.greet.className,"greet",["java.lang.String"]).replace(({args}) => { ${body} });
}});`;

test("standard TS template prepares and replaces both targets with dynamic parameters", () => {
  const plan = prepareDraft(draftTemplate(), evidence);
  const simulator = new HookSimulator(evidence); simulator.activate(plan);
  assert.equal(simulator.invoke("greet", ["phone"], () => "original").value, "script:PHONE");
  assert.equal(simulator.invoke("delay", [3], () => 16).value, 706);
  simulator.updateParameters({ message: "new:" });
  assert.equal(simulator.invoke("greet", ["phone"], () => "original").value, "new:PHONE");
});
test("TS annotations, destructuring, conditional and template literals retain standard semantics", () => {
  const plan = prepareDraft(script('const name: string = String(args[0]); if (name.length > 2) return `Hi ${name.toUpperCase()}`; return "short";'), evidence);
  const sim = new HookSimulator(evidence); sim.activate(plan);
  assert.equal(sim.invoke("greet", ["phone"], () => "original").value, "Hi PHONE");
});
test("before and after preserve original call once", () => {
  const code = script('return args[0];').replace('.replace(({args}) => { return args[0]; })', '.before(({args}) => [String(args[0]).toUpperCase()]).after(({result}) => "after:" + result)');
  const sim = new HookSimulator(evidence); sim.activate(prepareDraft(code, evidence)); let calls = 0;
  assert.equal(sim.invoke("greet", ["phone"], args => { calls++; return "original:" + args[0]; }).value, "after:original:PHONE");
  assert.equal(calls, 1);
});
test("unknown globals and dynamic property escapes throw without calling the original", () => {
  for (const body of ['return globalThis;', 'return Java.use("X");', 'return String["con" + "structor"]("return 7")();', 'return args["__proto__"];', 'return Math.max({ valueOf: () => 1 });']) {
    let calls = 0; const sim = new HookSimulator(evidence);
    sim.activate(prepareDraft(script(body), evidence));
    assert.throws(() => sim.invoke("greet", ["phone"], () => { calls++; return "original"; }), /DRAFT/);
    assert.equal(calls, 0);
    assert.equal(sim.events().filter(e => e.kind === "hook-error" && e.stage === "replace").length, 1);
  }
});
test("unsupported code is rejected even in an unexecuted branch", () => {
  for (const body of ['if(false) { while(true) {} } return "x";', 'return new Function("return 1")();', 'const f = async () => 1; return "x";', 'args[0] = "changed"; return "x";', 'return {get value(){return 1;}};']) {
    assert.throws(() => prepareDraft(script(body), evidence), /DRAFT/);
  }
});
test("cannot load modules or invent native signatures", () => {
  assert.throws(() => prepareDraft('import { readFile } from "node:fs";\n' + draftTemplate(), evidence), /Only @abox/);
  assert.throws(() => prepareDraft(draftTemplate().replace('buildId: symbols.delay.buildId', 'buildId: "wrong"'), evidence), /Build ID/);
});
test("self recursion is bounded across interpreted function calls", () => {
  const sim = new HookSimulator(evidence);
  sim.activate(prepareDraft(script('const recurse = () => recurse(); return recurse();'), evidence));
  let calls = 0;
  assert.throws(() => sim.invoke("greet", ["phone"], () => { calls++; return "original"; }), /DRAFT: Call depth exceeded/);
  assert.equal(calls, 0);
});
test("oversized source, AST and expanding strings are bounded", () => {
  assert.throws(() => prepareDraft(" ".repeat(32769), evidence), /32 KiB/);
  const sim = new HookSimulator(evidence);
  sim.activate(prepareDraft(script('const f = (s) => s.length > 8000 ? s + s : f(s+s); return f("xxxxxxxx");'), evidence));
  let calls = 0;
  assert.throws(() => sim.invoke("greet", ["phone"], () => { calls++; return "original"; }), /DRAFT: String size limit/);
  assert.equal(calls, 0);
});
test("a rejected draft never mutates an already prepared plan", () => {
  const sim = new HookSimulator(evidence); sim.activate(prepareDraft(draftTemplate(), evidence));
  assert.throws(() => prepareDraft(draftTemplate().replace('target,', 'target: { packageName:"wrong",versionCode:"1",apkSha256:"b" },'), evidence));
  assert.equal(sim.invoke("greet", ["phone"], () => "original").value, "script:PHONE");
});
test("ctx.debug.emitEvent records structured telemetry inside callbacks and rejects setup emission", () => {
  assert.throws(() => prepareDraft(script('return "ok";').replace('setup(ctx) {', 'setup(ctx) { ctx.debug.emitEvent("setup.tag", 1);'), evidence), /emitEvent can only be called inside a hook callback/);
  const plan = prepareDraft(script('ctx.debug.emitEvent("greet.called", { input: args[0] }); return "logged:" + args[0];'), evidence);
  const sim = new HookSimulator(evidence); sim.activate(plan);
  assert.equal(sim.invoke("greet", ["world"], () => "original").value, "logged:world");
  const debugEvents = sim.events().filter(e => e.kind === "debug-event");
  assert.equal(debugEvents.length, 1);
  assert.equal(debugEvents[0]?.tag, "greet.called");
  assert.deepEqual({ ...(debugEvents[0]?.payload as Record<string, unknown>) }, { input: "world" });
});
test("ctx.privacy exposes an importable SDK attribution catalog and an independent consent timeline", () => {
  const sim = new HookSimulator(evidence);
  sim.configureAttribution([
    { packagePrefix: "com.example.adsdk", sdkName: "ExampleAdSDK" },
    { packagePrefix: "com.example.adsdk.core", sdkName: "ExampleAdSDKCore" },
  ]);
  sim.markConsentStage("APP_START", "boot");
  const marked = sim.markConsentStage("CONSENT_GRANTED", "user tapped accept");
  assert.equal(marked.seq, 2);
  const probe = `import { definePlan } from "@abox/hook";
export default definePlan({ id:"test", target, params:{}, setup(ctx) {
ctx.java.method(symbols.greet.className,"greet",["java.lang.String"]).before(({args}) => {
  ctx.debug.emitEvent("privacy.probe", { attribution: ctx.privacy.attribution("com.example.adsdk.core.Collector"), stage: ctx.privacy.consentStage() });
  return args;
});
}});`;
  sim.activate(prepareDraft(probe, evidence));
  sim.invoke("greet", ["hi"], () => "original");
  const event = sim.events().find(e => e.kind === "debug-event" && e.tag === "privacy.probe");
  const payload = event?.payload as Record<string, unknown>;
  assert.deepEqual(payload.attribution, { className: "com.example.adsdk.core.Collector", matchedPrefix: "com.example.adsdk.core", sdkName: "ExampleAdSDKCore" });
  assert.deepEqual(payload.stage, marked);
  // Unmatched class name: explicit nulls, not a silent guess or the nearest looser prefix.
  const sim2 = new HookSimulator(evidence);
  sim2.activate(prepareDraft(probe.replace("com.example.adsdk.core.Collector", "com.other.sdk.Thing"), evidence));
  sim2.invoke("greet", ["hi"], () => "original");
  const unmatched = (sim2.events().find(e => e.kind === "debug-event")?.payload as Record<string, unknown>).attribution;
  assert.deepEqual(unmatched, { className: "com.other.sdk.Thing", matchedPrefix: null, sdkName: null });
});
test("Hex/Base64/JSON builtins round-trip and reject malformed input", () => {
  const sim = new HookSimulator(evidence);
  sim.activate(prepareDraft(script('return Hex.encode(String(args[0]));'), evidence));
  assert.equal(sim.invoke("greet", ["hi"], () => "original").value, "6869");
  const sim2 = new HookSimulator(evidence);
  sim2.activate(prepareDraft(script('return Hex.decode("6869");'), evidence));
  assert.equal(sim2.invoke("greet", ["x"], () => "original").value, "hi");
  const sim3 = new HookSimulator(evidence);
  sim3.activate(prepareDraft(script('return Base64.encode(Hex.encode(String(args[0])));'), evidence));
  assert.equal(sim3.invoke("greet", ["hi"], () => "original").value, "aGk=");
  const sim4 = new HookSimulator(evidence);
  sim4.activate(prepareDraft(script('return Hex.decode(Base64.decode("aGk="));'), evidence));
  assert.equal(sim4.invoke("greet", ["x"], () => "original").value, "hi");
  const sim5 = new HookSimulator(evidence);
  sim5.activate(prepareDraft(script('const obj = JSON.parse(\'{"a":1,"b":[true,null,"x"]}\'); return JSON.stringify(obj);'), evidence));
  assert.equal(sim5.invoke("greet", ["x"], () => "original").value, '{"a":1,"b":[true,null,"x"]}');
  const sim6 = new HookSimulator(evidence);
  sim6.activate(prepareDraft(script('return Hex.decode("abc");'), evidence));
  assert.throws(() => sim6.invoke("greet", ["x"], () => "original"), /even-length hex string/);
  const sim7 = new HookSimulator(evidence);
  sim7.activate(prepareDraft(script('return JSON.parse("{not json");'), evidence));
  assert.throws(() => sim7.invoke("greet", ["x"], () => "original"), /Invalid JSON/);
});
