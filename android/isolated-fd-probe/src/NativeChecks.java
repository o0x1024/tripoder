package dev.tripoder.isolatedfdprobe;

final class NativeChecks {
    static{System.loadLibrary("isolatedfdchecks");}
    static native String run(String kind,int library,int dependency);
    static native int executableCopy(int source)throws java.io.IOException;
    private NativeChecks(){}
}
