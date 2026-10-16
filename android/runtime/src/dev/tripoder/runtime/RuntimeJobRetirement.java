package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import java.util.*;

/** Resource retirement is independent of unknown business outcomes. Never fences a valid retry. */
final class RuntimeJobRetirement {
    private static final class Candidate {
        final JobComponentLedger.Lease lease;final String versions;
        Candidate(JobComponentLedger.Lease lease,String versions){this.lease=lease;this.versions=versions;}
    }
    static void reconcile(RuntimeBroker broker){
        if(Thread.holdsLock(broker))throw new IllegalStateException("JOB_RETIREMENT_INSIDE_BROKER_LOCK");
        List<Candidate> candidates=new ArrayList<>();
        synchronized(broker){
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT * FROM job_components WHERE retired=0",null)){
                while(c.moveToNext()){var lease=new JobComponentLedger.Lease(c);String version=eligible(broker,lease);if(version!=null)candidates.add(new Candidate(lease,version));}
            }
        }
        if(candidates.isEmpty())return;
        var manager=Objects.requireNonNull(broker.host.getSystemService(ActivityManager.class));
        var services=Objects.requireNonNull(manager.getRunningServices(Integer.MAX_VALUE),"JOB_SERVICE_OBSERVATION_UNAVAILABLE");
        var processes=Objects.requireNonNull(manager.getRunningAppProcesses(),"JOB_PROCESS_OBSERVATION_UNAVAILABLE");
        var pending=Objects.requireNonNull(broker.host.getSystemService(JobScheduler.class).getPendingJobsInAllNamespaces(),"JOB_PENDING_OBSERVATION_UNAVAILABLE");
        Set<String> retained=new HashSet<>();
        for(var service:services)if(service.service.getPackageName().equals(broker.host.getPackageName()))retained.add(service.service.getClassName());
        for(var jobs:pending.values())for(JobInfo job:jobs)if(job.getService().getPackageName().equals(broker.host.getPackageName()))retained.add(job.getService().getClassName());
        Set<String> live=new HashSet<>();for(var process:processes)if(process.uid==android.os.Process.myUid())live.add(process.processName);
        long observed=System.currentTimeMillis();
        synchronized(broker){for(var candidate:candidates){
            var lease=candidate.lease;String physical=broker.host.getPackageName()+":slot"+RuntimeSlot.index(lease.processStub);
            if(retained.contains(lease.stub)||live.contains(physical)||!candidate.versions.equals(eligible(broker,lease)))continue;
            ContentValues row=new ContentValues();row.put("retired",1);row.put("resourceProof","fenced-versions="+candidate.versions+";pending-absent;ams-service-absent="+lease.stub+";process-absent="+physical+";observedAt="+observed);
            if(broker.store.getWritableDatabase().update("job_components",row,"id=? AND retired=0",new String[]{lease.id})!=1)throw new IllegalStateException("JOB_RESOURCE_RETIREMENT_RACE");
        }}
    }
    private static String eligible(RuntimeBroker broker,JobComponentLedger.Lease lease){
        if(broker.stubUsed(lease.processStub))return null;
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT retired FROM job_components WHERE id=?",new String[]{lease.id})){if(!c.moveToFirst()||c.getInt(0)!=0)return null;}
        String sql="SELECT 1 FROM job_mutations WHERE installGeneration=? AND closed=0 UNION ALL SELECT 1 FROM job_instances WHERE lease=? AND state='LIVE' UNION ALL SELECT 1 FROM sessions WHERE activityStub=? AND state NOT IN ('DEAD','FAILED') UNION ALL SELECT 1 FROM job_versions WHERE lease=? AND fenced=0";
        try(Cursor c=broker.store.getReadableDatabase().rawQuery(sql,new String[]{lease.install,lease.id,lease.processStub,lease.id})){if(c.moveToFirst())return null;}
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*),MAX(rowid) FROM job_versions WHERE lease=?",new String[]{lease.id})){if(!c.moveToFirst()||c.getLong(0)==0)return null;return c.getLong(0)+":"+c.getLong(1);}
    }
    private RuntimeJobRetirement(){}
}
