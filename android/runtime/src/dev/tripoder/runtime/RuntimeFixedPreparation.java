package dev.tripoder.runtime;

import dev.tripoder.selfhook.*;
import java.lang.reflect.*;
import java.util.*;

/** Pure boot metadata and helper bytes precede deoptimization; guest code and
 * all managed entry publication remain behind the explicit process policy. */
final class RuntimeFixedPreparation {
    private static boolean processPrepared,frameworkPrepared,reserved;
    private static FrameworkAccess.Members members;
    private static Map<Executable,ManagedHook.Handle> reservations=Map.of();
    static synchronized void process()throws Exception{
        if(!processPrepared){
            framework();List<dev.tripoder.selfhook.dex.AbiSignature> signatures=new ArrayList<>();
            for(Executable target:targets().keySet())signatures.add(new dev.tripoder.selfhook.dex.AbiSignature(target));
            dev.tripoder.selfhook.dex.HelperDex.prepareTemplates(signatures);
            ManagedHook.initializeProcessForBootstrap(RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN);processPrepared=true;
        }else ManagedHook.requireProcessInitialization();
    }
    static synchronized FrameworkAccess.Members framework()throws Exception{
        if(!frameworkPrepared){
            members=FrameworkAccess.prepare();ServiceTokenAccess.prepare();ComponentFrameworkAccess.prepare();ManagerFrameworkAccess.prepare();
            ActivityFrameworkAccess.prepare();PackageFrameworkAccess.prepare();FixedComponentHooks.prepare();FixedRuntimeHooks.prepare();FixedManagerInventory.inspect();JobFrameworkAccess.prepare();PendingIntentFrameworkAccess.prepare();ExitInfoFrameworkAccess.prepare();
            frameworkPrepared=true;
        }return members;
    }
    static synchronized void prewarm()throws Exception{
        if(reserved||RuntimeEngine.current!=null)return;
        RuntimePhysicalManifest.capture(java.util.Objects.requireNonNull(RuntimeApplication.host,"JOB_PHYSICAL_HOST_REQUIRED"));
        // An idle slot has no guest mode yet. Preparing ART here used to put
        // every future thin guest under the thick process interpreter policy.
        framework();List<dev.tripoder.selfhook.dex.AbiSignature> signatures=new ArrayList<>();
        for(Executable target:targets().keySet())signatures.add(new dev.tripoder.selfhook.dex.AbiSignature(target));
        dev.tripoder.selfhook.dex.HelperDex.prepareTemplates(signatures);
    }
    private static Map<Executable,Executable[]> targets()throws Exception{
        Map<Executable,Executable[]> targets=new LinkedHashMap<>();
        for(var family:RuntimeSelfHookManagerPolicy.Family.values())if(!RuntimeThinPolicy.active()||!RuntimeThinPolicy.platformManager(family))for(Method method:FixedManagerInventory.methods(family.ordinal()))targets.put(method,new Executable[0]);
        for(var group:FixedComponentHooks.Group.values())for(Method method:FixedComponentHooks.methods(group))targets.put(method,new Executable[0]);
        for(var group:FixedRuntimeHooks.Group.values())if(!RuntimeThinPolicy.active()||!RuntimeThinPolicy.platformHooks(group))for(Executable method:FixedRuntimeHooks.methods(group)){
            if(!RuntimeThinPolicy.active()&&group==FixedRuntimeHooks.Group.PACKAGES&&RuntimeFixedPassthrough.packageMethod(method.getDeclaringClass().getName(),method.getName(),method.getParameterCount()))continue;
            if(group==FixedRuntimeHooks.Group.TRAFFIC&&RuntimeFixedPassthrough.trafficMethod(RuntimeSelfHookManagerPolicy.signature((Method)method)))continue;
            targets.put(method,new Executable[0]);
        }
        for(Method method:JobFrameworkAccess.hooks())targets.put(method,new Executable[0]);
        for(Method method:PendingIntentFrameworkAccess.hooks())targets.put(method,new Executable[0]);
        targets.put((Method)members.methods[1],new Executable[0]);
        targets.put(FrameworkAccess.activityThreadApplicationMethod(),new Executable[0]);
        targets.put(FrameworkAccess.processNameMethod(),new Executable[0]);
        targets.put((Method)members.methods[FrameworkAccess.CURRENT_PACKAGE_NAME],new Executable[0]);
        return targets;
    }
    static synchronized Map<Executable,ManagedHook.Handle> take()throws Exception{
        if(!reserved&&reservations.isEmpty()){
            process();reservations=ManagedHook.prepareBatch(targets());reserved=true;
        }
        var value=reservations;reservations=Map.of();return new LinkedHashMap<>(value);
    }
    private RuntimeFixedPreparation(){}
}
