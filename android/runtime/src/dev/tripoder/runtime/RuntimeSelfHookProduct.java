package dev.tripoder.runtime;

import dev.tripoder.selfhook.FixedManagerInventory;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.Method;
import java.util.EnumMap;
import static dev.tripoder.runtime.RuntimeSelfHookBootstrap.Capability.*;

/** Complete product plan, before any guest factory, initializer or constructor.
 * Existing Runtime owners retain every business ledger and authorization. */
final class RuntimeSelfHookProduct {
    private static volatile int installedBindings;
    static int installedBindings(){return installedBindings;}
    private RuntimeManagedInstaller managed;
    private RuntimeSelfHookInstallers adapters;
    private RuntimeSelfHookProduct(RuntimeEngine engine){
        if(engine==null||engine!=RuntimeEngine.current)throw new SecurityException("SELF_HOOK_PRODUCT_ENGINE_REQUIRED");
    }
    static void install(RuntimeEngine engine)throws Exception{
        RuntimeSelfHookProduct value=new RuntimeSelfHookProduct(engine);
        EnumMap<RuntimeSelfHookBootstrap.Capability,RuntimeSelfHookBootstrap.Installer> plan=new EnumMap<>(RuntimeSelfHookBootstrap.Capability.class);
        plan.put(PROCESS_EXECUTION_POLICY,()->{
            RuntimeFixedPreparation.process();value.managed=new RuntimeManagedInstaller(RuntimeFixedPreparation.take(),true);value.adapters=new RuntimeSelfHookInstallers(value.managed);
        });
        plan.put(FRAMEWORK_ACCESS,()->{
            RuntimeSelfHookApplication.prepareFramework(engine,RuntimeSelfHookPhysical.INSTANCE);
            RuntimeSelfHookComponents.prepare(engine,RuntimeSelfHookFixedFramework.INSTANCE,value.adapters);
            RuntimeSelfHookManagers.prepare(engine,RuntimeSelfHookFixedFramework.INSTANCE);
        });
        plan.put(APPLICATION_OWNERSHIP,RuntimeSelfHookApplication::prepareOwnership);
        plan.put(REFLECTION_VIEW,()->RuntimeSelfHookApplication.prepareView(value.managed));
        plan.put(ACTIVITY_CREATION,()->RuntimeSelfHookActivities.creation(value.managed));
        plan.put(ACTIVITY_DELIVERY,()->RuntimeSelfHookActivities.delivery(value.managed));
        plan.put(ACTIVITY_ROUTING,()->RuntimeSelfHookActivities.routing(value.managed));
        plan.put(SERVICES,()->{RuntimeSelfHookFixedComponents.services();RuntimeSelfHookRawServices.install(value.managed);});
        plan.put(PROVIDERS,()->{RuntimeSelfHookFixedComponents.providers();RuntimeSelfHookProviderInstallation.install(value.managed);});
        plan.put(RECEIVERS,()->{RuntimeSelfHookFixedComponents.receivers();RuntimeSelfHookRawReceivers.install(value.managed);});
        plan.put(WIDGETS,RuntimeSelfHookFixedComponents::widgets);
        plan.put(PACKAGES,()->{RuntimeSelfHookPackages.install(value.managed);RuntimeSelfHookResourceRegistration.install(value.managed);});
        plan.put(STORAGE,RuntimeSelfHookStorage::install);
        plan.put(ACTIVITY_MANAGER,()->value.manager(RuntimeSelfHookManagerPolicy.Family.ACTIVITY_MANAGER));
        plan.put(CONNECTIVITY,()->value.manager(RuntimeSelfHookManagerPolicy.Family.CONNECTIVITY));
        plan.put(WIFI_P2P,()->value.manager(RuntimeSelfHookManagerPolicy.Family.WIFI_P2P));
        plan.put(NETWORK_STATS,()->value.manager(RuntimeSelfHookManagerPolicy.Family.NETWORK_STATS));
        plan.put(TELEPHONY,()->value.manager(RuntimeSelfHookManagerPolicy.Family.TELEPHONY));
        plan.put(AUDIO,()->value.manager(RuntimeSelfHookManagerPolicy.Family.AUDIO));
        plan.put(BATTERY,()->value.manager(RuntimeSelfHookManagerPolicy.Family.BATTERY));
        plan.put(POWER,()->value.manager(RuntimeSelfHookManagerPolicy.Family.POWER));
        plan.put(NOTIFICATIONS,()->value.manager(RuntimeSelfHookManagerPolicy.Family.NOTIFICATIONS));
        plan.put(SHORTCUTS,()->value.manager(RuntimeSelfHookManagerPolicy.Family.SHORTCUTS));
        plan.put(LOCATION,()->value.manager(RuntimeSelfHookManagerPolicy.Family.LOCATION));
        plan.put(JOBS,()->{value.manager(RuntimeSelfHookManagerPolicy.Family.JOB_SCHEDULER);RuntimeSelfHookJobs.install(value.managed);});
        plan.put(PENDING_INTENTS,()->RuntimePendingIntents.install(value.managed));
        plan.put(UI_EFFECTS,()->{RuntimeSelfHookSystemEdges.autofill(value.managed);});
        plan.put(FAILURE_DIAGNOSTICS,()->{});
        plan.put(NATIVE_LIBRARY_RUNTIME,engine.hooks::installNativeRuntime);
        EnumMap<RuntimeSelfHookBootstrap.Capability,RuntimeSelfHookBootstrap.Installer> measured=new EnumMap<>(RuntimeSelfHookBootstrap.Capability.class);
        plan.forEach((capability,installer)->measured.put(capability,()->{
            long start=android.os.SystemClock.elapsedRealtimeNanos();
            android.util.Log.i("TripoderSelfHook","INSTALLING:"+capability);
            installer.install();
            android.util.Log.i("TripoderSelfHook","INSTALLED:"+capability+":ns="+(android.os.SystemClock.elapsedRealtimeNanos()-start));
        }));
        long start=android.os.SystemClock.elapsedRealtimeNanos();
        RuntimeSelfHookBootstrap.PROCESS.install(measured,()->{value.managed.requirePreparedConsumed();value.managed.publishPrepared();ManagedHook.completeBootstrap();RuntimeBridge.selfHookReady();});
        installedBindings=value.managed.installedCount();
        android.util.Log.i("TripoderSelfHook","READY:"+RuntimeSelfHookBootstrap.PROCESS.status()+":bindings="+value.managed.installedCount()+":ns="+(android.os.SystemClock.elapsedRealtimeNanos()-start));
        android.util.Log.i("TripoderSelfHook","FIXED_PUBLICATION:bindings="+installedBindings+":ns="+value.managed.publishNs());
        android.util.Log.i("TripoderSelfHook","PREPARATION_METRICS:"+dev.tripoder.selfhook.OriginalMethodStorage.preparationMetrics());
        android.util.Log.i("TripoderSelfHook","MANAGED_PREPARATION_METRICS:"+ManagedHook.preparationMetrics());
        android.util.Log.i("TripoderSelfHook","EXECUTION_POLICY:"+ManagedHook.processPolicy());
    }
    private void manager(RuntimeSelfHookManagerPolicy.Family family)throws Exception{
        if(RuntimePlatformPolicy.platformManager(family))return;
        RuntimeSelfHookManagers.install(family,FixedManagerInventory.methods(family.ordinal()).toArray(new Method[0]),adapters);
    }
}
