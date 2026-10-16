package dev.tripoder.vmprobe;

/** The only runtime backend: the locally built ARM64 QEMU DSO. */
final class NativeEngine {
    static { System.loadLibrary("tripoder-qemu"); }
    private NativeEngine() { }
    static native String start(String[] arguments);
}
