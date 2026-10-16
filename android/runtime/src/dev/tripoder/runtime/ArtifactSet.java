package dev.tripoder.runtime;

import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.*;

/** Verified base/split APK set, before publication. No database, platform installer or runner.
 * Caller owns an exclusive staging directory; same-UID hostile mutation is outside this boundary.
 * Published descriptor SHA must come from the committed catalog, never from the artifact itself.
 */
public final class ArtifactSet {
    public static final String DESCRIPTOR="descriptor.json";
    public static final int PLATFORM=37,MAX_PARTS=64;
    public static final long MAX_APK_BYTES=1024L*1024*1024,MAX_SET_BYTES=2L*1024*1024*1024;
    public final File directory,base;
    public final String packageName,signerSha,descriptorSha,descriptorJson;
    public final long versionCode;
    public final List<Part> parts;
    public final List<FileRecord> nativeFiles,files;
    public final List<ApkManifest.Component> components;
    public final List<String> processes,signerDigests;
    public final ApkManifest baseManifest;

    public static final class FileRecord {
        public final String path,sha256;
        public final long size;
        FileRecord(String path,String sha256,long size){this.path=path;this.sha256=sha256;this.size=size;}
        Map<String,Object> json(){Map<String,Object> o=new TreeMap<>();o.put("path",path);o.put("sha256",sha256);o.put("size",size);return o;}
    }
    public static final class Part {
        public final File file;
        public final String path,sha256;
        public final long size;
        public final ApkManifest manifest;
        public final List<FileRecord> entries;
        private final ArtifactSetZip.Scan scan;
        private final List<String> signers;
        Part(File file,ArtifactSetZip.Scan scan,ApkManifest manifest,List<String> signers)throws Exception{this.file=file;this.path=file.getName();this.sha256=digest(file);this.size=Files.size(file.toPath());this.scan=scan;this.manifest=manifest;this.signers=signers;entries=Collections.unmodifiableList(scan.entries);}
        Map<String,Object> json(){Map<String,Object> o=new TreeMap<>();o.put("path",path);o.put("sha256",sha256);o.put("size",size);o.put("manifest",manifest.json());List<Object> es=new ArrayList<>();for(FileRecord e:entries)es.add(e.json());o.put("entries",es);return o;}
    }
    private ArtifactSet(File directory,List<Part> parts,List<FileRecord> nativeFiles,List<ApkManifest.Component> components,List<String> processes)throws Exception{
        this.directory=directory;this.parts=Collections.unmodifiableList(parts);this.base=parts.get(0).file;baseManifest=parts.get(0).manifest;packageName=baseManifest.packageName;versionCode=baseManifest.versionCode;
        signerDigests=Collections.unmodifiableList(parts.get(0).signers);signerSha=signerDigests.size()==1?signerDigests.get(0):hash(String.join("\n",signerDigests).getBytes(StandardCharsets.UTF_8));
        this.nativeFiles=Collections.unmodifiableList(nativeFiles);this.components=Collections.unmodifiableList(components);this.processes=Collections.unmodifiableList(processes);
        List<FileRecord> all=new ArrayList<>();for(Part p:parts)all.add(new FileRecord(p.path,p.sha256,p.size));all.addAll(nativeFiles);all.sort(Comparator.comparing(r->r.path));files=Collections.unmodifiableList(all);
        Map<String,Object> o=new TreeMap<>();o.put("schema",1);o.put("platform",PLATFORM);o.put("abi","arm64-v8a");o.put("packageName",packageName);o.put("versionCode",versionCode);o.put("signerSha",signerSha);o.put("signerDigests",signerDigests);
        List<Object> ps=new ArrayList<>();for(Part p:parts)ps.add(p.json());o.put("parts",ps);List<Object> fs=new ArrayList<>();for(FileRecord f:files)fs.add(f.json());o.put("files",fs);List<Object> cs=new ArrayList<>();for(ApkManifest.Component c:components)cs.add(c.json());o.put("components",cs);o.put("processes",processes);
        descriptorJson=json(o);if(descriptorJson.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw reject("DESCRIPTOR_LIMIT");descriptorSha=hash(descriptorJson.getBytes(StandardCharsets.UTF_8));
    }
    /** Input: a private directory containing only regular root-level APKs. Output is sealed in place. */
    public static ArtifactSet materialize(File staged)throws Exception{
        Path root=root(staged);List<Path> apks=apkInputs(root,false);ArtifactSet set=inspect(root,apks);
        long extracted=0;for(FileRecord file:set.nativeFiles)extracted+=file.size;
        requireSpace(staged,extracted+64L*1024*1024);
        Path lib=root.resolve("lib");Files.createDirectory(lib);
        for(Part p:set.parts)for(Map.Entry<String,FileRecord> e:p.scan.libraries.entrySet())ArtifactSetZip.extract(p.file,e.getValue().path,lib.resolve(e.getKey()),e.getValue());
        Path descriptor=root.resolve(DESCRIPTOR);try(OutputStream out=Files.newOutputStream(descriptor,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.write(set.descriptorJson.getBytes(StandardCharsets.UTF_8));}
        for(FileRecord f:set.files){Path path=root.resolve(f.path);syncFile(path);seal(path,false);}syncFile(descriptor);seal(descriptor,false);seal(lib,true);syncDirectory(lib);seal(root,true);syncDirectory(root);
        // Every written file was hashed during extraction. The publication owner
        // performs a full independent verification after the atomic directory move.
        return set;
    }
    /** No writes. Revalidates signature, set graph, every ZIP byte and every extracted file. */
    public static ArtifactSet verify(File published,String descriptorSha)throws Exception{
        if(descriptorSha==null||!descriptorSha.matches("[0-9a-f]{64}"))throw reject("DESCRIPTOR_DIGEST_INVALID");
        Path root=root(published);Path descriptor=root.resolve(DESCRIPTOR);regular(descriptor);if(Files.size(descriptor)>16*1024*1024||!digest(descriptor.toFile()).equals(descriptorSha))throw reject("DESCRIPTOR_CORRUPT");
        ArtifactSet set=inspect(root,apkInputs(root,true));
        if(!set.descriptorSha.equals(descriptorSha)||!Arrays.equals(Files.readAllBytes(descriptor),set.descriptorJson.getBytes(StandardCharsets.UTF_8)))throw reject("DESCRIPTOR_CONTENT_MISMATCH");
        Set<String> expected=new TreeSet<>();expected.add(DESCRIPTOR);expected.add("lib");for(FileRecord r:set.files)expected.add(r.path);Set<String> actual=new TreeSet<>();
        try(var walk=Files.walk(root)){for(Iterator<Path> it=walk.iterator();it.hasNext();){Path path=it.next();if(path.equals(root))continue;String relative=root.relativize(path).toString().replace(File.separatorChar,'/');if(Files.isSymbolicLink(path))throw reject("ARTIFACT_SYMLINK");actual.add(relative);if(relative.equals("lib")){if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw reject("LIB_DIRECTORY_INVALID");}else regular(path);readonly(path);}}
        if(!actual.equals(expected))throw reject("ARTIFACT_FILE_SET_MISMATCH");readonly(root);
        for(FileRecord r:set.files){Path path=root.resolve(r.path);if(Files.size(path)!=r.size||!digest(path.toFile()).equals(r.sha256))throw reject("ARTIFACT_FILE_CORRUPT:"+r.path);}
        return set;
    }
    private static ArtifactSet inspect(Path root,List<Path> files)throws Exception{
        Map<String,Part> bySplit=new TreeMap<>();long bytes=0,expanded=0;
        for(Path file:files){regular(file);long size=Files.size(file);bytes+=size;if(size==0||size>MAX_APK_BYTES||bytes>MAX_SET_BYTES)throw reject("APK_BYTES_LIMIT");
            ArtifactSetZip.Scan scan=ArtifactSetZip.scan(file.toFile());expanded+=scan.expanded;if(expanded>ArtifactSetZip.MAX_EXPANDED)throw reject("SET_EXPANDED_LIMIT");
            ApkVerifier.Result verification=new ApkVerifier.Builder(file.toFile()).setMinCheckedPlatformVersion(PLATFORM).setMaxCheckedPlatformVersion(PLATFORM).build().verify();
            if(!verification.isVerified()||verification.containsErrors()||!(verification.isVerifiedUsingV2Scheme()||verification.isVerifiedUsingV3Scheme()||verification.isVerifiedUsingV31Scheme()||verification.isVerifiedUsingV32Scheme()))throw reject("APK_SIGNATURE_INVALID");
            List<String> signers=new ArrayList<>();for(X509Certificate cert:verification.getSignerCertificates())signers.add(hash(cert.getEncoded()));Collections.sort(signers);if(signers.isEmpty()||new HashSet<>(signers).size()!=signers.size())throw reject("APK_SIGNERS_INVALID");
            ApkManifest manifest=ApkManifest.parse(scan.manifests.get("AndroidManifest.xml"));Part part=new Part(file.toFile(),scan,manifest,signers);if(bySplit.put(manifest.splitName,part)!=null)throw reject("DUPLICATE_SPLIT");
        }
        Part base=bySplit.get("");if(base==null)throw reject("BASE_MISSING");
        if(base.manifest.featureSplit||!base.manifest.configForSplit.isEmpty()||!base.manifest.usesSplits.isEmpty())throw reject("BASE_DEPENDENCY_INVALID");
        Set<String> providedTypes=new HashSet<>();for(Part p:bySplit.values())providedTypes.addAll(p.manifest.splitTypes);
        List<Part> ordered=new ArrayList<>();Set<String> done=new HashSet<>(),visiting=new HashSet<>();
        for(Part p:bySplit.values()){
            if(!p.manifest.packageName.equals(base.manifest.packageName)||p.manifest.versionCode!=base.manifest.versionCode)throw reject("APK_SET_IDENTITY_MISMATCH");
            if(!p.signers.equals(base.signers))throw reject("APK_SET_SIGNER_MISMATCH");
            if(p!=base){
                if(p.manifest.application.attributes.containsKey("android:name")&&!p.manifest.applicationName.equals(base.manifest.applicationName))throw reject("SPLIT_APPLICATION_MISMATCH");
                if(p.manifest.application.attributes.containsKey("android:process")&&!p.manifest.applicationProcess.equals(base.manifest.applicationProcess))throw reject("SPLIT_APPLICATION_PROCESS_MISMATCH");
            }
            if(p.manifest.splitRequired&&bySplit.size()==1)throw reject("REQUIRED_SPLIT_MISSING");
            if(!providedTypes.containsAll(p.manifest.requiredSplitTypes))throw reject("REQUIRED_SPLIT_TYPE_MISSING");
            if(p!=base&&!p.manifest.configForSplit.isEmpty()&&p.manifest.featureSplit)throw reject("FEATURE_CONFIG_CONFLICT");
            visit(p,bySplit,done,visiting,ordered);
        }
        List<FileRecord> libs=new ArrayList<>();Set<String> names=new HashSet<>();Map<String,ApkManifest.Component> componentNames=new LinkedHashMap<>();List<ApkManifest.Component> components=new ArrayList<>();TreeSet<String> processes=new TreeSet<>();processes.add(base.manifest.applicationProcess);
        for(Part p:ordered){for(Map.Entry<String,FileRecord> entry:p.scan.libraries.entrySet()){if(!names.add(entry.getKey()))throw reject("NATIVE_LIBRARY_COLLISION");FileRecord r=entry.getValue();libs.add(new FileRecord("lib/"+entry.getKey(),r.sha256,r.size));}
            Map<String,String> attrs=new TreeMap<>(base.manifest.application.attributes);attrs.putAll(p.manifest.application.attributes);
            ApkManifest.Element effectiveApp=new ApkManifest.Element("application","",attrs,List.of());
            for(ApkManifest.Component declared:p.manifest.components){
                ApkManifest.Component c=new ApkManifest.Component(declared.declaration,base.manifest,effectiveApp);
                ApkManifest.Component prior=componentNames.putIfAbsent(c.type+":"+c.name,c);
                if(prior!=null){
                    if(prior.json().equals(c.json()))continue; // Raw declarations remain in each signed manifest.
                    if(!c.type.equals("provider"))throw reject("COMPONENT_COLLISION");
                    // Preserve all distinct Provider declarations for authority resolution.
                    // The process publication layer must validate its supported identity model.
                }
                components.add(c);processes.add(c.process);
            }}
        Set<String> activities=new HashSet<>();for(ApkManifest.Component c:components)if(c.type.equals("activity"))activities.add(c.name);
        for(ApkManifest.Component c:components)if(c.type.equals("activity-alias")&&!activities.contains(c.targetActivity))throw reject("ALIAS_TARGET_MISSING");
        for(Part p:ordered)for(ApkManifest.Element group:p.manifest.application.children)if(group.name.equals("processes"))for(ApkManifest.Element process:group.children)if(process.name.equals("process"))processes.add(ApkManifest.processName(base.manifest.packageName,ApkManifest.text(process.android("name",null),"PROCESS")));
        if(libs.isEmpty()&&ordered.stream().anyMatch(p->p.scan.containsNative))throw reject("ABI_OR_LIBRARY_UNSUPPORTED");
        libs.sort(Comparator.comparing(r->r.path));return new ArtifactSet(root.toFile(),ordered,libs,components,new ArrayList<>(processes));
    }
    private static void visit(Part p,Map<String,Part> all,Set<String> done,Set<String> visiting,List<Part> ordered)throws IOException{
        String n=p.manifest.splitName;if(done.contains(n))return;if(!visiting.add(n))throw reject("SPLIT_DEPENDENCY_CYCLE");
        List<String> deps=new ArrayList<>(p.manifest.usesSplits);if(!n.isEmpty())deps.add("");if(!p.manifest.configForSplit.isEmpty())deps.add(p.manifest.configForSplit.equals("base")?"":p.manifest.configForSplit);
        for(String dep:deps){Part target=all.get(dep);if(target==null)throw reject("SPLIT_DEPENDENCY_MISSING:"+dep);if(!dep.isEmpty()&&!target.manifest.featureSplit)throw reject("SPLIT_DEPENDENCY_NOT_FEATURE");visit(target,all,done,visiting,ordered);}
        visiting.remove(n);done.add(n);ordered.add(p);
    }
    private static List<Path> apkInputs(Path root,boolean published)throws Exception{
        List<Path> apks=new ArrayList<>();try(DirectoryStream<Path> children=Files.newDirectoryStream(root)){for(Path path:children){if(Files.isSymbolicLink(path))throw reject("ARTIFACT_SYMLINK");String name=path.getFileName().toString();if(published&&(name.equals("lib")||name.equals(DESCRIPTOR)))continue;if(!name.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,149}\\.apk"))throw reject("STAGE_FILE_UNSUPPORTED:"+name);regular(path);apks.add(path);}}
        if(apks.isEmpty()||apks.size()>MAX_PARTS)throw reject("APK_PART_COUNT");apks.sort(Comparator.comparing(Path::toString));return apks;
    }
    private static Path root(File directory)throws IOException{if(directory==null)throw reject("ARTIFACT_DIRECTORY_INVALID");Path root=directory.toPath().toAbsolutePath().normalize();if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw reject("ARTIFACT_DIRECTORY_INVALID");return root;}
    static void regular(Path file)throws IOException{if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw reject("ARTIFACT_NOT_REGULAR");}
    static void seal(Path path,boolean directory)throws IOException{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"r-xr-xr-x":"r--r--r--"));}
    static void readonly(Path path)throws IOException{Set<PosixFilePermission> p=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);if(p.contains(PosixFilePermission.OWNER_WRITE)||p.contains(PosixFilePermission.GROUP_WRITE)||p.contains(PosixFilePermission.OTHERS_WRITE))throw reject("ARTIFACT_NOT_IMMUTABLE");}
    static void syncFile(Path file)throws IOException{regular(file);try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}}
    static void syncDirectory(Path path)throws IOException{try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}}
    public static String digest(File file)throws Exception{regular(file.toPath());MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest());}
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
    static IOException reject(String reason){return new IOException(reason);}
    static void requireSpace(File directory,long required)throws IOException{if(required<0||directory.getUsableSpace()<required)throw reject("ARTIFACT_DISK_BUDGET");}
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?>){StringJoiner out=new StringJoiner(",","{","}");for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.add(json(e.getKey().toString())+":"+json(e.getValue()));return out.toString();}
        if(value instanceof Iterable<?>){StringJoiner out=new StringJoiner(",","[","]");for(Object v:(Iterable<?>)value)out.add(json(v));return out.toString();}
        String s=value.toString();StringBuilder out=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32||Character.isSurrogate(c))out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();
    }
}
