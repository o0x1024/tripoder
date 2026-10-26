package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Requested aliases remain distinct Android Intent components targeting one canonical Activity. */
final class ActivityEntryLedger {
    private final RuntimeStore store;
    ActivityEntryLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE activity_entries(stub TEXT PRIMARY KEY,componentLease TEXT NOT NULL REFERENCES activity_components(lease) ON DELETE CASCADE,requested TEXT NOT NULL,UNIQUE(componentLease,requested))");}
    void requireCapacity(String lease,String canonical,String requested){
        if(canonical.equals(requested))return;
        int used=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT requested FROM activity_entries WHERE componentLease=?",new String[]{lease})){
            while(c.moveToNext()){if(requested.equals(c.getString(0)))return;if(!canonical.equals(c.getString(0)))used++;}
        }
        if(used>=RuntimeActivitySlots.ALIASES)throw new IllegalStateException("ACTIVITY_ALIAS_CAPACITY_EXHAUSTED");
    }
    String acquire(String canonicalStub,String lease,String canonical,String requested){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM activity_entries WHERE componentLease=? AND requested=?",new String[]{lease,requested})){if(c.moveToFirst())return c.getString(0);}
        String stub=canonicalStub;
        if(!canonical.equals(requested)){
            Set<String> used=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM activity_entries WHERE componentLease=?",new String[]{lease})){while(c.moveToNext())used.add(c.getString(0));}
            stub=null;for(int i=0;i<RuntimeActivitySlots.ALIASES;i++){String alias=RuntimeActivitySlots.alias(canonicalStub,i);if(!used.contains(alias)){stub=alias;break;}}
            if(stub==null)throw new IllegalStateException("ACTIVITY_ALIAS_CAPACITY_EXHAUSTED");
        }
        ContentValues row=new ContentValues();row.put("stub",stub);row.put("componentLease",lease);row.put("requested",requested);store.getWritableDatabase().insertOrThrow("activity_entries",null,row);return stub;
    }
    void require(ActivityLedger.Ticket ticket){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_entries WHERE stub=? AND componentLease=? AND requested=?",new String[]{ticket.stub,ticket.componentLease,ticket.component})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_ENTRY_LEASE_MISMATCH");}}
    Set<String> stubs(String lease){Set<String> stubs=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM activity_entries WHERE componentLease=?",new String[]{lease})){while(c.moveToNext())stubs.add(c.getString(0));}return stubs;}
}
