package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.SharedLibraryInfo;
import android.content.res.Resources;
import android.os.Bundle;
import dev.tripoder.selfhook.ClassLoaderNamespaceAccess;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.*;

/** The platform library graph an isolated guest declares. The caller describes the graph it
 * resolved through the physical PM; the authority admits it only as an exact, public-PM-consistent
 * set of read-only built-in jars it hashes itself, and the child reproduces it from those files. */
final class RuntimeIsolatedLibraries {
    private static final String KIND="ISOLATED_PLATFORM_LIBRARIES_V1";
    private static final long MAX_LIBRARY_BYTES=64L*1024*1024;
    static final class Plan {
        final Bundle wire;
        Plan(Bundle wire){this.wire=wire.deepCopy();}
        static boolean same(Plan left,Plan right){return left==null?right==null:right!=null&&fingerprint(left.wire).equals(fingerprint(right.wire));}
        static void recheck(Plan admitted,Context host,ApkStore.BundleInstall install)throws Exception{if(!same(admitted,capture(host,install,admitted==null?null:admitted.wire)))throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_CHANGED");}
    }
    static final class Loaders {
        final List<ClassLoader> before,after;final List<String> nativeNames;
        Loaders(List<ClassLoader> before,List<ClassLoader> after,List<String> nativeNames){this.before=before;this.after=after;this.nativeNames=nativeNames;}
    }
    private static String fingerprint(Bundle wire){
        List<Object> parts=new ArrayList<>(List.of(wire.getString("kind"),wire.getStringArrayList("names"),Arrays.toString(wire.getIntArray("roots")),String.valueOf(wire.getStringArrayList("after")),wire.getStringArrayList("nativeNames")));
        for(int i=0;i<wire.getStringArrayList("names").size();i++)parts.add(List.of(wire.getStringArrayList("paths:"+i),String.valueOf(wire.getStringArrayList("hashes:"+i)),Arrays.toString(wire.getLongArray("sizes:"+i)),Arrays.toString(wire.getIntArray("deps:"+i)),wire.getStringArrayList("native:"+i),wire.getString("owner:"+i),wire.getLong("version:"+i),wire.getLong("ownerVersion:"+i)));
        return parts.toString();
    }
    /** Caller: the graph this guest process resolved through the physical PM at startup. */
    static Bundle describe(RuntimeSharedLibraries libraries){
        var graph=libraries.graph;if(graph.roots.isEmpty())return null;
        List<RuntimeSharedLibraries.Node> order=new ArrayList<>(graph.nodes.values());Bundle wire=new Bundle();wire.putString("kind",KIND);var names=new ArrayList<String>();
        for(int i=0;i<order.size();i++){
            var node=order.get(i);names.add(node.info.getName());var owner=node.info.getDeclaringPackage();
            int[] deps=new int[node.dependencies.size()];for(int d=0;d<deps.length;d++)deps[d]=order.indexOf(node.dependencies.get(d));
            wire.putStringArrayList("paths:"+i,new ArrayList<>(node.paths));wire.putIntArray("deps:"+i,deps);wire.putStringArrayList("native:"+i,new ArrayList<>(node.nativeNames));
            wire.putString("owner:"+i,owner.getPackageName());wire.putLong("version:"+i,node.info.getLongVersion());wire.putLong("ownerVersion:"+i,owner.getLongVersionCode());
        }
        int[] roots=new int[graph.roots.size()];for(int r=0;r<roots.length;r++)roots[r]=order.indexOf(graph.roots.get(r));
        wire.putStringArrayList("names",names);wire.putIntArray("roots",roots);wire.putStringArrayList("nativeNames",new ArrayList<>(graph.nativeNames));return wire;
    }
    /** Authority admission, before any isolated process exists. Only public PM state and the
     * authority's own reads of the files are trusted; caller paths are never opened as anything
     * other than built-in read-only jars. */
    static Plan capture(Context host,ApkStore.BundleInstall install,Bundle described)throws Exception{
        Map<String,Boolean> declared=new LinkedHashMap<>();Set<String> javaDeclarations=new HashSet<>();
        for(var part:install.artifacts.parts)for(var child:part.manifest.application.children){
            if(child.name.equals("uses-static-library")||child.name.equals("uses-sdk-library"))throw new UnsupportedOperationException("ISOLATED_STATIC_SHARED_LIBRARY_NOT_IMPLEMENTED");
            if(!child.name.equals("uses-library")&&!child.name.equals("uses-native-library"))continue;
            String name=ApkManifest.text(child.android("name",null),"SHARED_LIBRARY_NAME"),flag=child.android("required","true");
            if(!Set.of("true","false","1","0").contains(flag))throw new IllegalStateException("SHARED_LIBRARY_REQUIRED_FLAG_UNRESOLVED");
            if(child.name.equals("uses-library"))javaDeclarations.add(name);declared.merge(name,flag.equals("true")||flag.equals("1"),(left,right)->left||right);
        }
        Map<String,SharedLibraryInfo> available=new HashMap<>();
        if(!declared.isEmpty())for(var library:host.getPackageManager().getSharedLibraries(0))if(library.getType()==SharedLibraryInfo.TYPE_BUILTIN||library.getType()==SharedLibraryInfo.TYPE_DYNAMIC){
            SharedLibraryInfo prior=available.putIfAbsent(library.getName(),library);
            if(prior!=null&&declared.containsKey(library.getName()))throw new SecurityException("PLATFORM_LIBRARY_NAME_AMBIGUOUS");
        }
        if(described==null){
            for(String name:declared.keySet())if(available.containsKey(name))throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_DESCRIPTION_REQUIRED");
            return null;
        }
        if(!KIND.equals(described.getString("kind")))throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_SHAPE_REJECTED");
        var names=described.getStringArrayList("names");int[] roots=described.getIntArray("roots");
        if(names==null||names.isEmpty()||names.size()>256||new HashSet<>(names).size()!=names.size()||roots==null||roots.length==0||described.getStringArrayList("nativeNames")==null)throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_SHAPE_REJECTED");
        Bundle wire=new Bundle();wire.putString("kind",KIND);wire.putStringArrayList("names",new ArrayList<>(names));wire.putIntArray("roots",roots.clone());
        List<Set<String>> natives=new ArrayList<>();boolean code=false;
        for(int i=0;i<names.size();i++){
            String name=names.get(i);var info=available.get(name);var owner=info==null?null:info.getDeclaringPackage();
            var paths=described.getStringArrayList("paths:"+i);int[] deps=described.getIntArray("deps:"+i);var nativeNames=described.getStringArrayList("native:"+i);
            if(info==null||paths==null||deps==null||nativeNames==null)throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_UNKNOWN");
            if(info.getType()!=SharedLibraryInfo.TYPE_BUILTIN)throw new UnsupportedOperationException("ISOLATED_DYNAMIC_SHARED_LIBRARY_NOT_IMPLEMENTED");
            if(info.getLongVersion()!=described.getLong("version:"+i,Long.MIN_VALUE)||!owner.getPackageName().equals(described.getString("owner:"+i))||owner.getLongVersionCode()!=described.getLong("ownerVersion:"+i,Long.MIN_VALUE))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_IDENTITY_CHANGED");
            // A node's native exports are its dependencies' plus its own name when it carries no code.
            Set<String> expected=new LinkedHashSet<>();for(int dep:deps){if(dep<0||dep>=i)throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_ORDER_REJECTED");expected.addAll(natives.get(dep));}
            if(paths.isEmpty())expected.add(name);if(!expected.equals(new LinkedHashSet<>(nativeNames)))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_NATIVE_EXPORTS_CHANGED");natives.add(expected);
            var hashes=new ArrayList<String>();long[] sizes=new long[paths.size()];
            for(int p=0;p<paths.size();p++){
                String path=paths.get(p);RuntimeSharedLibraries.requireBuiltinPath(path);File file=new File(path);
                // A built-in APK library also contributes its own JNI directory, which this child does not model.
                if(!path.endsWith(".jar"))throw new UnsupportedOperationException("ISOLATED_PLATFORM_APK_LIBRARY_NOT_IMPLEMENTED");
                if(!file.isFile()||!file.getCanonicalPath().equals(path)||file.length()<=0||file.length()>MAX_LIBRARY_BYTES)throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_FILE_REJECTED");
                sizes[p]=file.length();hashes.add(digest(file,sizes[p]));code=true;
            }
            wire.putStringArrayList("paths:"+i,new ArrayList<>(paths));wire.putStringArrayList("hashes:"+i,hashes);wire.putLongArray("sizes:"+i,sizes);wire.putIntArray("deps:"+i,deps.clone());wire.putStringArrayList("native:"+i,new ArrayList<>(nativeNames));
            wire.putString("owner:"+i,owner.getPackageName());wire.putLong("version:"+i,info.getLongVersion());wire.putLong("ownerVersion:"+i,owner.getLongVersionCode());
        }
        Set<String> rootNames=new HashSet<>();LinkedHashSet<String> rootNatives=new LinkedHashSet<>();
        for(int root:roots){if(root<0||root>=names.size()||!rootNames.add(names.get(root))||!declared.containsKey(names.get(root)))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_ROOT_REJECTED");rootNatives.addAll(natives.get(root));}
        // A required available library is never dropped; an available optional Java library is
        // loaded by Android too. Only an optional native declaration naming a non-native
        // library may be absent, which the public PM cannot distinguish.
        for(var entry:declared.entrySet())if(available.containsKey(entry.getKey())&&!rootNames.contains(entry.getKey())&&(entry.getValue()||javaDeclarations.contains(entry.getKey())))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_OMITTED:"+entry.getKey());
        if(!new ArrayList<>(rootNatives).equals(described.getStringArrayList("nativeNames")))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_NATIVE_EXPORTS_CHANGED");
        if(!rootNatives.isEmpty()&&install.artifacts.nativeFiles.isEmpty())throw new UnsupportedOperationException("ISOLATED_PLATFORM_NATIVE_LIBRARY_WITHOUT_GUEST_NATIVE_NOT_IMPLEMENTED");
        var after=new ArrayList<String>();if(code){Set<String> configured=configuredAfter();for(String name:names)if(configured.contains(name))after.add(name);}
        wire.putStringArrayList("after",after);wire.putStringArrayList("nativeNames",new ArrayList<>(rootNatives));
        shape(wire);return new Plan(wire);
    }
    private static Set<String> configuredAfter(){
        Resources system=Resources.getSystem();int id=system.getIdentifier("config_sharedLibrariesLoadedAfterApp","array","android");
        if(id==0)throw new IllegalStateException("PLATFORM_LIBRARY_ORDER_CONFIG_MISSING");return Set.copyOf(Arrays.asList(system.getStringArray(id)));
    }
    static String[] files(Plan plan){
        if(plan==null)return null;LinkedHashSet<String> files=new LinkedHashSet<>();
        for(int i=0;i<plan.wire.getStringArrayList("names").size();i++)files.addAll(plan.wire.getStringArrayList("paths:"+i));
        return files.isEmpty()?null:files.toArray(String[]::new);
    }
    private static int shape(Bundle wire){
        var names=wire.getStringArrayList("names");var after=wire.getStringArrayList("after");int[] roots=wire.getIntArray("roots");var nativeNames=wire.getStringArrayList("nativeNames");
        if(!KIND.equals(wire.getString("kind"))||names==null||names.isEmpty()||names.size()>256||new HashSet<>(names).size()!=names.size()||after==null||!names.containsAll(after)||roots==null||roots.length==0||nativeNames==null)throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_SHAPE_REJECTED");
        for(int root:roots)if(root<0||root>=names.size())throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_SHAPE_REJECTED");
        for(int i=0;i<names.size();i++){
            var paths=wire.getStringArrayList("paths:"+i);var hashes=wire.getStringArrayList("hashes:"+i);long[] sizes=wire.getLongArray("sizes:"+i);int[] deps=wire.getIntArray("deps:"+i);var natives=wire.getStringArrayList("native:"+i);
            if(paths==null||hashes==null||sizes==null||deps==null||natives==null||hashes.size()!=paths.size()||sizes.length!=paths.size())throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_SHAPE_REJECTED");
            for(int dep:deps)if(dep<0||dep>=i)throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_ORDER_REJECTED");
            for(int p=0;p<paths.size();p++){RuntimeSharedLibraries.requireBuiltinPath(paths.get(p));if(!paths.get(p).endsWith(".jar")||hashes.get(p)==null||!hashes.get(p).matches("[0-9a-f]{64}")||sizes[p]<=0||sizes[p]>MAX_LIBRARY_BYTES)throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_FILE_REJECTED");}
        }
        return names.size();
    }
    private static String digest(File file,long expected)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];long total=0;int count;
        try(var input=new FileInputStream(file)){while((count=input.read(bytes))!=-1){total+=count;if(total>expected)throw new SecurityException("PLATFORM_LIBRARY_SIZE_CHANGED");sha.update(bytes,0,count);}}
        if(total!=expected)throw new SecurityException("PLATFORM_LIBRARY_SIZE_CHANGED");return HexFormat.of().formatHex(sha.digest());
    }
    /** Child: every file is an exact, read-only system publication; nothing is resolved here. */
    static Loaders open(Bundle wire,ClassLoader boot,String search,String permitted,int targetSdk)throws Exception{
        if(wire==null)return new Loaders(List.of(),List.of(),List.of());
        int count=shape(wire);var names=wire.getStringArrayList("names");Set<String> after=new HashSet<>(wire.getStringArrayList("after"));
        ClassLoader[] loaders=new ClassLoader[count];
        for(int i=0;i<count;i++){
            var paths=wire.getStringArrayList("paths:"+i);var hashes=wire.getStringArrayList("hashes:"+i);long[] sizes=wire.getLongArray("sizes:"+i);
            for(int p=0;p<paths.size();p++){if(!new File(paths.get(p)).getCanonicalPath().equals(paths.get(p))||!digest(new File(paths.get(p)),sizes[p]).equals(hashes.get(p)))throw new SecurityException("ISOLATED_PLATFORM_LIBRARY_CHANGED");}
            if(paths.isEmpty())continue;
            List<ClassLoader> first=new ArrayList<>(),last=new ArrayList<>();partition(wire.getIntArray("deps:"+i),names,after,loaders,first,last);
            loaders[i]=ClassLoaderNamespaceAccess.create(String.join(File.pathSeparator,paths),search,permitted,boot,targetSdk,wire.getStringArrayList("native:"+i),first,last);
        }
        List<ClassLoader> first=new ArrayList<>(),last=new ArrayList<>();partition(wire.getIntArray("roots"),names,after,loaders,first,last);
        return new Loaders(List.copyOf(first),List.copyOf(last),List.copyOf(wire.getStringArrayList("nativeNames")));
    }
    private static void partition(int[] indices,List<String> names,Set<String> after,ClassLoader[] loaders,List<ClassLoader> first,List<ClassLoader> last){
        for(int index:indices)if(loaders[index]!=null)(after.contains(names.get(index))?last:first).add(loaders[index]);
    }
    private RuntimeIsolatedLibraries(){}
}
