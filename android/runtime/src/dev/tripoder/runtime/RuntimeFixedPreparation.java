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
    static void neutralizeVendorMonitors(){
        try{
            // The platform's per-Looper MessageMonitor is intrinsic process state: the vendor Looper dereferences it on
            // every dispatch. Nulling it (or disabling its ENABLED switch) leaves the dispatcher inconsistent and
            // aborts the app on the next message, so instrumentation is never disabled by field surgery here.
            try{
                Class<?> su=Class.forName("miui.mqsas.scout.ScoutUtils");
                for(String fieldName: new String[]{"ENABLED_SCOUT", "ENABLED_LT_SCOUT", "ENABLED_CEHCK_FROZEN"}){
                    try{
                        Field f=su.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        f.setBoolean(null,false);
                    }catch(Throwable ignored){}
                }
            }catch(Throwable ignored){}
            try{
                Class<?> ati=Class.forName("android.app.ActivityThreadImpl");
                try{
                    Field sdp=ati.getDeclaredField("ScoutDisablePackageList");
                    sdp.setAccessible(true);
                    sdp.set(null,new java.util.AbstractList<String>(){
                        @Override public String get(int index){return "";}
                        @Override public int size(){return 1;}
                        @Override public boolean contains(Object o){return true;}
                    });
                }catch(Throwable ignored){}
                try{
                    Field anr=ati.getDeclaredField("ENABLE_ANR_CLUE");
                    anr.setAccessible(true);
                    anr.setBoolean(null,false);
                }catch(Throwable ignored){}
                try{
                    Field sm=ati.getDeclaredField("mAppScoutStateMachine");
                    sm.setAccessible(true);
                    Object stateMachine=sm.get(null);
                    if(stateMachine!=null){
                        try{
                            Method quitNow=stateMachine.getClass().getMethod("quitNow");
                            quitNow.invoke(stateMachine);
                        }catch(Throwable ignored){}
                    }
                    sm.set(null,null);
                }catch(Throwable ignored){}
                try{
                    Field st=ati.getDeclaredField("mAppScoutThread");
                    st.setAccessible(true);
                    st.set(null,null);
                }catch(Throwable ignored){}
            }catch(Throwable ignored){}
            try{
                ThreadGroup rootGroup=Thread.currentThread().getThreadGroup();
                while(rootGroup.getParent()!=null)rootGroup=rootGroup.getParent();
                Thread[] threads=new Thread[rootGroup.activeCount()+16];
                int count=rootGroup.enumerate(threads,true);
                for(int i=0;i<count;i++){
                    Thread t=threads[i];if(t==null)continue;
                    String n=t.getName();
                    if(n!=null&&(n.contains("MiuiMonitor")||n.contains("Scout"))){
                        if(t instanceof android.os.HandlerThread ht)ht.quit();
                        else t.interrupt();
                    }
                }
            }catch(Throwable ignored){}
        }catch(Throwable ignored){}
    }
    static synchronized void process()throws Exception{
        if(!processPrepared){
            neutralizeVendorMonitors();
            framework();List<dev.tripoder.selfhook.dex.AbiSignature> signatures=new ArrayList<>();
            for(Executable target:targets().keySet())signatures.add(new dev.tripoder.selfhook.dex.AbiSignature(target));
            dev.tripoder.selfhook.dex.HelperDex.prepareTemplates(signatures);
            ManagedHook.initializeProcess();processPrepared=true;
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
        process();reservations=ManagedHook.prepareBatch(targets());reserved=true;
    }
    private static Map<Executable,Executable[]> targets()throws Exception{
        Map<Executable,Executable[]> targets=new LinkedHashMap<>();
        for(var family:RuntimeSelfHookManagerPolicy.Family.values())for(Method method:FixedManagerInventory.methods(family.ordinal()))targets.put(method,new Executable[0]);
        for(var group:FixedComponentHooks.Group.values())for(Method method:FixedComponentHooks.methods(group))targets.put(method,new Executable[0]);
        for(var group:FixedRuntimeHooks.Group.values())for(Executable method:FixedRuntimeHooks.methods(group))targets.put(method,new Executable[0]);
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
