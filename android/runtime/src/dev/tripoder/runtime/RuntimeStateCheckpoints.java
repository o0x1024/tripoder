package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Data-directory checkpoints for one installation (QA rollback).
 *
 * A checkpoint is a full copy of the installation's data root (the same root file_export calls "data") stored under
 * {@code <runtime>/checkpoints/<id>}. Restoring replaces the data root contents while keeping the installation's
 * dataId, so every authority record keyed by dataId (project bindings, notifications, component settings) stays valid.
 * Only the data root is captured: authority ledgers (jobs, alarms, pending intents, component enabled state) and
 * external storage are not part of a checkpoint.
 *
 * Both operations require that no process of the installation is allocated. While one is in progress the row is
 * CREATING/RESTORING, and {@link RuntimeStore#requireProcessStart} refuses to start the installation, so the long copy
 * runs outside the broker lock without racing a guest writer. Boot recovery finishes or discards interrupted work.
 */
final class RuntimeStateCheckpoints {
    static final int MAX_PER_INSTALL=8;
    static final long MAX_BYTES=256L*1024*1024;
    static final int MAX_ENTRIES=20000;
    private static final Set<String> ACTIVE=Collections.synchronizedSet(new HashSet<>());

    private RuntimeStateCheckpoints(){}

    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS state_checkpoints(id TEXT PRIMARY KEY,space TEXT NOT NULL,package TEXT NOT NULL,dataId TEXT NOT NULL,"
            +"installGeneration TEXT NOT NULL,label TEXT NOT NULL,state TEXT NOT NULL,entries INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,"
            +"digest TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,restored INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS state_checkpoints_install ON state_checkpoints(space,package)");
    }

    /** Process start gate: an installation whose data root is being captured or replaced must not run. */
    static void requireIdle(SQLiteDatabase db,String space,String pkg){
        try(Cursor c=db.rawQuery("SELECT id,state FROM state_checkpoints WHERE space=? AND package=? AND state IN ('CREATING','RESTORING') LIMIT 1",new String[]{space,pkg})){
            if(c.moveToFirst())throw new IllegalStateException("CHECKPOINT_IN_PROGRESS:"+c.getString(1)+":"+c.getString(0));
        }
    }

    private static File root(RuntimeBroker broker){return new File(broker.apks.root,"checkpoints");}

    /** Validate the exact current installation and that none of its processes is allocated. Caller holds the broker lock. */
    private static ApkStore.BundleInstall stoppedInstall(RuntimeBroker broker,String space,String pkg,String generation)throws Exception{
        RuntimeFiles.id(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);
        var row=broker.apks.row(space,pkg);
        if(row==null||!"COMMITTED".equals(row.state)||!generation.equals(row.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
        ApkStore.BundleInstall install=broker.apks.get(space,pkg);
        if(!install.generation.equals(generation)||!install.data.getName().equals(row.dataId))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
        for(var slot:broker.processSlots())if(slot.install.space.equals(space)&&slot.install.info.packageName.equals(pkg))
            throw new IllegalStateException("CHECKPOINT_PROCESS_RUNNING_STOP_FIRST");
        requireIdle(broker.store.getReadableDatabase(),space,pkg);
        return install;
    }

    static JSONObject createCheckpoint(RuntimeBroker broker,String space,String pkg,String generation,String label)throws Exception{
        if(label==null||label.length()>64||label.indexOf('\0')>=0)throw new IllegalArgumentException("CHECKPOINT_LABEL");
        String id=UUID.randomUUID().toString();File data;String dataId;
        synchronized(broker){
            ApkStore.BundleInstall install=stoppedInstall(broker,space,pkg,generation);
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM state_checkpoints WHERE space=? AND package=?",new String[]{space,pkg})){
                c.moveToFirst();if(c.getInt(0)>=MAX_PER_INSTALL)throw new IllegalStateException("CHECKPOINT_LIMIT:"+MAX_PER_INSTALL);
            }
            data=install.data;dataId=data.getName();
            ContentValues v=new ContentValues();v.put("id",id);v.put("space",space);v.put("package",pkg);v.put("dataId",dataId);v.put("installGeneration",generation);
            v.put("label",label);v.put("state","CREATING");v.put("created",System.currentTimeMillis());
            ACTIVE.add(id);broker.store.getWritableDatabase().insertOrThrow("state_checkpoints",null,v);
        }
        File target=new File(root(broker),id);
        try{
            RuntimeFiles.mkdir(root(broker));
            // An installation that never ran has no data root yet; its checkpoint is the empty state.
            if(!Files.exists(data.toPath(),LinkOption.NOFOLLOW_LINKS))RuntimeFiles.mkdir(data);
            Copy copy=copyTree(data.toPath(),target.toPath());
            synchronized(broker){
                ContentValues v=new ContentValues();v.put("state","READY");v.put("entries",copy.entries);v.put("bytes",copy.bytes);v.put("digest",copy.digest());
                if(broker.store.getWritableDatabase().update("state_checkpoints",v,"id=? AND state='CREATING'",new String[]{id})!=1)throw new IllegalStateException("CHECKPOINT_PERSIST_FAILED");
            }
            return describe(broker,id);
        }catch(Exception|Error failure){
            try{remove(target);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            synchronized(broker){broker.store.getWritableDatabase().delete("state_checkpoints","id=? AND state='CREATING'",new String[]{id});}
            throw failure;
        }finally{ACTIVE.remove(id);}
    }

    static JSONObject restoreCheckpoint(RuntimeBroker broker,String space,String pkg,String generation,String id)throws Exception{
        RuntimeFiles.id(id);File data;String expectedDigest;
        synchronized(broker){
            ApkStore.BundleInstall install=stoppedInstall(broker,space,pkg,generation);
            Row row=row(broker.store.getReadableDatabase(),id);
            if(row==null||!row.space.equals(space)||!row.pkg.equals(pkg))throw new IllegalStateException("CHECKPOINT_NOT_FOUND");
            if(!"READY".equals(row.state))throw new IllegalStateException("CHECKPOINT_NOT_READY:"+row.state);
            // An upgrade keeps the dataId, so a checkpoint survives it; a reinstall gets a new data root and does not.
            if(!row.dataId.equals(install.data.getName()))throw new IllegalStateException("CHECKPOINT_DATA_ROOT_REPLACED");
            data=install.data;expectedDigest=row.digest;
            ACTIVE.add(id);setState(broker,id,"READY","RESTORING");
        }
        File source=new File(root(broker),id),staging=new File(root(broker),".restore-"+id),trash=new File(root(broker),".trash-"+id);
        boolean swapped=false;
        try{
            remove(staging);
            Copy copy=copyTree(source.toPath(),staging.toPath());
            if(!copy.digest().equals(expectedDigest))throw new IllegalStateException("CHECKPOINT_INTEGRITY_MISMATCH");
            synchronized(broker){
                remove(trash);
                Files.move(data.toPath(),trash.toPath(),StandardCopyOption.ATOMIC_MOVE);
                try{Files.move(staging.toPath(),data.toPath(),StandardCopyOption.ATOMIC_MOVE);}
                catch(Exception failure){Files.move(trash.toPath(),data.toPath(),StandardCopyOption.ATOMIC_MOVE);throw failure;}
                swapped=true;
                RuntimeFiles.sync(data.getParentFile());
                ContentValues v=new ContentValues();v.put("state","READY");v.put("restored",System.currentTimeMillis());
                if(broker.store.getWritableDatabase().update("state_checkpoints",v,"id=? AND state='RESTORING'",new String[]{id})!=1)throw new IllegalStateException("CHECKPOINT_PERSIST_FAILED");
            }
            remove(trash);
            JSONObject out=describe(broker,id);out.put("restoredEntries",copy.entries).put("restoredBytes",copy.bytes);return out;
        }catch(Exception|Error failure){
            try{remove(staging);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(!swapped)synchronized(broker){setState(broker,id,"RESTORING","READY");}
            throw failure;
        }finally{ACTIVE.remove(id);}
    }

    static JSONObject deleteCheckpoint(RuntimeBroker broker,String space,String pkg,String generation,String id)throws Exception{
        RuntimeFiles.id(id);
        synchronized(broker){
            var install=broker.apks.row(space,pkg);if(install==null||!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
            Row row=row(broker.store.getReadableDatabase(),id);
            if(row==null||!row.space.equals(space)||!row.pkg.equals(pkg))throw new IllegalStateException("CHECKPOINT_NOT_FOUND");
            if(!"READY".equals(row.state))throw new IllegalStateException("CHECKPOINT_NOT_READY:"+row.state);
            broker.store.getWritableDatabase().delete("state_checkpoints","id=?",new String[]{id});
        }
        remove(new File(root(broker),id));
        return new JSONObject().put("checkpointId",id).put("deleted",true);
    }

    static JSONObject list(RuntimeBroker broker,String space,String pkg,String generation)throws Exception{
        JSONArray out=new JSONArray();String dataId;
        synchronized(broker){
            var install=broker.apks.row(space,pkg);if(install==null||!generation.equals(install.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
            dataId=install.dataId;
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id FROM state_checkpoints WHERE space=? AND package=? ORDER BY created",new String[]{space,pkg})){
                List<String> ids=new ArrayList<>();while(c.moveToNext())ids.add(c.getString(0));
                for(String id:ids){JSONObject value=describe(broker,id);value.put("restorable","READY".equals(value.getString("state"))&&dataId.equals(row(broker.store.getReadableDatabase(),id).dataId));out.put(value);}
            }
        }
        return new JSONObject().put("checkpoints",out).put("limits",new JSONObject().put("perInstallation",MAX_PER_INSTALL).put("bytes",MAX_BYTES).put("entries",MAX_ENTRIES));
    }

    private static JSONObject describe(RuntimeBroker broker,String id)throws Exception{
        Row row=row(broker.store.getReadableDatabase(),id);if(row==null)throw new IllegalStateException("CHECKPOINT_NOT_FOUND");
        JSONObject out=new JSONObject().put("checkpointId",row.id).put("label",row.label).put("state",row.state).put("installGeneration",row.generation)
            .put("entries",row.entries).put("bytes",row.bytes).put("digest",row.digest).put("createdAt",row.created);
        if(row.restored!=null)out.put("lastRestoredAt",row.restored);
        return out;
    }

    private static void setState(RuntimeBroker broker,String id,String from,String to){
        ContentValues v=new ContentValues();v.put("state",to);
        if(broker.store.getWritableDatabase().update("state_checkpoints",v,"id=? AND state=?",new String[]{id,from})!=1)throw new IllegalStateException("CHECKPOINT_STATE_CONFLICT");
    }

    /**
     * Finish or discard interrupted work and remove checkpoints of uninstalled/reinstalled data roots.
     * Runs at boot (no live processes) and after uninstall; ids being worked on by this authority are skipped.
     */
    static void recover(File runtimeRoot,RuntimeStore store)throws Exception{
        File root=new File(runtimeRoot,"checkpoints");
        SQLiteDatabase db=store.getWritableDatabase();
        Set<String> live=new HashSet<>();
        try(Cursor c=db.rawQuery("SELECT space,package,dataId FROM installs",null)){while(c.moveToNext())live.add(c.getString(0)+"/"+c.getString(1)+"/"+c.getString(2));}
        List<Row> rows=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT id FROM state_checkpoints",null)){while(c.moveToNext()){Row row=row(db,c.getString(0));if(row!=null)rows.add(row);}}
        Set<String> kept=new HashSet<>();
        for(Row row:rows){
            if(ACTIVE.contains(row.id)){kept.add(row.id);continue;}
            File data=new File(runtimeRoot,"data/"+row.space+"/"+row.pkg+"/"+row.dataId),staging=new File(root,".restore-"+row.id),trash=new File(root,".trash-"+row.id);
            if("RESTORING".equals(row.state)){
                // The data root is moved aside only after the staging copy completed; a missing data root means the swap was interrupted.
                if(!data.exists()&&staging.isDirectory())Files.move(staging.toPath(),data.toPath(),StandardCopyOption.ATOMIC_MOVE);
                else if(!data.exists()&&trash.isDirectory())Files.move(trash.toPath(),data.toPath(),StandardCopyOption.ATOMIC_MOVE);
                remove(staging);remove(trash);
                db.execSQL("UPDATE state_checkpoints SET state='READY' WHERE id=?",new Object[]{row.id});row=row(db,row.id);
            }
            if("CREATING".equals(row.state)||!live.contains(row.space+"/"+row.pkg+"/"+row.dataId)){
                db.delete("state_checkpoints","id=?",new String[]{row.id});remove(new File(root,row.id));continue;
            }
            kept.add(row.id);
        }
        if(root.isDirectory())for(File child:RuntimeFiles.children(root)){
            String name=child.getName();String owner=name.startsWith(".restore-")?name.substring(9):name.startsWith(".trash-")?name.substring(7):name;
            if(!kept.contains(owner)||(!owner.equals(name)&&!ACTIVE.contains(owner)))remove(child);
        }
    }

    private static final class Row {
        String id,space,pkg,dataId,generation,label,state,digest;long entries,bytes,created;Long restored;
    }
    private static Row row(SQLiteDatabase db,String id){
        try(Cursor c=db.rawQuery("SELECT id,space,package,dataId,installGeneration,label,state,entries,bytes,digest,created,restored FROM state_checkpoints WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())return null;Row r=new Row();
            r.id=c.getString(0);r.space=c.getString(1);r.pkg=c.getString(2);r.dataId=c.getString(3);r.generation=c.getString(4);r.label=c.getString(5);r.state=c.getString(6);
            r.entries=c.getLong(7);r.bytes=c.getLong(8);r.digest=c.getString(9);r.created=c.getLong(10);r.restored=c.isNull(11)?null:c.getLong(11);return r;
        }
    }

    /** Copy result plus a content digest over (relative path, kind, size, content hash / link target), in sorted path order. */
    private static final class Copy {
        long entries,bytes;final List<String> records=new ArrayList<>();
        String digest()throws Exception{Collections.sort(records);MessageDigest md=MessageDigest.getInstance("SHA-256");for(String r:records){md.update(r.getBytes(StandardCharsets.UTF_8));md.update((byte)'\n');}return hex(md.digest());}
    }

    /** Copies without following symbolic links (links are recreated as links); bounded by MAX_BYTES/MAX_ENTRIES. */
    private static Copy copyTree(Path source,Path target)throws Exception{
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("CHECKPOINT_SOURCE_MISSING");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("CHECKPOINT_TARGET_EXISTS");
        Copy copy=new Copy();Files.createDirectory(target);copyChildren(source,target,source,copy);
        RuntimeFiles.sync(target.toFile());return copy;
    }
    private static void copyChildren(Path dir,Path targetDir,Path base,Copy copy)throws Exception{
        List<Path> children=new ArrayList<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){for(Path p:stream)children.add(p);}
        for(Path child:children){
            if(++copy.entries>MAX_ENTRIES)throw new IllegalStateException("CHECKPOINT_TOO_MANY_ENTRIES:"+MAX_ENTRIES);
            String rel=base.relativize(child).toString();Path dest=targetDir.resolve(child.getFileName().toString());
            BasicFileAttributes attrs=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attrs.isSymbolicLink()){Path link=Files.readSymbolicLink(child);Files.createSymbolicLink(dest,link);copy.records.add(rel+"\tL\t"+link);}
            else if(attrs.isDirectory()){Files.createDirectory(dest);copy.records.add(rel+"\tD");copyChildren(child,dest,base,copy);}
            else if(attrs.isRegularFile()){
                copy.bytes+=attrs.size();if(copy.bytes>MAX_BYTES)throw new IllegalStateException("CHECKPOINT_TOO_LARGE:"+MAX_BYTES);
                copy.records.add(rel+"\tF\t"+attrs.size()+"\t"+copyFile(child,dest));
            }
            else throw new IllegalStateException("CHECKPOINT_UNSUPPORTED_ENTRY:"+rel);
        }
    }
    private static String copyFile(Path from,Path to)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        try(InputStream in=Files.newInputStream(from,LinkOption.NOFOLLOW_LINKS);var out=new java.io.FileOutputStream(to.toFile())){
            int n;while((n=in.read(buffer))>0){md.update(buffer,0,n);out.write(buffer,0,n);}out.getFD().sync();
        }
        return hex(md.digest());
    }
    /** deleteTree that tolerates an absent path (it fails on a missing file). */
    private static void remove(File file)throws Exception{if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))RuntimeFiles.deleteTree(file);}
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
