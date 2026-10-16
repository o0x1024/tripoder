package dev.tripoder.selfhookprobe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Real managed frames remain blocked while entry ownership changes and GC runs. */
public final class ActiveIntFixture {
    public static final AtomicInteger calls=new AtomicInteger();
    public static final RuntimeException marker=new RuntimeException("owned-active-int-original");
    public static volatile CountDownLatch entered,release;
    public static int compute(int value){
        calls.incrementAndGet();Object root=new int[]{value,73};
        if(value==-1)throw marker;
        if(value==42){CountDownLatch start=entered,finish=release;
            if(start!=null&&finish!=null){start.countDown();try{finish.await();}catch(InterruptedException error){throw new IllegalStateException(error);}}}
        int[] values=(int[])root;
        if(values[0]!=value||values[1]!=73)throw new IllegalStateException("ACTIVE_FRAME_REFERENCE_CORRUPTED");
        return value+1;
    }
}
