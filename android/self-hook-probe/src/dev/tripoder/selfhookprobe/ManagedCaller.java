package dev.tripoder.selfhookprobe;

/** Compiled BEFORE replacement to expose stale direct calls and inlined bodies. */
public final class ManagedCaller {
    public static int call(int expectedOffset) {
        int errors = 0;
        for (int i = 0; i < 10000; i++) {
            if (InlineFixture.plusOne(i) != i + expectedOffset) errors++;
        }
        return errors;
    }
}
