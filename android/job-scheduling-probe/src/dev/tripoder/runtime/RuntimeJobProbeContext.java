package dev.tripoder.runtime;

import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/** New owned fixture storage per run; never clears or mutates the user's runtime database. */
final class RuntimeJobProbeContext extends ContextWrapper {
    private final File directory;
    RuntimeJobProbeContext(Context host,String run)throws Exception{super(host);directory=new File(host.getFilesDir(),"job-scheduling-tests/"+RuntimeFiles.id(run));RuntimeFiles.mkdir(directory);}
    @Override public File getFilesDir(){return directory;}
    @Override public File getDatabasePath(String name){return new File(directory,name);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
}
