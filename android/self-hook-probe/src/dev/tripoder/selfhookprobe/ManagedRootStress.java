package dev.tripoder.selfhookprobe;

import android.os.Debug;
import dev.tripoder.selfhook.OriginalMethodStorage;
import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Owned bounded-live-set allocator concurrent with paused root observations. */
final class ManagedRootStress {
    static JSONObject run() throws Exception {
        AtomicBoolean stop=new AtomicBoolean();AtomicLong batches=new AtomicLong();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        long gcBefore=Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));
        long adjustmentsBefore=new JSONObject(OriginalMethodStorage.report()).getLong("readBarrierAdjustedReads");
        Thread allocator=new Thread(()->{
            try{while(!stop.get()){
                byte[][] pressure=new byte[8][];
                for(int i=0;i<pressure.length;i++)pressure[i]=new byte[256*1024];
                Reference.reachabilityFence(pressure);batches.incrementAndGet();
                Thread.sleep(1);
            }}catch(Throwable error){failure.set(error);}
        },"owned-concurrent-root-gc");
        int observations=0;allocator.start();
        try{
            for(int i=0;i<400;i++){
                JSONObject roots=new JSONObject(OriginalMethodStorage.report());
                ProbeFiles.require(roots.getInt("published")==19&&roots.getInt("rootedOutsideDeclaredTables")==19,
                    "CONCURRENT_GC_ROOT_RELATION");
                observations++;Thread.sleep(1);
            }
        }finally{stop.set(true);allocator.join(10000);}
        ProbeFiles.require(!allocator.isAlive()&&failure.get()==null,"CONCURRENT_GC_ALLOCATOR_EXIT");
        long gcAfter=Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));
        long adjustmentsAfter=new JSONObject(OriginalMethodStorage.report()).getLong("readBarrierAdjustedReads");
        ProbeFiles.require(batches.get()>0&&gcAfter>gcBefore,"CONCURRENT_GC_MUST_OCCUR");
        return new JSONObject().put("observations",observations).put("publishedPerObservation",19)
            .put("allocationBatches",batches.get()).put("liveBatchBytes",8*256*1024).put("gcBefore",gcBefore).put("gcAfter",gcAfter)
            .put("readBarrierAdjustments",adjustmentsAfter-adjustmentsBefore).put("workerJoined",true);
    }
}
