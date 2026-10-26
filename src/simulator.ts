import {JavaObjectFrame,isJavaReferenceType} from "./java-objects.js";
import type {SimulatedJavaObject} from "./java-objects.js";
export type {SimulatedJavaObject} from "./java-objects.js";
import { HookError, requireThat } from "./errors.js";
import { evidenceKey, snapshotEvidence, validateArguments, validateHookValue } from "./evidence.js";
import type { EvidenceSnapshot, HookValue } from "./evidence.js";
import { resolveParameters } from "./parameters.js";
import type { Parameters, ParameterValues } from "./parameters.js";
import type { PreparedPlan } from "./plan.js";

interface Revision<S extends Parameters> {
  readonly revision: number;
  readonly plan: PreparedPlan<S>;
  readonly params: ParameterValues<S>;
}
export interface SimulationEvent {
  readonly revision: number;
  readonly symbolId: string;
  readonly kind: "simulated-hit" | "hook-error" | "original-error" | "debug-event";
  readonly stage?: "before" | "replace" | "after";
  readonly detail?: string;
  readonly tag?: string;
  readonly payload?: unknown;
}
export interface InvocationResult {
  readonly value: HookValue;
  readonly revision: number;
  readonly hookOutcome: "applied";
}

function synchronous(value: unknown): unknown {
  if (value !== null && (typeof value === "object" || typeof value === "function") && "then" in value) {
    // Only prevents unhandled rejections in this trusted-code harness; it cannot cancel a Promise's side effects.
    if (value instanceof Promise) void value.catch(() => {});
    throw new HookError("ASYNC_HOOK", "Synchronous hook callbacks must not return a Promise/thenable");
  }
  return value;
}

/** Single-process, trusted-code semantics harness. Does NOT load an Android agent or intercept real functions. */
export class HookSimulator<S extends Parameters> {
  readonly #evidence: EvidenceSnapshot;
  #current: Revision<S> | undefined;
  #nextRevision = 1;
  readonly #history = new Map<number, Revision<S>>();
  readonly #events: SimulationEvent[] = [];
  readonly #stats = new Map<string, { hits: number; errors: number }>();
  #attribution: readonly (readonly [string,string])[] = [];
  readonly #timeline: Readonly<Record<string,unknown>>[] = [];

