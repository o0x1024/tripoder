package dev.tripoder.runtime;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Runs only in the separate compatibility APK; all journal/resource mutations use a scratch DB. */
public final class CompatibilityIntegration extends Instrumentation {
    private Bundle arguments;
    private final List<String> checks=new ArrayList<>();
    private void check(String name,boolean value){if(!value)throw new AssertionError(name);checks.add(name);android.util.Log.i("TripoderCompat",name);}
    @Override public void onCreate(Bundle args){arguments=args;start();}
    private static ExecutionContract.Operation admission(String id,String caller){return new ExecutionContract.Operation(id,caller,"a".repeat(64),ExecutionContract.OperationState.ACCEPTED,"","",1,1);}
    private static String scalar(SQLiteDatabase db,String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    private void rejected(String name,Runnable action){try{action.run();throw new AssertionError(name+" admitted");}catch(ExecutionContract.AdmissionRejected expected){check(name,true);}}
    static Context scoped(Context target,File directory){return new ContextWrapper(target){
        @Override public File getFilesDir(){return directory;}
        @Override public File getDatabasePath(String name){return new File(directory,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).toString(),factory,handler);}
    };}
    /** Constructed envelopes only; never sends a suspension or changes Android package state. */
    private void selfPackageObservations(){
        String host="dev.tripoder.owned.container",guest="dev.tripoder.owned.guest";
        for(String action:RuntimeSystemBroadcasts.SELF_PACKAGE){
            var observed=new android.content.Intent(action).setPackage(host).putExtra("owned-model-value",7);
            var decoded=RuntimeBroadcastIntent.decode(RuntimeSystemBroadcasts.observation(observed));
            check("self-package-envelope-retains-physical-target-"+action,host.equals(decoded.getPackage()));
            var logical=RuntimeSystemBroadcasts.forGuest(RuntimeSystemBroadcasts.visible(decoded,java.util.Set.of()),guest,host);
            check("self-package-observation-maps-only-owned-container-target-"+action,guest.equals(logical.getPackage())&&logical.getIntExtra("owned-model-value",0)==7&&host.equals(observed.getPackage()));
            var delivered=RuntimeBroadcastIntent.decode(RuntimeSystemBroadcasts.encode(logical,java.util.Set.of()));
            check("self-package-logical-delivery-keeps-canonical-guest-target-"+action,guest.equals(delivered.getPackage()));
            boolean missing=false;try{RuntimeSystemBroadcasts.observation(new android.content.Intent(action));}catch(SecurityException expected){missing="SELF_PACKAGE_EVENT_TARGET_REQUIRED".equals(expected.getMessage());}
            check("self-package-observation-never-infers-missing-physical-target-"+action,missing);
            for(String foreign:new String[]{"dev.tripoder.other",guest}){
                boolean denied=false;try{RuntimeSystemBroadcasts.forGuest(new android.content.Intent(action).setPackage(foreign),guest,host);}catch(SecurityException expected){denied="SELF_PACKAGE_EVENT_NOT_VISIBLE".equals(expected.getMessage());}
                check("self-package-observation-rejects-foreign-or-replayed-logical-target-"+action+"-"+foreign,denied);
            }
        }
        boolean unrelated=false;try{RuntimeSystemBroadcasts.forGuest(new android.content.Intent(android.content.Intent.ACTION_PACKAGE_CHANGED,android.net.Uri.parse("package:dev.tripoder.other")),guest,host);}catch(SecurityException expected){unrelated="PACKAGE_EVENT_NOT_VISIBLE".equals(expected.getMessage());}
        check("self-package-profile-does-not-broaden-other-package-events",unrelated);
    }
    /** Exercise the real broker callback gate; no provider calls are dispatched. */
    private void privateProviderPublication()throws Exception{
        var declaration=new android.content.pm.ProviderInfo();declaration.packageName="dev.tripoder.owned.private";declaration.name="dev.tripoder.owned.private.Documents";declaration.authority="dev.tripoder.owned.private.documents";declaration.exported=false;
        var access=RuntimeProviderBroker.class.getDeclaredMethod("access",android.content.pm.ProviderInfo.class,String.class,String.class,android.net.Uri.class);access.setAccessible(true);
        var uri=android.net.Uri.parse("content://"+declaration.authority+"/owned-model");
        for(String kind:new String[]{"ready","query","getType","call","insert","update","delete","bulkInsert"}){
            boolean denied=false;try{access.invoke(null,declaration,"dev.tripoder.foreign",kind,uri);}catch(java.lang.reflect.InvocationTargetException failure){Throwable cause=failure.getCause();denied=cause instanceof SecurityException&&"PROVIDER_NOT_EXPORTED".equals(cause.getMessage());}
            check("private-provider-instance-configuration-cannot-export-broker-callback-"+kind,denied);
            access.invoke(null,declaration,declaration.packageName,kind,uri);
            check("private-provider-canonical-owner-keeps-broker-callback-"+kind,true);
        }
    }
    private void migration(Context target,File parent,int oldVersion)throws Exception{
        File directory=new File(parent,"migration-"+oldVersion);if(!directory.mkdir())throw new IllegalStateException("MIGRATION_DIRECTORY");Context owned=scoped(target,directory);
        try(RuntimeStore fresh=new RuntimeStore(owned)){check("fresh-schema-39-"+oldVersion,fresh.getWritableDatabase().getVersion()==39);}
        try(SQLiteDatabase seed=SQLiteDatabase.openDatabase(owned.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READWRITE)){
            seed.execSQL("DROP INDEX isolated_session_owner");for(String column:new String[]{"processKind","processInstance","realUid"})seed.execSQL("ALTER TABLE sessions DROP COLUMN "+column);seed.execSQL("ALTER TABLE service_leases DROP COLUMN isolatedInstanceName");if(oldVersion<38)seed.execSQL("ALTER TABLE sessions DROP COLUMN componentRestartOperation");if(oldVersion<37)seed.execSQL("DROP TABLE component_settings");else ComponentSettingsLedger.set(seed,"migration","dev.migration","retained-data","dev.migration.Component",2);if(oldVersion==35){seed.execSQL("DROP TABLE recovery_paused");seed.execSQL("DROP TABLE operation_scopes");}
            seed.execSQL("INSERT INTO spaces VALUES('migration',1)");
            seed.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName) VALUES('old-generation','migration','dev.migration','old-install',0,'DEAD','dev.migration')");
            seed.execSQL("INSERT INTO operations(id,caller,digest,state,result,detail,created,updated) VALUES('unknown-before-upgrade','migration',?,'UNKNOWN','','owned-unknown',1,1)",new Object[]{"a".repeat(64)});
            seed.execSQL("CREATE TABLE preserved_data(value BLOB)");seed.execSQL("INSERT INTO preserved_data VALUES(X'000102FF')");seed.setVersion(oldVersion);
        }
        try(RuntimeStore upgraded=new RuntimeStore(owned)){
            SQLiteDatabase db=upgraded.getWritableDatabase();check("schema-"+oldVersion+"-to-39",db.getVersion()==39);
            check("migration-"+oldVersion+"-preserves-data-and-unknown",scalar(db,"SELECT hex(value) FROM preserved_data").equals("000102FF")&&scalar(db,"SELECT state FROM operations WHERE id='unknown-before-upgrade'").equals("UNKNOWN")&&scalar(db,"SELECT state FROM sessions WHERE generation='old-generation'").equals("DEAD"));
            check("migration-"+oldVersion+"-adds-component-state-without-reset",scalar(db,"SELECT COUNT(*) FROM component_settings").equals(oldVersion<37?"0":"1"));
            if(oldVersion==37)check("schema37-preserves-component-override",ComponentSettingsLedger.snapshot(db,"migration","dev.migration","retained-data").getInt("dev.migration.Component")==2);
            if(oldVersion==35)check("schema35-recovery-fence-preserved",scalar(db,"SELECT COUNT(*) FROM recovery_paused WHERE generation='old-generation'").equals("1"));
        }
    }
    private record CancellationCase(String operation,String version,String session,String instance,android.app.job.JobInfo physical,android.app.job.JobScheduler platform){}
    private CancellationCase cancellationCase(RuntimeBroker broker,Context target,String kind,int lane)throws Exception{
        var db=broker.store.getWritableDatabase();String install=java.util.UUID.randomUUID().toString(),session=java.util.UUID.randomUUID().toString(),operation=java.util.UUID.randomUUID().toString(),lease=java.util.UUID.randomUUID().toString();
        String space="owned-cancel-"+lane,pkg="dev.tripoder.owned.cancel"+lane,stub=RuntimeJobSlots.stub(6,lane),processStub=RuntimeSlot.activity(6).getName();
        db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,activityStub,authorityGeneration) VALUES(?,?,?,?,0,'DEAD',?,?,?)",new Object[]{session,space,pkg,install,pkg,processStub,broker.store.authorityGeneration});
        broker.contract.accept(operation,"job-install:"+install,"a".repeat(64));broker.contract.start(operation);broker.jobs.mutation(operation,install,kind);
        db.execSQL("INSERT INTO job_components(id,space,package,installGeneration,processName,processStub,stub,component) VALUES(?,?,?,?,?,?,?,?)",new Object[]{lease,space,pkg,install,pkg,processStub,stub,pkg+".Service"});
        String version=ApkStore.textDigest(operation);android.os.PersistableBundle route=new android.os.PersistableBundle();route.putString(RuntimeJobInfo.VERSION,version);
        var logical=new android.app.job.JobInfo.Builder(881,new android.content.ComponentName(pkg,pkg+".Service")).setMinimumLatency(3600000).build();
        var physical=new android.app.job.JobInfo.Builder(881,new android.content.ComponentName(target,stub)).setMinimumLatency(3600000).setExtras(route).build();
        broker.jobs.prepared(version,broker.jobs.components.get(lease),"owned-cancel-objective",logical,physical,operation,null,null);broker.jobs.fence(version);broker.jobs.state(version,"ACCEPTED");
        broker.contract.markUnknown(operation,"OWNED_ACK_LOST");
        var platform=target.getSystemService(android.app.job.JobScheduler.class).forNamespace(broker.jobs.get(version).physicalNamespace);
        return new CancellationCase(operation,version,session,java.util.UUID.randomUUID().toString(),physical,platform);
    }
    /** Real Android pending/cancel observations; all journal fixtures remain in a scratch authority. */
    private void cancellationRecovery(Context target,File parent)throws Exception{
        File directory=new File(parent,"cancel-objective");if(!directory.mkdir())throw new IllegalStateException("CANCEL_SCRATCH_DIRECTORY");RuntimeBroker broker=new RuntimeBroker(scoped(target,directory));
        CancellationCase cancelled=null;
        try{
            var scheduled=cancellationCase(broker,target,"SCHEDULE",0);var enqueued=cancellationCase(broker,target,"ENQUEUE",1);cancelled=cancellationCase(broker,target,"CANCEL",2);var value=cancelled;var db=broker.store.getWritableDatabase();
            check("cancel-recovery-real-platform-job-accepted",value.platform.schedule(value.physical)==android.app.job.JobScheduler.RESULT_SUCCESS);
            RuntimeJobCancellationRecovery.reconcile(broker);
            check("pending-job-prevents-cancel-coordination",broker.contract.query(value.operation).state==ExecutionContract.OperationState.UNKNOWN);
            android.os.PersistableBundle replacement=new android.os.PersistableBundle();replacement.putString(RuntimeJobInfo.VERSION,"f".repeat(64));
            var different=new android.app.job.JobInfo.Builder(881,value.physical.getService()).setMinimumLatency(3600000).setExtras(replacement).build();
            check("cancel-recovery-real-replacement-accepted",value.platform.schedule(different)==android.app.job.JobScheduler.RESULT_SUCCESS);RuntimeJobCancellationRecovery.reconcile(broker);
            check("different-version-at-same-key-still-prevents-coordination",broker.contract.query(value.operation).state==ExecutionContract.OperationState.UNKNOWN);
            value.platform.cancel(881);check("cancel-recovery-real-platform-key-is-absent",value.platform.getPendingJob(881)==null);
            db.execSQL("UPDATE sessions SET state='FAILED' WHERE generation=?",new Object[]{value.session});RuntimeJobCancellationRecovery.reconcile(broker);
            check("failed-owner-is-not-confirmed-dead",broker.contract.query(value.operation).state==ExecutionContract.OperationState.UNKNOWN);
            db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{value.session});db.execSQL("UPDATE job_versions SET fenced=0 WHERE version=?",new Object[]{value.version});RuntimeJobCancellationRecovery.reconcile(broker);
            check("unfenced-target-prevents-cancel-coordination",broker.contract.query(value.operation).state==ExecutionContract.OperationState.UNKNOWN);
            broker.jobs.fence(value.version);var version=broker.jobs.get(value.version);
            db.execSQL("INSERT INTO job_instances(instance,lease,generation,stub,state) VALUES(?,?,?,?,'PROCESS_DEAD')",new Object[]{value.instance,version.lease,"owned-missing-session",value.physical.getService().getClassName()});RuntimeJobCancellationRecovery.reconcile(broker);
            check("missing-instance-owner-keeps-cancel-unknown",broker.contract.query(value.operation).state==ExecutionContract.OperationState.UNKNOWN);
            db.execSQL("UPDATE job_instances SET generation=? WHERE instance=?",new Object[]{value.session,value.instance});RuntimeJobCancellationRecovery.reconcile(broker);
            var receipt=broker.contract.query(value.operation);
            check("fenced-inert-cancel-resolves-by-real-absence-without-replay",receipt.state==ExecutionContract.OperationState.SUCCEEDED&&receipt.result.equals("CANCELLED_STATE_OBSERVED")&&scalar(db,"SELECT closed FROM job_mutations WHERE operation=?",value.operation).equals("1"));
            check("cancel-coordination-keeps-complete-prior-unknown-reason",new JSONObject(receipt.evidence).getJSONObject("prior").getString("detail").equals("OWNED_ACK_LOST"));
            check("absence-does-not-resolve-schedule-or-enqueue",broker.contract.query(scheduled.operation).state==ExecutionContract.OperationState.UNKNOWN&&broker.contract.query(enqueued.operation).state==ExecutionContract.OperationState.UNKNOWN&&scalar(db,"SELECT COUNT(*) FROM job_mutations WHERE kind IN ('SCHEDULE','ENQUEUE') AND closed=0").equals("2"));
            check("cancel-coordination-preserves-fences-and-admission-history",broker.jobs.get(value.version).fenced&&broker.jobs.get(value.version).state.equals("ACCEPTED"));
            unstartedRecovery(broker);
        }finally{if(cancelled!=null)cancelled.platform.cancel(881);broker.store.close();}
    }
    private void unstartedRecovery(RuntimeBroker broker)throws Exception{
        var db=broker.store.getWritableDatabase();String space="owned-unstarted",pkg="dev.tripoder.owned.unstarted",install=java.util.UUID.randomUUID().toString(),session=java.util.UUID.randomUUID().toString(),operation=java.util.UUID.randomUUID().toString(),scope="space/"+space+"/package/"+pkg;
        db.execSQL("INSERT INTO spaces(id,created) VALUES(?,1)",new Object[]{space});
        db.execSQL("INSERT INTO installs(space,package,generation,apk,sha256,descriptorSha,state,reason,version,signer,dataId,operation) VALUES(?,?,?,'owned.apk',?,?,'COMMITTED','',1,'owned','owned-data','owned-install')",new Object[]{space,pkg,install,"a".repeat(64),"b".repeat(64)});
        db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName,authorityGeneration) VALUES(?,?,?,?,0,'DEAD',?,?)",new Object[]{session,space,pkg,install,pkg,broker.store.authorityGeneration});
        StringBuilder encoded=new StringBuilder();for(String part:new String[]{space,pkg,install,pkg,session})encoded.append(part.length()).append(':').append(part);String caller=encoded.toString();
        broker.contract.accept(operation,caller,"c".repeat(64));broker.contract.markUnknown(operation,"BROKER_RECOVERY");long unknownAt=broker.contract.query(operation).updatedAtMs;
        check("pre-start-recovery-needs-later-explicit-stop",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        String stopped=java.util.UUID.randomUUID().toString();String digest=RuntimeControllerOperationIdentity.stopDigest(space,pkg,install);
        db.execSQL("INSERT INTO operations(id,caller,digest,state,result,detail,created,updated,evidence) VALUES(?,?,?,'SUCCEEDED','{}','',?,?,'')",new Object[]{stopped,RuntimeControllerOperationIdentity.CALLER,RuntimeControllerOperationIdentity.stopDigest(space,pkg,"different-install"),unknownAt,unknownAt});
        db.execSQL("INSERT INTO operation_scopes VALUES(?,?, 'stopApplication','MAY_HAVE_DISPATCHED')",new Object[]{stopped,scope});
        check("pre-start-recovery-rejects-another-install-stop",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operations SET digest=?,created=? WHERE id=?",new Object[]{digest,unknownAt-1,stopped});
        check("pre-start-recovery-rejects-earlier-stop",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operations SET created=?,caller='untrusted-owned-caller' WHERE id=?",new Object[]{unknownAt,stopped});
        check("pre-start-recovery-rejects-untrusted-stop",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operations SET caller=? WHERE id=?",new Object[]{RuntimeControllerOperationIdentity.CALLER,stopped});
        check("durable-not-dispatched-and-exact-stop-prove-execution-inert",RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        check("new-pre-start-proof-is-not-an-install-exception",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,operation,scope));
        db.execSQL("UPDATE operation_scopes SET dispatch='MAY_HAVE_DISPATCHED' WHERE operation=?",new Object[]{operation});
        check("committed-start-without-ticket-remains-fenced",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operation_scopes SET dispatch='NOT_DISPATCHED' WHERE operation=?",new Object[]{operation});
        db.execSQL("UPDATE operations SET caller=? WHERE id=?",new Object[]{"0"+caller,operation});
        check("noncanonical-caller-frame-is-not-owner-proof",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operations SET caller=? WHERE id=?",new Object[]{caller.replace(install,"x"+install.substring(1)),operation});
        check("caller-install-frame-must-match-exact-session",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operations SET caller=? WHERE id=?",new Object[]{caller,operation});
        db.execSQL("UPDATE sessions SET state='FAILED' WHERE generation=?",new Object[]{session});
        check("pre-start-owner-failed-is-not-dead",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{session});
        db.execSQL("INSERT INTO broadcast_registrations VALUES('owned-registration',?,?,?,'owned-action',X'',0,'ACTIVE','[]','')",new Object[]{session,space,pkg});
        check("open-registration-prevents-pre-start-retirement",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE broadcast_registrations SET state='CLOSED' WHERE id='owned-registration'");
        db.execSQL("INSERT INTO service_calls VALUES(?,'owned-lease',?,'bind','owned-connection','PREPARED')",new Object[]{operation,session});
        check("contradictory-admission-prevents-pre-start-retirement",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("DELETE FROM service_calls WHERE operation=?",new Object[]{operation});
        Bundle launch=new Bundle();launch.putString("command","prepareApplicationLaunch");launch.putString("space",space);launch.putString("package",pkg);String fresh=java.util.UUID.randomUUID().toString();launch.putString("operation",fresh);
        try(var guard=RuntimeMutationGuard.enter(null,launch)){broker.contract.accept(fresh,RuntimeControllerOperationIdentity.CALLER,"d".repeat(64));broker.contract.start(fresh);}
        String ack=scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation=?",operation);
        check("fresh-start-acknowledges-unstarted-effect-without-rewriting-unknown",ack.equals(RuntimeEffectRecoveryProof.ACK+fresh)&&broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN);
        String live=java.util.UUID.randomUUID().toString();db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName) VALUES(?,?,?,?,0,'RUNNING',?)",new Object[]{live,space,pkg,install,pkg});
        check("acknowledged-new-generation-continuation-remains-admitted",RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,operation,ack,scope));
        check("unacknowledged-explicit-start-cannot-ignore-live-owner",!RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        db.execSQL("UPDATE operation_scopes SET scope='space/owned-unrelated/package/dev.unrelated' WHERE operation=?",new Object[]{fresh});
        check("ack-cannot-borrow-another-scope",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,operation,ack,scope));
        db.execSQL("UPDATE operation_scopes SET scope=? WHERE operation=?",new Object[]{scope,fresh});
        broker.contract.markUnknown(fresh,"LAUNCH_PREPARATION_UNCONFIRMED");db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{live});
        check("old-inert-proof-survives-failure-of-acknowledged-start",RuntimeEffectRecoveryProof.permitsFreshStart(db,operation,scope));
        check("failed-start-does-not-grant-continuation",!RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,operation,ack,scope));
        String again=java.util.UUID.randomUUID().toString();launch.putString("operation",again);
        try(var guard=RuntimeMutationGuard.enter(null,launch)){
            try{broker.contract.accept(again,RuntimeControllerOperationIdentity.CALLER,"e".repeat(64));throw new AssertionError("failed new start admitted");}
            catch(ExecutionContract.ContractException rejected){check("failed-new-start-keeps-its-own-unknown-fenced",rejected.code.equals("RECOVERY_SCOPE_UNKNOWN"));}
        }
        long stoppedAt=broker.contract.query(fresh).updatedAtMs;db.execSQL("UPDATE operations SET created=?,updated=? WHERE id=?",new Object[]{stoppedAt,stoppedAt,stopped});
        try(var guard=RuntimeMutationGuard.enter(null,launch)){broker.contract.accept(again,RuntimeControllerOperationIdentity.CALLER,"e".repeat(64));broker.contract.start(again);}
        check("second-explicit-start-can-reack-both-exact-inert-attempts",scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation=?",operation).equals(RuntimeEffectRecoveryProof.ACK+again)&&scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation=?",fresh).equals(RuntimeEffectRecoveryProof.ACK+again));
        check("repeated-recovery-keeps-both-original-business-unknowns",broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN&&broker.contract.query(fresh).state==ExecutionContract.OperationState.UNKNOWN);
    }
    @Override public void onStart(){
        android.util.Log.i("TripoderCompat","checks-begin");
        Thread watcher=new Thread(()->{try{Thread.sleep(60000);for(var entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().contains("Instr")||entry.getKey().getName().contains("ForkJoin")){android.util.Log.w("TripoderCompat","thread="+entry.getKey().getName()+" state="+entry.getKey().getState());for(var frame:entry.getValue())android.util.Log.w("TripoderCompat",frame.toString());}}catch(InterruptedException ignored){}},"compat-watchdog");watcher.setDaemon(true);watcher.start();
        Bundle result=new Bundle();JSONObject report=new JSONObject();File scratch=null;
        try{
            Context target=getTargetContext();
            if(!target.getPackageName().startsWith("dev.tripoder.compat"))throw new SecurityException("SEPARATE_COMPAT_PACKAGE_REQUIRED");
            scratch=new File(target.getFilesDir(),"compat-"+java.util.UUID.randomUUID());if(!scratch.mkdir())throw new IllegalStateException("SCRATCH_DIRECTORY");
            File directory=scratch;Context scoped=new ContextWrapper(target){
                @Override public File getFilesDir(){return directory;}
                @Override public File getDatabasePath(String name){return new File(directory,name);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).toString(),factory,handler);}
            };
            File database=new File(scratch,"tripoder-runtime-v2.db");
            try(SQLiteDatabase seed=SQLiteDatabase.openOrCreateDatabase(database,null)){seed.execSQL("CREATE TABLE sessions(installGeneration TEXT,space TEXT,package TEXT,generation TEXT,state TEXT DEFAULT 'DEAD',pid INTEGER NOT NULL DEFAULT 0,processName TEXT,authorityGeneration TEXT)");ServiceLedger.create(seed);seed.setVersion(36);}
            try(RuntimeStore store=new RuntimeStore(scoped)){
                SQLiteDatabase db=store.getWritableDatabase();
                db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY,caller TEXT,state TEXT,detail TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL DEFAULT 1,updated INTEGER NOT NULL DEFAULT 1)");
                db.execSQL("CREATE TABLE operation_scopes(operation TEXT PRIMARY KEY,scope TEXT,command TEXT,dispatch TEXT)");
                db.execSQL("CREATE TABLE installs(generation TEXT,space TEXT,package TEXT)");
                ActivityLedger.create(db);ActivityLaunchLedger.create(db);BroadcastLedger.create(db);
                db.execSQL("INSERT INTO installs VALUES('install-a','qa-a','dev.a'),('install-b','qa-b','dev.b')");
                check("scratch-schema36-migrates-without-reset",db.getVersion()==39);
                ComponentSettingsLedger.set(db,"qa-a","dev.a","data-a","dev.a.Component",1);
                check("component-state-is-scoped-by-data-incarnation",ComponentSettingsLedger.snapshot(db,"qa-a","dev.a","data-a").getInt("dev.a.Component")==1&&ComponentSettingsLedger.snapshot(db,"qa-b","dev.a","data-a").isEmpty()&&ComponentSettingsLedger.snapshot(db,"qa-a","dev.a","data-b").isEmpty());
                ComponentSettingsLedger.set(db,"qa-a","dev.a","data-a","dev.a.Component",0);
                check("component-default-restores-declaration-without-override",ComponentSettingsLedger.snapshot(db,"qa-a","dev.a","data-a").isEmpty());
                check("upgrade-scope-resolves-exact-existing-generation",RuntimeMutationGuard.upgradePackage(db,"qa-a","install-a").equals("dev.a"));
                check("upgrade-generation-cannot-borrow-another-space",RuntimeMutationGuard.upgradePackage(db,"qa-b","install-a")==null);
                db.execSQL("INSERT INTO operations(id,caller,state) VALUES('historic','pending-install:install-a','UNKNOWN')");
                db.execSQL("INSERT INTO operation_scopes VALUES('historic','*','legacy-or-internal','MAY_HAVE_DISPATCHED')");
                RuntimeMutationGuard.accepting(db,admission("other-app","pending-install:install-b"));
                check("unrelated-pending-admitted",scalar(db,"SELECT scope FROM operation_scopes WHERE operation='other-app'").equals("space/qa-b/package/dev.b"));
                RuntimeMutationGuard.accepting(db,admission("other-job","job-install:install-b"));
                check("unrelated-job-admitted",scalar(db,"SELECT scope FROM operation_scopes WHERE operation='other-job'").equals("space/qa-b/package/dev.b"));
                rejected("same-owner-still-fenced",()->RuntimeMutationGuard.accepting(db,admission("own","pending-install:install-a")));
                Bundle scope=new Bundle();scope.putString("space","qa-a");scope.putString("command","deleteSpace");
                try(var guard=RuntimeMutationGuard.enter(null,scope)){rejected("delete-own-space-still-fenced",()->RuntimeMutationGuard.accepting(db,admission("delete","controller")));}
                check("historic-unknown-retained",scalar(db,"SELECT state FROM operations WHERE id='historic'").equals("UNKNOWN")&&scalar(db,"SELECT scope FROM operation_scopes WHERE operation='historic'").equals("*"));
                db.execSQL("DELETE FROM installs WHERE generation='install-a'");
                db.execSQL("INSERT INTO sessions(installGeneration,space,package) VALUES('install-a','qa-a','dev.a')");
                RuntimeMutationGuard.accepting(db,admission("after-removal","pending-install:install-b"));
                check("historic-session-proves-owner-after-removal",scalar(db,"SELECT scope FROM operation_scopes WHERE operation='after-removal'").equals("space/qa-b/package/dev.b"));
                db.execSQL("DELETE FROM sessions");
                rejected("missing-ownership-stays-global",()->RuntimeMutationGuard.accepting(db,admission("unproved","job-install:install-b")));
                db.execSQL("INSERT INTO sessions(installGeneration,space,package) VALUES('install-a','qa-a','dev.a')");
                db.execSQL("UPDATE operation_scopes SET command='explicit-global' WHERE operation='historic'");
                rejected("explicit-global-stays-global",()->RuntimeMutationGuard.accepting(db,admission("global","job-install:install-b")));
                db.execSQL("UPDATE operation_scopes SET command='legacy-or-internal' WHERE operation='historic'");
                ActivityAffinityLedger.create(db);ActivityComponentLedger.create(db);ActivityEntryLedger.create(db);
                ActivityAffinityLedger affinities=new ActivityAffinityLedger(store);
                for(int i=0;i<RuntimeActivitySlots.AFFINITIES;i++)affinities.acquire("space-"+i,"dev.app"+i,"affinity-"+i);
                affinities.requireCapacity("space-0","dev.app0","affinity-0");
                check("existing-affinity-admitted-at-capacity",scalar(db,"SELECT COUNT(*) FROM activity_affinities").equals("8"));
                try{affinities.requireCapacity("new-space","dev.new","new-affinity");throw new AssertionError("full affinity admitted");}catch(IllegalStateException expected){check("full-affinity-rejected-without-allocation",expected.getMessage().equals("ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED")&&scalar(db,"SELECT COUNT(*) FROM activity_affinities").equals("8"));}
                ActivityEntryLedger entries=new ActivityEntryLedger(store);String canonical=RuntimeActivitySlots.name(0,0,0,0,false);
                db.execSQL("INSERT INTO activity_components(stub,space,package,installGeneration,processName,processStub,component,generation,lease) VALUES(?,'qa-a','dev.a','install-a','dev.a','process-stub','dev.Main','process-a','component-lease')",new Object[]{canonical});
                entries.acquire(canonical,"component-lease","dev.Main","dev.Alias1");entries.acquire(canonical,"component-lease","dev.Main","dev.Alias2");
                entries.requireCapacity("component-lease","dev.Main","dev.Alias1");entries.requireCapacity("component-lease","dev.Main","dev.Main");
                check("aliases-retain-distinct-physical-identities",!entries.acquire(canonical,"component-lease","dev.Main","dev.Alias1").equals(entries.acquire(canonical,"component-lease","dev.Main","dev.Alias2")));
                try{entries.requireCapacity("component-lease","dev.Main","dev.Alias3");throw new AssertionError("third retained alias admitted");}catch(IllegalStateException expected){check("actual-alias-capacity-fails-before-allocation",expected.getMessage().equals("ACTIVITY_ALIAS_CAPACITY_EXHAUSTED"));}
                File aliasStage=new File(scratch,"alias-fixture");if(!aliasStage.mkdir())throw new IllegalStateException("ALIAS_STAGE");
                try(var input=getContext().getAssets().open("aliases.apk")){Files.copy(input,new File(aliasStage,"base.apk").toPath());}
                ArtifactSet aliasSet=ArtifactSet.materialize(aliasStage);
                check("resource-only-apk-worker-load-keeps-sealed-empty-dex-directory",ArtifactSet.loadVerified(aliasStage,aliasSet.descriptorSha).descriptorSha.equals(aliasSet.descriptorSha));
                ArtifactSet liteSet=ArtifactSet.loadTrusted(aliasStage,aliasSet.descriptorSha,aliasSet.liteJson(),ArtifactIntegrityCache.stampDigest(aliasStage));
                check("sealed-full-lite-worker-manifest-keeps-native-dependency-and-required-bit",liteSet.baseManifest.application.children.stream().anyMatch(element->element.name.equals("uses-native-library")&&element.android("name","").equals("libOpenCL.so")&&element.android("required","").equals("false")));
                java.nio.file.Path emptyDex=aliasStage.toPath().resolve("dex");
                Files.setPosixFilePermissions(aliasStage.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));Files.delete(emptyDex);ArtifactSet.seal(aliasStage.toPath(),true);
                try{ArtifactSet.loadVerified(aliasStage,aliasSet.descriptorSha);throw new AssertionError("missing sealed dex directory admitted");}catch(java.io.IOException expected){check("resource-only-apk-still-requires-published-directory",expected.getMessage().equals("ARTIFACT_FILE_SET_MISMATCH"));}
                finally{Files.setPosixFilePermissions(aliasStage.toPath(),java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));Files.createDirectory(emptyDex);ArtifactSet.seal(emptyDex,true);ArtifactSet.seal(aliasStage.toPath(),true);}
                check("resource-only-apk-restores-identical-authenticated-descriptor",ArtifactSet.verify(aliasStage,aliasSet.descriptorSha).descriptorSha.equals(aliasSet.descriptorSha));
                var aliasInfo=target.getPackageManager().getPackageArchiveInfo(aliasSet.base.toString(),android.content.pm.PackageManager.GET_ACTIVITIES|android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                var aliasInstall=new ApkStore.BundleInstall("alias-space","alias-install",aliasSet.base,aliasSet.parts.get(0).sha256,aliasInfo,new File(scratch,"alias-data"),aliasSet);
                ActivityComponentLedger components=new ActivityComponentLedger(store,target);
                try{components.requireCapacity(aliasInstall,"dev.tripoder.aliasfixture.Alias0");throw new AssertionError("full pool admitted alias");}catch(IllegalStateException expected){check("preflight-refuses-full-pool-before-start",expected.getMessage().equals("ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED"));}
                db.execSQL("DELETE FROM activity_affinities");
                components.requireCapacity(aliasInstall,"dev.tripoder.aliasfixture.Alias0");
                check("twelve-declared-aliases-admitted-without-expanding-pool",aliasInstall.activityIndex().size()==14&&RuntimeActivitySlots.ALIASES==2&&scalar(db,"SELECT COUNT(*) FROM activity_affinities").equals("0"));
                db.execSQL("INSERT INTO operations(id,caller,state) VALUES('retired-route','controller','UNKNOWN')");
                db.execSQL("INSERT INTO operation_scopes VALUES('retired-route','space/qa-c/package/dev.c','prepareApplicationLaunch','MAY_HAVE_DISPATCHED')");
                db.execSQL("INSERT INTO sessions(installGeneration,space,package,generation,state) VALUES('install-c','qa-c','dev.c','process-c','RUNNING')");
                db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,taskId,processStub) VALUES('ticket-c','qa-c','dev.c','install-c','dev.c','stub-c','dev.c.Main','process-c','PREPARED',-1,'slot-c')");
                db.execSQL("INSERT INTO activity_launches(id,candidate,generation,operation,expectsResult,state,callerPackage,callerKind) VALUES('delivery-c','ticket-c','process-c','retired-route',0,'UNKNOWN','dev.c','CONTROLLER')");
                check("live-unknown-launch-still-fenced",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"retired-route","space/qa-c/package/dev.c"));
                db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation='process-c'");
                check("dead-generation-alone-does-not-retire-entry",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"retired-route","space/qa-c/package/dev.c"));
                db.execSQL("UPDATE activity_tickets SET state='RETIRED' WHERE ticket='ticket-c'");
                check("terminal-entry-and-dead-generation-prove-no-reexecution",RuntimeEffectRecoveryProof.permitsExplicitStart(db,"retired-route","space/qa-c/package/dev.c"));
                db.execSQL("INSERT INTO operations(id,caller,state,detail,created,updated) VALUES('prep-c','controller','UNKNOWN','LAUNCH_PREPARATION_UNCONFIRMED',2,3)");
                db.execSQL("INSERT INTO operation_scopes VALUES('prep-c','space/qa-c/package/dev.c','prepareApplicationLaunch','MAY_HAVE_DISPATCHED')");
                check("unconfirmed-preparation-needs-later-stop",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"prep-c","space/qa-c/package/dev.c"));
                db.execSQL("INSERT INTO operations(id,caller,state,created,updated) VALUES('stop-c','controller','SUCCEEDED',4,5)");
                db.execSQL("INSERT INTO operation_scopes VALUES('stop-c','space/qa-c/package/dev.c','stopApplication','MAY_HAVE_DISPATCHED')");
                check("explicit-owner-stop-proves-preparation-inert",RuntimeEffectRecoveryProof.permitsExplicitStart(db,"prep-c","space/qa-c/package/dev.c"));
                check("recovery-proof-does-not-rewrite-outcome",scalar(db,"SELECT state FROM operations WHERE id='prep-c'").equals("UNKNOWN"));
                Bundle restart=new Bundle();restart.putString("command","prepareApplicationLaunch");restart.putString("space","qa-c");restart.putString("package","dev.c");
                try(var guard=RuntimeMutationGuard.enter(null,restart)){RuntimeMutationGuard.accepting(db,admission("fresh-c","controller"));}
                db.execSQL("INSERT INTO operations(id,caller,state) VALUES('fresh-c','controller','RUNNING')");
                String acknowledgement=scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation='retired-route'");
                check("fresh-start-acknowledges-exact-inert-execution",RuntimeEffectRecoveryProof.acknowledged(db,acknowledgement,"space/qa-c/package/dev.c"));
                db.execSQL("INSERT INTO sessions(installGeneration,space,package,generation,state) VALUES('install-c','qa-c','dev.c','fresh-process-c','RUNNING')");
                RuntimeMutationGuard.accepting(db,admission("new-callback-c","pending-install:install-c"));
                check("fresh-owner-callback-not-refenced-by-history",scalar(db,"SELECT scope FROM operation_scopes WHERE operation='new-callback-c'").equals("space/qa-c/package/dev.c"));
                check("new-live-owner-does-not-revive-old-preparation",RuntimeEffectRecoveryProof.permitsAcknowledgedContinuation(db,"prep-c",scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation='prep-c'"),"space/qa-c/package/dev.c"));
                check("new-explicit-admission-still-needs-owner-stop",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"prep-c","space/qa-c/package/dev.c"));
                check("fresh-start-keeps-prior-business-unknown",scalar(db,"SELECT state FROM operations WHERE id='retired-route'").equals("UNKNOWN"));
                db.execSQL("INSERT INTO operations(id,caller,state,detail,created,updated) VALUES('prep-d','controller','UNKNOWN','LAUNCH_PREPARATION_UNCONFIRMED',2,3),('stop-d','controller','SUCCEEDED','',4,5)");
                db.execSQL("INSERT INTO operation_scopes VALUES('prep-d','space/qa-d/package/dev.d','prepareApplicationLaunch','MAY_HAVE_DISPATCHED'),('stop-d','space/qa-d/package/dev.d','stopApplication','MAY_HAVE_DISPATCHED')");
                Bundle upgrade=new Bundle();upgrade.putString("command","install");upgrade.putString("space","qa-d");upgrade.putString("package","dev.d");
                try(var guard=RuntimeMutationGuard.enter(null,upgrade)){RuntimeMutationGuard.accepting(db,admission("upgrade-d","controller"));}
                db.execSQL("INSERT INTO operations(id,caller,state) VALUES('upgrade-d','controller','SUCCEEDED')");
                check("explicit-upgrade-admits-only-inert-prior-execution",scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation='prep-d'").equals(RuntimeEffectRecoveryProof.ACK+"upgrade-d"));
                Bundle binding=new Bundle();binding.putString("command","applicationTarget");binding.putString("space","qa-d");binding.putString("package","dev.d");
                try(var guard=RuntimeMutationGuard.enter(null,binding)){RuntimeMutationGuard.accepting(db,admission("metadata-d","trusted-workbench"));}
                check("application-target-binding-does-not-replay-guest",scalar(db,"SELECT command FROM operation_scopes WHERE operation='metadata-d'").equals("applicationTarget")&&scalar(db,"SELECT dispatch FROM operation_scopes WHERE operation='prep-d'").equals(RuntimeEffectRecoveryProof.ACK+"upgrade-d"));
                Bundle implicit=new Bundle();implicit.putString("command","systemCallback");implicit.putString("space","qa-d");implicit.putString("package","dev.d");
                try(var guard=RuntimeMutationGuard.enter(null,implicit)){rejected("upgrade-ack-does-not-grant-callback-execution",()->RuntimeMutationGuard.accepting(db,admission("callback-d","controller")));}
                check("upgrade-does-not-rewrite-unknown-business-outcome",scalar(db,"SELECT state FROM operations WHERE id='prep-d'").equals("UNKNOWN"));
                db.execSQL("INSERT INTO operations(id,caller,state,detail,created,updated) VALUES('service-prep-e','source-e','UNKNOWN','SERVICE_PREPARATION_UNCONFIRMED',2,3)");
                db.execSQL("INSERT INTO operation_scopes VALUES('service-prep-e','space/qa-e/package/dev.e','legacy-or-internal','MAY_HAVE_DISPATCHED')");
                check("failed-service-preparation-needs-owner-stop",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"service-prep-e","space/qa-e/package/dev.e"));
                db.execSQL("INSERT INTO operations(id,caller,state,created,updated) VALUES('stop-e','controller','SUCCEEDED',4,5)");
                db.execSQL("INSERT INTO operation_scopes VALUES('stop-e','space/qa-e/package/dev.e','stopApplication','MAY_HAVE_DISPATCHED')");
                check("stopped-no-dispatch-service-preparation-is-inert",RuntimeEffectRecoveryProof.permitsExplicitStart(db,"service-prep-e","space/qa-e/package/dev.e"));
                db.execSQL("INSERT INTO service_calls VALUES('service-prep-e','lease-e','source-e','bind','connection-e','PREPARED')");
                check("service-dispatch-capability-keeps-unknown-fenced",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"service-prep-e","space/qa-e/package/dev.e"));
                db.execSQL("DELETE FROM service_calls WHERE operation='service-prep-e'");
                db.execSQL("INSERT INTO sessions(installGeneration,space,package,generation,state) VALUES('install-e','qa-e','dev.e','source-e','RUNNING')");
                check("live-owner-service-preparation-stays-fenced",!RuntimeEffectRecoveryProof.permitsExplicitStart(db,"service-prep-e","space/qa-e/package/dev.e"));
                check("service-preparation-proof-keeps-business-unknown",scalar(db,"SELECT state FROM operations WHERE id='service-prep-e'").equals("UNKNOWN"));
                db.execSQL("UPDATE operations SET state='UNKNOWN' WHERE id='fresh-c'");
                check("uncertain-new-start-does-not-authorize-implicit-replay",!RuntimeEffectRecoveryProof.acknowledged(db,acknowledgement,"space/qa-c/package/dev.c"));
            }
            migration(target,scratch,35);migration(target,scratch,36);migration(target,scratch,37);migration(target,scratch,38);
            cancellationRecovery(target,scratch);
            new ComponentRestartChecks(this::check).run(target,scratch,getContext());
            File componentQueries=new File(scratch,"component-state-queries");if(!componentQueries.mkdir())throw new IllegalStateException("COMPONENT_QUERY_SCRATCH");new ComponentStateQueryChecks(this::check).run(scoped(target,componentQueries),getContext());
            new SettingsReadOnlyChecks(this::check).run(target);
            selfPackageObservations();
            File contextReceipts=new File(scratch,"context-start-receipts");if(!contextReceipts.mkdir())throw new IllegalStateException("CONTEXT_RECEIPT_SCRATCH");new ContextStartReceiptChecks(this::check).run(scoped(target,contextReceipts),getContext());
            File emptyTasks=new File(scratch,"empty-task-reclaim");if(!emptyTasks.mkdir())throw new IllegalStateException("EMPTY_TASK_SCRATCH");new EmptyTaskReclaimChecks(this::check).run(scoped(target,emptyTasks));
            File spaceCreation=new File(scratch,"space-creation-recovery");if(!spaceCreation.mkdir())throw new IllegalStateException("SPACE_CREATION_SCRATCH");new SpaceCreationRecoveryChecks(this::check).run(scoped(target,spaceCreation));
            File thinAccess=new File(scratch,"thin-provider-access");if(!thinAccess.mkdir())throw new IllegalStateException("THIN_PROVIDER_SCRATCH");new ThinProviderAccessChecks(this::check).run(scoped(target,thinAccess),getContext());
            File launcher=new File(scratch,"launcher-resolution");if(!launcher.mkdir())throw new IllegalStateException("LAUNCHER_RESOLUTION_SCRATCH");new LauncherResolutionChecks(this::check).run(scoped(target,launcher),getContext());
            ServiceParcelChecks.run(this::check);
            File construction=new File(scratch,"activity-construction");if(!construction.mkdir())throw new IllegalStateException("CONSTRUCTION_SCRATCH");new ActivityConstructionChecks(this::check).run(scoped(target,construction),getContext());
            File stoppedProof=new File(scratch,"stopped-execution-proof");if(!stoppedProof.mkdir())throw new IllegalStateException("STOPPED_PROOF_SCRATCH");new StoppedExecutionRecoveryChecks(this::check).run(scoped(target,stoppedProof));
            new IsolatedSessionChecks(this::check).run(target,scratch,getContext());
            if("true".equals(arguments.getString("nativePublication")))new IsolatedNativePublicationChecks(this::check).run(target,scratch,getContext());
            privateProviderPublication();
            try(SQLiteDatabase reopened=SQLiteDatabase.openOrCreateDatabase(database,null)){check("unknown-survives-independent-reopen",scalar(reopened,"SELECT state FROM operations WHERE id='historic'").equals("UNKNOWN"));}
            check("large-apk-contract-unified",ArtifactSet.MAX_APK_BYTES==2L*1024*1024*1024&&ArtifactSet.MAX_SET_BYTES==4L*1024*1024*1024);
            JSONObject library=new JSONObject().put("name","uses-library").put("namespace","").put("attributes",new JSONObject().put("android:name","org.apache.http.legacy").put("android:required","false")).put("children",new org.json.JSONArray());
            JSONObject nativeLibrary=new JSONObject().put("name","uses-native-library").put("namespace","").put("attributes",new JSONObject().put("android:name","libowned_declared.so").put("android:required","true")).put("children",new org.json.JSONArray());
            JSONObject app=new JSONObject().put("name","application").put("namespace","").put("attributes",new JSONObject()).put("children",new org.json.JSONArray().put(library).put(nativeLibrary));
            JSONObject manifest=new JSONObject().put("name","manifest").put("namespace","").put("attributes",new JSONObject().put("package","dev.tripoder.libraryfixture").put("android:versionCode","1")).put("children",new org.json.JSONArray().put(app));
            try(var reader=new android.util.JsonReader(new java.io.StringReader(manifest.toString()))){
                ApkManifest decoded=ApkManifest.fromJsonReader(reader);
                check("streaming-manifest-retains-declared-platform-library",decoded.application.children.size()==2&&"org.apache.http.legacy".equals(decoded.application.children.get(0).android("name",null)));
                check("streaming-manifest-keeps-native-declaration-kind-and-required-bit",decoded.application.children.get(1).name.equals("uses-native-library")&&decoded.application.children.get(1).android("name",null).equals("libowned_declared.so")&&decoded.application.children.get(1).android("required",null).equals("true"));
            }
            JSONObject beginSchema=null,writeSchema=null;
            try(var input=target.getAssets().open("mcp-contract.json")){
                var contract=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("entries");
                for(int i=0;i<contract.length();i++){var routes=contract.getJSONObject(i).getJSONArray("routes");for(int j=0;j<routes.length();j++){var route=routes.getJSONObject(j);if(route.getString("method").equals("import.begin"))beginSchema=route.getJSONObject("inputSchema");if(route.getString("method").equals("import.write"))writeSchema=route.getJSONObject("inputSchema");}}
            }
            var validator=Class.forName("dev.tripoder.hooklab.AgentSchema").getDeclaredMethod("validate",Object.class,JSONObject.class,String.class);validator.setAccessible(true);
            JSONObject metadata=new JSONObject().put("name","base.apk").put("size",ArtifactSet.MAX_APK_BYTES).put("sha256","a".repeat(64));
            JSONObject upload=new JSONObject().put("action","begin").put("operationId","large-apk").put("space","qa").put("expectedGeneration","").put("files",new org.json.JSONArray().put(metadata));
            validator.invoke(null,upload,beginSchema,"");check("device-mcp-schema-admits-2gib-metadata",true);
            metadata.put("size",ArtifactSet.MAX_APK_BYTES+1);
            try{validator.invoke(null,upload,beginSchema,"");throw new AssertionError("oversized schema admitted");}catch(java.lang.reflect.InvocationTargetException refused){check("device-mcp-schema-refuses-over-limit",refused.getCause() instanceof IllegalArgumentException);}
            JSONObject chunk=new JSONObject().put("action","write").put("operationId","large-chunk").put("uploadId","large-apk").put("index",0).put("offset",1504L*1024*1024).put("data","AA==");
            validator.invoke(null,chunk,writeSchema,"");check("device-mcp-schema-admits-large-offset",true);
            if("true".equals(arguments.getString("imports")))for(String pkg:new String[]{"cn.wps.moffice_eng.xiaomi.lite","com.hundsun.winner.pazq","com.baidu.netdisk","com.smile.gifmaker","com.tencent.qqlive","com.pangsheng.pshuochepiao.m"}){
                android.util.Log.i("TripoderCompat","import-begin:"+pkg);
                var info=target.getPackageManager().getApplicationInfo(pkg,0);File staged=new File(scratch,pkg);if(!staged.mkdir())throw new IllegalStateException("IMPORT_STAGE");
                long size=new File(info.sourceDir).length();if(staged.getUsableSpace()<size*2+128L*1024*1024)throw new IllegalStateException("TEST_STORAGE_FULL");
                Files.copy(new File(info.sourceDir).toPath(),new File(staged,"base.apk").toPath());
                if(info.splitSourceDirs!=null)for(int i=0;i<info.splitSourceDirs.length;i++)Files.copy(new File(info.splitSourceDirs[i]).toPath(),new File(staged,"split-"+i+".apk").toPath());
                ArtifactSet set=ArtifactSet.materialize(staged);check("real-import-"+pkg,set.packageName.equals(pkg)&&ArtifactSet.verify(staged,set.descriptorSha).descriptorSha.equals(set.descriptorSha));
                var decoded=target.getPackageManager().getPackageArchiveInfo(set.base.toString(),android.content.pm.PackageManager.GET_ACTIVITIES|android.content.pm.PackageManager.GET_RECEIVERS|android.content.pm.PackageManager.GET_SERVICES|android.content.pm.PackageManager.GET_PROVIDERS|android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                check("real-component-index-"+pkg,decoded!=null&&RuntimeManifestIndex.resolve(set,decoded).size()==set.components.size());
            }
            report.put("passed",true);
        }catch(Throwable failure){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(failure));}catch(Exception ignored){}}
        try{report.put("checks",new org.json.JSONArray(checks)).put("scratch",scratch==null?JSONObject.NULL:scratch.toString()).put("businessAccepted",false);result.putString("report",report.toString());}catch(Exception ignored){}
        watcher.interrupt();finish(report.optBoolean("passed")?-1:0,result);
    }
}
