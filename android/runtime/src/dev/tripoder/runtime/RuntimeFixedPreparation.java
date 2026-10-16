package dev.tripoder.runtime;

import dev.tripoder.selfhook.*;
import java.lang.reflect.*;
import java.util.*;

/** Identity-free preparation may run during physical JobService binding (18 s), before start ACK (8 s). */
final class RuntimeFixedPreparation {
    private static boolean processPrepared,frameworkPrepared,reserved;
    private static FrameworkAccess.Members members;
    private static Map<Executable,ManagedHook.Handle> reservations=Map.of();
    static synchronized void process(){if(!processPrepared){ManagedHook.initializeProcess();processPrepared=true;}else ManagedHook.requireProcessInitialization();}
    static synchronized FrameworkAccess.Members framework()throws Exception{
        if(!frameworkPrepared){
            members=FrameworkAccess.prepare();ServiceTokenAccess.prepare();ComponentFrameworkAccess.prepare();ManagerFrameworkAccess.prepare();
            ActivityFrameworkAccess.prepare();PackageFrameworkAccess.prepare();FixedComponentHooks.prepare();FixedRuntimeHooks.prepare();FixedManagerInventory.inspect();JobFrameworkAccess.prepare();
            frameworkPrepared=true;
        }return members;
    }
    static synchronized void prewarm()throws Exception{
        if(reserved||RuntimeEngine.current!=null)return;
        RuntimePhysicalManifest.capture(java.util.Objects.requireNonNull(RuntimeApplication.host,"JOB_PHYSICAL_HOST_REQUIRED"));
        process();framework();Map<Executable,Executable[]> targets=new LinkedHashMap<>();
        for(var family:RuntimeSelfHookManagerPolicy.Family.values())for(Method method:FixedManagerInventory.methods(family.ordinal()))targets.put(method,new Executable[0]);
        for(var group:FixedComponentHooks.Group.values())for(Method method:FixedComponentHooks.methods(group))targets.put(method,new Executable[0]);
        for(var group:FixedRuntimeHooks.Group.values())for(Executable method:FixedRuntimeHooks.methods(group))targets.put(method,new Executable[0]);
        for(Method method:JobFrameworkAccess.hooks())targets.put(method,new Executable[0]);
        targets.put((Method)members.methods[1],new Executable[0]);targets.put(FrameworkAccess.processNameMethod(),new Executable[0]);
        reservations=ManagedHook.prepareBatch(targets);reserved=true;
    }
    static synchronized Map<Executable,ManagedHook.Handle> take(){var value=reservations;reservations=Map.of();return new LinkedHashMap<>(value);}
    private RuntimeFixedPreparation(){}
}
