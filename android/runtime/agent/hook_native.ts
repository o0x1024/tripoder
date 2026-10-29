import type { NativeFunction as NativeSymbol, NativeType, HookValue } from "../../../src/evidence.js";
import { LinkerModules, type LoadedModule } from "./hook_modules.js";

export type NativeBinding = { kind: "native"; symbol: NativeSymbol; loaded: LoadedModule; address: NativePointer; metadata: any;
  original: any; replacement?: NativeCallback<any, any>; installed: boolean };

/** Exact artifact path + live build ID + load generation. No global symbol search. */
export class NativeTargets {
  private readonly selected = new Map<string, NativeBinding>();
  private readonly modules: LinkerModules;
  constructor(private readonly Bridge: any, removed: (generation: number) => void, fatal: (error: unknown) => never) { this.modules=new LinkerModules(removed,fatal); }
  lifecycle(): any[] { const names=new Set<string>(JSON.parse(String(this.Bridge.libraryNames())));return this.modules.events.filter(event=>names.has(event.moduleName)); }
  clear(): void { this.selected.clear(); }
  contains(generation: number): boolean { return [...this.selected.values()].some(value => value.loaded.generation === generation); }
  inventory(): any[] {
    const allowed = new Set<string>(JSON.parse(String(this.Bridge.libraryNames())));
    return [...this.modules.loaded.values()].filter(entry => allowed.has(entry.name)).map(entry => {
      try { return { ...this.attest(entry.name).metadata, state: "AVAILABLE" }; }
      catch (error) { return { moduleName: entry.name, path: entry.path, state: "UNAVAILABLE", reason: String(error).slice(0, 500) }; }
    });
  }
  private attest(name: string): { loaded: LoadedModule; metadata: any } {
    const metadata = JSON.parse(String(this.Bridge.library(name)));
    // Android exposes the same verified file through /data/user/0 and its
    // canonical /data/data root. Both names are attested before guest execution.
    const matches = [...this.modules.loaded.values()].filter(entry => entry.name === name
      && (entry.path === metadata.path || entry.path === metadata.sourcePath));
    if (matches.length !== 1) throw new Error("HOOK_MODULE_NOT_UNIQUELY_LOADED");
    const loaded = matches[0]!, size=metadata.virtualSize;
    for (const note of metadata.buildIdNotes) {
      if (!Number.isSafeInteger(note.virtualAddress) || note.virtualAddress < 0 || note.virtualAddress + note.size > size) throw new Error("HOOK_LIVE_BUILD_ID_BOUNDS");
      const bytes = new Uint8Array(loaded.base.add(note.virtualAddress).readByteArray(note.size)!);
      const actual = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
      if (actual !== metadata.buildId) throw new Error("HOOK_LIVE_BUILD_ID_MISMATCH");
    }
    return { loaded, metadata: { ...metadata, moduleGeneration: loaded.generation, base: String(loaded.base), size } };
  }
  observe(selectors: any[]): NativeSymbol[] {
    const bindings: NativeBinding[] = [];
    for (const selector of selectors) {
      const fields = ["id", "kind", "moduleName", "symbol", "argumentTypes", "returnType", "buildId", "signatureSource"];
      const keys = Object.keys(selector);
      const validKeys = (keys.length === fields.length && fields.every(key => Object.hasOwn(selector, key)))
        || (keys.length === fields.length + 1 && fields.every(key => Object.hasOwn(selector, key)) && Object.hasOwn(selector, "offset"));
      if (!validKeys || selector.kind !== "native"
        || !/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(selector.id) || typeof selector.symbol !== "string" || selector.symbol.length > 256
        || typeof selector.moduleName !== "string" || !/^lib[a-zA-Z0-9_.-]+\.so$/.test(selector.moduleName)
        || !Array.isArray(selector.argumentTypes) || selector.argumentTypes.length > 16 || selector.signatureSource !== "confirmed") throw new Error("HOOK_NATIVE_SELECTOR_FIELDS");
      const { loaded, metadata } = this.attest(selector.moduleName);
      if (selector.buildId !== metadata.buildId) throw new Error("HOOK_MODULE_BUILD_ID_STALE");
      let offset: number | null = null;
      if (selector.offset !== undefined) {
        offset = parseOffset(selector.offset);
      } else {
        const parsed = parseOffset(selector.symbol);
        if (parsed !== null) offset = parsed;
        else offset = Number(this.Bridge.exportAddress(selector.moduleName, selector.symbol));
      }
      if (!Number.isSafeInteger(offset) || offset! <= 0 || offset! >= metadata.size) throw new Error("HOOK_EXPORT_OFFSET_BOUNDS");
      const address = loaded.base.add(offset!);
      if (address.compare(loaded.base) < 0 || address.compare(loaded.base.add(metadata.size)) >= 0
        || !Process.getRangeByAddress(address).protection.includes("x")) throw new Error("HOOK_EXPORT_OUTSIDE_MODULE");
      if (bindings.some(binding => binding.address.equals(address))) throw new Error("HOOK_NATIVE_ADDRESS_ALIAS");
      const symbol: NativeSymbol = { ...selector, loaded: true, offset: "0x" + offset!.toString(16) };
      bindings.push({ kind: "native", symbol, loaded, address, metadata, original: undefined, installed: false });
    }
    this.selected.clear();for (const binding of bindings) this.selected.set(binding.symbol.id, binding);
    return bindings.map(binding => binding.symbol);
  }
  bind(symbol: NativeSymbol): NativeBinding {
    const binding = this.selected.get(symbol.id);
    if (!binding || !binding.loaded.live || binding.symbol.buildId !== symbol.buildId) throw new Error("HOOK_MODULE_GENERATION_STALE");
    const current = this.attest(symbol.moduleName);
    if (current.loaded !== binding.loaded) throw new Error("HOOK_MODULE_GENERATION_STALE");
    return { ...binding, original: new NativeFunction(binding.address, nativeAbi(symbol.returnType), symbol.argumentTypes.map(nativeAbi), { exceptions: "propagate" }) };
  }
  evidence(): any[] { return [...this.selected.values()].map(binding => ({ id: binding.symbol.id, address: String(binding.address), ...binding.metadata })); }
}
export function nativeAbi(type: NativeType): any { return type === "int32" ? "int" : type === "uint32" ? "uint" : type; }
export function decodeNative(type: NativeType, value: any): HookValue { return type === "int64" || type === "uint64" ? BigInt(value.toString()) : value; }
export function encodeNative(type: NativeType, value: HookValue): any {
  if (type === "int64") return new Int64(String(value));if (type === "uint64") return new UInt64(String(value));
  return type === "float" ? Math.fround(value as number) : value;
}
function parseOffset(val: any): number | null {
  if (typeof val === "number" && Number.isSafeInteger(val) && val > 0) return val;
  if (typeof val === "string") {
    const s = val.trim();
    if (/^0[xX][0-9a-fA-F]+$/.test(s)) return parseInt(s.slice(2), 16);
    if (/^[sS][uU][bB]_[0-9a-fA-F]+$/.test(s)) return parseInt(s.slice(4), 16);
    if (/^[0-9]+$/.test(s)) return parseInt(s, 10);
  }
  return null;
}
