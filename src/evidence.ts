import {JavaObjectFrame, isJavaReferenceType, validJavaType} from "./java-objects.js";
import type {JavaObjectRef} from "./java-objects.js";
import { requireThat } from "./errors.js";

export type JavaType = string;
export type NativeType = "void" | "int32" | "uint32" | "int64" | "uint64" | "float" | "double";
export type AbiType = JavaType | NativeType;
export type HookValue = string | boolean | number | bigint | null | undefined | JavaObjectRef;
export interface Target {
  readonly packageName: string;
  readonly versionCode: string;
  readonly apkSha256: string;
}
interface SymbolBase {
  readonly id: string;
  readonly loaded: boolean;
}
export interface JavaMethod extends SymbolBase {
  readonly kind: "java";
  readonly className: string;
  readonly methodName: string;
  readonly classLoaderId: string;
  readonly argumentTypes: readonly JavaType[];
  readonly returnType: JavaType;
}
export interface NativeFunction extends SymbolBase {
  readonly kind: "native";
  readonly moduleName: string;
  readonly symbol: string;
  readonly buildId: string;
  readonly argumentTypes: readonly NativeType[];
  readonly returnType: NativeType;
  readonly signatureSource: "debug-info" | "confirmed" | "unknown";
}
export type HookSymbol = JavaMethod | NativeFunction;
export interface EvidenceSnapshot {
  readonly id: string;
  readonly source: "fixture" | "guest-agent";
  readonly target: Target;
  readonly processInstanceId: string;
  readonly abi: "arm64-v8a";
  readonly capabilities: readonly ("java-hook" | "native-hook")[];
  readonly symbols: readonly HookSymbol[];
}

export function sameTarget(a: Target, b: Target): boolean {
  return a.packageName === b.packageName && a.versionCode === b.versionCode && a.apkSha256 === b.apkSha256;
}

export function snapshotEvidence(evidence: EvidenceSnapshot): EvidenceSnapshot {
  requireThat(evidence.source === "fixture" || evidence.source === "guest-agent", "INVALID_EVIDENCE", "Unknown evidence source");
  requireThat(evidence.id.length > 0 && evidence.processInstanceId.length > 0, "INVALID_EVIDENCE", "Evidence and process IDs are required");
  requireThat(evidence.target.packageName.length > 0, "INVALID_EVIDENCE", "Package name is required");
  requireThat(/^[a-f0-9]{64}$/.test(evidence.target.apkSha256), "INVALID_EVIDENCE", "Expected an APK SHA-256 digest");
  requireThat(/^\d+$/.test(evidence.target.versionCode), "INVALID_EVIDENCE", "versionCode must be a decimal string");
  requireThat(evidence.abi === "arm64-v8a", "UNSUPPORTED_ABI", "This prototype only models arm64-v8a");
  const ids = new Set<string>();
  requireThat(evidence.capabilities.every(c => c === "java-hook" || c === "native-hook"), "INVALID_EVIDENCE", "Unknown capability");
  const nativeTypes = new Set(["void", "int32", "uint32", "int64", "uint64", "float", "double"]);
  const symbols = evidence.symbols.map((symbol): HookSymbol => {
    requireThat(symbol.id.length > 0 && !ids.has(symbol.id), "INVALID_EVIDENCE", `Duplicate or empty symbol ID: ${symbol.id}`);
    ids.add(symbol.id);
    requireThat(typeof symbol.loaded === "boolean", "INVALID_EVIDENCE", "loaded must be a boolean");
    requireThat(!symbol.argumentTypes.includes("void"), "INVALID_SIGNATURE", "void is only valid as a return type");
    if (symbol.kind === "java") {
      requireThat(symbol.className.length > 0 && symbol.methodName.length > 0 && symbol.classLoaderId.length > 0,
        "INVALID_EVIDENCE", "Java class, method and class loader are required");
      requireThat(validJavaType(symbol.returnType, true) && symbol.argumentTypes.every(t => validJavaType(t)), "INVALID_SIGNATURE", "Unsupported Java signature");
      return Object.freeze({ ...symbol, argumentTypes: Object.freeze([...symbol.argumentTypes]) });
    }
    requireThat(symbol.kind === "native", "INVALID_EVIDENCE", "Unknown symbol kind");
    requireThat(symbol.moduleName.length > 0 && symbol.symbol.length > 0 && symbol.buildId.length > 0,
      "INVALID_EVIDENCE", "Native module, symbol and Build ID are required");
    requireThat(["debug-info", "confirmed", "unknown"].includes(symbol.signatureSource), "INVALID_EVIDENCE", "Unknown signature provenance");
    requireThat(nativeTypes.has(symbol.returnType) && symbol.argumentTypes.every(t => nativeTypes.has(t)), "INVALID_SIGNATURE", "Unsupported Native signature");
    return Object.freeze({ ...symbol, argumentTypes: Object.freeze([...symbol.argumentTypes]) });
  });
  return Object.freeze({ ...evidence, target: Object.freeze({ ...evidence.target }),
    capabilities: Object.freeze([...evidence.capabilities]), symbols: Object.freeze(symbols) });
}

