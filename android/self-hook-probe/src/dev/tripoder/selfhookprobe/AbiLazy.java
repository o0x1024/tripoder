package dev.tripoder.selfhookprobe;
public final class AbiLazy {
    static {AbiFixture.initializations.incrementAndGet();}
    public static long value(long value){return value;}
}
