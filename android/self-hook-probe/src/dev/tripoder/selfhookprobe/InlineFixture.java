package dev.tripoder.selfhookprobe;

/** Small leaf intentionally eligible for compiler inlining. */
public final class InlineFixture {
    public static int plusOne(int value) { return value + 1; }
}
