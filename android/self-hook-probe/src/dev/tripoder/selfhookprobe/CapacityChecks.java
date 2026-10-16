package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.ManagedHook;
import dev.tripoder.selfhook.OriginalMethodStorage;
import dev.tripoder.selfhook.dex.HookCapacity;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Full-budget owned targets, including the writer's final slot and GC roots. */
public final class CapacityChecks {
    private static final AtomicInteger bodies=new AtomicInteger();
    private CapacityChecks() {}
    public static void body(){bodies.incrementAndGet();}
    public static int overflow(int n){return n;}
    public static JSONObject run() throws Exception {
        if(HookCapacity.MAX_ENTRIES!=4096)throw new IllegalStateException("CAPACITY_FIXTURE_REGENERATION_REQUIRED");
        long started=android.os.SystemClock.elapsedRealtime();ManagedHook.initializeProcess();
        ManagedHook.Handle[] handles=new ManagedHook.Handle[HookCapacity.MAX_ENTRIES];Method[] targets=new Method[handles.length];
        int enabled=0;JSONArray checkpoints=new JSONArray();
        try {
            Map<Executable,Executable[]> requested=new LinkedHashMap<>();
            for(int slot=0;slot<handles.length;slot++) {
                Class<?> owner=Class.forName("dev.tripoder.selfhookprobe.capacity.Group"+(slot/128));
                Method target=owner.getDeclaredMethod("value"+(slot%128),int.class);targets[slot]=target;
                requested.put(target,new Executable[0]);
            }
            var prepared=ManagedHook.prepareBatch(requested);Map<ManagedHook.Handle,ManagedHook.Handler> handlers=new LinkedHashMap<>();
            for(int slot=0;slot<handles.length;slot++){handles[slot]=prepared.get(targets[slot]);handlers.put(handles[slot],call->(Integer)call.original(call.arguments())+10000);}
            ManagedHook.enableBatch(handlers);enabled=handles.length;
            for(int slot=0;slot<handles.length;slot++) {
                Method target=targets[slot];
                ProbeFiles.require((Integer)target.invoke(null,7)==slot+10007,"CAPACITY_ACTIVE_VALUE:"+slot);
                if(slot==63||slot==64||slot==1270||slot==handles.length-1)
                    checkpoints.put(new JSONObject().put("slot",slot).put("state",new JSONObject(handles[slot].report())));
            }
            ProbeFiles.require(ManagedHook.remainingCapacity()==0,"CAPACITY_ZERO_REMAINING");
            boolean rejected=false;
            try{ManagedHook.prepare(CapacityChecks.class.getDeclaredMethod("overflow",int.class),new Method[0]);}
            catch(IllegalArgumentException expected){rejected=expected.getMessage().equals("HOOK_PREPARE_BUDGET");}
            ProbeFiles.require(rejected&&ManagedHook.remainingCapacity()==0,"CAPACITY_OVERFLOW_REJECTION");
            JSONObject beforeGc=new JSONObject(OriginalMethodStorage.report());
            for(int round=0;round<4;round++) {
                byte[][] pressure=new byte[32][];for(int n=0;n<pressure.length;n++)pressure[n]=new byte[65536];System.gc();
            }
            JSONObject afterGc=new JSONObject(OriginalMethodStorage.report());
            ProbeFiles.require(beforeGc.getInt("published")==handles.length&&afterGc.getInt("rootedOutsideDeclaredTables")==handles.length,"CAPACITY_GC_ROOTS");
            // The real ART writer must select entries beyond the former 64-slot limit.
            JSONObject writers=new JSONObject(EntryWriterProbe.exerciseNative(targets[handles.length-1]));
            ProbeFiles.require(writers.getBoolean("passed"),"CAPACITY_LAST_SLOT_WRITER");
            for(int slot=0;slot<handles.length;slot++) {
                ProbeFiles.require((Integer)targets[slot].invoke(null,11)==slot+10011,"CAPACITY_POST_GC_VALUE:"+slot);
                ProbeFiles.require(new JSONObject(handles[slot].report()).getLong("originals")==2,"CAPACITY_ORIGINAL_COUNT:"+slot);
            }
            ProbeFiles.require(bodies.get()==handles.length*2,"CAPACITY_EXACT_BODY_COUNT");
            ManagedHook.disableBatch(Arrays.asList(handles));enabled=0;
            for(int slot=handles.length-1;slot>=0;slot--) {
                ProbeFiles.require((Integer)targets[slot].invoke(null,3)==slot+3,"CAPACITY_DISABLED_VALUE:"+slot);
            }
            ProbeFiles.require(ManagedHook.remainingCapacity()==0,"CAPACITY_PROCESS_LEASE_RETAINED");
            return new JSONObject().put("entries",handles.length).put("declaringClasses",32).put("enabledCalls",handles.length*2)
                .put("disabledCalls",handles.length).put("bodyCalls",bodies.get()).put("overflowRejected",rejected)
                .put("remainingAfterDisable",ManagedHook.remainingCapacity()).put("checkpoints",checkpoints)
                .put("beforeGc",beforeGc).put("afterGc",afterGc).put("writerEvents",writers)
                .put("batchHelpers",true).put("batchPublication",true)
                .put("durationMs",android.os.SystemClock.elapsedRealtime()-started).put("productAccepted",false);
        } finally {
            if(enabled!=0)ManagedHook.disableBatch(Arrays.asList(handles));
        }
    }
}
