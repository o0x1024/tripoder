package dev.tripoder.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One STOP dispatch per immutable generation; only observed death ends the wait. */
final class ProcessStopDispatch {
    private final AtomicBoolean dispatched=new AtomicBoolean();
    final CountDownLatch death=new CountDownLatch(1);
    void request(Executor executor,Runnable call){
        if(dispatched.compareAndSet(false,true))executor.execute(call);
    }
    void observedDead(){death.countDown();}
    void awaitDeath(long deadlineNanos)throws InterruptedException{
        long remaining=Math.max(0,deadlineNanos-System.nanoTime());
        if(!death.await(remaining,TimeUnit.NANOSECONDS))throw new IllegalStateException("PROCESS_STOP_UNCONFIRMED");
    }
}
