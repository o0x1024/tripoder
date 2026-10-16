export type LoadedModule = { name: string; path: string; base: NativePointer; generation: number; live: boolean };

/** ELF64 debugger protocol is the lifecycle authority. Android's soinfo list
 * still contains an unloading module when RT_CONSISTENT is emitted. */
export class LinkerModules {
  readonly loaded = new Map<string, LoadedModule>();
  readonly events: any[] = [];
  private serial = 0;
  private readonly listener: InvocationListener;
  constructor(private readonly removed: (generation: number) => void, fatal: (error: unknown) => never) {
    if (Process.arch !== "arm64" || Process.pointerSize !== 8) throw new Error("HOOK_LINKER_ABI_UNSUPPORTED");
    const debug = this.debugAddress();
    if (debug.readU32() !== 1 || debug.add(24).readU32() !== 0) throw new Error("HOOK_LINKER_NOT_CONSISTENT");
    const notify = debug.add(16).readPointer(), linker = Process.getModuleByAddress(notify);
    if (linker.name !== "linker64" || !Process.getRangeByAddress(notify).protection.includes("x")) throw new Error("HOOK_LINKER_NOTIFIER_IDENTITY");
    // Bionic's debugger rendezvous may start with an intentional breakpoint.
    const point = (notify.readU32() & 0xffe0001f) >>> 0 === 0xd4200000 ? notify.add(4) : notify;
    this.listener = Interceptor.attach(point, { onEnter: () => {
      try { if (debug.add(24).readU32() === 0) this.synchronize(debug); }
      catch (error) { fatal(error); }
    } });
    Interceptor.flush();this.synchronize(debug);
  }
  private debugAddress(): NativePointer {
    const main = Process.mainModule, base = main.base;
    if (base.readU32() !== 0x464c457f || base.add(4).readU8() !== 2 || base.add(5).readU8() !== 1) throw new Error("HOOK_LINKER_MAIN_ELF");
    const phoff = base.add(32).readU64().toNumber(), size = base.add(54).readU16(), count = base.add(56).readU16();
    if (size !== 56 || count < 1 || count > 256 || phoff + count * size > main.size) throw new Error("HOOK_LINKER_MAIN_HEADERS");
    for (let index = 0; index < count; index++) {
      const ph = base.add(phoff + index * size);
      if (ph.readU32() !== 2) continue;
      const virtual = ph.add(16).readU64().toNumber(), bytes = ph.add(40).readU64().toNumber();
      if (bytes < 16 || bytes > 65536 || virtual + bytes > main.size) throw new Error("HOOK_LINKER_DYNAMIC_BOUNDS");
      const dynamic = base.add(virtual);
      for (let offset = 0; offset + 16 <= bytes; offset += 16) {
        const tag = dynamic.add(offset).readU64().toNumber();
        if (tag === 0) break;
        if (tag === 21) { const pointer = dynamic.add(offset + 8).readPointer();if (!pointer.isNull()) return pointer; }
      }
    }
    throw new Error("HOOK_LINKER_DEBUG_MISSING");
  }
  private synchronize(debug: NativePointer): void {
    const current = new Map<string, {name: string;path: string;base: NativePointer}>(), seen = new Set<string>();
    let cursor = debug.add(8).readPointer(), previous = ptr(0);
    while (!cursor.isNull()) {
      const id = String(cursor);
      if (seen.has(id) || seen.size >= 4096 || !cursor.add(32).readPointer().equals(previous)) throw new Error("HOOK_LINKER_CHAIN_INVALID");
      seen.add(id);
      const base = cursor.readPointer(), name = cursor.add(8).readPointer();
      if (name.isNull()) throw new Error("HOOK_LINKER_NAME_MISSING");
      const path = name.readUtf8String();
      if (path === null || path.length > 4096) throw new Error("HOOK_LINKER_NAME_INVALID");
      if (path.length > 0 && !base.isNull()) {
        const key = path + ":" + base;
        if (current.has(key)) throw new Error("HOOK_LINKER_MODULE_DUPLICATE");
        current.set(key, {name:path.slice(path.lastIndexOf("/")+1),path,base});
      }
      previous = cursor;cursor = cursor.add(24).readPointer();
    }
    for (const [key, old] of this.loaded) if (!current.has(key)) {
      old.live = false;this.loaded.delete(key);this.record("removed",old);this.removed(old.generation);
    }
    for (const [key, module] of current) if (!this.loaded.has(key)) {
      const next = {...module,generation:++this.serial,live:true};this.loaded.set(key,next);this.record("added",next);
    }
  }
  private record(event: string,module: LoadedModule): void {
    this.events.push({event,moduleName:module.name,path:module.path,base:String(module.base),generation:module.generation});
    if (this.events.length > 64) this.events.shift();
  }
}
