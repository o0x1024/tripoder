package dev.tripoder.selfhookprobe;
final class JniRawProbe {
    static native String run();
    @dalvik.annotation.optimization.FastNative static native long registered(int index);
}
