package dev.tripoder.selfhookprobe;
import dalvik.annotation.optimization.FastNative;
import dalvik.annotation.optimization.CriticalNative;
public final class JniRawTarget {
    public final int identity=77;
    public static native long values(long a,long b,long c,long d,long e,long f,long g,long h,long i,long j,long k,long l,long m,long n,long o,long p);
    @FastNative public static native long fast(long a,long b,long c,long d,long e,long f,long g,long h,long i,long j,long k,long l,long m,long n,long o,long p);
    @CriticalNative public static native long critical(long a,long b,long c,long d,long e,long f,long g,long h,long i,long j,long k,long l,long m,long n,long o,long p);
    public native String text(String first,String second,long exact,double fraction);
}
