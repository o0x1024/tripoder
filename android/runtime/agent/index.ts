import Java from "frida-java-bridge";
import { installServices } from "./services.js";
import { installProviders } from "./providers.js";
import { installReceivers } from "./receivers.js";
import { installStorage } from "./storage.js";
import { installActivityDelivery } from "./activities.js";
import { installActivityCreation } from "./activity-creation.js";
import { installHooks } from "./hooks.js";
import { installActivityManager } from "./activity-manager.js";
import { installConnectivity } from "./connectivity.js";
import { installTelephony } from "./telephony.js";
import { installAudio } from "./audio.js";
import { installBattery } from "./battery.js";
import { installPower } from "./power.js";
import { installNetworkStats } from "./network-stats.js";
import { installWifiP2p } from "./wifi-p2p.js";
import { installFailureDiagnostics } from "./failures.js";
import { installNotifications } from "./notifications.js";
import { installShortcutCapability } from "./shortcuts.js";
import { installApplicationOwnership } from "./application-ownership.js";
import { installApplicationContext } from "./application-context.js";
import { installPackages } from "./packages.js";

// Required engine adaptation only. No guest package, fixture method or test result is hardcoded.
Java.performNow(() => {
  const Bridge = Java.use("dev.tripoder.runtime.RuntimeBridge");
  try {
  const Activity = Java.use("android.app.Activity");
  // View retains its own enabled/attachment checks. Only the click effect at the
  // owned window boundary is granted; the audio facade has its own capability checks.
  const ViewRoot = (Java.classFactory as any).use("android.view.ViewRootImpl", { members: "methods" });
  ViewRoot.playSoundEffect.overload("int").implementation = function (this: any, effect: number) {
    Bridge.playUiSound(this.getView(), effect);
  };
  installActivityDelivery(Bridge);
  const QueuedWork = (Java.classFactory as any).use("android.app.QueuedWork", { members: "methods" });
  const addFinisher = QueuedWork.addFinisher.overload("java.lang.Runnable");
  Bridge.storageFinisher.implementation = (finisher: any) => {
    try { addFinisher.call(QueuedWork, finisher); }
    catch (error) { throw Java.use("java.lang.IllegalStateException").$new("STORAGE_FINISHER_FAILED: " + String(error)); }
  };
  const applicationAttach = Java.use("android.app.Application").attach.overload("android.content.Context");
  Bridge.attachApplication.implementation = (application: any, context: any) => applicationAttach.call(application, context);
  installApplicationOwnership(Bridge);
  installApplicationContext(Bridge);
  Java.use("android.app.Application").getProcessName.implementation = () => Bridge.processName();
  installProviders(Bridge);
  installShortcutCapability(Bridge);
  installReceivers(Bridge);
  const Wrapper = Java.use("android.content.ContextWrapper");
  const Theme = Java.use("android.view.ContextThemeWrapper");
  Bridge.attach.implementation = (activity: any) => {
    try {
    if (!Bridge.owns(activity)) throw Java.use("java.lang.SecurityException").$new("NOT_GUEST_ACTIVITY");
    Bridge.requireActivityContext(activity.getBaseContext());
    Java.cast(activity, Theme).mResources.value = Bridge.resources();
    Java.cast(activity, Theme).mTheme.value = null;
    Java.cast(activity, Theme).mThemeResource.value = 0;
    Java.cast(activity, Activity).mApplication.value = Bridge.application();
    const visible = Bridge.intent(activity);
    Java.cast(activity, Activity).mComponent.value = visible.getComponent();
    Java.cast(activity, Activity).mActivityInfo.value = Bridge.activityInfo(activity);
    activity.setIntent(visible, activity.getCaller());
    activity.setTheme(Bridge.theme(activity));
    } catch (error) { throw Java.use("java.lang.IllegalStateException").$new("ACTIVITY_ATTACH_FAILED: " + String(error)); }
  };
  const Instrumentation = Java.use("android.app.Instrumentation");
  installActivityCreation(Bridge);
  const startResult = Instrumentation.checkStartActivityResult.overload("int", "java.lang.Object");
  startResult.implementation = function (this: any, result: number, intent: any) {
    Bridge.activityStartResult(intent, result);
    return startResult.call(this, result, intent);
  };
  for (const name of [...new Set(Instrumentation.$ownMembers.filter((s: string) => s.startsWith("execStartActivit")))]) {
    for (const overload of Instrumentation[name as string].overloads) {
      overload.implementation = () => { throw Java.use("java.lang.UnsupportedOperationException").$new("ACTIVITY_START_SIGNATURE_UNSUPPORTED"); };
    }
  }
  const start = Instrumentation.execStartActivity.overload("android.content.Context", "android.os.IBinder", "android.os.IBinder", "android.app.Activity", "android.content.Intent", "int", "android.os.Bundle");
  start.implementation = function (this: any, who: any, thread: any, token: any, target: any, intent: any, code: number, options: any) {
    if (!Bridge.owns(target)) throw Java.use("java.lang.SecurityException").$new("ACTIVITY_START_CALLER_UNOWNED");
    if (options !== null) throw Java.use("java.lang.UnsupportedOperationException").$new("ACTIVITY_START_OPTIONS_UNSUPPORTED");
    const routed = Bridge.route(target, intent, code);
    return start.call(this, who, thread, token, target, routed, code, null);
  };
  const finish = Activity.finish.overload();
  finish.implementation = function (this: any) {
    if (this.getClass().getName().toString() === "dev.tripoder.runtime.RuntimeRejectedActivity") return finish.call(this);
    if (!Bridge.owns(this)) throw Java.use("java.lang.SecurityException").$new("RESULT_CALLER_UNSUPPORTED");
    // Android permits setResult to replace an earlier value; only finish submits it.
    Bridge.returning(this, this.mResultCode.value, this.mResultData.value);
    return finish.call(this);
  };
  const delivery = Activity.dispatchActivityResult.overload("java.lang.String", "int", "int", "android.content.Intent", "android.app.ComponentCaller", "java.lang.String");
    delivery.implementation = function (this: any, ...args: any[]) {
      // Free the request code before guest code may re-enter startActivityForResult.
      // Receipt is still acknowledged only after that real callback returns.
      const operation = Bridge.receiving(this, args[1], args[2], args[4]);
      let success = false;
      try { const value = delivery.call(this, ...args); success = true; return value; }
      finally { Bridge.received(this, operation, args[2], success); }
    };
  installServices(Bridge);
  installActivityManager(Bridge);
  installConnectivity(Bridge);
  installTelephony(Bridge);
  installAudio(Bridge);
  installBattery(Bridge);
  installPower(Bridge);
  installNetworkStats(Bridge);
  installWifiP2p(Bridge);
  installNotifications(Bridge);
  installPackages();
  installFailureDiagnostics(Bridge);
  installStorage(Bridge);
  installHooks();
  const artEvidence = (globalThis as any).__tripoderArtMethodEvidence;
  if (!artEvidence) throw new Error("ART_METHOD_EVIDENCE_MISSING");
  Java.use("android.util.Log").i("TripoderART", JSON.stringify(artEvidence));
  Bridge.ready();
  } catch (error) { Bridge.failed(String(error)); }
});
