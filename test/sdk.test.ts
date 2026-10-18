import assert from "node:assert/strict";
import test from "node:test";
import { definePlan, param, parameterJsonSchema, preparePlan, resolveParameters } from "@abox/hook";
import type { EvidenceSnapshot, HookPlan, HookValue, PlanContext } from "@abox/hook";
import { HookSimulator } from "@abox/hook/simulator";
import { createGenerationContext } from "@abox/hook/ai-context";
import basicPlan from "../examples/basic.hook.js";
import { evidence } from "../examples/evidence.js";

const params = { text: param.string("old", { maxLength: 10 }), count: param.integer(1, { minimum: 0, maximum: 10 }) };
type TestPlan = HookPlan<typeof params>;
function plan(setup: TestPlan["setup"]): TestPlan {
  return definePlan({ id: "test-plan", target: { ...evidence.target }, params, setup });
}
function method(ctx: PlanContext<{ readonly text: string; readonly count: number }>) {
  return ctx.java.method("com.example.hookdemo.Greeter", "greet", ["java.lang.String"]);
}
function prepared() { return preparePlan(plan(ctx => method(ctx).replace(({ params }) => params.text)), evidence); }
function runtime() {
  const r = new HookSimulator<typeof params>(evidence);
  r.activate(prepared());
  return r;
}
function changed(patch: Partial<EvidenceSnapshot>): EvidenceSnapshot { return { ...structuredClone(evidence), ...patch }; }

test("standard TS plan models Java and Native replacements without invoking the original", () => {
  const r = new HookSimulator<typeof basicPlan.params>(evidence);
  r.activate(preparePlan(basicPlan, evidence));
  let calls = 0;
  assert.equal(r.invoke("greet", ["input"], () => { calls++; return "original"; }).value, "你好，ABox");
  assert.equal(r.invoke("delay", [], () => { calls++; return 1000; }).value, 250);
  assert.equal(calls, 0);
  assert.equal(r.inspect().androidValidated, false);
});

test("parameters expose a standard JSON Schema and reject unknown keys and invalid values", () => {
  assert.equal(parameterJsonSchema(params).additionalProperties, false);
  assert.equal(parameterJsonSchema(params).properties.count?.type, "integer");
  assert.deepEqual(resolveParameters(params), { text: "old", count: 1 });
  assert.throws(() => resolveParameters(params, { other: 1 }), { code: "UNKNOWN_PARAM" });
  assert.throws(() => resolveParameters(params, JSON.parse('{"__proto__": 1}')), { code: "UNKNOWN_PARAM" });
  assert.throws(() => resolveParameters(params, { text: 2 }), { code: "PARAM_TYPE" });
  for (const count of [-1, 11, 1.5, Infinity, NaN]) {
    assert.throws(() => resolveParameters(params, { count }), { code: "PARAM_RANGE" });
  }
  assert.throws(() => resolveParameters(params, { count: undefined }), { code: "PARAM_TYPE" });
  assert.throws(() => param.integer(2, { minimum: 3, maximum: 1 }), { code: "PARAM_SCHEMA" });
  assert.throws(() => param.number(NaN), { code: "PARAM_RANGE" });
  assert.equal(resolveParameters({ text: param.string("😀", { maxLength: 1 }) }).text, "😀");
});

test("invalid parameter updates keep the active revision and values unchanged", () => {
  const r = runtime();
  r.updateParameters({ text: "new" });
  const before = r.inspect();
  assert.throws(() => r.updateParameters({ count: 99 }), { code: "PARAM_RANGE" });
  assert.deepEqual(r.inspect(), before);
  assert.equal(r.invoke("greet", ["input"], () => "original").value, "new");
});

