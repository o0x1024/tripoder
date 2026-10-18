package dev.tripoder.runtime;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Waiting is separate from the disk operation: interrupting a caller never cancels a write. */
final class PreferencesWait {
    static <T>T complete(Future<T> future){
        boolean interrupted=false;
        try{
            for(;;)try{return future.get();}
            catch(InterruptedException signal){interrupted=true;}
            catch(ExecutionException failure){throw new IllegalStateException("PREFERENCES_WRITE_FAILED",failure.getCause());}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    static boolean commit(Future<Boolean> future)throws InterruptedException{
        // Like Android's commit latch, an interrupted waiter cannot report a
        // confirmed commit, even when the independent disk writer completes.
        if(Thread.interrupted())throw new InterruptedException();
        try{return future.get();}
        catch(ExecutionException failure){throw new IllegalStateException("PREFERENCES_WRITE_FAILED",failure.getCause());}
    }
    private PreferencesWait(){}
}
