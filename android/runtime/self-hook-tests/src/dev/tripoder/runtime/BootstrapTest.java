package dev.tripoder.runtime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BootstrapTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String message)throws Exception {
        try{action.run();throw new AssertionError("accepted "+message);}
        catch(IllegalStateException error){check(error.getMessage().contains(message));}
    }
    private static EnumMap<RuntimeSelfHookBootstrap.Capability,RuntimeSelfHookBootstrap.Installer> plan(
        RuntimeSelfHookBootstrap.Installer action) {
        var plan=new EnumMap<RuntimeSelfHookBootstrap.Capability,RuntimeSelfHookBootstrap.Installer>(RuntimeSelfHookBootstrap.Capability.class);
        for(var item:RuntimeSelfHookBootstrap.Capability.values())plan.put(item,action);
        return plan;
    }
    public static void main(String[] args)throws Exception {
        int size=RuntimeSelfHookBootstrap.Capability.values().length;
        check(RuntimeSelfHookBootstrap.Capability.values()[0]==RuntimeSelfHookBootstrap.Capability.PROCESS_EXECUTION_POLICY);
        for(var absent:RuntimeSelfHookBootstrap.Capability.values()) {
            var gate=new RuntimeSelfHookBootstrap();var calls=new AtomicInteger();var entries=plan(()->calls.incrementAndGet());
            entries.remove(absent);
            rejects(()->gate.install(entries,()->calls.incrementAndGet()),"INSTALLER_MISSING:"+absent);
            check(calls.get()==0);check(gate.status().startsWith("FAILED:0/"));
            rejects(()->gate.install(plan(()->calls.incrementAndGet()),()->calls.incrementAndGet()),"ALREADY_ATTEMPTED");
            check(calls.get()==0);
        }
        for(var failing:RuntimeSelfHookBootstrap.Capability.values()) {
            var gate=new RuntimeSelfHookBootstrap();var calls=new AtomicInteger();var ready=new AtomicInteger();
            var entries=plan(()->calls.incrementAndGet());
            RuntimeException original=new RuntimeException("fixture");
            entries.put(failing,()->{throw original;});
            try{gate.install(entries,()->ready.incrementAndGet());throw new AssertionError("failure lost");}
            catch(RuntimeException error){check(error==original);}
            check(calls.get()==failing.ordinal());check(ready.get()==0);
            rejects(()->gate.install(entries,()->ready.incrementAndGet()),"ALREADY_ATTEMPTED");
        }
        var gate=new RuntimeSelfHookBootstrap();var order=new ArrayList<RuntimeSelfHookBootstrap.Capability>();
        var entries=plan(()->{});AtomicInteger publication=new AtomicInteger();
        for(var item:RuntimeSelfHookBootstrap.Capability.values())entries.put(item,()->{
            rejects(gate::requireComplete,"NOT_COMPLETE");
            rejects(()->gate.install(entries,()->{}),"ALREADY_ATTEMPTED");
            order.add(item);
        });
        gate.install(entries,()->{gate.requireComplete();check(order.size()==size);publication.incrementAndGet();});
        check(order.equals(List.of(RuntimeSelfHookBootstrap.Capability.values())));check(publication.get()==1);
        rejects(gate::requireComplete,"NOT_COMPLETE:READY");
        rejects(()->gate.install(entries,()->{}),"ALREADY_ATTEMPTED:READY");

        var errorGate=new RuntimeSelfHookBootstrap();Error fatal=new AssertionError("fatal");
        try{errorGate.install(plan(()->{throw fatal;}),()->{});throw new AssertionError("error lost");}
        catch(Error error){check(error==fatal);}
        rejects(()->errorGate.install(plan(()->{}),()->{}),"ALREADY_ATTEMPTED:FAILED");
        var publishFailure=new RuntimeSelfHookBootstrap();
        try{publishFailure.install(plan(()->{}),()->{throw fatal;});throw new AssertionError("publication error lost");}
        catch(Error error){check(error==fatal);}
        rejects(publishFailure::requireComplete,"NOT_COMPLETE:FAILED");
        rejects(()->publishFailure.install(plan(()->{}),()->{}),"ALREADY_ATTEMPTED:FAILED");

        // Install callbacks run without the gate monitor: other threads can inspect/reject promptly.
        var concurrent=new RuntimeSelfHookBootstrap();var workerError=new AtomicReference<Throwable>();
        var seen=new CountDownLatch(1);
        Thread observer=new Thread(()->{try{
            check(concurrent.status().startsWith("INSTALLING:"));
            rejects(concurrent::requireComplete,"NOT_COMPLETE");
            rejects(()->concurrent.install(plan(()->{}),()->{}),"ALREADY_ATTEMPTED");
        }catch(Throwable error){workerError.set(error);}finally{seen.countDown();}});
        var concurrentPlan=plan(()->{});
        concurrentPlan.put(RuntimeSelfHookBootstrap.Capability.FRAMEWORK_ACCESS,()->{
            observer.start();check(seen.await(5,TimeUnit.SECONDS));
            if(workerError.get()!=null)throw new AssertionError(workerError.get());
        });
        concurrent.install(concurrentPlan,concurrent::requireComplete);observer.join();

        var wrongThread=new RuntimeSelfHookBootstrap();var denied=new AtomicReference<Throwable>();
        wrongThread.install(plan(()->{}),()->{
            Thread thread=new Thread(()->{try{wrongThread.requireComplete();}catch(Throwable error){denied.set(error);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException error){throw new AssertionError(error);}
            check(!thread.isAlive());check(denied.get() instanceof IllegalStateException);wrongThread.requireComplete();
        });
        var mutableGate=new RuntimeSelfHookBootstrap();var mutable=plan(()->{});var ran=new AtomicInteger();
        mutable.put(RuntimeSelfHookBootstrap.Capability.FRAMEWORK_ACCESS,mutable::clear);
        mutable.put(RuntimeSelfHookBootstrap.Capability.HOOK_CONTROL,()->ran.incrementAndGet());
        mutableGate.install(mutable,mutableGate::requireComplete);check(ran.get()==1);
        System.out.println("bootstrap checks="+checks);
    }
}
