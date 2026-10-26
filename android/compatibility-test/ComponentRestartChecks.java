package dev.tripoder.runtime;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;

/** SQLite crash/rollback models; actual self-death is tested by the separate owned APK. */
final class ComponentRestartChecks {
    private final BiConsumer<String,Boolean> check;
    ComponentRestartChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private void blocked(String name,Runnable body){try{body.run();throw new AssertionError(name);}catch(IllegalStateException expected){check.accept(name,true);}}
    private static String scalar(SQLiteDatabase db,String sql,String...args){try(var c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    private static String session(RuntimeBroker broker,ApkStore.BundleInstall install){
        var allocated=broker.contract.allocate(install.space,install.info.packageName,install.generation,install.info.packageName+":owned_"+UUID.randomUUID().toString().replace("-",""),android.os.Process.myUid());
        broker.store.reserve(allocated.session,RuntimeSlot.activity(7).getName());return allocated.session.processGeneration;
    }
    void run(Context target,File parent,Context assets)throws Exception{
        File directory=new File(parent,"component-restart");if(!directory.mkdir())throw new IllegalStateException("RESTART_SCRATCH_DIRECTORY");
        Context owned=new ContextWrapper(target){
            @Override public File getFilesDir(){return directory;}
            @Override public File getDatabasePath(String name){return new File(directory,name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).toString(),factory,handler);}
        };
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            var db=broker.store.getWritableDatabase();File stage=new File(directory,"apk");if(!stage.mkdir())throw new IllegalStateException("RESTART_APK_DIRECTORY");
            try(var input=assets.getAssets().open("aliases.apk")){Files.copy(input,new File(stage,"base.apk").toPath());}
            var artifacts=ArtifactSet.materialize(stage);var info=target.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),android.content.pm.PackageManager.GET_ACTIVITIES);
            var install=new ApkStore.BundleInstall("owned-restart",UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(directory,"data"),artifacts);
            db.execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{install.space});
            db.execSQL("INSERT INTO installs VALUES(?,?,?,'owned.apk',?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{install.space,info.packageName,install.generation,"a".repeat(64),"b".repeat(64),install.data.getName()});
            String a=session(broker,install),b=session(broker,install),operation=UUID.randomUUID().toString();
            String component=info.packageName+".Main";
            synchronized(broker){
                db.beginTransaction();try{
                    broker.contract.accept(operation,"pending-install:"+install.generation,"a".repeat(64));broker.contract.start(operation);
                    ComponentSettingsLedger.set(db,install.space,info.packageName,install.data.getName(),component,2);
                    RuntimeComponentRestart.mark(broker,operation,install);
                    // No setTransactionSuccessful: lost setting and restart intent roll back together.
                }finally{db.endTransaction();}
                broker.store.requireOpen(a);broker.store.requireOpen(b);
                check.accept("restart-rollback-keeps-original-admission-and-settings",broker.contract.query(operation)==null&&ComponentSettingsLedger.snapshot(db,install.space,info.packageName,install.data.getName()).isEmpty());
                db.beginTransaction();try{
                    broker.contract.accept(operation,"pending-install:"+install.generation,"a".repeat(64));broker.contract.start(operation);
                    ComponentSettingsLedger.set(db,install.space,info.packageName,install.data.getName(),component,2);
                    check.accept("restart-targets-all-current-install-generations",new HashSet<>(RuntimeComponentRestart.mark(broker,operation,install)).equals(Set.of(a,b)));
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                blocked("committed-marker-invalidates-prior-open-cache",()->broker.store.requireOpen(a));
                blocked("restart-gates-even-fresh-application-owner",()->broker.store.requireProcessStart(install,info.packageName,true));
                broker.contract.markUnknown(operation,"OWNED_COMPONENT_COMMIT_ACK_LOST");
                db.execSQL("UPDATE sessions SET state='FAILED' WHERE generation=?",new Object[]{b});
                broker.dead(a);
                check.accept("one-dead-and-one-failed-is-not-complete",broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN);
                blocked("partial-death-keeps-new-process-closed",()->RuntimeComponentRestart.requireCompleted(db,install.space,info.packageName,install.generation));
                broker.dead(b);
                var receipt=broker.contract.query(operation);
                check.accept("all-confirmed-dead-completes-same-setting-operation",receipt.state==ExecutionContract.OperationState.SUCCEEDED&&RuntimeComponentRestart.RESULT.equals(receipt.result));
                check.accept("restart-reconciliation-preserves-prior-unknown",new org.json.JSONObject(receipt.evidence).getJSONObject("prior").getString("detail").equals("OWNED_COMPONENT_COMMIT_ACK_LOST"));
                broker.dead(b);RuntimeComponentRestart.requireCompleted(db,install.space,info.packageName,install.generation);
                check.accept("restart-death-and-duplicate-death-never-pause-package",scalar(db,"SELECT COUNT(*) FROM recovery_paused").equals("0"));
                check.accept("restart-preserves-raw-setting-and-terminal-old-identities",ComponentSettingsLedger.snapshot(db,install.space,info.packageName,install.data.getName()).getInt(component)==2&&scalar(db,"SELECT COUNT(*) FROM sessions WHERE componentRestartOperation=? AND state='DEAD'",operation).equals("2"));
                // A later authority recovery must not reapply a historical pause to new work.
                db.execSQL("INSERT INTO recovery_paused SELECT generation,space,package,installGeneration,processName FROM sessions WHERE generation=?",new Object[]{a});
                broker.pendingIntents.prepare("owned-pending","owned-pending-key",install,info.packageName,"",0,0,new byte[]{1},null);
                broker.store.recoverSessions(target);
                check.accept("historical-pause-does-not-refence-new-pending-intent",!broker.pendingIntents.get("owned-pending").fenced&&broker.pendingIntents.get("owned-pending").current);
            }
            processBoundaries(broker,install);
            recoveryBoundaries(broker,install,target);
            externalClaimBoundary(broker,install);
        }finally{broker.store.close();}
    }
    private record LaunchModel(Object launch,RuntimeBroker.Slot slot){}
    private static LaunchModel launch(RuntimeBroker broker,ApkStore.BundleInstall install,boolean external)throws Exception{
        var allocated=broker.contract.allocate(install.space,install.info.packageName,install.generation,install.info.packageName,android.os.Process.myUid());
        broker.store.reserve(allocated.session,RuntimeSlot.activity(7).getName());
        var slot=new RuntimeBroker.Slot(install,allocated,RuntimeSlot.activity(7).getName(),RuntimeMode.THIN,null);
        Class<?> type=Class.forName("dev.tripoder.runtime.ProcessSupervisor$Launch");var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object launch=constructor.newInstance(broker.processes,"owned-model-"+allocated.session.processGeneration,7,slot);
        var flag=type.getDeclaredField("external");flag.setAccessible(true);flag.setBoolean(launch,external);
        var launches=ProcessSupervisor.class.getDeclaredField("launches");launches.setAccessible(true);
        ((Map<String,Object>)launches.get(broker.processes)).put("owned-model-"+allocated.session.processGeneration,launch);
        var slots=RuntimeBroker.class.getDeclaredField("slots");slots.setAccessible(true);((Map<String,RuntimeBroker.Slot>)slots.get(broker)).put(allocated.session.processGeneration,slot);
        return new LaunchModel(launch,slot);
    }
    private static List<String> intent(RuntimeBroker broker,ApkStore.BundleInstall install,String operation){
        var db=broker.store.getWritableDatabase();db.beginTransaction();try{
            broker.contract.accept(operation,"pending-install:"+install.generation,"a".repeat(64));broker.contract.start(operation);
            var targets=RuntimeComponentRestart.mark(broker,operation,install);db.setTransactionSuccessful();return targets;
        }finally{db.endTransaction();}
    }
    /** Model endpoints deliberately block STOP; no real package/process is stalled. */
    private void processBoundaries(RuntimeBroker broker,ApkStore.BundleInstall install)throws Exception{
        LaunchModel before;String operation=UUID.randomUUID().toString();
        synchronized(broker){before=launch(broker,install,false);intent(broker,install,operation);}
        broker.processes.stop(before.slot.allocation.session.processGeneration);
        synchronized(broker){
            check.accept("reserved-before-bind-target-retires-without-a-physical-stop",broker.store.isDead(before.slot.allocation.session.processGeneration)&&broker.contract.query(operation).state==ExecutionContract.OperationState.SUCCEEDED);
            var start=ProcessSupervisor.class.getDeclaredMethod("start",before.launch.getClass());start.setAccessible(true);start.invoke(broker.processes,before.launch);
            var bind=before.launch.getClass().getDeclaredField("bindAdmissionStarted");bind.setAccessible(true);
            check.accept("retired-reservation-cannot-later-issue-bind",!bind.getBoolean(before.launch));
        }
        // Drain the queued release before constructing the next model slot.
        java.util.concurrent.CountDownLatch main=new java.util.concurrent.CountDownLatch(1);new android.os.Handler(android.os.Looper.getMainLooper()).post(main::countDown);if(!main.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("MAIN_RELEASE_TIMEOUT");
        LaunchModel stalled;String stalledOperation=UUID.randomUUID().toString();List<String> targets;
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.CountDownLatch(1);
        var dispatches=new java.util.concurrent.atomic.AtomicInteger();
        RuntimeWire.Endpoint endpoint=new RuntimeWire.Endpoint(){@Override protected android.os.Bundle handle(int code,android.os.Bundle input,int pid,int uid)throws Exception{
            if(code!=RuntimeWire.STOP)throw new AssertionError("UNEXPECTED_MODEL_COMMAND");dispatches.incrementAndGet();entered.countDown();
            try{if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("MODEL_RELEASE_TIMEOUT");return new android.os.Bundle();}finally{returned.countDown();}
        }};
        synchronized(broker){
            stalled=launch(broker,install,true);var peer=stalled.launch.getClass().getDeclaredField("peer");peer.setAccessible(true);peer.set(stalled.launch,endpoint);stalled.slot.peer=endpoint;
            targets=intent(broker,install,stalledOperation);broker.processes.restartComponents(targets,stalledOperation);
        }
        try{
            if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("MODEL_STOP_NOT_ENTERED");
            long stopBegin=android.os.SystemClock.elapsedRealtime();boolean unconfirmed=false;
            try{broker.processes.close(stalled.slot.allocation.session.processGeneration);}
            catch(IllegalStateException expected){unconfirmed="PROCESS_STOP_UNCONFIRMED".equals(expected.getMessage());}
            long stopElapsed=android.os.SystemClock.elapsedRealtime()-stopBegin;
            check.accept("explicit-stop-deadline-bounds-a-blocked-binder-rpc",unconfirmed&&stopElapsed>=14000&&stopElapsed<18000);
            check.accept("restart-and-explicit-close-share-one-stop-dispatch",dispatches.get()==1);
            long deadline=android.os.SystemClock.elapsedRealtime()+18000;
            while(broker.contract.query(stalledOperation).state==ExecutionContract.OperationState.RUNNING&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50);
            synchronized(broker){
                check.accept("stalled-stop-rpc-has-independent-unknown-deadline",broker.contract.query(stalledOperation).state==ExecutionContract.OperationState.UNKNOWN);
                blocked("unknown-stop-keeps-new-generation-fenced",()->broker.store.requireProcessStart(install,install.info.packageName,true));
                check.accept("stop-rpc-timeout-does-not-assert-target-dead",!broker.store.isDead(stalled.slot.allocation.session.processGeneration));
            }
            release.countDown();if(!returned.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("MODEL_ACK_TIMEOUT");
            check.accept("late-stop-ack-is-not-death-evidence",!broker.store.isDead(stalled.slot.allocation.session.processGeneration)&&stalled.slot.stopDispatch.death.getCount()==1);
            // Exercise the existing death callback separately from the blocked RPC.
            var died=stalled.launch.getClass().getDeclaredMethod("peerDied");died.setAccessible(true);died.invoke(stalled.launch);
            check.accept("late-death-coordinates-original-unknown-restart",broker.contract.query(stalledOperation).state==ExecutionContract.OperationState.SUCCEEDED&&new org.json.JSONObject(broker.contract.query(stalledOperation).evidence).getJSONObject("prior").getString("detail").equals("COMPONENT_RESTART_DEATH_UNCONFIRMED"));
        }finally{release.countDown();returned.await(5,java.util.concurrent.TimeUnit.SECONDS);}
    }
    /** Ordinary unexpected death intentionally pauses its owner. Keep that
     * distinct from the preceding coordinated-restart recovery oracle. */
    private void externalClaimBoundary(RuntimeBroker broker,ApkStore.BundleInstall install)throws Exception{
        LaunchModel unclaimed;
        synchronized(broker){unclaimed=launch(broker,install,true);}
        var drain=ProcessSupervisor.class.getDeclaredMethod("drain",unclaimed.launch.getClass(),Exception.class);drain.setAccessible(true);
        drain.invoke(broker.processes,unclaimed.launch,new IllegalStateException("OWNED_COLD_CLAIM_TIMEOUT"));
        check.accept("external-without-peer-is-not-death-evidence",!broker.store.isDead(unclaimed.slot.allocation.session.processGeneration));
        check.accept("unclaimed-external-keeps-reservation-and-death-unconfirmed",unclaimed.slot.closing&&unclaimed.slot.stopDispatch.death.getCount()==1);
        var modelDeath=unclaimed.launch.getClass().getDeclaredMethod("peerDied");modelDeath.setAccessible(true);modelDeath.invoke(unclaimed.launch);
    }
    private void recoveryBoundaries(RuntimeBroker broker,ApkStore.BundleInstall install,Context target)throws Exception{
        synchronized(broker){
            var db=broker.store.getWritableDatabase();String jobOperation=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
            broker.contract.accept(jobOperation,"job-install:"+install.generation,"b".repeat(64));broker.contract.start(jobOperation);broker.jobs.mutation(jobOperation,install.generation,"SCHEDULE");
            String stub=RuntimeJobSlots.stub(7,3),processStub=RuntimeSlot.activity(7).getName();
            db.execSQL("INSERT INTO job_components(id,space,package,installGeneration,processName,processStub,stub,component) VALUES(?,?,?,?,?,?,?,?)",new Object[]{lease,install.space,install.info.packageName,install.generation,install.info.packageName,processStub,stub,install.info.packageName+".OwnedJob"});
            var logical=new android.app.job.JobInfo.Builder(771,new ComponentName(install.info.packageName,install.info.packageName+".OwnedJob")).setMinimumLatency(3600000).build();
            var physical=new android.app.job.JobInfo.Builder(771,new ComponentName(target,stub)).setMinimumLatency(3600000).build();
            // Journal model only: no platform schedule RPC is performed or asserted.
            broker.jobs.prepared(version,broker.jobs.components.get(lease),"owned-recovery",logical,physical,jobOperation,null,null);broker.jobs.state(version,"ACCEPTED");broker.jobs.close(jobOperation);broker.contract.succeed(jobOperation,"OWNED_MODEL");
            String failed=session(broker,install);broker.store.session(broker.contract.markFailed(failed,"OWNED_HISTORICAL_FAILURE"));String operation=UUID.randomUUID().toString();intent(broker,install,operation);
            RuntimeComponentRestart.reconcile(broker);
            check.accept("historical-failed-marker-stays-running-without-death-proof",broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING);
            // pid=0 model session is positively absent from the real AMS observation.
            broker.store.recoverSessions(target);broker.contract.onRecover();RuntimeComponentRestart.reconcile(broker);
            var receipt=broker.contract.query(operation);
            check.accept("marked-failed-owner-needs-real-recovery-absence-observation",broker.store.isDead(failed)&&receipt.state==ExecutionContract.OperationState.SUCCEEDED&&new org.json.JSONObject(receipt.evidence).getJSONObject("prior").getString("detail").equals("BROKER_RECOVERY"));
            check.accept("marker-recovery-and-historic-pause-preserve-new-job-and-pending",!broker.jobs.get(version).fenced&&!broker.pendingIntents.get("owned-pending").fenced&&broker.pendingIntents.get("owned-pending").current);
            String ordinary=session(broker,install);broker.store.recoverSessions(target);
            check.accept("ordinary-unconfirmed-recovery-still-fences-current-entries",broker.jobs.get(version).fenced&&broker.pendingIntents.get("owned-pending").fenced&&!broker.pendingIntents.get("owned-pending").current&&broker.store.recoveryPaused(ordinary));
            check.accept("history-and-original-setting-receipt-survive-recovery",scalar(db,"SELECT COUNT(*) FROM recovery_paused").equals("2")&&broker.contract.query(operation).state==ExecutionContract.OperationState.SUCCEEDED);
        }
    }
}
