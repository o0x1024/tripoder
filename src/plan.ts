import { requireThat } from "./errors.js";
import { evidenceKey, sameTarget, snapshotEvidence } from "./evidence.js";
import type { EvidenceSnapshot, HookSymbol, HookValue, JavaType, NativeType, Target } from "./evidence.js";
import { resolveParameters } from "./parameters.js";
import type { Parameters, ParameterValues } from "./parameters.js";

export interface HookCall<P> {
  readonly args: readonly HookValue[];
  readonly params: P;
}
export interface AfterCall<P> extends HookCall<P> { readonly result: HookValue }
export interface HookHandlers<P> {
  before?: (call: HookCall<P>) => readonly HookValue[];
  replace?: (call: HookCall<P>) => HookValue;
  after?: (call: AfterCall<P>) => HookValue;
}
export interface HookBuilder<P> {
  before(handler: NonNullable<HookHandlers<P>["before"]>): HookBuilder<P>;
  replace(handler: NonNullable<HookHandlers<P>["replace"]>): HookBuilder<P>;
  after(handler: NonNullable<HookHandlers<P>["after"]>): HookBuilder<P>;
}
export interface PlanContext<P> {
  readonly java: {
    method(className: string, methodName: string, argumentTypes: readonly JavaType[], options?: { classLoaderId?: string; returnType?: JavaType }): HookBuilder<P>;
  };
  readonly native: {
    function(moduleName: string, symbol: string, signature: {
      argumentTypes: readonly NativeType[]; returnType: NativeType; buildId: string;
    }): HookBuilder<P>;
  };
  readonly debug: {
    emitEvent(tag: string, payload?: unknown): void;
    captureJavaStack(maxDepth?: number): readonly string[];
    callerModuleOffset(): string;
  };
}
export interface HookPlan<S extends Parameters = Parameters> {
  readonly id: string;
  readonly target: Target;
  readonly params: S;
  readonly setup: (context: PlanContext<ParameterValues<S>>) => void;
}
export interface PreparedRule<P> {
  readonly symbol: HookSymbol;
  readonly handlers: Readonly<HookHandlers<P>>;
}
export interface PreparedPlan<S extends Parameters> {
  readonly id: string;
  readonly evidence: EvidenceSnapshot;
  readonly evidenceKey: string;
  readonly parameterSpecs: S;
  readonly parameters: ParameterValues<S>;
  readonly rules: readonly PreparedRule<ParameterValues<S>>[];
  readonly emitSink?: {
    fn?: ((tag: string, payload: unknown) => void) | undefined;
    stack?: ((maxDepth: number) => readonly string[]) | undefined;
    caller?: (() => string) | undefined;
  };
}

export function definePlan<S extends Parameters>(plan: HookPlan<S>): HookPlan<S> {
  return plan;
}

