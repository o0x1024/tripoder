import {isJavaObjectRef,liveJavaObjectRef} from "./java-objects.js";
import { parse } from "@babel/parser";
import { definePlan, preparePlan } from "./plan.js";
import type { HookPlan, PreparedPlan } from "./plan.js";
import { param } from "./parameters.js";
import type { Parameters } from "./parameters.js";
import { snapshotEvidence } from "./evidence.js";
import type { EvidenceSnapshot } from "./evidence.js";

// Interpret a documented subset of ordinary TypeScript ASTs. Never eval source,
// import a draft, or expose the host/Frida globals to it. This is a language
// capability boundary, not OS isolation for other same-UID Android code.
type Ast = { type: string; [key: string]: any };
type Scope = Map<string, unknown>;
const forbidden = new Set(["constructor", "prototype", "__proto__", "caller", "callee", "arguments"]);
const allowed = new Set(["File", "Program", "ImportDeclaration", "ImportSpecifier", "ExportDefaultDeclaration",
  "Identifier", "StringLiteral", "NumericLiteral", "BigIntLiteral", "BooleanLiteral", "NullLiteral", "VariableDeclaration", "VariableDeclarator",
  "ObjectExpression", "ObjectProperty", "ObjectMethod", "ArrayExpression", "ArrowFunctionExpression", "FunctionExpression",
  "BlockStatement", "ExpressionStatement", "ReturnStatement", "IfStatement", "EmptyStatement", "ObjectPattern", "ArrayPattern",
  "AssignmentPattern", "MemberExpression", "CallExpression", "BinaryExpression", "LogicalExpression", "UnaryExpression",
  "ConditionalExpression", "TemplateLiteral", "TemplateElement", "TSAsExpression", "TSTypeAssertion", "TSNonNullExpression",
  "TSSatisfiesExpression", "TSInterfaceDeclaration", "TSTypeAliasDeclaration"]);
const scalar = (v: unknown): v is string | number | bigint | boolean | null | undefined => v == null || ["string", "number", "bigint", "boolean"].includes(typeof v);
function demand(value: unknown, message: string): asserts value { if (!value) throw new Error("DRAFT: " + message); }
function keyOf(value: unknown): string {
  demand(typeof value === "string" || typeof value === "number", "Property key must be a string or number");
  const key = String(value); demand(!forbidden.has(key), "Forbidden property: " + key); return key;
}
// Strict JSON.stringify over an already-sandboxed value tree: explicitly rejects
// functions/undefined/bigint rather than silently dropping them like native JSON.stringify.
function jsonStringify(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number") { demand(Number.isFinite(value), "JSON.stringify requires a finite number"); return JSON.stringify(value); }
  if (typeof value === "boolean") return JSON.stringify(value);
  demand(typeof value !== "function" && typeof value !== "bigint" && value !== undefined, "JSON.stringify does not accept this value");
  if (Array.isArray(value)) return "[" + value.map(jsonStringify).join(",") + "]";
  demand(typeof value === "object", "JSON.stringify requires a plain object, array or scalar");
  return "{" + Object.entries(value as Record<string, unknown>).map(([k, v]) => JSON.stringify(k) + ":" + jsonStringify(v)).join(",") + "}";
}

