import {JavaObjectFrame, assertSnapshotData, eventSnapshot} from "./java-objects.js";
import type {JavaObjectRef} from "./java-objects.js";
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
  readonly code: {
    /** Queue a runtime-owned snapshot; bytes are written off the callback thread. */
    snapshotDex(dexId: string): Readonly<{ artifactId: string; state: string }>;
  };
  readonly java: {
    objectInfo(ref: JavaObjectRef): Readonly<Record<string,unknown>>;
    snapshotThread(ref: JavaObjectRef): Readonly<Record<string,unknown>>;
    snapshotThrowable(ref: JavaObjectRef, limits?: {maxFrames?: number;maxCauses?: number}): Readonly<Record<string,unknown>>;
    /** Direct field read, never a guest-overridden getter. Returns a scalar, or a new JavaObjectRef for an object-typed field. */
    readField(ref: JavaObjectRef, fieldName: string): HookValue | JavaObjectRef;
    /** Shallow container snapshot without executing guest-overridden virtual methods or toString. */
    snapshotContainer(ref: JavaObjectRef, maxEntries?: number): Readonly<Record<string, unknown>>;
    method(className: string, methodName: string, argumentTypes: readonly JavaType[], options?: { classLoaderId?: string; returnType?: JavaType }): HookBuilder<P>;
  };
  readonly state: {
    get(key: string): unknown;
    set(key: string, value: unknown): unknown;
    delete(key: string): boolean;
    clear(): void;
    keys(): readonly string[];
  };
  readonly native: {
    /** After original only; signed byte-count result, uint64 pointer slot, integral capacity slot. */
    captureBuffer(pointerArgument: number, capacityArgument: number, maxBytes: number): Readonly<Record<string, unknown>>;
    function(moduleName: string, symbol: string, signature: {
      argumentTypes: readonly NativeType[]; returnType: NativeType; buildId: string;
    }): HookBuilder<P>;
  };
  readonly debug: {
    emitEvent(tag: string, payload?: unknown): void;
    captureEvidence(tag: string, payload: unknown): Readonly<{artifactId:string;state:string}>;
    captureJavaStack(maxDepth?: number): readonly string[];
    callerModuleOffset(): string;
  };
  readonly privacy: {
    /** Longest packagePrefix match against an importable catalog; sdkName is null when nothing matches. */
    attribution(className: string): Readonly<Record<string, unknown>>;
    /** Most recently marked consent-timeline stage for this process, independent of any active Hook. */
    consentStage(): Readonly<Record<string, unknown>>;
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
    nativeBuffer?: ((pointerArgument:number,capacityArgument:number,maxBytes:number)=>Readonly<Record<string,unknown>>) | undefined;
    snapshotDex?: ((dexId: string) => Readonly<{ artifactId: string; state: string }>) | undefined;
    fn?: ((tag: string, payload: unknown) => void) | undefined;
    evidence?: ((tag:string,payload:unknown)=>Readonly<{artifactId:string;state:string}>) | undefined;
    stack?: ((maxDepth: number) => readonly string[]) | undefined;
    caller?: (() => string) | undefined;
    privacyAttribution?: ((className: string) => Readonly<Record<string,unknown>>) | undefined;
    privacyConsentStage?: (() => Readonly<Record<string,unknown>>) | undefined;
    stateStore?: Map<string, unknown> | undefined;
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
    nativeBuffer?: ((pointerArgument:number,capacityArgument:number,maxBytes:number)=>Readonly<Record<string,unknown>>) | undefined;
    snapshotDex?: ((dexId: string) => Readonly<{ artifactId: string; state: string }>) | undefined;
    fn?: ((tag: string, payload: unknown) => void) | undefined;
    evidence?: ((tag:string,payload:unknown)=>Readonly<{artifactId:string;state:string}>) | undefined;
    stack?: ((maxDepth: number) => readonly string[]) | undefined;
    caller?: (() => string) | undefined;
    privacyAttribution?: ((className: string) => Readonly<Record<string,unknown>>) | undefined;
    privacyConsentStage?: (() => Readonly<Record<string,unknown>>) | undefined;
    stateStore?: Map<string, unknown> | undefined;
  } = { stateStore: new Map<string, unknown>() };
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
    code: {
      snapshotDex(dexId) {
        requireThat(!collecting, "CODE_CAPTURE_OUTSIDE_INVOCATION", "Code capture is available inside hook callbacks");
        requireThat(typeof dexId === "string" && /^[A-Za-z0-9_-]{1,80}$/.test(dexId), "DEX_REFERENCE", "Invalid DEX reference");
        requireThat(emitSink.snapshotDex !== undefined, "CODE_SNAPSHOT_UNSUPPORTED", "A runtime code capture backend is required");
        return Object.freeze({ ...emitSink.snapshotDex(dexId) });
      },
    },
    java: {
      objectInfo: ref => JavaObjectFrame.info(ref),
      snapshotThread: ref => JavaObjectFrame.snapshot(ref,"java.lang.Thread"),
      snapshotThrowable: (ref,limits={}) => {requireThat(Object.keys(limits).every(k=>k==="maxFrames"||k==="maxCauses"),"HOOK_SNAPSHOT_FIELDS","Unknown snapshot option");requireThat(Number.isSafeInteger(limits.maxFrames??16)&&Number.isSafeInteger(limits.maxCauses??4)&&(limits.maxFrames??16)>=1&&(limits.maxFrames??16)<=32&&(limits.maxCauses??4)>=1&&(limits.maxCauses??4)<=6,"HOOK_SNAPSHOT_LIMIT","Snapshot exceeds limits");return JavaObjectFrame.snapshot(ref,"java.lang.Throwable");},
      readField: (ref,fieldName) => {requireThat(typeof fieldName==="string"&&fieldName.length>0,"HOOK_FIELD_NAME","Field name must be a non-empty string");return JavaObjectFrame.field(ref,fieldName) as HookValue | JavaObjectRef;},
      snapshotContainer: (ref,maxEntries=16) => {requireThat(typeof maxEntries==="number"&&Number.isSafeInteger(maxEntries)&&maxEntries>=1&&maxEntries<=64,"HOOK_SNAPSHOT_LIMIT","maxEntries must be an integer between 1 and 64");return JavaObjectFrame.container(ref,maxEntries);},
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
      captureBuffer(pointerArgument,capacityArgument,maxBytes){
        requireThat(!collecting,"NATIVE_BUFFER_OUTSIDE_INVOCATION","Native capture requires an active invocation");
        requireThat(Number.isInteger(pointerArgument)&&pointerArgument>=0&&pointerArgument<16&&Number.isInteger(capacityArgument)&&capacityArgument>=0&&capacityArgument<16&&pointerArgument!==capacityArgument&&Number.isInteger(maxBytes)&&maxBytes>=1&&maxBytes<=1024,"NATIVE_BUFFER_ARGUMENT","Invalid native capture bounds");
        requireThat(emitSink.nativeBuffer,"NATIVE_BUFFER_UNSUPPORTED","An invocation-owned native buffer provider is required");
        return Object.freeze({...emitSink.nativeBuffer(pointerArgument,capacityArgument,maxBytes)});
      },
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
      captureEvidence(tag,payload){requireThat(!collecting&&emitSink.evidence,"EVIDENCE_CAPTURE_UNSUPPORTED","Evidence sink is unavailable");requireThat(typeof tag==="string"&&/^[a-zA-Z0-9_.:-]{1,64}$/.test(tag),"HOOK_EVENT_TAG","Invalid evidence tag");assertSnapshotData(payload);return emitSink.evidence(tag,payload);},
      emitEvent(tag, payload = null) {
        requireThat(!collecting, "HOOK_EMIT_OUTSIDE_INVOCATION", "emitEvent can only be called inside a hook callback");
        requireThat(typeof tag === "string" && /^[a-zA-Z0-9_.:-]{1,64}$/.test(tag), "HOOK_EVENT_TAG", "Invalid event tag");
        const snapshot=eventSnapshot(payload);assertSnapshotData(snapshot);emitSink.fn?.(tag, snapshot);
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
    privacy: {
      attribution(className) {
        requireThat(typeof className === "string" && className.length > 0 && className.length <= 512, "PRIVACY_CLASS_NAME", "Invalid class name");
        requireThat(emitSink.privacyAttribution !== undefined, "PRIVACY_ATTRIBUTION_UNSUPPORTED", "An attribution backend is required");
        return Object.freeze({ ...emitSink.privacyAttribution(className) });
      },
      consentStage() {
        requireThat(emitSink.privacyConsentStage !== undefined, "PRIVACY_CONSENT_STAGE_UNSUPPORTED", "A consent-stage backend is required");
        return Object.freeze({ ...emitSink.privacyConsentStage() });
      },
    },
    state: {
      get(key) {
        requireThat(!collecting, "HOOK_STATE_OUTSIDE_INVOCATION", "state.get can only be called inside a hook callback");
        requireThat(typeof key === "string" && key.length >= 1 && key.length <= 128, "HOOK_STATE_KEY", "State key must be a 1..128 character string");
        const store = emitSink.stateStore!;
        return store.has(key) ? store.get(key) : undefined;
      },
      set(key, value) {
        requireThat(!collecting, "HOOK_STATE_OUTSIDE_INVOCATION", "state.set can only be called inside a hook callback");
        requireThat(typeof key === "string" && key.length >= 1 && key.length <= 128, "HOOK_STATE_KEY", "State key must be a 1..128 character string");
        const store = emitSink.stateStore!;
        if (!store.has(key)) requireThat(store.size < 64, "HOOK_STATE_CAPACITY", "Session state capacity limit (64 keys) exceeded");
        const normalized = value === undefined ? null : eventSnapshot(value);
        assertSnapshotData(normalized);
        requireThat(JSON.stringify({ k: key, v: normalized }).length <= 4096, "HOOK_STATE_VALUE_SIZE", "State entry exceeds 4096 bytes");
        const frozen = normalized !== null && typeof normalized === "object" ? Object.freeze(structuredClone(normalized)) : normalized;
        store.set(key, frozen);
        return frozen;
      },
      delete(key) {
        requireThat(!collecting, "HOOK_STATE_OUTSIDE_INVOCATION", "state.delete can only be called inside a hook callback");
        requireThat(typeof key === "string" && key.length >= 1 && key.length <= 128, "HOOK_STATE_KEY", "State key must be a 1..128 character string");
        return emitSink.stateStore!.delete(key);
      },
      clear() {
        requireThat(!collecting, "HOOK_STATE_OUTSIDE_INVOCATION", "state.clear can only be called inside a hook callback");
        emitSink.stateStore!.clear();
      },
      keys() {
        requireThat(!collecting, "HOOK_STATE_OUTSIDE_INVOCATION", "state.keys can only be called inside a hook callback");
        return Object.freeze([...emitSink.stateStore!.keys()]);
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
