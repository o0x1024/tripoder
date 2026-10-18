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
    private static native boolean prepareBatchNative(Object[] targets, Class<?>[] owners, Object[] shorties);
    public static native String report();
    public static native String preparationMetrics();

    public static synchronized boolean prepare(Executable target) throws Exception {
        return prepareBatch(new Executable[]{target}, new String[]{null});
    }
    public static synchronized boolean prepareBatch(Executable[] targets, String[] shorties) throws Exception {
        if (targets == null || shorties == null || targets.length == 0 || targets.length != shorties.length)
            throw new IllegalArgumentException("OWNED_MANAGED_STORAGE_REQUIRED");
        Class<?>[] owners = new Class<?>[targets.length];
        String[] resolvedShorties = new String[targets.length];
        boolean hasNative = false;
        for (int i = 0; i < targets.length; i++) {
            Executable target = targets[i];
            if (target == null || target.getDeclaringClass().isInterface()
                || java.lang.reflect.Proxy.isProxyClass(target.getDeclaringClass())
                || (target.getModifiers() & Modifier.ABSTRACT) != 0)
                throw new IllegalArgumentException("OWNED_MANAGED_STORAGE_REQUIRED");
            FrameworkAccess.requireManagedHookTarget(target);
            owners[i] = target.getDeclaringClass();
            String s = shorties[i];
            if (s == null) {
                AbiSignature abi = new AbiSignature(target);
                StringBuilder sb = new StringBuilder(AbiSignature.reference(abi.result) ? "L" : abi.result);
                for (String arg : abi.parameters) sb.append(AbiSignature.reference(arg) ? "L" : arg);
                s = sb.toString();
            }
            resolvedShorties[i] = s;
            if (Modifier.isNative(target.getModifiers())) hasNative = true;
        }
        if (!bound) {
            if (!bindNative(OriginalMethodStorage.class.getDeclaredMethod("allocateNative", int.class),OriginalMethodStorage.class.getDeclaredMethod("findOwnerNative",Class.class))) return false;
            bound = true;
        }
        if (hasNative) NativeMethodOriginal.initialize();
        if (targets.length == 1) return prepareNative(targets[0], owners[0], resolvedShorties[0]);
        return prepareBatchNative(targets, owners, resolvedShorties);
    }
}
