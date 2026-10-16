package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.PackageManager;
import android.content.ContentValues;

/** Removed physical names are never reused or dispatched to a different application. */
final class RuntimeRetiredActivities {
    static void reconcile(Context context,RuntimeStore store){
        java.util.Set<String> absent=new java.util.HashSet<>();
        try(var c=store.getReadableDatabase().rawQuery("SELECT t.ticket,t.stub FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.state NOT IN ('RETIRED') AND s.state='DEAD'",null)){
            while(c.moveToNext()){
                String stub=c.getString(1);if(!RuntimeSlot.isActivity(stub)&&!RuntimeActivitySlots.retired(stub))continue;
                try{if(!absent.contains(stub))context.getPackageManager().getActivityInfo(new ComponentName(context,stub),PackageManager.MATCH_DISABLED_COMPONENTS);else throw new PackageManager.NameNotFoundException(stub);throw new SecurityException("RETIRED_ACTIVITY_REDECLARED");}
                catch(PackageManager.NameNotFoundException removed){absent.add(stub);
                    ContentValues row=new ContentValues();row.put("state","RETIRED");row.put("resourceProof","schema13:declared-activity-absent;factory-rejects;process-dead;name-permanently-retired:"+stub);store.getWritableDatabase().update("activity_tickets",row,"ticket=?",new String[]{c.getString(0)});
                }
            }
        }
        // Old component names have no entry in the new manifest and can never
        // be issued again. This proof is stronger than an absent recent task.
        // Preserve every ticket/operation; release only its obsolete name lease.
        java.util.List<String[]> released=new java.util.ArrayList<>();
        try(var c=store.getReadableDatabase().rawQuery("SELECT ac.stub,ac.lease FROM activity_components ac WHERE NOT EXISTS (SELECT 1 FROM activity_tickets t WHERE t.componentLease=ac.lease AND t.state!='RETIRED') AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.generation=ac.generation AND s.state!='DEAD')",null)){
            while(c.moveToNext()){
                String stub=c.getString(0),lease=c.getString(1);java.util.Set<String> names=new ActivityEntryLedger(store).stubs(lease);names.add(stub);boolean proven=true;
                for(String name:names){
                    if(!RuntimeSlot.isActivity(name)&&!RuntimeActivitySlots.retired(name)){proven=false;break;}
                    try{context.getPackageManager().getActivityInfo(new ComponentName(context,name),PackageManager.MATCH_DISABLED_COMPONENTS);throw new SecurityException("RETIRED_ACTIVITY_REDECLARED");}
                    catch(PackageManager.NameNotFoundException missing){/* Exact physical name is permanently rejected by the factory. */}
                }
                if(proven)released.add(new String[]{stub,lease});
            }
        }
        for(String[] lease:released)if(store.getWritableDatabase().delete("activity_components","stub=? AND lease=?",lease)!=1)throw new SecurityException("RETIRED_COMPONENT_LEASE_CHANGED");
    }
    private RuntimeRetiredActivities(){}
}
