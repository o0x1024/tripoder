import Java from "frida-java-bridge";

/** Wrapped direct calls use the Java router; explicit clients use these acquisition hooks. */
export function installProviders(Bridge: any): void {
  const Resolver = Java.use("android.content.ContentResolver");
  const Restricted = Java.use(String(Bridge.resolver().getClass().getName()));
  const Provider = Java.use("android.content.ContentProvider");
  Bridge.settingsProviderHandle(Bridge.provider().getIContentProvider());
  // Hook return wrappers are disposed by frida-java-bridge: fetch settings afresh
  // and keep handle identity/ownership in Java. Never let a
  // JavaScript exception turn a required Java adapter into a default null/false return.
  const checked = (body: (this: any, ...args: any[]) => any) => function(this: any, ...args: any[]) {
    try { return body.apply(this, args); }
    catch (error: any) {
      if (error?.$h !== undefined) throw error;
      throw Java.use("java.lang.IllegalStateException").$new("PROVIDER_ADAPTER_FAILED: " + String(error));
    }
  };
  const Operation = Java.use("android.content.ContentProviderOperation");
  const selectionField = Operation.class.getDeclaredField("mSelectionArgs");
  if (String(selectionField.getType().getName()) !== "android.util.SparseArray") throw new Error("REQUIRED_BATCH_ADAPTER_UNSUPPORTED");
  const BackReference = Java.use("android.content.ContentProviderOperation$BackReference");
  if (String(BackReference.class.getDeclaredField("fromIndex").getType().getName()) !== "int" || String(BackReference.class.getDeclaredField("fromKey").getType().getName()) !== "java.lang.String") throw new Error("REQUIRED_BATCH_ADAPTER_UNSUPPORTED");
  Bridge.batchSelectionKeys.implementation = checked(function(this: any, operation: any, index: number) {
    if (String(operation.getClass().getName()) !== "android.content.ContentProviderOperation") throw Java.use("java.lang.SecurityException").$new("PROVIDER_BATCH_OPERATION_CLASS_INVALID");
    const args = Java.cast(operation, Operation).mSelectionArgs.value;
    if (args === null) return Java.array("int", []);
    if (String(args.getClass().getName()) !== "android.util.SparseArray" || args.size() > 128) throw Java.use("java.lang.IllegalArgumentException").$new("PROVIDER_BATCH_SELECTION_TOO_LARGE");
    const keys: number[] = [];
    for (let i = 0; i < args.size(); i++) {
      const key = args.keyAt(i);
      if (key < 0 || key >= 128) throw Java.use("java.lang.IllegalArgumentException").$new("PROVIDER_BATCH_SELECTION_INDEX_INVALID");
      const value = args.valueAt(i);
      if (value !== null) {
        const type = String(value.getClass().getName());
        if (type === "java.lang.String") {
          if (Java.cast(value, Java.use("java.lang.String")).length() > 8192) throw Java.use("java.lang.IllegalArgumentException").$new("PROVIDER_BATCH_SELECTION_TOO_LARGE");
        } else if (type === "android.content.ContentProviderOperation$BackReference") {
          const ref = Java.cast(value, BackReference), from = ref.fromIndex.value, fromKey = ref.fromKey.value;
          if (from < 0 || from >= index || fromKey !== null && String(fromKey).length > 512) throw Java.use("java.lang.IllegalArgumentException").$new("PROVIDER_BATCH_BACK_REFERENCE_INVALID");
        } else throw Java.use("java.lang.IllegalArgumentException").$new("PROVIDER_BATCH_SELECTION_TYPE_INVALID");
      }
      keys.push(key);
    }
    return Java.array("int", keys);
  });
  for (const method of ["acquireProvider", "acquireUnstableProvider", "acquireExistingProvider"]) {
    const acquire = Restricted[method].overload("android.content.Context", "java.lang.String");
    acquire.implementation = checked(function(this: any, context: any, authority: any) {
      if (!Bridge.ownsResolver(this)) return acquire.call(this, context, authority);
      if (String(authority) === "settings") return Bridge.provider().getIContentProvider();
      const facade = Bridge.acquireProvider(authority, method !== "acquireUnstableProvider", method === "acquireExistingProvider");
      if (facade === null) return null;
      try {
        const handle = facade.getIContentProvider();
        Bridge.rememberProviderHandle(handle, facade);
        return handle;
      } catch (error) { Bridge.releaseProvider(facade); throw error; }
    });
  }
  for (const method of ["releaseProvider", "releaseUnstableProvider"]) {
    const release = Restricted[method].overload("android.content.IContentProvider");
    release.implementation = checked(function(this: any, value: any) {
      if (!Bridge.ownsResolver(this)) return release.call(this, value);
      Bridge.releaseProviderHandle(value);
      return true;
    });
  }
  for (const observer of Resolver.registerContentObserver.overloads) {
    observer.implementation = checked(function(this: any, ...args: any[]) {
      if (Bridge.ownsResolver(this)) {
        if (args.length !== 3 && args.length !== 4) throw Java.use("java.lang.UnsupportedOperationException").$new("SETTINGS_OBSERVER_OVERLOAD_UNSUPPORTED");
        const user = args.length === 4 ? args[3] : Java.use("android.os.Process").myUserHandle().hashCode();
        return Bridge.registerSettingsObserver(args[0], args[1], args[2], user);
      }
      return observer.call(this, ...args);
    });
  }
  const unregister = Resolver.unregisterContentObserver.overload("android.database.ContentObserver");
  unregister.implementation = checked(function(this: any, observer: any) {
    if (Bridge.ownsResolver(this)) return Bridge.unregisterSettingsObserver(observer);
    return unregister.call(this, observer);
  });
  for (const name of ["takePersistableUriPermission", "releasePersistableUriPermission", "getPersistedUriPermissions", "getOutgoingPersistedUriPermissions"]) {
    for (const method of Resolver[name].overloads) method.implementation = function(this: any, ...args: any[]) {
      if (Bridge.ownsResolver(this)) throw Java.use("java.lang.UnsupportedOperationException").$new("URI_PERSISTABLE_GRANT_NOT_IMPLEMENTED");
      return method.call(this, ...args);
    };
  }
  for (const notify of Resolver.notifyChange.overloads) {
    notify.implementation = function(this: any, ...args: any[]) {
      if (Bridge.ownsResolver(this)) throw Java.use("java.lang.UnsupportedOperationException").$new("PROVIDER_OBSERVER_NOT_IMPLEMENTED");
      return notify.call(this, ...args);
    };
  }
  const Identity = Java.use("dev.tripoder.runtime.RuntimeProviderIdentity");
  const attach = Provider.attachInfo.overload("android.content.Context", "android.content.pm.ProviderInfo", "boolean");
  attach.implementation = function(this: any, context: any, info: any, testing: boolean) {
    const owned = Identity.owns(this), scoped = Bridge.providerAttachmentScoped(this);
    Java.use("android.util.Log").i("TripoderProvider", JSON.stringify({stage:"platform-attach", type:String(this.getClass().getName()), owned, scoped, contextType:context===null?null:String(context.getClass().getName())}));
    return attach.call(this, owned || scoped ? Bridge.providerAttachContext(this, context, info, testing) : context, info, testing);
  };
  for (const name of ["getCallingPackage", "getCallingPackageUnchecked", "getCallingAttributionTag", "getCallingAttributionSource"]) {
    const original = Provider[name].overload();
    original.implementation = function(this: any) {
      if (!Identity.owns(this)) return original.call(this);
      if (name === "getCallingAttributionTag") return null;
      return name === "getCallingAttributionSource" ? Identity.attribution(this) : Identity.callingPackage(this);
    };
  }
  const clear = Provider.clearCallingIdentity.overload();
  clear.implementation = function(this: any) {
    const token = clear.call(this);
    if (Identity.owns(this)) Identity.clear(this, token);
    return token;
  };
  const restore = Provider.restoreCallingIdentity.overload("android.content.ContentProvider$CallingIdentity");
  restore.implementation = function(this: any, token: any) {
    if (Identity.owns(this)) Identity.restore(this, token);
    return restore.call(this, token);
  };
}
