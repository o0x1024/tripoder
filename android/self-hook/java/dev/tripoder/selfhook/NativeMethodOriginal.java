package dev.tripoder.selfhook;
import dalvik.annotation.optimization.FastNative;
import java.lang.reflect.Method;
/** Private original bridge. ART remains the sole writer of target JNI data. */
final class NativeMethodOriginal {
    private static boolean attempted,bound;
    private NativeMethodOriginal(){}
    private static native boolean bindNative(Method resolver);
    @FastNative private static native long resolve(int slot);
    static synchronized void initialize()throws Exception{
        if(bound)return;if(attempted)throw new IllegalStateException("NATIVE_ORIGINAL_BINDING_FAILED");attempted=true;
        if(!bindNative(NativeMethodOriginal.class.getDeclaredMethod("resolve",int.class)))throw new IllegalStateException("NATIVE_ORIGINAL_ART_CONTRACT");bound=true;
    }
}
