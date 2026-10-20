package dev.tripoder.selfhook;

/** Read-only access to retained ART DexFile objects on the validated ARM64
 * runtime. Native pointers never come from an external command. */
public final class CodeSnapshots {
    private CodeSnapshots(){}
    public static native Object[] dexFiles(ClassLoader loader);
    public static native long[] cookies(Object dexFile);
    /** begin, file size, DEX version; null means unsupported or unreadable. */
    public static native long[] inspect(long nativeDexFile);
    public static native byte[] copy(long nativeDexFile,int maximumBytes);
    /** Atomic write through an authority-issued directory descriptor. The name
     * is a single artifact ID with a .dex/.json suffix, never a path. */
    public static native void writeArtifact(int directory,String name,byte[] bytes)throws java.io.IOException;
    /** Fixed boot adapter admission, limited to loader construction and path
     * extension; this grants no arbitrary boot-method instrumentation. */
    static boolean permitsLoaderProbe(java.lang.reflect.Executable member){
        Class<?> owner=member.getDeclaringClass();
        if(!java.util.Set.of("dalvik.system.BaseDexClassLoader","dalvik.system.PathClassLoader","dalvik.system.DexClassLoader","dalvik.system.InMemoryDexClassLoader").contains(owner.getName())||java.lang.reflect.Modifier.isNative(member.getModifiers()))return false;
        try{if(Class.forName(owner.getName(),false,null)!=owner)return false;}catch(ClassNotFoundException missing){return false;}
        if(member instanceof java.lang.reflect.Constructor<?>)return true;
        if(!owner.getName().equals("dalvik.system.BaseDexClassLoader"))return false;
        if(!member.getName().equals("addDexPath")||!(member instanceof java.lang.reflect.Method method)||method.getReturnType()!=void.class||java.lang.reflect.Modifier.isStatic(member.getModifiers()))return false;
        Class<?>[] args=member.getParameterTypes();return args.length==1&&args[0]==String.class||args.length==2&&args[0]==String.class&&args[1]==boolean.class;
    }
}
