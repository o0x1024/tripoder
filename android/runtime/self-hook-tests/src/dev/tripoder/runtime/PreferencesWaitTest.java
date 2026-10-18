package dev.tripoder.runtime;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PreferencesWaitTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError(checks);}
    public static void main(String[] args)throws Exception{
        CountDownLatch readAttempts=new CountDownLatch(2);
        FutureTask<Integer> read=new FutureTask<>(()->42){
            @Override public Integer get()throws InterruptedException,ExecutionException{readAttempts.countDown();return super.get();}
        };
        AtomicInteger observed=new AtomicInteger();AtomicBoolean restored=new AtomicBoolean();
        Thread reader=new Thread(()->{Thread.currentThread().interrupt();observed.set(PreferencesWait.complete(read));restored.set(Thread.currentThread().isInterrupted());});
        reader.start();
        try{
            // Observe the actual interrupted read and its retry, not a racy JVM thread-state sample.
            check(readAttempts.await(3,TimeUnit.SECONDS));reader.interrupt();
            check(!read.isCancelled()&&observed.get()==0);
        }finally{read.run();reader.join(3000);}
        check(!reader.isAlive()&&observed.get()==42&&restored.get());
        AtomicInteger durable=new AtomicInteger();FutureTask<Boolean> write=new FutureTask<>(()->{durable.set(7);return true;});
        Thread.currentThread().interrupt();boolean interrupted=false;try{PreferencesWait.commit(write);}catch(InterruptedException expected){interrupted=true;}check(interrupted);check(!Thread.currentThread().isInterrupted());
        check(!write.isCancelled()&&durable.get()==0);write.run();check(PreferencesWait.commit(write)&&durable.get()==7);
        FutureTask<Boolean> failed=new FutureTask<>(()->{throw new java.io.IOException("owned disk failure");});failed.run();
        boolean rejected=false;try{PreferencesWait.complete(failed);}catch(IllegalStateException expected){rejected=expected.getCause() instanceof java.io.IOException;}
        check(rejected);check(!PreferencesWait.commit(CompletableFuture.completedFuture(false)));
        System.out.println("preferences-wait: "+checks+" passed");
    }
}
