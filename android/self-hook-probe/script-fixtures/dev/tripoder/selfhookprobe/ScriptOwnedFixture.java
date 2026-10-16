package dev.tripoder.selfhookprobe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned probe bodies. Never referenced by product target selection. */
public final class ScriptOwnedFixture {
    public static final AtomicInteger intCalls=new AtomicInteger(),longCalls=new AtomicInteger(),floatCalls=new AtomicInteger(),failureCalls=new AtomicInteger();
    public static final RuntimeException MARKER=new RuntimeException("owned-script-original-marker");
    public static volatile int lastFloatBits;
    public static volatile CountDownLatch entered,release;
    private ScriptOwnedFixture(){}
    public static int scriptIntOwned(int value){
        intCalls.incrementAndGet();
        CountDownLatch signal=entered,wait=release;
        if(value==42&&signal!=null&&wait!=null){signal.countDown();try{if(!wait.await(10,TimeUnit.SECONDS))throw new IllegalStateException("OWNED_SCRIPT_RELEASE_TIMEOUT");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
        return value+1;
    }
    public static long scriptLongOwned(long value){longCalls.incrementAndGet();return value;}
    public static float scriptFloatOwned(float value){floatCalls.incrementAndGet();lastFloatBits=Float.floatToRawIntBits(value);return value;}
    public static int scriptFailureOwned(int value){failureCalls.incrementAndGet();if(value<0)throw MARKER;return value;}
}
