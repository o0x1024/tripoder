package dev.tripoder.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LoaderTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void rejects(Runnable action,String code) {
        try{action.run();throw new AssertionError("accepted "+code);}
        catch(SecurityException error){check(error.getMessage().equals(code));}
    }
    private static boolean owns(ClassLoader root,ClassLoader loader) {
        for(ClassLoader value=loader;value!=null;value=value.getParent())if(value==root)return true;
        return false;
    }
    public static void main(String[] args)throws Exception {
        URL url=Path.of(args[0]).toUri().toURL();
        try(var artifact=new URLClassLoader(new URL[]{url},null);
            var selected=new URLClassLoader(new URL[0],artifact);
            var sibling=new URLClassLoader(new URL[]{url},null)) {
            Class<?> application=artifact.loadClass("fixture.OwnedApplication");
            Class<?> adapter=artifact.loadClass("fixture.OwnedDelegatingLoader");
            ClassLoader delegated=(ClassLoader)adapter.getConstructor(ClassLoader.class).newInstance(artifact);
            ClassLoader foreignDelegate=(ClassLoader)adapter.getConstructor(ClassLoader.class).newInstance(sibling);
            check(RuntimeSelfHookLoaders.owns(artifact,delegated,application));
            check(!RuntimeSelfHookLoaders.owns(artifact,foreignDelegate,application));
            check(!RuntimeSelfHookLoaders.owns(artifact,sibling,application));
            check(!RuntimeSelfHookLoaders.owns(artifact,delegated,Object.class));
            check(System.getProperty("fixture.initialized")==null);
            RuntimeSelfHookLoaders.requireApplication(artifact,application,null,loader->owns(artifact,loader));checks++;
            RuntimeSelfHookLoaders.requireApplication(selected,application,null,loader->owns(artifact,loader));checks++;
            check(application.getClassLoader()==artifact);check(selected.loadClass(application.getName())==application);
            rejects(()->RuntimeSelfHookLoaders.requireApplication(sibling,application,null,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
            rejects(()->RuntimeSelfHookLoaders.requireApplication(selected,Object.class,null,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
            Class<?> foreign=sibling.loadClass(application.getName());
            rejects(()->RuntimeSelfHookLoaders.requireApplication(selected,foreign,null,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
            byte[] bytes=Files.readAllBytes(Path.of(args[0],"fixture/OwnedApplication.class"));
            ClassLoader shadow=new ClassLoader(artifact) {
                protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                    if(!name.equals("fixture.OwnedApplication"))return super.loadClass(name,resolve);
                    Class<?> value=findLoadedClass(name);if(value==null)value=defineClass(name,bytes,0,bytes.length);
                    if(resolve)resolveClass(value);return value;
                }
            };
            rejects(()->RuntimeSelfHookLoaders.requireApplication(shadow,application,null,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_RESOLUTION_MISMATCH");
            RuntimeSelfHookLoaders.requireApplication(shadow,shadow.loadClass(application.getName()),null,loader->owns(artifact,loader));checks++;
            ClassLoader missing=new ClassLoader(artifact){protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{throw new ClassNotFoundException(name);}};
            rejects(()->RuntimeSelfHookLoaders.requireApplication(missing,application,null,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_RESOLUTION_MISSING");
            check(System.getProperty("fixture.initialized")==null);
            RuntimeSelfHookLoaders.requireApplication(selected,Object.class,Object.class,loader->owns(artifact,loader));checks++;
            rejects(()->RuntimeSelfHookLoaders.requireApplication(sibling,Object.class,Object.class,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
            rejects(()->RuntimeSelfHookLoaders.requireApplication(selected,String.class,Object.class,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_OUTSIDE_ARTIFACT");
            rejects(()->RuntimeSelfHookLoaders.requireApplication(missing,Object.class,Object.class,loader->owns(artifact,loader)),"APPLICATION_CLASS_LOADER_RESOLUTION_MISSING");
        }
        System.out.println("loader checks="+checks);
    }
}
