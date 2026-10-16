package dev.tripoder.selfhookprobe;

/** Owned methods for JNI/ART identity and invocation observations. */
public final class ArtFixture {
    private final int bias;
    public ArtFixture(int bias) { this.bias = bias; }
    public static int add(int value) { return value + 1; }
    public int addInstance(int value) { return value + bias; }
    public static synchronized int monitor(int value) {
        if (!Thread.holdsLock(ArtFixture.class)) throw new AssertionError("MONITOR_MISSING");
        return value + 1;
    }
    public static int recurse(int value) { return value == 0 ? 0 : 1 + recurse(value - 1); }
    public static long threadId() { return Thread.currentThread().getId(); }
    public static Object echo(Object value) { System.gc(); return value; }
    public static void throwExact(RuntimeException value) { throw value; }
}