export function prepareDraft(source: string, observed: EvidenceSnapshot): PreparedPlan<Parameters> {
  demand(typeof source === "string" && source.length <= 32768, "Source exceeds 32 KiB characters");
  const ast = parse(source, { sourceType: "module", plugins: ["typescript"], attachComment: false }) as unknown as Ast;
  let nodes = 0;
  function validate(node: Ast, depth = 0): void {
    demand(++nodes <= 8000 && depth < 80, "AST size/depth limit");
    demand(allowed.has(node.type), "Unsupported syntax: " + node.type);
    if (node.type === "TSInterfaceDeclaration" || node.type === "TSTypeAliasDeclaration") return;
    demand(!node.async && !node.generator, "Async and generators are unavailable");
    if (node.type === "ObjectMethod") demand(node.kind === "method", "Getters/setters are unavailable");
    if (node.type === "VariableDeclaration") demand(node.kind === "const", "Use const declarations");
    if (node.type === "Identifier") keyOf(node.name);
    for (const [key, value] of Object.entries(node)) {
      if (["loc", "extra", "typeAnnotation", "returnType", "typeParameters", "typeArguments", "comments"].includes(key)) continue;
      for (const child of Array.isArray(value) ? value : [value]) {
        if (child && typeof child === "object" && typeof child.type === "string") validate(child, depth + 1);
      }
    }
  }
  validate(ast);
  const callable = new WeakSet<Function>();
  const wrapped = new WeakMap<object, unknown>();
  let activeBudget: { remaining: number; depth: number } | undefined;
  function bounded<T>(body: () => T): T {
    const previous = activeBudget;
    const budget = activeBudget ?? { remaining: 3000, depth: 0 };
    activeBudget = budget; demand(++budget.depth < 64, "Call depth exceeded");
    try { return body(); } finally { budget.depth--; activeBudget = previous; }
  }
  function tick() { demand(activeBudget && --activeBudget.remaining >= 0, "Execution budget exceeded"); }
  function limit(value: unknown): unknown {
    if (typeof value === "string") demand(value.length <= 8192, "String size limit");
    if (typeof value === "number") demand(Number.isFinite(value), "Non-finite number");
    if (typeof value === "bigint") demand(value >= -(1n << 127n) && value < (1n << 127n), "BigInt size limit");
    if (Array.isArray(value)) demand(value.length <= 64, "Array size limit");
    return value;
  }
  function mark(fn: (...args: any[]) => unknown) { callable.add(fn); return fn; }
  function expose(value: any): any {
    if (scalar(value)) return limit(value);
    if(isJavaObjectRef(value))return liveJavaObjectRef(value);
    if (wrapped.has(value)) return wrapped.get(value);
    if (typeof value === "function") {
      if (callable.has(value)) return value;
      const fn = mark((...args: unknown[]) => expose(value(...args)));
      wrapped.set(value, fn); return fn;
    }
    const copy: any = Array.isArray(value) ? [] : Object.create(null);
    wrapped.set(value, copy);
    for (const key of Object.keys(value)) copy[keyOf(key)] = expose(value[key]);
    return Object.freeze(copy);
  }
  function get(value: any, rawKey: unknown): any {
    const key = keyOf(rawKey); demand(value != null, "Cannot read " + key + " on null/undefined");
    if (typeof value === "string") {
      if (key === "length") return value.length;
      if (/^\d+$/.test(key)) return value[Number(key)];
      if (["toUpperCase", "toLowerCase", "trim", "slice", "substring", "includes", "startsWith", "endsWith"].includes(key)) {
        return mark((...args: unknown[]) => {
          demand(args.every(scalar), "String methods accept scalar arguments only");
          return limit((value as any)[key](...args));
        });
      }
    }
    demand(typeof value !== "function" && Object.hasOwn(Object(value), key), "Unavailable property: " + key);
    return value[key];
  }
  function bind(pattern: Ast, value: unknown, scope: Scope): void {
    tick();
    switch (pattern.type) {
      case "Identifier": demand(!scope.has(pattern.name), "Duplicate binding: " + pattern.name); scope.set(pattern.name, value); break;
      case "AssignmentPattern": bind(pattern.left, value === undefined ? evaluate(pattern.right, scope) : value, scope); break;
      case "ObjectPattern": for (const p of pattern.properties) bind(p.value, get(value, p.computed ? evaluate(p.key, scope) : p.key.name ?? p.key.value), scope); break;
      case "ArrayPattern": demand(Array.isArray(value), "Array destructuring needs an array"); pattern.elements.forEach((p: Ast, i: number) => { if (p) bind(p, value[i], scope); }); break;
      default: throw new Error("DRAFT: Unsupported binding");
    }
  }
  function fn(node: Ast, closure: Scope): Function {
    const result = mark((...args: unknown[]) => bounded(() => {
      const local = new Map(closure);
      // Parameters may shadow outer bindings, as in ordinary TypeScript.
      const bindings = new Map<string, unknown>();
      node.params.forEach((p: Ast, i: number) => bind(p, expose(args[i]), bindings));
      for (const [k, v] of bindings) local.set(k, v);
      if (node.body.type !== "BlockStatement") return limit(evaluate(node.body, local));
      return statements(node.body.body, local)?.value;
    }));
    return result;
  }
  function evaluate(node: Ast, scope: Scope): any {
    tick();
    switch (node.type) {
      case "Identifier": demand(scope.has(node.name), "Unknown identifier: " + node.name); return scope.get(node.name);
      case "StringLiteral": case "NumericLiteral": case "BooleanLiteral": return limit(node.value);
      case "BigIntLiteral": demand(typeof node.value === "bigint", "BigInt AST value required"); return limit(node.value);
      case "NullLiteral": return null;
      case "TSAsExpression": case "TSTypeAssertion": case "TSNonNullExpression": case "TSSatisfiesExpression": return evaluate(node.expression, scope);
      case "ArrayExpression": return Object.freeze(limit(node.elements.map((x: Ast) => { demand(x, "Array holes unavailable"); return evaluate(x, scope); })));
      case "ObjectExpression": {
        demand(node.properties.length <= 64, "Object size limit");
        const result = Object.create(null);
        for (const property of node.properties) {
          const key = keyOf(property.computed ? evaluate(property.key, scope) : property.key.name ?? property.key.value);
          result[key] = property.type === "ObjectMethod" ? fn(property, scope) : evaluate(property.value, scope);
        }
        return Object.freeze(result);
      }
      case "ArrowFunctionExpression": case "FunctionExpression": demand(!node.id, "Use anonymous callbacks"); return fn(node, scope);
      case "MemberExpression": return get(evaluate(node.object, scope), node.computed ? evaluate(node.property, scope) : node.property.name);
      case "CallExpression": {
        const callee = evaluate(node.callee, scope);
        demand(typeof callee === "function" && callable.has(callee), "Unavailable function");
        demand(node.arguments.length <= 64, "Argument count limit");
        return limit(callee(...node.arguments.map((a: Ast) => evaluate(a, scope))));
      }
      case "ConditionalExpression": return evaluate(evaluate(node.test, scope) ? node.consequent : node.alternate, scope);
      case "LogicalExpression": {
        const left = evaluate(node.left, scope);
        if (node.operator === "&&") return left && evaluate(node.right, scope);
        if (node.operator === "||") return left || evaluate(node.right, scope);
        if (node.operator === "??") return left ?? evaluate(node.right, scope);
        throw new Error("DRAFT: Unsupported logical operator");
      }
      case "UnaryExpression": {
        const value = evaluate(node.argument, scope); if(isJavaObjectRef(value)){liveJavaObjectRef(value);demand(node.operator==="!"||node.operator==="typeof","Unsupported object operation");return node.operator==="typeof"?"object":false;} demand(scalar(value), "Unary operand must be scalar");
        if (node.operator === "!") return !value;
        if (node.operator === "-") return limit(typeof value === "bigint" ? -value : -Number(value));
        if (node.operator === "+") { demand(typeof value !== "bigint", "Unary plus does not accept BigInt"); return limit(Number(value)); }
        if (node.operator === "typeof") return typeof value;
        throw new Error("DRAFT: Unsupported unary operator");
      }
      case "BinaryExpression": {
        const a = evaluate(node.left, scope), b = evaluate(node.right, scope); if(isJavaObjectRef(a)||isJavaObjectRef(b)){if(isJavaObjectRef(a))liveJavaObjectRef(a);if(isJavaObjectRef(b))liveJavaObjectRef(b);demand(node.operator==="==="||node.operator==="!==","Unsupported object operation");return node.operator==="==="?a===b:a!==b;} demand(scalar(a) && scalar(b), "Binary operands must be scalar");
        if ((typeof a === "bigint" || typeof b === "bigint") && ["+", "-", "*", "/", "%"].includes(node.operator)
          && !(node.operator === "+" && (typeof a === "string" || typeof b === "string"))) {
          demand(typeof a === "bigint" && typeof b === "bigint", "BigInt arithmetic needs two BigInt operands");
          switch (node.operator) {
            case "+": return limit(a + b); case "-": return limit(a - b); case "*": return limit(a * b);
            case "/": return limit(a / b); case "%": return limit(a % b);
          }
        }
        switch (node.operator) {
          case "+": return limit(typeof a === "string" || typeof b === "string" ? String(a) + String(b) : Number(a) + Number(b));
          case "-": return limit(Number(a) - Number(b)); case "*": return limit(Number(a) * Number(b));
          case "/": return limit(Number(a) / Number(b)); case "%": return limit(Number(a) % Number(b));
          case "===": return a === b; case "!==": return a !== b;
          case "<": return (a as any) < (b as any); case "<=": return (a as any) <= (b as any);
          case ">": return (a as any) > (b as any); case ">=": return (a as any) >= (b as any);
          default: throw new Error("DRAFT: Unsupported binary operator");
        }
      }
      case "TemplateLiteral": {
        let result = "";
        node.quasis.forEach((q: Ast, i: number) => {
          result += q.value.cooked;
          if (i < node.expressions.length) { const value = evaluate(node.expressions[i], scope); demand(scalar(value), "Template value must be scalar"); result += String(value); }
          limit(result);
        }); return result;
      }
      default: throw new Error("DRAFT: Unsupported expression: " + node.type);
    }
  }
  function statements(body: Ast[], scope: Scope): { value: unknown } | undefined {
    for (const node of body) {
      tick();
      switch (node.type) {
        case "VariableDeclaration": for (const declaration of node.declarations) { demand(declaration.init, "const needs a value"); bind(declaration.id, evaluate(declaration.init, scope), scope); } break;
        case "ExpressionStatement": evaluate(node.expression, scope); break;
        case "ReturnStatement": return { value: node.argument ? evaluate(node.argument, scope) : undefined };
        case "BlockStatement": { const result = statements(node.body, new Map(scope)); if (result) return result; break; }
        case "IfStatement": {
          const branch = evaluate(node.test, scope) ? node.consequent : node.alternate;
          if (branch) { const result = statements([branch], scope); if (result) return result; } break;
        }
        case "EmptyStatement": case "TSInterfaceDeclaration": case "TSTypeAliasDeclaration": break;
        default: throw new Error("DRAFT: Unsupported statement: " + node.type);
      }
    }
    return undefined;
  }
  const evidence = snapshotEvidence(observed);
  const scope: Scope = new Map(Object.entries(expose({ target: evidence.target,
    symbols: Object.fromEntries(evidence.symbols.map(s => [s.id, s])), undefined,
    String: (v: unknown) => { demand(scalar(v), "String expects scalar"); return String(v); },
    Number: (v: unknown) => { demand(scalar(v), "Number expects scalar"); return limit(Number(v)); },
    BigInt: (v: unknown) => {
      demand(typeof v === "bigint" || typeof v === "boolean" || typeof v === "number" && Number.isSafeInteger(v)
        || typeof v === "string" && v.length <= 40 && /^-?\d+$/.test(v), "BigInt expects a bounded exact integer");
      return limit(BigInt(v));
    },
    Boolean: (v: unknown) => { demand(scalar(v), "Boolean expects scalar"); return Boolean(v); },
    Math: Object.fromEntries(Object.entries({ min: Math.min, max: Math.max, floor: Math.floor, ceil: Math.ceil, round: Math.round, abs: Math.abs })
      .map(([name, math]) => [name, (...args: unknown[]) => {
        demand(args.length > 0 && args.every(x => typeof x === "number"), "Math requires numeric arguments");
        return limit((math as (...values: number[]) => number)(...args as number[]));
      }])),
    // Hex <-> UTF-8 text, and Base64 <-> hex (hex is this project's canonical raw-bytes-as-string
    // form elsewhere, e.g. byte[] arguments), mirroring android/runtime/.../script/RestrictedAst.java.
    Hex: {
      encode: (text: unknown) => { demand(typeof text === "string", "Hex.encode expects a string"); return limit(Buffer.from(text, "utf8").toString("hex")); },
      decode: (hex: unknown) => { demand(typeof hex === "string" && /^([0-9a-fA-F]{2})*$/.test(hex), "Hex.decode expects an even-length hex string"); return limit(Buffer.from(hex, "hex").toString("utf8")); },
    },
    Base64: {
      encode: (hex: unknown) => { demand(typeof hex === "string" && /^([0-9a-fA-F]{2})*$/.test(hex), "Base64.encode expects an even-length hex string"); return limit(Buffer.from(hex, "hex").toString("base64")); },
      decode: (b64: unknown) => {
        demand(typeof b64 === "string", "Base64.decode expects a string");
        demand(/^[A-Za-z0-9+/]*={0,2}$/.test(b64) && b64.length % 4 === 0, "Base64.decode expects valid Base64");
        return limit(Buffer.from(b64, "base64").toString("hex"));
      },
    },
    JSON: {
      parse: (text: unknown) => { demand(typeof text === "string", "JSON.parse expects a string"); let value: unknown; try { value = JSON.parse(text); } catch { throw new Error("DRAFT: Invalid JSON"); } return expose(value); },
      stringify: (value: unknown) => limit(jsonStringify(value)),
    },
  })));
  const imports = expose({ definePlan, param });
  const plan = bounded(() => {
    let result: unknown;
    for (const node of ast.program.body) {
      if (node.type === "ImportDeclaration") {
        demand(node.source.value === "@abox/hook", "Only @abox/hook imports are available");
        for (const specifier of node.specifiers) {
          demand(specifier.type === "ImportSpecifier", "Use named SDK imports");
          scope.set(specifier.local.name, get(imports, specifier.imported.name));
        }
      } else if (node.type === "ExportDefaultDeclaration") result = evaluate(node.declaration, scope);
      else statements([node], scope);
    }
    demand(result && typeof result === "object", "export default definePlan(...) is required");
    return result as HookPlan<Parameters>;
  });
  return preparePlan(plan, evidence);
}

export function draftTemplate(): string {
  return `import { definePlan, param } from "@abox/hook";
export default definePlan({
  id: "my-hook", target,
  params: { message: param.string("script:"), delayMs: param.integer(700, { minimum: 0, maximum: 10000 }) },
  setup(ctx) {
    ctx.java.method(symbols.greet.className, "greet", ["java.lang.String"])
      .replace(({ args, params }) => params.message + String(args[0]).toUpperCase());
    ctx.native.function(symbols.delay.moduleName, symbols.delay.symbol, {
      argumentTypes: ["int32"], returnType: "int32", buildId: symbols.delay.buildId
    }).replace(({ args, params }) => Number(args[0]) * 2 + params.delayMs);
  }
});`;
}
