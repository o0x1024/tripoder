package dev.tripoder.selfhookprobe;
import java.lang.reflect.Method;
final class EntryWriterProbe {
    static native String exerciseNative(Method target);
    static native byte[] imageNative(int kind);
    static native String statusNative();
}
