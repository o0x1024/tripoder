package dev.tripoder.runtime;

import android.content.Context;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.Executable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unwrapping a guest Context must not ask StorageManager to create a directory
 * owned by an uninstalled package. These exact framework entries share the
 * explicit wrapper's storage owner; physical host/provider contexts pass through. */
final class RuntimeSelfHookRawStorage {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable method:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.CONTEXT_STORAGE))callbacks.put(method,(receiver,args,original)->{
            RuntimeEngine engine=RuntimeEngine.current;Context source=(Context)receiver;
            if(!RuntimePackageBridge.active()||engine==null||!engine.packageName.equals(source.getPackageName()))return original.call(args);
            ApplicationAdapter.requireLogicalContext(source);
            RuntimeStorage storage=source.isDeviceProtectedStorage()?engine.storage.view("device-protected"):engine.storage;
            GuestStorageContext view=new GuestStorageContext(engine.host,storage){};
            return switch(method.getName()){
                case "getExternalFilesDir"->view.getExternalFilesDir((String)args[0]);
                case "getExternalFilesDirs"->view.getExternalFilesDirs((String)args[0]);
                case "getExternalCacheDir"->view.getExternalCacheDir();
                case "getExternalCacheDirs"->view.getExternalCacheDirs();
                case "getObbDir"->view.getObbDir();
                case "getObbDirs"->view.getObbDirs();
                case "getExternalMediaDirs"->view.getExternalMediaDirs();
                default->throw new IllegalStateException("RAW_STORAGE_ENTRY_INVALID");
            };
        });
        installer.install(callbacks);
    }
    private RuntimeSelfHookRawStorage(){}
}
