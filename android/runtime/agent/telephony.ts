import Java from "frida-java-bridge";

export function installTelephony(Bridge: any) {
  const Manager = Java.use("android.telephony.TelephonyManager");
  const create = Manager.$new.overload("android.content.Context");
  Bridge.createTelephonyManager.implementation = (host: any) => create.call(Manager, host);
  const allowed = new Set([
    "getPhoneType()", "getSimState()", "getSimState(int)", "getPhoneCount()", "getActiveModemCount()",
    "getSupportedModemCount()", "isVoiceCapable()", "isSmsCapable()", "isDataCapable()",
  ]);
  const required = new Set([...allowed, "registerTelephonyCallback(java.util.concurrent.Executor,android.telephony.TelephonyCallback)", "registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)", "unregisterTelephonyCallback(android.telephony.TelephonyCallback)"]);
  const installed = new Set<string>();
  for (const declaration of Manager.class.getDeclaredMethods()) {
    if ((declaration.getModifiers() & 8) !== 0) continue;
    const name = String(declaration.getName());
    const signature = Array.from(declaration.getParameterTypes(), (type: any) => String(type.getName()));
    const key = name + "(" + signature.join(",") + ")";
    if (installed.has(key)) continue;
    const method = Manager[name].overload(...signature);
    method.implementation = function (this: any, ...args: any[]) {
      if (!Bridge.ownsTelephonyManager(this)) return method.call(this, ...args);
      if (allowed.has(key)) return method.call(Bridge.telephonyPlatform(), ...args);
      if (key === "registerTelephonyCallback(java.util.concurrent.Executor,android.telephony.TelephonyCallback)") return Bridge.telephonyRegister(...args);
      if (key === "registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)") {
        if (args[0] !== Manager.INCLUDE_LOCATION_DATA_NONE.value) throw Java.use("java.lang.UnsupportedOperationException").$new("TELEPHONY_LOCATION_CALLBACK_UNSUPPORTED");
        return Bridge.telephonyRegister(args[1], args[2]);
      }
      if (key === "unregisterTelephonyCallback(android.telephony.TelephonyCallback)") return Bridge.telephonyUnregister(args[0]);
      throw Java.use("java.lang.UnsupportedOperationException").$new("TELEPHONY_CAPABILITY_UNSUPPORTED:" + key);
    };
    installed.add(key);
  }
  for (const key of required) if (!installed.has(key)) throw new Error("TELEPHONY_SIGNATURE_MISSING:" + key);
}
