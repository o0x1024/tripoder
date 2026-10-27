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
    private final Context host;
    private final File anchor,canonicalRoot;
    private final Path basePath,rootPath;
    private final Set<Path> verifiedDirectories=Collections.synchronizedSet(new HashSet<>());
    private final Map<String,RuntimeStorage> views=new HashMap<>();
    RuntimeStorage(Context host,Bundle input)throws Exception{
        this.host=host;
        space=RuntimeFiles.id(RuntimeWire.required(input,"space"));packageName=RuntimeFiles.id(RuntimeWire.required(input,"package"));
        installGeneration=RuntimeFiles.id(RuntimeWire.required(input,"installGeneration"));dataId=RuntimeFiles.id(RuntimeWire.required(input,"dataId"));processGeneration=RuntimeWire.required(input,"generation");
        anchor=host.getFilesDir();root=new File(anchor,"runtime/data/"+space+"/"+packageName+"/"+dataId);
        basePath=anchor.toPath();rootPath=root.toPath();
        if(!root.equals(new File(RuntimeWire.required(input,"data"))))throw new SecurityException("STORAGE_IDENTITY_MISMATCH");
        directory(root);canonicalRoot=root.getCanonicalFile();
    }
    /** A namespace view over the same guest data incarnation (device-protected storage); never leaves the guest data root. */
    private RuntimeStorage(RuntimeStorage parent,String subdirectory)throws Exception{
        host=parent.host;
        space=parent.space;packageName=parent.packageName;installGeneration=parent.installGeneration;dataId=parent.dataId;processGeneration=parent.processGeneration;
        anchor=parent.anchor;root=new File(parent.root,name(subdirectory));
        basePath=parent.basePath;rootPath=root.toPath();
        directory(root);canonicalRoot=root.getCanonicalFile();
    }
    RuntimeStorage view(String subdirectory)throws Exception{
        String key=name(subdirectory);
        synchronized(views){RuntimeStorage view=views.get(key);if(view==null){view=new RuntimeStorage(this,key);views.put(key,view);}return view;}
    }
    static String name(String name){if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\0')>=0||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200)throw new IllegalArgumentException("INVALID_FILE_NAME:chars="+(name==null?-1:name.length())+";bytes="+(name==null?-1:name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)+";slash="+(name!=null&&name.indexOf('/')>=0)+";nul="+(name!=null&&name.indexOf('\0')>=0));return name;}
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
        // One no-follow observation: a platform AtomicFile rename can remove
        // the path between separate exists/type checks. ENOENT is a valid new
        // or in-flight file, while a real symlink/non-regular inode stays denied.
        try{
            var stat=Os.lstat(path.toString());
            if(OsConstants.S_ISLNK(stat.st_mode))throw new SecurityException("STORAGE_SYMLINK_REJECTED");
            if(!OsConstants.S_ISREG(stat.st_mode))throw new IllegalStateException("STORAGE_REGULAR_FILE_REQUIRED");
        }catch(ErrnoException error){if(error.errno!=OsConstants.ENOENT)throw new IllegalStateException("STORAGE_FILE_OBSERVATION_FAILED",error);}
    }
    FileOutputStream output(String name,int mode)throws FileNotFoundException{
        File path=file("files",name);
        try{return new FileOutputStream(Os.open(path.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC|((mode&Context.MODE_APPEND)!=0?OsConstants.O_APPEND:OsConstants.O_TRUNC),0600));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    FileInputStream input(String name)throws FileNotFoundException{
        File path=file("files",name);try{return new FileInputStream(Os.open(path.toString(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0));}
        catch(ErrnoException error){FileNotFoundException out=new FileNotFoundException(error.getMessage());out.initCause(error);throw out;}
    }
    String[] list(String directory){String[] names=dir(directory).list();if(names==null)throw new IllegalStateException("STORAGE_LIST_FAILED");return names;}
    /** The platform owns preference caching and write scheduling for each guest data file. */
    private static String preferenceName(String value){String name=String.valueOf(value);name(name+".xml");return name;}
    SharedPreferences preferences(String name,int mode){
        name=preferenceName(name);
        File target=file("shared_prefs",name+".xml");
        int physicalMode=(mode&Context.MODE_MULTI_PROCESS)!=0?Context.MODE_MULTI_PROCESS:Context.MODE_PRIVATE;
        return dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,target,physicalMode);
    }
    boolean deletePreferences(String name){
        name=preferenceName(name);
        File target=file("shared_prefs",name+".xml"),backup=new File(target.getPath()+".bak");
        SharedPreferences p=dev.tripoder.selfhook.PackageFrameworkAccess.sharedPreferences(host,target,Context.MODE_PRIVATE);
        p.edit().clear().commit();
        try{Files.deleteIfExists(target.toPath());Files.deleteIfExists(backup.toPath());return true;}catch(IOException failed){return false;}
    }
    /** Same data incarnation only (e.g. credential-protected to device-protected view): bytes move, and no stale in-memory view keeps serving the old file. */
    boolean movePreferencesFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        name=preferenceName(name);if(source==this)return true;
        File from=source.file("shared_prefs",name+".xml"),to=file("shared_prefs",name+".xml");
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
    boolean moveDatabaseFrom(RuntimeStorage source,String name)throws java.io.IOException{
        if(!source.anchor.equals(anchor)||!source.dataId.equals(dataId)||!source.processGeneration.equals(processGeneration))throw new SecurityException("STORAGE_MOVE_ACROSS_INCARNATIONS_DENIED");
        if(source==this)return true;File from=source.database(name),to=database(name);
        if(!Files.exists(from.toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        for(String suffix:new String[]{"","-wal","-shm","-journal"}){File s=new File(from+suffix);if(Files.exists(s.toPath(),LinkOption.NOFOLLOW_LINKS))Files.move(s.toPath(),new File(to+suffix).toPath(),StandardCopyOption.REPLACE_EXISTING);}
        return true;
    }
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
