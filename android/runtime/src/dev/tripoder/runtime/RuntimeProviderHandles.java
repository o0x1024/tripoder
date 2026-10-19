package dev.tripoder.runtime;

import java.util.IdentityHashMap;

/** Java owns interface identity; borrowed adapter wrappers never retain client references. */
final class RuntimeProviderHandles {
    private static final class Ref {
        final android.content.ContentProvider provider;
        int count = 1;
        Ref(android.content.ContentProvider provider){this.provider=provider;}
    }
    private final IdentityHashMap<Object,Ref> clients=new IdentityHashMap<>();
    private Object settings;
    synchronized void settings(Object handle){
        if(handle==null||settings!=null)throw new IllegalStateException("PROVIDER_SETTINGS_HANDLE_INVALID");
        settings=handle;
    }
    synchronized void remember(Object handle,android.content.ContentProvider provider){
        if(handle==null||provider==null||handle==settings)throw new SecurityException("PROVIDER_HANDLE_DUPLICATE");
        Ref existing=clients.get(handle);
        if(existing!=null){
            if(existing.provider!=provider)throw new SecurityException("PROVIDER_HANDLE_DUPLICATE");
            existing.count++;
            return;
        }
        clients.put(handle,new Ref(provider));
    }
    void release(Object handle){
        android.content.ContentProvider provider=null;
        boolean close=false;
        synchronized(this){
            if(handle!=null&&handle==settings)return;
            Ref ref=clients.get(handle);
            if(ref==null)throw new SecurityException("PROVIDER_HANDLE_UNKNOWN");
            ref.count--;
            if(ref.count<=0){
                clients.remove(handle);
                provider=ref.provider;
                close=true;
            }
        }
        if(close&&provider instanceof RuntimeProviderFacade)((RuntimeProviderFacade)provider).close();
    }
}
