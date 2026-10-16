package dev.tripoder.runtime;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.io.*;

/** Context entry points share exactly one process-bound storage policy. */
abstract class GuestStorageContext extends ContextWrapper {
    private final RuntimeStorage storage;
    GuestStorageContext(Context base,RuntimeStorage storage){super(base);this.storage=storage;}
    @Override public File getDataDir(){return storage.root;}
    @Override public File getFilesDir(){return storage.dir("files");}
    @Override public File getCacheDir(){return storage.dir("cache");}
    @Override public File getCodeCacheDir(){return storage.dir("code-cache");}
    @Override public File getNoBackupFilesDir(){return storage.dir("no-backup");}
    @Override public File getFileStreamPath(String name){return storage.file("files",name);}
    @Override public File getDir(String name,int mode){if(mode!=MODE_PRIVATE)throw new IllegalArgumentException("FILE_MODE_UNSUPPORTED");return storage.dir("app_"+RuntimeStorage.name(name));}
    @Override public boolean deleteFile(String name){return getFileStreamPath(name).delete();}
    @Override public String[] fileList(){return storage.list("files");}
    @Override public File getExternalFilesDir(String type){if(type!=null)throw new UnsupportedOperationException("EXTERNAL_TYPE_UNSUPPORTED");return storage.dir("external");}
    @Override public File[] getExternalFilesDirs(String type){return new File[]{getExternalFilesDir(type)};}
    @Override public File getExternalCacheDir(){return storage.dir("external-cache");}
    @Override public File[] getExternalCacheDirs(){return new File[]{getExternalCacheDir()};}
    @Override public File getObbDir(){throw new UnsupportedOperationException("OBB_UNSUPPORTED");}
    @Override public File[] getObbDirs(){throw new UnsupportedOperationException("OBB_UNSUPPORTED");}
    @Override public File[] getExternalMediaDirs(){throw new UnsupportedOperationException("EXTERNAL_MEDIA_UNSUPPORTED");}
    @Override public FileOutputStream openFileOutput(String name,int mode)throws FileNotFoundException{return storage.output(name,mode);}
    @Override public FileInputStream openFileInput(String name)throws FileNotFoundException{return storage.input(name);}
    @Override public SharedPreferences getSharedPreferences(String name,int mode){return storage.preferences(name,mode);}
    @Override public boolean deleteSharedPreferences(String name){return storage.deletePreferences(name);}
    @Override public boolean moveSharedPreferencesFrom(Context source,String name){throw new UnsupportedOperationException("CROSS_CONTEXT_PREFERENCES_MOVE_UNSUPPORTED");}
    @Override public File getDatabasePath(String name){return storage.database(name);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return storage.database(name,mode,factory,null);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return storage.database(name,mode,factory,handler);}
    @Override public boolean moveDatabaseFrom(Context source,String name){throw new UnsupportedOperationException("CROSS_CONTEXT_DATABASE_MOVE_UNSUPPORTED");}
    @Override public boolean deleteDatabase(String name){return storage.deleteDatabase(name);}
    @Override public String[] databaseList(){return storage.list("databases");}
}
