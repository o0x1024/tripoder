package dev.tripoder.runtime;

import android.app.job.*;
import android.content.*;
import android.os.PersistableBundle;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Production ledger/broker against Android's scheduler, with explicitly labelled RPC fault points. */
public final class RuntimeJobSchedulingProbe {
    private static final String PACKAGE="dev.tripoder.jobsfixture",SERVICE=PACKAGE+".OwnedJob";
    private static final long FUTURE=24*60*60*1000L;
    private final JSONArray checks=new JSONArray();
    private RuntimeBroker broker;
    private FaultPlatform platform;
    private RuntimeJobBroker jobs;
    private final Context host;
    private final String run;
    private final List<ApkStore.BundleInstall> installs=new ArrayList<>();
    private RuntimeJobSchedulingProbe(Context host,String run){this.host=host;this.run=run;}
    public static JSONObject run(Context host,String run)throws Exception{return new RuntimeJobSchedulingProbe(host,run).execute();}
    private JSONObject execute()throws Exception{
        JSONObject out=new JSONObject();boolean passed=false;
        Context isolated=new RuntimeJobProbeContext(host,run);
        try{
            RuntimeJobSchemaProbe.run(host,run,checks);
            broker=new RuntimeBroker(isolated);platform=new FaultPlatform(new RuntimeJobBroker.AndroidPlatform(host));jobs=new RuntimeJobBroker(broker,platform);
            JSONObject fixture;try(var input=host.getAssets().open("jobs-fixture.json")){fixture=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            for(String suffix:List.of("a","b")){
                String space="jobs-"+suffix+"-"+run;broker.catalog.createSpace(space);
                installs.add(broker.apks.install(space,fixture.getString("asset"),fixture.getString("sha256")));
            }
            var a=installs.get(0);var b=installs.get(1);
            check("new-fixture-store-has-no-sessions",count("sessions")==0);
            declarations(a);
            JobInfo first=job(7,SERVICE,"original");byte[] original=RuntimeJobInfo.encode(first);
            check("android-schedule-accepted-a",schedule(a,"same",first)==JobScheduler.RESULT_SUCCESS);
            check("android-schedule-accepted-b",schedule(b,"same",job(7,SERVICE,"other-space"))==JobScheduler.RESULT_SUCCESS);
            var va=last(a);var vb=last(b);
            check("space-and-install-derive-different-system-namespaces",!va.physicalNamespace.equals(vb.physicalNamespace)&&!va.physicalNamespace.equals("same"));
            check("logical-process-leases-are-distinct",!broker.jobs.components.get(va.lease).processStub.equals(broker.jobs.components.get(vb.lease).processStub));
            check("future-job-submission-does-not-allocate-session",count("sessions")==0&&broker.processSlots().isEmpty());
            check("pending-job-pins-capacity",broker.processes.capacity().getInt("retained")==2&&broker.processes.capacity().getInt("allocated")==0);
            check("original-job-info-unchanged",Arrays.equals(original,RuntimeJobInfo.encode(first)));
            JobInfo observed=jobs.pending(a).get("same").get(0);
            check("real-query-restores-logical-component-and-extras",observed.getId()==7&&observed.getService().equals(first.getService())&&"original".equals(observed.getExtras().getString("payload"))&&!observed.getExtras().containsKey(RuntimeJobInfo.VERSION));
            check("real-query-matches-complete-logical-parcel",Arrays.equals(RuntimeJobInfo.encode(observed),original));
            platform.corruptQuery=true;
            rejects("unrecognized-platform-version-is-not-a-logical-job","JOB_SYSTEM_VERSION_MISMATCH",()->jobs.pending(a));
            rejects("pending-job-blocks-install-upgrade-or-uninstall","INSTALL_IN_USE_BY_JOB",()->broker.catalog.requireUnusedMetadata(a.space,PACKAGE));
            String prior=va.version;platform.rejectNext=true;
            check("injected-result-failure-is-returned",schedule(a,"same",job(7,SERVICE,"rejected"))==JobScheduler.RESULT_FAILURE);
            check("rejected-replacement-preserves-old-version",!broker.jobs.get(prior).fenced&&last(a).state.equals("REJECTED")&&"original".equals(jobs.pending(a).get("same").get(0).getExtras().getString("payload")));
            String replacementOperation=operation();
            jobs.schedule(a,"same",job(7,SERVICE,"replacement"),true,false,replacementOperation);
            check("accepted-replacement-fences-old-version",broker.jobs.get(prior).fenced&&last(a).state.equals("ACCEPTED"));
            int calls=platform.scheduleCalls;
            rejects("same-operation-never-replays-schedule","JOB_ALREADY_ACCEPTED_QUERY_REQUIRED",()->jobs.schedule(a,"same",job(7,SERVICE,"replacement"),true,false,replacementOperation));
            check("duplicate-operation-has-no-extra-rpc",platform.scheduleCalls==calls);
            check("same-service-parallel-job-submitted",schedule(a,"same",job(8,SERVICE,"parallel"))==JobScheduler.RESULT_SUCCESS);
            check("parallel-jobs-share-one-immutable-component",last(a).lease.equals(broker.jobs.targets(replacementOperation).get(0).lease));
            check("different-service-in-process-submitted",schedule(a,"same",job(9,SERVICE+"$Peer","peer"))==JobScheduler.RESULT_SUCCESS);
            check("different-service-keeps-process-identity",broker.jobs.components.get(last(a).lease).processStub.equals(broker.jobs.components.get(va.lease).processStub));
            check("distinct-namespace-submitted",schedule(a,"other",job(7,SERVICE,"other-namespace"))==JobScheduler.RESULT_SUCCESS);
            check("default-namespace-submitted",schedule(a,null,job(7,SERVICE,"default-namespace"))==JobScheduler.RESULT_SUCCESS);
            concurrency(a,b);
            jobs.cancel(a,"same",null,false,operation());
            check("cancel-all-is-only-current-namespace",!jobs.pending(a).containsKey("same")&&jobs.pending(a).containsKey("other")&&jobs.pending(a).containsKey(""));
            check("cancel-all-preserves-other-space",jobs.pending(b).get("same").size()==2);
            jobs.cancel(a,null,null,true,operation());
            check("cancel-in-all-namespaces-is-install-scoped",jobs.pending(a).isEmpty()&&!jobs.pending(b).isEmpty());
            check("pending-absence-does-not-retire-components",broker.jobs.components.occupied(broker.jobs.components.get(va.lease).processStub));
            int versionsBefore=count("job_versions"),operationsBefore=count("operations");RuntimeJobRetirement.reconcile(broker);RuntimeJobRetirement.reconcile(broker);
            check("fenced-component-retires-after-system-service-process-absence",retired(va.lease));
            check("retirement-preserves-job-version-and-operation-history",count("job_versions")==versionsBefore&&count("operations")==operationsBefore);
            check("unfenced-other-space-is-not-retired",!retired(vb.lease));
            acknowledgementLoss(a);
            jobs.cancel(a,null,null,true,operation());
            check("cleanup-before-unknown-cancel-is-observed",jobs.pending(a).isEmpty());
            String cancelOperation=operation();platform.loseCancelAck=true;
            rejects("injected-cancel-ack-loss-propagated","INJECTED_CANCEL_ACK_LOST",()->jobs.cancel(b,null,null,true,cancelOperation));
            check("cancel-ack-loss-keeps-unknown-operation",broker.contract.query(cancelOperation).state==ExecutionContract.OperationState.UNKNOWN);
            check("cancel-ack-loss-fences-every-batch-target",broker.jobs.targets(cancelOperation).stream().allMatch(v->broker.jobs.get(v.version).fenced));
            // First cancel RPC took effect, later members were never dispatched. A query
            // must expose those remaining real jobs even though all admission is fenced.
            check("partial-cancel-query-does-not-invent-empty-list",!jobs.pending(b).isEmpty());
            rejects("unknown-cancel-keeps-install-mutation-owner","JOB_MUTATION_IN_PROGRESS",()->schedule(b,"new",job(40,SERVICE,"blocked")));
            jobs.reconcileSchedules();
            check("pending-query-never-promotes-cancel-to-business-success",broker.contract.query(cancelOperation).state==ExecutionContract.OperationState.UNKNOWN);
            String missingOperation=operation();platform.failBeforeSchedule=true;
            rejects("injected-dispatch-uncertainty-propagated","INJECTED_SCHEDULE_OUTCOME_UNKNOWN",()->jobs.schedule(a,"absent",job(41,SERVICE,"absent"),true,false,missingOperation));
            jobs.reconcileSchedules();
            check("pending-absence-does-not-resolve-unknown-schedule",broker.contract.query(missingOperation).state==ExecutionContract.OperationState.UNKNOWN&&last(a).state.equals("UNKNOWN"));
            check("all-submissions-still-have-zero-worker-sessions",count("sessions")==0);
            out.put("fixture",fixture).put("storeDirectory",isolated.getFilesDir().toString()).put("scope","durable-scheduling-only").put("guestLifecycleVerified",false).put("systemJobsSubmitted",platform.acceptedCalls).put("systemScheduleCalls",platform.scheduleCalls).put("systemQueryCalls",platform.queryCalls);
            passed=true;
        }catch(Throwable error){out.put("error",android.util.Log.getStackTraceString(error));}
        finally{
            try{if(platform!=null){platform.cleanup();check("owned-test-system-jobs-removed",platform.empty());
                if(passed){RuntimeJobRetirement.reconcile(broker);check("unknown-mutation-keeps-component-after-system-absence",!retired(last(installs.get(0)).lease)&&!retired(last(installs.get(1)).lease));}
}}
            catch(Exception error){passed=false;out.put("cleanupError",android.util.Log.getStackTraceString(error));}
            if(broker!=null)broker.store.close();
        }
        return out.put("passed",passed).put("checks",checks);
    }
    private void declarations(ApkStore.BundleInstall install)throws Exception{
        rejects("foreign-package-service-denied","JOB_SERVICE_OUTSIDE_INSTALL",()->schedule(install,null,new JobInfo.Builder(1,new ComponentName("foreign.package",SERVICE)).setMinimumLatency(FUTURE).build()));
        rejects("unknown-service-denied","COMPONENT_NOT_DECLARED",()->schedule(install,null,job(1,PACKAGE+".Unknown","bad")));
        rejects("service-bind-permission-required","JOB_SERVICE_BIND_PERMISSION_REQUIRED",()->schedule(install,null,job(1,SERVICE+"$Unprotected","bad")));
        rejects("disabled-service-denied","COMPONENT_DISABLED",()->schedule(install,null,job(1,SERVICE+"$Disabled","bad")));
        rejects("isolated-service-denied","JOB_SERVICE_PROCESS_KIND_UNSUPPORTED",()->schedule(install,null,job(1,SERVICE+"$Isolated","bad")));
        rejects("explicit-empty-namespace-denied","JOB_NAMESPACE_INVALID",()->schedule(install,"",job(1,SERVICE,"bad")));
        rejects("invalid-utf16-namespace-cannot-alias-another-owner","JOB_NAMESPACE_INVALID",()->schedule(install,"bad\uD800",job(1,SERVICE,"bad")));
        rejects("lossy-constraint-presence-denied","JOB_FIELDS_NOT_LOSSLESS",()->jobs.schedule(install,null,job(1,SERVICE,"bad"),false,false,operation()));
        check("invalid-admission-rolls-back-operation-and-lease",count("job_versions")==0&&count("job_components")==0&&count("job_mutations")==0&&platform.scheduleCalls==0);
    }
    private void concurrency(ApkStore.BundleInstall a,ApkStore.BundleInstall b)throws Exception{
        platform.entered=new CountDownLatch(1);platform.release=new CountDownLatch(1);platform.blockNext=true;
        var work=new FutureTask<Integer>(()->schedule(a,"same",job(10,SERVICE,"concurrent")));Thread thread=new Thread(work,"job-mutation-gate");thread.start();
        try{
            check("platform-rpc-entered-with-durable-prepared",platform.entered.await(10,TimeUnit.SECONDS)&&last(a).state.equals("PREPARED"));
            rejects("same-install-concurrent-schedule-denied","JOB_MUTATION_IN_PROGRESS",()->schedule(a,"different",job(11,SERVICE,"denied")));
            rejects("cancel-all-cannot-miss-concurrent-new-job","JOB_MUTATION_IN_PROGRESS",()->jobs.cancel(a,null,null,true,operation()));
            check("other-install-can-progress-during-rpc",schedule(b,"same",job(12,SERVICE,"independent"))==JobScheduler.RESULT_SUCCESS);
        }finally{platform.release.countDown();}
        check("blocked-rpc-completes-once",work.get(10,TimeUnit.SECONDS)==JobScheduler.RESULT_SUCCESS);
    }
    private void acknowledgementLoss(ApkStore.BundleInstall a)throws Exception{
        String operation=operation();platform.loseScheduleAck=true;int before=platform.scheduleCalls;
        rejects("injected-schedule-ack-loss-propagated","INJECTED_SCHEDULE_ACK_LOST",()->jobs.schedule(a,"recover",job(30,SERVICE,"durable"),true,false,operation));
        check("ack-loss-is-durable-unknown",broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN&&last(a).state.equals("UNKNOWN"));
        rejects("unknown-schedule-cannot-be-replayed","JOB_MUTATION_IN_PROGRESS",()->jobs.schedule(a,"recover",job(30,SERVICE,"durable"),true,false,operation));
        check("unknown-query-uses-real-system-version",jobs.pending(a).get("recover").get(0).getId()==30);
        Context context=broker.host;broker.store.close();broker=new RuntimeBroker(context);jobs=new RuntimeJobBroker(broker,platform);
        var receipt=broker.contract.query(operation);
        check("disk-reopen-recovers-from-exact-platform-version",receipt.state==ExecutionContract.OperationState.SUCCEEDED&&receipt.evidence.contains(last(a).version)&&last(a).state.equals("ACCEPTED"));
        check("disk-recovery-never-replays-schedule",platform.scheduleCalls==before+1);
    }
    private JobLedger.Version last(ApkStore.BundleInstall install){var versions=broker.jobs.versions(install.generation);return versions.get(versions.size()-1);}
    private int schedule(ApkStore.BundleInstall install,String namespace,JobInfo info)throws Exception{return jobs.schedule(install,namespace,info,true,false,operation());}
    private static JobInfo job(int id,String component,String payload){PersistableBundle extras=new PersistableBundle();extras.putString("payload",payload);return new JobInfo.Builder(id,new ComponentName(PACKAGE,component)).setMinimumLatency(FUTURE).setExtras(extras).build();}
    private static String operation(){return UUID.randomUUID().toString();}
    private boolean retired(String lease){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT retired,resourceProof FROM job_components WHERE id=?",new String[]{lease})){if(!c.moveToFirst())throw new AssertionError("JOB_HISTORY_REMOVED");return c.getInt(0)==1&&c.getString(1).contains("pending-absent;ams-service-absent");}}
    private int count(String table){try(var cursor=broker.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){cursor.moveToFirst();return cursor.getInt(0);}}
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private interface Action{void run()throws Exception;}
    private void rejects(String name,String expected,Action action)throws Exception{boolean rejected=false;try{action.run();}catch(Exception error){rejected=String.valueOf(error.getMessage()).contains(expected);if(!rejected)throw error;}check(name,rejected);}
    private static final class FaultPlatform implements RuntimeJobBroker.Platform {
        final RuntimeJobBroker.Platform real;
        final Map<String,Set<Integer>> owned=new ConcurrentHashMap<>();
        volatile boolean rejectNext,loseScheduleAck,loseCancelAck,failBeforeSchedule,blockNext,corruptQuery;
        volatile CountDownLatch entered,release;
        int scheduleCalls,acceptedCalls,queryCalls;
        FaultPlatform(RuntimeJobBroker.Platform real){this.real=real;}
        public int schedule(String namespace,JobInfo job){
            if(rejectNext){rejectNext=false;return JobScheduler.RESULT_FAILURE;}
            if(failBeforeSchedule){failBeforeSchedule=false;throw new IllegalStateException("INJECTED_SCHEDULE_OUTCOME_UNKNOWN");}
            if(blockNext){blockNext=false;entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("PROBE_GATE_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
            owned.computeIfAbsent(namespace,k->ConcurrentHashMap.newKeySet()).add(job.getId());int result=real.schedule(namespace,job);scheduleCalls++;if(result==JobScheduler.RESULT_SUCCESS)acceptedCalls++;
            if(loseScheduleAck){loseScheduleAck=false;throw new IllegalStateException("INJECTED_SCHEDULE_ACK_LOST");}return result;
        }
        public void cancel(String namespace,int id){real.cancel(namespace,id);if(loseCancelAck){loseCancelAck=false;throw new IllegalStateException("INJECTED_CANCEL_ACK_LOST");}}
        public Map<String,List<JobInfo>> pending(){
            queryCalls++;var observed=real.pending();if(!corruptQuery)return observed;corruptQuery=false;
            Map<String,List<JobInfo>> corrupt=new LinkedHashMap<>(observed);
            for(var entry:observed.entrySet())if(owned.containsKey(entry.getKey())&&!entry.getValue().isEmpty()){
                var actual=entry.getValue().get(0);var jobs=new ArrayList<>(entry.getValue());
                jobs.set(0,RuntimeJobInfo.physical(RuntimeJobInfo.decode(RuntimeJobInfo.encode(actual)),true,false,actual.getService(),"0".repeat(64)));corrupt.put(entry.getKey(),jobs);
            }return corrupt;
        }
        void cleanup(){for(var entry:owned.entrySet())for(int id:entry.getValue())real.cancel(entry.getKey(),id);}
        boolean empty(){var pending=real.pending();for(String namespace:owned.keySet())if(pending.containsKey(namespace)&&!pending.get(namespace).isEmpty())return false;return true;}
    }
}
