package dev.tripoder.runtime;

/** Provider acquisition cleanup; the existing handle registry retains all ownership authority. */
final class RuntimeSelfHookProviderCalls {
    interface Route {
        boolean owns(Object resolver);
        Object settings();
        Object acquire(String authority,boolean stable,boolean existing)throws Throwable;
        Object handle(Object provider)throws Throwable;
        void remember(Object handle,Object provider)throws Throwable;
        void close(Object provider)throws Throwable;
        void release(Object handle)throws Throwable;
    }
    static Object acquire(Route route,Object resolver,String authority,boolean stable,boolean existing,
                          RuntimeSelfHookLifecycle.Original original,Object... originalArguments)throws Throwable {
        if(!route.owns(resolver))return original.call(originalArguments);
        if("settings".equals(authority))return requiredHandle(route,route.settings());
        Object provider=route.acquire(authority,stable,existing);
        if(provider==null)return null;
        try {
            Object handle=requiredHandle(route,provider);route.remember(handle,provider);return handle;
        } catch(Throwable error) {
            try{route.close(provider);}catch(Throwable cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}
            throw error;
        }
    }
    static Object release(Route route,Object resolver,Object handle,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(resolver))return original.call(handle);
        route.release(handle);return true;
    }
    private static Object requiredHandle(Route route,Object provider)throws Throwable {
        if(provider==null)throw new IllegalStateException("PROVIDER_PLATFORM_HANDLE_MISSING");
        Object result=route.handle(provider);
        if(result==null)throw new IllegalStateException("PROVIDER_PLATFORM_HANDLE_MISSING");
        return result;
    }
    private RuntimeSelfHookProviderCalls(){}
}
