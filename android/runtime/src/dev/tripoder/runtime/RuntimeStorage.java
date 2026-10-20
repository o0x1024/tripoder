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
    final RuntimeMode runtimeMode;
    final File root;
    private final Context host;
    private final File anchor,canonicalRoot;
    private final Path basePath,rootPath;
    private final java.util.concurrent.ConcurrentHashMap<String,RuntimePreferences> preferences=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Object> preferenceLocks=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService preferenceWriter=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"guest-preferences"));
    private final java.util.concurrent.atomic.AtomicBoolean finisherRegistered=new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<Path> verifiedDirectories=Collections.synchronizedSet(new HashSet<>());
    RuntimeStorage(Context host,Bundle input)throws Exception{
        this.host=host;runtimeMode=RuntimeMode.parse(input.getString(RuntimeMode.KEY)).requireImplemented();
        space=RuntimeFiles.id(RuntimeWire.required(input,"space"));packageName=RuntimeFiles.id(RuntimeWire.required(input,"package"));
        installGeneration=RuntimeFiles.id(RuntimeWire.required(input,"installGeneration"));dataId=RuntimeFiles.id(RuntimeWire.required(input,"dataId"));processGeneration=RuntimeWire.required(input,"generation");
        anchor=host.getFilesDir();root=new File(anchor,"runtime/data/"+space+"/"+packageName+"/"+dataId);
        basePath=anchor.toPath();rootPath=root.toPath();
        if(!root.equals(new File(RuntimeWire.required(input,"data"))))throw new SecurityException("STORAGE_IDENTITY_MISMATCH");
        directory(root);canonicalRoot=root.getCanonicalFile();
    }
    /** A namespace view over the same guest data incarnation (device-protected storage); never leaves the guest data root. */
    private RuntimeStorage(RuntimeStorage parent,String subdirectory)throws Exception{
        host=parent.host;runtimeMode=parent.runtimeMode;
        space=parent.space;packageName=parent.packageName;installGeneration=parent.installGeneration;dataId=parent.dataId;processGeneration=parent.processGeneration;
        anchor=parent.anchor;root=new File(parent.root,name(subdirectory));
        basePath=parent.basePath;rootPath=root.toPath();
        directory(root);canonicalRoot=root.getCanonicalFile();
    }
    RuntimeStorage view(String subdirectory)throws Exception{return new RuntimeStorage(this,subdirectory);}
    static String name(String name){if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\0')>=0||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200)throw new IllegalArgumentException("INVALID_FILE_NAME");return name;}
    private Object preferenceLock(String name){return preferenceLocks.computeIfAbsent(name,k->new Object());}
    private void directory(File file){
        Path target=file.toPath();if(verifiedDirectories.contains(target))return;
        if(!target.startsWith(basePath)||!target.normalize().equals(target))throw new SecurityException("STORAGE_PATH_OUTSIDE_ROOT");
        File current=anchor;
        for(Path part:basePath.relativize(target)){current=new File(current,part.toString());try{
            Path currentPath=current.toPath();
            if(verifiedDirectories.contains(currentPath))continue;
            if(Files.isSymbolicLink(currentPath))throw new SecurityException("STORAGE_SYMLINK_REJECTED");
            if(!current.exists()){try{Os.mkdir(current.toString(),0700);}catch(ErrnoException raced){if(raced.errno!=OsConstants.EEXIST)throw raced;}}
            var stat=Os.lstat(current.toString());if(!OsConstants.S_ISDIR(stat.st_mode))throw new IllegalStateException("STORAGE_DIRECTORY_REQUIRED");
            verifiedDirectories.add(currentPath);
        }catch(ErrnoException error){throw new IllegalStateException("STORAGE_DIRECTORY_FAILED",error);}}
    }
    File dir(String name){File path=new File(root,name(name));directory(path);return path;}
    File file(String directory,String name){File path=new File(dir(directory),name(name));check(path);return path;}
    void check(File path){
        Path p=path.toPath();
        if(!p.startsWith(rootPath)||!p.normalize().equals(p))throw new SecurityException("STORAGE_PATH_OUTSIDE_ROOT");
        Path parent=p.getParent();
        if(parent!=null&&!verifiedDirectories.contains(parent))directory(path.getParentFile());
        if(Files.isSymbolicLink(p))throw new SecurityException("STORAGE_SYMLINK_REJECTED");
        if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("STORAGE_REGULAR_FILE_REQUIRED");
    }
    FileOutputStream output(String name,int mode)throws FileNotFoundException{
        if(runtimeMode!=RuntimeMode.THIN&&mode!=Context.MODE_PRIVATE&&mode!=Context.MODE_APPEND)throw new IllegalArgumentException("FILE_MODE_UNSUPPORTED");File path=file("files",name);
        try{return new FileOutputStream(Os.open(path.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC|((mode&Context.MODE_APPEND)!=0?OsConstants.O_APPEND:OsConstants.O_TRUNC),0600));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    FileInputStream input(String name)throws FileNotFoundException{
        File path=file("files",name);try{return new FileInputStream(Os.open(path.toString(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    String[] list(String directory){String[] names=dir(directory).list();if(names==null)throw new IllegalStateException("STORAGE_LIST_FAILED");return names;}
    /**
     * One instance per file per data incarnation. The load happens under the file's own lock object so two callers can never
     * open the same preference file at once: each load holds the file's own kernel lock, and a second concurrent load in
     * this process fails with OverlappingFileLockException instead of waiting. Different files load in parallel without
     * blocking each other on a storage-wide monitor.
     */
    SharedPreferences preferences(String name,int mode){
        if(runtimeMode==RuntimeMode.THIN){
            File target=file("shared_prefs",name+".xml");
            int physicalMode=(mode&Context.MODE_MULTI_PROCESS)!=0?Context.MODE_MULTI_PROCESS:Context.MODE_PRIVATE;
            return dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,target,physicalMode);
        }
        if(mode!=Context.MODE_PRIVATE&&mode!=Context.MODE_MULTI_PROCESS)throw new IllegalArgumentException("PREFERENCES_MODE_UNSUPPORTED:0x"+Integer.toHexString(mode));name(name);
        if(finisherRegistered.compareAndSet(false,true))RuntimeBridge.storageFinisher(this::flush);
        RuntimePreferences found=preferences.get(name);
        if(found==null){
            synchronized(preferenceLock(name)){
                found=preferences.get(name);
                if(found==null){
                    found=new RuntimePreferences(this,file("shared_prefs",name+".xml"),preferenceWriter);
                    preferences.put(name,found);
                }
            }
        }
        if(mode==Context.MODE_MULTI_PROCESS)found.reload();
        return found;
    }
    boolean deletePreferences(String name){
        name(name);
        if(runtimeMode==RuntimeMode.THIN){
            File target=file("shared_prefs",name+".xml"),backup=new File(target.getPath()+".bak");
            SharedPreferences p=dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,target,Context.MODE_PRIVATE);
            p.edit().clear().commit();
            try{Files.deleteIfExists(target.toPath());Files.deleteIfExists(backup.toPath());return true;}catch(IOException failed){return false;}
        }
        RuntimePreferences p=(RuntimePreferences)preferences(name,Context.MODE_PRIVATE);return p.delete();
    }
    /** Same data incarnation only (e.g. credential-protected to device-protected view): bytes move, and no stale in-memory view keeps serving the old file. */
    boolean movePreferencesFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        name(name);if(source==this)return true;
        File from=source.file("shared_prefs",name+".xml"),to=file("shared_prefs",name+".xml");
        if(runtimeMode==RuntimeMode.THIN){
            if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
            SharedPreferences srcPrefs=dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,from,Context.MODE_PRIVATE);
            Map<String,?> snapshot=new LinkedHashMap<>(srcPrefs.getAll());
            SharedPreferences dstPrefs=dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,to,Context.MODE_PRIVATE);
            SharedPreferences.Editor editor=dstPrefs.edit().clear();
            for(var entry:snapshot.entrySet()){
                String k=entry.getKey();Object v=entry.getValue();
                if(v instanceof String s)editor.putString(k,s);
                else if(v instanceof Integer i)editor.putInt(k,i);
                else if(v instanceof Long l)editor.putLong(k,l);
                else if(v instanceof Float f)editor.putFloat(k,f);
                else if(v instanceof Boolean b)editor.putBoolean(k,b);
                else if(v instanceof Set<?> set){@SuppressWarnings("unchecked") Set<String> strings=(Set<String>)set;editor.putStringSet(k,new LinkedHashSet<>(strings));}
            }
            editor.commit();
            source.deletePreferences(name);
            return true;
        }
        RuntimePreferences live=source.preferences.get(name);if(live!=null)live.flush();
        if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        Object lockA=preferenceLock(name),lockB=source.preferenceLock(name);
        Object first=System.identityHashCode(lockA)<=System.identityHashCode(lockB)?lockA:lockB;
        Object second=first==lockA?lockB:lockA;
        synchronized(first){
            synchronized(second){
                Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
                File backup=new File(from.getPath()+".bak");if(backup.exists())Files.move(backup.toPath(),new File(to.getPath()+".bak").toPath(),StandardCopyOption.REPLACE_EXISTING);
                RuntimePreferences mine=preferences.get(name);if(mine!=null)mine.reload();
                source.preferences.remove(name);
            }
        }
        return true;
    }
    boolean moveDatabaseFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        if(source==this)return true;File from=source.database(name),to=database(name);
        if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        for(String suffix:new String[]{"","-wal","-shm","-journal"}){File s=new File(from+suffix);if(Files.exists(s.toPath(),LinkOption.NOFOLLOW_LINKS))Files.move(s.toPath(),new File(to+suffix).toPath(),StandardCopyOption.REPLACE_EXISTING);}
        return true;
    }
    void flush(){List<RuntimePreferences> snapshot=new ArrayList<>(preferences.values());for(var p:snapshot)p.flush();}
    /**
     * {@code Context.getDatabasePath} parity. A relative name is a file inside {@code databases/}; an absolute name is
     * already a guest path (WorkManager and others pass one back in) and must stay absolute instead of being nested a
     * second time under {@code databases/}. Either way the real parent directory is materialized in this incarnation,
     * so a later native {@code open} of the returned path has somewhere to land.
     */
    File databasePath(String name,File guestData){
        if(name==null||name.isEmpty())throw new IllegalArgumentException("INVALID_DATABASE_NAME");
        if(new File(name).isAbsolute()){
            File physical=RuntimeDatabasePath.resolve(root,canonicalRoot,packageName,name);
            File parent=physical.getParentFile();if(parent!=null)directory(parent);
            return new File(name);
        }
        File databases=dir("databases");check(new File(databases,name));
        return new File(new File(guestData,"databases"),name);
    }
    File database(String name){
        File path=name!=null&&new File(name).isAbsolute()?RuntimeDatabasePath.resolve(root,canonicalRoot,packageName,name):file("databases",name);
        check(path);for(String suffix:new String[]{"-wal","-shm","-journal"})check(new File(path+suffix));return path;
    }
    SQLiteDatabase database(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){
        int allowed=Context.MODE_ENABLE_WRITE_AHEAD_LOGGING|Context.MODE_NO_LOCALIZED_COLLATORS;if(runtimeMode!=RuntimeMode.THIN&&(mode&~allowed)!=0)throw new IllegalArgumentException("DATABASE_MODE_UNSUPPORTED");
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
