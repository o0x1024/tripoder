package dev.tripoder.selfhookprobe;

/** Diagnostic addresses only; no Java reference is reconstructed from a number. */
final class GcProbe {
    private GcProbe(){}
    static native long[] snapshot(Object[] roots);
}
