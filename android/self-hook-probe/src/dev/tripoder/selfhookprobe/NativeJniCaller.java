package dev.tripoder.selfhookprobe;
public final class NativeJniCaller {
    public static native boolean open(String library,String audit);
    public static native long[] rvas();
    public static native int add(int a,int b);
    public static native long exact(long value);
    public static native double fraction(double value);
    public static native int error(int value);
    public static native int count(int index);
    public static native long[] concurrent();
    public static native long[] floatingEnvironment();
    public static native boolean close();
}