  constructor(evidence: EvidenceSnapshot) { this.#evidence = snapshotEvidence(evidence); }

  /** Replaces the whole catalog, longest prefix wins. Mirrors RuntimePrivacyProbeState.configureAttribution. */
  configureAttribution(entries: readonly { packagePrefix: string; sdkName: string }[]): void {
    this.#attribution = [...entries].map(e => [e.packagePrefix, e.sdkName] as const).sort((a, b) => b[0].length - a[0].length);
  }
  #attributionOf(className: string): Readonly<Record<string,unknown>> {
    for (const [prefix, sdkName] of this.#attribution) if (className === prefix || className.startsWith(prefix + ".")) return { className, matchedPrefix: prefix, sdkName };
    return { className, matchedPrefix: null, sdkName: null };
  }
  markConsentStage(stage: string, note = ""): Readonly<Record<string,unknown>> {
    requireThat(/^[A-Z][A-Z0-9_]{0,63}$/.test(stage), "PRIVACY_CONSENT_STAGE", "Invalid consent stage");
    const row = Object.freeze({ seq: this.#timeline.length + 1, stage, note, elapsedRealtimeMillis: Date.now(), wallClockIso: new Date().toISOString() });
    this.#timeline.push(row);
    return row;
  }
  #currentStage(): Readonly<Record<string,unknown>> { return this.#timeline.at(-1) ?? { stage: null, seq: 0 }; }

  activate(plan: PreparedPlan<S>): number {
    requireThat(plan.evidenceKey === evidenceKey(this.#evidence), "STALE_EVIDENCE", "Process, capabilities, symbols or app version changed; prepare again");
    return this.#commit(plan, plan.parameters);
  }

  updateParameters(patch: Readonly<Record<string, unknown>>): number {
    const current = this.#active();
    const values = resolveParameters(current.plan.parameterSpecs, { ...current.params, ...patch });
    return this.#commit(current.plan, values);
  }

  rollback(revision: number): number {
    const saved = this.#history.get(revision);
    requireThat(saved, "REVISION_NOT_FOUND", "Revision unavailable (only the last 32 revisions are retained)");
    // Rollback is a new monotonic revision. It does not undo external effects of previous invocations.
    return this.#commit(saved.plan, saved.params);
  }

  #commit(plan: PreparedPlan<S>, params: ParameterValues<S>): number {
    const state = Object.freeze({ revision: this.#nextRevision++, plan, params });
    this.#history.set(state.revision, state);
    this.#current = state;
    if (this.#history.size > 32) {
      const oldest = this.#history.keys().next().value!;
      this.#history.delete(oldest);
      for (const key of this.#stats.keys()) if (key.startsWith(`${oldest}:`)) this.#stats.delete(key);
    }
    return state.revision;
  }

  #active(): Revision<S> {
    requireThat(this.#current, "NOT_ACTIVE", "Activate a prepared plan first");
    return this.#current;
  }

  #record(event: SimulationEvent): void {
    this.#events.push(Object.freeze({ ...event }));
    if (this.#events.length > 128) this.#events.shift();
    if (event.kind === "debug-event") return;
    const key = `${event.revision}:${event.symbolId}`;
    const stats = this.#stats.get(key) ?? { hits: 0, errors: 0 };
    if (event.kind === "simulated-hit") stats.hits++;
    if (event.kind !== "simulated-hit") stats.errors++;
    this.#stats.set(key, stats);
  }

  inspect() {
    const current = this.#current;
    return {
      backend: "local-simulator" as const,
      androidValidated: false as const,
      revision: current?.revision ?? null,
      params: current?.params ?? null,
      rules: current?.plan.rules.map(rule => {
        const stats = this.#stats.get(`${current.revision}:${rule.symbol.id}`) ?? { hits: 0, errors: 0 };
        return { symbolId: rule.symbol.id, status: !rule.symbol.loaded ? "waiting-target"
          : stats.errors > 0 ? "simulation-error" : stats.hits > 0 ? "simulated-hit" : "ready-for-simulation", ...stats };
      }) ?? [],
    };
  }

  events(): readonly SimulationEvent[] { return [...this.#events]; }

  invokeJavaFixture(symbolId: string,incoming: readonly unknown[],original:(args:readonly unknown[])=>unknown): {value:unknown;revision:number;hookOutcome:"applied"} {
    const rule=this.#active().plan.rules.find(r=>r.symbol.id===symbolId);requireThat(rule?.symbol.kind==="java","HOOK_NOT_REGISTERED",symbolId);
    const objectType=(type:string)=>isJavaReferenceType(type)&&type!=="java.lang.String"&&type!=="[B";
    const frame=new JavaObjectFrame();try{
      const values=incoming.map((value,i)=>objectType(rule.symbol.argumentTypes[i]!)?frame.wrap(rule.symbol.argumentTypes[i]!,value as SimulatedJavaObject|null):value) as HookValue[];
      const result=this.invoke(symbolId,values,args=>{
        const raw=args.map((value,i)=>objectType(rule.symbol.argumentTypes[i]!)?frame.unwrap(rule.symbol.argumentTypes[i]!,value):value);
        const output=original(raw);return objectType(rule.symbol.returnType)?frame.wrap(rule.symbol.returnType,output as SimulatedJavaObject|null):output as HookValue;
      });return {...result,value:objectType(rule.symbol.returnType)?frame.unwrap(rule.symbol.returnType,result.value):result.value};
    }finally{frame.close();}
  }
  invoke(symbolId: string, incoming: readonly HookValue[], original: (args: readonly HookValue[]) => HookValue): InvocationResult {
    // Capture once: reentrant updates from a callback affect subsequent calls only.
    const state = this.#active();
    const rule = state.plan.rules.find(r => r.symbol.id === symbolId);
    requireThat(rule, "HOOK_NOT_REGISTERED", `No hook registered for ${symbolId}`);
    requireThat(rule.symbol.loaded, "WAITING_TARGET", "The target is indexed but has not loaded");
    validateArguments(rule.symbol, incoming);
    let args: readonly HookValue[] = Object.freeze([...incoming]);
    this.#record({ revision: state.revision, symbolId, kind: "simulated-hit" });
    const previousEvidence = state.plan.emitSink?.evidence;
    const previousEmit = state.plan.emitSink?.fn;
    const previousStack = state.plan.emitSink?.stack;
    const previousCaller = state.plan.emitSink?.caller;
    const previousPrivacyAttribution = state.plan.emitSink?.privacyAttribution;
    const previousPrivacyConsentStage = state.plan.emitSink?.privacyConsentStage;
    if (state.plan.emitSink) {
      state.plan.emitSink.evidence=(tag,payload)=>{this.#record({revision:state.revision,symbolId,kind:"debug-event",tag,payload});return {artifactId:`simulated-${state.revision}-${this.#events.length}`,state:"SIMULATED"};};
      state.plan.emitSink.fn = (tag, payload) => {
        this.#record({ revision: state.revision, symbolId, kind: "debug-event", tag, payload });
      };
      state.plan.emitSink.stack = (maxDepth) => Object.freeze([
        rule.symbol.kind === "java" ? `${rule.symbol.className}.${rule.symbol.methodName}` : `${rule.symbol.moduleName}!${rule.symbol.symbol}`
      ].slice(0, maxDepth));
      state.plan.emitSink.caller = () => rule.symbol.kind === "native"
        ? `${rule.symbol.moduleName}+0x0`
        : `${rule.symbol.className}.${rule.symbol.methodName}`;
      state.plan.emitSink.privacyAttribution = (className) => this.#attributionOf(className);
      state.plan.emitSink.privacyConsentStage = () => this.#currentStage();
    }
    const fail = (stage: "before" | "replace" | "after", error: unknown): never => {
      const failure = error instanceof Error ? error
        : new HookError("HOOK_EXECUTION", `${symbolId} ${stage}: callback threw a non-Error value`);
      this.#record({ revision: state.revision, symbolId, kind: "hook-error", stage,
        detail: failure.message });
      throw failure;
    };
    const callOriginal = (callArgs: readonly HookValue[]): HookValue => {
      try {
        const value = synchronous(original(callArgs));
        validateHookValue(rule.symbol.returnType, value, `${symbolId} original result`);
        return value;
      } catch (error) {
        this.#record({ revision: state.revision, symbolId, kind: "original-error" });
        throw error;
      }
    };
    try {
      if (rule.handlers.replace) {
        let value: HookValue;
        try {
          const candidate = synchronous(rule.handlers.replace(Object.freeze({ args, params: state.params })));
          validateHookValue(rule.symbol.returnType, candidate, `${symbolId} replacement`);
          value = candidate;
        } catch (error) {
          return fail("replace", error);
        }
        return { value, revision: state.revision, hookOutcome: "applied" };
      }
      if (rule.handlers.before) {
        try {
          const candidate = synchronous(rule.handlers.before(Object.freeze({ args, params: state.params })));
          requireThat(Array.isArray(candidate), "HOOK_TYPE", "before must return an argument array");
          validateArguments(rule.symbol, candidate);
          args = Object.freeze([...candidate]);
        } catch (error) {
          return fail("before", error);
        }
      }
      let value = callOriginal(args);
      if (rule.handlers.after) {
        try {
          const candidate = synchronous(rule.handlers.after(Object.freeze({ args, params: state.params, result: value })));
          validateHookValue(rule.symbol.returnType, candidate, `${symbolId} after result`);
          value = candidate;
        } catch (error) {
          // The original has already executed. Report failure without returning its result or replaying it.
          return fail("after", error);
        }
      }
      return { value, revision: state.revision, hookOutcome: "applied" };
    } finally {
      if (state.plan.emitSink) {
        state.plan.emitSink.evidence=previousEvidence;
        state.plan.emitSink.fn = previousEmit;
        state.plan.emitSink.stack = previousStack;
        state.plan.emitSink.caller = previousCaller;
        state.plan.emitSink.privacyAttribution = previousPrivacyAttribution;
        state.plan.emitSink.privacyConsentStage = previousPrivacyConsentStage;
      }
    }
  }
}
