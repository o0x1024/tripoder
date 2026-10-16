package dev.tripoder.p0.shared;
public final class NativeProbe {
    static {System.loadLibrary("p0fixture");}
    public static native String writeRead(String path,String text);
    public static native boolean canRead(String path);
    public static native boolean canReadRaw(String path);
}
