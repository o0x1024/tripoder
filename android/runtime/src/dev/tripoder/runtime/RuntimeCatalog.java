package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import java.util.*;

/** Metadata only. All callers are serialized by RuntimeBroker's authority. */
public final class RuntimeCatalog {
    private final RuntimeStore store;
    private final ActivityComponentLedger activityComponents;
    RuntimeCatalog(RuntimeStore store,ActivityComponentLedger activityComponents){this.store=store;this.activityComponents=activityComponents;}
    public void createSpace(String id){RuntimeFiles.id(id);ContentValues row=new ContentValues();row.put("id",id);row.put("created",System.currentTimeMillis());store.getWritableDatabase().insertOrThrow("spaces",null,row);}
    public void requireSpace(String id){RuntimeFiles.id(id);try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM spaces WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new IllegalStateException("SPACE_NOT_FOUND:"+id);}}
    public List<String> spaces(){List<String> result=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM spaces ORDER BY id",null)){while(c.moveToNext())result.add(c.getString(0));}return result;}
    public void removeEmptySpace(String id){requireSpace(id);try(Cursor c=store.getReadableDatabase().rawQuery("SELECT package FROM installs WHERE space=? UNION SELECT id FROM project_refs WHERE space=?",new String[]{id,id})){if(c.moveToFirst())throw new IllegalStateException("SPACE_NOT_EMPTY");}store.getWritableDatabase().delete("spaces","id=?",new String[]{id});}
    public void requireUnused(String space,String pkg){activityComponents.releaseUnused();requireUnusedMetadata(space,pkg);}
    /** Revalidate durable references while the caller's metadata transaction is open. */
    void requireUnusedMetadata(String space,String pkg){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation FROM sessions WHERE space=? AND package=? AND state NOT IN ('DEAD','FAILED')",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT ticket FROM activity_tickets WHERE space=? AND package=? AND state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_TASK");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT l.state,t.state,l.kind,t.installGeneration,t.taskId FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate WHERE t.space=? AND t.package=? AND l.state NOT IN ('RECEIVED','SYSTEM_CONFIRMED','RETIRED') LIMIT 1",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_ACTIVITY_DELIVERY:launch="+c.getString(0)+";ticket="+c.getString(1)+";kind="+c.getString(2)+";install="+c.getString(3)+";task="+c.getInt(4));}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT l.state,l.generation,COALESCE(s.state,'NONE'),l.stub,l.component,(SELECT group_concat(o.kind||':'||o.state) FROM service_calls o WHERE o.lease=l.id AND (o.state='PREPARED' OR (o.kind='bind' AND o.state='APPLIED'))) FROM service_leases l LEFT JOIN sessions s ON s.generation=l.generation WHERE l.space=? AND l.package=? AND l.state!='RELEASED'",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_SERVICE:lease="+c.getString(0)+";generation="+c.getString(1)+";session="+c.getString(2)+";stub="+c.getString(3)+";component="+c.getString(4)+";calls="+c.getString(5)+";rows="+c.getCount());}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM provider_leases pl JOIN sessions s ON pl.generation=s.generation JOIN provider_acquisitions pa ON pa.lease=pl.id LEFT JOIN provider_calls pc ON pc.cap=pa.cap WHERE s.space=? AND s.package=? AND (pa.state='OPEN' OR pc.state IN ('PREPARED','RUNNING','UNKNOWN')) LIMIT 1",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_PROVIDER");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM broadcast_deliveries b JOIN sessions s ON b.target=s.generation WHERE s.space=? AND s.package=? AND b.state!='COMPLETED' AND b.resourceRetired=0 LIMIT 1",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_BROADCAST");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT j.component,(SELECT COUNT(*) FROM job_versions v WHERE v.lease=j.id),(SELECT COUNT(*) FROM job_versions v WHERE v.lease=j.id AND v.fenced=0),(SELECT COUNT(*) FROM job_instances i WHERE i.lease=j.id AND i.state='LIVE'),(SELECT COUNT(*) FROM job_mutations m WHERE m.installGeneration=j.installGeneration AND m.closed=0),(SELECT group_concat(s.state) FROM sessions s WHERE s.activityStub=j.processStub AND s.state NOT IN ('DEAD','FAILED')) FROM job_components j WHERE j.space=? AND j.package=? AND j.retired=0",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_JOB:component="+c.getString(0)+";versions="+c.getInt(1)+";unfenced="+c.getInt(2)+";liveInstances="+c.getInt(3)+";openMutations="+c.getInt(4)+";liveSessions="+c.getString(5)+";rows="+c.getCount());}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM job_mutations j JOIN installs i ON i.generation=j.installGeneration WHERE i.space=? AND i.package=? AND j.closed=0 LIMIT 1",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_JOB_MUTATION");}
        activityComponents.requireUnused(space,pkg);
    }
    void invalidateProjects(String space,String pkg){ContentValues v=new ContentValues();v.put("state","STALE");store.getWritableDatabase().update("project_refs",v,"space=? AND package=?",new String[]{space,pkg});}
}
