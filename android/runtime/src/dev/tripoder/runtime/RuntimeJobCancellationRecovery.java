package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.app.job.JobScheduler;
import java.util.*;

/** Observes a completed cancellation objective; never retries an unknown platform RPC. */
final class RuntimeJobCancellationRecovery {
    private record Target(String version,String namespace,int jobId,String service,String process){}
    private record Candidate(String operation,String install,long unknownAt,String prior,List<Target> targets){}
    static void reconcile(RuntimeBroker broker)throws Exception{
        if(Thread.holdsLock(broker))throw new IllegalStateException("JOB_CANCEL_RECOVERY_INSIDE_BROKER_LOCK");
        List<Candidate> candidates=new ArrayList<>();
        synchronized(broker){for(String operation:broker.jobs.openCancels()){Candidate candidate=eligible(broker,operation);if(candidate!=null)candidates.add(candidate);}}
        if(candidates.isEmpty())return;
        var manager=Objects.requireNonNull(broker.host.getSystemService(ActivityManager.class));
        var services=Objects.requireNonNull(manager.getRunningServices(Integer.MAX_VALUE),"JOB_CANCEL_SERVICE_OBSERVATION_UNAVAILABLE");
        var processes=Objects.requireNonNull(manager.getRunningAppProcesses(),"JOB_CANCEL_PROCESS_OBSERVATION_UNAVAILABLE");
        var pending=Objects.requireNonNull(broker.host.getSystemService(JobScheduler.class).getPendingJobsInAllNamespaces(),"JOB_CANCEL_PENDING_OBSERVATION_UNAVAILABLE");
        Set<String> activeServices=new HashSet<>(),activeProcesses=new HashSet<>(),pendingKeys=new HashSet<>();
        for(var service:services)if(broker.host.getPackageName().equals(service.service.getPackageName()))activeServices.add(service.service.getClassName());
        for(var process:processes)if(process.uid==android.os.Process.myUid())activeProcesses.add(process.processName);
        // Any version occupying the physical key blocks recovery, including an
        // unexpected replacement. Absence of just the old version is insufficient.
        for(var namespace:pending.entrySet())for(var job:namespace.getValue())pendingKeys.add(namespace.getKey()+"/"+job.getId());
        long observedAt=System.currentTimeMillis();
        synchronized(broker){for(Candidate candidate:candidates){
            if(!candidate.equals(eligible(broker,candidate.operation)))continue;
            boolean absent=true;
            for(Target target:candidate.targets)if(pendingKeys.contains(target.namespace+"/"+target.jobId)||activeServices.contains(target.service)||activeProcesses.contains(target.process)){absent=false;break;}
            if(!absent)continue;
            String observation="CANCEL_OBJECTIVE_OBSERVED;targets="+candidate.targets.size()+";targetDigest="+ApkStore.textDigest(candidate.targets.toString())+";all-target-versions-fenced;pending-keys-absent;gateway-services-absent;physical-processes-absent;owners-inert;observedAt="+observedAt+";priorUnknownUpdatedAt="+candidate.unknownAt;
            // Keep every field changed by reconciliation, including the old
            // UNKNOWN reason/evidence, in the new receipt. Oversized evidence is
            // rejected by ExecutionContract before changing either durable row.
            String proof=new org.json.JSONObject().put("prior",new org.json.JSONObject(candidate.prior)).put("observation",observation).toString();
            if(proof.length()>2048){android.util.Log.w("TripoderJobs","CANCEL_RECOVERY_EVIDENCE_CAPACITY:"+candidate.operation);continue;}
            var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                broker.contract.reconcile(candidate.operation,ExecutionContract.OperationState.SUCCEEDED,"CANCELLED_STATE_OBSERVED",proof);
                broker.jobs.close(candidate.operation);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }}
    }
    private static Candidate eligible(RuntimeBroker broker,String operation)throws Exception{
        var receipt=broker.contract.query(operation);if(receipt==null||receipt.state!=ExecutionContract.OperationState.UNKNOWN||broker.jobBroker.mutationInFlight(operation))return null;
        String install;
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT installGeneration FROM job_mutations WHERE operation=? AND kind='CANCEL' AND closed=0",new String[]{operation})){if(!c.moveToFirst())return null;install=c.getString(0);}
        if(!("job-install:"+install).equals(receipt.callerIdentity))throw new SecurityException("JOB_CANCEL_RECOVERY_OWNER_MISMATCH");
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE installGeneration=? AND state!='DEAD' LIMIT 1",new String[]{install})){if(c.moveToFirst())return null;}
        List<Target> targets=new ArrayList<>();
        for(var version:broker.jobs.targets(operation)){
            if(!version.install.equals(install)||!version.fenced)return null;
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT stub,processStub,installGeneration,space FROM job_components WHERE id=?",new String[]{version.lease})){
                if(!c.moveToFirst()||!install.equals(c.getString(2)))throw new SecurityException("JOB_CANCEL_RECOVERY_LEASE_MISMATCH");
                var physical=version.physical();
                if(!version.physicalNamespace.equals(RuntimeJobNamespace.physical(c.getString(3),install,version.namespace))||physical.getId()!=version.jobId||!broker.host.getPackageName().equals(physical.getService().getPackageName())||!c.getString(0).equals(physical.getService().getClassName())||!version.version.equals(physical.getExtras().getString(RuntimeJobInfo.VERSION)))throw new SecurityException("JOB_CANCEL_RECOVERY_PHYSICAL_IDENTITY_MISMATCH");
                String physicalProcess=broker.host.getPackageName()+":slot"+RuntimeSlot.index(c.getString(1));
                targets.add(new Target(version.version,version.physicalNamespace,version.jobId,c.getString(0),physicalProcess));
            }
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT i.state,s.state,s.installGeneration FROM job_instances i LEFT JOIN sessions s ON s.generation=i.generation WHERE i.lease=?",new String[]{version.lease})){
                while(c.moveToNext())if(!Set.of("DESTROYED","PROCESS_DEAD").contains(c.getString(0))||!"DEAD".equals(c.getString(1))||!install.equals(c.getString(2)))return null;
            }
        }
        if(targets.isEmpty())return null;
        String prior=new org.json.JSONObject().put("state",receipt.state.name()).put("result",receipt.result==null?org.json.JSONObject.NULL:receipt.result).put("detail",receipt.detail==null?org.json.JSONObject.NULL:receipt.detail).put("evidence",receipt.evidence==null?org.json.JSONObject.NULL:receipt.evidence).put("updatedAtMs",receipt.updatedAtMs).toString();
        return new Candidate(operation,install,receipt.updatedAtMs,prior,List.copyOf(targets));
    }
    private RuntimeJobCancellationRecovery(){}
}
