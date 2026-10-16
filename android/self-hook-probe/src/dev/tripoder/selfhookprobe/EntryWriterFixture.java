package dev.tripoder.selfhookprobe;
public final class EntryWriterFixture {
    public static final java.util.concurrent.atomic.AtomicInteger bodies=new java.util.concurrent.atomic.AtomicInteger();
    public static int value(int n){bodies.incrementAndGet();return n+1;}
}
