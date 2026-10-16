import type { HookValue } from "../../../src/evidence.js";
import { validateArguments, validateHookValue } from "../../../src/evidence.js";
import type { PreparedRule } from "../../../src/plan.js";

/** Preserve the exact guest throwable; a script error is a different failure. */
export class OriginalThrown { constructor(readonly value: unknown) {} }

/** Immutable invocation snapshot; callbacks never receive Frida or arbitrary Java objects. */
export function executeRule(rule: PreparedRule<any>, parameters: any, incoming: HookValue[], original: (args: readonly HookValue[]) => HookValue): HookValue {
  validateArguments(rule.symbol, incoming);
  let args = Object.freeze([...incoming]) as readonly HookValue[];
  if (rule.handlers.replace) {
    const value = rule.handlers.replace({ args, params: parameters });
    validateHookValue(rule.symbol.returnType, value, rule.symbol.id);return value;
  }
  if (rule.handlers.before) {
    const value = rule.handlers.before({ args, params: parameters });
    if (!Array.isArray(value)) throw new Error("HOOK_BEFORE_ARGUMENT_ARRAY_REQUIRED");
    validateArguments(rule.symbol, value);args = Object.freeze([...value]);
  }
  let value = original(args);
  validateHookValue(rule.symbol.returnType, value, rule.symbol.id);
  if (rule.handlers.after) {
    value = rule.handlers.after({ args, params: parameters, result: value });
    validateHookValue(rule.symbol.returnType, value, rule.symbol.id);
  }
  return value;
}
