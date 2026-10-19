package dev.tripoder.runtime;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;

/** Single durable mutation owner per installation. Platform RPCs never run under the broker lock. */
final class RuntimeJobBroker {
    interface Platform {
    int schedule(String namespace,JobInfo job);
        void cancel(String namespace,int jobId);
        Map<String,List<JobInfo>> pending();
    }
    static final class AndroidPlatform implements Platform {
        private final JobScheduler scheduler;
        AndroidPlatform(Context host){scheduler=Objects.requireNonNull(host.getSystemService(JobScheduler.class),"JOB_PLATFORM_UNAVAILABLE");}
        public int schedule(String namespace,JobInfo job){return scheduler.forNamespace(namespace).schedule(job);}
        public void cancel(String namespace,int jobId){scheduler.forNamespace(namespace).cancel(jobId);}
        public Map<String,List<JobInfo>> pending(){return Objects.requireNonNull(scheduler.getPendingJobsInAllNamespaces(),"JOB_SYSTEM_QUERY_UNAVAILABLE");}
    }
    private final RuntimeBroker broker;
    private final JobLedger ledger;
    private final Platform platform;
    RuntimeJobBroker(RuntimeBroker broker){this(broker,new AndroidPlatform(broker.host));}
    RuntimeJobBroker(RuntimeBroker broker,Platform platform){this.broker=broker;ledger=broker.jobs;this.platform=Objects.requireNonNull(platform);}

        Bundle route(Bundle request,int pid,int uid)throws Exception{
        ApkStore.BundleInstall install;
        synchronized(broker){String generation=RuntimeWire.required(request,"generation");broker.contract.authenticate(generation,pid,uid);install=broker.requiredSlot(generation).install;}
        String kind=RuntimeWire.required(request,"kind"),namespace=request.getString("namespace");Bundle out=new Bundle();
        switch(kind){
            case "schedule":out.putInt("result",schedule(install,namespace,RuntimeJobInfo.decode(request.getByteArray("job")),request.getBoolean("early"),request.getBoolean("late"),RuntimeWire.required(request,"operation")));return out;
            case "cancel":cancel(install,namespace,request.containsKey("id")?request.getInt("id"):null,request.getBoolean("allNamespaces"),RuntimeWire.required(request,"operation"));return out;
            case "query":{
                reconcileSchedules();
                ArrayList<Bundle> rows=new ArrayList<>();int bytes=0;
                for(var entry:pending(install).entrySet())for(JobInfo info:entry.getValue()){
                    byte[] payload=RuntimeJobInfo.encode(info);bytes+=payload.length;if(bytes>512*1024)throw new IllegalStateException("JOB_QUERY_TOO_LARGE");
                    Bundle row=new Bundle();row.putString("namespace",entry.getKey().isEmpty()?null:entry.getKey());row.putByteArray("job",payload);rows.add(row);
                }out.putParcelableArrayList("jobs",rows);return out;
            }
            default:throw new UnsupportedOperationException("JOB_ROUTE_UNSUPPORTED");
        }
    }

    Bundle beginAttempt(Bundle input,RuntimeBroker.Slot slot){return broker.jobAttempts.begin(input,slot);}
    Bundle attemptEvent(Bundle input,RuntimeBroker.Slot slot){return broker.jobAttempts.event(input,slot);}

