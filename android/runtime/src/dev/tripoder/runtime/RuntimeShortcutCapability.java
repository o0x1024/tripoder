package dev.tripoder.runtime;

import android.content.pm.ShortcutManager;

/** Capability discovery only: this runtime has no external Launcher entry contract yet. */
final class RuntimeShortcutCapability {
    private final RuntimeEngine engine;
    private ShortcutManager facade;
    RuntimeShortcutCapability(RuntimeEngine engine){this.engine=engine;}
    synchronized ShortcutManager facade(){
        if(facade==null){facade=dev.tripoder.selfhook.ManagerServiceAccess.shortcuts(engine.host,(proxy,method,args)->{
                return switch(method.getName()){
                    case "getDynamicShortcuts","getManifestShortcuts","getPinnedShortcuts","getShortcuts"->dev.tripoder.selfhook.ManagerServiceAccess.slice(java.util.List.of());
                    case "getMaxShortcutCountPerActivity"->5;
                    case "getIconMaxDimensions"->192;
                    case "getRemainingCallCount"->0;
                    case "getRateLimitResetTime"->0L;
                    case "isRequestPinItemSupported","setDynamicShortcuts","addDynamicShortcuts","updateShortcuts","requestPinShortcut"->false;
                    case "pushDynamicShortcut","removeDynamicShortcuts","removeAllDynamicShortcuts","removeLongLivedShortcuts","enableShortcuts","disableShortcuts","reportShortcutUsed"->null;
                    case "createShortcutResultIntent"->new android.content.Intent();
                    case "asBinder"->null;
                    case "toString"->"TripoderShortcutCapability";
                    case "hashCode"->System.identityHashCode(proxy);
                    case "equals"->proxy==args[0];
                    default->throw new UnsupportedOperationException("SHORTCUT_CAPABILITY_UNSUPPORTED:"+method.getName());
                };
            });if(facade==null)throw new IllegalStateException("SHORTCUT_CAPABILITY_ADAPTER_REQUIRED");}
        return facade;
    }
    boolean owns(ShortcutManager manager){return manager!=null&&manager==facade;}
    boolean pinSupported(){return false;}
}
