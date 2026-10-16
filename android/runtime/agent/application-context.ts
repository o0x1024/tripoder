import Java from "frida-java-bridge";
import { installInitialApplicationView } from "./reflection-view.js";

/** One logical LoadedApk, loader, factory and ContextImpl per immutable guest process. */
export function installApplicationContext(Bridge: any) {
  const LoadedApk = (Java.classFactory as any).use("android.app.LoadedApk", { members: "methods" });
  const Thread = (Java.classFactory as any).use("android.app.ActivityThread", { members: "methods" });
  const ContextImpl = (Java.classFactory as any).use("android.app.ContextImpl", { members: "methods" });
  const AppInfo = Java.use("android.content.pm.ApplicationInfo");
  const Application = Java.use("android.app.Application");
  const fields: Record<string, any> = {};
  for (const name of ["mClassLoader", "mDefaultClassLoader", "mResources", "mApplication", "mAppComponentFactory", "mApplicationInfo"]) {
    fields[name] = LoadedApk.class.getDeclaredField(name); fields[name].setAccessible(true);
  }
  const appApk = Application.class.getDeclaredField("mLoadedApk"); appApk.setAccessible(true);
  const credentialData = AppInfo.class.getDeclaredField("credentialProtectedDataDir"); credentialData.setAccessible(true);
  const initialApp = Thread.class.getDeclaredField("mInitialApplication"); initialApp.setAccessible(true);
  const configureInitial = installInitialApplicationView(initialApp);
  const construct = LoadedApk.$new.overload("android.app.ActivityThread", "android.content.pm.ApplicationInfo",
    "android.content.res.CompatibilityInfo", "java.lang.ClassLoader", "boolean", "boolean", "boolean");
  const context = ContextImpl.createAppContext.overload("android.app.ActivityThread", "android.app.LoadedApk", "java.lang.String");
  Bridge.createApplicationContext.implementation = (host: any, guest: any, info: any, loader: any, factory: any, resources: any) => {
    const thread = Thread.currentActivityThread();
    configureInitial(thread, host, guest);
    const physical = Java.cast(appApk.get(host), LoadedApk);
    // No constructor-side guest factory or class-loader creation. The engine already owns both.
    const declared = AppInfo.$new(info), construction = AppInfo.$new(info);
    credentialData.set(declared, declared.dataDir.value); credentialData.set(construction, construction.dataDir.value);
    construction.appComponentFactory.value = null;
    const logical = construct.call(LoadedApk, thread, construction, physical.getCompatibilityInfo(), null, false, true, false);
    fields.mApplicationInfo.set(logical, declared);
    fields.mClassLoader.set(logical, loader); fields.mDefaultClassLoader.set(logical, loader);
    fields.mResources.set(logical, resources); fields.mAppComponentFactory.set(logical, factory);
    fields.mApplication.set(logical, guest);
    const base = context.call(ContextImpl, thread, logical, host.getPackageName());
    base.setOuterContext(guest);
    return base;
  };
  Bridge.checkApplicationOwnership.implementation = (host: any, guest: any) => {
    const physical = appApk.get(host), logical = appApk.get(guest);
    if (logical === null || physical.equals(logical) || !fields.mApplication.get(physical).equals(host) ||
        !fields.mApplication.get(logical).equals(guest) || !host.getApplicationContext().equals(host) ||
        !Thread.currentApplication().equals(host))
      throw Java.use("java.lang.SecurityException").$new("APPLICATION_FRAMEWORK_OWNERSHIP_VIOLATION");
  };
}
