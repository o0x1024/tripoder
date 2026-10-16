import Java from "frida-java-bridge";
import { applicationHistory } from "./application-history.js";

/** A dedicated facade owns the guest capability. Platform cached managers stay platform-owned. */
export function installActivityManager(Bridge: any) {
  const Manager = Java.use("android.app.ActivityManager");
  const Handler = Java.use("android.os.Handler");
  const Looper = Java.use("android.os.Looper");
  const create = Manager.$new.overload("android.content.Context", "android.os.Handler");
  Bridge.createActivityManager.implementation = (host: any) => create.call(Manager, host, Handler.$new(Looper.getMainLooper()));
  const history = applicationHistory(Bridge);
  const allowed: Record<string, (args: any[]) => any> = {
    "getMemoryClass()": () => Bridge.memoryClass(false),
    "getLargeMemoryClass()": () => Bridge.memoryClass(true),
    "isLowRamDevice()": () => Bridge.lowRamDevice(),
    "getMemoryInfo(android.app.ActivityManager$MemoryInfo)": args => Bridge.memoryInfo(args[0]),
    "getRunningAppProcesses()": () => Bridge.processView(),
    "getHistoricalProcessStartReasons(int)": args => history(args[0]),
  };
  const installed = new Set<string>();
  for (const declaration of Manager.class.getDeclaredMethods()) {
    if ((declaration.getModifiers() & 8) !== 0) continue;
    const name = String(declaration.getName());
    const signature = Array.from(declaration.getParameterTypes(), (type: any) => String(type.getName()));
    const key = name + "(" + signature.join(",") + ")";
    if (installed.has(key)) continue;
    const method = Manager[name].overload(...signature);
    method.implementation = function (this: any, ...args: any[]) {
      if (!Bridge.ownsActivityManager(this)) return method.call(this, ...args);
      const capability = allowed[key];
      if (!capability) throw Java.use("java.lang.UnsupportedOperationException").$new("ACTIVITY_MANAGER_CAPABILITY_UNSUPPORTED:" + key);
      return capability(args);
    };
    installed.add(key);
  }
  for (const key of Object.keys(allowed)) if (!installed.has(key)) throw new Error("ACTIVITY_MANAGER_SIGNATURE_MISSING:" + key);
}
