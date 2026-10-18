package dev.tripoder.runtime;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.net.Uri;

/** Typed endpoints for the fixed Framework installer. Declaring these does not install a Hook. */
final class RuntimeSelfHookPlatformCallbacks {
    private static final RuntimeSelfHookBoundaries.ServiceRoute SERVICES=new RuntimeSelfHookBoundaries.ServiceRoute() {
        public boolean owns(Object service){return RuntimeBridge.ownsService((Service)service);}
        public Object context(Object physical){return RuntimeBridge.context((Context)physical);}
        public Object application(){return RuntimeBridge.application();}
        public boolean stop(Object service,int startId)throws Exception {
            Service value=(Service)service;
            return RuntimeSelfHookComponents.frameworkCalls().stopServiceToken(value,RuntimeBridge.serviceComponent(value),startId);
        }
    };
    private static final RuntimeSelfHookBoundaries.ProviderRoute PROVIDERS=new RuntimeSelfHookBoundaries.ProviderRoute() {
        public boolean owns(Object provider){return RuntimeProviderIdentity.owns((ContentProvider)provider);}
        public boolean scoped(Object provider){return RuntimeBridge.providerAttachmentScoped((ContentProvider)provider);}
        public Object context(Object provider,Object context,Object declaration,boolean testing) {
            return RuntimeBridge.providerAttachContext((ContentProvider)provider,(Context)context,(ProviderInfo)declaration,testing);
        }
        public Object callingPackage(Object provider){return RuntimeProviderIdentity.callingPackage((ContentProvider)provider);}
        public Object attribution(Object provider){return RuntimeProviderIdentity.attribution((ContentProvider)provider);}
        public void cleared(Object provider,Object token){RuntimeProviderIdentity.clear((ContentProvider)provider,token);}
        public void restoring(Object provider,Object token){RuntimeProviderIdentity.restore((ContentProvider)provider,token);}
    };
    private static final RuntimeSelfHookBoundaries.ObserverRoute OBSERVERS=new RuntimeSelfHookBoundaries.ObserverRoute() {
        public boolean owns(Object resolver){return RuntimeBridge.ownsResolver((ContentResolver)resolver);}
        public int currentUser(){return android.os.Process.myUserHandle().hashCode();}
        public void register(Object uri,boolean descendants,Object observer,int user) {
            RuntimeBridge.registerSettingsObserver((Uri)uri,descendants,(ContentObserver)observer,user);
        }
        public void unregister(Object observer){RuntimeBridge.unregisterSettingsObserver((ContentObserver)observer);}
    };

    static Object serviceAttach(Service service,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.serviceAttach(SERVICES,service,arguments,original);
    }
    static Object stopSelf(Service service,int startId,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookJobs.rejectServiceControl(service);
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.stopService(SERVICES,service,startId,false,new Object[]{startId},original);
    }
    static Object stopSelf(Service service,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookJobs.rejectServiceControl(service);
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.stopService(SERVICES,service,-1,false,new Object[0],original);
    }
    static Object stopSelfResult(Service service,int startId,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookJobs.rejectServiceControl(service);
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.stopService(SERVICES,service,startId,true,new Object[]{startId},original);
    }
    /** A guest service cannot hold the host's foreground slot, but the user-visible contract of startForeground is its ongoing notification: that is posted through the guest's own notification facade. */
    static Object foreground(Service service,String name,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookJobs.rejectServiceControl(service);
        RuntimeSelfHookComponents.requireActive();
        if(!RuntimeBridge.ownsService(service))return original.call(arguments);
        return RuntimeBridge.serviceForeground(service,name,arguments);
    }
    static Object providerAttach(ContentProvider provider,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.providerAttach(PROVIDERS,provider,arguments,original);
    }
    static Object providerIdentity(ContentProvider provider,RuntimeSelfHookBoundaries.ProviderIdentity identity,
                                   RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.providerIdentity(PROVIDERS,provider,identity,original);
    }
    static Object clearProviderIdentity(ContentProvider provider,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.clearProviderIdentity(PROVIDERS,provider,original);
    }
    static Object restoreProviderIdentity(ContentProvider provider,Object token,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.restoreProviderIdentity(PROVIDERS,provider,token,original);
    }
    static Object registerObserver(ContentResolver resolver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.registerObserver(OBSERVERS,resolver,arguments,original);
    }
    static Object unregisterObserver(ContentResolver resolver,ContentObserver observer,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.unregisterObserver(OBSERVERS,resolver,observer,original);
    }
    static Object persistablePermission(ContentResolver resolver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.denyOwned(value->RuntimeBridge.ownsResolver((ContentResolver)value),
            resolver,"URI_PERSISTABLE_GRANT_NOT_IMPLEMENTED",arguments,original);
    }
    static Object notifyChange(ContentResolver resolver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.denyOwned(value->RuntimeBridge.ownsResolver((ContentResolver)value),
            resolver,"PROVIDER_OBSERVER_NOT_IMPLEMENTED",arguments,original);
    }
    static Object peekService(BroadcastReceiver receiver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeSelfHookComponents.requireActive();return RuntimeSelfHookBoundaries.denyOwned(value->RuntimeBridge.ownsReceiver((BroadcastReceiver)value),
            receiver,"RECEIVER_PEEK_SERVICE_NOT_IMPLEMENTED",arguments,original);
    }
    private RuntimeSelfHookPlatformCallbacks(){}
}
