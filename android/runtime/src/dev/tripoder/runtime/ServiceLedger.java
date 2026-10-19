package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.util.*;

/** A physical AMS service record retains exactly one immutable virtual component owner. */
final class ServiceLedger {
    private final RuntimeStore store;
    ServiceLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE service_leases(id TEXT PRIMARY KEY,space TEXT NOT NULL,package TEXT NOT NULL,installGeneration TEXT NOT NULL,processName TEXT NOT NULL,processStub TEXT NOT NULL,stub TEXT NOT NULL,component TEXT NOT NULL,generation TEXT NOT NULL,state TEXT NOT NULL,instance TEXT)");
        db.execSQL("CREATE TABLE service_calls(operation TEXT PRIMARY KEY,lease TEXT NOT NULL,source TEXT NOT NULL,kind TEXT NOT NULL,connection TEXT NOT NULL,state TEXT NOT NULL)");
    }
    static final class Lease {
        final String id,space,pkg,install,process,processStub,stub,component,generation,state,instance;
        Lease(Cursor c){id=c.getString(0);space=c.getString(1);pkg=c.getString(2);install=c.getString(3);process=c.getString(4);processStub=c.getString(5);stub=c.getString(6);component=c.getString(7);generation=c.getString(8);state=c.getString(9);instance=c.getString(10);}
        Bundle wire(){Bundle b=new Bundle();b.putString("lease",id);b.putString("stub",stub);b.putString("component",component);b.putString("package",pkg);b.putString("generation",generation);return b;}
    }
    Lease get(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM service_leases WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("SERVICE_LEASE_MISSING");return new Lease(c);}}
    Lease forStub(String stub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM service_leases WHERE stub=? AND state!='RELEASED'",new String[]{stub})){if(!c.moveToFirst())throw new SecurityException("SERVICE_STUB_UNASSIGNED");Lease value=new Lease(c);if(c.moveToNext())throw new SecurityException("SERVICE_STUB_OWNER_CONFLICT");return value;}}
    Lease find(String space,String pkg,String component){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM service_leases WHERE space=? AND package=? AND component=? AND state!='RELEASED'",new String[]{space,pkg,component})){return c.moveToFirst()?new Lease(c):null;}}
    Lease reserve(RuntimeBroker.Slot slot,String component){
        var identity=slot.allocation.session.identity;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM service_leases WHERE space=? AND package=? AND component=? AND state!='RELEASED'",new String[]{identity.spaceId,identity.packageName,component})){
            // reserve is reached only by a newly admitted SERVICE_ROUTE request.
            // Retained stub ownership is reusable; the old Hook plan is not replayed.
            if(c.moveToFirst()){Lease existing=new Lease(c);owner(existing,slot);if(!existing.generation.equals(slot.allocation.session.processGeneration))rebind(existing,slot,ServiceRecoveryPolicy.Admission.NEW_REQUEST);return get(existing.id);}
        }
        return insert(identity.spaceId,identity.packageName,identity.installGeneration,identity.processName,slot.activityStub,component,slot.allocation.session.processGeneration,"RESERVED");
    }
    Lease reservePassive(ApkStore.BundleInstall install,String process,String component,String processStub){
        Lease existing=find(install.space,install.info.packageName,component);
        if(existing!=null){if(!existing.install.equals(install.generation)||!existing.process.equals(process)||!existing.processStub.equals(processStub))throw new SecurityException("PASSIVE_SERVICE_OWNER_MISMATCH");return existing;}
        return insert(install.space,install.info.packageName,install.generation,process,processStub,component,"","PASSIVE");
    }
    private Lease insert(String space,String pkg,String install,String processName,String processStub,String component,String generation,String state){
        int process=RuntimeSlot.index(processStub);Set<String> occupied=new HashSet<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM service_leases WHERE state!='RELEASED'",null)){while(c.moveToNext())occupied.add(c.getString(0));}
        String stub=null;for(int lane=0;lane<RuntimeServices.PER_PROCESS;lane++){String candidate=RuntimeServices.stub(process,lane);if(!occupied.contains(candidate)){stub=candidate;break;}}
        if(stub==null){
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,stub FROM service_leases WHERE processStub=? AND state='DORMANT' ORDER BY id ASC LIMIT 1",new String[]{processStub})){
                if(c.moveToFirst()){
                    String dormantId=c.getString(0);
                    stub=c.getString(1);
                    ContentValues rel=new ContentValues();rel.put("state","RELEASED");
                    store.getWritableDatabase().update("service_leases",rel,"id=?",new String[]{dormantId});
                }
            }
        }
        if(stub==null)throw new IllegalStateException("SERVICE_CAPACITY_EXHAUSTED");
        ContentValues row=new ContentValues();String id=UUID.randomUUID().toString();row.put("id",id);row.put("space",space);row.put("package",pkg);row.put("installGeneration",install);row.put("processName",processName);row.put("processStub",processStub);row.put("stub",stub);row.put("component",component);row.put("generation",generation);row.put("state",state);store.getWritableDatabase().insertOrThrow("service_leases",null,row);return get(id);
    }
    Bundle claim(String stub,RuntimeBroker.Slot slot,String instance){Lease lease=forStub(stub);owner(lease,slot);if(lease.state.equals("PASSIVE"))throw new SecurityException("PASSIVE_SERVICE_HAS_NO_START_AUTHORITY");if(!lease.generation.equals(slot.allocation.session.processGeneration))rebind(lease,slot);lease=get(lease.id);if(!Set.of("RESERVED","DORMANT").contains(lease.state))throw new SecurityException("SERVICE_INSTANCE_ALREADY_CLAIMED");ContentValues row=new ContentValues();row.put("state","CLAIMED");row.put("instance",instance);update(lease.id,row);return get(lease.id).wire();}
    void lifecycle(String id,String generation,String instance,String event){Lease lease=get(id);if(!lease.generation.equals(generation)||!Objects.equals(lease.instance,instance))throw new SecurityException("SERVICE_INSTANCE_STALE");
        String next;if(event.equals("onCreate")){if(lease.state.equals("LIVE"))return;if(!lease.state.equals("CLAIMED"))throw new SecurityException("SERVICE_CREATE_STATE");next="LIVE";}
        else if(event.equals("onDestroy")){if(lease.state.equals("DORMANT"))return;if(!lease.state.equals("LIVE"))throw new SecurityException("SERVICE_DESTROY_STATE");next="DORMANT";}
        else{if(!lease.state.equals("LIVE"))throw new SecurityException("SERVICE_CALLBACK_STATE");return;}
        ContentValues row=new ContentValues();row.put("state",next);update(id,row);
    }
    void operation(String op,Lease lease,String source,String kind,String connection){ContentValues row=new ContentValues();row.put("operation",op);row.put("lease",lease.id);row.put("source",source);row.put("kind",kind);row.put("connection",connection);row.put("state","PREPARED");store.getWritableDatabase().insertOrThrow("service_calls",null,row);}
    void complete(String op,String source,boolean applied){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT kind,connection,state FROM service_calls WHERE operation=? AND source=?",new String[]{op,source})){
            if(!c.moveToFirst()||!c.getString(2).equals("PREPARED"))throw new SecurityException("SERVICE_OPERATION_STALE");
            if(applied&&c.getString(0).equals("unbind")){ContentValues row=new ContentValues();row.put("state","RELEASED");store.getWritableDatabase().update("service_calls",row,"source=? AND connection=? AND kind='bind' AND state='APPLIED'",new String[]{source,c.getString(1)});}
        }
        ContentValues row=new ContentValues();row.put("state",applied?"APPLIED":"REJECTED");store.getWritableDatabase().update("service_calls",row,"operation=?",new String[]{op});
    }
    Lease binding(String source,String connection){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT lease FROM service_calls WHERE source=? AND connection=? AND kind='bind' AND state='APPLIED'",new String[]{source,connection})){if(!c.moveToFirst())throw new IllegalArgumentException("SERVICE_CONNECTION_NOT_BOUND");return get(c.getString(0));}}
    Lease restore(String stub){Lease lease=forStub(stub);if(lease.state.equals("PASSIVE"))throw new SecurityException("PASSIVE_SERVICE_HAS_NO_START_AUTHORITY");requireDead(lease.generation);return lease;}
    void rebind(Lease lease,RuntimeBroker.Slot slot){rebind(lease,slot,ServiceRecoveryPolicy.Admission.SYSTEM_RECOVERY);}
    private void rebind(Lease lease,RuntimeBroker.Slot slot,ServiceRecoveryPolicy.Admission admission){owner(lease,slot);if(lease.state.equals("PASSIVE")){if(!lease.generation.isEmpty()||lease.instance!=null)throw new SecurityException("PASSIVE_SERVICE_STATE_INVALID");}else requireDead(lease.generation,admission);ContentValues row=new ContentValues();row.put("generation",slot.allocation.session.processGeneration);row.put("state","RESERVED");row.putNull("instance");update(lease.id,row);}
    String retainedStub(String space,String pkg,String install,String process){String found=null;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT processStub,installGeneration FROM service_leases WHERE space=? AND package=? AND processName=? AND state!='RELEASED'",new String[]{space,pkg,process})){while(c.moveToNext()){if(!c.getString(1).equals(install))throw new IllegalStateException("SERVICE_INSTALL_PIN_CONFLICT");if(found!=null&&!found.equals(c.getString(0)))throw new IllegalStateException("SERVICE_PROCESS_PIN_CONFLICT");found=c.getString(0);}}return found;}
    boolean occupied(String processStub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM service_leases WHERE processStub=? AND state!='RELEASED' LIMIT 1",new String[]{processStub})){return c.moveToFirst();}}
    boolean occupiedByOther(String stub,ApkStore.BundleInstall install,String process){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM service_leases WHERE processStub=? AND state!='RELEASED' AND NOT(space IS ? AND package IS ? AND installGeneration IS ? AND processName IS ?) LIMIT 1",new String[]{stub,install.space,install.info.packageName,install.generation,process})){return c.moveToFirst();}}
    /** Explicit uninstall of one installation: a call still PREPARED whose calling generation is DEAD can no longer take effect, because Android drops the caller's connections when its process dies. It is closed as ABANDONED, not REJECTED, so it stays distinguishable; the lease itself is then released only by the usual reconcile rules. */
    int abandonDeadCalls(String space,String pkg,String install){
        ContentValues row=new ContentValues();row.put("state","ABANDONED");
        return store.getWritableDatabase().update("service_calls",row,"state='PREPARED' AND source IN (SELECT generation FROM sessions WHERE state='DEAD') AND lease IN (SELECT id FROM service_leases WHERE space=? AND package=? AND installGeneration=?)",new String[]{space,pkg,install});
    }
    /** No timeout-based reclamation. Dead callers and actual absence of an AMS service are required. */
    void reconcile(Context host){
        List<ActivityManager.RunningServiceInfo> observed=((ActivityManager)host.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(Integer.MAX_VALUE);
        if(observed==null)throw new IllegalStateException("SERVICE_OBSERVATION_UNAVAILABLE");Set<String> system=new HashSet<>();for(var service:observed)if(service.service.getPackageName().equals(host.getPackageName()))system.add(service.service.getClassName());
        store.getWritableDatabase().execSQL("UPDATE service_calls SET state='RELEASED' WHERE kind='bind' AND state='APPLIED' AND source IN (SELECT generation FROM sessions WHERE state='DEAD')");
        // PASSIVE has never authorized a process/Service creation. All dispatch results
        // must be known and all real bindings gone before its unactivated owner can retire.
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT l.id,l.stub FROM service_leases l WHERE l.state='PASSIVE' AND l.generation='' AND NOT EXISTS (SELECT 1 FROM service_calls o WHERE o.lease=l.id AND (o.state='PREPARED' OR (o.kind='bind' AND o.state='APPLIED')))",null)){
            while(c.moveToNext())if(!system.contains(c.getString(1))){ContentValues row=new ContentValues();row.put("state","RELEASED");update(c.getString(0),row);}
        }
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT l.id,l.stub FROM service_leases l JOIN sessions s ON l.generation=s.generation WHERE s.state='DEAD' AND l.state!='RELEASED' AND NOT EXISTS (SELECT 1 FROM service_calls o WHERE o.lease=l.id AND (o.state='PREPARED' OR (o.kind='bind' AND o.state='APPLIED')))",null)){
            while(c.moveToNext())if(!system.contains(c.getString(1))){ContentValues row=new ContentValues();row.put("state","RELEASED");update(c.getString(0),row);}
        }
    }
    private void owner(Lease lease,RuntimeBroker.Slot slot){var identity=slot.allocation.session.identity;if(!lease.space.equals(identity.spaceId)||!lease.pkg.equals(identity.packageName)||!lease.install.equals(identity.installGeneration)||!lease.process.equals(identity.processName)||!lease.processStub.equals(slot.activityStub))throw new SecurityException("SERVICE_LEASE_OWNER_MISMATCH");}
    private void requireDead(String generation){requireDead(generation,ServiceRecoveryPolicy.Admission.SYSTEM_RECOVERY);}
    private void requireDead(String generation,ServiceRecoveryPolicy.Admission admission){ServiceRecoveryPolicy.require(store.closeRequested(generation),store.isDead(generation),store.hookRecoveryBlocked(generation),admission);}
    private void update(String id,ContentValues row){if(store.getWritableDatabase().update("service_leases",row,"id=?",new String[]{id})!=1)throw new IllegalStateException("SERVICE_PERSIST_FAILED");}
}
