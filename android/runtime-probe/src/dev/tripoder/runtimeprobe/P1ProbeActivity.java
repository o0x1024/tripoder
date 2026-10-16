package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Real process-death and disk-reopen acceptance. Every destructive action targets own fixtures. */
public final class P1ProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray();
    private final List<Link> workers=new ArrayList<>();
    private Link authority;
    private String runId;
    private JSONObject fixtures,p1,report=new JSONObject();
    private TextView status;
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setText("Tripoder P1: running");status.setTextSize(20);setContentView(status);
        runId=getIntent().getStringExtra("runId");if(runId==null||!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(this::run,"p1-validation").start();}
    private void run(){boolean passed=false;try{
        fixtures=asset("fixtures.json");p1=asset("p1-fixtures.json");
        report.put("schema",1).put("phase","P1").put("runId",runId).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("fixtures",fixtures).put("p1Fixtures",p1);
        report.put("priorOwnedProbeTasksRemoved",ProbeTasks.prepare(this));authority=bind(P1Authority.class);Bundle first=call(request("status"));check("authority-separate-process",first.getInt("pid")!=android.os.Process.myPid(),json(first));
        Bundle coldIntegrity=call(request("probeIntegrityCache"));check("cold-metadata-recovery-does-not-open-unrelated-apks",coldIntegrity.getLong("fullVerifications")==0,json(coldIntegrity));
        check("second-writer-rejected",call(request("probeSecondWriter")).getBoolean("denied"),true);
        boolean denied=false;try(var second=new RuntimeStore(this)){second.getWritableDatabase();}catch(IllegalStateException expected){denied="AUTHORITY_ACQUIRE_FAILED".equals(expected.getMessage());}
        check("cross-process-second-writer-rejected",denied,true);
        int prior=first.getInt("pid");check("closed-writer-cannot-reopen",call(request("probeClosedWriter")).getBoolean("denied"),true);invokeCrash(request("probeCrash","operation",operation(),"crashAt","CLOSED_WRITER"),"CLOSED_WRITER");reopen(prior);
        JSONObject sourceTests=ProjectBindingsSourceTest.run(getFilesDir());check("migration-source-boundaries",sourceTests.getBoolean("passed"),sourceTests);
        JSONObject jobs=new JSONObject(call(request("probeJobInfoContract")).getString("result"));report.put("jobInfoContract",jobs);
        check("job-info-contract-no-system-submission",jobs.getBoolean("passed")&&jobs.getInt("systemJobsSubmitted")==0,jobs);
        testControllerApi();testInstallConcurrency();testArtifactCases();testInstallInterruptions();testUpgradeAndUninstall();testLiveOwnerDeath();testLostAcknowledgement();testMigrations();testCorruption();
        passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("TripoderP1","failed",error);
    }finally{
        try{for(Link worker:new ArrayList<>(workers))stopWorker(worker);if(authority!=null)unbind(authority);}catch(Exception error){passed=false;try{report.put("cleanupError",error.toString());}catch(JSONException ignored){}}
        try{report.put("passed",passed).put("checks",checks).put("finishedAt",System.currentTimeMillis()).put("productMigrated",false).put("strongIsolationVerified",false).put("genericApkExecutionVerified",false);
            File output=new File(getExternalFilesDir(null),"result-"+runId+".json");ProbeReports.publish(output,report.toString(2));
            final boolean success=passed;runOnUiThread(()->status.setText("Tripoder P1: "+(success?"PASS":"FAIL")+"\n"+checks.length()+" checks\n"+output));
        }catch(Exception error){android.util.Log.e("TripoderP1","report failed",error);}
    }}
    private void testControllerApi()throws Exception{
        String space=space("fd-api");File apk=new File(getCacheDir(),runId+"-a.apk");try(var source=getAssets().open("a.apk");var output=new FileOutputStream(apk)){source.transferTo(output);output.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle forbidden=request("status");forbidden.putParcelable("fd",fd);boolean blocked=false;try{call(forbidden);}catch(SecurityException expected){blocked=expected.getMessage().contains("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");}check("control-client-undeclared-fd-rejected",blocked,true);
            check("control-endpoint-undeclared-fd-rejected",rawRejected(forbidden,"CONTROL_FILE_DESCRIPTOR_UNSUPPORTED"),true);
        }
        Bundle install=request("install","operation",operation(),"space",space);install.putStringArrayList("names",new ArrayList<>(List.of("a.apk")));install.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getJSONObject("a").getString("sha256"))));
        int descriptorCount=call(request("probeDescriptorCount")).getInt("count");
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            for(int i=0;i<8;i++){
                Bundle malformed=new Bundle(install);malformed.putParcelableArrayList("names",new ArrayList<>(List.of(fd)));malformed.putParcelable("fd0",fd);
                check("control-endpoint-nested-fd-rejected-"+i,rawRejected(malformed,"INSTALL_FIELD_TYPE_INVALID:names"),true);
                malformed=new Bundle(install);malformed.putStringArrayList("names",new ArrayList<>(List.of("a.apk","extra.apk")));malformed.putStringArrayList("sha256",new ArrayList<>(List.of("a","b")));malformed.putString("fd0","invalid");malformed.putParcelable("fd1",fd);
                check("control-endpoint-invalid-leading-fd-rejected-"+i,rawRejected(malformed,"SOURCE_FD_REQUIRED"),true);
            }
        }
        check("control-rejected-input-descriptors-closed",call(request("probeDescriptorCount")).getInt("count")==descriptorCount,descriptorCount);
        Bundle bootstrap=new Bundle();bootstrap.putString("stub",RuntimeSlot.A.class.getName());bootstrap.putBinder("peer",new Binder());bootstrap.putString("ticket","unused");bootstrap.putString("extra","forbidden");boolean shapeRejected=false;
        try{getContentResolver().call(android.net.Uri.parse("content://"+getPackageName()+".runtime.bootstrap"),"restoreActivity",null,bootstrap);}catch(RuntimeException expected){shapeRejected=String.valueOf(expected.getMessage()).contains("BOOTSTRAP_SHAPE_UNSUPPORTED");}check("bootstrap-extra-fields-rejected",shapeRejected,true);
        String generation;try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){install.putParcelable("fd0",fd);generation=call(install).getString("generation");}
        check("controller-fd-install-committed",generation.equals(inspect(space,"dev.tripoder.p0.a").getString("generation"))&&"SUCCEEDED".equals(query(install.getString("operation")).getString("state")),generation);
        check("nonempty-space-delete-rejected",rejected(request("removeEmptySpace","space",space),"SPACE_NOT_EMPTY"),true);
        call(request("uninstall","space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"operation",operation()));call(request("removeEmptySpace","space",space));
        check("empty-space-deleted",rejected(inspectRequest(space,"dev.tripoder.p0.a"),"SPACE_NOT_FOUND"),true);
        if(!apk.delete())throw new IOException("PROBE_SOURCE_CLEANUP_FAILED");
    }
    private boolean rawRejected(Bundle input,String marker)throws Exception{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{data.writeInterfaceToken(RuntimeWire.DESCRIPTOR);data.writeBundle(input);authority.peer.transact(RuntimeWire.CONTROL,data,reply,0);try{reply.readException();return false;}catch(RuntimeException expected){return String.valueOf(expected.getMessage()).contains(marker);}}finally{data.recycle();reply.recycle();}
    }
    private void testInstallConcurrency()throws Exception{
        String running=space("concurrent-running");call(install(running,a(),null));
        for(String phase:List.of("AFTER_COPY","AFTER_PUBLISH")){
            String target=space("concurrent-"+phase),gate=UUID.randomUUID().toString();
            call(request("probeInstallGateArm","gate",gate,"phase",phase));
            Bundle request=install(target,a(),null);request.putString("gate",gate);
            FutureTask<Bundle> task=new FutureTask<>(()->call(request));new Thread(task,"install-concurrency").start();
            String process=null;
            try{
                long until=SystemClock.elapsedRealtime()+10000;Bundle state;
                do{state=call(request("probeInstallGateState","gate",gate));if(state.getBoolean("entered"))break;Thread.sleep(10);}while(SystemClock.elapsedRealtime()<until);
                check("install-real-io-phase-held-"+phase,state.getBoolean("held"),json(state));
                long started=SystemClock.elapsedRealtime();Bundle operation=query(request.getString("operation"));long elapsed=SystemClock.elapsedRealtime()-started;
                check("install-does-not-block-operation-query-"+phase,"RUNNING".equals(operation.getString("state"))&&elapsed<1000,new JSONObject().put("elapsedMs",elapsed).put("state",json(operation)));
                call(request("probeRecoverArtifacts"));Bundle inventory=call(request("probeInventory"));
                check("recovery-retains-active-install-files-"+phase,phase.equals("AFTER_COPY")?inventory.getInt("staged")==1:inventory.getInt("orphans")==1,json(inventory));
                Bundle active=call(request("ensureProcess","space",running,"package","dev.tripoder.p0.a","processName","dev.tripoder.p0.a"));process=active.getString("generation");
                check("application-register-and-ready-during-install-"+phase,"RUNNING".equals(active.getString("state"))&&call(request("probeInstallGateState","gate",gate)).getBoolean("held"),json(active));
                call(request("stopProcess","generation",process));process=null;
                check("install-gate-released-"+phase,call(request("probeInstallGateRelease","gate",gate)).getBoolean("held"),true);
                Bundle installed=task.get(30,TimeUnit.SECONDS);
                check("install-published-once-after-concurrent-callbacks-"+phase,installed.getString("generation").equals(inspect(target,"dev.tripoder.p0.a").getString("generation"))&&"SUCCEEDED".equals(query(request.getString("operation")).getString("state")),json(installed));
            }finally{
                if(process!=null)call(request("stopProcess","generation",process));
                if(!task.isDone()){call(request("probeInstallGateRelease","gate",gate));task.get(30,TimeUnit.SECONDS);}
            }
        }
    }
    private void testArtifactCases()throws Exception{
        JSONObject cases=p1.getJSONObject("cases");for(var names=cases.keys();names.hasNext();){String name=names.next();JSONObject test=cases.getJSONObject(name);String space=space("case-"+name);Bundle install=install(space,test.getJSONArray("files"),null);
            if(test.getBoolean("accepted")){Bundle result=call(install);Bundle current=inspect(space,p1.getString("packageName"));check("apk-set-"+name,current.getInt("parts")==test.getInt("parts")&&current.getString("generation").equals(result.getString("generation")),json(current));if(name.equals("resource-flags")){Bundle flags=call(request("probeManifestFlags","space",space,"package",p1.getString("packageName")));check("device-resolves-resource-flags",flags.getBoolean("rawResourceReference")&&flags.getInt("enabledCount")==1&&flags.getInt("disabledCount")==1&&flags.getInt("exportedCount")==0,json(flags));}}
            else{check("reject-"+name,rejected(install,test.getString("error")),test.getString("error"));check("rejected-set-not-installed-"+name,rejected(inspectRequest(space,p1.getString("packageName")),"PACKAGE_NOT_IN_SPACE"),true);}
        }
        cleanInventory();
    }
    private void testInstallInterruptions()throws Exception{
        for(String point:new String[]{"AFTER_ACCEPT","AFTER_COPY","AFTER_VERIFY","AFTER_PUBLISH","AFTER_METADATA","BEFORE_COMMIT","AFTER_COMMIT"}){
            String space=space("install-"+point);Bundle request=install(space,a(),null);request.putString("crashAt",point);crashAndReopen(request,point);
            Bundle operation=query(request.getString("operation"));boolean committed=point.equals("AFTER_COMMIT");check("install-state-"+point,operation.getString("state").equals(committed?"SUCCEEDED":"UNKNOWN"),json(operation));
            if(committed){Bundle installed=inspect(space,"dev.tripoder.p0.a");Bundle duplicate=call(request);check("committed-retry-query-only",installed.getString("generation").equals(duplicate.getString("generation")),json(installed));check("install-time-survives-commit-before-ack-crash",installed.getLong("firstInstallTime")>0&&installed.getLong("firstInstallTime")==installed.getLong("lastUpdateTime"),json(installed));}
            else{check("incomplete-install-not-visible-"+point,rejected(inspectRequest(space,"dev.tripoder.p0.a"),"PACKAGE_NOT_IN_SPACE"),true);check("unknown-install-not-replayed-"+point,rejected(request,"OPERATION_QUERY_REQUIRED"),true);}
            cleanInventory();
        }
    }
    private void testUpgradeAndUninstall()throws Exception{
        Set<String> channelScopes=new HashSet<>();
        String rejectSpace=space("uninstall-stale");String valid=call(install(rejectSpace,a(),null)).getString("generation");Bundle stale=request("uninstall","space",rejectSpace,"package","dev.tripoder.p0.a","expectedGeneration","wrong-generation","operation",operation());
        check("stale-uninstall-rejected",rejected(stale,"INSTALL_GENERATION_CONFLICT"),true);check("stale-uninstall-terminal-failure",query(stale.getString("operation")).getString("state").equals("FAILED")&&inspect(rejectSpace,"dev.tripoder.p0.a").getString("generation").equals(valid),true);
        for(String point:new String[]{"AFTER_PUBLISH","AFTER_METADATA","AFTER_COMMIT"}){
            String space=space("upgrade-"+point);String old=call(install(space,a(),null)).getString("generation");Bundle before=inspect(space,"dev.tripoder.p0.a");Bundle channel=call(request("probeNotificationSeed","space",space,"package","dev.tripoder.p0.a"));
            check("notification-same-logical-id-different-space-"+point,channel.getBoolean("exists")&&channelScopes.add(channel.getString("id")),channel.getString("id"));
            Bundle upgrade=install(space,new JSONArray().put(p1.getJSONObject("upgrade")),old);upgrade.putString("crashAt",point);crashAndReopen(upgrade,point);
            Bundle current=inspect(space,"dev.tripoder.p0.a");boolean committed=point.equals("AFTER_COMMIT");check("upgrade-atomic-"+point,current.getLong("version")== (committed?2:1)&&current.getString("generation").equals(old)!=committed,json(current));
            check("install-history-atomic-across-upgrade-"+point,before.getLong("firstInstallTime")>0&&current.getLong("firstInstallTime")==before.getLong("firstInstallTime")&&(committed?current.getLong("lastUpdateTime")>=before.getLong("lastUpdateTime"):current.getLong("lastUpdateTime")==before.getLong("lastUpdateTime")),json(current));
            Bundle afterChannel=call(request("probeNotificationInspect","id",channel.getString("id"),"group",channel.getString("group")));
            check("notification-upgrade-and-recovery-preserve-system-channel-"+point,afterChannel.getBoolean("exists")&&afterChannel.getBoolean("groupExists"),json(afterChannel));
            check("upgrade-stale-generation-rejected",rejected(install(space,new JSONArray().put(p1.getJSONObject("upgrade")),"stale-generation"),"INSTALL_GENERATION_CONFLICT"),true);cleanInventory();
        }
        for(String point:new String[]{"UNINSTALL_BEFORE_COMMIT","UNINSTALL_AFTER_COMMIT"}){
            String space=space("uninstall-"+point);String generation=call(install(space,a(),null)).getString("generation");Bundle channel=call(request("probeNotificationSeed","space",space,"package","dev.tripoder.p0.a"));Bundle remove=request("probeUninstall","space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"operation",operation(),"crashAt",point);crashAndReopen(remove,point);
            boolean committed=point.equals("UNINSTALL_AFTER_COMMIT");check("uninstall-state-"+point,query(remove.getString("operation")).getString("state").equals(committed?"SUCCEEDED":"UNKNOWN"),committed);
            Bundle afterChannel=call(request("probeNotificationInspect","id",channel.getString("id"),"group",channel.getString("group")));
            check("notification-uninstall-recovery-real-resource-"+point,afterChannel.getBoolean("exists")==!committed&&afterChannel.getBoolean("groupExists")==!committed,json(afterChannel));
            if(committed){check("uninstall-absent",rejected(inspectRequest(space,"dev.tripoder.p0.a"),"PACKAGE_NOT_IN_SPACE"),true);call(remove);}
            else check("uninstall-rollback-preserved",inspect(space,"dev.tripoder.p0.a").getString("generation").equals(generation),generation);cleanInventory();
        }
    }
    private void testLiveOwnerDeath()throws Exception{
        String space=space("live");String generation=call(install(space,a(),null)).getString("generation");
        Bundle capacity=call(request("probeProcessCapacity"));int available=-1;
        for(Bundle slot:capacity.getParcelableArrayList("slots",Bundle.class))if(!slot.getBoolean("allocated")&&!slot.getBoolean("activity")&&!slot.getBoolean("service")&&!slot.getBoolean("provider")&&!slot.getBoolean("broadcast")){available=slot.getInt("index");break;}
        check("owner-death-fixture-selects-real-free-slot",available>=0,json(capacity));Link worker=bind(RuntimeSlot.worker(available));workers.add(worker);
        Bundle allocate=request("probeAllocate","space",space,"package","dev.tripoder.p0.a");allocate.putInt("slot",available);allocate.putBinder("worker",worker.peer);
        Bundle loaded=RuntimeWire.call(worker.peer,RuntimeWire.LOAD,call(allocate));RuntimeWire.call(worker.peer,RuntimeWire.START_APPLICATION,new Bundle());check("real-worker-before-owner-death",loaded.getInt("pid")!=android.os.Process.myPid(),json(loaded));
        check("runtime-artifact-remains-sealed",call(request("probeArtifactEntries","space",space,"package","dev.tripoder.p0.a")).getString("entries").indexOf("oat")==-1,true);
        check("live-upgrade-rejected",rejected(install(space,new JSONArray().put(p1.getJSONObject("upgrade")),generation),"INSTALL_IN_USE"),true);
        check("live-uninstall-rejected",rejected(request("uninstall","space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"operation",operation()),"INSTALL_IN_USE"),true);
        Bundle crash=request("probeCrash","operation",operation(),"crashAt","OWNER_DIED");int before=call(request("status")).getInt("pid");
        invokeCrash(crash,"OWNER_DIED");check("guest-dies-with-authority",worker.dead.await(10,TimeUnit.SECONDS),loaded.getString("generation"));stopWorker(worker);reopen(before);
        Bundle upgraded=call(install(space,new JSONArray().put(p1.getJSONObject("upgrade")),generation));check("dead-process-releases-install",!upgraded.getString("generation").equals(generation),json(upgraded));
    }
    private void testLostAcknowledgement()throws Exception{
        Bundle effect=request("probeEffect","operation",operation(),"crashAt","AFTER_EFFECT");crashAndReopen(effect,"AFTER_EFFECT");check("crash-reopen-operation-unknown",query(effect.getString("operation")).getString("state").equals("UNKNOWN"),json(query(effect.getString("operation"))));
        Bundle duplicate=call(effect);check("crash-reopen-no-effect-replay",!duplicate.getBoolean("created")&&"effect\n".equals(duplicate.getString("content")),json(duplicate));
        call(request("probeReconcileEffect","operation",effect.getString("operation")));crashAndReopen(request("probeCrash","operation",operation(),"crashAt","AFTER_RECONCILE"),"AFTER_RECONCILE");Bundle terminal=query(effect.getString("operation"));check("reconciliation-survives-process-death",terminal.getString("state").equals("SUCCEEDED")&&terminal.getString("evidence").startsWith("file-sha256="),json(terminal));
    }
    private void testMigrations()throws Exception{
        List<String> migratedData=new ArrayList<>();for(int i=0;i<2;i++){
            String space=space("project-"+i),project="project-"+i+"-"+runId;String generation=call(install(space,a(),null)).getString("generation");
            Bundle source=request("probeMigrationSource","projectId",project,"package","dev.tripoder.p0.a","value","owned-data-"+i);String sourceSha=call(source).getString("sourceSha");
            Bundle migration=request("probeMigrate","operation",operation(),"projectId",project,"package","dev.tripoder.p0.a","space",space,"expectedGeneration",generation,"sourceSha",sourceSha);call(migration);call(migration);
            Bundle reference=call(request("probeResolveProject","projectId",project));check("migration-data-"+i,reference.getString("value").equals("owned-data-"+i),json(reference));migratedData.add(reference.getString("data"));
            call(install(space,new JSONArray().put(p1.getJSONObject("upgrade")),generation));check("upgrade-invalidates-project-"+i,rejected(request("probeResolveProject","projectId",project),"MIGRATION_REFERENCE_STALE"),true);
        }
        check("migration-projects-never-merge-data",!migratedData.get(0).equals(migratedData.get(1)),new JSONArray(migratedData));
        for(String point:new String[]{"MIGRATION_AFTER_COPY","MIGRATION_AFTER_PUBLISH","MIGRATION_BEFORE_COMMIT","MIGRATION_AFTER_COMMIT"}){
            String space=space(point),project="migration-"+UUID.randomUUID();String generation=call(install(space,a(),null)).getString("generation");String sha=call(request("probeMigrationSource","projectId",project,"package","dev.tripoder.p0.a","value",point)).getString("sourceSha");
            Bundle migration=request("probeMigrate","operation",operation(),"projectId",project,"space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"sourceSha",sha,"crashAt",point);crashAndReopen(migration,point);
            boolean committed=point.equals("MIGRATION_AFTER_COMMIT");check("migration-state-"+point,query(migration.getString("operation")).getString("state").equals(committed?"SUCCEEDED":"UNKNOWN"),committed);
            if(committed){check("migration-commit-durable",call(request("probeResolveProject","projectId",project)).getString("value").equals(point),true);call(migration);}
            else{check("migration-incomplete-unbound-"+point,rejected(request("probeResolveProject","projectId",project),"PROJECT_NOT_BOUND"),true);check("migration-unknown-not-replayed-"+point,rejected(migration,"OPERATION_QUERY_REQUIRED"),true);}
            cleanInventory();
        }
        String space=space("unbound"),project="source-only-"+runId;String sha=call(request("probeMigrationSource","projectId",project,"package","dev.tripoder.p0.a","value","not-installed")).getString("sourceSha");
        check("legacy-runnable-not-install-proof",rejected(request("probeMigrate","operation",operation(),"projectId",project,"space",space,"package","dev.tripoder.p0.a","expectedGeneration","fake-generation","sourceSha",sha),"PACKAGE_NOT_IN_SPACE"),true);
        String generation=call(install(space,a(),null)).getString("generation");call(request("probeSourceChanged","projectId",project));check("changed-source-rejected",rejected(request("probeMigrate","operation",operation(),"projectId",project,"space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"sourceSha",sha),"MIGRATION_SOURCE_DIGEST_MISMATCH"),true);
        String changedIdSha=call(request("probeSourceIdentityChanged","projectId",project)).getString("sourceSha");check("source-project-identity-mismatch-rejected",rejected(request("probeMigrate","operation",operation(),"projectId",project,"space",space,"package","dev.tripoder.p0.a","expectedGeneration",generation,"sourceSha",changedIdSha),"MIGRATION_PROJECT_ID_MISMATCH"),true);
    }
    private void testCorruption()throws Exception{
        String stable=space("cache-ctime");call(install(stable,a(),null));inspect(stable,"dev.tripoder.p0.a");Bundle cacheBefore=call(request("probeIntegrityCache"));inspect(stable,"dev.tripoder.p0.a");Bundle cacheAfter=call(request("probeIntegrityCache"));
        check("immutable-artifact-cache-hit",cacheAfter.getLong("hits")>cacheBefore.getLong("hits")&&cacheAfter.getLong("fullVerifications")==cacheBefore.getLong("fullVerifications"),json(cacheAfter));
        Bundle mutate=request("probeCorruptNative","space",stable,"package","dev.tripoder.p0.a");mutate.putBoolean("sameSize",true);Bundle changed=call(mutate);
        check("corruption-preserves-size-and-mtime",changed.getBoolean("sizeUnchanged")&&changed.getBoolean("mtimeUnchanged"),json(changed));
        check("cache-invalidates-on-ctime-change",rejected(inspectRequest(stable,"dev.tripoder.p0.a"),"ARTIFACT_FILE_CORRUPT"),true);
        String space=space("corrupt");call(install(space,a(),null));Bundle before=call(request("probeCorruptNative","space",space,"package","dev.tripoder.p0.a"));check("native-corruption-rejected",rejected(inspectRequest(space,"dev.tripoder.p0.a"),"ARTIFACT_FILE_CORRUPT"),json(before));
        crashAndReopen(request("probeCrash","operation",operation(),"crashAt","CORRUPTION_PERSISTED"),"CORRUPTION_PERSISTED");check("corrupt-install-not-repaired",rejected(inspectRequest(space,"dev.tripoder.p0.a"),"INSTALL_CORRUPT"),true);
        String cold=space("cold-corrupt");call(install(cold,a(),null));
        Bundle mutation=request("probeCorruptNative","space",cold,"package","dev.tripoder.p0.a");mutation.putBoolean("sameSize",true);call(mutation);
        crashAndReopen(request("probeCrash","operation",operation(),"crashAt","CORRUPT_BYTES_BEFORE_FIRST_COLD_USE"),"CORRUPT_BYTES_BEFORE_FIRST_COLD_USE");
        Bundle fresh=call(request("probeIntegrityCache"));check("cold-content-trust-is-not-restored-from-metadata",fresh.getLong("fullVerifications")==0&&fresh.getInt("sets")==0,json(fresh));
        check("first-cold-use-rejects-unobserved-native-corruption",rejected(inspectRequest(cold,"dev.tripoder.p0.a"),"ARTIFACT_FILE_CORRUPT"),true);
        check("cold-corrupt-install-remains-unusable",rejected(inspectRequest(cold,"dev.tripoder.p0.a"),"INSTALL_CORRUPT"),true);
    }
    private void cleanInventory()throws Exception{Bundle state=call(request("probeInventory"));check("recovery-no-unreferenced-artifacts",state.getInt("orphans")==0&&state.getInt("staged")==0,json(state));}
    private void crashAndReopen(Bundle command,String point)throws Exception{int before=call(request("status")).getInt("pid");invokeCrash(command,point);reopen(before);}
    private void invokeCrash(Bundle command,String point)throws Exception{
        boolean disconnected=false;try{call(command);}catch(RemoteException expected){disconnected=true;}
        check("actual-authority-death-"+point,disconnected&&authority.dead.await(10,TimeUnit.SECONDS)&&authority.disconnected.await(15,TimeUnit.SECONDS),point);
        JSONObject receipt=new JSONObject(Files.readString(new File(getFilesDir(),"fault-"+command.getString("operation")+".json").toPath()));check("fault-point-reached-"+point,point.equals(receipt.getString("point")),receipt);
    }
    private void reopen(int previousPid)throws Exception{Link prior=authority;authority=null;unbind(prior);authority=bind(P1Authority.class);Bundle next=call(request("status"));check("authority-pid-changed",next.getInt("pid")!=previousPid,json(next));}
    private String space(String suffix)throws Exception{String id=runId+"-"+suffix;call(request("createSpace","space",id));return id;}
    private String operation(){return "op-"+UUID.randomUUID();}
    private JSONArray a()throws Exception{return new JSONArray().put(new JSONObject().put("asset","a.apk").put("sha256",fixtures.getJSONObject("a").getString("sha256")));}
    private Bundle install(String space,JSONArray files,String expected){Bundle b=request("probeInstall","operation",operation(),"space",space,"files",files.toString());if(expected!=null)b.putString("expectedGeneration",expected);return b;}
    private Bundle inspectRequest(String space,String pkg){return request("inspect","space",space,"package",pkg);}
    private Bundle inspect(String space,String pkg)throws Exception{return call(inspectRequest(space,pkg));}
    private Bundle query(String operation)throws Exception{return call(request("query","operation",operation));}
    private Bundle call(Bundle input)throws Exception{return RuntimeWire.call(authority.peer,RuntimeWire.CONTROL,input);}
    private boolean rejected(Bundle input,String expected)throws Exception{try{call(input);return false;}catch(RuntimeException error){if(!String.valueOf(error.getMessage()).contains(expected))throw error;return true;}}
    private static Bundle request(String command,String... fields){Bundle result=new Bundle();result.putString("command",command);for(int i=0;i<fields.length;i+=2)result.putString(fields[i],fields[i+1]);return result;}
    private static JSONObject json(Bundle bundle)throws Exception{JSONObject out=new JSONObject();for(String key:bundle.keySet())out.put(key,bundle.get(key));return out;}
    private JSONObject asset(String name)throws Exception{try(var input=getAssets().open(name)){return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}}
    private void check(String name,boolean passed,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
    private Link bind(Class<?> type)throws Exception{Link link=new Link();runOnUiThread(()->{if(!(link.registered=getApplicationContext().bindService(new Intent(this,type),link,BIND_AUTO_CREATE))){link.error="BIND_REJECTED";link.bound.countDown();}});if(!link.bound.await(15,TimeUnit.SECONDS)||link.peer==null){if(link.registered)unbind(link);throw new IllegalStateException("BIND_FAILED:"+link.error);}link.peer.linkToDeath(link.dead::countDown,0);return link;}
    private void unbind(Link link)throws Exception{FutureTask<Void> job=new FutureTask<>(()->{getApplicationContext().unbindService(link);link.registered=false;return null;});runOnUiThread(job);job.get(5,TimeUnit.SECONDS);}
    private void stopWorker(Link worker)throws Exception{if(!workers.remove(worker))return;try{RuntimeWire.call(worker.peer,RuntimeWire.STOP,new Bundle());}catch(DeadObjectException ignored){}unbind(worker);if(!worker.dead.await(10,TimeUnit.SECONDS))throw new IllegalStateException("WORKER_DEATH_UNCONFIRMED");}
    private static final class Link implements ServiceConnection{
        final CountDownLatch bound=new CountDownLatch(1),dead=new CountDownLatch(1),disconnected=new CountDownLatch(1);boolean registered;IBinder peer;String error;
        @Override public void onServiceConnected(ComponentName name,IBinder value){if(peer!=null)return;peer=value;bound.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){disconnected.countDown();}
        @Override public void onNullBinding(ComponentName name){error="NULL_BINDING";bound.countDown();}
    }
}
