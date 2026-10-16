package dev.tripoder.selfhookprobe;

/** Keep this compiled frame small and independent of diagnostics. */
public final class InFlightCaller {
    private InFlightCaller() {}
    public static int call(boolean wait, int value) {
        if (wait) barrierNative();
        return EntryWriterFixture.value(value);
    }
    private static native void barrierNative();
    static native boolean enteredNative();
    static native void releaseNative();
}