test("package, version and APK hash must all match before setup runs", () => {
  for (const target of [
    { ...evidence.target, packageName: "other.app" },
    { ...evidence.target, versionCode: "2" },
    { ...evidence.target, apkSha256: "b".repeat(64) },
  ]) {
    let setupRan = false;
    assert.throws(() => preparePlan({ ...plan(() => { setupRan = true; }), target }, evidence), { code: "TARGET_MISMATCH" });
    assert.equal(setupRan, false);
  }
});

test("activation rejects stale process, symbol and capability evidence", () => {
  for (const next of [
    changed({ processInstanceId: "restarted-process" }),
    changed({ capabilities: ["java-hook"] }),
    changed({ symbols: evidence.symbols.map(s => ({ ...s, loaded: false })) }),
  ]) {
    assert.throws(() => new HookSimulator<typeof params>(next).activate(prepared()), { code: "STALE_EVIDENCE" });
  }
});

test("snapshot data and parameters cannot be mutated after preparation", () => {
  const mutable = structuredClone(evidence);
  const p = preparePlan(plan(ctx => method(ctx).replace(({ params }) => params.text)), mutable);
  (mutable.target as { versionCode: string }).versionCode = "99";
  assert.equal(p.evidence.target.versionCode, "1");
  assert.throws(() => { (p.parameters as { text: string }).text = "tampered"; }, TypeError);
  assert.throws(() => { (p.rules[0]!.symbol as { loaded: boolean }).loaded = false; }, TypeError);
});

test("unknown Java methods and ambiguous class loaders are not guessed", () => {
  assert.throws(() => preparePlan(plan(ctx => ctx.java.method("missing.Class", "greet", []).replace(() => "x")), evidence), { code: "TARGET_NOT_FOUND" });
  const java = evidence.symbols[0]!;
  assert.equal(java.kind, "java");
  if (java.kind !== "java") throw new Error("fixture");
  const duplicate = changed({ symbols: [...evidence.symbols, { ...java, id: "greet-other", classLoaderId: "plugin" }] });
  assert.throws(() => preparePlan(plan(ctx => method(ctx).replace(() => "x")), duplicate), { code: "TARGET_AMBIGUOUS" });
  const p = preparePlan(plan(ctx => ctx.java.method(java.className, java.methodName, java.argumentTypes,
    { classLoaderId: "plugin" }).replace(() => "x")), duplicate);
  assert.equal(p.rules[0]?.symbol.id, "greet-other");
});

test("missing backend capabilities block preparation", () => {
  assert.throws(() => preparePlan(plan(ctx => method(ctx).replace(() => "x")), changed({ capabilities: [] })), { code: "CAPABILITY_MISSING" });
});

test("Native signature provenance, Build ID and ABI must match", () => {
  const make = (buildId = "fixture-build-001", returnType: "int32" | "double" = "int32") => plan(ctx => {
    ctx.native.function("libhookdemo.so", "retry_delay_ms", { argumentTypes: [], returnType, buildId }).replace(() => 1);
  });
  assert.throws(() => preparePlan(make("other-build"), evidence), { code: "BUILD_ID_MISMATCH" });
  assert.throws(() => preparePlan(make(undefined, "double"), evidence), { code: "SIGNATURE_MISMATCH" });
  const unknown = changed({ symbols: evidence.symbols.map(s => s.kind === "native" ? { ...s, signatureSource: "unknown" } : s) });
  assert.throws(() => preparePlan(make(), unknown), { code: "SIGNATURE_UNKNOWN" });
});

test("duplicate mutations and replace/before conflicts are rejected", () => {
  assert.throws(() => preparePlan(plan(ctx => {
    method(ctx).replace(() => "one");
    method(ctx).replace(() => "two");
  }), evidence), { code: "HOOK_CONFLICT" });
  assert.throws(() => preparePlan(plan(ctx => {
    method(ctx).before(({ args }) => args).replace(() => "two");
  }), evidence), { code: "HOOK_CONFLICT" });
  assert.throws(() => preparePlan(plan(() => {}), evidence), { code: "EMPTY_PLAN" });
  assert.throws(() => preparePlan(plan(ctx => { method(ctx); }), evidence), { code: "EMPTY_HOOK" });
});

