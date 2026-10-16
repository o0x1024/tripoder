package dev.tripoder.runtime;

import java.util.IdentityHashMap;

/** Java owns interface identity; borrowed adapter wrappers never retain client references. */
final class RuntimeProviderHandles {
    private final IdentityHashMap<Object,RuntimeProviderFacade> clients=new IdentityHashMap<>();
    private Object settings;
    synchronized void settings(Object handle){
        if(handle==null||settings!=null)throw new IllegalStateException("PROVIDER_SETTINGS_HANDLE_INVALID");
        settings=handle;
    }
    synchronized void remember(Object handle,RuntimeProviderFacade provider){
        if(handle==null||provider==null||handle==settings||clients.containsKey(handle))throw new SecurityException("PROVIDER_HANDLE_DUPLICATE");
        clients.put(handle,provider);
    }
    void release(Object handle){
        RuntimeProviderFacade provider;
        synchronized(this){
            if(handle!=null&&handle==settings)return;
            provider=clients.remove(handle);
            if(provider==null)throw new SecurityException("PROVIDER_HANDLE_UNKNOWN");
        }
        provider.close();
    }
}
