package dev.tripoder.runtime;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.*;

/** Context entry points share exactly one process-bound storage policy. */
abstract class GuestStorageContext extends ContextWrapper {
    final RuntimeStorage storage;
    GuestStorageContext(Context base,RuntimeStorage storage){super(base);this.storage=storage;}
    @Override public File getDataDir(){return new File("/data/user/0/"+getPackageName());}
    @Override public File getFilesDir(){storage.dir("files");return new File(getDataDir(),"files");}
    @Override public File getCacheDir(){storage.dir("cache");return new File(getDataDir(),"cache");}
    @Override public File getCodeCacheDir(){storage.dir("code_cache");return new File(getDataDir(),"code_cache");}
    @Override public File getNoBackupFilesDir(){storage.dir("no_backup");return new File(getDataDir(),"no_backup");}
    @Override public File getFileStreamPath(String name){return new File(getFilesDir(),name);}
    @Override public File getDir(String name,int mode){storage.dir("app_"+RuntimeStorage.name(name));return new File(getDataDir(),"app_"+RuntimeStorage.name(name));}
    @Override public boolean deleteFile(String name){return getFileStreamPath(name).delete();}
    @Override public String[] fileList(){return storage.list("files");}
    // Typed external directories stay guest-private; the type is a single path segment, never a host path.
    // Android treats "" like null and allows nested types ("a/b"); each segment is validated and the type is flattened into one directory name, so nothing leaves the guest root.
    @Override public File getExternalFilesDir(String type){
        if(type==null||type.isEmpty())return storage.dir("external");
        try{return externalType(type);}catch(IllegalArgumentException error){android.util.Log.w("TripoderStorage","getExternalFilesDir rejected type=\""+type+"\"");throw error;}
    }
    private File externalType(String type){
        String[] parts=type.split("/",-1);StringBuilder flat=new StringBuilder("external-type-");
        for(int i=0;i<parts.length;i++){if(parts[i].isEmpty()&&(i==0||i==parts.length-1))continue;if(i>0&&flat.charAt(flat.length()-1)!='-')flat.append('~');flat.append(RuntimeStorage.name(parts[i]));}
        return storage.dir(flat.toString());
    }
    @Override public File[] getExternalFilesDirs(String type){return new File[]{getExternalFilesDir(type)};}
    @Override public File getExternalCacheDir(){return storage.dir("external-cache");}
    @Override public File[] getExternalCacheDirs(){return new File[]{getExternalCacheDir()};}
    // External-storage roots are guest-private: the guest never receives a path under shared storage it does not own.
    @Override public File getObbDir(){return storage.dir("external-obb");}
    @Override public File[] getObbDirs(){return new File[]{getObbDir()};}
    @Override public File[] getExternalMediaDirs(){return new File[]{storage.dir("external-media")};}
    @Override public FileOutputStream openFileOutput(String name,int mode)throws FileNotFoundException{return storage.output(name,mode);}
    @Override public FileInputStream openFileInput(String name)throws FileNotFoundException{return storage.input(name);}
    @Override public SharedPreferences getSharedPreferences(String name,int mode){return storage.preferences(name,mode);}
    @Override public boolean deleteSharedPreferences(String name){return storage.deletePreferences(name);}
    /** The guest's own storage views move bytes; a platform or foreign context has no storage this guest may touch, which Android reports as a failed move. */
    private static RuntimeStorage storageOf(Context context){Context c=context;while(c instanceof ContextWrapper&&!(c instanceof GuestStorageContext))c=((ContextWrapper)c).getBaseContext();return c instanceof GuestStorageContext?((GuestStorageContext)c).storage:null;}
    @Override public boolean moveSharedPreferencesFrom(Context source,String name){
        RuntimeStorage from=storageOf(source);if(from==null)return false;
        try{return storage.movePreferencesFrom(from,name);}catch(IOException failed){return false;}
    }
    @Override public File getDatabasePath(String name){return storage.databasePath(name,getDataDir());}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return storage.database(name,mode,factory,null);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return storage.database(name,mode,factory,handler);}
    @Override public boolean moveDatabaseFrom(Context source,String name){
        RuntimeStorage from=storageOf(source);if(from==null)return false;
        try{return storage.moveDatabaseFrom(from,name);}catch(IOException failed){return false;}
    }
    @Override public boolean deleteDatabase(String name){return storage.deleteDatabase(name);}
    @Override public String[] databaseList(){return storage.list("databases");}
}
