package dev.tripoder.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.system.Os;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Explicit legacy-project import. Only the serialized controller authority may call mutation methods. */
public final class ProjectBindings {
    public static final long MAX_FILE_BYTES=16L*1024*1024, MAX_TOTAL_BYTES=64L*1024*1024;
    public static final int MAX_FILES=512, MAX_DIRECTORIES=512, MAX_DEPTH=32;
    private final RuntimeStore store;
    private final RuntimeCatalog catalog;
    private final ApkStore apks;
    private final ExecutionContract contract;
    private final File root;

    public ProjectBindings(Context host,RuntimeStore store,RuntimeCatalog catalog,ApkStore apks,ExecutionContract contract){
        this.store=Objects.requireNonNull(store);this.catalog=Objects.requireNonNull(catalog);
        this.apks=Objects.requireNonNull(apks);this.contract=Objects.requireNonNull(contract);
        root=new File(host.getFilesDir(),"runtime");
    }
    public static final class SourceFile {
        public final String path,sha256;
        public final long size;
        private SourceFile(String path,long size,String hash){this.path=path;this.size=size;sha256=hash;}
    }
    public static final class Snapshot {
        public final String digest,packageName,projectId;
        public final List<SourceFile> files;
        public final List<String> directories;
        public final long totalBytes;
        private final File source;
        private Snapshot(File source,String digest,String pkg,String id,List<SourceFile> files,List<String> directories,long total){
            this.source=source;this.digest=digest;packageName=pkg;projectId=id;
            this.files=List.copyOf(files);this.directories=List.copyOf(directories);totalBytes=total;
        }
    }
    public static final class Reference {
        public final String projectId,space,packageName,generation,sourceDigest,dataId,operation;
        public final File snapshot;
        private Reference(String id,String space,String pkg,String generation,String digest,File snapshot,String dataId,String operation){
            projectId=id;this.space=space;packageName=pkg;this.generation=generation;sourceDigest=digest;
            this.snapshot=snapshot;this.dataId=dataId;this.operation=operation;
        }
        private String result()throws Exception{
            return new JSONObject().put("projectId",projectId).put("space",space).put("package",packageName)
                .put("generation",generation).put("sourceSha",sourceDigest).put("snapshot",snapshot.toString())
                .put("dataId",dataId).put("operation",operation).toString();
        }
    }

