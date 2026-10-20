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
    /** One manifest intent-filter as plain data (no framework types), so it can be read where the platform's IntentFilter is unavailable. */
    public static final class FilterSpec {
        public final int priority;public final List<String> actions=new ArrayList<>(),categories=new ArrayList<>();
        /** scheme, host, port, path, pathPrefix, pathPattern, pathAdvancedPattern, pathSuffix, mimeType (null when absent). */
        public final List<String[]> data=new ArrayList<>();
        FilterSpec(int priority){this.priority=priority;}
    }
    /** Intent filters by "type/name". Present only when the descriptor was published with them (the lite descriptor is). */
    public volatile Map<String,List<FilterSpec>> filterSpecs=Map.of();
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
        Part(File file,String path,String sha256,long size,ApkManifest manifest,List<FileRecord> entries,List<String> signers){this.file=file;this.path=path;this.sha256=sha256;this.size=size;this.scan=null;this.manifest=manifest;this.signers=signers;this.entries=Collections.unmodifiableList(entries);}
        Map<String,Object> json(){Map<String,Object> o=new TreeMap<>();o.put("path",path);o.put("sha256",sha256);o.put("size",size);o.put("manifest",manifest.json());List<Object> es=new ArrayList<>();for(FileRecord e:entries)es.add(e.json());o.put("entries",es);return o;}
    }
    private ArtifactSet(File directory,List<Part> parts,List<FileRecord> nativeFiles,List<FileRecord> dexFiles,List<ApkManifest.Component> components,List<String> processes)throws Exception{
        this.directory=directory;this.parts=Collections.unmodifiableList(parts);this.base=parts.get(0).file;baseManifest=parts.get(0).manifest;packageName=baseManifest.packageName;versionCode=baseManifest.versionCode;
        signerDigests=Collections.unmodifiableList(parts.get(0).signers);signerSha=signerDigests.size()==1?signerDigests.get(0):hash(String.join("\n",signerDigests).getBytes(StandardCharsets.UTF_8));
        this.nativeFiles=Collections.unmodifiableList(nativeFiles);this.components=Collections.unmodifiableList(components);this.processes=Collections.unmodifiableList(processes);
        List<FileRecord> all=new ArrayList<>();for(Part p:parts)all.add(new FileRecord(p.path,p.sha256,p.size));all.addAll(nativeFiles);all.addAll(dexFiles);all.sort(Comparator.comparing(r->r.path));files=Collections.unmodifiableList(all);
        Map<String,Object> o=new TreeMap<>();o.put("schema",1);o.put("platform",PLATFORM);o.put("abi","arm64-v8a");o.put("packageName",packageName);o.put("versionCode",versionCode);o.put("signerSha",signerSha);o.put("signerDigests",signerDigests);
        List<Object> ps=new ArrayList<>();for(Part p:parts)ps.add(p.json());o.put("parts",ps);List<Object> fs=new ArrayList<>();for(FileRecord f:files)fs.add(f.json());o.put("files",fs);List<Object> cs=new ArrayList<>();for(ApkManifest.Component c:components)cs.add(c.json());o.put("components",cs);o.put("processes",processes);
        descriptorJson=json(o);if(descriptorJson.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw reject("DESCRIPTOR_LIMIT");descriptorSha=hash(descriptorJson.getBytes(StandardCharsets.UTF_8));
    }
    private ArtifactSet(File directory,String descriptorJson,String descriptorSha,List<Part> parts,List<FileRecord> nativeFiles,List<FileRecord> dexFiles,List<ApkManifest.Component> components,List<String> processes){
        this.directory=directory;this.descriptorJson=descriptorJson;this.descriptorSha=descriptorSha;this.parts=Collections.unmodifiableList(parts);this.base=parts.get(0).file;baseManifest=parts.get(0).manifest;packageName=baseManifest.packageName;versionCode=baseManifest.versionCode;
        signerDigests=Collections.unmodifiableList(parts.get(0).signers);signerSha=signerDigests.size()==1?signerDigests.get(0):hashUnchecked(String.join("\n",signerDigests).getBytes(StandardCharsets.UTF_8));
        this.nativeFiles=Collections.unmodifiableList(nativeFiles);this.components=Collections.unmodifiableList(components);this.processes=Collections.unmodifiableList(processes);
        List<FileRecord> all=new ArrayList<>();for(Part p:parts)all.add(new FileRecord(p.path,p.sha256,p.size));all.addAll(nativeFiles);all.addAll(dexFiles);all.sort(Comparator.comparing(r->r.path));files=Collections.unmodifiableList(all);
    }
    private static String hashUnchecked(byte[] bytes){try{return hash(bytes);}catch(Exception error){throw new IllegalStateException(error);}}
    static String dexExtractedPath(int partIndex,String entryPath){return "dex/"+partIndex+"-"+entryPath;}
    /** Input: a private directory containing only regular root-level APKs. Output is sealed in place. */
    public static ArtifactSet materialize(File staged)throws Exception{
        Path root=root(staged);List<Path> apks=apkInputs(root,false);ArtifactSet set=inspect(root,apks);
        long extracted=0;for(FileRecord file:set.files)if(file.path.startsWith("lib/")||file.path.startsWith("dex/"))extracted+=file.size;
        requireSpace(staged,extracted+64L*1024*1024);
        Path lib=root.resolve("lib");Files.createDirectory(lib);
        for(Part p:set.parts)for(Map.Entry<String,FileRecord> e:p.scan.libraries.entrySet())ArtifactSetZip.extract(p.file,e.getValue().path,lib.resolve(e.getKey()),e.getValue());
        Path dexDir=root.resolve("dex");Files.createDirectory(dexDir);
        for(int p=0;p<set.parts.size();p++){Part part=set.parts.get(p);for(FileRecord entry:part.entries)if(entry.path.indexOf('/')<0&&entry.path.endsWith(".dex"))ArtifactSetZip.extract(part.file,entry.path,root.resolve(dexExtractedPath(p,entry.path)),entry);}
        Path descriptor=root.resolve(DESCRIPTOR);try(OutputStream out=Files.newOutputStream(descriptor,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.write(set.descriptorJson.getBytes(StandardCharsets.UTF_8));}
        for(FileRecord f:set.files){Path path=root.resolve(f.path);syncFile(path);seal(path,false);}syncFile(descriptor);seal(descriptor,false);seal(lib,true);syncDirectory(lib);seal(dexDir,true);syncDirectory(dexDir);seal(root,true);syncDirectory(root);
        // Every written file was hashed during extraction. The publication owner
        // performs a full independent verification after the atomic directory move.
        return set;
    }
    /** Fast worker reconstruction from a sealed artifact directory already verified by authority. */
    public static ArtifactSet loadVerified(File published,String descriptorSha)throws Exception{
        if(descriptorSha==null||!descriptorSha.matches("[0-9a-f]{64}"))throw reject("DESCRIPTOR_DIGEST_INVALID");
        Path root=root(published);Path descriptor=root.resolve(DESCRIPTOR);regular(descriptor);
        if(Files.size(descriptor)>16*1024*1024)throw reject("DESCRIPTOR_CORRUPT");
        byte[] rawBytes=Files.readAllBytes(descriptor);
        if(!hash(rawBytes).equals(descriptorSha))throw reject("DESCRIPTOR_CORRUPT");
        String rawJson=new String(rawBytes,StandardCharsets.UTF_8);
        ArtifactSet set=parse(root,rawJson,descriptorSha);
        return finishLoad(root,set);
    }
    /** Worker path: authority already verified this tree; the worker re-checks only the tree stamp, never the descriptor bytes. */
    public static ArtifactSet loadTrusted(File published,String descriptorSha,String liteJson,String stampDigest)throws Exception{
        if(descriptorSha==null||!descriptorSha.matches("[0-9a-f]{64}"))throw reject("DESCRIPTOR_DIGEST_INVALID");
        Path root=root(published);
        if(!ArtifactIntegrityCache.stampDigest(root.toFile()).equals(stampDigest))throw reject("ARTIFACT_STAMP_MISMATCH");
        return parse(root,liteJson,descriptorSha);
    }
    /** Descriptor without per-resource records: workers need only top-level DEX entries. */
    private String lite;
    public synchronized String liteJson()throws Exception{
        if(lite==null){
            org.json.JSONObject o=new org.json.JSONObject(descriptorJson);org.json.JSONArray ps=o.getJSONArray("parts");
            for(int i=0;i<ps.length();i++){
                org.json.JSONObject part=ps.getJSONObject(i);org.json.JSONArray all=part.getJSONArray("entries"),kept=new org.json.JSONArray();
                for(int j=0;j<all.length();j++){String path=all.getJSONObject(j).getString("path");if(path.indexOf('/')<0&&path.endsWith(".dex"))kept.put(all.getJSONObject(j));}
                part.put("entries",kept);
            }
            o.put("filters",filtersJson());
            lite=o.toString();
        }
        return lite;
    }
    /** Publishes the lite descriptor as a digest-named side file (the 64 KB wire limit cannot carry it) plus the tree stamp the worker must reproduce. */
    void putTrustedLoad(android.os.Bundle bundle)throws Exception{
        String lite=liteJson();byte[] bytes=lite.getBytes(StandardCharsets.UTF_8);String sha=hash(bytes);
        File dir=new File(directory.getParentFile().getParentFile(),"launch-lite");
        File file=new File(dir,descriptorSha+"-"+sha+".json");
        if(!file.isFile()||file.length()!=bytes.length){
            if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("LAUNCH_LITE_DIRECTORY");
            File tmp=new File(dir,file.getName()+"."+android.os.Process.myPid()+"."+Thread.currentThread().getId()+".tmp");
            try(OutputStream out=new FileOutputStream(tmp)){out.write(bytes);}
            if(!tmp.renameTo(file)){tmp.delete();throw new IOException("LAUNCH_LITE_PUBLISH");}
        }
        bundle.putString("descriptorLiteFile",file.toString());bundle.putString("descriptorLiteSha",sha);
        bundle.putString("artifactStamp",ArtifactIntegrityCache.stampDigest(directory));
    }
    /** Worker side of putTrustedLoad: bytes are hashed once and the same bytes are parsed. */
    public static ArtifactSet loadTrusted(File published,String descriptorSha,String liteFile,String liteSha,String stampDigest)throws Exception{
        byte[] bytes=Files.readAllBytes(new File(liteFile).toPath());
        if(!hash(bytes).equals(liteSha))throw reject("DESCRIPTOR_LITE_CORRUPT");
        return loadTrusted(published,descriptorSha,new String(bytes,StandardCharsets.UTF_8),stampDigest);
    }
    private static Map<String,List<FilterSpec>> readFilters(android.util.JsonReader r)throws Exception{
        Map<String,List<FilterSpec>> out=new HashMap<>();r.beginArray();
        while(r.hasNext()){
            String type=null,name=null;List<FilterSpec> specs=new ArrayList<>();r.beginObject();
            while(r.hasNext()){
                switch(r.nextName()){
                    case "t"->type=r.nextString();
                    case "n"->name=r.nextString();
                    case "f"->{
                        r.beginArray();
                        while(r.hasNext()){
                            FilterSpec spec=null;List<String> actions=new ArrayList<>(),categories=new ArrayList<>();List<String[]> data=new ArrayList<>();int priority=0;
                            r.beginObject();
                            while(r.hasNext()){
                                switch(r.nextName()){
                                    case "p"->priority=r.nextInt();
                                    case "a"->{r.beginArray();while(r.hasNext())actions.add(r.nextString());r.endArray();}
                                    case "c"->{r.beginArray();while(r.hasNext())categories.add(r.nextString());r.endArray();}
                                    case "d"->{r.beginArray();while(r.hasNext()){String[] row=new String[9];r.beginArray();for(int i=0;i<9&&r.hasNext();i++){if(r.peek()==android.util.JsonToken.NULL){r.nextNull();row[i]=null;}else row[i]=r.nextString();}while(r.hasNext())r.skipValue();r.endArray();data.add(row);}r.endArray();}
                                    default->r.skipValue();
                                }
                            }
                            r.endObject();spec=new FilterSpec(priority);spec.actions.addAll(actions);spec.categories.addAll(categories);spec.data.addAll(data);specs.add(spec);
                        }
                        r.endArray();
                    }
                    default->r.skipValue();
                }
            }
            r.endObject();if(type!=null&&name!=null)out.put(type+"/"+name,specs);
        }
        r.endArray();return out;
    }
    /** The intent-filters of every component, taken from the full manifest tree (which only the authority has) for the lite descriptor. */
    private org.json.JSONArray filtersJson()throws Exception{
        org.json.JSONArray all=new org.json.JSONArray();
        Set<String> seen=new HashSet<>();
        for(Part part:parts)for(ApkManifest.Component component:part.manifest.components){
            if(!seen.add(component.type+"/"+component.name))continue;
            org.json.JSONArray filters=new org.json.JSONArray();
            for(ApkManifest.Element element:component.declaration.children){
                if(!element.name.equals("intent-filter"))continue;
                int priority=0;try{priority=Integer.parseInt(element.android("priority","0"));}catch(NumberFormatException ignored){}
                org.json.JSONArray actions=new org.json.JSONArray(),categories=new org.json.JSONArray(),data=new org.json.JSONArray();
                for(ApkManifest.Element child:element.children){
                    String value=child.android("name","");
                    if(child.name.equals("action")&&!value.isEmpty())actions.put(value);
                    else if(child.name.equals("category")&&!value.isEmpty())categories.put(value);
                    else if(child.name.equals("data")){
                        org.json.JSONArray row=new org.json.JSONArray();
                        for(String key:new String[]{"scheme","host","port","path","pathPrefix","pathPattern","pathAdvancedPattern","pathSuffix","mimeType"}){String v=child.android(key,null);row.put(v==null?org.json.JSONObject.NULL:v);}
                        data.put(row);
                    }
                }
                filters.put(new org.json.JSONObject().put("p",priority).put("a",actions).put("c",categories).put("d",data));
            }
            if(filters.length()>0)all.put(new org.json.JSONObject().put("t",component.type).put("n",component.name).put("f",filters));
        }
        return all;
    }
    private static ArtifactSet parse(Path root,String rawJson,String descriptorSha)throws Exception{
        Map<String,List<FilterSpec>> filters=Map.of();
        int schema=-1,platform=-1;String abi=null,topPkg=null,topSignerSha=null;long topVersion=-1;
        List<String> signers=new ArrayList<>();List<Part> parts=new ArrayList<>();List<FileRecord> nativeFiles=new ArrayList<>(),dexFiles=new ArrayList<>();List<ApkManifest.Component> components=new ArrayList<>();List<String> processes=new ArrayList<>();
        try(android.util.JsonReader r=new android.util.JsonReader(new StringReader(rawJson))){
            r.beginObject();
            while(r.hasNext()){
                switch(r.nextName()){
                    case "schema"->schema=r.nextInt();
                    case "platform"->platform=r.nextInt();
                    case "abi"->abi=r.nextString();
                    case "packageName"->topPkg=r.nextString();
                    case "versionCode"->topVersion=r.nextLong();
                    case "signerSha"->topSignerSha=r.nextString();
                    case "signerDigests"->{r.beginArray();while(r.hasNext())signers.add(r.nextString());r.endArray();}
                    case "parts"->{
                        r.beginArray();
                        while(r.hasNext()){
                            String path=null,sha=null;long size=-1;ApkManifest manifest=null;List<FileRecord> entries=new ArrayList<>();
                            r.beginObject();
                            while(r.hasNext()){
                                switch(r.nextName()){
                                    case "path"->path=r.nextString();
                                    case "sha256"->sha=r.nextString();
                                    case "size"->size=r.nextLong();
                                    case "manifest"->manifest=ApkManifest.fromJsonReader(r);
                                    case "entries"->{
                                        r.beginArray();
                                        while(r.hasNext()){
                                            String ep=null,es=null;long ez=-1;
                                            r.beginObject();
                                            while(r.hasNext()){
                                                switch(r.nextName()){
                                                    case "path"->{ep=r.nextString();}
                                                    case "sha256"->es=r.nextString();
                                                    case "size"->ez=r.nextLong();
                                                    default->r.skipValue();
                                                }
                                            }
                                            r.endObject();
                                            // Worker consumers read only top-level DEX; thousands of resource records are dead weight under interpretation.
                                            if(ep!=null&&ep.indexOf('/')<0&&ep.endsWith(".dex"))entries.add(new FileRecord(ep,es,ez));
                                        }
                                        r.endArray();
                                    }
                                    default->r.skipValue();
                                }
                            }
                            r.endObject();
                            parts.add(new Part(root.resolve(path).toFile(),path,sha,size,manifest,entries,signers));
                        }
                        r.endArray();
                    }
                    case "files"->{
                        r.beginArray();
                        while(r.hasNext()){
                            String fp=null,fs=null;long fz=-1;
                            r.beginObject();
                            while(r.hasNext()){
                                switch(r.nextName()){
                                    case "path"->fp=r.nextString();
                                    case "sha256"->fs=r.nextString();
                                    case "size"->fz=r.nextLong();
                                    default->r.skipValue();
                                }
                            }
                            r.endObject();
                            if(fp!=null&&fp.startsWith("lib/"))nativeFiles.add(new FileRecord(fp,fs,fz));
                            else if(fp!=null&&fp.startsWith("dex/"))dexFiles.add(new FileRecord(fp,fs,fz));
                        }
                        r.endArray();
                    }
                    case "components"->{
                        r.beginArray();
                        while(r.hasNext()){
                            String type=null,name=null,process=null,permission="",authorities="",targetActivity="";Boolean exported=null,enabled=null;ApkManifest.Element declaration=null;
                            r.beginObject();
                            while(r.hasNext()){
                                switch(r.nextName()){
                                    case "type"->type=r.nextString();
                                    case "name"->name=r.nextString();
                                    case "process"->process=r.nextString();
                                    case "permission"->permission=r.nextString();
                                    case "authorities"->authorities=r.nextString();
                                    case "targetActivity"->targetActivity=r.nextString();
                                    case "exported"->{if(r.peek()==android.util.JsonToken.NULL){r.nextNull();exported=null;}else exported=r.nextBoolean();}
                                    case "enabled"->{if(r.peek()==android.util.JsonToken.NULL){r.nextNull();enabled=null;}else enabled=r.nextBoolean();}
                                    case "declaration"->declaration=ApkManifest.componentElementFromJsonReader(r,0);
                                    default->r.skipValue();
                                }
                            }
                            r.endObject();
                            components.add(new ApkManifest.Component(declaration!=null?declaration:new ApkManifest.Element(type,"",Map.of(),List.of()),type,name,process,permission,authorities,targetActivity,exported,enabled));
                        }
                        r.endArray();
                    }
                    case "processes"->{r.beginArray();while(r.hasNext())processes.add(r.nextString());r.endArray();}
                    case "filters"->filters=readFilters(r);
                    default->r.skipValue();
                }
            }
            r.endObject();
        }
        if(schema!=1||platform!=PLATFORM||!"arm64-v8a".equals(abi)||parts.isEmpty())throw reject("DESCRIPTOR_CONTENT_MISMATCH");
        for(int i=0;i<parts.size();i++){Part p=parts.get(i);parts.set(i,new Part(p.file,p.path,p.sha256,p.size,p.manifest,p.entries,signers));}
        ArtifactSet set=new ArtifactSet(root.toFile(),rawJson,descriptorSha,parts,nativeFiles,dexFiles,components,processes);set.filterSpecs=filters;
        if(!set.packageName.equals(topPkg)||set.versionCode!=topVersion||!set.signerSha.equals(topSignerSha))throw reject("DESCRIPTOR_CONTENT_MISMATCH");
        return set;
    }
    private static ArtifactSet finishLoad(Path root,ArtifactSet set)throws Exception{
        Set<String> expected=new TreeSet<>();expected.add(DESCRIPTOR);expected.add("lib");if(set.files.stream().anyMatch(f->f.path.startsWith("dex/")))expected.add("dex");for(FileRecord r:set.files)expected.add(r.path);Set<String> actual=new TreeSet<>();
        try(var walk=Files.walk(root)){for(Iterator<Path> it=walk.iterator();it.hasNext();){Path path=it.next();if(path.equals(root))continue;String relative=root.relativize(path).toString().replace(File.separatorChar,'/');if(Files.isSymbolicLink(path))throw reject("ARTIFACT_SYMLINK");if(derivedOptimization(relative))continue;actual.add(relative);if(relative.equals("lib")||relative.equals("dex")){if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw reject("LIB_DIRECTORY_INVALID");}else regular(path);readonly(path);}}
        if(!actual.equals(expected))throw reject("ARTIFACT_FILE_SET_MISMATCH");readonly(root);
        for(FileRecord r:set.files){Path path=root.resolve(r.path);if(Files.size(path)!=r.size)throw reject("ARTIFACT_FILE_CORRUPT:"+r.path);}
        return set;
    }
    /** No writes. Revalidates signature, set graph, every ZIP byte and every extracted file. */
    public static ArtifactSet verify(File published,String descriptorSha)throws Exception{
        if(descriptorSha==null||!descriptorSha.matches("[0-9a-f]{64}"))throw reject("DESCRIPTOR_DIGEST_INVALID");
        Path root=root(published);Path descriptor=root.resolve(DESCRIPTOR);regular(descriptor);if(Files.size(descriptor)>16*1024*1024||!digest(descriptor.toFile()).equals(descriptorSha))throw reject("DESCRIPTOR_CORRUPT");
        ArtifactSet set=inspect(root,apkInputs(root,true));
        if(!set.descriptorSha.equals(descriptorSha)||!Arrays.equals(Files.readAllBytes(descriptor),set.descriptorJson.getBytes(StandardCharsets.UTF_8)))throw reject("DESCRIPTOR_CONTENT_MISMATCH");
        Set<String> expected=new TreeSet<>();expected.add(DESCRIPTOR);expected.add("lib");expected.add("dex");for(FileRecord r:set.files)expected.add(r.path);Set<String> actual=new TreeSet<>();
        try(var walk=Files.walk(root)){for(Iterator<Path> it=walk.iterator();it.hasNext();){Path path=it.next();if(path.equals(root))continue;String relative=root.relativize(path).toString().replace(File.separatorChar,'/');if(Files.isSymbolicLink(path))throw reject("ARTIFACT_SYMLINK");if(derivedOptimization(relative))continue;actual.add(relative);if(relative.equals("lib")||relative.equals("dex")){if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw reject("LIB_DIRECTORY_INVALID");}else regular(path);readonly(path);}}
        if(!actual.equals(expected))throw reject("ARTIFACT_FILE_SET_MISMATCH");readonly(root);
        List<java.util.concurrent.CompletableFuture<String>> checks=new ArrayList<>();
        for(FileRecord r:set.files){Path path=root.resolve(r.path);checks.add(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return Files.size(path)!=r.size||!digest(path.toFile()).equals(r.sha256)?r.path:null;}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}}));}
        for(var check:checks){String bad;try{bad=check.join();}catch(java.util.concurrent.CompletionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}if(bad!=null)throw reject("ARTIFACT_FILE_CORRUPT:"+bad);}
        return set;
    }
    private static ArtifactSet inspect(Path root,List<Path> files)throws Exception{
        Map<String,Part> bySplit=new TreeMap<>();long bytes=0,expanded=0;
        for(Path file:files){regular(file);long size=Files.size(file);bytes+=size;if(size==0||size>MAX_APK_BYTES||bytes>MAX_SET_BYTES)throw reject("APK_BYTES_LIMIT");
            // Signature verification and the full ZIP byte scan read the same immutable file independently; neither consumes the other's result.
            java.util.concurrent.CompletableFuture<ApkVerifier.Result> signature=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{return new ApkVerifier.Builder(file.toFile()).setMinCheckedPlatformVersion(PLATFORM).setMaxCheckedPlatformVersion(PLATFORM).build().verify();}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
            });
            ArtifactSetZip.Scan scan;ApkVerifier.Result verification;
            try{scan=ArtifactSetZip.scan(file.toFile());}catch(Exception error){signature.cancel(true);throw error;}
            try{verification=signature.join();}catch(java.util.concurrent.CompletionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
            expanded+=scan.expanded;if(expanded>ArtifactSetZip.MAX_EXPANDED)throw reject("SET_EXPANDED_LIMIT");
            if(!verification.isVerified()||verification.containsErrors()||!(verification.isVerifiedUsingV2Scheme()||verification.isVerifiedUsingV3Scheme()||verification.isVerifiedUsingV31Scheme()||verification.isVerifiedUsingV32Scheme()))throw reject("APK_SIGNATURE_INVALID");
            List<String> signers=new ArrayList<>();for(X509Certificate cert:verification.getSignerCertificates())signers.add(hash(cert.getEncoded()));Collections.sort(signers);if(signers.isEmpty()||new HashSet<>(signers).size()!=signers.size())throw reject("APK_SIGNERS_INVALID");
            ApkManifest manifest=ApkManifest.parse(scan.manifests.get("AndroidManifest.xml"),new ArscValues(file.toFile()));Part part=new Part(file.toFile(),scan,manifest,signers);if(bySplit.put(manifest.splitName,part)!=null)throw reject("DUPLICATE_SPLIT");
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
        List<FileRecord> libs=new ArrayList<>(),dexFiles=new ArrayList<>();Set<String> names=new HashSet<>();Map<String,ApkManifest.Component> componentNames=new LinkedHashMap<>();List<ApkManifest.Component> components=new ArrayList<>();TreeSet<String> processes=new TreeSet<>();processes.add(base.manifest.applicationProcess);
        for(int pIndex=0;pIndex<ordered.size();pIndex++){Part p=ordered.get(pIndex);for(Map.Entry<String,FileRecord> entry:p.scan.libraries.entrySet()){if(!names.add(entry.getKey()))throw reject("NATIVE_LIBRARY_COLLISION");FileRecord r=entry.getValue();libs.add(new FileRecord("lib/"+entry.getKey(),r.sha256,r.size));}
            for(FileRecord entry:p.entries)if(entry.path.indexOf('/')<0&&entry.path.endsWith(".dex"))dexFiles.add(new FileRecord(dexExtractedPath(pIndex,entry.path),entry.sha256,entry.size));
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
        libs.sort(Comparator.comparing(r->r.path));dexFiles.sort(Comparator.comparing(r->r.path));return new ArtifactSet(root.toFile(),ordered,libs,dexFiles,components,new ArrayList<>(processes));
    }
    private static void visit(Part p,Map<String,Part> all,Set<String> done,Set<String> visiting,List<Part> ordered)throws IOException{
        String n=p.manifest.splitName;if(done.contains(n))return;if(!visiting.add(n))throw reject("SPLIT_DEPENDENCY_CYCLE");
        List<String> deps=new ArrayList<>(p.manifest.usesSplits);if(!n.isEmpty())deps.add("");if(!p.manifest.configForSplit.isEmpty())deps.add(p.manifest.configForSplit.equals("base")?"":p.manifest.configForSplit);
        for(String dep:deps){Part target=all.get(dep);if(target==null)throw reject("SPLIT_DEPENDENCY_MISSING:"+dep);if(!dep.isEmpty()&&!target.manifest.featureSplit)throw reject("SPLIT_DEPENDENCY_NOT_FEATURE");visit(target,all,done,visiting,ordered);}
        visiting.remove(n);done.add(n);ordered.add(p);
    }
    private static List<Path> apkInputs(Path root,boolean published)throws Exception{
        List<Path> apks=new ArrayList<>();try(DirectoryStream<Path> children=Files.newDirectoryStream(root)){for(Path path:children){if(Files.isSymbolicLink(path))throw reject("ARTIFACT_SYMLINK");String name=path.getFileName().toString();if(published&&(name.equals("lib")||name.equals("dex")||name.equals(DESCRIPTOR)||derivedOptimization(name)))continue;if(!name.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,149}\\.apk"))throw reject("STAGE_FILE_UNSUPPORTED:"+name);regular(path);apks.add(path);}}
        if(apks.isEmpty()||apks.size()>MAX_PARTS)throw reject("APK_PART_COUNT");apks.sort(Comparator.comparing(Path::toString));return apks;
    }
    private static Path root(File directory)throws IOException{if(directory==null)throw reject("ARTIFACT_DIRECTORY_INVALID");Path root=directory.toPath().toAbsolutePath().normalize();if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw reject("ARTIFACT_DIRECTORY_INVALID");return root;}
    static void regular(Path file)throws IOException{if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw reject("ARTIFACT_NOT_REGULAR");}
    static void seal(Path path,boolean directory)throws IOException{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"r-xr-xr-x":"r--r--r--"));}
    static void readonly(Path path)throws IOException{Set<PosixFilePermission> p=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);if(p.contains(PosixFilePermission.OWNER_WRITE)||p.contains(PosixFilePermission.GROUP_WRITE)||p.contains(PosixFilePermission.OTHERS_WRITE))throw reject("ARTIFACT_NOT_IMMUTABLE:"+path.getFileName()+":"+PosixFilePermissions.toString(p));}
    /** ART writes optimization sidecars — an "oat" directory of .odex/.vdex/.art, plus *.prof/*.cur_prof
     *  profiles — next to a PathClassLoader's on-disk DEX at runtime. The guest loader is deliberately
     *  on-disk (so hot-fix frameworks like Tinker can inspect their own loader), so these derived caches
     *  appear inside the sealed tree after the first run. They are not part of the authenticated set —
     *  ART validates an oat against its DEX checksum before use — so the integrity walk tolerates them
     *  instead of bricking every relaunch with ARTIFACT_NOT_IMMUTABLE/ARTIFACT_FILE_SET_MISMATCH. */
    static boolean derivedOptimization(String relative){
        for(String segment:relative.split("/"))if(segment.equals("oat"))return true;
        return relative.endsWith(".odex")||relative.endsWith(".vdex")||relative.endsWith(".art")||relative.endsWith(".oat")||relative.endsWith(".prof")||relative.endsWith(".cur_prof");
    }
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
