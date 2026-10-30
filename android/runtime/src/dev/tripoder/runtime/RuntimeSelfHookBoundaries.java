package dev.tripoder.runtime;

import java.util.function.Predicate;

/** Framework callback ordering. Routing/identity/observer state stays in existing Runtime owners. */
final class RuntimeSelfHookBoundaries {
    interface ServiceRoute {
        boolean owns(Object service);
        Object context(Object physical);
        Object application();
        boolean stop(Object service,int startId)throws Throwable;
    }
    interface ProviderRoute {
        boolean owns(Object provider);
        boolean scoped(Object provider);
        Object context(Object provider,Object supplied,Object declaration,boolean testing);
        Object callingPackage(Object provider);
        Object attribution(Object provider);
        void cleared(Object provider,Object token);
        void restoring(Object provider,Object token);
    }
    enum ProviderIdentity { PACKAGE, PACKAGE_UNCHECKED, ATTRIBUTION_TAG, ATTRIBUTION_SOURCE }
    interface ObserverRoute {
        boolean owns(Object resolver);
        int currentUser();
        void register(Object uri,boolean descendants,Object observer,int user);
        void unregister(Object observer);
    }

    static Object serviceAttach(ServiceRoute route,Object service,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(service))return original.call(arguments);
        if(arguments.length!=6)throw new IllegalArgumentException("SERVICE_ATTACH_ARGUMENT_SHAPE");
        Object[] logical=arguments.clone();logical[0]=route.context(arguments[0]);logical[4]=route.application();
        return original.call(logical);
    }
    static Object stopService(ServiceRoute route,Object service,int startId,boolean result,Object[] arguments,
                              RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(service))return original.call(arguments);
        boolean stopped=false;
        try{
            stopped=route.stop(service,startId);
        }catch(Throwable error){
            android.util.Log.w("TripoderService","Service stop failed: "+error.getMessage());
        }
        return result?Boolean.valueOf(stopped):null;
    }
    static Object providerAttach(ProviderRoute route,Object provider,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(provider)&&!route.scoped(provider))return original.call(arguments);
        if(arguments.length!=3)throw new IllegalArgumentException("PROVIDER_ATTACH_ARGUMENT_SHAPE");
        Object[] logical=arguments.clone();
        logical[0]=route.context(provider,arguments[0],arguments[1],(Boolean)arguments[2]);
        return original.call(logical);
    }
    static Object providerIdentity(ProviderRoute route,Object provider,ProviderIdentity identity,
                                   RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(provider))return original.call();
        switch(identity) {
            case PACKAGE:case PACKAGE_UNCHECKED:return route.callingPackage(provider);
            case ATTRIBUTION_TAG:return null;
            case ATTRIBUTION_SOURCE:return route.attribution(provider);
            default:throw new IllegalArgumentException("PROVIDER_IDENTITY_KIND_UNSUPPORTED");
        }
    }
    static Object clearProviderIdentity(ProviderRoute route,Object provider,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        Object token=original.call();
        if(route.owns(provider))route.cleared(provider,token);
        return token;
    }
    static Object restoreProviderIdentity(ProviderRoute route,Object provider,Object token,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(route.owns(provider))route.restoring(provider,token);
        return original.call(token);
    }
    static Object registerObserver(ObserverRoute route,Object resolver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(resolver))return original.call(arguments);
        if(arguments.length!=3&&arguments.length!=4)throw new UnsupportedOperationException("SETTINGS_OBSERVER_OVERLOAD_UNSUPPORTED");
        int user=arguments.length==4?(Integer)arguments[3]:route.currentUser();
        route.register(arguments[0],(Boolean)arguments[1],arguments[2],user);return null;
    }
    static Object unregisterObserver(ObserverRoute route,Object resolver,Object observer,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(resolver))return original.call(observer);
        route.unregister(observer);return null;
    }
    static Object denyOwned(Predicate<Object> owns,Object receiver,String reason,Object[] arguments,
                            RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(owns.test(receiver))throw new UnsupportedOperationException(reason);
        return original.call(arguments);
    }
    private RuntimeSelfHookBoundaries(){}
}
