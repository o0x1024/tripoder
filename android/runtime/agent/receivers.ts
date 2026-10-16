import Java from "frida-java-bridge";

export function installReceivers(Bridge: any): void {
  const Thread = Java.use("android.app.ActivityThread");
  const physicalPackages=Java.retain(Thread.getPackageManager());
  Bridge.protectedBroadcast.implementation = (action: any) => physicalPackages.isProtectedBroadcast(action);
  const Receiver = Java.use("android.content.BroadcastReceiver");
  const hooks = new Set<string>();
  const checked = (body: (this: any, ...args: any[]) => any) => function(this: any, ...args: any[]) {
    try { return body.apply(this, args); }
    catch (error: any) { if (error?.$h !== undefined) throw error; throw Java.use("java.lang.IllegalStateException").$new("RECEIVER_ADAPTER_FAILED: " + String(error)); }
  };
  Bridge.installReceiverAdapter.implementation = checked((receiver: any) => {
    const factory = Java.ClassFactory.get(receiver.getClass().getClassLoader());
    const types = Java.array("java.lang.Class", [Java.use("android.content.Context").class, Java.use("android.content.Intent").class]);
    const declaring = String(receiver.getClass().getMethod("onReceive", types).getDeclaringClass().getName());
    if (hooks.has(declaring)) return;
    const method = (factory as any).use(declaring, { members: "methods" }).onReceive.overload("android.content.Context", "android.content.Intent");
    method.implementation = checked(function(this: any, context: any, intent: any) {
      if (!Bridge.ownsReceiver(this)) return method.call(this, context, intent);
      const actual = String(this.getClass().getMethod("onReceive", types).getDeclaringClass().getName());
      if (actual !== declaring) return method.call(this, context, intent);
      const logical = Bridge.receiverBefore(this, intent, Java.cast(this, Receiver).getPendingResult());
      if (logical === null) return;
      let success = false;
      try { const result = method.call(this, Bridge.context(context), logical); success = true; return result; }
      finally { Bridge.receiverAfter(this, success); }
    });
    hooks.add(declaring);
  });
  const async = Receiver.goAsync.overload();
  async.implementation = checked(function(this: any) {
    const pending = async.call(this);
    if (Bridge.ownsReceiver(this)) Bridge.receiverAsync(this, pending);
    return pending;
  });
  const finish = Java.use("android.content.BroadcastReceiver$PendingResult").finish.overload();
  finish.implementation = checked(function(this: any) { const result = finish.call(this); Bridge.receiverFinished(this); return result; });
  const peek = Receiver.peekService.overload("android.content.Context", "android.content.Intent");
  peek.implementation = checked(function(this: any, context: any, intent: any) {
    if (Bridge.ownsReceiver(this)) throw Java.use("java.lang.UnsupportedOperationException").$new("RECEIVER_PEEK_SERVICE_NOT_IMPLEMENTED");
    return peek.call(this, context, intent);
  });
}