test("indexed but unloaded symbols remain pending and never report a hit", () => {
  const unloaded = changed({ symbols: evidence.symbols.map(s => ({ ...s, loaded: false })) });
  const r = new HookSimulator<typeof params>(unloaded);
  r.activate(preparePlan(plan(ctx => method(ctx).replace(() => "x")), unloaded));
  assert.equal(r.inspect().rules[0]?.status, "waiting-target");
  assert.throws(() => r.invoke("greet", ["x"], () => "original"), { code: "WAITING_TARGET" });
  assert.equal(r.events().length, 0);
});

test("an in-flight invocation keeps its parameter snapshot across a reentrant update", () => {
  const r = new HookSimulator<typeof params>(evidence);
  const seen: string[] = [];
  r.activate(preparePlan(plan(ctx => {
    method(ctx).before(call => {
      seen.push(call.params.text);
      r.updateParameters({ text: "new" });
      return call.args;
    }).after(call => { seen.push(call.params.text); return call.params.text; });
  }), evidence));
  const first = r.invoke("greet", ["x"], () => "original");
  assert.deepEqual(seen, ["old", "old"]);
  assert.equal(first.revision, 1);
  assert.equal(first.value, "old");
  assert.equal(r.invoke("greet", ["x"], () => "original").value, "new");
});

test("a code revision swap does not change the after handler of an in-flight call", () => {
  const r = new HookSimulator<typeof params>(evidence);
  const next = preparePlan(plan(ctx => method(ctx).replace(() => "new-code")), evidence);
  const old = preparePlan(plan(ctx => method(ctx).before(({ args }) => {
    r.activate(next);
    return args;
  }).after(() => "old-after")), evidence);
  r.activate(old);
  assert.equal(r.invoke("greet", ["x"], () => "original").value, "old-after");
  assert.equal(r.invoke("greet", ["x"], () => "original").value, "new-code");
});

test("rollback creates a new revision restoring both code and parameters", () => {
  const r = runtime();
  r.updateParameters({ text: "changed" });
  r.activate(preparePlan(plan(ctx => method(ctx).replace(() => "new-code")), evidence));
  assert.equal(r.rollback(2), 4);
  assert.equal(r.invoke("greet", ["x"], () => "original").value, "changed");
  assert.equal(r.rollback(1), 5);
  assert.equal(r.invoke("greet", ["x"], () => "original").value, "old");
  assert.throws(() => r.rollback(99), { code: "REVISION_NOT_FOUND" });
});

test("failed before hooks throw without calling the original or after handler", () => {
  const failure = new Error("before failed");
  for (const before of [() => [123], () => { throw failure; }]) {
    const r = new HookSimulator<typeof params>(evidence);
    let afterCalls = 0, calls = 0;
    r.activate(preparePlan(plan(ctx => method(ctx).before(before).after(() => { afterCalls++; return "bad"; })), evidence));
    assert.throws(() => r.invoke("greet", ["original-arg"], args => { calls++; return args[0]; }),
      error => error === failure || (error instanceof Error && "code" in error && error.code === "HOOK_TYPE"));
    assert.equal(calls, 0);
    assert.equal(afterCalls, 0);
    assert.equal(r.events().filter(e => e.kind === "hook-error" && e.stage === "before").length, 1);
    assert.equal(r.inspect().rules[0]?.status, "simulation-error");
  }
});

test("failed after hooks throw after exactly one original invocation", () => {
  const failure = new Error("after failed");
  for (const after of [() => 42, () => { throw failure; }]) {
    const r = new HookSimulator<typeof params>(evidence);
    r.activate(preparePlan(plan(ctx => method(ctx).after(after)), evidence));
    let calls = 0;
    assert.throws(() => r.invoke("greet", ["x"], () => { calls++; return "original-result"; }),
      error => error === failure || (error instanceof Error && "code" in error && error.code === "HOOK_TYPE"));
    assert.equal(calls, 1);
    assert.equal(r.events().filter(e => e.kind === "hook-error" && e.stage === "after").length, 1);
    assert.equal(r.inspect().rules[0]?.status, "simulation-error");
  }
});