    int schedule(ApkStore.BundleInstall install,String namespace,JobInfo input,boolean early,boolean late,String operation)throws Exception{
        outsideLock();RuntimeJobRetirement.reconcile(broker);namespace=RuntimeJobNamespace.logical(namespace);
        JobInfo logical=RuntimeJobInfo.decode(RuntimeJobInfo.encode(input));
        String digest=ApkStore.textDigest("job-schedule-v1"+RuntimeJobNamespace.part(install.generation)+RuntimeJobNamespace.part(namespace)+early+":"+late+":"+Base64.getEncoder().encodeToString(RuntimeJobInfo.encode(logical)));
        JobLedger.Version prepared;
        synchronized(broker){
            current(install);ServiceInfo declaration=declaration(install,logical.getService());
            ledger.requireIdle(install.generation);
            String version=randomVersion();
            // Validate the complete shape before selecting resources. Existing component
            // reconciliation observes Android and must run outside the SQLite transaction.
            RuntimeJobInfo.physical(logical,early,late,logical.getService(),version);
            String processStub=broker.processes.jobProcessStub(install,declaration.processName);
            var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                begin(operation,install,digest,"SCHEDULE");
                JobComponentLedger.Lease lease=ledger.components.reserve(install,declaration.processName,declaration.name,processStub);
                JobInfo physical=RuntimeJobInfo.physical(logical,early,late,new ComponentName(broker.host,lease.stub),version);
                String hookGeneration=null,hookOperation=null;
                var live=broker.findProcess(install.space,install.info.packageName,declaration.processName);
                if(live!=null&&broker.store.hookRecoveryBlocked(live.allocation.session.processGeneration)){
                    hookGeneration=live.allocation.session.processGeneration;hookOperation=broker.store.hookOperation(hookGeneration);
                }
                ledger.prepared(version,lease,namespace,logical,physical,operation,hookGeneration,hookOperation);
                prepared=ledger.get(version);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        try{
            int result=platform.schedule(prepared.physicalNamespace,prepared.physical());
            if(result!=JobScheduler.RESULT_SUCCESS&&result!=JobScheduler.RESULT_FAILURE)throw new IllegalStateException("JOB_PLATFORM_RESULT_UNKNOWN");
            synchronized(broker){atomic(()->{
                if(result==JobScheduler.RESULT_SUCCESS){ledger.accepted(prepared);broker.contract.succeed(operation,"PLATFORM_ACCEPTED:"+prepared.version);}
                else{if(ledger.get(prepared.version).state.equals("ACCEPTED"))throw new IllegalStateException("JOB_PLATFORM_ACK_CONTRADICTS_CALLBACK");ledger.state(prepared.version,"REJECTED");ledger.fence(prepared.version);broker.contract.fail(operation,"JOB_PLATFORM_REJECTED");}
                ledger.close(operation);
            });}
            return result;
        }catch(Exception error){unknown(operation,error);throw error;}
    }

    /** Null id means all jobs in namespace; allNamespaces covers only this install. */
    void cancel(ApkStore.BundleInstall install,String namespace,Integer id,boolean allNamespaces,String operation)throws Exception{
        outsideLock();namespace=RuntimeJobNamespace.logical(namespace);
        if(allNamespaces&&id!=null)throw new IllegalArgumentException("JOB_CANCEL_SCOPE_INVALID");
        String digest=ApkStore.textDigest("job-cancel-v1"+RuntimeJobNamespace.part(install.generation)+RuntimeJobNamespace.part(namespace)+":"+id+":"+allNamespaces);
        List<JobLedger.Version> selected=new ArrayList<>();
        synchronized(broker){
            current(install);ledger.requireIdle(install.generation);
            for(var version:ledger.versions(install.generation))if(!version.fenced&&!version.state.equals("REJECTED")&&(allNamespaces||version.namespace.equals(namespace))&&(id==null||version.jobId==id))selected.add(version);
            atomic(()->{
                begin(operation,install.generation,digest,"CANCEL");
                for(var version:selected){ledger.target(operation,version.version);ledger.fence(version.version);}
            });
        }
        try{
            Set<String> cancelled=new HashSet<>();
            for(var version:selected)if(cancelled.add(version.physicalNamespace+":"+version.jobId))platform.cancel(version.physicalNamespace,version.jobId);
            synchronized(broker){atomic(()->{
                for(var version:selected)ledger.state(version.version,"CANCEL_RPC_RETURNED");
                broker.contract.succeed(operation,"CANCEL_RPC_RETURNED");ledger.close(operation);
            });}
        }catch(Exception error){unknown(operation,error);throw error;}
    }

    /** Cancels all scheduled jobs of an installation being removed, without requiring CORRUPT or schema-migrated artifacts to re-verify. */
    void cancelForUninstall(String space,String pkg,String generation,String operation)throws Exception{
        outsideLock();
        String digest=ApkStore.textDigest("job-cancel-v1"+RuntimeJobNamespace.part(generation)+RuntimeJobNamespace.part("")+":null:true");
        List<JobLedger.Version> selected=new ArrayList<>();
        synchronized(broker){
            var row=broker.apks.row(space,pkg);if(row==null||!generation.equals(row.generation))throw new SecurityException("JOB_INSTALL_STALE");
            ledger.requireIdle(generation);
            for(var version:ledger.versions(generation))if(!version.fenced&&!version.state.equals("REJECTED"))selected.add(version);
            atomic(()->{
                begin(operation,generation,digest,"CANCEL");
                for(var version:selected){ledger.target(operation,version.version);ledger.fence(version.version);}
            });
        }
        try{
            Set<String> cancelled=new HashSet<>();
            for(var version:selected)if(cancelled.add(version.physicalNamespace+":"+version.jobId))platform.cancel(version.physicalNamespace,version.jobId);
            synchronized(broker){atomic(()->{
                for(var version:selected)ledger.state(version.version,"CANCEL_RPC_RETURNED");
                broker.contract.succeed(operation,"CANCEL_RPC_RETURNED");ledger.close(operation);
            });}
        }catch(Exception error){unknown(operation,error);throw error;}
    }

    /** Always queries Android. Saved JobInfo restores the logical view, never substitutes for system state. */
    Map<String,List<JobInfo>> pending(ApkStore.BundleInstall install)throws Exception{
        outsideLock();List<JobLedger.Version> versions;
        synchronized(broker){current(install);versions=ledger.versions(install.generation);}
        Map<String,List<JobInfo>> observed=platform.pending();
        Map<String,String> namespaces=new LinkedHashMap<>();Map<String,JobLedger.Version> byVersion=new HashMap<>();
        for(var version:versions){namespaces.put(version.physicalNamespace,version.namespace);byVersion.put(version.version,version);}
        Map<String,List<JobInfo>> out=new LinkedHashMap<>();
        for(var scope:namespaces.entrySet()){
            List<JobInfo> system=observed.get(scope.getKey());if(system==null)continue;
            List<JobInfo> logical=new ArrayList<>();Set<Integer> unique=new HashSet<>();
            for(JobInfo job:system){
                JobLedger.Version version=byVersion.get(job.getExtras().getString(RuntimeJobInfo.VERSION));
                if(version==null||!matches(version,scope.getKey(),job)||!unique.add(job.getId()))throw new SecurityException("JOB_SYSTEM_VERSION_MISMATCH");
                // Cancellation may have fenced admission while the platform still reports the
                // pending job. Return the real observation, not an optimistic empty result.
                logical.add(version.logical());
            }
            if(!logical.isEmpty())out.put(scope.getValue(),logical);
        }
        synchronized(broker){current(install);}
        return out;
    }

    /** Positive pending-version evidence resolves schedule ACK loss. Absence proves no outcome. */
    void reconcileSchedules()throws Exception{
        outsideLock();List<String> operations;
        synchronized(broker){operations=ledger.openSchedules();}
        if(operations.isEmpty())return;
        Map<String,List<JobInfo>> observed=platform.pending();
        synchronized(broker){for(String operation:operations){
            if(broker.contract.query(operation).state!=ExecutionContract.OperationState.UNKNOWN)continue;
            List<JobLedger.Version> targets=ledger.targets(operation);if(targets.size()!=1)throw new IllegalStateException("JOB_SCHEDULE_TARGET_CORRUPT");
            JobLedger.Version version=targets.get(0);
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT attempt FROM job_attempts WHERE version=? LIMIT 1",new String[]{version.version})){
                if(c.moveToFirst()){String attempt=c.getString(0);atomic(()->{ledger.accepted(version);broker.contract.reconcile(operation,ExecutionContract.OperationState.SUCCEEDED,"PLATFORM_ACCEPTED:"+version.version,"JobService callback admission attempt="+attempt+" version="+version.version);ledger.close(operation);});continue;}
            }
            List<JobInfo> jobs=observed.get(version.physicalNamespace);if(jobs==null)continue;
            for(JobInfo job:jobs)if(matches(version,version.physicalNamespace,job)){
                atomic(()->{ledger.accepted(version);broker.contract.reconcile(operation,ExecutionContract.OperationState.SUCCEEDED,"PLATFORM_ACCEPTED:"+version.version,"JobScheduler.pending namespace="+version.physicalNamespace+" id="+version.jobId+" version="+version.version);ledger.close(operation);});break;
            }
        }}
    }
    static boolean matches(JobLedger.Version version,String namespace,JobInfo observed){
        if(!version.physicalNamespace.equals(namespace)||observed.getId()!=version.jobId||!version.version.equals(observed.getExtras().getString(RuntimeJobInfo.VERSION)))return false;
        JobInfo snapshot=RuntimeJobInfo.decode(RuntimeJobInfo.encode(observed));
        return Arrays.equals(RuntimeJobInfo.encode(version.physical()),RuntimeJobInfo.encode(snapshot));
    }
    private void current(ApkStore.BundleInstall install){
        var row=broker.apks.row(install.space,install.info.packageName);
        if(row==null||!"COMMITTED".equals(row.state)||!install.generation.equals(row.generation)||!install.data.getName().equals(row.dataId))throw new SecurityException("JOB_INSTALL_STALE");
    }
    private static ServiceInfo declaration(ApkStore.BundleInstall install,ComponentName component){
        RuntimeComponents.requireExecutable(install);
        if(!install.info.packageName.equals(component.getPackageName()))throw new SecurityException("JOB_SERVICE_OUTSIDE_INSTALL");
        var declared=RuntimeComponents.declared(install,"service",component.getClassName());
        if(!"android.permission.BIND_JOB_SERVICE".equals(declared.permission))throw new SecurityException("JOB_SERVICE_BIND_PERMISSION_REQUIRED");
        ServiceInfo found=null;if(install.info.services!=null)for(var info:install.info.services)if(info.name.equals(declared.name)){if(found!=null&&(found.enabled!=info.enabled||found.exported!=info.exported||!Objects.equals(found.processName,info.processName)||!Objects.equals(found.permission,info.permission)))throw new SecurityException("JOB_SERVICE_AMBIGUOUS");found=info;}
        if(found==null||!found.enabled||!found.applicationInfo.enabled||!"android.permission.BIND_JOB_SERVICE".equals(found.permission))throw new SecurityException("JOB_SERVICE_DECLARATION_INVALID");
        if((found.flags&(ServiceInfo.FLAG_ISOLATED_PROCESS|ServiceInfo.FLAG_EXTERNAL_SERVICE))!=0||!Set.of("false","0").contains(declared.attributes.getOrDefault("android:isolatedProcess","false"))||!Set.of("false","0").contains(declared.attributes.getOrDefault("android:externalService","false")))throw new UnsupportedOperationException("JOB_SERVICE_PROCESS_KIND_UNSUPPORTED");
        return found;
    }
    private void begin(String operation,ApkStore.BundleInstall install,String digest,String kind){begin(operation,install.generation,digest,kind);}
    private void begin(String operation,String generation,String digest,String kind){
        if(!broker.contract.accept(operation,"job-install:"+generation,digest).created)throw new IllegalStateException("JOB_ALREADY_ACCEPTED_QUERY_REQUIRED");
        broker.contract.start(operation);ledger.mutation(operation,generation,kind);
    }
    private void unknown(String operation,Exception error){synchronized(broker){
        if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)atomic(()->{
            broker.contract.markUnknown(operation,"JOB_PLATFORM_OUTCOME_UNCONFIRMED:"+error.getClass().getName());
            for(var version:ledger.targets(operation))if(version.state.equals("PREPARED"))ledger.state(version.version,"UNKNOWN");
        });
    }}
    private void atomic(Runnable action){var db=broker.store.getWritableDatabase();db.beginTransaction();try{action.run();db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private void outsideLock(){if(Thread.holdsLock(broker))throw new IllegalStateException("JOB_PLATFORM_CALL_INSIDE_BROKER_LOCK");}
    private static String randomVersion(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return ArtifactSet.hex(bytes);}
}
