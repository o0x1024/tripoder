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
  readonly kind: "simulated-hit" | "hook-error" | "original-error";
  readonly stage?: "before" | "replace" | "after";
  readonly detail?: string;
}
export interface InvocationResult {
  readonly value: HookValue;
  readonly revision: number;
  readonly hookOutcome: "applied" | "failed";
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

  constructor(evidence: EvidenceSnapshot) { this.#evidence = snapshotEvidence(evidence); }

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

  invoke(symbolId: string, incoming: readonly HookValue[], original: (args: readonly HookValue[]) => HookValue): InvocationResult {
    // Capture once: reentrant updates from a callback affect subsequent calls only.
    const state = this.#active();
    const rule = state.plan.rules.find(r => r.symbol.id === symbolId);
    requireThat(rule, "HOOK_NOT_REGISTERED", `No hook registered for ${symbolId}`);
    requireThat(rule.symbol.loaded, "WAITING_TARGET", "The target is indexed but has not loaded");
    validateArguments(rule.symbol, incoming);
    const originalArgs = Object.freeze([...incoming]);
    let args: readonly HookValue[] = originalArgs;
    let outcome: InvocationResult["hookOutcome"] = "applied";
    this.#record({ revision: state.revision, symbolId, kind: "simulated-hit" });
    const fail = (stage: "before" | "replace" | "after", error: unknown) => {
      outcome = "failed";
      this.#record({ revision: state.revision, symbolId, kind: "hook-error", stage,
        detail: error instanceof Error ? error.message : "Callback failed" });
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
    if (rule.handlers.replace) {
      let value: HookValue;
      try {
        const candidate = synchronous(rule.handlers.replace(Object.freeze({ args, params: state.params })));
        validateHookValue(rule.symbol.returnType, candidate, `${symbolId} replacement`);
        value = candidate;
      } catch (error) {
        fail("replace", error);
        value = callOriginal(originalArgs);
      }
      return { value, revision: state.revision, hookOutcome: outcome };
    }
    if (rule.handlers.before) {
      try {
        const candidate = synchronous(rule.handlers.before(Object.freeze({ args, params: state.params })));
        requireThat(Array.isArray(candidate), "HOOK_TYPE", "before must return an argument array");
        validateArguments(rule.symbol, candidate);
        args = Object.freeze([...candidate]);
      } catch (error) {
        fail("before", error);
        return { value: callOriginal(originalArgs), revision: state.revision, hookOutcome: outcome };
      }
    }
    let value = callOriginal(args);
    if (rule.handlers.after) {
      try {
        const candidate = synchronous(rule.handlers.after(Object.freeze({ args, params: state.params, result: value })));
        validateHookValue(rule.symbol.returnType, candidate, `${symbolId} after result`);
        value = candidate;
      } catch (error) {
        // The original has already executed. Never replay it on an after-hook failure.
        fail("after", error);
      }
    }
    return { value, revision: state.revision, hookOutcome: outcome };
  }
}
