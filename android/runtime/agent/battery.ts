import Java from "frida-java-bridge";

export function installBattery(Bridge: any) {
  const Manager = Java.use("android.os.BatteryManager");
  const create = Manager.$new.overload("android.content.Context", "com.android.internal.app.IBatteryStats", "android.os.IBatteryPropertiesRegistrar");
  // This facade contains no physical service handles. Only the explicit routes below query them.
  Bridge.createBatteryManager.implementation = () => create.call(Manager, null, null, null);
  const queries = new Set(["isCharging()", "computeChargeTimeRemaining()"]);
  const properties = new Set(["getIntProperty(int)", "getLongProperty(int)"]);
  const required = new Set([...queries,...properties]), installed = new Set<string>();
  for (const declaration of Manager.class.getDeclaredMethods()) {
    if ((declaration.getModifiers() & 8) !== 0) continue;
    const name = String(declaration.getName()), signature = Array.from(declaration.getParameterTypes(), (type: any) => String(type.getName()));
    const key = name+"("+signature.join(",")+")";
    if (installed.has(key)) continue;
    const method = Manager[name].overload(...signature);
    method.implementation = function(this: any,...args: any[]) {
      if (!Bridge.ownsBatteryManager(this)) return method.call(this,...args);
      if (key === "getIntProperty(int)") return Bridge.batteryIntProperty(args[0]);
      if (properties.has(key)) Bridge.batteryProperty(args[0]);
      if (queries.has(key)||properties.has(key)) return method.call(Bridge.batteryPlatform(),...args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("BATTERY_CAPABILITY_UNSUPPORTED:"+key);
    };
    installed.add(key);
  }
  for (const key of required) if (!installed.has(key)) throw new Error("BATTERY_SIGNATURE_MISSING:"+key);
}
