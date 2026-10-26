package dev.tripoder.runtime;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Product callback bodies. Only a coverage-validating installer may bind them to real methods. */
final class RuntimeSelfHookComponents {
    @FunctionalInterface interface Callback {
        Object invoke(Object receiver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable;
    }
    interface Installer {
        /** Publish every exact entry before exposing the component to Android.
         * Guest lifecycle dispatch uses that entry; arbitrary old inlined local
         * callers are not a component-delivery boundary. Failure is fatal. */
        void install(Map<Method,Callback> callbacks)throws Exception;
    }

    private static volatile RuntimeSelfHookComponents current;
    private static boolean attempted;
    private final RuntimeEngine engine;
    private final RuntimeSelfHookFrameworkCalls framework;
    private final Installer installer;
    private final Map<Method,Callback> installed=new LinkedHashMap<>();
    private volatile Throwable failure;
    private boolean installing,settingsAttempted;
    private volatile boolean settingsRegistered;

    static synchronized void prepare(RuntimeEngine engine,RuntimeSelfHookFrameworkCalls framework,Installer installer) {
        if(attempted)throw new IllegalStateException("SELF_COMPONENT_PREPARE_ALREADY_ATTEMPTED");
        attempted=true;
        if(engine==null||engine!=RuntimeEngine.current)throw new SecurityException("SELF_COMPONENT_ENGINE_REQUIRED");
        current=new RuntimeSelfHookComponents(engine,Objects.requireNonNull(framework),Objects.requireNonNull(installer));
    }
    private RuntimeSelfHookComponents(RuntimeEngine engine,RuntimeSelfHookFrameworkCalls framework,Installer installer) {
        this.engine=engine;this.framework=framework;this.installer=installer;
    }
    private static RuntimeSelfHookComponents require() {
        RuntimeSelfHookComponents value=current;
        if(value==null)throw new IllegalStateException("SELF_COMPONENT_ADAPTER_REQUIRED");
        value.check();return value;
    }
    static void requireActive(){require();}
    static void installFixed(Map<Method,Callback> callbacks){require().install(callbacks);}
    static Object serviceLifecycle(Method target,Object receiver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        return RuntimeSelfHookLifecycle.service(require().services,target,receiver,arguments,original);
    }
    static Object receiverLifecycle(Method target,Object receiver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        return RuntimeSelfHookLifecycle.receiver(require().receivers,target,receiver,arguments,original);
    }
    static RuntimeSelfHookFrameworkCalls frameworkCalls(){return require().framework;}
    private void check() {
        if(engine!=RuntimeEngine.current||failure!=null)throw new IllegalStateException("SELF_COMPONENT_ADAPTER_UNAVAILABLE",failure);
    }

    private final RuntimeSelfHookLifecycle.ServiceRoute services=new RuntimeSelfHookLifecycle.ServiceRoute() {
        public boolean owns(Object service){check();return RuntimeBridge.ownsService((Service)service);}
        public Object intent(Object service,Object physical){return RuntimeBridge.serviceIntent((Service)service,(Intent)physical);}
        public void completed(Object service,String event)throws Exception{RuntimeBridge.serviceEvent((Service)service,event);}
    };
    private final RuntimeSelfHookLifecycle.ReceiverRoute receivers=new RuntimeSelfHookLifecycle.ReceiverRoute() {
        public boolean owns(Object receiver){check();return RuntimeBridge.ownsReceiver((BroadcastReceiver)receiver);}
        public Object pending(Object receiver){return framework.receiverPendingResult((BroadcastReceiver)receiver);}
        public boolean platformContext(Object context){return engine.receivers.platformContext((Context)context);}
        public Object before(Object receiver,Object intent,Object pending){return RuntimeBridge.receiverBefore((BroadcastReceiver)receiver,(Intent)intent,pending);}
        public Object context(Object original){return RuntimeBridge.context((Context)original);}
        public void after(Object receiver,boolean success){RuntimeBridge.receiverAfter((BroadcastReceiver)receiver,success);}
    };
    private final RuntimeSelfHookProviderCalls.Route providers=new RuntimeSelfHookProviderCalls.Route() {
        public boolean owns(Object resolver){check();boolean owned=RuntimeBridge.ownsResolver((ContentResolver)resolver);
            if(owned&&!settingsRegistered)throw new IllegalStateException("SELF_SETTINGS_HANDLE_NOT_REGISTERED");return owned;}
        public Object settings(){return RuntimeBridge.provider();}
        public Object acquire(String authority,boolean stable,boolean existing){return RuntimeBridge.acquireProvider(authority,stable,existing);}
        public Object handle(Object provider){return framework.providerHandle((ContentProvider)provider);}
        public void remember(Object handle,Object provider){RuntimeBridge.rememberProviderHandle(handle,(ContentProvider)provider);}
        public void close(Object provider){RuntimeBridge.releaseProvider((ContentProvider)provider);}
        public void release(Object handle){RuntimeBridge.releaseProviderHandle(handle);}
    };

