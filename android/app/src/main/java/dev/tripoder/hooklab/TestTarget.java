package dev.tripoder.hooklab;

public final class TestTarget {
    static { System.loadLibrary("hookbridge"); }
    // No Hook flags or configurable return values in either target.
    public static String greet(String name) { return "original:" + name; }
    public static native int nativeDelay(int base);
    public static native String nativeBuildId();
}
