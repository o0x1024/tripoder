package dev.tripoder.p2;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Owned concurrent Java stacks remain live across the controller's activation RPC. */
final class HookStackChurn {
    private static volatile boolean running;
    private static CountDownLatch entered,done;
    private static final AtomicLong iterations=new AtomicLong(),checksum=new AtomicLong();
    static synchronized JSONObject start()throws Exception{
        if(running)throw new IllegalStateException("CHURN_ALREADY_ACTIVE");
        iterations.set(0);checksum.set(0);entered=new CountDownLatch(4);done=new CountDownLatch(4);running=true;
        for(int index=0;index<4;index++){final int worker=index;new Thread(()->{
            entered.countDown();long deadline=android.os.SystemClock.elapsedRealtime()+30000;
            try{while(running&&android.os.SystemClock.elapsedRealtime()<deadline){
                checksum.addAndGet(stack(8,worker));long n=iterations.incrementAndGet();
                if(worker==0&&(n&1023)==0)System.gc();
            }}finally{done.countDown();}
        },"owned-deopt-stack-"+index).start();}
        if(!entered.await(5,TimeUnit.SECONDS))throw new IllegalStateException("CHURN_START_TIMEOUT");
        return new JSONObject().put("threads",4).put("running",running).put("iterations",iterations.get());
    }
    private static long stack(int depth,int seed){byte[] bytes=new byte[128+depth];bytes[depth]=(byte)(seed+depth);return depth==0?bytes[0]:stack(depth-1,seed)+bytes[depth];}
    static synchronized JSONObject stop()throws Exception{
        boolean alive=done!=null&&done.getCount()==4;running=false;
        if(done==null||!done.await(5,TimeUnit.SECONDS))throw new IllegalStateException("CHURN_STOP_TIMEOUT");
        return new JSONObject().put("allAliveBeforeStop",alive).put("allStopped",done.getCount()==0).put("iterations",iterations.get()).put("checksum",checksum.get());
    }
    private HookStackChurn(){}
}
