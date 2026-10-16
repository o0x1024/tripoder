package dev.tripoder.selfhookprobe;

/** Loaded only by the owned child loader; initialization is observable without touching the Class. */
public final class OwnedProductJavaTarget {
    static {System.setProperty("tripoder.owned.product-java-initialized","yes");}
    public static final RuntimeException failure=new IllegalStateException("owned-product-original-exception");
    public static int voidCalls;
    public static int increment(int n){return n+1;}
    public static long wide(long n){return n;}
    public static char letter(char c){return c;}
    public static boolean flag(boolean value){return value;}
    public static byte tiny(byte value){return value;}
    public static short small(short value){return value;}
    public static float ratio(float value){return value;}
    public static double precise(double value){return value;}
    public static String greet(String value){return value==null?null:value+"!";}
    public static void touch(){voidCalls++;}
    public int instance(int n){return n;}
    public static int throwSame(int ignored){throw failure;}
    public static native int unresolvedNative(int n);
    public static native int nativeValue(int n);
    @dalvik.annotation.optimization.FastNative public static native float nativeFloat(float value);
    @dalvik.annotation.optimization.CriticalNative public static native double nativeDouble(double value);
}
