import Java from "frida-java-bridge";
import { prepareDraft } from "../../../src/draft.js";
import { snapshotEvidence, type EvidenceSnapshot } from "../../../src/evidence.js";
import { bindJava, decodeJava, encodeJava, type JavaBinding } from "./hook_java.js";
import { executeRule, OriginalThrown } from "./hook_execution.js";
import { resolveParameters } from "../../../src/parameters.js";
import { HookError } from "../../../src/errors.js";
import { NativeTargets, nativeAbi, decodeNative, encodeNative, type NativeBinding } from "./hook_native.js";

/** One agent owns adaptation and user hooks; only observed guest methods and verified modules. */
export function installHooks(): void {
  const Bridge = Java.use("dev.tripoder.runtime.RuntimeHookBridge");
  const Failure = Java.use("java.lang.IllegalStateException");
  const Clock = Java.use("android.os.SystemClock");
  const exit = new NativeFunction(Process.getModuleByName("libc.so").getExportByName("_exit"), "void", ["int"]);
  const log = new NativeFunction(Process.getModuleByName("liblog.so").getExportByName("__android_log_write"), "int", ["int", "pointer", "pointer"]);
  const tag = Memory.allocUtf8String("TripoderHook");
  let evidence: EvidenceSnapshot | undefined;
  type Prepared = { token: string; sourceSha256: string; plan: ReturnType<typeof prepareDraft>; deadline: number };
  let prepared: Prepared | undefined;
  let commandInFlight = false;
  type Binding = JavaBinding | NativeBinding;
  let active: { revision: string; sourceSha256: string; bindings: Binding[]; plan: Prepared["plan"]; parameters: Prepared["plan"]["parameters"]; parameterVersion: number; calls: number; hits: number; errors: number; state: "ACTIVE" | "DRAINING" } | undefined;
  // Keep native callback handles through process life, including entries queued
  // before unpatching but not yet counted in JS. Bound retention, never free early.
  const retired: Binding[][] = [];
  const deoptimized = new Set<string>();
  const native = new NativeTargets(Bridge, generation => {
    if (active?.bindings.some(binding => binding.kind === "native" && binding.loaded.generation === generation)) fatal("HOOK_ACTIVE_MODULE_UNLOADED");
    if (native.contains(generation)) { evidence = undefined;prepared = undefined; }
  },fatal);
  function requireThat(ok: unknown, code: string): asserts ok { if (!ok) throw new Error(code); }
  function failureText(error: unknown): string { return error instanceof HookError ? `${error.code}: ${error.message}` : String(error); }
  function fatal(error: unknown): never {
    try { log(6, tag, Memory.allocUtf8String("HOOK_PROCESS_TERMINATED:" + failureText(error).slice(0, 500))); }
    finally { for (;;) exit(71); }
  }
  function status() {
    if (active?.state === "DRAINING" && active.calls === 0) {
      try { for (const binding of active.bindings) {
        if (binding.kind === "java") binding.method.implementation = null;else Interceptor.revert(binding.address);
        binding.installed = false;
      } Interceptor.flush();retired.push(active.bindings); }
      catch (error) { fatal(error); }
      const result = { state: "DISABLED", revision: active.revision, hits: active.hits, errors: active.errors };
      active = undefined;evidence=undefined;native.clear();return result;
    }
    return active ? { state: active.state, revision: active.revision, sourceSha256: active.sourceSha256, parameterVersion: active.parameterVersion, parameters: active.parameters, hits: active.hits, errors: active.errors, inFlight: active.calls }
      : prepared ? { state: "PREPARED", sourceSha256: prepared.sourceSha256, deadlineElapsedMs: prepared.deadline }
      : evidence ? { state:"OBSERVED",evidenceId:evidence.id } : { state: "IDLE" };
  }
  function command(input: any): any {
    requireThat(input && typeof input === "object" && !Array.isArray(input), "HOOK_REQUEST_OBJECT");
    const fields: Record<string, string[]> = { modules: [], observe: ["selectors"], prepare: ["evidenceId", "source"],
      activate: ["token", "sourceSha256"], disable: ["revision"], updateParameters: ["revision", "parameterVersion", "parameters"], cancelPreparation: ["token"], discard: [], status: [] };
    const allowed = Object.hasOwn(fields, input.command) ? fields[input.command] : undefined;
    requireThat(allowed && Object.keys(input).length === allowed.length + 2
      && Object.keys(input).every(key => ["command", "processGeneration", ...allowed].includes(key)), "HOOK_COMMAND_FIELDS");
    const identity = JSON.parse(String(Bridge.identity()));
    requireThat(input.processGeneration === identity.processGeneration, "HOOK_PROCESS_STALE");
    if (prepared && Number(Clock.elapsedRealtime()) > prepared.deadline) prepared = undefined;
    switch (input.command) {
      case "modules": return { modules: native.inventory(), identity };
      case "observe": {
        requireThat(!active && !prepared, "HOOK_CONTEXT_IN_USE");
        evidence = undefined;native.clear();
        requireThat(Array.isArray(input.selectors) && input.selectors.length > 0 && input.selectors.length <= 32
          && input.selectors.every((selector: any) => selector && ["java", "native"].includes(selector.kind)), "HOOK_SELECTOR_COUNT_OR_KIND");
        const symbols = JSON.parse(String(Bridge.observe(JSON.stringify(input.selectors.filter((selector: any) => selector.kind === "java")))));
        symbols.push(...native.observe(input.selectors.filter((selector: any) => selector.kind === "native")));
        evidence = snapshotEvidence({ id: String(Bridge.token()), source: "guest-agent", target: identity.target,
          processInstanceId: `${Process.id}:${identity.processGeneration}`, abi: "arm64-v8a", capabilities: ["java-hook", "native-hook"], symbols });
        return { evidence, identity, nativeModules: native.evidence() };
      }
      case "prepare": {
        requireThat(!active && !prepared, "HOOK_CONTEXT_IN_USE");
        requireThat(evidence && input.evidenceId === evidence.id, "HOOK_EVIDENCE_STALE");
        requireThat(typeof input.source === "string" && input.source.length <= 24000, "HOOK_SOURCE_SIZE");
        const plan = prepareDraft(input.source, evidence);
        requireThat(plan.rules.length <= 32 && retired.length < 32, "HOOK_ACTIVATION_BUDGET");
        prepared = { token: String(Bridge.token()), sourceSha256: String(Bridge.digest(input.source)), plan, deadline: Number(Clock.elapsedRealtime()) + 60000 };
        return { state: "PREPARED", token: prepared.token, sourceSha256: prepared.sourceSha256, evidenceId: evidence.id, rules: plan.rules.length, identity };
      }
      case "activate": {
        requireThat(!active && prepared && input.token === prepared.token && input.sourceSha256 === prepared.sourceSha256, "HOOK_PREPARATION_STALE");
        const candidate = prepared;prepared = undefined;
        const next = { revision: String(Bridge.token()), sourceSha256: candidate.sourceSha256, bindings: [] as Binding[], plan: candidate.plan, parameters: candidate.plan.parameters, parameterVersion: 1, calls: 0, hits: 0, errors: 0, state: "ACTIVE" as const };
        // Class initialization and all method mutations belong to activation. If
        // either has uncertain partial effects, the process cannot remain usable.
        try {
          for (const rule of candidate.plan.rules) {
            const binding = rule.symbol.kind === "java" ? bindJava(Bridge, rule.symbol) : native.bind(rule.symbol);next.bindings.push(binding);
          }
          // ART deoptimization is a process-lifetime state and requires the
          // original non-native ArtMethod. Snapshot/install only after this step.
          for (const binding of next.bindings) if (binding.kind === "java" && !binding.nativeDeclaration) {
            const key = String(binding.method.handle);
            if (!deoptimized.has(key)) { (Java as any).deoptimizeMethod(binding.method.handle);deoptimized.add(key); }
          }
          active = next;
          for (let index = 0; index < next.bindings.length; index++) {
            const binding = next.bindings[index]!, rule = candidate.plan.rules[index]!;
            if (binding.kind === "native") {
              const symbol = binding.symbol;
              binding.replacement = new NativeCallback((...incoming: any[]) => {
                const enabled = next.state === "ACTIVE", parameters = next.parameters;
                next.calls++;if (enabled) next.hits++;
                try {
                  requireThat(binding.loaded.live, "HOOK_MODULE_GENERATION_STALE");
                  if (!enabled) return binding.original(...incoming);
                  requireThat(next.calls <= 64, "HOOK_REENTRY_BUDGET");
                  const args = incoming.map((value, i) => decodeNative(symbol.argumentTypes[i]!, value));
                  const original = (values: readonly any[]) => decodeNative(symbol.returnType,
                    binding.original(...values.map((value, i) => encodeNative(symbol.argumentTypes[i]!, value))));
                  return encodeNative(symbol.returnType, executeRule(rule, parameters, args, original));
                } catch (error) { next.errors++;fatal(error); }
                finally { next.calls--; }
              }, nativeAbi(symbol.returnType), symbol.argumentTypes.map(nativeAbi));
              Interceptor.replace(binding.address, binding.replacement);binding.installed = true;continue;
            }
            binding.method.implementation = function (this: any, ...incoming: any[]) {
              const enabled = next.state === "ACTIVE";
              const parameters = next.parameters;
              next.calls++;if (enabled) next.hits++;
              try {
                const call = (args: readonly any[]) => { try { return binding.method.call(this, ...args); } catch (error) { throw new OriginalThrown(error); } };
                if (!enabled) return call(incoming);
                requireThat(next.calls <= 64, "HOOK_REENTRY_BUDGET");
                const args = incoming.map((value, i) => decodeJava(binding.symbol.argumentTypes[i]!, value));
                const original = (values: readonly any[]) => decodeJava(binding.symbol.returnType,
                  call(values.map((value, i) => encodeJava(binding.symbol.argumentTypes[i]!, value))));
                return encodeJava(binding.symbol.returnType, executeRule(rule, parameters, args, original));
              } catch (error) {
                if (error instanceof OriginalThrown) throw error.value;
                next.errors++;throw Failure.$new("HOOK_EXECUTION:" + failureText(error).slice(0, 500));
              }
              finally { next.calls--; }
            };
            binding.replacement = binding.method.implementation;
            binding.installed = true;
          }
          Interceptor.flush();return { ...status(), identity };
        } catch (error) { fatal(error); }
      }
      case "disable":
        requireThat(active && input.revision === active.revision, "HOOK_REVISION_STALE");active.state = "DRAINING";return status();
      case "updateParameters": {
        requireThat(active?.state === "ACTIVE" && input.revision === active.revision && input.parameterVersion === active.parameterVersion, "HOOK_PARAMETER_VERSION_STALE");
        requireThat(input.parameters && typeof input.parameters === "object" && !Array.isArray(input.parameters), "HOOK_PARAMETERS_OBJECT");
        const values = resolveParameters(active.plan.parameterSpecs, { ...active.parameters, ...input.parameters });
        requireThat(JSON.stringify(values).length <= 8192 && active.parameterVersion < 10000, "HOOK_PARAMETER_BUDGET");
        active.parameters = values;active.parameterVersion++;return status();
      }
      case "cancelPreparation":
        requireThat(prepared && input.token === prepared.token, "HOOK_PREPARATION_STALE");prepared = undefined;return { state: "CANCELED" };
      case "discard":
        requireThat(!active,"HOOK_CONTEXT_IN_USE");prepared=undefined;evidence=undefined;native.clear();Bridge.observe("[]");return {state:"IDLE"};
      case "status": return { ...status(), moduleEvents: native.lifecycle() };
      default: throw new Error("HOOK_COMMAND_UNSUPPORTED");
    }
  }
  // The Java entry returns before ART instrumentation walks stacks. Executing
  // deoptimization inside a Java replacement's JNI callback can expose the
  // transitional managed stack to that same walk.
  Bridge.submit.implementation = (idValue: any, rawValue: any) => {
    const id=String(idValue),raw=String(rawValue);
    if (commandInFlight) { Bridge.complete(id,JSON.stringify({ ok: false, error: "HOOK_CONTROL_BUSY" }));return; }
    commandInFlight = true;
    setImmediate(() => Java.performNow(() => {
      let response: string;
      try { response=JSON.stringify({ ok:true,result:command(JSON.parse(raw)) }); }
      catch (error) { response=JSON.stringify({ ok:false,error:failureText(error).slice(0,1000) }); }
      finally { commandInFlight=false; }
      if (!Bridge.complete(id,response)) log(6,tag,Memory.allocUtf8String("HOOK_ACK_NOT_DELIVERED:"+id));
    }));
  };
}
