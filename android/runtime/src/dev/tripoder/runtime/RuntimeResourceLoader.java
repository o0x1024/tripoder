package dev.tripoder.runtime;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The guest's classes come from memory, which leaves its class loader without any resources. Real applications read META-INF/services files and similar entries through it (ServiceLoader, kotlinx.coroutines, ...). This parent serves exactly the entries of the verified APK, ahead of the platform parent, and never loads a class itself. */
final class RuntimeResourceLoader extends ClassLoader {
    private final List<File> apks;
    private final ZipFile[] zips;
    RuntimeResourceLoader(ClassLoader parent,File apk){this(parent,List.of(apk));}
    RuntimeResourceLoader(ClassLoader parent,List<File> apks){super(parent);this.apks=List.copyOf(apks);this.zips=new ZipFile[this.apks.size()];}

    private static boolean validEntryName(String name){
        return name!=null&&!name.isEmpty()&&!name.startsWith("/")&&!name.contains("..")&&!name.contains("\\")&&name.indexOf('\0')<0&&name.length()<=1024;
    }
    private synchronized URL own(String name){
        if(!validEntryName(name))return null;
        for(int i=0;i<apks.size();i++){
            try{
                if(zips[i]==null)zips[i]=new ZipFile(apks.get(i));
                ZipEntry entry=zips[i].getEntry(name);
                if(entry!=null&&!entry.isDirectory())return new URL("jar:"+apks.get(i).toURI().toURL()+"!/"+new URI(null,null,name,null).getRawPath());
            }catch(Exception ignored){}
        }
        return null;
    }
    private synchronized List<URL> ownAll(String name){
        if(!validEntryName(name))return List.of();
        List<URL> found=new ArrayList<>();
        for(int i=0;i<apks.size();i++){
            try{
                if(zips[i]==null)zips[i]=new ZipFile(apks.get(i));
                ZipEntry entry=zips[i].getEntry(name);
                if(entry!=null&&!entry.isDirectory())found.add(new URL("jar:"+apks.get(i).toURI().toURL()+"!/"+new URI(null,null,name,null).getRawPath()));
            }catch(Exception ignored){}
        }
        return found;
    }
    private ClassLoader legacyHttpLoader;
    private synchronized ClassLoader legacyHttp(){
        if(legacyHttpLoader==null){
            File legacyJar=new File("/system/framework/org.apache.http.legacy.jar");
            if(legacyJar.isFile())legacyHttpLoader=new dalvik.system.PathClassLoader(legacyJar.getPath(),getParent());
        }
        return legacyHttpLoader;
    }
    @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
        try{return super.loadClass(name,resolve);}
        catch(ClassNotFoundException error){
            if(name!=null&&(name.startsWith("org.apache.http.")||name.startsWith("android.net.http.")||name.startsWith("com.android.internal.http."))){
                ClassLoader legacy=legacyHttp();
                if(legacy!=null)return legacy.loadClass(name);
            }
            throw error;
        }
    }
    @Override public URL getResource(String name){URL found=own(name);return found!=null?found:super.getResource(name);}
    @Override public Enumeration<URL> getResources(String name)throws IOException{
        List<URL> all=new ArrayList<>(ownAll(name));
        Enumeration<URL> inherited=super.getResources(name);while(inherited.hasMoreElements())all.add(inherited.nextElement());
        return Collections.enumeration(all);
    }
    @Override protected URL findResource(String name){return own(name);}
    @Override protected Enumeration<URL> findResources(String name){return Collections.enumeration(ownAll(name));}
}
