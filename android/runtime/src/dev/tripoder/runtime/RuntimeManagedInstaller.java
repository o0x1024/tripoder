package dev.tripoder.runtime;

import android.os.Process;
import dev.tripoder.selfhook.ManagedHook;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Permanent product adapter bindings. The process owner must establish the
 * startup boundary before constructing this installer. Partial installation is
 * never reported as success and cannot resume guest code. */
final class RuntimeManagedInstaller {
    @FunctionalInterface interface Original {Object call(Object... arguments)throws Throwable;}
    @FunctionalInterface interface Callback {Object invoke(Object receiver,Object[] arguments,Original original)throws Throwable;}
    private final Map<Executable,ManagedHook.Handle> installed=new LinkedHashMap<>();
    private final Map<Executable,ManagedHook.Handle> reserved;
    private long lastPrepareNs,lastPublishNs;
    private boolean installing,failed;
    RuntimeManagedInstaller(Map<Executable,ManagedHook.Handle> reservations){
        ManagedHook.requireProcessInitialization();
        reserved=new LinkedHashMap<>(Objects.requireNonNull(reservations));
    }
    synchronized void install(Map<? extends Executable,Callback> supplied)throws Exception {
        if(failed||installing)throw new IllegalStateException("FIXED_INSTALLER_UNAVAILABLE");
        ManagedHook.requireProcessInitialization();
        Map<Executable,Callback> callbacks=new LinkedHashMap<>(Objects.requireNonNull(supplied));
        if(callbacks.isEmpty())throw new IllegalArgumentException("FIXED_INSTALLER_EMPTY_BATCH");
        for(var item:callbacks.entrySet()){
            Executable method=Objects.requireNonNull(item.getKey());Objects.requireNonNull(item.getValue());
            if(installed.containsKey(method)||(ManagedHook.ownsTarget(method)&&!reserved.containsKey(method))||Modifier.isAbstract(method.getModifiers()))
                throw new IllegalArgumentException("FIXED_INSTALLER_TARGET_UNAVAILABLE:"+method);
        }
        if(callbacks.keySet().stream().filter(m->!reserved.containsKey(m)).count()>ManagedHook.remainingCapacity())throw new IllegalStateException("FIXED_INSTALLER_CAPACITY");
        installing=true;boolean touched=false;
        try {
            Map<Executable,Executable[]> targets=new LinkedHashMap<>();Map<Executable,ManagedHook.Handle> prepared=new LinkedHashMap<>();for(Executable method:callbacks.keySet()){if(reserved.containsKey(method))prepared.put(method,reserved.remove(method));else targets.put(method,new Executable[0]);}
            long start=android.os.SystemClock.elapsedRealtimeNanos();touched=true;
            if(!targets.isEmpty())prepared.putAll(ManagedHook.prepareBatch(targets));
            lastPrepareNs=android.os.SystemClock.elapsedRealtimeNanos()-start;start=android.os.SystemClock.elapsedRealtimeNanos();
            Map<ManagedHook.Handle,ManagedHook.Handler> handlers=new LinkedHashMap<>();
            prepared.forEach((target,handle)->{Callback callback=callbacks.get(target);handlers.put(handle,call->callback.invoke(call.receiver(),call.arguments(),call::original));});
            ManagedHook.enableBatch(handlers);
            installed.putAll(prepared);
            lastPublishNs=android.os.SystemClock.elapsedRealtimeNanos()-start;
        }catch(Exception|Error error){
            failed=true;
            if(touched){android.util.Log.e("TripoderHook","FIXED_INSTALLER_PARTIAL_PUBLICATION",error);Process.killProcess(Process.myPid());System.exit(74);}
            throw error;
        }finally{installing=false;}
    }
    synchronized void requirePreparedConsumed(){if(!reserved.isEmpty())throw new IllegalStateException("FIXED_PREPARED_BINDINGS_UNUSED:"+reserved.size());}
    synchronized int installedCount(){if(failed)throw new IllegalStateException("FIXED_INSTALLER_FAILED");return installed.size();}
    synchronized long prepareNs(){return lastPrepareNs;}
    synchronized long publishNs(){return lastPublishNs;}
}
