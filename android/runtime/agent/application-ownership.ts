import Java from "frida-java-bridge";

/** Observation of the physical/logical Application boundary; no field is rewritten. */
export function installApplicationOwnership(Bridge: any) {
  const Application = Java.use("android.app.Application");
  const LoadedApk = (Java.classFactory as any).use("android.app.LoadedApk", { members: "methods" });
  const Thread = (Java.classFactory as any).use("android.app.ActivityThread", { members: "methods" });
  const System = Java.use("java.lang.System");
  const appApk = Application.class.getDeclaredField("mLoadedApk");
  const apkApp = LoadedApk.class.getDeclaredField("mApplication");
  const initialApp = Thread.class.getDeclaredField("mInitialApplication");
  for (const field of [appApk, apkApp, initialApp]) field.setAccessible(true);
  const describe = (value: any) => value === null ? null : {
    type: String(value.getClass().getName()), identity: System.identityHashCode(value),
  };
  Bridge.observeApplicationOwnership.implementation = (stage: any, host: any, guest: any) => {
    const physical = host;
    const physicalApk = appApk.get(physical);
    const guestApk = appApk.get(guest);
    const current = Thread.currentActivityThread();
    Java.use("android.util.Log").i("TripoderOwnership", JSON.stringify({
      stage: String(stage), physical: describe(physical), guest: describe(guest),
      physicalBase: describe(Java.cast(physical, Application).getBaseContext()),
      guestBase: describe(Java.cast(guest, Application).getBaseContext()),
      physicalApk: describe(physicalApk), guestApk: describe(guestApk),
      physicalApkApplication: physicalApk === null ? null : describe(apkApp.get(physicalApk)),
      initialApplication: describe(Thread.currentApplication()),
      reflectedInitialApplication: describe(initialApp.get(current)),
      hostApplicationContext: describe(host.getApplicationContext()),
    }));
  };
}