// Explicit order makes a snapshot fingerprint independent of JSON object property order.
export function evidenceKey(e: EvidenceSnapshot): string {
  return JSON.stringify([e.id, e.source, e.target.packageName, e.target.versionCode, e.target.apkSha256,
    e.processInstanceId, e.abi, [...e.capabilities].sort(), [...e.symbols].sort((a, b) => a.id.localeCompare(b.id)).map(s =>
      s.kind === "java" ? [s.kind, s.id, s.loaded, s.className, s.methodName, s.classLoaderId, s.argumentTypes, s.returnType]
        : [s.kind, s.id, s.loaded, s.moduleName, s.symbol, s.buildId, s.argumentTypes, s.returnType, s.signatureSource])]);
}

export function validateHookValue(type: AbiType, value: unknown, location: string): asserts value is HookValue {
  let valid: boolean;
  switch (type) {
    case "void": valid = value === undefined; break;
    case "boolean": valid = typeof value === "boolean"; break;
    case "java.lang.String": valid = value === null || typeof value === "string"; break;
    case "[B": case "byte[]": valid = value === null || (typeof value === "string" && value.length <= 8192 && (value.length & 1) === 0 && /^[0-9a-fA-F]*$/.test(value)); break;
    case "char": valid = typeof value === "string" && value.length === 1; break;
    case "long": case "int64": valid = typeof value === "bigint" && value >= -(1n << 63n) && value < (1n << 63n); break;
    case "uint64": valid = typeof value === "bigint" && value >= 0n && value < (1n << 64n); break;
    case "float": valid = typeof value === "number" && Number.isFinite(value) && Number.isFinite(Math.fround(value)); break;
    case "double": valid = typeof value === "number" && Number.isFinite(value); break;
    default: {
      const ranges = { byte: [-128, 127], short: [-32768, 32767], int: [-2147483648, 2147483647],
        int32: [-2147483648, 2147483647], uint32: [0, 4294967295] } as const;
      const range = ranges[type as keyof typeof ranges];
      valid = isJavaReferenceType(type) ? JavaObjectFrame.valid(type,value) : range !== undefined && typeof value === "number" && Number.isInteger(value) && value >= range[0] && value <= range[1];
    }
  }
  requireThat(valid, "HOOK_TYPE", `${location}: expected ${type}`);
}

export function validateArguments(symbol: HookSymbol, args: readonly unknown[]): asserts args is readonly HookValue[] {
  requireThat(args.length === symbol.argumentTypes.length, "ARGUMENT_COUNT", `${symbol.id}: incorrect argument count`);
  symbol.argumentTypes.forEach((type, i) => validateHookValue(type, args[i], `${symbol.id} argument ${i}`));
}
