package dev.tripoder.runtime;

import android.content.Context;
import android.database.sqlite.*;

/** Only :recovery opens this database; it never opens RuntimeStore. */
final class EngineRecoveryStore extends SQLiteOpenHelper implements EngineRecoveryPolicy.Store {
    private final java.io.File established;
    EngineRecoveryStore(Context context){super(context,"tripoder-recovery.db",null,1);established=new java.io.File(context.getNoBackupFilesDir(),"recovery-established");setWriteAheadLoggingEnabled(true);}
    @Override public void onConfigure(SQLiteDatabase db){db.execSQL("PRAGMA synchronous=FULL");}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE recovery(id INTEGER PRIMARY KEY CHECK(id=1),record BLOB NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("RECOVERY_SCHEMA_UNSUPPORTED");}
    @Override public byte[] read(){try(var c=getReadableDatabase().rawQuery("SELECT record FROM recovery WHERE id=1",null)){if(c.moveToFirst())return c.getBlob(0);if(established.exists())throw new IllegalStateException("RECOVERY_RECORD_MISSING");return null;}}
    @Override public void write(byte[] value)throws Exception{var db=getWritableDatabase();db.beginTransaction();try{android.content.ContentValues row=new android.content.ContentValues();row.put("id",1);row.put("record",value);if(db.insertWithOnConflict("recovery",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("RECOVERY_PERSIST_FAILED");db.setTransactionSuccessful();}finally{db.endTransaction();}if(!established.exists())try(var out=new java.io.FileOutputStream(established)){out.write(1);out.getFD().sync();}}
}
