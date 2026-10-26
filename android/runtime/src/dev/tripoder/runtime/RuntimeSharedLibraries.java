package dev.tripoder.runtime;

import android.content.pm.*;
import android.content.res.Resources;
import dev.tripoder.selfhook.ClassLoaderNamespaceAccess;
import dev.tripoder.selfhook.SharedLibraryFrameworkAccess;
import java.io.File;
import java.util.*;

/** One physical-PM dependency graph owns metadata, code paths and defining loaders. */
final class RuntimeSharedLibraries {
    final ClassLoader parent;final String[] files;final List<String> nativeNames;final List<SharedLibraryInfo> metadata;
    final List<ClassLoader> before,after;final String nativeSearch;
    final Graph graph;private final ApplicationInfo application;
    static final class Node {
        final SharedLibraryInfo info;final List<String> paths;final List<Node> dependencies;final Set<String> nativeNames;final List<Object> shape;ClassLoader loader;
        Node(SharedLibraryInfo info,List<String> paths,List<Node> dependencies,Set<String> nativeNames,List<Object> shape){this.info=info;this.paths=paths;this.dependencies=dependencies;this.nativeNames=nativeNames;this.shape=shape;}
    }
    /** Physical-PM resolution only. It creates no loader, namespace or search path, so the
     * authority can resolve the exact graph an isolated child must reproduce. */
    static final class Graph {
        final List<Node> roots=new ArrayList<>();final List<SharedLibraryInfo> selected=new ArrayList<>();final LinkedHashSet<String> nativeNames=new LinkedHashSet<>();
        /** Dependencies always precede their dependents. */
        final Map<String,Node> nodes=new LinkedHashMap<>();final Set<String> afterNames;
        private final Set<String> visiting=new HashSet<>();private final PackageManager platform;
        Graph(PackageManager platform,ArtifactSet artifacts)throws Exception{
            this.platform=platform;
            Map<String,Boolean> declared=new LinkedHashMap<>();Set<String> nativeDeclarations=new HashSet<>();
            for(var part:artifacts.parts)for(var element:part.manifest.application.children)if(element.name.equals("uses-library")||element.name.equals("uses-native-library")){
                String name=ApkManifest.text(element.android("name",null),"SHARED_LIBRARY_NAME"),flag=element.android("required","true");
                if(!Set.of("true","false","1","0").contains(flag))throw new IllegalStateException("SHARED_LIBRARY_REQUIRED_FLAG_UNRESOLVED");
                if(element.name.equals("uses-native-library"))nativeDeclarations.add(name);
                declared.merge(name,flag.equals("true")||flag.equals("1"),(left,right)->left||right);
            }
            Map<String,SharedLibraryInfo> available=new HashMap<>();
            if(!declared.isEmpty())for(var library:platform.getSharedLibraries(0))if(library.getType()==SharedLibraryInfo.TYPE_BUILTIN||library.getType()==SharedLibraryInfo.TYPE_DYNAMIC){
                SharedLibraryInfo prior=available.putIfAbsent(library.getName(),library);
                if(prior!=null&&declared.containsKey(library.getName()))throw new SecurityException("PLATFORM_LIBRARY_NAME_AMBIGUOUS");
            }
            for(var entry:declared.entrySet()){
                var library=available.get(entry.getKey());
                if(library==null||nativeDeclarations.contains(entry.getKey())&&!SharedLibraryFrameworkAccess.isNative(library)){if(entry.getValue())throw new IllegalStateException("REQUIRED_PLATFORM_LIBRARY_MISSING:"+entry.getKey());continue;}
                Node node=collect(library);roots.add(node);selected.add(library);nativeNames.addAll(node.nativeNames);
            }
            afterNames=nodes.values().stream().anyMatch(node->!node.paths.isEmpty())?configuredAfter():Set.of();
            android.util.Log.i("TripoderLibraries","declared="+declared.keySet()+";nodes="+nodes.size()+";nativeNames="+nativeNames);
        }
        private static Set<String> configuredAfter(){
            Resources system=Resources.getSystem();int id=system.getIdentifier("config_sharedLibrariesLoadedAfterApp","array","android");
            if(id==0)throw new IllegalStateException("PLATFORM_LIBRARY_ORDER_CONFIG_MISSING");return Set.copyOf(Arrays.asList(system.getStringArray(id)));
        }
        private Node collect(SharedLibraryInfo library)throws Exception{
            String name=Objects.requireNonNull(library.getName());List<SharedLibraryInfo> declaredDependencies=SharedLibraryFrameworkAccess.dependencies(library);
            boolean nativeType=SharedLibraryFrameworkAccess.isNative(library);List<String> rawPaths=SharedLibraryFrameworkAccess.paths(library);var owner=library.getDeclaringPackage();
            List<String> edges=new ArrayList<>();for(var dependency:declaredDependencies){var pkg=dependency.getDeclaringPackage();edges.add(dependency.getName()+":"+dependency.getType()+":"+dependency.getLongVersion()+":"+pkg.getPackageName()+":"+pkg.getLongVersionCode());}
            List<Object> shape=List.of(library.getType(),library.getLongVersion(),owner.getPackageName(),owner.getLongVersionCode(),nativeType,rawPaths,List.copyOf(edges));Node known=nodes.get(name);
            if(known!=null){if(!known.shape.equals(shape))throw new SecurityException("PLATFORM_LIBRARY_DEPENDENCY_IDENTITY_CONFLICT");for(var dependency:declaredDependencies)collect(dependency);return known;}
            if(nodes.size()+visiting.size()>=256)throw new SecurityException("PLATFORM_LIBRARY_GRAPH_LIMIT");
            if(!visiting.add(name))throw new SecurityException("PLATFORM_LIBRARY_DEPENDENCY_CYCLE");
            List<Node> dependencies=new ArrayList<>();LinkedHashSet<String> nativeNames=new LinkedHashSet<>();
            for(var dependency:declaredDependencies){Node child=collect(dependency);dependencies.add(child);nativeNames.addAll(child.nativeNames);}
            List<String> paths;
            if(nativeType){if(library.getType()!=SharedLibraryInfo.TYPE_BUILTIN)throw new SecurityException("DYNAMIC_NATIVE_PLATFORM_LIBRARY_REJECTED");nativeNames.add(name);paths=List.of();}
            else{paths=canonicalPaths(rawPaths);if(library.getType()==SharedLibraryInfo.TYPE_DYNAMIC)requireDynamicOwner(library,paths);else for(String path:paths)requireBuiltinPath(path);}
            Node node=new Node(library,paths,List.copyOf(dependencies),Collections.unmodifiableSet(nativeNames),shape);nodes.put(name,node);visiting.remove(name);return node;
        }
        private void requireDynamicOwner(SharedLibraryInfo library,List<String> paths)throws Exception{
            VersionedPackage declaration=library.getDeclaringPackage();PackageInfo owner=platform.getPackageInfo(declaration.getPackageName(),PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            ApplicationInfo info=Objects.requireNonNull(owner.applicationInfo);
            if(owner.getLongVersionCode()!=declaration.getLongVersionCode()||(info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0||owner.signingInfo==null||owner.signingInfo.getApkContentsSigners().length==0)throw new SecurityException("PLATFORM_LIBRARY_OWNER_REJECTED");
            LinkedHashSet<String> authorized=new LinkedHashSet<>();authorized.add(new File(info.sourceDir).getCanonicalPath());if(info.splitSourceDirs!=null)for(String path:info.splitSourceDirs)authorized.add(new File(path).getCanonicalPath());
            if(!paths.get(0).equals(new File(info.sourceDir).getCanonicalPath())||!authorized.containsAll(paths))throw new SecurityException("PLATFORM_LIBRARY_OWNER_PATH_MISMATCH");
        }
    }
    RuntimeSharedLibraries(PackageManager platform,ArtifactSet artifacts,ClassLoader boot,ApplicationInfo application,String executionNativeDirectory)throws Exception{
        this.application=application;parent=Objects.requireNonNull(boot);graph=new Graph(platform,artifacts);
        LinkedHashSet<String> paths=new LinkedHashSet<>();for(Node node:graph.nodes.values())paths.addAll(node.paths);
        // Absolute JNI loads use ApplicationInfo's verified execution copy.
        // DT_NEEDED and findLibrary must resolve that SAME backing file: the
        // sealed archive's different inode would create a second DSO whose
        // JNI_OnLoad state was never initialized by the absolute load.
        LinkedHashSet<String> search=new LinkedHashSet<>();search.add(Objects.requireNonNull(executionNativeDirectory));
        // Android adds JNI locations only for authenticated shared APKs, in root
        // then dependency order. Native sonames remain namespace exports.
        Set<Node> searched=Collections.newSetFromMap(new IdentityHashMap<>());for(Node root:graph.roots)nativePaths(root,searched,search);
        nativeSearch=String.join(File.pathSeparator,search);
        List<ClassLoader> first=new ArrayList<>(),last=new ArrayList<>();partition(graph.roots,first,last);
        before=List.copyOf(first);after=List.copyOf(last);metadata=List.copyOf(graph.selected);nativeNames=List.copyOf(graph.nativeNames);files=paths.toArray(String[]::new);
        android.util.Log.i("TripoderLibraries","selectedPaths="+paths+";before="+before.size()+";after="+after.size());
    }
    static List<String> canonicalPaths(List<String> paths)throws Exception{
        if(paths.isEmpty())throw new SecurityException("PLATFORM_LIBRARY_PATH_MISSING");LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String path:paths){if(path==null||!new File(path).isAbsolute())throw new SecurityException("PLATFORM_LIBRARY_PATH_REJECTED");File file=new File(path);if(!file.isFile()||!result.add(file.getCanonicalPath()))throw new SecurityException("PLATFORM_LIBRARY_PATH_REJECTED");}
        return List.copyOf(result);
    }
    static void requireBuiltinPath(String path){if(List.of("/system/","/system_ext/","/product/","/vendor/","/apex/").stream().noneMatch(path::startsWith))throw new SecurityException("PLATFORM_LIBRARY_PATH_REJECTED");}
    private void partition(List<Node> dependencies,List<ClassLoader> first,List<ClassLoader> last){
        for(Node node:dependencies)if(!node.paths.isEmpty())(graph.afterNames.contains(node.info.getName())?last:first).add(loader(node));
    }
    private ClassLoader loader(Node node){
        if(node.loader!=null)return node.loader;List<ClassLoader> first=new ArrayList<>(),last=new ArrayList<>();partition(node.dependencies,first,last);
        node.loader=ClassLoaderNamespaceAccess.create(String.join(File.pathSeparator,node.paths),nativeSearch,application.dataDir,parent,application.targetSdkVersion,List.copyOf(node.nativeNames),first,last);return node.loader;
    }
    private static void nativePaths(Node node,Set<Node> seen,Set<String> search){if(!seen.add(node))return;for(String path:node.paths)if(path.endsWith(".apk"))search.add(path+"!/lib/arm64-v8a");for(Node child:node.dependencies)nativePaths(child,seen,search);}
    boolean owns(ClassLoader candidate){if(candidate==null)return false;for(Node node:graph.nodes.values())if(node.loader==candidate)return true;return false;}
}
