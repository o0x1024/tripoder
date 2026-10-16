package dev.tripoder.selfhookprobe;

import java.util.concurrent.atomic.AtomicInteger;

/** Owned, non-native target. The original body has observable effects and exceptions. */
public final class ManagedFixture {
    public static final AtomicInteger calls = new AtomicInteger();
    public static final RuntimeException marker = new RuntimeException("owned-managed-original");
    public static volatile long thread;
    public static int compute(int value) {
        calls.incrementAndGet();
        thread = Thread.currentThread().getId();
        if (value == -1) throw marker;
        return value + 1;
    }
}
