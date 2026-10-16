package dev.tripoder.selfhook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Owned static int(int) capability used to prove metadata-safe entry changes. */
public final class OwnedIntHook {
    private OwnedIntHook() {}
    private static boolean attempted;
    private static native int delegate(int value);
    private static native boolean prepareNative(Method target,Method helper,Class<?> owner,Method[] callers,int delta);
    public static native boolean toggle(boolean enabled);
    public static native int callOriginal(int value);
    public static native String report();
    public static native byte[] relayBytes();
    public static synchronized boolean prepare(Method target,Method[] callers,int delta) throws Exception {
        if(attempted)throw new IllegalStateException("OWNED_INT_HOOK_ALREADY_PREPARED");
        ClassLoader loader=OwnedIntHook.class.getClassLoader();
        if(target==null||target.getDeclaringClass().getClassLoader()!=loader||target.getReturnType()!=int.class||
            !java.util.Arrays.equals(target.getParameterTypes(),new Class<?>[]{int.class})||
            (target.getModifiers()&(Modifier.STATIC|Modifier.NATIVE|Modifier.ABSTRACT|Modifier.SYNCHRONIZED))!=Modifier.STATIC)
            throw new IllegalArgumentException("OWNED_STATIC_INT_TARGET_REQUIRED");
        if(callers==null||callers.length<1||callers.length>64)throw new IllegalArgumentException("OWNED_CALLER_SET_REQUIRED");
        Method[] snapshot=callers.clone();java.util.HashSet<Method> unique=new java.util.HashSet<>();
        for(Method caller:snapshot)if(caller==null||caller.equals(target)||caller.getDeclaringClass().getClassLoader()!=loader||
            Modifier.isNative(caller.getModifiers())||Modifier.isAbstract(caller.getModifiers())||!unique.add(caller))
            throw new IllegalArgumentException("OWNED_CALLER_IDENTITY_REQUIRED");
        attempted=true;
        if (!OriginalMethodStorage.prepare(target)) return false;
        return prepareNative(target,
            OwnedIntHook.class.getDeclaredMethod("delegate",int.class),target.getDeclaringClass(),snapshot,delta);
    }
}
