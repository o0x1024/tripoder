import Java from "frida-java-bridge";

/** Only the measured libc kernel-entry wrappers; no pattern scan, guessed signature or alternate backend. */
export function installStorage(Bridge: any): void {
  const libc = Process.getModuleByName("libc.so"), adapter = Process.getModuleByName("libtripoderio.so");
  const required: [string, string, number][] = [
    ["__openat", "tp_openat", 0xe0a40], ["fstatat", "tp_fstatat", 0xe0b40],
    ["unlinkat", "tp_unlinkat", 0xe0d00], ["mkdirat", "tp_mkdirat", 0xe0bc0],
    ["renameat2", "tp_renameat2", 0xe0c80], ["__faccessat", "tp_faccessat", 0xe0a80],
    ["readlinkat", "tp_readlinkat", 0xe0c40], ["chdir", "tp_chdir", 0xe1040],
    ["symlinkat", "tp_symlinkat", 0xe0cc0], ["linkat", "tp_linkat", 0xe0b80],
  ];
  const symbols = libc.enumerateSymbols();
  const bindings = required.map(([name, replacement, offset]) => {
    const matches = symbols.filter(s => s.name === name && s.type === "function");
    if (matches.length !== 1 || !matches[0].address.equals(libc.base.add(offset))) throw new Error("NATIVE_ENTRY_NOT_VALIDATED:" + name);
    return [matches[0].address, adapter.getExportByName(replacement)] as const;
  });
  const spec = JSON.parse(String(Bridge.nativeStorageSpec()));
  const strings = [spec.data, spec.dataCanonical, spec.artifacts, spec.artifactsCanonical, spec.package].map(s => Memory.allocUtf8String(s));
  const configure = new NativeFunction(adapter.getExportByName("tp_configure"), "int", ["pointer", "pointer", "pointer", "pointer", "pointer"]);
  if (configure(...strings as [NativePointer, NativePointer, NativePointer, NativePointer, NativePointer]) !== 0) throw new Error("NATIVE_STORAGE_CONFIGURATION_FAILED");
  for (const [target, replacement] of bindings) Interceptor.replace(target, replacement);
  Interceptor.flush();
}
