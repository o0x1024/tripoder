package dev.tripoder.selfhook;

import dalvik.annotation.optimization.FastNative;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import dev.tripoder.selfhook.dex.AbiSignature;

/** Fixed-platform storage experiment. Bounded originals rooted by owned declaring classes. */
public final class OriginalMethodStorage {
    private OriginalMethodStorage() {}
    private static boolean bound;
    private static native boolean bindNative(Method allocator,Method ownerLookup);
    @FastNative private static native boolean allocateNative(int slot);
    @FastNative private static native int findOwnerNative(Class<?> owner);
    private static native boolean prepareNative(Executable target, Class<?> owner, String shorty);
    public static native String report();

    public static synchronized boolean prepare(Executable target) throws Exception {
        if (target == null || target.getDeclaringClass().isInterface()
            || java.lang.reflect.Proxy.isProxyClass(target.getDeclaringClass())
            || (target.getModifiers() & Modifier.ABSTRACT) != 0)
            throw new IllegalArgumentException("OWNED_MANAGED_STORAGE_REQUIRED");
        FrameworkAccess.requireManagedHookTarget(target);
        if (!bound) {
            if (!bindNative(OriginalMethodStorage.class.getDeclaredMethod("allocateNative", int.class),OriginalMethodStorage.class.getDeclaredMethod("findOwnerNative",Class.class))) return false;
            bound = true;
        }
        AbiSignature abi=new AbiSignature(target);StringBuilder shorty=new StringBuilder(AbiSignature.reference(abi.result)?"L":abi.result);
        for(String arg:abi.parameters)shorty.append(AbiSignature.reference(arg)?"L":arg);
        if(Modifier.isNative(target.getModifiers()))NativeMethodOriginal.initialize();
        return prepareNative(target, target.getDeclaringClass(),shorty.toString());
    }
}
