package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.util.*;

/** Binder identities live only in this authority generation; receipts survive its death. */
final class JobAttemptLedger {
    private final RuntimeBroker broker;
    private final Map<String,IBinder> callbacks=new HashMap<>();
    JobAttemptLedger(RuntimeBroker broker){this.broker=broker;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE job_instances(instance TEXT PRIMARY KEY,lease TEXT NOT NULL REFERENCES job_components(id),generation TEXT NOT NULL,stub TEXT NOT NULL,state TEXT NOT NULL)");
        db.execSQL("CREATE TABLE job_attempts(attempt TEXT PRIMARY KEY,version TEXT NOT NULL REFERENCES job_versions(version),instance TEXT NOT NULL REFERENCES job_instances(instance),generation TEXT NOT NULL,state TEXT NOT NULL)");
        db.execSQL("CREATE TABLE job_attempt_events(id INTEGER PRIMARY KEY AUTOINCREMENT,attempt TEXT NOT NULL REFERENCES job_attempts(attempt),event TEXT NOT NULL,detail TEXT NOT NULL,created INTEGER NOT NULL)");
    }
    Bundle begin(Bundle input,RuntimeBroker.Slot slot){
        String version=RuntimeWire.required(input,"version"),instance=RuntimeWire.required(input,"instance"),stub=RuntimeWire.required(input,"stub");
        var v=broker.jobs.get(version);var lease=broker.jobs.components.get(v.lease);String generation=slot.allocation.session.processGeneration;
        RuntimeJobBootstrap.requireVersion(broker,v,lease,slot);
        IBinder callback=input.getBinder("callback");if(callback==null||!v.physicalNamespace.equals(input.getString("namespace"))||v.jobId!=input.getInt("jobId")||!lease.stub.equals(stub))throw new SecurityException("JOB_ATTEMPT_IDENTITY_MISMATCH");
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT attempt,version FROM job_attempts WHERE instance=? AND generation=?",new String[]{instance,generation})){
            while(c.moveToNext())if(callback.equals(callbacks.get(c.getString(0))))throw new SecurityException("JOB_CALLBACK_ALREADY_CLAIMED");
        }
        var db=broker.store.getWritableDatabase();db.beginTransaction();String attempt=UUID.randomUUID().toString();try{
            try(Cursor c=db.rawQuery("SELECT lease,generation,state FROM job_instances WHERE instance=?",new String[]{instance})){
                if(c.moveToFirst()){if(!lease.id.equals(c.getString(0))||!generation.equals(c.getString(1))||!c.getString(2).equals("LIVE"))throw new SecurityException("JOB_INSTANCE_OWNER_MISMATCH");}
                else{ContentValues row=new ContentValues();row.put("instance",instance);row.put("lease",lease.id);row.put("generation",generation);row.put("stub",stub);row.put("state","LIVE");db.insertOrThrow("job_instances",null,row);}
            }
            ContentValues row=new ContentValues();row.put("attempt",attempt);row.put("version",version);row.put("instance",instance);row.put("generation",generation);row.put("state","STARTING");db.insertOrThrow("job_attempts",null,row);record(attempt,"START_ENTERED","");
            // A real system callback can precede schedule's ACK and proves admission.
            if(v.state.equals("PREPARED")||v.state.equals("UNKNOWN"))broker.jobs.accepted(v);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        callbacks.put(attempt,callback);Bundle out=new Bundle();out.putString("attempt",attempt);out.putString("component",lease.component);out.putByteArray("logical",v.logical);out.putString("namespace",v.namespace.isEmpty()?null:v.namespace);return out;
    }
    Bundle event(Bundle input,RuntimeBroker.Slot slot){
        String event=RuntimeWire.required(input,"event"),generation=slot.allocation.session.processGeneration;
        String instance=RuntimeWire.required(input,"instance");
        if(event.equals("DESTROYED")){
            ContentValues row=new ContentValues();row.put("state","DESTROYED");if(broker.store.getWritableDatabase().update("job_instances",row,"instance=? AND generation=? AND state='LIVE'",new String[]{instance,generation})!=1)throw new SecurityException("JOB_INSTANCE_STALE");releaseCallbacks("instance",instance);return new Bundle();
        }
        String attempt=RuntimeWire.required(input,"attempt");IBinder callback=input.getBinder("callback");
        if(callback==null||!callback.equals(callbacks.get(attempt)))throw new SecurityException("JOB_ATTEMPT_CALLBACK_STALE");
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT generation,instance FROM job_attempts WHERE attempt=?",new String[]{attempt})){if(!c.moveToFirst()||!generation.equals(c.getString(0))||!instance.equals(c.getString(1)))throw new SecurityException("JOB_ATTEMPT_OWNER_MISMATCH");}
        if(!Set.of("START_RETURNED","STOP_ENTERED","STOP_RETURNED","NETWORK_CHANGED","FINISH_REQUESTED","START_RPC_RETURNED","STOP_RPC_RETURNED","FINISH_RPC_RETURNED","WORK_DEQUEUE_ENTERED","WORK_DEQUEUE_RETURNED","WORK_DEQUEUE_UNCONFIRMED","WORK_COMPLETE_ENTERED","WORK_COMPLETE_RETURNED","WORK_COMPLETE_UNCONFIRMED").contains(event))throw new SecurityException("JOB_ATTEMPT_EVENT_UNSUPPORTED");
        if(event.equals("WORK_DEQUEUE_ENTERED"))broker.requiredSlot(generation);
        record(attempt,event,Boolean.toString(input.getBoolean("value")));
        boolean completed=event.equals("START_RPC_RETURNED")&&!input.getBoolean("value");
        ContentValues row=new ContentValues();row.put("state",completed?"COMPLETED":event.endsWith("UNCONFIRMED")?"UNKNOWN":event);broker.store.getWritableDatabase().update("job_attempts",row,"attempt=?",new String[]{attempt});
        if(completed||event.equals("STOP_RPC_RETURNED")||event.equals("FINISH_RPC_RETURNED"))callbacks.remove(attempt);
        return new Bundle();
    }
    private void record(String attempt,String event,String detail){ContentValues row=new ContentValues();row.put("attempt",attempt);row.put("event",event);row.put("detail",detail);row.put("created",System.currentTimeMillis());broker.store.getWritableDatabase().insertOrThrow("job_attempt_events",null,row);}
    private void releaseCallbacks(String column,String value){try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT attempt FROM job_attempts WHERE "+column+"=?",new String[]{value})){while(c.moveToNext())callbacks.remove(c.getString(0));}}
    void dead(String generation){
        releaseCallbacks("generation",generation);
        broker.store.getWritableDatabase().execSQL("UPDATE job_attempts SET state='UNKNOWN' WHERE generation=? AND state NOT IN ('STOP_RPC_RETURNED','FINISH_RPC_RETURNED','COMPLETED')",new Object[]{generation});
        broker.store.getWritableDatabase().execSQL("UPDATE job_instances SET state='PROCESS_DEAD' WHERE generation=? AND state='LIVE'",new Object[]{generation});
    }
    static void recover(SQLiteDatabase db){db.execSQL("UPDATE job_attempts SET state='UNKNOWN' WHERE state NOT IN ('STOP_RPC_RETURNED','FINISH_RPC_RETURNED','COMPLETED')");db.execSQL("UPDATE job_instances SET state='PROCESS_DEAD' WHERE state='LIVE'");}
}
