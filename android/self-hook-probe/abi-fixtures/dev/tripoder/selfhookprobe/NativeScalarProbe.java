package dev.tripoder.selfhookprobe;

/** Owned ABI calls only; native library is loaded by the existing ProbeEnvironment gate. */
public final class NativeScalarProbe {
    private NativeScalarProbe(){}
    public static native String run();
}
