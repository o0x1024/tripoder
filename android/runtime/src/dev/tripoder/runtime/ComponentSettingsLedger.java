package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

/** One durable component override per retained application data incarnation. */
final class ComponentSettingsLedger {
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE component_settings(space TEXT NOT NULL,package TEXT NOT NULL,dataId TEXT NOT NULL,component TEXT NOT NULL,state INTEGER NOT NULL CHECK(state IN (1,2)),PRIMARY KEY(space,package,dataId,component))");}
    static Bundle snapshot(SQLiteDatabase db,String space,String pkg,String dataId){
        Bundle out=new Bundle();try(var c=db.rawQuery("SELECT component,state FROM component_settings WHERE space=? AND package=? AND dataId=? ORDER BY component",new String[]{space,pkg,dataId})){
            while(c.moveToNext())out.putInt(c.getString(0),c.getInt(1));
        }
        android.os.Parcel size=android.os.Parcel.obtain();try{size.writeBundle(out);if(size.dataSize()>512*1024)throw new IllegalStateException("COMPONENT_SETTINGS_SNAPSHOT_LIMIT");}finally{size.recycle();}return out;
    }
    static void set(SQLiteDatabase db,String space,String pkg,String dataId,String component,int state){
        if(state<0||state>2)throw new IllegalArgumentException("COMPONENT_STATE_INVALID");
        if(state==0){db.delete("component_settings","space=? AND package=? AND dataId=? AND component=?",new String[]{space,pkg,dataId,component});return;}
        ContentValues row=new ContentValues();row.put("space",space);row.put("package",pkg);row.put("dataId",dataId);row.put("component",component);row.put("state",state);
        if(db.insertWithOnConflict("component_settings",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("COMPONENT_SETTING_PERSIST_FAILED");
    }
    static void remove(SQLiteDatabase db,String space,String pkg,String dataId){db.delete("component_settings","space=? AND package=? AND dataId=?",new String[]{space,pkg,dataId});}
    private ComponentSettingsLedger(){}
}
