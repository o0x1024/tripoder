package dev.tripoder.runtime;

/** Resolution identity, not equality between a selected loader and a defining loader. */
final class RuntimeSelfHookLoaders {
    private RuntimeSelfHookLoaders() {}

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
