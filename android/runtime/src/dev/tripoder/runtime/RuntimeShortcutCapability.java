package dev.tripoder.runtime;

import android.content.pm.ShortcutManager;

/** Capability discovery only: this runtime has no external Launcher entry contract yet. */
final class RuntimeShortcutCapability {
    private final RuntimeEngine engine;
    private ShortcutManager facade;
    RuntimeShortcutCapability(RuntimeEngine engine){this.engine=engine;}
    synchronized ShortcutManager facade(){
        if(facade==null){facade=RuntimeBridge.createShortcutCapability(new GuestContext(engine.host,engine));if(facade==null)throw new IllegalStateException("SHORTCUT_CAPABILITY_ADAPTER_REQUIRED");}
        return facade;
    }
    boolean owns(ShortcutManager manager){return manager!=null&&manager==facade;}
    boolean pinSupported(){return false;}
}
