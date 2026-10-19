package dev.tripoder.runtime;

import dalvik.system.PathClassLoader;

/**
 * The loader the platform gives every installed application, and the loader that defines guest classes.
 *
 * <p>Android installs an application with {@code LoadedApk.mClassLoader} of type {@link PathClassLoader} over the
 * verified APK sequence, and guest code is entitled to rely on that: App Bundle runtimes, hot-fix frameworks and
 * class-loader utilities read the defining loader of their own class and cast it to {@code PathClassLoader} (Alipay's
 * quinox {@code setupClassLoader} does {@code LauncherApplicationWrapper.class.getClassLoader()} then
 * {@code check-cast PathClassLoader}). Only one loader defines the guest's classes, so only that loader can carry the
 * platform type: a facade over a differently-typed defining loader still leaves every {@code Class.getClassLoader()}
 * answer uncastable. The runtime therefore defines guest classes here, over the verified archive, exactly as an
 * installation does.
 */
final class RuntimeGuestLoader extends PathClassLoader {
    /** The defining loader: the verified archive sequence, in installation order. */
    RuntimeGuestLoader(String dexPath,String libraries,ClassLoader parent){super(dexPath,libraries,parent);}
    /** The application-visible loader: the same type, no DEX of its own, delegating to the defining loader. */
    RuntimeGuestLoader(ClassLoader defining,String libraries){super("",libraries,defining);}
}
