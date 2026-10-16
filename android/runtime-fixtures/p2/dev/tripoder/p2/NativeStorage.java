package dev.tripoder.p2;

/** Logical package name and payload only; JNI itself constructs the file path. */
public final class NativeStorage {
    static {System.loadLibrary("p0fixture");}
    public static native String run(String packageName,String readonlyCodePath,String token);
    public static native void rawSymlink(String target,String path);
    private NativeStorage(){}
}
