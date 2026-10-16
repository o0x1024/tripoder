package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** An Android affinity is shared across processes, but never across logical UID domains. */
final class ActivityAffinityLedger {
    private final RuntimeStore store;
    ActivityAffinityLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE activity_affinities(lease TEXT PRIMARY KEY,space TEXT NOT NULL,uidDomain TEXT NOT NULL,logical TEXT NOT NULL,slot INTEGER UNIQUE NOT NULL,UNIQUE(space,uidDomain,logical))");}
    static final class Affinity {final String lease;final int slot;Affinity(String lease,int slot){this.lease=lease;this.slot=slot;}}
    Affinity acquire(String space,String pkg,String logical){
        if(logical==null)return new Affinity(null,RuntimeActivitySlots.NO_AFFINITY);
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT lease,slot FROM activity_affinities WHERE space=? AND uidDomain=? AND logical=?",new String[]{space,pkg,logical})){if(c.moveToFirst())return new Affinity(c.getString(0),c.getInt(1));}
        Set<Integer> used=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT slot FROM activity_affinities",null)){while(c.moveToNext())used.add(c.getInt(0));}
        for(int slot=0;slot<RuntimeActivitySlots.AFFINITIES;slot++)if(!used.contains(slot)){
            String lease=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("lease",lease);row.put("space",space);row.put("uidDomain",pkg);row.put("logical",logical);row.put("slot",slot);store.getWritableDatabase().insertOrThrow("activity_affinities",null,row);return new Affinity(lease,slot);
        }
        throw new IllegalStateException("ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED");
    }
    void releaseUnused(){store.getWritableDatabase().execSQL("DELETE FROM activity_affinities WHERE NOT EXISTS (SELECT 1 FROM activity_components ac WHERE ac.affinityLease=activity_affinities.lease)");}
}
