package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** A pending platform job retains a component and process identity, never a worker session. */
final class JobComponentLedger {
    private final RuntimeStore store;
    JobComponentLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE job_components(id TEXT PRIMARY KEY,space TEXT NOT NULL,package TEXT NOT NULL,installGeneration TEXT NOT NULL,processName TEXT NOT NULL,processStub TEXT NOT NULL,stub TEXT NOT NULL,component TEXT NOT NULL,retired INTEGER NOT NULL DEFAULT 0,resourceProof TEXT)");
        db.execSQL("CREATE UNIQUE INDEX job_component_owner ON job_components(stub) WHERE retired=0");
        db.execSQL("CREATE UNIQUE INDEX job_logical_component ON job_components(installGeneration,component) WHERE retired=0");
    }
    static final class Lease {
        final String id,space,pkg,install,process,processStub,stub,component;
        Lease(Cursor c){id=c.getString(0);space=c.getString(1);pkg=c.getString(2);install=c.getString(3);process=c.getString(4);processStub=c.getString(5);stub=c.getString(6);component=c.getString(7);}
    }
    Lease get(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM job_components WHERE id=? AND retired=0",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("JOB_COMPONENT_MISSING");return new Lease(c);}}
    Lease reserve(ApkStore.BundleInstall install,String process,String component,String processStub){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM job_components WHERE installGeneration=? AND component=? AND retired=0",new String[]{install.generation,component})){
            if(c.moveToFirst()){Lease lease=new Lease(c);if(!lease.space.equals(install.space)||!lease.pkg.equals(install.info.packageName)||!lease.process.equals(process)||!lease.processStub.equals(processStub))throw new SecurityException("JOB_COMPONENT_OWNER_MISMATCH");return lease;}
        }
        Set<String> occupied=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM job_components WHERE retired=0",null)){while(c.moveToNext())occupied.add(c.getString(0));}
        String stub=null;for(int lane=0;lane<RuntimeJobSlots.PER_PROCESS;lane++){String candidate=RuntimeJobSlots.stub(RuntimeSlot.index(processStub),lane);if(!occupied.contains(candidate)){stub=candidate;break;}}
        if(stub==null)throw new IllegalStateException("JOB_COMPONENT_CAPACITY_EXHAUSTED");
        ContentValues row=new ContentValues();String id=UUID.randomUUID().toString();row.put("id",id);row.put("space",install.space);row.put("package",install.info.packageName);row.put("installGeneration",install.generation);row.put("processName",process);row.put("processStub",processStub);row.put("stub",stub);row.put("component",component);
        store.getWritableDatabase().insertOrThrow("job_components",null,row);return get(id);
    }
    String retainedStub(String space,String pkg,String install,String process){
        String found=null;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT processStub,installGeneration FROM job_components WHERE space=? AND package=? AND processName=? AND retired=0",new String[]{space,pkg,process})){
            while(c.moveToNext()){if(!install.equals(c.getString(1)))throw new IllegalStateException("JOB_INSTALL_PIN_CONFLICT");if(found!=null&&!found.equals(c.getString(0)))throw new IllegalStateException("JOB_PROCESS_PIN_CONFLICT");found=c.getString(0);}
        }return found;
    }
    boolean occupied(String stub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM job_components WHERE processStub=? AND retired=0 LIMIT 1",new String[]{stub})){return c.moveToFirst();}}
    boolean occupiedByOther(String stub,ApkStore.BundleInstall install,String process){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM job_components WHERE processStub=? AND retired=0 AND NOT(space IS ? AND package IS ? AND installGeneration IS ? AND processName IS ?) LIMIT 1",new String[]{stub,install.space,install.info.packageName,install.generation,process})){return c.moveToFirst();}}
    // RuntimeJobRetirement requires permanent admission fences plus real pending,
    // service and process exit observations. Historical rows and UNKNOWN remain.
}
