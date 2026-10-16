import Java from "frida-java-bridge";

export function installServices(Bridge: any) {
  Bridge.bindServiceFlags.implementation = (flags: any) => flags.getValue();
  const Service = Java.use("android.app.Service");
  const attach = Service.attach.overload("android.content.Context", "android.app.ActivityThread", "java.lang.String", "android.os.IBinder", "android.app.Application", "java.lang.Object");
  attach.implementation = function(this: any, context: any, thread: any, name: any, token: any, application: any, manager: any) {
    if (!Bridge.ownsService(this)) return attach.call(this, context, thread, name, token, application, manager);
    return attach.call(this, Bridge.context(context), thread, name, token, Bridge.application(), manager);
  };
  const hooks = new Set<string>();
  Bridge.installServiceAdapter.implementation = (service: any) => {
    const factory = Java.ClassFactory.get(service.getClass().getClassLoader());
    const signatures: Record<string, string[]> = {onCreate: [], onDestroy: [], onBind: ["android.content.Intent"], onUnbind: ["android.content.Intent"], onRebind: ["android.content.Intent"], onStartCommand: ["android.content.Intent", "int", "int"]};
    for (const [name, signature] of Object.entries(signatures)) {
      const types = signature.map(s => s === "int" ? Java.use("java.lang.Integer").TYPE.value : Java.use(s).class);
      const declaring = String(service.getClass().getMethod(name, Java.array("java.lang.Class", types)).getDeclaringClass().getName());
      const key = declaring + "/" + name;
      if (hooks.has(key)) continue;
      const clazz = factory.use(declaring);
      const method = clazz[name].overload(...signature);
      method.implementation = function(this: any, ...args: any[]) {
        if (!Bridge.ownsService(this)) return method.call(this, ...args);
        // Only the most-derived implementation translates the physical Intent. A guest
        // super call must receive the already-translated Intent without translating twice.
        const actual = String(this.getClass().getMethod(name, Java.array("java.lang.Class", types)).getDeclaringClass().getName());
        if (actual !== declaring) return method.call(this, ...args);
        if (signature[0] === "android.content.Intent") args[0] = Bridge.serviceIntent(this, args[0]);
        const result = method.call(this, ...args);
        Bridge.serviceEvent(this, name);
        return result;
      };
      hooks.add(key);
    }
  };
  const stopResult = Service.stopSelfResult.overload("int");
  stopResult.implementation = function(this: any, id: number) {
    if (!Bridge.ownsService(this)) return stopResult.call(this, id);
    return this.mActivityManager.value.stopServiceToken(Bridge.serviceComponent(this), this.mToken.value, id);
  };
  const stopId = Service.stopSelf.overload("int");
  stopId.implementation = function(this: any, id: number) {
    if (!Bridge.ownsService(this)) return stopId.call(this, id);
    this.mActivityManager.value.stopServiceToken(Bridge.serviceComponent(this), this.mToken.value, id);
  };
  for (const name of ["startForeground", "stopForeground", "getForegroundServiceType"]) {
    for (const method of Service[name].overloads) method.implementation = function(this: any, ...args: any[]) {
      if (!Bridge.ownsService(this)) return method.call(this, ...args);
      throw Java.use("java.lang.UnsupportedOperationException").$new("FOREGROUND_SERVICE_POLICY_NOT_IMPLEMENTED");
    };
  }
}
