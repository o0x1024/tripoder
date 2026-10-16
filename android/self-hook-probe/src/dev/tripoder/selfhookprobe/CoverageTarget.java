package dev.tripoder.selfhookprobe;
import java.util.concurrent.atomic.AtomicInteger;
public final class CoverageTarget {
    public static final java.util.concurrent.atomic.AtomicInteger dynamicBodies=new java.util.concurrent.atomic.AtomicInteger();
    public static final AtomicInteger initializations=new AtomicInteger();
    public static final AtomicInteger bodyCalls=new AtomicInteger();
    public static int value(int value){bodyCalls.incrementAndGet();return value+1;}
}
