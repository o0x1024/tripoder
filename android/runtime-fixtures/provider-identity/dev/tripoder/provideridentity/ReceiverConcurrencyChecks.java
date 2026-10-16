package dev.tripoder.provideridentity;

import android.app.ActivityManager;
import android.content.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Concurrent real registration and callback dispatch must not invert adapter/Java locks. */
final class ReceiverConcurrencyChecks {
    static JSONObject run(Context context)throws Exception{
        String action=context.getPackageName()+".CONCURRENT_RECEIVER";AtomicInteger received=new AtomicInteger();CountDownLatch delivered=new CountDownLatch(1),start=new CountDownLatch(1);
        BroadcastReceiver stable=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){received.incrementAndGet();delivered.countDown();}};
        context.registerReceiver(stable,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{
            Callable<long[]> work=()->{start.await();long begin=System.nanoTime();for(int i=0;i<20;i++){
                BroadcastReceiver temporary=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){}};
                context.registerReceiver(temporary,new IntentFilter(action+".TEMP"),Context.RECEIVER_NOT_EXPORTED);
                try{context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()));if(context.getSystemService(ActivityManager.class).getMemoryClass()<=0)throw new AssertionError("MEMORY_CAPABILITY_MISSING");}
                finally{context.unregisterReceiver(temporary);}
            }return new long[]{begin,System.nanoTime()};};
            Future<long[]> left=threads.submit(work),right=threads.submit(work);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);start.countDown();
            long[] a=left.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS),b=right.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            boolean passed=delivered.await(5,TimeUnit.SECONDS)&&received.get()>0&&a[0]<b[1]&&b[0]<a[1];
            return new JSONObject().put("passed",passed).put("received",received.get()).put("leftStart",a[0]).put("leftEnd",a[1]).put("rightStart",b[0]).put("rightEnd",b[1]);
        }finally{threads.shutdownNow();context.unregisterReceiver(stable);}
    }
    private ReceiverConcurrencyChecks(){}
}
