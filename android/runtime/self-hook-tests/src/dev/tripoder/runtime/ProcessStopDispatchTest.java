package dev.tripoder.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProcessStopDispatchTest {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("stop check "+checks);checks++;}
    private static void unconfirmed(ProcessStopDispatch stop,long milliseconds)throws Exception{
        long begin=System.nanoTime();
        try{stop.awaitDeath(begin+TimeUnit.MILLISECONDS.toNanos(milliseconds));throw new AssertionError("death without evidence");}
        catch(IllegalStateException expected){check("PROCESS_STOP_UNCONFIRMED".equals(expected.getMessage()));}
        check(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(2));
    }
    public static void main(String[] args)throws Exception{
        ExecutorService rpc=Executors.newFixedThreadPool(2),callers=Executors.newFixedThreadPool(8);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),ack=new CountDownLatch(1);
        AtomicInteger sent=new AtomicInteger();ProcessStopDispatch stop=new ProcessStopDispatch();
        Runnable frozen=()->{sent.incrementAndGet();entered.countDown();try{release.await();}catch(InterruptedException error){throw new AssertionError(error);}ack.countDown();};
        try{
            for(int i=0;i<64;i++)callers.submit(()->stop.request(rpc,frozen));
            check(entered.await(2,TimeUnit.SECONDS));callers.shutdown();check(callers.awaitTermination(2,TimeUnit.SECONDS));check(sent.get()==1);
            unconfirmed(stop,40); // The RPC is still blocked; its cancellation is never assumed.
            stop.request(rpc,()->{throw new AssertionError("STOP replayed after timeout");});check(sent.get()==1);
            release.countDown();check(ack.await(2,TimeUnit.SECONDS));
            unconfirmed(stop,40); // A late ACK is not a death observation.
            stop.observedDead();stop.awaitDeath(System.nanoTime());check(stop.death.getCount()==0);
            ProcessStopDispatch next=new ProcessStopDispatch();unconfirmed(next,20);check(next.death.getCount()==1); // Old death cannot release a new generation.
            AtomicInteger batch=new AtomicInteger();ProcessStopDispatch[] workers=new ProcessStopDispatch[8];
            for(int i=0;i<workers.length;i++){workers[i]=new ProcessStopDispatch();ProcessStopDispatch worker=workers[i];worker.request(rpc,()->{batch.incrementAndGet();worker.observedDead();});}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);for(ProcessStopDispatch worker:workers)worker.awaitDeath(deadline);
            check(batch.get()==8); // No waiting observer occupies the RPC pool.
            System.out.println("Process stop dispatch: "+checks+" passed");
        }finally{release.countDown();callers.shutdownNow();rpc.shutdownNow();}
    }
}