/** Executes trusted setup code. This is NOT an isolation boundary for AI-generated code. */
export function preparePlan<S extends Parameters>(plan: HookPlan<S>, observed: EvidenceSnapshot,
  parameters: Readonly<Record<string, unknown>> = {}): PreparedPlan<S> {
  const evidence = snapshotEvidence(observed);
  requireThat(/^[a-zA-Z][a-zA-Z0-9_.-]{0,79}$/.test(plan.id), "PLAN_ID", "Invalid plan ID");
  requireThat(sameTarget(plan.target, evidence.target), "TARGET_MISMATCH", "Package, version or APK digest changed");
  const specs = Object.freeze(Object.fromEntries(Object.entries(plan.params).map(([key, p]) =>
    [key, Object.freeze({ schema: Object.freeze({ ...p.schema }) })]))) as S;
  const values = resolveParameters(specs, parameters);
  const rules = new Map<string, { symbol: HookSymbol; handlers: HookHandlers<ParameterValues<S>> }>();
  const emitSink: {
    fn?: ((tag: string, payload: unknown) => void) | undefined;
    stack?: ((maxDepth: number) => readonly string[]) | undefined;
    caller?: (() => string) | undefined;
  } = {};
  let collecting = true;
  function builder(symbol: HookSymbol): HookBuilder<ParameterValues<S>> {
    let rule = rules.get(symbol.id);
    if (!rule) {
      rule = { symbol, handlers: {} };
      rules.set(symbol.id, rule);
    }
    const handlers = rule.handlers;
    const result: HookBuilder<ParameterValues<S>> = {
      before(fn) { add("before", fn); return result; },
      replace(fn) { add("replace", fn); return result; },
      after(fn) { add("after", fn); return result; },
    };
    function add<K extends keyof HookHandlers<ParameterValues<S>>>(stage: K, handler: NonNullable<HookHandlers<ParameterValues<S>>[K]>) {
      requireThat(collecting, "SETUP_CLOSED", "Register hooks synchronously during setup");
      requireThat(typeof handler === "function" && handler.constructor.name !== "AsyncFunction", "ASYNC_HOOK", "Hook callbacks must be synchronous");
      requireThat(!handlers[stage], "HOOK_CONFLICT", `Duplicate ${stage} hook for ${symbol.id}`);
      requireThat(stage === "replace" ? !handlers.before && !handlers.after : !handlers.replace,
        "HOOK_CONFLICT", "replace cannot be combined with before/after on the same target");
      handlers[stage] = handler;
    }
    return result;
  }
  const context: PlanContext<ParameterValues<S>> = {
    java: {
      method(className, methodName, argumentTypes, options = {}) {
        requireThat(evidence.capabilities.includes("java-hook"), "CAPABILITY_MISSING", "java-hook capability is unavailable");
        const norm = (t: string) => t === "byte[]" ? "[B" : t;
        const normArgs = JSON.stringify(argumentTypes.map(norm));
        const matches = evidence.symbols.filter(s => s.kind === "java" && s.className === className && s.methodName === methodName
          && JSON.stringify(s.argumentTypes.map(norm)) === normArgs
          && (options.classLoaderId === undefined || s.classLoaderId === options.classLoaderId)
          && (options.returnType === undefined || norm(s.returnType) === norm(options.returnType)));
        requireThat(matches.length > 0, "TARGET_NOT_FOUND", `No observed method: ${className}#${methodName}`);
        requireThat(matches.length === 1, "TARGET_AMBIGUOUS", "Select a class loader or a more precise target");
        return builder(matches[0]!);
      },
    },
    native: {
      function(moduleName, name, signature) {
        requireThat(evidence.capabilities.includes("native-hook"), "CAPABILITY_MISSING", "native-hook capability is unavailable");
        const matches = evidence.symbols.filter(s => s.kind === "native" && s.moduleName === moduleName && s.symbol === name);
        requireThat(matches.length === 1, matches.length === 0 ? "TARGET_NOT_FOUND" : "TARGET_AMBIGUOUS", `Cannot uniquely resolve ${moduleName}!${name}`);
        const symbol = matches[0]!;
        requireThat(symbol.kind === "native" && symbol.signatureSource !== "unknown", "SIGNATURE_UNKNOWN", "A Native signature needs evidence or confirmation");
        requireThat(signature.buildId.length > 0 && symbol.buildId === signature.buildId, "BUILD_ID_MISMATCH", "Native library Build ID changed");
        requireThat(symbol.returnType === signature.returnType && JSON.stringify(symbol.argumentTypes) === JSON.stringify(signature.argumentTypes),
          "SIGNATURE_MISMATCH", "Native ABI does not match the observed signature");
        return builder(symbol);
      },
    },
    debug: {
      emitEvent(tag, payload = null) {
        requireThat(!collecting, "HOOK_EMIT_OUTSIDE_INVOCATION", "emitEvent can only be called inside a hook callback");
        requireThat(typeof tag === "string" && /^[a-zA-Z0-9_.:-]{1,64}$/.test(tag), "HOOK_EVENT_TAG", "Invalid event tag");
        emitSink.fn?.(tag, payload);
      },
      captureJavaStack(maxDepth = 8) {
        requireThat(!collecting, "HOOK_EMIT_OUTSIDE_INVOCATION", "captureJavaStack can only be called inside a hook callback");
        requireThat(typeof maxDepth === "number" && Number.isSafeInteger(maxDepth) && maxDepth >= 1 && maxDepth <= 32,
          "HOOK_STACK_DEPTH", "maxDepth must be an integer between 1 and 32");
        return emitSink.stack ? emitSink.stack(maxDepth) : Object.freeze([]);
      },
      callerModuleOffset() {
        requireThat(!collecting, "HOOK_EMIT_OUTSIDE_INVOCATION", "callerModuleOffset can only be called inside a hook callback");
        return emitSink.caller ? emitSink.caller() : "unknown+0x0";
      },
    },
  };
  try {
    const value: unknown = plan.setup(context);
    if (value !== null && (typeof value === "object" || typeof value === "function") && "then" in value) {
      if (value instanceof Promise) void value.catch(() => {});
      requireThat(false, "ASYNC_SETUP", "setup must be synchronous");
    }
    // Ignore synchronous return values, allowing the usual concise `setup: ctx => builder.replace(...)` form.
  } finally {
    collecting = false;
  }
  requireThat(rules.size > 0, "EMPTY_PLAN", "Register at least one hook");
  const compiled = [...rules.values()].map(rule => {
    requireThat(Object.keys(rule.handlers).length > 0, "EMPTY_HOOK", `${rule.symbol.id}: no handler registered`);
    return Object.freeze({ symbol: rule.symbol, handlers: Object.freeze({ ...rule.handlers }) });
  });
  return Object.freeze({ id: plan.id, evidence, evidenceKey: evidenceKey(evidence), parameterSpecs: specs,
    parameters: values, rules: Object.freeze(compiled), emitSink });
}
