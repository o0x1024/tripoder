package dev.tripoder.runtime;

import android.app.ApplicationStartInfo;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.Settings;
import java.util.*;

/** Actual logical Application boundaries. Never attributes a physical engine history by PID alone. */
final class ApplicationStartLedger {
    private final RuntimeStore store;
    private final int boot;
    ApplicationStartLedger(RuntimeStore store,Context host)throws Settings.SettingNotFoundException{
        this.store=store;boot=Settings.Global.getInt(host.getContentResolver(),Settings.Global.BOOT_COUNT);
        if(boot<0)throw new IllegalStateException("DEVICE_BOOT_ID_UNAVAILABLE");
    }
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE application_starts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,generation TEXT NOT NULL UNIQUE REFERENCES sessions(generation),space TEXT NOT NULL,package TEXT NOT NULL,installGeneration TEXT NOT NULL,processName TEXT NOT NULL,pid INTEGER NOT NULL,uid INTEGER NOT NULL,boot INTEGER NOT NULL,startedNs INTEGER NOT NULL,onCreateNs INTEGER,readyNs INTEGER,failedNs INTEGER,state TEXT NOT NULL)");
        db.execSQL("CREATE INDEX application_start_scope ON application_starts(space,package,installGeneration,boot,sequence)");
    }
    void event(ExecutionContract.Session session,Bundle input){
        if(!input.keySet().equals(Set.of("generation","stage")))throw new SecurityException("APPLICATION_START_EVENT_SHAPE_REJECTED");
        String stage=RuntimeWire.required(input,"stage"),generation=session.processGeneration;
        if(!Set.of("STARTING","ON_CREATE","READY","FAILED").contains(stage))throw new SecurityException("APPLICATION_START_STAGE_REJECTED");
        long now=System.nanoTime();if(now<=0)throw new IllegalStateException("APPLICATION_START_CLOCK_UNAVAILABLE");
        ContentValues row=new ContentValues();row.put("state",stage);
        if(stage.equals("STARTING")){
            if(session.pid<=0)throw new SecurityException("APPLICATION_START_PID_MISSING");
            row.put("generation",generation);row.put("space",session.identity.spaceId);row.put("package",session.identity.packageName);
            row.put("installGeneration",session.identity.installGeneration);row.put("processName",session.identity.processName);
            row.put("pid",session.pid);row.put("uid",session.uid);row.put("boot",boot);row.put("startedNs",now);
            store.getWritableDatabase().insertOrThrow("application_starts",null,row);return;
        }
        String expected;
        if(stage.equals("ON_CREATE")){row.put("onCreateNs",now);expected="state='STARTING'";}
        else if(stage.equals("READY")){row.put("readyNs",now);expected="state='ON_CREATE'";}
        else{row.put("failedNs",now);expected="state IN ('STARTING','ON_CREATE','READY')";}
        if(store.getWritableDatabase().update("application_starts",row,"generation=? AND pid=? AND boot=? AND "+expected,
            new String[]{generation,Integer.toString(session.pid),Integer.toString(boot)})!=1)throw new SecurityException("APPLICATION_START_TRANSITION_REJECTED");
    }
    Bundle query(ExecutionContract.Session session,Bundle input){
        if(!input.keySet().equals(Set.of("generation","maxNum"))||!(input.get("maxNum") instanceof Integer))throw new SecurityException("APPLICATION_START_QUERY_SHAPE_REJECTED");
        int max=input.getInt("maxNum");if(max<0)throw new IllegalArgumentException("APPLICATION_START_LIMIT_INVALID");
        int limit=max==0?64:Math.min(max,64);ArrayList<Bundle> rows=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT package,processName,pid,uid,startedNs,onCreateNs,state FROM application_starts WHERE space=? AND package=? AND installGeneration=? AND boot=? ORDER BY sequence DESC LIMIT ?",
            new String[]{session.identity.spaceId,session.identity.packageName,session.identity.installGeneration,Integer.toString(boot),Integer.toString(limit)})){
            while(c.moveToNext()){
                Bundle row=new Bundle();row.putString("package",c.getString(0));row.putString("processName",c.getString(1));row.putInt("pid",c.getInt(2));row.putInt("uid",c.getInt(3));
                row.putLong("startedNs",c.getLong(4));row.putLong("createdMs",c.getLong(4)/1_000_000L);if(!c.isNull(5))row.putLong("onCreateNs",c.getLong(5));
                row.putInt("startupState",c.getString(6).equals("FAILED")?ApplicationStartInfo.STARTUP_STATE_ERROR:ApplicationStartInfo.STARTUP_STATE_STARTED);
                row.putInt("startType",ApplicationStartInfo.START_TYPE_COLD);row.putInt("reason",ApplicationStartInfo.START_REASON_OTHER);row.putInt("component",ApplicationStartInfo.START_COMPONENT_OTHER);
                row.putInt("launchMode",ApplicationStartInfo.LAUNCH_MODE_STANDARD);rows.add(row);
            }
        }
        Bundle result=new Bundle();result.putParcelableArrayList("starts",rows);return result;
    }
}
