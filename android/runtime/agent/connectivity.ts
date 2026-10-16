import Java from "frida-java-bridge";

export function installConnectivity(Bridge: any) {
  const Manager = Java.use("android.net.ConnectivityManager");
  const create = Manager.$new.overload("android.content.Context", "android.net.IConnectivityManager");
  Bridge.createConnectivityManager.implementation = (host: any) => {
    const physical = Java.cast(host.getSystemService("connectivity"), Manager);
    return create.call(Manager, host, physical.mService.value);
  };
  const Callback = Java.use("android.net.ConnectivityManager$NetworkCallback");
  const flags = Callback.class.getDeclaredField("mFlags");flags.setAccessible(true);
  Bridge.networkCallbackFlags.implementation = (callback: any) => flags.getInt(callback);
  const reads = new Set([
    "getActiveNetwork()", "getActiveNetworkInfo()", "getAllNetworks()", "getAllNetworkInfo()",
    "getNetworkInfo(int)", "getNetworkInfo(android.net.Network)", "getNetworkCapabilities(android.net.Network)",
    "getLinkProperties(android.net.Network)", "getDefaultProxy()", "isActiveNetworkMetered()", "getRestrictBackgroundStatus()",
  ]);
  const operations: Record<string, (args: any[]) => any> = {
    "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback)": a => Bridge.networkRegister(null, a[0], null, true),
    "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback,android.os.Handler)": a => Bridge.networkRegister(null, a[0], a[1], true),
    "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback)": a => Bridge.networkRegister(a[0], a[1], null, false),
    "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback,android.os.Handler)": a => Bridge.networkRegister(a[0], a[1], a[2], false),
    "unregisterNetworkCallback(android.net.ConnectivityManager$NetworkCallback)": a => Bridge.networkUnregister(a[0]),
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
      if (!Bridge.ownsConnectivityManager(this)) return method.call(this, ...args);
      if (reads.has(key)) return method.call(Bridge.networkPlatform(), ...args);
      if (operations[key]) return operations[key](args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("CONNECTIVITY_CAPABILITY_UNSUPPORTED:" + key);
    };
    installed.add(key);
  }
  for (const key of [...reads, ...Object.keys(operations)]) if (!installed.has(key)) throw new Error("CONNECTIVITY_SIGNATURE_MISSING:" + key);
}
