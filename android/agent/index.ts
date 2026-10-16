import Java from "frida-java-bridge";
import { definePlan, preparePlan } from "../../src/plan.js";
import { param, resolveParameters } from "../../src/parameters.js";
import type { Parameters } from "../../src/parameters.js";
import type { PreparedPlan } from "../../src/plan.js";
import { prepareDraft, draftTemplate } from "../../src/draft.js";
import { validateArguments, validateHookValue } from "../../src/evidence.js";
import type { EvidenceSnapshot, HookValue } from "../../src/evidence.js";

// This adapter deliberately supports only our two owned test targets, synchronous callbacks,
// and validated parameter updates. It is not a generic sandbox or cross-process engine.
setImmediate(() => {
  Java.perform(() => {
    const Bridge = Java.use("dev.tripoder.hooklab.AgentBridge");
    const identity = JSON.parse(String(Bridge.identity()));
    const isGuest = identity.mode === "guest";
    const IllegalStateException = Java.use("java.lang.IllegalStateException");
    const Build = Java.use("android.os.Build");
    const originalEnvironment = Object.fromEntries(["MODEL", "MANUFACTURER", "BRAND"].map(k => [k, String(Build[k].value)]));
    if (isGuest) {
      const Activity = Java.use("android.app.Activity");
      Activity.getApplication.implementation = function (this: any) {
        const application = Bridge.application();
        return Bridge.ownsActivity(this) && application !== null ? application : this.mApplication.value;
      };
    }
    const factory = isGuest ? Java.ClassFactory.get(Bridge.loader()) : Java.classFactory;
    const Target = factory.use(isGuest ? "dev.tripoder.fixture.GuestProbe" : "dev.tripoder.hooklab.TestTarget");
    const greet = Target.greet.overload("java.lang.String");
    // Keep the selected target out of ART's optimized entry path; no global deoptimization.
    if (Number(Java.androidVersion) >= 11) (Java as any).deoptimizeMethod(greet.handle);
    const module = Process.getModuleByName(isGuest ? "libguestfixture.so" : "libhooktarget.so");
    const symbolName = isGuest ? "guest_delay_ms" : "retry_delay_ms";
    const address = module.getExportByName(symbolName);
    const buildId = String(Target.nativeBuildId());
    const evidence: EvidenceSnapshot = {
      id: identity.session, source: "guest-agent", target: identity.target,
      processInstanceId: `${Process.id}:${identity.session}`, abi: "arm64-v8a",
      capabilities: ["java-hook", "native-hook"],
      symbols: [
        { id: "greet", kind: "java", loaded: true, className: Target.$className,
          methodName: "greet", classLoaderId: String(Target.class.getClassLoader()),
          argumentTypes: ["java.lang.String"], returnType: "java.lang.String" },
        { id: "delay", kind: "native", loaded: true, moduleName: module.name,
          symbol: symbolName, buildId, argumentTypes: ["int32"], returnType: "int32", signatureSource: "confirmed" },
      ],
    };
    const plan = definePlan<Parameters>({
      id: "android-owned-targets", target: evidence.target,
      params: { message: param.string("hook:"), delayMs: param.integer(500, { minimum: 0, maximum: 10000 }) },
      setup(ctx) {
        ctx.java.method(Target.$className, "greet", ["java.lang.String"])
          .replace(({ args, params }) => String(params.message) + String(args[0]));
        ctx.native.function(module.name, symbolName, { argumentTypes: ["int32"], returnType: "int32", buildId })
          .replace(({ args, params }) => Number(params.delayMs) + Number(args[0]));
      },
    });
    const prepared = preparePlan(plan, evidence);
    type State = { plan: PreparedPlan<Parameters>; params: Readonly<Record<string, string | number | boolean>>; source: string };
    const initial: State = { plan: prepared, params: prepared.parameters, source: "" };
    let current = initial, previous = initial;
    let enabled = false, revision = 0, javaHits = 0, nativeHits = 0;
    const errors: { symbol: string; stage: string; message: string }[] = [];
    const originalNative = new NativeFunction(address, "int", ["int"], { exceptions: "propagate" });
    const nativeExit = new NativeFunction(Process.getModuleByName("libc.so").getExportByName("_exit"),
      "void", ["int"], { exceptions: "propagate" });
    const nativeLog = new NativeFunction(Process.getModuleByName("liblog.so").getExportByName("__android_log_write"),
      "int", ["int", "pointer", "pointer"], { exceptions: "propagate" });
    const logTag = Memory.allocUtf8String("Tripoder");
    function errorMessage(error: unknown): string {
      return error instanceof Error ? error.message.slice(0, 500) : "Callback threw a non-Error value";
    }
    function terminateNative(error: unknown): never {
      // A JS exception cannot cross a C ABI safely. Record the failure synchronously,
      // then end this process; never return an invented value or call the original.
      try {
        const message = `HOOK_EXECUTION: Native callback failed; terminating process ${Process.id}: ${errorMessage(error)}`;
        nativeLog(6, logTag, Memory.allocUtf8String(message));
      } finally {
        // libc _exit never returns. The loop also prevents an accidental JS return.
        for (;;) nativeExit(70);
      }
    }
    function commit(next: State) { previous = current; current = next; revision++; }
    function invoke(symbolId: string, incoming: HookValue[], original: (args: readonly HookValue[]) => HookValue): HookValue {
      const state = current;
      const rule = state.plan.rules.find(r => r.symbol.id === symbolId);
      if (!rule) return original(incoming);
      if (symbolId === "greet") javaHits++; else nativeHits++;
      const params = state.params;
      let args: readonly HookValue[] = Object.freeze([...incoming]);
      const fail = (stage: string, error: unknown): never => {
        const message = `HOOK_EXECUTION: ${symbolId} ${stage}: ${errorMessage(error)}`;
        errors.push({ symbol: symbolId, stage, message });
        if (errors.length > 20) errors.shift();
        if (rule.symbol.kind === "java") throw IllegalStateException.$new(message);
        throw new Error(message);
      };
      if (rule.handlers.replace) {
        try {
          const value = rule.handlers.replace({ args, params });
          validateHookValue(rule.symbol.returnType, value, symbolId); return value;
        } catch (error) { return fail("replace", error); }
      }
      if (rule.handlers.before) {
        try {
          const next = rule.handlers.before({ args, params });
          if (!Array.isArray(next)) throw new Error("before must return an array");
          validateArguments(rule.symbol, next); args = Object.freeze([...next]);
        } catch (error) { return fail("before", error); }
      }
      let result = original(args);
      if (rule.handlers.after) {
        try {
          const next = rule.handlers.after({ args, params, result });
          validateHookValue(rule.symbol.returnType, next, symbolId); result = next;
        } catch (error) { return fail("after", error); }
      }
      return result;
    }
    const bytes = () => Array.from(new Uint8Array(address.readByteArray(32)!))
      .map(v => v.toString(16).padStart(2, "0")).join("");
    const originalBytes = bytes();
    const replacement = new NativeCallback((base: number) => {
      try {
        return Number(invoke("delay", [base], args => originalNative(Number(args[0]))));
      } catch (error) {
        return terminateNative(error);
      }
    }, "int", ["int"]);

    function install() {
      if (enabled) return;
      Interceptor.replace(address, replacement);
      try {
        greet.implementation = function (this: any, name: unknown): HookValue {
          return invoke("greet", [name == null ? null : String(name)], args => greet.call(this, args[0]));
        };
        Interceptor.flush();
        enabled = true;
        revision++;
      } catch (error) {
        greet.implementation = null;
        Interceptor.revert(address);
        Interceptor.flush();
        throw error;
      }
    }
    function uninstall() {
      if (!enabled) return;
      greet.implementation = null;
      Interceptor.revert(address);
      Interceptor.flush();
      enabled = false;
      revision++;
    }
    function status() {
      return { ok: true, backend: "embedded-frida", frida: Frida.version,
        enabled, revision, javaHits, nativeHits, params: current.params, evidence, mode: isGuest ? "guest" : "host",
        planId: current.plan.id, source: current.source, errors, template: draftTemplate(),
        nativeAddress: address.toString(), nativeCodeChanged: bytes() !== originalBytes };
    }
    Bridge.dispatch.overload("java.lang.String").implementation = (input: unknown) => {
      try {
        const command = JSON.parse(String(input));
        switch (command.op) {
          case "status": break;
          case "enable": install(); break;
          case "disable": uninstall(); break;
          case "reset": uninstall(); commit(initial); break;
          case "environment": {
            if (!isGuest) throw new Error("Environment profiles require the owned guest process");
            const profile = command.profile;
            if (!profile || typeof profile !== "object" || Array.isArray(profile)) throw new Error("Invalid environment profile");
            for (const [key, value] of Object.entries(profile)) {
              if (!Object.hasOwn(originalEnvironment, key) || typeof value !== "string" || value.length > 100) throw new Error("Invalid environment field: " + key);
            }
            for (const [key, value] of Object.entries({ ...originalEnvironment, ...profile })) Build[key].value = value;
            return JSON.stringify({ ok: true, environment: Object.fromEntries(Object.keys(originalEnvironment).map(k => [k, String(Build[k].value)])), scope: "guest-process-java-Build-only" });
          }
          case "update": {
            const params = resolveParameters(current.plan.parameterSpecs, { ...current.params, ...command.params });
            commit({ ...current, params }); break;
          }
          case "rollback": { const next = previous; previous = current; current = next; revision++; break; }
          case "check": case "deploy": {
            if (command.session !== evidence.id) throw new Error("STALE_EVIDENCE: refresh the editor after a process restart");
            const next = prepareDraft(command.source, evidence);
            if (command.op === "check") return JSON.stringify({ ok: true, checked: true, planId: next.id,
              params: next.parameters, targets: next.rules.map(r => r.symbol.id), session: evidence.id });
            install(); commit({ plan: next, params: next.parameters, source: command.source }); break;
          }
          default: throw new Error("Unknown operation");
        }
        return JSON.stringify(status());
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error), enabled, revision });
      }
    };
    console.log("Tripoder agent ready: " + JSON.stringify(status()));
  });
});