    static void installService(Service service) {
        RuntimeSelfHookComponents value=require();value.ownedType(service);
        String[] names={"onCreate","onDestroy","onBind","onUnbind","onRebind","onStartCommand"};
        Class<?>[][] types={{},{},{Intent.class},{Intent.class},{Intent.class},{Intent.class,int.class,int.class}};
        Map<Method,Callback> callbacks=new LinkedHashMap<>();
        for(int i=0;i<names.length;i++) {
            Method target=publicLifecycle(service.getClass(),names[i],types[i]);
            if(target==null)continue;
            callbacks.put(target,(receiver,arguments,original)->RuntimeSelfHookLifecycle.service(value.services,target,receiver,arguments,original));
        }
        value.install(callbacks);
    }
    static void installReceiver(BroadcastReceiver receiver) {
        RuntimeSelfHookComponents value=require();
        if(receiver==null||!value.engine.ownsReceiverType(receiver.getClass()))throw new SecurityException("SELF_COMPONENT_LOADER_MISMATCH");
        Method target=publicLifecycle(receiver.getClass(),"onReceive",Context.class,Intent.class);
        if(target==null){
            if(!dev.tripoder.selfhook.SystemReceiverAccess.ownsType(receiver.getClass()))throw new SecurityException("SELF_RECEIVER_FIXED_CALLBACK_REQUIRED");
            return;
        }
        value.install(Map.of(target,(instance,arguments,original)->RuntimeSelfHookLifecycle.receiver(value.receivers,target,instance,arguments,original)));
    }
    private void ownedType(Object component) {
        if(component==null||!engine.ownsLoader(component.getClass().getClassLoader()))throw new SecurityException("SELF_COMPONENT_LOADER_MISMATCH");
    }
    /**
     * Boot lifecycle entries belong to the fixed pre-guest adapters, including
     * IntentService's queueing and teardown overrides. This dynamic boundary
     * only installs guest or guest-library implementations.
     */
    private static Method publicLifecycle(Class<?> type,String name,Class<?>... parameters) {
        try {
            Method method=type.getMethod(name,parameters);
            if(method.getDeclaringClass().getClassLoader()==Class.class.getClassLoader())return null;
            if(Modifier.isStatic(method.getModifiers())||Modifier.isAbstract(method.getModifiers()))
                throw new IllegalStateException("SELF_COMPONENT_LIFECYCLE_NOT_CONCRETE:"+method);
            return method;
        } catch(NoSuchMethodException error){throw new IllegalStateException("SELF_COMPONENT_LIFECYCLE_MISSING:"+name,error);}
    }
    private synchronized void install(Map<Method,Callback> requested) {
        check();
        if(installing)throw new IllegalStateException("SELF_COMPONENT_INSTALL_REENTRY");
        Map<Method,Callback> additions=new LinkedHashMap<>();
        requested.forEach((method,callback)->{if(!installed.containsKey(method))additions.put(method,callback);});
        if(additions.isEmpty())return;
        installing=true;
        try {
            installer.install(java.util.Collections.unmodifiableMap(additions));
            installed.putAll(additions);
        } catch(Exception|Error error){failure=error;throw new IllegalStateException("SELF_COMPONENT_INSTALL_FAILED",error);}
        finally{installing=false;}
    }

    static boolean protectedBroadcast(String action){return require().framework.protectedBroadcast(action);}
    static long bindServiceFlags(Context.BindServiceFlags flags){return require().framework.bindServiceFlags(Objects.requireNonNull(flags));}
    static int[] selectionKeys(ContentProviderOperation operation,int index) {
        if(operation==null||operation.getClass()!=ContentProviderOperation.class)throw new SecurityException("PROVIDER_BATCH_OPERATION_CLASS_INVALID");
        RuntimeSelfHookSelection selection=require().framework.selectionArguments(operation);
        if(selection==null)throw new IllegalStateException("PROVIDER_BATCH_SNAPSHOT_REQUIRED");
        return selection.keys(index);
    }
    static void registerSettingsHandle() {
        RuntimeSelfHookComponents value=require();
        synchronized(value) {
            if(value.settingsAttempted)throw new IllegalStateException("SELF_SETTINGS_ALREADY_ATTEMPTED");
            value.settingsAttempted=true;
            try {
                Object handle=Objects.requireNonNull(value.framework.providerHandle(RuntimeBridge.provider()),"PROVIDER_SETTINGS_HANDLE_REQUIRED");
                RuntimeBridge.settingsProviderHandle(handle);
                dev.tripoder.selfhook.SettingsNamespaceAccess.publish(handle);
                if(value.engine.traceStartup)android.util.Log.i("TripoderSettingsNamespace",new org.json.JSONObject(dev.tripoder.selfhook.SettingsNamespaceAccess.check()).toString());
                value.settingsRegistered=true;
            }catch(RuntimeException|Error error){value.failure=error;throw error;}
        }
    }
    static Object acquireProvider(ContentResolver resolver,Context context,String authority,boolean stable,boolean existing,
                                  RuntimeSelfHookLifecycle.Original original)throws Throwable {
        return RuntimeSelfHookProviderCalls.acquire(require().providers,resolver,authority,stable,existing,original,context,authority);
    }
    static Object releaseProvider(ContentResolver resolver,Object handle,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        return RuntimeSelfHookProviderCalls.release(require().providers,resolver,handle,original);
    }
    static Object goAsync(BroadcastReceiver receiver,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        require();Object result=original.call();
        if(result!=null&&RuntimeBridge.ownsReceiver(receiver))RuntimeBridge.receiverAsync(receiver,result);
        return result;
    }
    static Object finishPending(Object pending,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        require();Object result=original.call();RuntimeBridge.receiverFinished(pending);return result;
    }
}
