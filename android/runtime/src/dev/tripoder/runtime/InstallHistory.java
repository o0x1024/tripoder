package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Logical installation timestamps are durable commit receipts, never host package times. */
final class InstallHistory {
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE install_history(generation TEXT PRIMARY KEY,firstTime INTEGER,updateTime INTEGER NOT NULL,operation TEXT NOT NULL)");}
    static void committed(RuntimeStore store,ExecutionContract contract,String generation,String previous,String operation){
        var receipt=contract.query(operation);if(receipt.state!=ExecutionContract.OperationState.SUCCEEDED||!generation.equals(receipt.result))throw new IllegalStateException("INSTALL_COMMIT_RECEIPT_MISMATCH");
        Long first=previous==null?receipt.updatedAtMs:first(store,previous);
        put(store,generation,first,receipt.updatedAtMs,operation);
    }
    private static Long first(RuntimeStore store,String generation){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT firstTime FROM install_history WHERE generation=?",new String[]{generation})){if(!c.moveToFirst())throw new IllegalStateException("INSTALL_HISTORY_MISSING");return c.isNull(0)?null:c.getLong(0);}}
    static void resolve(RuntimeStore store,ExecutionContract contract,ApkStore.BundleInstall install)throws Exception{
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT firstTime,updateTime FROM install_history WHERE generation=?",new String[]{install.generation})){
            if(c.moveToFirst()){install.info.firstInstallTime=c.isNull(0)?0:c.getLong(0);install.info.lastUpdateTime=c.getLong(1);return;}
        }
        String operation;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation FROM installs WHERE generation=?",new String[]{install.generation})){if(!c.moveToFirst())throw new IllegalStateException("INSTALL_HISTORY_OWNER_MISSING");operation=c.getString(0);}
        var receipt=contract.query(operation);if(receipt.state!=ExecutionContract.OperationState.SUCCEEDED||!install.generation.equals(receipt.result))throw new IllegalStateException("INSTALL_COMMIT_RECEIPT_MISMATCH");
        // Migration can prove an original install by recomputing its admitted request.
        // An old upgrade without first-install evidence keeps that field unknown.
        List<ArtifactSet.Part> parts=new ArrayList<>(install.artifacts.parts);parts.sort(Comparator.comparing(p->p.path));
        StringBuilder original=new StringBuilder("install-v2|").append(install.space).append("|new");for(var part:parts)original.append('|').append(part.path).append(':').append(part.sha256);
        Long first=ApkStore.textDigest(original.toString()).equals(receipt.payloadDigest)?receipt.updatedAtMs:null;
        put(store,install.generation,first,receipt.updatedAtMs,operation);install.info.firstInstallTime=first==null?0:first;install.info.lastUpdateTime=receipt.updatedAtMs;
    }
    private static void put(RuntimeStore store,String generation,Long first,long update,String operation){
        ContentValues values=new ContentValues();values.put("generation",generation);if(first==null)values.putNull("firstTime");else values.put("firstTime",first);values.put("updateTime",update);values.put("operation",operation);store.getWritableDatabase().insertOrThrow("install_history",null,values);
    }
    private InstallHistory(){}
}
