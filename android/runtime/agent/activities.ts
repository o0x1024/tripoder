import Java from "frida-java-bridge";

/** One actual framework new-Intent entry; never invoke guest callbacks ourselves. */
export function installActivityDelivery(Bridge: any): void {
  const Activity = Java.use("android.app.Activity");
  const perform = Activity.performNewIntent.overload("android.content.Intent", "android.app.ComponentCaller");
  perform.implementation = function (this: any, physical: any, caller: any) {
    if (!Bridge.owns(this)) return perform.call(this, physical, caller);
    const delivery = physical.getStringExtra("tripoder.delivery");
    const visible = Bridge.newIntent(this, physical, caller);
    if (visible === null) return; // Exact permanently closed entry: no guest delivery or success ACK.
    try { perform.call(this, visible, caller); }
    catch (error) {
      try { Bridge.newIntentComplete(this, delivery, false); }
      catch (ackError) { Java.use("android.util.Log").e("TripoderRuntime", "Failed newIntent acknowledgement: " + String(ackError)); }
      throw error;
    }
    Bridge.newIntentComplete(this, delivery, true);
  };
  const legacy = Activity.performNewIntent.overload("android.content.Intent");
  legacy.implementation = function (this: any, intent: any) {
    if (Bridge.owns(this)) throw Java.use("java.lang.UnsupportedOperationException").$new("ACTIVITY_CALLER_DELIVERY_REQUIRED");
    return legacy.call(this, intent);
  };
  for (const name of ["getCaller", "getInitialCaller", "getCurrentCaller"]) {
    const method = Activity[name].overload();
    method.implementation = function (this: any) {
      const caller = method.call(this);
      return Bridge.owns(this) ? Bridge.activityCaller(this, caller, name === "getCurrentCaller") : caller;
    };
  }
  const referrer = Activity.getReferrer.overload();
  referrer.implementation = function (this: any) { return Bridge.owns(this) ? Bridge.activityReferrer(this) : referrer.call(this); };
  const calling = Activity.getCallingActivity.overload();
  calling.implementation = function (this: any) {
    const physical = calling.call(this);
    return Bridge.owns(this) ? Bridge.callingActivity(this, physical) : physical;
  };
  const callingPackage = Activity.getCallingPackage.overload();
  callingPackage.implementation = function (this: any) {
    if (!Bridge.owns(this)) return callingPackage.call(this);
    // This calls the single mapping boundary above. Invoking that intercepted
    // wrapper from another hook and mapping again would transform it twice.
    const logical = this.getCallingActivity();
    return logical === null ? null : logical.getPackageName();
  };
  const Caller = Java.use("android.app.ComponentCaller");
  const pkg = Caller.getPackage.overload();
  pkg.implementation = function (this: any) { return Bridge.ownsCaller(this) ? Bridge.callerPackage(this) : pkg.call(this); };
  const permission = Caller.checkContentUriPermission.overload("android.net.Uri", "int");
  permission.implementation = function (this: any, uri: any, flags: number) {
    if (Bridge.ownsCaller(this)) throw Java.use("java.lang.UnsupportedOperationException").$new("URI_GRANT_NOT_IMPLEMENTED");
    return permission.call(this, uri, flags);
  };
}
