import Java from "frida-java-bridge";
import type { JavaMethod, HookValue } from "../../../src/evidence.js";

export type JavaBinding = { kind: "java"; symbol: JavaMethod; method: any; owner: any; nativeDeclaration: boolean; replacement?: any; installed: boolean };
export function bindJava(Bridge: any, symbol: JavaMethod): JavaBinding {
  const factory = Java.ClassFactory.get(Bridge.loader(symbol.id));
  const owner = (factory as any).use(symbol.className, { members: "methods" });
  if (!Bridge.sameClass(symbol.id, owner.class)) throw new Error("HOOK_CLASS_IDENTITY_CHANGED");
  const method = owner[symbol.methodName].overload(...symbol.argumentTypes);
  if (method.returnType.className !== symbol.returnType || method.implementation !== null)
    throw new Error("HOOK_METHOD_CHANGED_OR_OWNED");
  return { kind: "java", symbol, method, owner, nativeDeclaration: Boolean(Bridge.nativeMethod(symbol.id)), installed: false };
}
export function decodeJava(type: string, value: any): HookValue {
  if (type === "long") return BigInt(value.toString());
  if (type === "java.lang.String") return value === null ? null : String(value);
  return value;
}
export function encodeJava(type: string, value: HookValue): any {
  if (type === "long") return new Int64(String(value));
  if (type === "float") return Math.fround(value as number);
  return value;
}
