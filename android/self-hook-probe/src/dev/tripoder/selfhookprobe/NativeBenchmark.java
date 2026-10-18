package dev.tripoder.selfhookprobe;
public final class NativeBenchmark {
    public static native int pinThread(int cpu);
    public static native String affinityReport();
    public static native long[] measureState();
    public static native boolean verifyThread(int cpu);
    public static native long[] nativeSamples(String library,long generation,long rva,int count);
    public static native long[] foreignSamples(int count);
    private NativeBenchmark(){}
}
