package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.*;
import android.os.Bundle;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** A single pre-admitted system publication; never a retry through another code source. */
final class RuntimePublishedNativeCode {
    private static final String KIND="SYSTEM_PUBLISHED_NATIVE_V1";
    private static final int EXTRACT_NATIVE_LIBS=0x10000000;
    static final class Plan {
        final Bundle wire;
        Plan(Bundle wire){this.wire=wire.deepCopy();}
        boolean same(Plan other){return other!=null&&fingerprint(wire).equals(fingerprint(other.wire));}
        void require(ApkStore.BundleInstall install){if(!Objects.equals(wire.getString("space"),install.space)||!Objects.equals(wire.getString("package"),install.artifacts.packageName)||!Objects.equals(wire.getString("install"),install.generation)||!Objects.equals(wire.getString("descriptor"),install.artifacts.descriptorSha)||install.artifacts.nativeFiles.isEmpty())throw new SecurityException("ISOLATED_NATIVE_PLAN_OWNER_CHANGED");}
        void recheck(Context host,ApkStore.BundleInstall install)throws Exception{if(!same(capture(host,install)))throw new SecurityException("PUBLIC_NATIVE_PUBLICATION_CHANGED");}
    }
    static final class Verified implements AutoCloseable {
        final Bundle wire;final String search,permitted;final List<RuntimePublishedCodeFiles.Open> held;private final Map<String,Located> located;
        /** One verified, read-only library file and where its bytes begin inside it. */
        static final class Located {final RuntimePublishedCodeFiles.Open file;final long offset;Located(RuntimePublishedCodeFiles.Open file,long offset){this.file=file;this.offset=offset;}}
        Verified(Bundle wire,String search,List<RuntimePublishedCodeFiles.Open> held,Map<String,Located> located){this.wire=wire;this.search=search;permitted=wire.getString("root");this.held=held;this.located=located;}
        /** The exact published library, never another file of the same name. */
        Located locate(String name){Located value=located.get(name);if(value==null)throw new SecurityException("PUBLIC_NATIVE_LIBRARY_NOT_PUBLISHED:"+name);return value;}
        void metadata(ApplicationInfo info){
            var paths=wire.getStringArrayList("apks");info.sourceDir=paths.get(0);info.publicSourceDir=paths.get(0);info.splitSourceDirs=paths.size()>1?paths.subList(1,paths.size()).toArray(String[]::new):null;info.splitPublicSourceDirs=info.splitSourceDirs;info.nativeLibraryDir=wire.getString("nativeDirectory");
        }
        @Override public void close(){for(var file:held)try{file.close();}catch(IOException error){android.util.Log.e("TripoderIsolated","PUBLIC_CODE_CLOSE_FAILED",error);}}
    }
    private static String fingerprint(Bundle value){
        return List.of(value.getString("package"),value.getString("space"),value.getString("install"),value.getString("descriptor"),value.getString("mode"),value.getString("root"),value.getString("nativeDirectory"),value.getInt("sourceUid"),value.getStringArrayList("apks"),value.getStringArrayList("apkHashes"),value.getStringArrayList("names"),value.getStringArrayList("hashes"),Arrays.toString(value.getLongArray("apkSizes")),Arrays.toString(value.getLongArray("sizes"))).toString();
    }
    static Plan capture(Context host,ApkStore.BundleInstall install)throws Exception{
        if(install.artifacts.nativeFiles.isEmpty())return null;
        var artifacts=install.artifacts;
        PackageInfo source;
        try{source=host.getPackageManager().getPackageInfo(artifacts.packageName,PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));}
        catch(PackageManager.NameNotFoundException absent){throw new UnsupportedOperationException("ISOLATED_NATIVE_SYSTEM_PUBLICATION_REQUIRED",absent);}
        if(source.applicationInfo==null||source.signingInfo==null||!source.packageName.equals(artifacts.packageName)||source.getLongVersionCode()!=artifacts.versionCode)throw new SecurityException("PUBLIC_NATIVE_PACKAGE_IDENTITY_CHANGED");
        var signers=new TreeSet<String>();for(var signer:source.signingInfo.getApkContentsSigners())signers.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())));
        if(signers.isEmpty()||!signers.equals(new TreeSet<>(artifacts.signerDigests)))throw new SecurityException("PUBLIC_NATIVE_SIGNER_CHANGED");
        ApplicationInfo info=source.applicationInfo;var installed=new HashMap<String,String>();installed.put("",info.sourceDir);
        String[] splits=source.splitNames,paths=info.splitSourceDirs;
        if((splits==null?0:splits.length)!=(paths==null?0:paths.length))throw new SecurityException("PUBLIC_NATIVE_SPLIT_SHAPE_CHANGED");
        if(splits!=null)for(int i=0;i<splits.length;i++)if(splits[i]==null||splits[i].isEmpty()||installed.put(splits[i],paths[i])!=null)throw new SecurityException("PUBLIC_NATIVE_SPLIT_AMBIGUOUS");
        if(installed.size()!=artifacts.parts.size())throw new SecurityException("PUBLIC_NATIVE_APK_SET_CHANGED");
        String root=new File(RuntimePublishedCodeFiles.canonical(info.sourceDir)).getParent();RuntimePublishedCodeFiles.directory(root);
        Bundle wire=new Bundle();wire.putString("kind",KIND);wire.putString("package",artifacts.packageName);wire.putString("space",install.space);wire.putString("install",install.generation);wire.putString("descriptor",artifacts.descriptorSha);wire.putString("root",root);wire.putInt("sourceUid",info.uid);
        wire.putString("mode",(info.flags&EXTRACT_NATIVE_LIBS)!=0?"DIRECTORY":"APK");
        String directory=RuntimePublishedCodeFiles.canonical(info.nativeLibraryDir);if(!directory.startsWith(root+"/"))throw new SecurityException("PUBLIC_NATIVE_DIRECTORY_OWNER_CHANGED");wire.putString("nativeDirectory",directory);
        var publicPaths=new ArrayList<String>();var apkHashes=new ArrayList<String>();long[] apkSizes=new long[artifacts.parts.size()];
        for(int i=0;i<artifacts.parts.size();i++){
            var part=artifacts.parts.get(i);String split=part.manifest.splitName==null?"":part.manifest.splitName;String path=installed.remove(split);
            if(path==null||!new File(RuntimePublishedCodeFiles.canonical(path)).getParent().equals(root))throw new SecurityException("PUBLIC_NATIVE_APK_PART_CHANGED");publicPaths.add(path);apkHashes.add(part.sha256);apkSizes[i]=part.size;
        }
        if(!installed.isEmpty())throw new SecurityException("PUBLIC_NATIVE_APK_SET_CHANGED");wire.putStringArrayList("apks",publicPaths);wire.putStringArrayList("apkHashes",apkHashes);wire.putLongArray("apkSizes",apkSizes);
        var names=new ArrayList<String>();var hashes=new ArrayList<String>();long[] sizes=new long[artifacts.nativeFiles.size()];
        for(int i=0;i<artifacts.nativeFiles.size();i++){var file=artifacts.nativeFiles.get(i);String name=new File(file.path).getName();if(names.contains(name))throw new SecurityException("PUBLIC_NATIVE_LIBRARY_AMBIGUOUS");names.add(name);hashes.add(file.sha256);sizes[i]=file.size;}
        wire.putStringArrayList("names",names);wire.putStringArrayList("hashes",hashes);wire.putLongArray("sizes",sizes);
        try(var ignored=verify(wire,install.space,artifacts.packageName,install.generation,artifacts.descriptorSha)){}
        return new Plan(wire);
    }
    private static String digest(InputStream input,long expected)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[262144];long total=0;int count;
        while((count=input.read(bytes))!=-1){total+=count;if(total>expected)throw new SecurityException("PUBLIC_NATIVE_ENTRY_SIZE_CHANGED");sha.update(bytes,0,count);}
        if(total!=expected)throw new SecurityException("PUBLIC_NATIVE_ENTRY_SIZE_CHANGED");return HexFormat.of().formatHex(sha.digest());
    }
    static Verified verify(Bundle wire,String space,String pkg,String install,String descriptor)throws Exception{
        if(wire==null||!KIND.equals(wire.getString("kind"))||!Objects.equals(space,wire.getString("space"))||!Objects.equals(pkg,wire.getString("package"))||!Objects.equals(install,wire.getString("install"))||!Objects.equals(descriptor,wire.getString("descriptor")))throw new SecurityException("PUBLIC_NATIVE_BINDING_CHANGED");
        String root=RuntimePublishedCodeFiles.canonical(wire.getString("root")),mode=wire.getString("mode");if(mode==null||!Set.of("DIRECTORY","APK").contains(mode))throw new SecurityException("PUBLIC_NATIVE_MODE_REJECTED");
        String originalDirectory=RuntimePublishedCodeFiles.canonical(wire.getString("nativeDirectory"));if(!originalDirectory.startsWith(root+"/"))throw new SecurityException("PUBLIC_NATIVE_DIRECTORY_REJECTED");
        var paths=wire.getStringArrayList("apks");var apkHashes=wire.getStringArrayList("apkHashes");long[] apkSizes=wire.getLongArray("apkSizes");
        var names=wire.getStringArrayList("names");var hashes=wire.getStringArrayList("hashes");long[] sizes=wire.getLongArray("sizes");
        if(paths==null||paths.isEmpty()||paths.size()>ArtifactSet.MAX_PARTS||apkHashes==null||apkSizes==null||apkHashes.size()!=paths.size()||apkSizes.length!=paths.size()||new HashSet<>(paths).size()!=paths.size()||names==null||names.isEmpty()||names.size()>512||hashes==null||sizes==null||hashes.size()!=names.size()||sizes.length!=names.size()||new HashSet<>(names).size()!=names.size())throw new SecurityException("PUBLIC_NATIVE_PLAN_SHAPE_REJECTED");
        if(!root.equals(new File(paths.get(0)).getParent()))throw new SecurityException("PUBLIC_NATIVE_ROOT_CHANGED");
        for(int i=0;i<names.size();i++)if(names.get(i)==null||hashes.get(i)==null||!names.get(i).matches("lib[A-Za-z0-9_.+\\-]+\\.so")||!hashes.get(i).matches("[0-9a-f]{64}")||sizes[i]<=0||sizes[i]>ArtifactSet.MAX_APK_BYTES)throw new SecurityException("PUBLIC_NATIVE_LIBRARY_SHAPE_REJECTED");
        var held=new ArrayList<RuntimePublishedCodeFiles.Open>();
        try{
            for(int i=0;i<paths.size();i++){if(!root.equals(new File(paths.get(i)).getParent()))throw new SecurityException("PUBLIC_NATIVE_PART_ROOT_CHANGED");held.add(RuntimePublishedCodeFiles.open(paths.get(i),root,apkHashes.get(i),apkSizes[i]));}
            var search=new ArrayList<String>();var located=new HashMap<String,Verified.Located>();
            if(mode.equals("DIRECTORY")){
                String directory=originalDirectory;RuntimePublishedCodeFiles.directory(directory);search.add(directory);
                for(int i=0;i<names.size();i++){var file=RuntimePublishedCodeFiles.open(directory+"/"+names.get(i),root,hashes.get(i),sizes[i]);held.add(file);located.put(names.get(i),new Verified.Located(file,0));}
            }else{
                Set<String> found=new HashSet<>();
                for(int i=0;i<paths.size();i++){
                    var entries=new HashSet<String>();
                    try(var archive=new ZipFile(held.get(i).descriptor())){
                        for(int n=0;n<names.size();n++){
                            String path="lib/arm64-v8a/"+names.get(n);var entry=archive.getEntry(path);if(entry==null)continue;
                            if(!found.add(names.get(n))||entry.getMethod()!=ZipEntry.STORED||entry.getSize()!=sizes[n])throw new SecurityException("PUBLIC_NATIVE_APK_LIBRARY_CHANGED");
                            try(var input=archive.getInputStream(entry)){if(!digest(input,sizes[n]).equals(hashes.get(n)))throw new SecurityException("PUBLIC_NATIVE_ENTRY_DIGEST_CHANGED");}entries.add(path);
                        }
                    }
                    var offsets=RuntimeNativeZipLayout.require(held.get(i).descriptor(),entries);if(!entries.isEmpty())search.add(paths.get(i)+"!/lib/arm64-v8a");
                    for(var entry:offsets.entrySet())located.put(entry.getKey().substring("lib/arm64-v8a/".length()),new Verified.Located(held.get(i),entry.getValue()));
                }
                if(!found.equals(new HashSet<>(names)))throw new SecurityException("PUBLIC_NATIVE_APK_LIBRARY_SET_CHANGED");
            }
            for(var file:held)file.unchanged();return new Verified(wire.deepCopy(),String.join(File.pathSeparator,search),held,located);
        }catch(Exception|Error failure){for(var file:held)try{file.close();}catch(IOException suppressed){failure.addSuppressed(suppressed);}throw failure;}
    }
    private RuntimePublishedNativeCode(){}
}