test("failed replacements throw without invoking the original", () => {
  const failure = new Error("replace failed");
  for (const replace of [() => 42, () => { throw failure; }, () => { throw "non-error failure"; }]) {
    const r = new HookSimulator<typeof params>(evidence);
    r.activate(preparePlan(plan(ctx => method(ctx).replace(replace)), evidence));
    let calls = 0;
    assert.throws(() => r.invoke("greet", ["x"], () => { calls++; return "original"; }),
      error => error === failure || (error instanceof Error && "code" in error && ["HOOK_TYPE", "HOOK_EXECUTION"].includes(String(error.code))));
    assert.equal(calls, 0);
    assert.equal(r.events().filter(e => e.kind === "hook-error" && e.stage === "replace").length, 1);
    assert.equal(r.inspect().rules[0]?.status, "simulation-error");
  }
});

test("original exceptions propagate unchanged and are never retried", () => {
  const error = new Error("original failed");
  const r = new HookSimulator<typeof params>(evidence);
  let afterCalls = 0;
  r.activate(preparePlan(plan(ctx => method(ctx).after(() => { afterCalls++; return "bad"; })), evidence));
  let calls = 0;
  assert.throws(() => r.invoke("greet", ["x"], () => { calls++; throw error; }), e => e === error);
  assert.equal(calls, 1);
  assert.equal(afterCalls, 0);
  assert.equal(r.events().filter(e => e.kind === "hook-error").length, 0);
  assert.equal(r.inspect().rules[0]?.status, "simulation-error");
});

test("async hooks are rejected at registration and thenables are rejected during simulation", () => {
  assert.throws(() => preparePlan(plan(ctx => {
    // @ts-expect-error Async return values are also rejected by the SDK's TypeScript surface.
    method(ctx).replace(async () => "async");
  }), evidence), { code: "ASYNC_HOOK" });
  const r = new HookSimulator<typeof params>(evidence);
  r.activate(preparePlan(plan(ctx => method(ctx).replace(() => Promise.resolve("async") as unknown as HookValue)), evidence));
  let calls = 0;
  assert.throws(() => r.invoke("greet", ["x"], () => { calls++; return "original"; }), { code: "ASYNC_HOOK" });
  assert.equal(calls, 0);
});

test("async setup is rejected; hook registration closes after preparation", () => {
  assert.throws(() => preparePlan(plan(async ctx => { method(ctx).replace(() => "x"); }), evidence), { code: "ASYNC_SETUP" });
  let late: (() => void) | undefined;
  preparePlan(plan(ctx => { const b = method(ctx); b.after(({ result }) => result); late = () => b.before(({ args }) => args); }), evidence);
  assert.throws(() => late!(), { code: "SETUP_CLOSED" });
});

test("Native int32 boundaries are validated at runtime", () => {
  const r = new HookSimulator<typeof params>(evidence);
  r.activate(preparePlan(plan(ctx => ctx.native.function("libhookdemo.so", "retry_delay_ms", {
    argumentTypes: [], returnType: "int32", buildId: "fixture-build-001",
  }).replace(() => 2147483648)), evidence));
  let calls = 0;
  assert.throws(() => r.invoke("delay", [], () => { calls++; return 5; }), { code: "HOOK_TYPE" });
  assert.equal(calls, 0);
});

test("events and rollback history are bounded without losing current hit totals", () => {
  const r = runtime();
  for (let i = 0; i < 200; i++) r.invoke("greet", ["x"], () => "original");
  assert.equal(r.events().length, 128);
  assert.equal(r.inspect().rules[0]?.hits, 200);
  for (let i = 0; i < 33; i++) r.updateParameters({ text: "new" });
  assert.throws(() => r.rollback(1), { code: "REVISION_NOT_FOUND" });
});

