package dev.tripoder.runtime;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import dev.tripoder.selfhook.FixedManagerInventory;

/** Typed callback adapters over the same real, permanent ManagedHook owner. */
final class RuntimeSelfHookInstallers implements RuntimeSelfHookComponents.Installer,RuntimeSelfHookManagers.Installer {
    private final RuntimeManagedInstaller managed;
    RuntimeSelfHookInstallers(RuntimeManagedInstaller managed){this.managed=java.util.Objects.requireNonNull(managed);}
    public void install(Map<Method,RuntimeSelfHookComponents.Callback> callbacks)throws Exception {
        Map<Method,RuntimeManagedInstaller.Callback> batch=new LinkedHashMap<>();
        callbacks.forEach((method,callback)->batch.put(method,(receiver,args,original)->callback.invoke(receiver,args,original::call)));
        managed.install(batch);
    }
    public void install(RuntimeSelfHookManagerPolicy.Family family,Map<Method,RuntimeSelfHookManagerPolicy.Callback> callbacks)throws Exception {
        if(!callbacks.keySet().equals(new HashSet<>(FixedManagerInventory.methods(family.ordinal()))))
            throw new IllegalArgumentException("FIXED_MANAGER_INSTALLATION_INCOMPLETE:"+family);
        Map<Method,RuntimeManagedInstaller.Callback> batch=new LinkedHashMap<>();
        callbacks.forEach((method,callback)->batch.put(method,(receiver,args,original)->callback.invoke(receiver,args,original::call)));
        managed.install(batch);
    }
    int installedCount(){return managed.installedCount();}
}
