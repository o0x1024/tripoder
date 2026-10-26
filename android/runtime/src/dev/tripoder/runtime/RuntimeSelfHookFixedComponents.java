package dev.tripoder.runtime;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.database.ContentObserver;
import dev.tripoder.selfhook.FixedComponentHooks;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
/** Real component installation batches. The existing component owner records
 * these exact bindings, including inherited boot Service lifecycles. */
final class RuntimeSelfHookFixedComponents {
    private RuntimeSelfHookFixedComponents(){}
    static void services(){
        Map<Method,RuntimeSelfHookComponents.Callback> callbacks=new LinkedHashMap<>();
        for(Method method:FixedComponentHooks.methods(FixedComponentHooks.Group.SERVICES)){
            RuntimeSelfHookComponents.Callback callback;
            switch(method.getName()){
                case "onCreate":case "onDestroy":case "onBind":case "onRebind":case "onUnbind":case "onStartCommand":
                    callback=(receiver,args,original)->RuntimeSelfHookComponents.serviceLifecycle(method,receiver,args,original);break;
                case "attach":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.serviceAttach((Service)receiver,args,original);break;
                case "stopSelf":callback=method.getParameterCount()==0
                    ?(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.stopSelf((Service)receiver,original)
                    :(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.stopSelf((Service)receiver,(Integer)args[0],original);break;
                case "stopSelfResult":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.stopSelfResult((Service)receiver,(Integer)args[0],original);break;
                case "startForeground":case "stopForeground":case "getForegroundServiceType":
                    callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.foreground((Service)receiver,method.getName(),args,original);break;
                default:throw new IllegalStateException("FIXED_SERVICE_BINDING_UNHANDLED:"+method);
            }
            callbacks.put(method,callback);
        }
        RuntimeSelfHookComponents.installFixed(callbacks);
    }
    static void providers()throws Exception{
        Map<Method,RuntimeSelfHookComponents.Callback> callbacks=new LinkedHashMap<>();
        for(Method method:FixedComponentHooks.methods(FixedComponentHooks.Group.PROVIDERS)){
            RuntimeSelfHookComponents.Callback callback;
            switch(method.getName()){
                case "attachInfo":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.providerAttach((ContentProvider)receiver,args,original);break;
                case "getCallingPackage":callback=identity(RuntimeSelfHookBoundaries.ProviderIdentity.PACKAGE);break;
                case "getCallingPackageUnchecked":callback=identity(RuntimeSelfHookBoundaries.ProviderIdentity.PACKAGE_UNCHECKED);break;
                case "getCallingAttributionTag":callback=identity(RuntimeSelfHookBoundaries.ProviderIdentity.ATTRIBUTION_TAG);break;
                case "getCallingAttributionSource":callback=identity(RuntimeSelfHookBoundaries.ProviderIdentity.ATTRIBUTION_SOURCE);break;
                case "clearCallingIdentity":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.clearProviderIdentity((ContentProvider)receiver,original);break;
                case "restoreCallingIdentity":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.restoreProviderIdentity((ContentProvider)receiver,args[0],original);break;
                case "acquireProvider":case "acquireUnstableProvider":case "acquireExistingProvider":
                    callback=(receiver,args,original)->RuntimeSelfHookComponents.acquireProvider((ContentResolver)receiver,(android.content.Context)args[0],(String)args[1],
                        !method.getName().equals("acquireUnstableProvider"),method.getName().equals("acquireExistingProvider"),original);break;
                case "releaseProvider":case "releaseUnstableProvider":callback=(receiver,args,original)->RuntimeSelfHookComponents.releaseProvider((ContentResolver)receiver,args[0],original);break;
                case "registerContentObserver":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.registerObserver((ContentResolver)receiver,args,original);break;
                case "unregisterContentObserver":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.unregisterObserver((ContentResolver)receiver,(ContentObserver)args[0],original);break;
                case "notifyChange":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.notifyChange((ContentResolver)receiver,args,original);break;
                case "takePersistableUriPermission":case "releasePersistableUriPermission":case "getPersistedUriPermissions":case "getOutgoingPersistedUriPermissions":
                    callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.persistablePermission((ContentResolver)receiver,method.getName(),args,original);break;
                default:throw new IllegalStateException("FIXED_PROVIDER_BINDING_UNHANDLED:"+method);
            }
            callbacks.put(method,callback);
        }
        // Creating the settings wrapper after the exact boundary set has been
        // prepared must produce the same pinned implementation. No lookup by
        // runtime class name and no replacement resolver backend.
        if(RuntimeBridge.resolver().getClass()!=Class.forName("android.content.ContentResolver$1",false,null))
            throw new IllegalStateException("FIXED_WRAPPED_RESOLVER_IDENTITY_CHANGED");
        RuntimeSelfHookComponents.installFixed(callbacks);
        RuntimeSelfHookComponents.registerSettingsHandle();
    }
    private static RuntimeSelfHookComponents.Callback identity(RuntimeSelfHookBoundaries.ProviderIdentity kind){
        return (receiver,args,original)->RuntimeSelfHookPlatformCallbacks.providerIdentity((ContentProvider)receiver,kind,original);
    }
    static void receivers(){
        Map<Method,RuntimeSelfHookComponents.Callback> callbacks=new LinkedHashMap<>();
        for(Method method:FixedComponentHooks.methods(FixedComponentHooks.Group.RECEIVERS)){
            RuntimeSelfHookComponents.Callback callback;
            switch(method.getName()){
                case "onReceive":callback=(receiver,args,original)->RuntimeSelfHookComponents.receiverLifecycle(method,receiver,args,original);break;
                case "goAsync":callback=(receiver,args,original)->RuntimeSelfHookComponents.goAsync((BroadcastReceiver)receiver,original);break;
                case "finish":callback=(receiver,args,original)->RuntimeSelfHookComponents.finishPending(receiver,original);break;
                case "peekService":callback=(receiver,args,original)->RuntimeSelfHookPlatformCallbacks.peekService((BroadcastReceiver)receiver,args,original);break;
                default:throw new IllegalStateException("FIXED_RECEIVER_BINDING_UNHANDLED:"+method);
            }
            callbacks.put(method,callback);
        }
        RuntimeSelfHookComponents.installFixed(callbacks);
    }
    /** Guest widget providers have no host in this virtual device: queries answer empty, writes are no-ops. */
    static void widgets(){
        Map<Method,RuntimeSelfHookComponents.Callback> callbacks=new LinkedHashMap<>();
        for(Method method:FixedComponentHooks.methods(FixedComponentHooks.Group.WIDGETS))
            callbacks.put(method,(receiver,args,original)->RuntimeSelfHookWidgets.dispatch(method,args,original));
        RuntimeSelfHookComponents.installFixed(callbacks);
    }
}