test("AI context is grounded data with explicit unsupported capabilities and a draft-only contract", () => {
  const context = createGenerationContext("修改返回文字", evidence);
  assert.equal(context.evidence.source, "fixture");
  assert.equal(context.outputContract.status, "draft");
  assert.equal(context.outputContract.evidenceId, evidence.id);
  assert.equal(context.evidence.symbols.length, 2);
  assert.ok(context.unsupported.includes("remote model calls"));
  assert.doesNotThrow(() => JSON.stringify(context));
});

test("return-type ambiguity can be resolved without guessing a Java bridge method", () => {
  const java = evidence.symbols[0]!;
  if (java.kind !== "java") throw new Error("fixture");
  const snapshot = changed({ symbols: [...evidence.symbols, { ...java, id: "greet-bridge", returnType: "int" }] });
  assert.throws(() => preparePlan(plan(ctx => method(ctx).replace(() => "x")), snapshot), { code: "TARGET_AMBIGUOUS" });
  const p = preparePlan(plan(ctx => ctx.java.method(java.className, java.methodName, java.argumentTypes,
    { classLoaderId: "main", returnType: "int" }).replace(() => 1)), snapshot);
  assert.equal(p.rules[0]?.symbol.id, "greet-bridge");
});

test("invalid evidence is rejected before hook setup", () => {
  const targetPlan = plan(ctx => method(ctx).replace(() => "x"));
  assert.throws(() => preparePlan(targetPlan, changed({ symbols: [...evidence.symbols, evidence.symbols[0]!] })), { code: "INVALID_EVIDENCE" });
  assert.throws(() => preparePlan(targetPlan, changed({ abi: "x86" as "arm64-v8a" })), { code: "UNSUPPORTED_ABI" });
  assert.throws(() => preparePlan(targetPlan, changed({ symbols: evidence.symbols.map(s => ({ ...s, argumentTypes: ["void"] })) })), { code: "INVALID_SIGNATURE" });
});

test("failed preparation leaves a running plan and its counters untouched", () => {
  const r = runtime();
  r.invoke("greet", ["x"], () => "original");
  const current = r.inspect();
  assert.throws(() => r.activate(preparePlan(plan(ctx => ctx.java.method("missing", "method", []).replace(() => "x")), evidence)), { code: "TARGET_NOT_FOUND" });
  assert.deepEqual(r.inspect(), current);
});

test("byte[] ([B and byte[]) Java hooks match normalized types and validate hex encoding", () => {
  const cryptoEvidence = changed({
    symbols: [
      ...evidence.symbols,
      {
        id: "mac-sign",
        kind: "java",
        loaded: true,
        className: "javax.crypto.Mac",
        methodName: "doFinal",
        classLoaderId: "main",
        argumentTypes: ["[B"],
        returnType: "[B",
      },
    ],
  });
  const r = new HookSimulator<typeof params>(cryptoEvidence);
  const p = preparePlan(plan(ctx => {
    ctx.java.method("javax.crypto.Mac", "doFinal", ["byte[]"], { returnType: "byte[]" })
      .before(({ args }) => [args[0] === "deadbeef" ? "cafebabe" : args[0]])
      .after(({ result }) => result);
  }), cryptoEvidence);
  r.activate(p);
  assert.equal(r.invoke("mac-sign", ["deadbeef"], args => args[0]).value, "cafebabe");
  assert.equal(r.invoke("mac-sign", [null], () => null).value, null);
  assert.throws(() => r.invoke("mac-sign", ["xyz"], () => "00"), { code: "HOOK_TYPE" });
  assert.throws(() => r.invoke("mac-sign", ["abc"], () => "00"), { code: "HOOK_TYPE" });
});

