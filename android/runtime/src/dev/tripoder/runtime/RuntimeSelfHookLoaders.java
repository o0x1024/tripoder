package dev.tripoder.runtime;

/** Resolution identity, not equality between a selected loader and a defining loader. */
final class RuntimeSelfHookLoaders {
    private RuntimeSelfHookLoaders() {}

    private static boolean rooted(ClassLoader root,ClassLoader candidate){
        for(int depth=0;candidate!=null&&depth<32;depth++,candidate=candidate.getParent())if(candidate==root)return true;
        return false;
    }
    /** A sibling plugin loader must itself be defined by this installation and
     * resolve the already constructed guest Application to the exact same Class.
     * A foreign duplicate DEX or a boot-defined sibling supplies neither proof. */
    static boolean owns(ClassLoader root,ClassLoader candidate,Class<?> application){
        if(root==null||candidate==null)return false;
        if(rooted(root,candidate))return true;
        if(application==null||!rooted(root,candidate.getClass().getClassLoader())||!rooted(root,application.getClassLoader()))return false;
        try{return Class.forName(application.getName(),false,candidate)==application;}
        catch(ClassNotFoundException error){return false;}
    }

    static void requireApplication(ClassLoader selected,Class<?> application,Class<?> declaredPlatformDefault,java.util.function.Predicate<ClassLoader> owns) {
        if(selected==null||application==null||owns==null||!owns.test(selected)||
            (application!=declaredPlatformDefault&&(application.getClassLoader()==null||!owns.test(application.getClassLoader()))))
            throw new SecurityException("APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
        try {
            if(Class.forName(application.getName(),false,selected)!=application)
                throw new SecurityException("APPLICATION_CLASS_LOADER_RESOLUTION_MISMATCH");
        } catch(ClassNotFoundException error) {
            throw new SecurityException("APPLICATION_CLASS_LOADER_RESOLUTION_MISSING",error);
        }
    }

}
