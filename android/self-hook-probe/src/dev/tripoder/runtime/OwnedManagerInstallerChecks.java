package dev.tripoder.runtime;

import android.os.BatteryManager;
import dev.tripoder.selfhook.FixedManagerInventory;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.ManagerFrameworkAccess;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Installs the real fixed boot BatteryManager inventory, without Binder I/O. */
public final class OwnedManagerInstallerChecks {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    public static JSONObject run(boolean all)throws Exception {
        try{new RuntimeManagedInstaller(java.util.Map.of());throw new IllegalStateException("INSTALLER_ACCEPTED_NO_PROCESS_BOUNDARY");}
        catch(IllegalStateException expected){check("EXPLICIT_PROCESS_INITIALIZATION_REQUIRED".equals(expected.getMessage()),"EXPLICIT_PROCESS_BOUNDARY");}
        ManagedHook.initializeProcess();FrameworkAccess.prepare();ManagerFrameworkAccess.prepare();
        var inventory=FixedManagerInventory.inspect();check(inventory.size()==10,"TEN_COMPLETE_FIXED_FAMILIES");
        FixedManagerInventory.Family family=inventory.stream().filter(item->item.owner==BatteryManager.class).findFirst().orElseThrow();
        check(family.instanceMethods.size()==8,"BATTERY_ALL_EIGHT_INSTANCE_METHODS");
        BatteryManager owned=ManagerFrameworkAccess.battery(),unowned=ManagerFrameworkAccess.battery();
        RuntimeManagedInstaller installer=new RuntimeManagedInstaller(java.util.Map.of());int before=ManagedHook.remainingCapacity();
        AtomicInteger callbacks=new AtomicInteger(),originals=new AtomicInteger();
        Map<Method,RuntimeManagedInstaller.Callback> batch=new LinkedHashMap<>();
        for(Method method:family.instanceMethods)batch.put(method,(receiver,args,original)->{
            if(receiver!=owned){originals.incrementAndGet();return original.call(args);}
            callbacks.incrementAndGet();
            return switch(method.getName()){
                case "isCharging"->true;
                case "computeChargeTimeRemaining"->1234567890123L;
                case "getIntProperty"->700+(Integer)args[0];
                case "getLongProperty"->9007199254740993L+(Integer)args[0];
                default->throw new UnsupportedOperationException("OWNED_BATTERY_DENIED:"+method.getName());
            };
        });
        installer.install(batch);check(installer.installedCount()==8&&before-ManagedHook.remainingCapacity()==8,"REAL_EIGHT_ENTRIES_INSTALLED");
        long installationNs=0;
        if(all){
            long start=android.os.SystemClock.elapsedRealtimeNanos();
            Map<Method,RuntimeManagedInstaller.Callback> others=new LinkedHashMap<>();
            for(var other:inventory)if(other!=family)for(Method method:other.instanceMethods)
                others.put(method,(receiver,args,original)->original.call(args));
            check(others.size()==1515,"FULL_OTHER_MANAGER_INVENTORY");installer.install(others);
            installationNs=android.os.SystemClock.elapsedRealtimeNanos()-start;
            check(installer.installedCount()==1523&&before-ManagedHook.remainingCapacity()==1523,"ALL_TEN_MANAGER_ENTRIES_INSTALLED");
        }
        check(owned.isCharging(),"BOOT_BOOLEAN_CALLBACK");
        check(owned.computeChargeTimeRemaining()==1234567890123L,"BOOT_LONG_CALLBACK");
        check(owned.getIntProperty(17)==717,"BOOT_INT_ARGUMENT");
        check(owned.getLongProperty(19)==9007199254741012L,"BOOT_EXACT_LONG_ARGUMENT");
        check(callbacks.get()==4,"CALLBACK_ONCE_PER_PUBLIC_CALL");
        try{unowned.isCharging();throw new IllegalStateException("UNOWNED_NULL_SERVICE_DID_NOT_THROW");}
        catch(NullPointerException expected){check(originals.get()==1,"UNOWNED_ORIGINAL_THROWABLE_ONCE");}
        Method denied=family.instanceMethods.stream().filter(method->method.getName().equals("queryProperty")).findFirst().orElseThrow();denied.setAccessible(true);
        try{denied.invoke(owned,5);throw new IllegalStateException("PRIVATE_UNSUPPORTED_NOT_INTERCEPTED");}
        catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof UnsupportedOperationException,"PRIVATE_UNSUPPORTED_ENTRY_DENIED");}
        try{installer.install(batch);throw new IllegalStateException("DUPLICATE_INSTALL_ACCEPTED");}
        catch(IllegalArgumentException expected){check(expected.getMessage().startsWith("FIXED_INSTALLER_TARGET_UNAVAILABLE:"),"DUPLICATE_INSTALL_REJECTED_BEFORE_MUTATION");}
        int installed=all?1523:8;
        check(installer.installedCount()==installed&&before-ManagedHook.remainingCapacity()==installed,"DUPLICATE_REJECTION_NO_PARTIAL_SIDE_EFFECT");
        return new JSONObject().put("checks",checks).put("installed",installed).put("callbacks",callbacks.get()).put("unownedOriginals",originals.get())
            .put("otherInstallationNs",installationNs).put("lastPrepareNs",installer.prepareNs()).put("lastPublishNs",installer.publishNs())
            .put("allManagers",all).put("actualProductInstaller",true).put("bootClassTargets",true).put("systemBinderCalls",0).put("productAccepted",false);
    }
}
