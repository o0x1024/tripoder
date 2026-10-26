package dev.tripoder.selfhook;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Android's application loader namespace with exact declared native sonames. */
public final class ClassLoaderNamespaceAccess {
    private static Method create;
    private static Method attach;
    private static java.lang.reflect.Field libraries,librariesAfter;
    private static synchronized void prepare(){
        if(create!=null)return;
        try{create=FixedFrameworkMembers.method(68,"createClassLoader","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;IZLjava/lang/String;Ljava/util/List;Ljava/util/List;Ljava/util/List;)Ljava/lang/ClassLoader;",true);}
        catch(Exception failure){throw new IllegalStateException("APPLICATION_CLASSLOADER_NAMESPACE_UNAVAILABLE",failure);}
    }
    public static ClassLoader create(String dex,String search,String permitted,ClassLoader parent,int targetSdk,List<String> nativeLibraries,List<ClassLoader> before,List<ClassLoader> after){
        prepare();Objects.requireNonNull(nativeLibraries);Objects.requireNonNull(before);Objects.requireNonNull(after);
        for(String name:nativeLibraries)if(name==null||name.isEmpty()||name.equals("ALL")||name.indexOf(':')>=0||name.indexOf('/')>=0||name.indexOf('\0')>=0)throw new SecurityException("NATIVE_PLATFORM_LIBRARY_NAME_REJECTED");
        ClassLoader loader=(ClassLoader)FixedFrameworkMembers.call(create,null,Objects.requireNonNull(dex),Objects.requireNonNull(search),Objects.requireNonNull(permitted),Objects.requireNonNull(parent),targetSdk,false,null,List.copyOf(before),List.copyOf(nativeLibraries),List.copyOf(after));
        if(!(loader instanceof dalvik.system.PathClassLoader)||loader.getParent()!=parent)throw new SecurityException("APPLICATION_CLASSLOADER_NAMESPACE_IDENTITY_CHANGED");
        return loader;
    }
    /** Establish the existing in-memory loader's namespace before any guest JNI call.
     * Declared platform sonames are exported exactly as for an application loader. */
    public static synchronized void initializeInMemory(ClassLoader loader,int targetSdk,String search,String permitted,List<String> nativeLibraries){
        if(loader==null||loader.getClass()!=dalvik.system.InMemoryDexClassLoader.class||targetSdk<=0||search==null||search.isEmpty()||permitted==null||permitted.isEmpty())throw new SecurityException("ISOLATED_NATIVE_NAMESPACE_ARGUMENTS_REJECTED");
        for(String name:nativeLibraries)if(name==null||name.isEmpty()||name.equals("ALL")||name.indexOf(':')>=0||name.indexOf('/')>=0||name.indexOf('\0')>=0)throw new SecurityException("NATIVE_PLATFORM_LIBRARY_NAME_REJECTED");
        if(attach==null)try{attach=FixedFrameworkMembers.method(68,"createClassloaderNamespace","(Ljava/lang/ClassLoader;ILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",true);}catch(Exception error){throw new IllegalStateException("ISOLATED_NATIVE_NAMESPACE_UNAVAILABLE",error);}
        Object error=FixedFrameworkMembers.call(attach,null,loader,targetSdk,search,permitted,false,"",String.join(":",nativeLibraries));
        if(error!=null)throw new UnsatisfiedLinkError("ISOLATED_NATIVE_NAMESPACE_REJECTED:"+error);
    }
    /** An application loader receives its shared-library loaders at construction. The
     * in-memory constructor has no such parameter, so the same arrays are installed
     * by the caller immediately after construction, before the loader's first lookup.
     * A loader that already carries libraries is rejected. */
    public static synchronized void attachSharedLibraries(ClassLoader loader,List<ClassLoader> before,List<ClassLoader> after){
        if(loader==null||loader.getClass()!=dalvik.system.InMemoryDexClassLoader.class)throw new SecurityException("ISOLATED_SHARED_LIBRARY_LOADER_REJECTED");
        if(before.isEmpty()&&after.isEmpty())return;
        for(ClassLoader library:before)if(!(library instanceof dalvik.system.PathClassLoader))throw new SecurityException("ISOLATED_SHARED_LIBRARY_KIND_REJECTED");
        for(ClassLoader library:after)if(!(library instanceof dalvik.system.PathClassLoader))throw new SecurityException("ISOLATED_SHARED_LIBRARY_KIND_REJECTED");
        if(libraries==null)try{libraries=FixedFrameworkMembers.field(70,85,"sharedLibraryLoaders","[Ljava/lang/ClassLoader;",false);librariesAfter=FixedFrameworkMembers.field(71,85,"sharedLibraryLoadersAfter","[Ljava/lang/ClassLoader;",false);}
        catch(Exception error){libraries=null;throw new IllegalStateException("ISOLATED_SHARED_LIBRARY_FIELDS_UNAVAILABLE",error);}
        if(FixedFrameworkMembers.get(libraries,loader)!=null||FixedFrameworkMembers.get(librariesAfter,loader)!=null)throw new SecurityException("ISOLATED_SHARED_LIBRARIES_ALREADY_ATTACHED");
        FixedFrameworkMembers.set(libraries,loader,before.isEmpty()?null:before.toArray(ClassLoader[]::new));
        FixedFrameworkMembers.set(librariesAfter,loader,after.isEmpty()?null:after.toArray(ClassLoader[]::new));
    }
    private ClassLoaderNamespaceAccess(){}
}
