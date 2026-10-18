package dev.tripoder.runtime;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.Bundle;
import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Storage identity comes only from the installed data incarnation. Not a same-UID sandbox. */
final class RuntimeStorage {
    final String space,packageName,installGeneration,dataId,processGeneration;
    final File root;
    private final File anchor,canonicalRoot;
    private final Map<String,RuntimePreferences> preferences=new HashMap<>();
    private final java.util.concurrent.ExecutorService preferenceWriter=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"guest-preferences"));
    private boolean finisherRegistered;
    RuntimeStorage(Context host,Bundle input)throws Exception{
        space=RuntimeFiles.id(RuntimeWire.required(input,"space"));packageName=RuntimeFiles.id(RuntimeWire.required(input,"package"));
        installGeneration=RuntimeFiles.id(RuntimeWire.required(input,"installGeneration"));dataId=RuntimeFiles.id(RuntimeWire.required(input,"dataId"));processGeneration=RuntimeWire.required(input,"generation");
        anchor=host.getFilesDir();root=new File(anchor,"runtime/data/"+space+"/"+packageName+"/"+dataId);
        if(!root.equals(new File(RuntimeWire.required(input,"data"))))throw new SecurityException("STORAGE_IDENTITY_MISMATCH");
        directory(root);canonicalRoot=root.getCanonicalFile();
    }
    /** A namespace view over the same guest data incarnation (device-protected storage); never leaves the guest data root. */
    private RuntimeStorage(RuntimeStorage parent,String subdirectory)throws Exception{
        space=parent.space;packageName=parent.packageName;installGeneration=parent.installGeneration;dataId=parent.dataId;processGeneration=parent.processGeneration;
        anchor=parent.anchor;root=new File(parent.root,name(subdirectory));directory(root);canonicalRoot=root.getCanonicalFile();
    }
    RuntimeStorage view(String subdirectory)throws Exception{return new RuntimeStorage(this,subdirectory);}
    static String name(String name){if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\0')>=0||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200)throw new IllegalArgumentException("INVALID_FILE_NAME");return name;}
    private void directory(File file){
        Path base=anchor.toPath(),target=file.toPath();if(!target.startsWith(base)||!target.normalize().equals(target))throw new SecurityException("STORAGE_PATH_OUTSIDE_ROOT");
        File current=anchor;
        for(Path part:base.relativize(target)){current=new File(current,part.toString());try{
            if(Files.isSymbolicLink(current.toPath()))throw new SecurityException("STORAGE_SYMLINK_REJECTED");
            if(!current.exists()){try{Os.mkdir(current.toString(),0700);}catch(ErrnoException raced){if(raced.errno!=OsConstants.EEXIST)throw raced;}}
            var stat=Os.lstat(current.toString());if(!OsConstants.S_ISDIR(stat.st_mode))throw new IllegalStateException("STORAGE_DIRECTORY_REQUIRED");
        }catch(ErrnoException error){throw new IllegalStateException("STORAGE_DIRECTORY_FAILED",error);}}
    }
    File dir(String name){directory(root);File path=new File(root,name(name));directory(path);return path;}
    File file(String directory,String name){File path=new File(dir(directory),name(name));check(path);return path;}
    void check(File path){
        if(!path.toPath().startsWith(root.toPath())||!path.toPath().normalize().equals(path.toPath()))throw new SecurityException("STORAGE_PATH_OUTSIDE_ROOT");
        directory(path.getParentFile());if(Files.isSymbolicLink(path.toPath()))throw new SecurityException("STORAGE_SYMLINK_REJECTED");
        if(Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORAGE_REGULAR_FILE_REQUIRED");
    }
    FileOutputStream output(String name,int mode)throws FileNotFoundException{
        if(mode!=Context.MODE_PRIVATE&&mode!=Context.MODE_APPEND)throw new IllegalArgumentException("FILE_MODE_UNSUPPORTED");File path=file("files",name);
        try{return new FileOutputStream(Os.open(path.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC|(mode==Context.MODE_APPEND?OsConstants.O_APPEND:OsConstants.O_TRUNC),0600));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    FileInputStream input(String name)throws FileNotFoundException{
        File path=file("files",name);try{return new FileInputStream(Os.open(path.toString(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    String[] list(String directory){String[] names=dir(directory).list();if(names==null)throw new IllegalStateException("STORAGE_LIST_FAILED");return names;}
    synchronized SharedPreferences preferences(String name,int mode){
        if(mode!=Context.MODE_PRIVATE&&mode!=Context.MODE_MULTI_PROCESS)throw new IllegalArgumentException("PREFERENCES_MODE_UNSUPPORTED:0x"+Integer.toHexString(mode));name(name);
        RuntimePreferences found=preferences.get(name);if(found!=null){if(mode==Context.MODE_MULTI_PROCESS)found.reload();return found;}
        if(!finisherRegistered){RuntimeBridge.storageFinisher(this::flush);finisherRegistered=true;}
        RuntimePreferences created=new RuntimePreferences(this,file("shared_prefs",name+".xml"),preferenceWriter);preferences.put(name,created);return created;
    }
    synchronized boolean deletePreferences(String name){
        name(name);RuntimePreferences p=(RuntimePreferences)preferences(name,Context.MODE_PRIVATE);return p.delete();
    }
    /** Same data incarnation only (e.g. credential-protected to device-protected view): bytes move, and no stale in-memory view keeps serving the old file. */
    boolean movePreferencesFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        name(name);if(source==this)return true;
        File from=source.file("shared_prefs",name+".xml"),to=file("shared_prefs",name+".xml");
        RuntimePreferences live;synchronized(source){live=source.preferences.get(name);}if(live!=null)live.flush();
        if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        synchronized(this){
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
            File backup=new File(from.getPath()+".bak");if(backup.exists())Files.move(backup.toPath(),new File(to.getPath()+".bak").toPath(),StandardCopyOption.REPLACE_EXISTING);
            RuntimePreferences mine=preferences.get(name);if(mine!=null)mine.reload();
        }
        synchronized(source){source.preferences.remove(name);}
        return true;
    }
    boolean moveDatabaseFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        if(source==this)return true;File from=source.database(name),to=database(name);
        if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        for(String suffix:new String[]{"","-wal","-shm","-journal"}){File s=new File(from+suffix);if(Files.exists(s.toPath(),LinkOption.NOFOLLOW_LINKS))Files.move(s.toPath(),new File(to+suffix).toPath(),StandardCopyOption.REPLACE_EXISTING);}
        return true;
    }
    void flush(){List<RuntimePreferences> snapshot;synchronized(this){snapshot=new ArrayList<>(preferences.values());}for(var p:snapshot)p.flush();}
    File database(String name){
        File path=name!=null&&new File(name).isAbsolute()?RuntimeDatabasePath.resolve(root,canonicalRoot,packageName,name):file("databases",name);
        check(path);for(String suffix:new String[]{"-wal","-shm","-journal"})check(new File(path+suffix));return path;
    }
    SQLiteDatabase database(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){
        int allowed=Context.MODE_ENABLE_WRITE_AHEAD_LOGGING|Context.MODE_NO_LOCALIZED_COLLATORS;if((mode&~allowed)!=0)throw new IllegalArgumentException("DATABASE_MODE_UNSUPPORTED");
        File path=database(name);
        int flags=SQLiteDatabase.CREATE_IF_NECESSARY;
        if((mode&Context.MODE_ENABLE_WRITE_AHEAD_LOGGING)!=0)flags|=SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        if((mode&Context.MODE_NO_LOCALIZED_COLLATORS)!=0)flags|=SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        DatabaseErrorHandler onCorruption=handler==null?db->{throw new SQLiteDatabaseCorruptException("GUEST_DATABASE_CORRUPT_PRESERVED:"+db.getPath());}:handler;
        return SQLiteDatabase.openDatabase(path.toString(),factory,flags,onCorruption);
    }
    boolean deleteDatabase(String name){File path=database(name);return SQLiteDatabase.deleteDatabase(path);}
    String nativeSpec(File artifacts)throws Exception{return new org.json.JSONObject().put("data",root.toString()).put("dataCanonical",root.getCanonicalPath()).put("artifacts",artifacts.toString()).put("artifactsCanonical",artifacts.getCanonicalPath()).put("package",packageName).toString();}
}
