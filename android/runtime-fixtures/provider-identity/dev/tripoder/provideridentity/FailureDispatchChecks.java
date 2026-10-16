package dev.tripoder.provideridentity;

import java.util.concurrent.*;
import org.json.*;

/** An app-owned uncaught handler must still receive the exact Throwable and thread. */
final class FailureDispatchChecks {
    static void inspect(JSONArray checks)throws Exception{
        RuntimeException original=new RuntimeException("owned-failure-dispatch-contract");CountDownLatch handled=new CountDownLatch(1);Throwable[] received=new Throwable[1];Thread[] caller=new Thread[1];
        Thread worker=new Thread(()->{throw original;},"owned-uncaught-handler");worker.setUncaughtExceptionHandler((thread,error)->{caller[0]=thread;received[0]=error;handled.countDown();});worker.start();
        boolean signaled=handled.await(5,TimeUnit.SECONDS);worker.join(5000);boolean passed=signaled&&!worker.isAlive()&&received[0]==original&&caller[0]==worker;
        checks.put(new JSONObject().put("name","uncaught-observation-preserves-app-handler-and-throwable").put("passed",passed));if(!passed)throw new AssertionError("uncaught-dispatch");
        Thread.UncaughtExceptionHandler saved=Thread.getDefaultUncaughtExceptionHandler();Throwable[] direct=new Throwable[1];Thread[] directThread=new Thread[1];RuntimeException groupError=new RuntimeException("owned-direct-group-contract");Thread source=Thread.currentThread();
        try{
            Thread.setDefaultUncaughtExceptionHandler((thread,error)->{directThread[0]=thread;direct[0]=error;});
            source.getThreadGroup().uncaughtException(source,groupError);
        }finally{Thread.setDefaultUncaughtExceptionHandler(saved);}
        boolean preserved=direct[0]==groupError&&directThread[0]==source&&Thread.getDefaultUncaughtExceptionHandler()==saved;
        checks.put(new JSONObject().put("name","direct-thread-group-observation-preserves-handler-and-throwable").put("passed",preserved));if(!preserved)throw new AssertionError("direct-thread-group-dispatch");
    }
    private FailureDispatchChecks(){}
}
