package dev.tripoder.closurefixture;

import android.app.job.*;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real sticky system callbacks overlap real scheduler mutations in the authority. */
final class AuthorityConcurrency {
    static void run(Context context)throws Exception{
        CountDownLatch start=new CountDownLatch(1),received=new CountDownLatch(16);
        List<BroadcastReceiver> receivers=new ArrayList<>();
        AtomicReference<Throwable> error=new AtomicReference<>();
        JobScheduler scheduler=context.getSystemService(JobScheduler.class).forNamespace("owned-concurrent-authority");
        Thread registrations=new Thread(()->{
            try{
                start.await();
                for(int i=0;i<16;i++){
                    BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context owner,Intent intent){if(Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))received.countDown();}};
                    context.registerReceiver(receiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED),Context.RECEIVER_NOT_EXPORTED);receivers.add(receiver);
                }
            }catch(Throwable failure){error.compareAndSet(null,failure);}
        },"owned-concurrent-registrations");
        Thread jobs=new Thread(()->{
            try{
                start.await();
                for(int i=0;i<8;i++){
                    android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putInt("revision",i);
                    JobInfo job=new JobInfo.Builder(920,new ComponentName(context,OwnedJob.class)).setMinimumLatency(3600000).setExtras(extras).build();
                    if(scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS)throw new AssertionError("REAL_CONCURRENT_JOB_REJECTED");
                }
            }catch(Throwable failure){error.compareAndSet(null,failure);}
        },"owned-concurrent-job-mutations");
        registrations.start();jobs.start();start.countDown();
        try{
            registrations.join(6000);jobs.join(6000);
            if(registrations.isAlive()||jobs.isAlive())throw new AssertionError("AUTHORITY_CONCURRENT_MUTATIONS_BLOCKED");
            if(error.get()!=null)throw new AssertionError("AUTHORITY_CONCURRENT_MUTATION_FAILED",error.get());
            if(!received.await(6,TimeUnit.SECONDS))throw new AssertionError("REAL_STICKY_CALLBACKS_NOT_COMPLETED");
        }finally{
            if(!registrations.isAlive())for(BroadcastReceiver receiver:receivers)context.unregisterReceiver(receiver);
        }
    }
    private AuthorityConcurrency(){}
}
