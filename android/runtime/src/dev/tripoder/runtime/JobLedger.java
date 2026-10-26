package dev.tripoder.runtime;

import android.app.job.JobInfo;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Immutable submission versions; fences and receipt facts are separate from resource retirement. */
final class JobLedger {
    final RuntimeStore store;
    final JobComponentLedger components;
    JobLedger(RuntimeStore store){this.store=store;components=new JobComponentLedger(store);}
    static void create(SQLiteDatabase db){
        JobComponentLedger.create(db);
        db.execSQL("CREATE TABLE job_versions(version TEXT PRIMARY KEY,lease TEXT NOT NULL REFERENCES job_components(id),installGeneration TEXT NOT NULL,namespace TEXT NOT NULL,physicalNamespace TEXT NOT NULL,jobId INTEGER NOT NULL,logical BLOB NOT NULL,physical BLOB NOT NULL,operation TEXT NOT NULL REFERENCES operations(id),state TEXT NOT NULL,fenced INTEGER NOT NULL DEFAULT 0,hookGeneration TEXT,hookOperation TEXT)");
        db.execSQL("CREATE INDEX job_logical_key ON job_versions(installGeneration,namespace,jobId)");
        db.execSQL("CREATE TABLE job_mutations(operation TEXT PRIMARY KEY REFERENCES operations(id),installGeneration TEXT NOT NULL,kind TEXT NOT NULL,closed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE UNIQUE INDEX job_mutation_owner ON job_mutations(installGeneration) WHERE closed=0");
        db.execSQL("CREATE TABLE job_mutation_targets(operation TEXT NOT NULL REFERENCES job_mutations(operation),version TEXT NOT NULL REFERENCES job_versions(version),PRIMARY KEY(operation,version))");
    }
    static final class Version {
        final String version,lease,install,namespace,physicalNamespace,operation,state,hookGeneration,hookOperation;
        final int jobId;final byte[] logical,physical;final boolean fenced;
        Version(Cursor c){version=c.getString(0);lease=c.getString(1);install=c.getString(2);namespace=c.getString(3);physicalNamespace=c.getString(4);jobId=c.getInt(5);logical=c.getBlob(6);physical=c.getBlob(7);operation=c.getString(8);state=c.getString(9);fenced=c.getInt(10)!=0;hookGeneration=c.getString(11);hookOperation=c.getString(12);}
        JobInfo logical(){return RuntimeJobInfo.decode(logical);}
        JobInfo physical(){return RuntimeJobInfo.decode(physical);}
    }
    Version get(String version){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM job_versions WHERE version=?",new String[]{version})){if(!c.moveToFirst())throw new SecurityException("JOB_VERSION_UNKNOWN");return new Version(c);}}
    List<Version> versions(String install){List<Version> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM job_versions WHERE installGeneration=? ORDER BY rowid",new String[]{install})){while(c.moveToNext())out.add(new Version(c));}return out;}
    Version current(String install,String namespace,int jobId){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM job_versions WHERE installGeneration=? AND namespace=? AND jobId=? AND fenced=0 AND state='ACCEPTED' ORDER BY rowid DESC",new String[]{install,namespace,Integer.toString(jobId)})){
            if(!c.moveToFirst())return null;Version found=new Version(c);if(c.moveToNext())throw new IllegalStateException("JOB_CURRENT_VERSION_CONFLICT");return found;
        }
    }
    void requireIdle(String install){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation FROM job_mutations WHERE installGeneration=? AND closed=0",new String[]{install})){if(c.moveToFirst())throw new IllegalStateException("JOB_MUTATION_IN_PROGRESS:"+c.getString(0));}}
    void mutation(String operation,String install,String kind){ContentValues row=new ContentValues();row.put("operation",operation);row.put("installGeneration",install);row.put("kind",kind);store.getWritableDatabase().insertOrThrow("job_mutations",null,row);}
    void target(String operation,String version){ContentValues row=new ContentValues();row.put("operation",operation);row.put("version",version);store.getWritableDatabase().insertOrThrow("job_mutation_targets",null,row);}
    List<Version> targets(String operation){List<Version> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT v.* FROM job_versions v JOIN job_mutation_targets t ON t.version=v.version WHERE t.operation=? ORDER BY v.rowid",new String[]{operation})){while(c.moveToNext())out.add(new Version(c));}return out;}
    void prepared(String version,JobComponentLedger.Lease lease,String namespace,JobInfo logical,JobInfo physical,String operation,String hookGeneration,String hookOperation){
        ContentValues row=new ContentValues();row.put("version",version);row.put("lease",lease.id);row.put("installGeneration",lease.install);row.put("namespace",namespace);row.put("physicalNamespace",RuntimeJobNamespace.physical(lease.space,lease.install,namespace));row.put("jobId",logical.getId());row.put("logical",RuntimeJobInfo.encode(logical));row.put("physical",RuntimeJobInfo.encode(physical));row.put("operation",operation);row.put("state","PREPARED");row.put("hookGeneration",hookGeneration);row.put("hookOperation",hookOperation);store.getWritableDatabase().insertOrThrow("job_versions",null,row);target(operation,version);
    }
    void accepted(Version selected){
        store.getWritableDatabase().execSQL("UPDATE job_versions SET fenced=1 WHERE installGeneration=? AND namespace=? AND jobId=? AND version!=?",new Object[]{selected.install,selected.namespace,selected.jobId,selected.version});
        state(selected.version,"ACCEPTED");
    }
    void state(String version,String state){ContentValues row=new ContentValues();row.put("state",state);if(store.getWritableDatabase().update("job_versions",row,"version=?",new String[]{version})!=1)throw new IllegalStateException("JOB_VERSION_PERSIST_FAILED");}
    void fence(String version){store.getWritableDatabase().execSQL("UPDATE job_versions SET fenced=1 WHERE version=?",new Object[]{version});}
    void close(String operation){ContentValues row=new ContentValues();row.put("closed",1);if(store.getWritableDatabase().update("job_mutations",row,"operation=? AND closed=0",new String[]{operation})!=1)throw new IllegalStateException("JOB_MUTATION_OWNER_MISSING");}
    List<String> openSchedules(){List<String> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation FROM job_mutations WHERE kind='SCHEDULE' AND closed=0",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    List<String> openCancels(){List<String> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation FROM job_mutations WHERE kind='CANCEL' AND closed=0",null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    void recovered(){store.getWritableDatabase().execSQL("UPDATE job_versions SET state='UNKNOWN' WHERE state='PREPARED'");}
    void hook(String generation,String operation){
        store.getWritableDatabase().execSQL("UPDATE job_versions SET hookGeneration=?,hookOperation=? WHERE hookOperation IS NULL AND fenced=0 AND lease IN (SELECT j.id FROM job_components j JOIN sessions s ON s.space=j.space AND s.package=j.package AND s.installGeneration=j.installGeneration AND s.processName=j.processName WHERE s.generation=?)",new Object[]{generation,operation,generation});
    }
}