    /** Reads only the exact caller-supplied tree. No app discovery, implicit export or fixture installation. */
    public static Snapshot inspectSource(File source)throws Exception{
        Objects.requireNonNull(source,"source");
        if(Files.isSymbolicLink(source.toPath()))throw rejected("MIGRATION_SOURCE_SYMLINK");
        File canonical=source.getCanonicalFile();
        if(!Files.isDirectory(canonical.toPath(),LinkOption.NOFOLLOW_LINKS))throw rejected("MIGRATION_SOURCE_NOT_DIRECTORY");
        List<SourceFile> files=new ArrayList<>();List<String> directories=new ArrayList<>();long[] total={0};
        scan(canonical,"",0,files,directories,total);
        files.sort(Comparator.comparing(f->f.path));Collections.sort(directories);
        SourceFile metadata=null;for(SourceFile file:files){if(file.path.equals("project.json"))metadata=file;if(file.path.equals("runtime"))throw rejected("MIGRATION_RUNTIME_NOT_DIRECTORY");}
        if(metadata==null)throw rejected("MIGRATION_PROJECT_METADATA_MISSING");
        if(metadata.size>65536)throw rejected("MIGRATION_PROJECT_METADATA_LIMIT");
        byte[] bytes=readBounded(new File(canonical,"project.json"),65536);
        if(!hash(bytes).equals(metadata.sha256))throw rejected("MIGRATION_SOURCE_CHANGED");
        String json=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JSONTokener tokens=new JSONTokener(json);Object parsed=tokens.nextValue();
        if(!(parsed instanceof JSONObject)||tokens.nextClean()!=0)throw rejected("MIGRATION_PROJECT_METADATA_INVALID");
        JSONObject project=(JSONObject)parsed;
        if(!(project.get("schema") instanceof Number)||((Number)project.get("schema")).doubleValue()!=1.0)throw rejected("MIGRATION_SCHEMA_UNSUPPORTED");
        if(!(project.get("id") instanceof String)||!(project.getJSONObject("app").get("packageName") instanceof String))throw rejected("MIGRATION_PROJECT_METADATA_INVALID");
        String id=projectId(project.getString("id")),pkg=packageName(project.getJSONObject("app").getString("packageName"));
        // The runnable/source fields are legacy metadata, never evidence of an installed application.
        return new Snapshot(canonical,treeDigest(files,directories),pkg,id,files,directories,total[0]);
    }
    private static void scan(File directory,String prefix,int depth,List<SourceFile> files,List<String> directories,long[] total)throws Exception{
        if(depth>MAX_DEPTH)throw rejected("MIGRATION_DEPTH_LIMIT");
        if(Files.isSymbolicLink(directory.toPath()))throw rejected("MIGRATION_SOURCE_SYMLINK");
        File[] children=directory.listFiles();if(children==null)throw rejected("MIGRATION_DIRECTORY_READ_FAILED");
        Arrays.sort(children,Comparator.comparing(File::getName));
        for(File child:children){
            String name=child.getName();if(name.contains("\\")||name.equals(".")||name.equals(".."))throw rejected("MIGRATION_PATH_UNSUPPORTED");
            String path=prefix+name;if(path.getBytes(StandardCharsets.UTF_8).length>1024)throw rejected("MIGRATION_PATH_LIMIT");
            BasicFileAttributes before=Files.readAttributes(child.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(before.isSymbolicLink())throw rejected("MIGRATION_SOURCE_SYMLINK");
            if(before.isDirectory()){
                if(directories.size()>=MAX_DIRECTORIES)throw rejected("MIGRATION_DIRECTORY_LIMIT");
                directories.add(path);scan(child,path+"/",depth+1,files,directories,total);
            }else if(before.isRegularFile()){
                if(files.size()>=MAX_FILES)throw rejected("MIGRATION_FILE_COUNT_LIMIT");
                if(before.size()>MAX_FILE_BYTES)throw rejected("MIGRATION_FILE_LIMIT");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
                try(InputStream in=Files.newInputStream(child.toPath(),LinkOption.NOFOLLOW_LINKS)){
                    byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){count+=n;
                        if(count>MAX_FILE_BYTES)throw rejected("MIGRATION_FILE_LIMIT");
                        if(total[0]+count>MAX_TOTAL_BYTES)throw rejected("MIGRATION_TOTAL_LIMIT");digest.update(buffer,0,n);}
                }
                BasicFileAttributes after=Files.readAttributes(child.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!after.isRegularFile()||count!=before.size()||count!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw rejected("MIGRATION_SOURCE_CHANGED");
                total[0]+=count;files.add(new SourceFile(path,count,HexFormat.of().formatHex(digest.digest())));
            }else throw rejected("MIGRATION_SPECIAL_FILE_UNSUPPORTED");
        }
    }
    private static String treeDigest(List<SourceFile> files,List<String> directories)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeUTF("tripoder-project-tree-v1");out.writeInt(directories.size());
            for(String dir:directories)out.writeUTF(dir);out.writeInt(files.size());
            for(SourceFile file:files){out.writeUTF(file.path);out.writeLong(file.size);out.writeUTF(file.sha256);}
        }
        return hash(bytes.toByteArray());
    }

    /** expectedSourceDigest is the reviewed inspectSource result, not a digest invented during migration. */
    public synchronized Reference migrate(String operation,String id,File source,String space,String pkg,
            String expectedGeneration,String expectedSourceDigest,ApkStore.FaultPoint fault)throws Exception{
        projectId(id);RuntimeFiles.id(space);packageName(pkg);RuntimeFiles.id(expectedGeneration);digest(expectedSourceDigest);
        Objects.requireNonNull(source,"source");Objects.requireNonNull(fault,"fault");
        String path=source.toPath().toAbsolutePath().normalize().toString();
        String request="project-migration-v1"+part(id)+part(path)+part(space)+part(pkg)+part(expectedGeneration)+part(expectedSourceDigest);
        var admission=contract.accept(operation,"runtime-controller",ApkStore.textDigest(request));
        if(!admission.created){
            if(admission.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+admission.operation.state);
            Reference prior=reference(id);if(!operation.equals(prior.operation))throw rejected("MIGRATION_OPERATION_REFERENCE_MISMATCH");
            resolve(id);return prior;
        }
        contract.start(operation);
        File stage=null;boolean published=false,transactionStarted=false;Exception failure=null;
        try{
            catalog.requireSpace(space);catalog.requireUnused(space,pkg);requireUnbound(id,space,pkg);
            ApkStore.BundleInstall installed=apks.get(space,pkg);
            if(!installed.generation.equals(expectedGeneration))throw rejected("MIGRATION_INSTALL_GENERATION_CONFLICT");
            requireAbsentData(installed.data);
            Snapshot input=inspectSource(source);
            if(!input.digest.equals(expectedSourceDigest))throw rejected("MIGRATION_SOURCE_DIGEST_MISMATCH");
            if(!input.packageName.equals(pkg))throw rejected("MIGRATION_PACKAGE_MISMATCH");
            if(!input.projectId.equals(id))throw rejected("MIGRATION_PROJECT_ID_MISMATCH");
            File canonicalRoot=root.getCanonicalFile();
            if(within(input.source,canonicalRoot)||within(canonicalRoot,input.source))throw rejected("MIGRATION_SOURCE_OVERLAPS_RUNTIME");
            String snapshotId=UUID.randomUUID().toString(),dataId=UUID.randomUUID().toString();
            File snapshot=new File(root,"projects/"+id+"/"+snapshotId),data=new File(root,"data/"+space+"/"+pkg+"/"+dataId);
            stage=new File(root,"staging/migration-"+UUID.randomUUID());ownedMkdir(stage);
            File stageProject=new File(stage,"project"),stageData=new File(stage,"data");
            copySource(input,stageProject);copyRuntimeData(input,stageProject,stageData);
            requireSame(input,inspectSource(stageProject));requireSame(input,inspectSource(source));
            RuntimeFiles.sync(stage);fault.at("MIGRATION_AFTER_COPY");
            ownedMkdir(snapshot.getParentFile());ownedMkdir(data.getParentFile());requireAbsent(snapshot);requireAbsent(data);
            Os.rename(stageProject.toString(),snapshot.toString());published=true;sealSnapshot(snapshot);
            Os.rename(stageData.toString(),data.toString());RuntimeFiles.sync(data);RuntimeFiles.sync(stage);
            RuntimeFiles.sync(snapshot.getParentFile());RuntimeFiles.sync(data.getParentFile());
            fault.at("MIGRATION_AFTER_PUBLISH");
            Reference ref=new Reference(id,space,pkg,expectedGeneration,input.digest,snapshot,dataId,operation);
            var db=store.getWritableDatabase();transactionStarted=true;db.beginTransaction();
            try{
                catalog.requireUnusedMetadata(space,pkg);requireUnbound(id,space,pkg);
                ApkStore.Row current=apks.row(space,pkg);
                if(current==null||!"COMMITTED".equals(current.state)||!expectedGeneration.equals(current.generation)||!current.dataId.equals(installed.data.getName()))throw rejected("MIGRATION_INSTALL_GENERATION_CONFLICT");
                requireAbsentData(installed.data);requireSame(input,inspectSource(snapshot));requireSame(input,inspectSource(source));assertSealed(snapshot);
                ContentValues installValues=new ContentValues();installValues.put("dataId",dataId);
                if(db.update("installs",installValues,"space=? AND package=? AND generation=? AND dataId=? AND state='COMMITTED'",new String[]{space,pkg,expectedGeneration,current.dataId})!=1)throw rejected("MIGRATION_INSTALL_GENERATION_CONFLICT");
                ContentValues row=new ContentValues();row.put("id",id);row.put("space",space);row.put("package",pkg);row.put("generation",expectedGeneration);
                row.put("sourceSha",input.digest);row.put("snapshot",snapshot.toString());row.put("state","COMMITTED");row.put("operation",operation);
                db.insertOrThrow("project_refs",null,row);contract.succeed(operation,ref.result());
                fault.at("MIGRATION_BEFORE_COMMIT");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            fault.at("MIGRATION_AFTER_COMMIT");return reference(id);
        }catch(Exception error){
            failure=error;
            try{var op=contract.query(operation);if(op!=null&&op.state==ExecutionContract.OperationState.RUNNING){
                if(transactionStarted||published)contract.markUnknown(operation,"MIGRATION_PERSISTENCE_REQUIRES_RECOVERY");
                else contract.fail(operation,"MIGRATION_REJECTED");
            }}catch(Exception journal){error.addSuppressed(journal);}throw error;
        }finally{
            if(stage!=null&&Files.exists(stage.toPath(),LinkOption.NOFOLLOW_LINKS))try{RuntimeFiles.deleteTree(stage);}catch(Exception cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    public synchronized ApkStore.BundleInstall resolve(String id)throws Exception{
        Reference ref=reference(id);ApkStore.BundleInstall install=apks.get(ref.space,ref.packageName);
        if(!ref.generation.equals(install.generation)||!ref.dataId.equals(install.data.getName()))throw rejected("MIGRATION_REFERENCE_STALE");
        Snapshot snapshot=inspectSource(ref.snapshot);
        if(!ref.sourceDigest.equals(snapshot.digest)||!ref.packageName.equals(snapshot.packageName)||!id.equals(snapshot.projectId))throw rejected("MIGRATION_SNAPSHOT_CORRUPT");
        assertSealed(ref.snapshot);
        if(Files.isSymbolicLink(install.data.toPath())||!Files.isDirectory(install.data.toPath(),LinkOption.NOFOLLOW_LINKS))throw rejected("MIGRATION_DATA_MISSING_OR_UNSAFE");
        return install;
    }
    private Reference reference(String id)throws Exception{
        projectId(id);
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT kind FROM project_refs WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!"MIGRATED".equals(c.getString(0)))throw rejected("MIGRATION_REFERENCE_KIND_MISMATCH");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT space,package,generation,sourceSha,snapshot,state,operation FROM project_refs WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())throw rejected("PROJECT_NOT_BOUND");
            if(!"COMMITTED".equals(c.getString(5)))throw rejected("MIGRATION_REFERENCE_STALE");
            String operation=c.getString(6);var op=contract.query(operation);
            if(op==null||op.state!=ExecutionContract.OperationState.SUCCEEDED)throw rejected("MIGRATION_REFERENCE_OPERATION_NOT_SUCCEEDED");
            JSONObject result=new JSONObject(op.result);String dataId=RuntimeFiles.id(result.getString("dataId"));
            Reference ref=new Reference(id,c.getString(0),c.getString(1),c.getString(2),c.getString(3),new File(c.getString(4)),dataId,operation);
            requireSnapshotPath(id,ref.snapshot);
            if(!id.equals(result.getString("projectId"))||!ref.space.equals(result.getString("space"))||!ref.packageName.equals(result.getString("package"))||!ref.generation.equals(result.getString("generation"))||!ref.sourceDigest.equals(result.getString("sourceSha"))||!ref.snapshot.toString().equals(result.getString("snapshot"))||!operation.equals(result.getString("operation")))throw rejected("MIGRATION_REFERENCE_RECEIPT_MISMATCH");
            return ref;
        }
    }
    private void requireUnbound(String id,String space,String pkg)throws Exception{
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM project_refs WHERE id=? OR (space=? AND package=?)",new String[]{id,space,pkg})){
            if(c.moveToFirst())throw rejected("MIGRATION_BINDING_ALREADY_EXISTS");
        }
    }
    private static void requireAbsentData(File data)throws Exception{
        if(Files.exists(data.toPath(),LinkOption.NOFOLLOW_LINKS))throw rejected("MIGRATION_TARGET_DATA_EXISTS");
        if(!Files.notExists(data.toPath(),LinkOption.NOFOLLOW_LINKS))throw rejected("MIGRATION_TARGET_DATA_UNREADABLE");
    }
    private static void requireAbsent(File file)throws Exception{
        if(!Files.notExists(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw rejected("MIGRATION_DESTINATION_EXISTS_OR_UNREADABLE");
    }
    private void ownedMkdir(File directory)throws Exception{
        Path base=root.toPath().toAbsolutePath().normalize(),target=directory.toPath().toAbsolutePath().normalize();
        if(!target.startsWith(base))throw rejected("MIGRATION_DESTINATION_OUTSIDE_RUNTIME");
        RuntimeFiles.mkdir(root);Path current=base;
        for(Path part:base.relativize(target)){current=current.resolve(part);RuntimeFiles.mkdir(current.toFile());Os.chmod(current.toString(),0700);RuntimeFiles.sync(current.getParent().toFile());}
    }
    private static void copySource(Snapshot input,File destination)throws Exception{
        requireAbsent(destination);RuntimeFiles.mkdir(destination);Os.chmod(destination.toString(),0700);
        for(String path:input.directories){File directory=new File(destination,path);RuntimeFiles.mkdir(directory);Os.chmod(directory.toString(),0700);}
        for(SourceFile entry:input.files){
            File source=new File(input.source,entry.path),target=new File(destination,entry.path);
            try(InputStream in=Files.newInputStream(source.toPath(),LinkOption.NOFOLLOW_LINKS)){RuntimeFiles.write(in,target,MAX_FILE_BYTES);}
            if(target.length()!=entry.size||!ApkStore.digest(target).equals(entry.sha256))throw rejected("MIGRATION_SOURCE_CHANGED");
        }
        syncTree(destination);
    }
    private static void copyRuntimeData(Snapshot input,File immutableSource,File destination)throws Exception{
        requireAbsent(destination);RuntimeFiles.mkdir(destination);Os.chmod(destination.toString(),0700);
        for(SourceFile entry:input.files)if(entry.path.equals("runtime"))throw rejected("MIGRATION_RUNTIME_NOT_DIRECTORY");
        for(String path:input.directories)if(path.startsWith("runtime/")){File directory=new File(destination,path.substring(8));RuntimeFiles.mkdir(directory);Os.chmod(directory.toString(),0700);}
        for(SourceFile entry:input.files)if(entry.path.startsWith("runtime/")){
            File target=new File(destination,entry.path.substring(8));
            try(InputStream in=Files.newInputStream(new File(immutableSource,entry.path).toPath(),LinkOption.NOFOLLOW_LINKS)){RuntimeFiles.write(in,target,MAX_FILE_BYTES);}
            if(target.length()!=entry.size||!ApkStore.digest(target).equals(entry.sha256))throw rejected("MIGRATION_DATA_COPY_CORRUPT");
            Os.chmod(target.toString(),0600);
            try(var channel=java.nio.channels.FileChannel.open(target.toPath(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        }
        syncTree(destination);
    }
    private static void syncTree(File directory)throws Exception{for(File child:RuntimeFiles.children(directory))if(child.isDirectory())syncTree(child);RuntimeFiles.sync(directory);}
    private static void sealSnapshot(File directory)throws Exception{
        for(File child:RuntimeFiles.children(directory)){if(child.isDirectory())sealSnapshot(child);else Os.chmod(child.toString(),0400);}
        Os.chmod(directory.toString(),0555);RuntimeFiles.sync(directory);
    }
    private static void assertSealed(File file)throws Exception{
        if(Files.isSymbolicLink(file.toPath())||(Os.lstat(file.toString()).st_mode&0222)!=0)throw rejected("MIGRATION_SNAPSHOT_NOT_IMMUTABLE");
        if(file.isDirectory())for(File child:RuntimeFiles.children(file))assertSealed(child);
    }
    private static void requireSame(Snapshot a,Snapshot b)throws Exception{if(!a.digest.equals(b.digest)||!a.packageName.equals(b.packageName))throw rejected("MIGRATION_SOURCE_CHANGED");}

    /** Run after process and operation recovery. ApkStore.recover owns unreferenced dataId cleanup. */
    public synchronized void recover()throws Exception{
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation FROM sessions WHERE state NOT IN ('DEAD','FAILED')",null)){
            if(c.moveToFirst())throw rejected("MIGRATION_RECOVERY_WITH_LIVE_PROCESS");
        }
        for(File stage:RuntimeFiles.children(new File(root,"staging")))if(stage.getName().startsWith("migration-"))RuntimeFiles.deleteTree(stage);
        Set<String> referenced=new HashSet<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,snapshot FROM project_refs WHERE kind='MIGRATED'",null)){
            while(c.moveToNext()){File snapshot=new File(c.getString(1));requireSnapshotPath(c.getString(0),snapshot);referenced.add(snapshot.toPath().toAbsolutePath().normalize().toString());}
        }
        File projects=new File(root,"projects");
        for(File project:RuntimeFiles.children(projects)){
            if(Files.isSymbolicLink(project.toPath()))throw rejected("MIGRATION_PROJECT_DIRECTORY_SYMLINK");
            for(File snapshot:RuntimeFiles.children(project))if(!referenced.contains(snapshot.toPath().toAbsolutePath().normalize().toString()))RuntimeFiles.deleteTree(snapshot);
            if(RuntimeFiles.children(project).isEmpty())RuntimeFiles.deleteTree(project);
        }
        if(projects.exists())RuntimeFiles.sync(projects);
    }
    private void requireSnapshotPath(String id,File snapshot)throws Exception{
        projectId(id);RuntimeFiles.id(snapshot.getName());
        File parent=new File(root,"projects/"+id);
        if(!snapshot.toPath().toAbsolutePath().normalize().getParent().equals(parent.toPath().toAbsolutePath().normalize()))throw rejected("MIGRATION_SNAPSHOT_PATH_INVALID");
        if(Files.isSymbolicLink(parent.toPath())||Files.isSymbolicLink(snapshot.toPath()))throw rejected("MIGRATION_SNAPSHOT_PATH_UNSAFE");
    }
    private static boolean within(File child,File parent){return child.toPath().startsWith(parent.toPath());}
    private static byte[] readBounded(File file,int max)throws Exception{
        try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw rejected("MIGRATION_PROJECT_METADATA_LIMIT");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String projectId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("MIGRATION_PROJECT_ID_INVALID");return id;}
    private static String packageName(String pkg){if(pkg==null||pkg.length()>255||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))throw new IllegalArgumentException("MIGRATION_PACKAGE_NAME_INVALID");return pkg;}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("MIGRATION_DIGEST_INVALID");}
    private static String part(String value){return value.length()+":"+value;}
    private static IOException rejected(String reason){return new IOException(reason);}
}
