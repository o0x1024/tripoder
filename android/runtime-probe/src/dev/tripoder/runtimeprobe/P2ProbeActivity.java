package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Acceptance invokes production scheduling; it never binds or preloads a worker. */
public final class P2ProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray(),runs=new JSONArray();
    private final Set<String> active=new LinkedHashSet<>();
    private final BlockingQueue<JSONObject> results=new LinkedBlockingQueue<>();
    private final Map<Intent,Integer> controllerRequests=new IdentityHashMap<>();
    private final JSONArray unmatchedControllerResults=new JSONArray();private int nextControllerRequest=200;
    private final JSONObject report=new JSONObject();
    private Link authority;private TextView status;private String runId;private JSONObject fixture;
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setText("Tripoder P2: running");status.setTextSize(20);setContentView(status);
        runId=getIntent().getStringExtra("runId");if(runId==null||!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(this::run,"p2-validation").start();}
    private void run(){boolean passed=false;try{
        fixture=asset("p2-fixtures.json");report.put("schema",1).put("phase","P2-process-activity").put("runId",runId).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir)))
            .put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("fixtures",asset("fixtures.json")).put("p2Fixtures",fixture);
        report.put("priorOwnedProbeTasksRemoved",ProbeTasks.prepare(this));
        authority=bind();Bundle status=call(request("status"));check("authority-separate-process",status.getInt("pid")!=android.os.Process.myPid(),json(status));
        report.put("activityInventoryBefore",new JSONObject(ProbeReports.receive(this,call(request("probeActivityInventory")))));if(getIntent().getBooleanExtra("inventoryOnly",false)){report.put("diagnosticOnly",true);return;}
        if(getIntent().getBooleanExtra("receiverOnly",false)){report.put("scope","receiver-cold-concurrency-retirement-only");testReceivers();testReceiverRetirement();passed=true;return;}
        if(getIntent().getBooleanExtra("packageReplacedOnly",false)){report.put("scope","physical-package-replaced-worker-survival");testPackageReplaced();passed=true;return;}
        if(getIntent().getBooleanExtra("providerIndexOnly",false)){report.put("scope","cold-indexed-provider-routing-only");testProviderIndex();passed=true;return;}
        if(getIntent().getBooleanExtra("providersOnly",false)){report.put("scope","provider-routing-identity-and-unknown-effects");testProviders();testProviderIdentity();passed=true;return;}
        if(getIntent().getBooleanExtra("providerIdentityOnly",false)){report.put("scope","provider-multiprocess-identity-and-system-resources");testProviderIdentity();passed=true;return;}
        if(getIntent().getBooleanExtra("entryOnly",false)){report.put("scope","unattached-entry-fence-only");testEntryFences();passed=true;return;}
        if(getIntent().getBooleanExtra("processReuseOnly",false)){report.put("scope","closed-dead-fixture-process-reuse-preserving-activity-leases");testClosedProcessReuse();passed=true;return;}
        if(getIntent().getBooleanExtra("failureOnly",false)){report.put("scope","failure-reflection-application-contracts-only");testFailureDiagnostics();passed=true;return;}
        if(getIntent().getBooleanExtra("packageOnly",false)){report.put("scope","application-only-package-world-contract");testPackageWorld();passed=true;return;}
        if(getIntent().getBooleanExtra("sourcesOnly",false)){report.put("scope","real-activity-source-identity");testActivitySources();passed=true;return;}
        if(getIntent().getBooleanExtra("creationOnly",false)){report.put("scope","real-instrumentation-activity-construction");testInstrumentationCreation();passed=true;return;}
        if(getIntent().getBooleanExtra("jobsOnly",false)){report.put("scope","real-job-callbacks-and-worker-recovery");testJobs();passed=true;return;}
        if(getIntent().getBooleanExtra("pendingOnly",false)){report.put("scope","real-pending-intent-and-alarm");testPendingIntents();passed=true;return;}
        if(getIntent().getBooleanExtra("serviceStartupOnly",false)){report.put("scope","real-service-request-during-application-attach");testServiceStartup();passed=true;return;}
        if(getIntent().getBooleanExtra("servicePassiveOnly",false)){report.put("scope","non-creating-service-binding-and-later-start");testServicePassive();passed=true;return;}
        if(getIntent().getBooleanExtra("activityFlagsOnly",false)){report.put("scope","standard-activity-single-top-intent-flag");testActivityFlags();passed=true;return;}
        if(getIntent().getBooleanExtra("notificationOnly",false)){
            report.put("scope","production-notification-cancellation-on-real-system-records");JSONObject result=new JSONObject(call(request("probeNotificationCancellation")).getString("result"));runs.put(result);
            JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}passed=result.getBoolean("passed");return;
        }
        testTaskVisibility();testClosingReceipts();
        String s1=space("first"),s2=space("second");String install1=install(s1),install2=install(s2);
        check("install-generations-distinct",!install1.equals(install2),new JSONArray().put(install1).put(install2));
        check("undeclared-process-rejected",rejected(request("ensureProcess","space",s1,"package","dev.tripoder.p2","processName","dev.tripoder.p2:absent"),"PROCESS_NOT_DECLARED"),true);
        Bundle main=ensureConcurrently(s1);Bundle same=ensure(s1,"dev.tripoder.p2");
        check("ensure-preserves-live-identity",main.getString("generation").equals(same.getString("generation"))&&main.getInt("pid")==same.getInt("pid"),json(same));
        check("live-install-pinned",rejected(request("uninstall","space",s1,"package","dev.tripoder.p2","expectedGeneration",install1,"operation",UUID.randomUUID().toString()),"INSTALL_IN_USE"),true);
        JSONObject first=launch(s1);verify(first,1);Bundle remote=ensure(s1,"dev.tripoder.p2:remote");
        check("manifest-process-matches-real-pid",first.getInt("pid")==main.getInt("pid")&&first.getJSONObject("child").getInt("pid")==remote.getInt("pid"),new JSONArray().put(json(main)).put(json(remote)));
        check("same-package-processes-share-data",first.getString("data").equals(first.getJSONObject("child").getString("data")),first.getString("data"));
        JSONObject second=launch(s2);verify(second,1);ensure(s2,"dev.tripoder.p2");ensure(s2,"dev.tripoder.p2:remote");
        check("spaces-have-distinct-data-and-pids",!first.getString("data").equals(second.getString("data"))&&first.getInt("pid")!=second.getInt("pid"),new JSONArray().put(first).put(second));
        stop(main.getString("generation"));stop(remote.getString("generation"));
        Bundle restarted=ensure(s1,"dev.tripoder.p2");check("restart-new-process-generation",!main.getString("generation").equals(restarted.getString("generation"))&&main.getInt("pid")!=restarted.getInt("pid"),json(restarted));
        JSONObject third=launch(s1);verify(third,2);ensure(s1,"dev.tripoder.p2:remote");check("restart-retains-data",first.getString("data").equals(third.getString("data")),third.getString("data"));
        Bundle capacity=call(request("probeProcessCapacity"));int available=capacity.getInt("available"),expected=capacity.getInt("allocated")+available;
        check("capacity-includes-retained-resource-pins",capacity.getInt("total")==8&&capacity.getInt("allocated")==active.size()&&expected+capacity.getInt("retained")==8,json(capacity));
        for(int i=0;i<available;i++){String extra=space("capacity-"+i);install(extra);ensure(extra,"dev.tripoder.p2");}
        String overflow=space("overflow");install(overflow);check("pool-capacity-fails-closed",rejected(request("ensureProcess","space",overflow,"package","dev.tripoder.p2","processName","dev.tripoder.p2"),"PROCESS_CAPACITY_EXHAUSTED"),active.size());
        JSONArray sessions=new JSONArray(call(request("probeSessions","runId",runId)).getString("sessions"));int live=0;Set<Integer> pids=new HashSet<>();
        for(int i=0;i<sessions.length();i++){JSONObject row=sessions.getJSONObject(i);if(!active.contains(row.getString("generation")))continue;live++;pids.add(row.getInt("pid"));check("durable-session-identity-"+live,row.getString("processName").startsWith("dev.tripoder.p2")&&row.getString("activityStub").startsWith("dev.tripoder.runtime.RuntimeSlot$")&&!row.getString("authorityGeneration").isEmpty()&&row.getString("state").equals("RUNNING"),row);}
        check("all-available-slots-have-distinct-supervised-pids",live==expected&&pids.size()==expected,new JSONObject().put("pids",new JSONArray(pids)).put("capacityBeforeFill",json(capacity)));
        for(String generation:new ArrayList<>(active))stop(generation);testTaskModes();testSingleInstance();testReparent();testActivitySources();testResultFlows();testAliases();testActivityChurn();testSingleTop();testSingleTopDeath();testServices();testProviders();testProviderIdentity();testReceivers();testReceiverRetirement();testColdActivity();passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("TripoderP2","failed",error);
    }finally{
        try{if(authority!=null)report.put("activityInventoryAfter",new JSONObject(ProbeReports.receive(this,call(request("probeActivityInventory")))));}catch(Exception error){try{report.put("inventoryError",String.valueOf(error));}catch(Exception ignored){}}
        try{for(String generation:new ArrayList<>(active))stop(generation);if(authority!=null){FutureTask<Void> unbind=new FutureTask<>(()->{getApplicationContext().unbindService(authority);return null;});runOnUiThread(unbind);unbind.get(5,TimeUnit.SECONDS);}}
        catch(Exception error){passed=false;try{report.put("cleanupError",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        try{boolean anr=false;JSONArray exits=new JSONArray();for(var exit:getSystemService(android.app.ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,32))if(exit.getTimestamp()>=report.optLong("startedAt",Long.MAX_VALUE)){exits.put(new JSONObject().put("pid",exit.getPid()).put("reason",exit.getReason()).put("process",exit.getProcessName()).put("timestamp",exit.getTimestamp()));if(exit.getReason()==android.app.ApplicationExitInfo.REASON_ANR)anr=true;}report.put("ownedProcessExits",exits).put("unexpectedAnrObserved",anr);if(anr)passed=false;}catch(Exception error){passed=false;try{report.put("exitEvidenceError",error.toString());}catch(Exception ignored){}}
        try{report.put("unmatchedControllerResults",unmatchedControllerResults).put("passed",passed).put("checks",checks).put("runs",runs).put("finishedAt",System.currentTimeMillis()).put("fullP2Accepted",false).put("genericApkExecutionVerified",false).put("strongIsolationVerified",false);
            File output=new File(getExternalFilesDir(null),"result-"+runId+".json");ProbeReports.publish(output,report.toString(2));
            final boolean success=passed;runOnUiThread(()->status.setText("Tripoder P2 process/activity: "+(success?"PASS":"FAIL")+"\n"+checks.length()+" checks\n"+output));
        }catch(Exception error){android.util.Log.e("TripoderP2","report failed",error);}
    }}
    private Bundle entry(String action,String space,String...pairs)throws Exception{
        Bundle input=request("probeEntryFence","action",action,"space",space);for(int i=0;i<pairs.length;i+=2)input.putString(pairs[i],pairs[i+1]);return call(input);
    }
    private void testClosedProcessReuse()throws Exception{
        JSONObject plan=new JSONObject(new String(Base64.getUrlDecoder().decode(getIntent().getStringExtra("closurePlan")),StandardCharsets.UTF_8));JSONArray cases=plan.getJSONArray("cases");String target=plan.getString("targetSpace");
        if(cases.length()!=2||!target.matches("provider-identity-[a-f0-9]{16}-provider-identity"))throw new IllegalArgumentException("FIXTURE_CLOSE_PLAN_INVALID");report.put("closurePlan",plan);
        String[] processes={"dev.tripoder.provideridentity:peer","dev.tripoder.provideridentity:provider"};
        for(int i=0;i<cases.length();i++){
            JSONObject item=cases.getJSONObject(i);String old=item.getString("generation"),space=item.getString("space");
            Bundle inspect=request("probeClosedFixture","action","inspect","generation",old,"space",space);Bundle before=call(inspect);
            boolean alreadyClosed=item.optBoolean("alreadyClosed",false);
            check("old-fixture-exact-closure-state-"+i,before.getBoolean("closed")==alreadyClosed&&before.getBoolean("processPinned")==!alreadyClosed&&before.getBoolean("componentRetained"),json(before));
            if(alreadyClosed)check("prior-closure-history-receipt-matches-"+i,ApkStore.textDigest(before.getString("snapshot")).equals(item.getString("snapshotSha256")),true);
            Bundle close=request("probeClosedFixture","action","close","generation",old,"space",space);Bundle closed=call(close);
            check("closed-dead-entry-keeps-history-and-component-"+i,closed.getBoolean("closed")&&closed.getBoolean("entryRejected")&&!closed.getBoolean("processPinned")&&closed.getBoolean("componentRetained")&&before.getString("snapshot").equals(closed.getString("snapshot")),json(closed));
            Intent late=closed.getParcelable("intent",Intent.class);launchOwned(late);JSONObject cold=resultFor(late,15);
            Bundle afterCold=call(inspect);check("old-cold-entry-cancels-without-guest-"+i,cold!=null&&cold.optInt("invalidResult",999)==RESULT_CANCELED&&afterCold.getInt("rejected")>=1&&afterCold.getInt("applicationStarts")==before.getInt("applicationStarts"),json(afterCold));
            Bundle next=call(request("ensureProcess","space",target,"package","dev.tripoder.provideridentity","processName",processes[i]));active.add(next.getString("generation"));
            Bundle successor=request("probeClosedFixture","action","successor","generation",old,"space",space,"successor",next.getString("generation"));Bundle mapped=call(successor);
            check("distinct-install-reuses-only-process-slot-"+i,mapped.getInt("successorPid")==next.getInt("pid")&&mapped.getBoolean("componentRetained"),json(mapped));
            check("retained-old-owner-cannot-steal-live-successor-slot-"+i,rejected(request("ensureProcess","space",space,"package","dev.tripoder.provideridentity","processName","dev.tripoder.provideridentity"),"PROCESS_CAPACITY_EXHAUSTED"),true);
            Intent hot=new Intent(late);launchOwned(hot);JSONObject canceled=resultFor(hot,15);Bundle afterHot=call(successor);
            check("late-old-hot-entry-keeps-successor-alive-"+i,canceled!=null&&canceled.optInt("invalidResult",999)==RESULT_CANCELED&&afterHot.getInt("successorPid")==next.getInt("pid")&&afterHot.getInt("rejected")>=2&&afterHot.getInt("applicationStarts")==before.getInt("applicationStarts")&&before.getString("snapshot").equals(afterHot.getString("snapshot")),json(afterHot));
        }
    }
    private void testFailureDiagnostics()throws Exception{
        String space=space("failure"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerIdentity")).toString()));
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".FailureDispatchActivity"));active.add(prepared.getString("generation"));
        Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);JSONObject result=resultFor(intent,30);if(result==null)throw new IllegalStateException("FAILURE_CONTRACT_TIMEOUT_UNKNOWN");runs.put(result);
        call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("failure-diagnostics-fixture-completed",result.optBoolean("completed")&&result.optBoolean("passed")&&result.getInt("pid")==prepared.getInt("pid"),result);stop(prepared.getString("generation"));
        String ownership=space("application-ownership");install(ownership);Bundle app=ensure(ownership,"dev.tripoder.p2");
        check("application-native-reflection-view-ownership-and-access","RUNNING".equals(app.getString("state")),json(app));stop(app.getString("generation"));
    }
    private void testJobs()throws Exception{
        String space=space("jobs"),pkg="dev.tripoder.jobsfixture";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("jobs")).toString()));
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".JobsActivity"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=null;Bundle evidence=null;boolean killed=false;JSONArray samples=new JSONArray();long sampled=0;String signature="";long deadline=SystemClock.elapsedRealtime()+200000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle read=call(request("probeJobsReport","generation",prepared.getString("generation")));evidence=read;
            if(read.containsKey("jobGeneration"))active.add(read.getString("jobGeneration"));
            if(read.containsKey("system")){
                JSONObject sample=new JSONObject(read.getString("system"));String current=sample.getBoolean("pending")+":"+sample.getJSONArray("reasons")+":"+sample.getBoolean("retryRpcReturned")+":"+killed;
                long now=SystemClock.elapsedRealtime();if(!current.equals(signature)||now-sampled>=1000){sample.put("workerTerminationRequested",killed);samples.put(sample);sampled=now;signature=current;report.put("jobSystemSamples",samples);ProbeReports.checkpoint(new File(getExternalFilesDir(null),"job-system-"+runId+".json"),new JSONObject().put("runId",runId).put("samples",samples).toString());}
            }
            if(!killed&&read.getBoolean("pendingRetryConfirmed")&&read.containsKey("recoveryPid")&&read.getInt("recoveryPid")==read.getInt("jobPid")){stop(read.getString("jobGeneration"));killed=true;}
            if(read.containsKey("result")){result=new JSONObject(read.getString("result"));break;}Thread.sleep(100);
        }
        report.put("jobEvidence",evidence==null?JSONObject.NULL:json(evidence));if(result==null)throw new IllegalStateException("JOB_EXECUTION_REPORT_TIMEOUT");runs.put(result);
        for(int i=0;i<result.getJSONArray("checks").length();i++){JSONObject item=result.getJSONArray("checks").getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("job-fixture-completed",result.getBoolean("passed")&&result.getBoolean("completed"),result);
        JSONArray events=new JSONArray(evidence.getString("events"));boolean finish=false,stopped=false;Set<String> recovery=new HashSet<>();
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(e.getInt("jobId")==42&&e.getString("event").equals("FINISH_RPC_RETURNED"))finish=true;if(e.getInt("jobId")==43&&e.getString("event").equals("STOP_RPC_RETURNED"))stopped=true;if(e.getInt("jobId")==44&&e.getString("event").equals("START_ENTERED"))recovery.add(e.getString("generation"));}
        check("finish-and-stop-have-real-callback-rpc-receipts",finish&&stopped,events);check("cold-recovery-has-distinct-durable-attempt-generations",killed&&recovery.size()==2,events);
        call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
    }
    private void testPendingIntents()throws Exception{
        String pkg="dev.tripoder.pendingfixture";
        report.put("pendingCapacityBefore",json(call(request("probeProcessCapacity"))));
        String cleanupSpace=getIntent().getStringExtra("pendingCleanupSpace");if(cleanupSpace!=null)report.put("pendingPriorFixtureCleanup",json(call(request("probePending","space",cleanupSpace,"action","cleanup"))));
        for(int target:new int[]{30,31}){
            String space=space("pending"+target);try{call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("pending"+target)).toString()));
            Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".PendingActivity"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
            JSONObject result=null;Bundle observed=null;long deadline=SystemClock.elapsedRealtime()+150000;
            while(SystemClock.elapsedRealtime()<deadline){observed=call(request("probePending","space",space,"action","read"));if(observed.containsKey("receiverGeneration"))active.add(observed.getString("receiverGeneration"));if(observed.containsKey("result")){result=new JSONObject(observed.getString("result"));break;}Thread.sleep(100);}
            if(result==null)throw new IllegalStateException("PENDING_CONTRACT_TIMEOUT");runs.put(result);
            for(int i=0;i<result.getJSONArray("checks").length();i++){JSONObject item=result.getJSONArray("checks").getJSONObject(i);check("target"+target+":"+item.getString("name"),item.getBoolean("passed"),item);}
            check("pending-fixture-completed-target"+target,result.optBoolean("passed")&&result.getInt("targetSdk")==target,result);
            check("pending-token-does-not-reserve-process-slot-target"+target,observed.getBoolean("tokensReserveNoProcess"),true);
            call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));int creatorPid=result.getInt("pid"),receiverPid=observed.getInt("receiverPid");
            stop(prepared.getString("generation"));stop(observed.getString("receiverGeneration"));
            long started=SystemClock.elapsedRealtime();Bundle fired=call(request("probePending","space",space,"action","fireAfterDeath"));JSONObject cold=null;deadline=started+30000;
            while(SystemClock.elapsedRealtime()<deadline){observed=call(request("probePending","space",space,"action","read"));if(observed.containsKey("receiverGeneration"))active.add(observed.getString("receiverGeneration"));if(observed.containsKey("cold")){cold=new JSONObject(observed.getString("cold"));break;}Thread.sleep(50);}
            check("pending-token-survives-creator-and-receiver-death-target"+target,cold!=null&&cold.getInt("pid")!=creatorPid&&cold.getInt("pid")!=receiverPid&&"durable".equals(cold.getString("value")),cold);
            check("cold-pending-real-system-ordered-completion-target"+target,cold.getBoolean("ordered")&&fired.getInt("systemResultCode")==77&&"owned-result".equals(fired.getString("systemResultData")),json(fired));
            JSONArray history=cold.getJSONArray("history"),physical=new JSONArray(observed.getString("physicalExitHistory"));Set<Integer> exitPids=new HashSet<>();boolean names=true,exact=true;
            for(int i=0;i<history.length();i++){JSONObject exit=history.getJSONObject(i);exitPids.add(exit.getInt("pid"));names&=exit.getBoolean("summaryHidden")&&(exit.getString("process").equals(pkg)||exit.getString("process").equals(pkg+":delivery"));boolean matched=false;
                for(int j=0;j<physical.length();j++){JSONObject raw=physical.getJSONObject(j);if(raw.getInt("pid")==exit.getInt("pid")&&raw.getLong("timestamp")==exit.getLong("timestamp"))matched=raw.getInt("uid")==exit.getInt("uid")&&raw.getInt("reason")==exit.getInt("reason")&&raw.getInt("status")==exit.getInt("status")&&raw.getInt("summaryBytes")==68;}exact&=matched;}
            check("exit-history-is-current-install-only-target"+target,exitPids.equals(Set.of(creatorPid,receiverPid))&&history.length()==2&&names,history);
            check("exit-history-retains-real-system-fields-target"+target,exact&&physical.length()==2,physical);
            check("exit-history-query-boundaries-target"+target,cold.getInt("nullPackageHistoryCount")==2&&cold.getBoolean("pidFiltered")&&cold.getBoolean("otherPackageRejected")&&cold.getBoolean("negativeLimitRejected")&&cold.getBoolean("ownershipStampProtected"),cold);
            check("cold-pending-delivery-within-10-second-budget-target"+target,SystemClock.elapsedRealtime()-started<10000,new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-started));
            check("first-cold-pending-delivery-within-10-second-budget-target"+target,result.getLong("coldSendToReceiverMs")<10000,result.getLong("coldSendToReceiverMs"));
            stop(observed.getString("receiverGeneration"));
            }finally{for(String generation:new ArrayList<>(active))stop(generation);call(request("probePending","space",space,"action","cleanup"));}
        }
    }
    private void testServiceStartup()throws Exception{
        String space=space("service-startup"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("serviceStartup")).toString()));
        Bundle prepared=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg));active.add(prepared.getString("generation"));
        JSONObject result=null;long deadline=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle read=call(request("probeServiceStartupReport","generation",prepared.getString("generation")));if(read.containsKey("result")){result=new JSONObject(read.getString("result"));break;}Thread.sleep(50);}
        if(result==null)throw new IllegalStateException("SERVICE_STARTUP_RESULT_UNKNOWN");runs.put(result);
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("service-startup-fixture-completed",result.optBoolean("completed")&&result.optBoolean("passed")&&result.getInt("pid")==prepared.getInt("pid"),result);
        check("non-auto-create-binding-accepted",result.getString("bindWithoutCreate").equals("accepted"),result);
        Bundle remote=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg+":startup"));active.add(remote.getString("generation"));check("service-created-through-android-owns-distinct-runtime-process",remote.getInt("pid")>0&&remote.getInt("pid")!=prepared.getInt("pid"),json(remote));
        stop(remote.getString("generation"));stop(prepared.getString("generation"));
    }
    private void testServicePassive()throws Exception{
        String space=space("service-passive"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("servicePassive")).toString()));
        Bundle prepared=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg));active.add(prepared.getString("generation"));
        Bundle gate=null;long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){gate=call(request("probePassiveGate","generation",prepared.getString("generation")));if(gate.getBoolean("approved"))break;Thread.sleep(50);}
        check("passive-binding-reserves-component-without-session-or-real-process",gate!=null&&gate.getBoolean("approved"),json(gate));
        check("canceled-passive-owner-retires-without-starting-target",gate.getBoolean("canceledOwnerRetired"),json(gate));
        JSONObject result=null;deadline=SystemClock.elapsedRealtime()+40000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle read=call(request("probePassiveReport","generation",prepared.getString("generation")));if(read.containsKey("result")){result=new JSONObject(read.getString("result"));break;}Thread.sleep(50);}
        if(result==null)throw new IllegalStateException("PASSIVE_RESULT_UNKNOWN");runs.put(result);
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("passive-binding-fixture-completed",result.optBoolean("completed")&&result.optBoolean("passed")&&result.getInt("pid")==prepared.getInt("pid"),result);
        Bundle remote=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg+":passive"));active.add(remote.getString("generation"));
        stop(remote.getString("generation"));stop(prepared.getString("generation"));
    }
    private void testActivityFlags()throws Exception{
        JSONObject values=new JSONObject(call(request("probeActivityPayload")).getString("result"));runs.put(values);JSONArray valueChecks=values.getJSONArray("checks");for(int i=0;i<valueChecks.length();i++){JSONObject item=valueChecks.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        String space=space("activity-flags"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerIdentity")).toString()));
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".FlagTopActivity"));String generation=prepared.getString("generation");active.add(generation);
        Intent intent=prepared.getParcelable("intent",Intent.class);runOnUiThread(()->startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        JSONObject result=null;long deadline=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle read=call(request("probeActivityFlagsReport","generation",generation));if(read.containsKey("result")){result=new JSONObject(read.getString("result"));break;}Thread.sleep(50);}
        if(result==null)throw new IllegalStateException("ACTIVITY_FLAGS_RESULT_UNKNOWN");runs.put(result);
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("activity-flags-fixture-completed",result.optBoolean("completed")&&result.optBoolean("passed")&&result.getInt("pid")==prepared.getInt("pid"),result);
        JSONObject destroyed=awaitTicket(space,intent.getStringExtra("tripoder.ticket"),"FINISHED",true);check("root-real-onDestroy-acknowledged",destroyed.getInt("destroyedConfirmed")==1,destroyed);
        Bundle receipts=call(request("probeActivityFlagsReport","generation",generation));check("denied-send-permissions-never-admit-physical-deliveries",receipts.getInt("broadcasts")==2&&receipts.getInt("completedBroadcasts")==2,json(receipts));
        check("sticky-callback-has-real-completed-delivery-receipt",receipts.getInt("completedStickyCallbacks")>=1,json(receipts));
        JSONArray deliveries=new JSONArray(receipts.getString("deliveries"));int creates=0,reused=0;
        for(int i=0;i<deliveries.length();i++){JSONObject row=deliveries.getJSONObject(i);check("delivery-callback-receipt-"+i,row.getString("state").equals("RECEIVED")&&(row.isNull("execution")||row.getString("execution").equals("SUCCEEDED")),row);if(row.getString("kind").equals("CREATE"))creates++;else if(row.getString("kind").equals("NEW_INTENT"))reused++;}
        check("ledger-preserves-real-instance-selection",deliveries.length()==7&&creates==4&&reused==3,deliveries);stop(generation);
    }
    private void testInstrumentationCreation()throws Exception{
        JSONObject values=new JSONObject(call(request("probeActivityPayload")).getString("result"));runs.put(values);JSONArray valueChecks=values.getJSONArray("checks");for(int i=0;i<valueChecks.length();i++){JSONObject item=valueChecks.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        String space=space("instrumentation"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("instrumentationWorld")).toString()));
        for(String mode:new String[]{"direct","super","super-replace"}){
            Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".InstrumentationActivity"));active.add(prepared.getString("generation"));
            Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("mode",mode);launchOwned(intent);
            JSONObject result=resultFor(intent,25);if(result==null)throw new IllegalStateException("INSTRUMENTATION_CREATION_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
            check("instrumentation-final-object-device-identity-"+mode,result.getInt("pid")==prepared.getInt("pid")&&result.getInt("uid")==android.os.Process.myUid(),result);
            JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(mode+":"+item.getString("name"),item.getBoolean("passed"),item);}
            check("instrumentation-fixture-completed-"+mode,result.optBoolean("completed")&&result.optBoolean("passed")&&mode.equals(result.getString("mode")),result);
        }
        for(String generation:new ArrayList<>(active))stop(generation);
    }
    private void testPackageWorld()throws Exception{
        String space=space("package-world"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("packageWorld")).toString()));
        Bundle trafficBefore=call(request("probeTrafficSnapshot"));
        Bundle prepared=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg));active.add(prepared.getString("generation"));
        JSONObject result=new JSONObject(call(request("probePackageWorldReport","generation",prepared.getString("generation"))).getString("result"));runs.put(result);
        if(result.optBoolean("passed")){
            Bundle trafficAfter=call(request("probeTrafficSnapshot"));JSONObject traffic=result.getJSONObject("networkObservations");
            check("traffic-guest-observation-bracketed-by-unadapted-platform-uid",traffic.getInt("physicalUid")==trafficBefore.getInt("uid")&&traffic.getLong("rxBytes")>=trafficBefore.getLong("rx")&&traffic.getLong("rxBytes")<=trafficAfter.getLong("rx")&&traffic.getLong("txBytes")>=trafficBefore.getLong("tx")&&traffic.getLong("txBytes")<=trafficAfter.getLong("tx"),new JSONObject().put("before",json(trafficBefore)).put("guest",traffic).put("after",json(trafficAfter)));
        }
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("package-world-application-contract-completed",result.optBoolean("completed")&&result.optBoolean("passed")&&result.getInt("pid")==prepared.getInt("pid"),result);stop(prepared.getString("generation"));
    }
    private Bundle awaitEntry(String space,String operation,int rejections,Integer intents)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle state=entry("state",space,"launchOperation",operation);if(state.getInt("rejected")>=rejections&&(intents==null||state.containsKey("fixture")&&new JSONObject(state.getString("fixture")).getInt("intents")==intents))return state;Thread.sleep(30);}
        throw new IllegalStateException("ENTRY_FIXTURE_EVENT_TIMEOUT");
    }

    private void testEntryFences()throws Exception{
        String space=space("entry-fence");install(space);
        for(String mode:List.of("Standard","Top")){
            entry("reset",space);String component="dev.tripoder.p2.EntryFenceActivity$"+mode;ensure(space,"dev.tripoder.p2");
            Bundle old=entry("prepare",space,"component",component);active.add(old.getString("generation"));Intent oldIntent=old.getParcelable("intent",Intent.class);
            String prior=old.getString("operation"),close=UUID.randomUUID().toString();
            check("entry-close-live-generation-rejected-"+mode,rejected(request("probeEntryFence","action","close","space",space,"launchOperation",prior,"operation",close),"LAUNCH_ENTRY_OUTCOME_NOT_UNKNOWN"),true);
            stop(old.getString("generation"));Bundle before=entry("state",space,"launchOperation",prior);
            check("entry-dead-before-dispatch-remains-unknown-and-pinned-"+mode,"UNKNOWN".equals(before.getString("outcome"))&&before.getInt("taskId")==-1&&"PREPARED".equals(before.getString("ticketState"))&&before.getBoolean("installPinned")&&!before.getBoolean("closed"),json(before));
            entry("close",space,"launchOperation",prior,"operation",close);entry("close",space,"launchOperation",prior,"operation",close);
            Bundle fenced=entry("state",space,"launchOperation",prior);
            check("entry-close-is-idempotent-without-retiring-resource-"+mode,fenced.getBoolean("closed")&&fenced.getBoolean("installPinned")&&"UNKNOWN".equals(fenced.getString("outcome"))&&fenced.getInt("taskId")==-1&&before.getString("componentLease").equals(fenced.getString("componentLease")),json(fenced));
            if(mode.equals("Standard")){
                String previous=call(request("status")).getString("authority");try{call(request("probeCrash","operation",UUID.randomUUID().toString(),"crashAt","ENTRY_FENCE_RESTART"));throw new IllegalStateException("AUTHORITY_DID_NOT_EXIT");}catch(android.os.RemoteException expected){}
                Link oldAuthority=authority;FutureTask<Void> unbind=new FutureTask<>(()->{getApplicationContext().unbindService(oldAuthority);return null;});runOnUiThread(unbind);unbind.get(5,TimeUnit.SECONDS);authority=bind();
                Bundle recovered=entry("state",space,"launchOperation",prior);check("entry-fence-survives-authority-death",!previous.equals(call(request("status")).getString("authority"))&&recovered.getBoolean("closed")&&"UNKNOWN".equals(recovered.getString("outcome")),json(recovered));
            }
            launchOwned(oldIntent);JSONObject canceled=results.poll(15,TimeUnit.SECONDS);Bundle cold=awaitEntry(space,prior,1,null);
            check("entry-cold-late-factory-does-not-create-guest-"+mode,canceled!=null&&canceled.optInt("invalidResult",999)==RESULT_CANCELED&&!cold.getBoolean("workerAllocated")&&cold.getInt("starts")==before.getInt("starts")&&"factory".equals(cold.getString("rejectionKind")),json(cold));
            ensure(space,"dev.tripoder.p2");Bundle next=entry("prepare",space,"component",component);active.add(next.getString("generation"));Intent nextIntent=next.getParcelable("intent",Intent.class);launchOwned(nextIntent);Bundle live=awaitEntry(space,prior,1,0),nextState=awaitEntryOutcome(space,next.getString("operation"),"SUCCEEDED");
            check("entry-new-explicit-launch-reuses-immutable-lease-"+mode,!old.getString("generation").equals(next.getString("generation"))&&before.getString("componentLease").equals(nextState.getString("componentLease"))&&before.getString("processStub").equals(nextState.getString("processStub"))&&"SUCCEEDED".equals(nextState.getString("outcome")),json(nextState));
            if(mode.equals("Standard"))launchOwned(oldIntent);else runOnUiThread(()->startActivity(oldIntent));
            Bundle hot=awaitEntry(space,prior,2,0);
            check("entry-hot-late-delivery-does-not-enter-or-kill-successor-"+mode,hot.getInt("pid")==next.getInt("pid")&&hot.getInt("starts")==live.getInt("starts")&&"UNKNOWN".equals(hot.getString("outcome"))&&(mode.equals("Standard")?"factory":"new-intent").equals(hot.getString("rejectionKind")),json(hot));
            if(mode.equals("Top")){entry("normalIntent",space);Bundle normal=awaitEntry(space,prior,2,1);check("entry-valid-new-intent-still-reaches-guest",normal.getInt("pid")==next.getInt("pid"),json(normal));}
            entry("finish",space);JSONObject result=results.poll(15,TimeUnit.SECONDS);
            // Android delivers results to the caller when it resumes, after its live child exits.
            if(mode.equals("Standard")){JSONObject second=results.poll(15,TimeUnit.SECONDS),canceledHot=result!=null&&result.has("invalidResult")?result:second;if(canceledHot==result)result=second;check("entry-hot-factory-returns-canceled",canceledHot!=null&&canceledHot.optInt("invalidResult",999)==RESULT_CANCELED,canceledHot);}
            check("entry-successor-finishes-normally-"+mode,result!=null&&result.optInt("pid")==next.getInt("pid"),result);call(request("completeActivity","ticket",nextIntent.getStringExtra("tripoder.ticket")));stop(next.getString("generation"));runs.put(result);
        }
    }
    private Bundle awaitEntryOutcome(String space,String operation,String expected)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){Bundle state=entry("state",space,"launchOperation",operation);if(expected.equals(state.getString("outcome")))return state;Thread.sleep(30);}
        throw new IllegalStateException("ENTRY_FIXTURE_OUTCOME_TIMEOUT:"+expected);
    }
    private void testClosingReceipts()throws Exception{
        for(boolean permanent:new boolean[]{false,true}){
            String space=space("closing-"+permanent);install(space);
            Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ClosingActivity"));
            String generation=prepared.getString("generation");active.add(generation);
            Intent intent=prepared.getParcelable("intent",Intent.class);String ticket=intent.getStringExtra("tripoder.ticket");
            launchOwned(intent);
            JSONObject live=awaitTicket(space,ticket,"LIVE",false);
            Bundle fenced=call(request("probeCloseActivityGate","generation",generation,"permanent",Boolean.toString(permanent)));
            JSONObject result=resultFor(intent,15);if(result==null)throw new IllegalStateException("CLOSING_RECEIPT_TIMEOUT");
            check("closing-rejects-new-work-"+permanent,result.optBoolean("newAdmissionRejected")&&result.getInt("pid")==fenced.getInt("pid"),result);
            JSONObject finished=awaitTicket(space,ticket,"FINISHED",true);call(request("completeActivity","ticket",ticket));
            Bundle state=call(request("probeProcessState","generation",generation));
            check("closing-accepts-exact-live-instance-destruction-"+permanent,live.getString("instance").equals(finished.getString("instance"))&&state.getString("state").equals("RUNNING")&&state.getInt("pid")==result.getInt("pid"),finished);
            stop(generation);runs.put(result);
        }
    }
    private JSONObject awaitTicket(String space,String ticket,String state,boolean destroyed)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline){
            JSONArray rows=new JSONArray(call(request("probeActivityTickets","space",space)).getString("tickets"));
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("ticket").equals(ticket)&&row.getString("state").equals(state)&&(!destroyed||row.getInt("destroyedConfirmed")==1))return row;}
            Thread.sleep(25);
        }throw new IllegalStateException("ACTIVITY_TICKET_STATE_TIMEOUT:"+state);
    }
    private void testTaskVisibility()throws Exception{
        int[] ids=new int[2];
        try{
            for(int index=0;index<2;index++){final int value=index;runOnUiThread(()->startActivity(new Intent(this,TaskVisibilityActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS).putExtra("index",value)));
                long deadline=SystemClock.elapsedRealtime()+10000;while(!TaskVisibilityActivity.instances.containsKey(index)){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("TASK_OBSERVATION_FIXTURE_CREATE_TIMEOUT");Thread.sleep(30);}ids[index]=TaskVisibilityActivity.instances.get(index).getTaskId();Thread.sleep(250);
            }
            Bundle seen=call(request("probeTaskVisibility"));boolean recent=false,running=false;for(int id:seen.getIntArray("recent"))recent|=id==ids[0];for(int id:seen.getIntArray("running"))running|=id==ids[0];
            check("hidden-owned-task-running-observation-complements-recents",ids[0]!=ids[1]&&!recent&&running&&TaskVisibilityActivity.instances.containsKey(0),new JSONObject().put("first",ids[0]).put("second",ids[1]).put("recent",new JSONArray(seen.getIntArray("recent"))).put("running",new JSONArray(seen.getIntArray("running"))));
        }finally{runOnUiThread(()->{for(var task:new ArrayList<>(TaskVisibilityActivity.instances.values()))task.finishAndRemoveTask();});}
        long deadline=SystemClock.elapsedRealtime()+10000;while(!TaskVisibilityActivity.instances.isEmpty()){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("TASK_OBSERVATION_FIXTURE_DESTROY_TIMEOUT");Thread.sleep(30);}
        Bundle gone=call(request("probeTaskVisibility"));boolean retained=false;for(String key:new String[]{"recent","running"})for(int id:gone.getIntArray(key))retained|=id==ids[0]||id==ids[1];
        check("explicit-owned-task-finish-removes-both-observations",!retained,new JSONObject().put("recent",new JSONArray(gone.getIntArray("recent"))).put("running",new JSONArray(gone.getIntArray("running"))));
    }
    private void verify(JSONObject node,int starts)throws Exception{
        String challenge=node.getString("challenge");int main=node.getInt("pid"),remote=node.getJSONObject("child").getInt("pid");
        for(String name:new String[]{"Main","Remote","Middle","Leaf"}){
            check("real-activity-"+name,node.getString("component").equals("dev.tripoder.p2/dev.tripoder.p2.Chain$"+name)&&node.getString("package").equals("dev.tripoder.p2")&&node.getBoolean("loader")&&node.getInt("starts")==starts&&node.getString("data").equals(node.getString("applicationData"))&&node.getString("challenge").equals(challenge),node);
            String expectedCaller=name.equals("Main")?getComponentName().flattenToString():"dev.tripoder.p2/dev.tripoder.p2.Chain$"+(name.equals("Remote")?"Main":name.equals("Middle")?"Remote":"Middle");
            check("activity-logical-result-owner-"+name,node.getString("callingActivity").equals(expectedCaller)&&node.getString("callingPackage").equals(name.equals("Main")?getPackageName():"dev.tripoder.p2"),node.getString("callingActivity"));
            if(!name.equals("Leaf"))check("activity-result-component-caller-"+name,node.getString("resultCaller").equals("dev.tripoder.p2"),node.getString("resultCaller"));
            check("manifest-process-"+name,node.getString("process").equals(name.equals("Main")?"dev.tripoder.p2":"dev.tripoder.p2:remote")&&node.getInt("pid")== (name.equals("Main")?main:remote),node.getString("process"));
            check("declared-component-factory-"+name,node.getInt("factoryApplication")==1&&node.getInt("factoryLoader")==1&&node.getInt("factoryActivities")>0&&(!name.equals("Leaf")||node.getString("implementation").equals("dev.tripoder.p2.Factory$ReplacedLeaf")),node.getString("implementation"));
            if(!name.equals("Leaf"))node=node.getJSONObject("child");
        }
        check("main-and-remote-are-different-pids",main!=remote,new JSONArray().put(main).put(remote));
    }
    private void testServices()throws Exception{
        String space=space("services");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Services$Main"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,150);if(result==null)throw new IllegalStateException("SERVICE_ACCEPTANCE_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item.opt("observed"));}check("service-fixture-completed",result.optBoolean("passed"),result);
        Bundle remote=ensure(space,"dev.tripoder.p2:services");stop(remote.getString("generation"));stop(prepared.getString("generation"));
    }
    private void testAliases()throws Exception{
        String space=space("aliases");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Aliases$Parent"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("ALIAS_ACCEPTANCE_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        check("disabled-alias-rejected-before-start",result.getBoolean("disabledRejected"),result);JSONArray children=result.getJSONArray("runs");int childPid=children.getJSONObject(0).getInt("pid");
        for(int i=0;i<3;i++){JSONObject child=children.getJSONObject(i);String expected=i==1?"dev.tripoder.p2.Aliases$Collision":"dev.tripoder.p2.EntryAlias";check("alias-target-class-logical-name-and-process-"+i,child.getString("class").equals("dev.tripoder.p2.Aliases$Target")&&child.getString("component").equals(expected)&&child.getString("intent").equals(expected)&&child.getString("process").equals("dev.tripoder.p2:aliasremote")&&child.getInt("pid")==childPid&&childPid!=result.getInt("pid"),child);}
        JSONArray tickets=new JSONArray(call(request("probeActivityTickets","space",space)).getString("tickets"));Map<String,String> byName=new HashMap<>();Set<String> physical=new HashSet<>();boolean consistent=true;
        for(int i=0;i<tickets.length();i++){JSONObject ticket=tickets.getJSONObject(i);String component=ticket.getString("component"),stub=ticket.getString("stub");String prior=byName.put(component,stub);consistent&=prior==null||prior.equals(stub);physical.add(stub);}
        check("activity-components-have-independent-stable-physical-identities",consistent&&tickets.length()==4&&byName.size()==3&&physical.size()==3,tickets);
        ensure(space,"dev.tripoder.p2:aliasremote");for(String generation:new ArrayList<>(active))stop(generation);
    }
    private void testActivityChurn()throws Exception{
        String space=space("churn");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ActivityChurn$Parent"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,60);if(result==null)throw new IllegalStateException("ACTIVITY_CHURN_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        check("activity-churn-24-components-same-live-process",result.getJSONArray("children").length()==24&&result.getInt("destroyed")==24&&result.getInt("creates")==25&&result.getInt("recreates")==1&&result.getJSONArray("children").getJSONObject(5).getBoolean("restored"),result);
        check("activity-hooks-survive-concurrent-art-gc",result.getLong("gcCollections")>=24,result);
        JSONArray tickets=new JSONArray(call(request("probeActivityTickets","space",space)).getString("tickets"));Set<String> stubs=new HashSet<>(),leases=new HashSet<>();boolean confirmed=true;int children=0;
        for(int i=0;i<tickets.length();i++){JSONObject row=tickets.getJSONObject(i);if(row.getString("component").contains("$C")){children++;stubs.add(row.getString("stub"));leases.add(row.getString("componentLease"));confirmed&=row.getString("state").equals("FINISHED")&&row.getInt("destroyedConfirmed")==1&&row.getInt("taskId")==result.getInt("task");}}
        check("activity-churn-recycles-only-confirmed-component-leases",confirmed&&children==24&&leases.size()==24&&stubs.size()<16,tickets);
        for(String generation:new ArrayList<>(active))stop(generation);
    }
    private void testSingleInstance()throws Exception{
        String space=space("single-instance");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.SingleInstanceTasks$Coordinator"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("SINGLE_INSTANCE_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        check("single-instance-alias-reuses-root",result.getInt("creates")==1&&result.getInt("deliveries")==1,result);
        check("single-instance-launches-child-into-other-task",result.getInt("rootTask")!=result.getInt("childTask")&&result.getInt("rootTask")!=result.getInt("coordinatorTask")&&result.getInt("childDestroyed")==1,result);
        JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));int reused=0;for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);if("NEW_INTENT".equals(launch.optString("kind"))){reused++;check("single-instance-delivery-received",launch.getString("state").equals("RECEIVED")&&launch.getString("execution").equals("SUCCEEDED"),launch);check("task-to-front-can-also-deliver-new-intent",launch.getInt("startResult")==2,launch);}}check("single-instance-one-real-delivery",reused==1,launches);
        for(String generation:new ArrayList<>(active))stop(generation);
        report.put("singleInstanceCompletedTasksDismissed",ProbeTasks.dismissCompleted(this,result.getInt("rootTask"),result.getInt("childTask")));
    }
    private void testSingleTop()throws Exception{
        for(String variant:new String[]{"Root","Double"}){
            String space=space("top-"+variant);install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.SingleTop$"+variant));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
            JSONObject result=resultFor(intent,45);if(result==null)throw new IllegalStateException("SINGLE_TOP_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
            check("singleTop-real-instance-and-one-delivery-"+variant,result.getBoolean("passed")&&result.getInt("creates")==2&&result.getInt("oneCallbacks")==2&&result.getInt("twoCallbacks")== (variant.equals("Double")?2:0)&&result.getInt("siblings")==1&&result.getBoolean("sameTask")&&result.getBoolean("nonTopNewInstance"),result);
            for(String field:new String[]{"oldIntentPreservedFirst","oldIntentPreservedSecond","savedCallerStable","oneArgSetIntentClearsCaller","twoArgSetIntentStoresCaller","outsideCurrentCallerRejected"})check("singleTop-"+variant+"-"+field,result.getBoolean(field),result);
            JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));int creates=0,reused=0;boolean receipts=launches.length()==5;
            for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);receipts&=launch.getString("state").equals("RECEIVED")&&(launch.isNull("operation")||launch.getString("execution").equals("SUCCEEDED"));if(launch.getString("kind").equals("CREATE"))creates++;else if(launch.getString("kind").equals("NEW_INTENT")){reused++;receipts&=!launch.getString("candidate").equals(launch.getString("receiver"));}}
            check("singleTop-"+variant+"-durable-instance-and-launch-receipts",receipts&&creates==3&&reused==2,launches);
            for(String generation:new ArrayList<>(active))stop(generation);
        }
    }
    private void testSingleTopDeath()throws Exception{
        String space=space("top-death");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.SingleTop$CrashAfterWrite"));String generation=prepared.getString("generation");active.add(generation);Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,45);if(result==null)throw new IllegalStateException("NEW_INTENT_DEATH_RESULT_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("newIntent-process-death-effect-written-once","x".equals(call(request("probeActivityFaultData","space",space)).getString("effect")),result);
        JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));boolean unknown=launches.length()==2;int failed=0;for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);if("NEW_INTENT".equals(launch.optString("kind"))){failed++;unknown&=launch.getString("state").equals("UNKNOWN")&&launch.getString("execution").equals("UNKNOWN");}}
        check("newIntent-dead-callback-remains-unknown-no-replay",unknown&&failed==1,launches);
        JSONArray sessions=new JSONArray(call(request("probeSessions","runId",runId)).getString("sessions"));boolean originalDead=false;for(int i=0;i<sessions.length();i++){JSONObject session=sessions.getJSONObject(i);if(space.equals(session.optString("space"))){if(generation.equals(session.getString("generation")))originalDead=session.getString("state").equals("DEAD");if(!session.getString("state").equals("DEAD"))stop(session.getString("generation"));}}active.remove(generation);
        check("newIntent-original-process-confirmed-dead",originalDead,generation);
        check("newIntent-unknown-effect-protects-install",rejected(request("probeProviderInstallUnused","space",space,"package","dev.tripoder.p2"),"INSTALL_IN_USE_BY_ACTIVITY_DELIVERY"),true);
    }
    private void testPackageReplaced()throws Exception{
        String space=space("package-replaced");install(space);Bundle worker=ensure(space,"dev.tripoder.p2");
        Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.PackageBroadcastActivity"));
        Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);JSONObject result=resultFor(intent,30);if(result==null)throw new IllegalStateException("PACKAGE_REPLACED_RESULT_TIMEOUT");runs.put(result);
        call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("real-activity-thread-handles-empty-and-owned-package-replacement",result.optBoolean("passed")&&result.optBoolean("emptyAndOwnedHandled"),result);
        check("package-replacement-runs-in-existing-worker",result.getInt("workerPid")==worker.getInt("pid")&&result.getString("generation").equals(worker.getString("generation")),result);
        Bundle same=ensure(space,"dev.tripoder.p2");check("package-replacement-keeps-session-generation",same.getString("generation").equals(worker.getString("generation"))&&same.getInt("pid")==worker.getInt("pid"),json(same));
        report.put("packageReplacement",result);
    }
    private void testProviderIndex()throws Exception{
        JSONObject index=new JSONObject(call(request("probeInstallProviderIndex")).getString("result"));
        JSONArray indexChecks=index.getJSONArray("checks");for(int i=0;i<indexChecks.length();i++){JSONObject item=indexChecks.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        JSONObject prior=null;
        for(int round=0;round<2;round++){
            String space=space("providers-index-"+round);install(space);
            for(String name:new String[]{"providerPeer","providerIdentity"})call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject(name)).toString()));
            Bundle before=call(request("probeUnrelatedProviderInstall","space",space));
            Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ProviderLookupActivity"));active.add(prepared.getString("generation"));
            Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);JSONObject result=resultFor(intent,60);if(result==null)throw new IllegalStateException("PROVIDER_INDEX_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
            JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name")+"-space-"+round,item.getBoolean("passed"),item);}
            check("indexed-routing-completes-with-unrelated-corrupt-install-"+round,result.getBoolean("passed"),result);
            Bundle after=call(request("probeProviderIntegrityStats"));long verified=after.getLong("fullVerifications")-before.getLong("fullVerifications");
            check("cold-lookup-verifies-only-two-selected-apks-"+round,verified==2,new JSONObject().put("fullVerifications",verified).put("lookupElapsedMs",result.getLong("lookupElapsedMs")));
            if(prior!=null)check("indexed-authority-isolated-between-spaces",!prior.getString("data").equals(result.getString("data"))&&prior.getInt("remotePid")!=result.getInt("remotePid"),result);prior=result;
            ensure(space,"dev.tripoder.p2:providerremote");Bundle peer=call(request("ensureProcess","space",space,"package","dev.tripoder.peer","processName","dev.tripoder.peer"));active.add(peer.getString("generation"));
            for(String generation:new ArrayList<>(active))stop(generation);
        }
        check("indexed-lookup-never-enters-host-provider",ProviderDecoy.hits.get()==0,ProviderDecoy.hits.get());
    }
    private void testProviders()throws Exception{
        JSONObject index=new JSONObject(call(request("probeInstallProviderIndex")).getString("result"));
        JSONArray indexChecks=index.getJSONArray("checks");for(int i=0;i<indexChecks.length();i++){JSONObject item=indexChecks.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        JSONObject prior=null;
        for(int round=0;round<2;round++){
            String space=space("providers-"+round);install(space);call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerIdentity")).toString()));call(request("probeUnrelatedProviderInstall","space",space));call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerPeer")).toString()));Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Providers$Main"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
            JSONObject result=resultFor(intent,90);if(result==null)throw new IllegalStateException("PROVIDER_ACCEPTANCE_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
            JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name")+"-space-"+round,item.getBoolean("passed"),item.opt("observed"));}check("provider-fixture-completed-"+round,result.optBoolean("passed"),result);
            check("provider-routing-ignores-unrelated-corrupt-install-"+round,result.optBoolean("passed"),true);
            ensure(space,"dev.tripoder.p2:providerremote");
            ensure(space,"dev.tripoder.p2:providercyclea");ensure(space,"dev.tripoder.p2:providercycleb");
            Bundle peer=call(request("ensureProcess","space",space,"package","dev.tripoder.peer","processName","dev.tripoder.peer"));active.add(peer.getString("generation"));
            Bundle references=call(request("probeProviderState","space",space));JSONArray unknown=new JSONArray(references.getString("unknown"));boolean expected=unknown.length()==3;Set<String> failureKinds=new HashSet<>();for(int i=0;i<unknown.length();i++){JSONObject failure=unknown.getJSONObject(i);expected&=failure.getString("execution").equals("UNKNOWN")&&failure.getString("delivery").equals("UNKNOWN");failureKinds.add(failure.getString("kind"));}expected&=failureKinds.equals(Set.of("insert","applyBatch"));check("provider-references-released-and-uncertain-effects-retained-"+round,references.getInt("OPEN")==0&&references.getInt("CLOSED")>10&&references.getInt("unresolved")==3&&references.getInt("journalMismatch")==0&&expected,json(references));
            check("provider-callback-results-received-by-source-"+round,references.getInt("deliveryUnconfirmed")==0,json(references));
            if(prior!=null)check("same-authority-spaces-distinct-data-and-process",!prior.getString("data").equals(result.getString("data"))&&prior.getInt("remotePid")!=result.getInt("remotePid"),new JSONArray().put(prior).put(result));prior=result;
            for(String generation:new ArrayList<>(active))stop(generation);
            check("provider-unknown-effect-keeps-install-protected-"+round,rejected(request("probeProviderInstallUnused","space",space,"package","dev.tripoder.p2"),"INSTALL_IN_USE_BY_PROVIDER"),true);
        }
        check("host-same-authority-provider-never-invoked",ProviderDecoy.hits.get()==0,ProviderDecoy.hits.get());
        for(String generation:new ArrayList<>(active))stop(generation);
    }
    private void testProviderIdentity()throws Exception{
        String space=space("provider-identity"),pkg="dev.tripoder.provideridentity";
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerIdentity")).toString()));
        call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerPeer")).toString()));
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".IdentityActivity"));active.add(prepared.getString("generation"));
        Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("PROVIDER_IDENTITY_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("provider-identity-executes-in-engine-worker",result.getInt("pid")==prepared.getInt("pid")&&result.getInt("uid")==android.os.Process.myUid(),result);
        JSONObject first=result.getJSONObject("first"),repeat=result.getJSONObject("repeatBeforeOther"),second=result.getJSONObject("second"),after=result.getJSONObject("firstAgain");
        check("repeated-provider-class-initializes-once",first.getBoolean("returned")&&repeat.getBoolean("returned")&&after.getBoolean("returned")&&first.getInt("creations")==1&&after.getInt("creations")==1&&first.getString("instance").equals(repeat.getString("instance"))&&first.getString("instance").equals(after.getString("instance")),result);
        check("provider-publishes-first-component-authority",first.getString("attachedAuthority").equals("dev.tripoder.identity.first")&&first.getString("authorityArgument").equals("dev.tripoder.identity.first")&&first.getString("callingPackage").equals(pkg),first);
        check("provider-rejects-unpublished-authority-as-security-exception",!second.getBoolean("returned")&&second.getString("errorClass").equals("java.lang.SecurityException")&&second.getString("error").contains("PROVIDER_AUTHORITY_NOT_PUBLISHED"),second);
        check("provider-rejection-never-enters-business-callback",first.getInt("calls")==1&&repeat.getInt("calls")==2&&after.getInt("calls")==3,after);
        check("provider-attach-metadata-matches-platform",first.getString("label").equals("missing"),first);
        check("provider-private-delegate-neither-republished-nor-host-capability",first.getInt("delegateCreates")==1&&first.getString("delegatePackage").equals(pkg)&&!first.getBoolean("delegateAppOpsAvailableToGuest"),first);
        check("provider-component-and-authority-metadata-distinct",result.getString("componentLabel").equals("first")&&result.getString("firstResolvedLabel").equals("first")&&result.getString("secondResolvedLabel").equals("second"),result);
        check("provider-pm-metadata-is-defensive-and-flag-bound",result.getBoolean("metadataCopyIsolated")&&result.getBoolean("metadataOmittedWithoutFlag"),result);
        Bundle ledger=call(request("probeProviderState","space",space));check("provider-pre-dispatch-rejection-does-not-create-unknown-effect",ledger.getInt("unresolved")==0&&ledger.getInt("OPEN")==0&&ledger.getInt("deliveryUnconfirmed")==0&&ledger.getInt("journalMismatch")==0,json(ledger));
        testMultiprocessProvider(space,pkg);
        testPackageResources(space,pkg);
        Bundle permissions=call(request("probeBroadcastState","space",space));check("exported-receiver-missing-permission-never-admitted",permissions.getInt("peerDeliveries")==1,json(permissions));
        stop(prepared.getString("generation"));awaitCapacity(active.size());
    }
    private void testPackageResources(String space,String pkg)throws Exception{
        JSONObject startFailure=rejection(request("ensureProcess","space",space,"package",pkg,"processName",pkg+":historyfail"),"OWNED_APPLICATION_START_FAILURE");
        check("application-start-failure-is-explicit",startFailure.getBoolean("matched"),startFailure);awaitCapacity(active.size());
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".PackageResourcesActivity"));active.add(prepared.getString("generation"));
        String reportNonce=UUID.randomUUID().toString();Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("reportNonce",reportNonce);launchOwned(intent);
        long peerDeadline=SystemClock.elapsedRealtime()+30000L;
        while(SystemClock.elapsedRealtime()<peerDeadline){
            Bundle permissions=call(request("probeBroadcastState","space",space)),providerState=call(request("probeProviderState","space",space));
            if(permissions.getInt("peerDeliveries")==1&&permissions.getInt("unresolved")==0&&providerState.getInt("OPEN")==0&&providerState.getInt("unresolved")==0)break;
            Thread.sleep(50);
        }
        Bundle peer=call(request("ensureProcess","space",space,"package","dev.tripoder.peer","processName","dev.tripoder.peer"));active.add(peer.getString("generation"));stop(peer.getString("generation"));awaitCapacity(active.size());
        String otherSpace=space("process-query-other");call(request("probeInstall","space",otherSpace,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture.getJSONObject("providerIdentity")).toString()));
        Bundle other=call(request("ensureProcess","space",otherSpace,"package",pkg,"processName",pkg));active.add(other.getString("generation"));
        Bundle sameSpacePeer=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg+":peer"));active.add(sameSpacePeer.getString("generation"));
        // Real minute event (70s), network (10s), signal/cancel (20s), private (10s), concurrency (35s).
        JSONObject receipt=resultFor(intent,160);if(receipt==null)throw new IllegalStateException("PACKAGE_RESOURCES_TIMEOUT_UNKNOWN");check("package-resources-result-binds-request",!receipt.has("invalidResult")&&receipt.optInt("pid")==prepared.getInt("pid")&&reportNonce.equals(receipt.optString("reportNonce")),receipt);
        Bundle read=request("probePackageResourcesReport","generation",prepared.getString("generation"),"reportNonce",reportNonce,"reportSha256",receipt.getString("reportSha256"));read.putLong("reportBytes",receipt.getLong("reportBytes"));
        JSONObject result=new JSONObject(ProbeReports.receive(this,call(read)));runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("package-resources-fixture-in-engine-worker",result.getInt("pid")==prepared.getInt("pid")&&result.getInt("uid")==android.os.Process.myUid(),result);
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item);}
        check("package-resources-fixture-completed",result.optBoolean("passed")&&result.optBoolean("completed"),result);
        JSONArray observed=result.getJSONObject("systemServices").getJSONArray("processes");Set<Integer> pids=new HashSet<>();for(int i=0;i<observed.length();i++)pids.add(observed.getJSONObject(i).getInt("pid"));
        check("process-view-includes-live-own-peer-and-excludes-same-package-other-space",pids.equals(Set.of(prepared.getInt("pid"),sameSpacePeer.getInt("pid")))&&!pids.contains(other.getInt("pid")),observed);
        JSONArray history=result.getJSONObject("systemServices").getJSONArray("startHistory");Set<Integer> historyPids=new HashSet<>();boolean failed=false;
        for(int i=0;i<history.length();i++){JSONObject item=history.getJSONObject(i);historyPids.add(item.getInt("pid"));if(item.getString("name").equals(pkg+":historyfail")&&item.getInt("state")==android.app.ApplicationStartInfo.STARTUP_STATE_ERROR)failed=true;}
        check("application-start-history-includes-peer-and-excludes-other-space",historyPids.containsAll(pids)&&!historyPids.contains(other.getInt("pid")),history);
        check("application-start-history-retains-observed-failure",failed,history);
        stop(sameSpacePeer.getString("generation"));stop(other.getString("generation"));awaitCapacity(active.size());
    }
    private void testMultiprocessProvider(String space,String pkg)throws Exception{
        Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",pkg+".MultiprocessActivity"));active.add(prepared.getString("generation"));
        Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("MULTIPROCESS_PROVIDER_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("multiprocess-provider-fixture-completed",result.optBoolean("completed"),result);
        for(String key:new String[]{"main","peer"}){
            JSONObject sample=result.getJSONObject(key),first=sample.getJSONObject("first"),again=sample.getJSONObject("again");
            check("multiprocess-provider-lazy-local-once-"+key,sample.getInt("before")==0&&sample.getInt("after")==1&&first.getInt("pid")==sample.getInt("pid")&&first.getInt("creations")==1&&first.getString("instance").equals(again.getString("instance"))&&first.getString("callingPackage").equals(pkg),sample);
        }
        JSONObject main=result.getJSONObject("main"),peer=result.getJSONObject("peer"),after=result.getJSONObject("mainAfterPeer");
        check("multiprocess-provider-independent-real-processes",main.getInt("pid")==prepared.getInt("pid")&&main.getInt("pid")!=peer.getInt("pid")&&!main.getJSONObject("first").getString("instance").equals(peer.getJSONObject("first").getString("instance")),result);
        check("multiprocess-provider-later-acquire-keeps-instance",after.getInt("before")==1&&main.getJSONObject("first").getString("instance").equals(after.getJSONObject("first").getString("instance")),after);
        Bundle ledger=call(request("probeProviderState","space",space));check("multiprocess-provider-all-calls-and-references-confirmed",ledger.getInt("unresolved")==0&&ledger.getInt("OPEN")==0&&ledger.getInt("deliveryUnconfirmed")==0&&ledger.getInt("journalMismatch")==0,json(ledger));
        Bundle remote=call(request("ensureProcess","space",space,"package",pkg,"processName",pkg+":peer"));active.add(remote.getString("generation"));check("multiprocess-provider-uses-existing-service-process",remote.getInt("pid")==peer.getInt("pid"),json(remote));stop(remote.getString("generation"));
    }
    private void testReceivers()throws Exception{
        java.util.concurrent.atomic.AtomicInteger decoyHits=new java.util.concurrent.atomic.AtomicInteger();BroadcastReceiver decoy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){decoyHits.incrementAndGet();}};
        registerReceiver(decoy,new IntentFilter("dev.tripoder.p2.RECEIVER_TEST"),Context.RECEIVER_NOT_EXPORTED);
        try{
            for(int round=0;round<2;round++){
                String space=space("receivers-"+round);install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Receivers$Main"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);
                JSONArray sessions=new JSONArray(call(request("probeSessions","runId",runId)).getString("sessions"));boolean preheated=false;for(int i=0;i<sessions.length();i++){JSONObject session=sessions.getJSONObject(i);if(space.equals(session.optString("space"))&&"dev.tripoder.p2:receivers".equals(session.optString("processName")))preheated=true;}
                check("receiver-target-not-prestarted-"+round,!preheated,sessions.length());
                Bundle raced=null;
                if(round==1){
                    call(request("probeHoldMain"));ExecutorService client=Executors.newSingleThreadExecutor();
                    try{
                        Future<Bundle> worker=client.submit(()->call(request("ensureProcess","space",space,"package","dev.tripoder.p2","processName","dev.tripoder.p2:receivers")));
                        JSONObject allocated=null;long deadline=SystemClock.elapsedRealtime()+2000;
                        while(allocated==null&&SystemClock.elapsedRealtime()<deadline){JSONArray rows=new JSONArray(call(request("probeSessions","runId",runId)).getString("sessions"));for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(space.equals(row.optString("space"))&&"dev.tripoder.p2:receivers".equals(row.optString("processName")))allocated=row;}if(allocated==null)Thread.sleep(10);}
                        check("receiver-race-worker-reserved-before-component",allocated!=null&&allocated.getString("state").equals("ALLOCATED")&&allocated.getInt("pid")==-1,allocated);
                        launchOwned(intent);raced=worker.get(7,TimeUnit.SECONDS);active.add(raced.getString("generation"));
                        check("receiver-race-shares-reserved-generation",raced.getString("generation").equals(allocated.getString("generation"))&&raced.getString("state").equals("RUNNING"),json(raced));
                    }finally{Bundle gate=call(request("probeReleaseMain"));client.shutdownNow();check("receiver-race-completes-before-worker-bind-released",gate.getBoolean("held"),json(gate));}
                }else launchOwned(intent);
                JSONObject result=resultFor(intent,80);if(result==null)throw new IllegalStateException("RECEIVER_ACCEPTANCE_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
                JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name")+"-space-"+round,item.getBoolean("passed"),item.opt("observed"));}check("receiver-fixture-completed-"+round,result.optBoolean("passed"),result);
                Bundle remote=ensure(space,"dev.tripoder.p2:receivers");check("receiver-cold-session-reused-by-supervisor-"+round,remote.getInt("pid")==result.getInt("coldPid"),json(remote));
                if(raced!=null)check("receiver-race-one-process",raced.getInt("pid")==result.getInt("coldPid")&&raced.getString("generation").equals(remote.getString("generation")),json(raced));
                Bundle ledger=call(request("probeBroadcastState","space",space));check("receiver-deliveries-complete-and-registrations-closed-"+round,ledger.getInt("completed")==3&&ledger.getInt("unresolved")==0&&ledger.getInt("registrations")==0&&ledger.getInt("journalMismatch")==0,json(ledger));
            }
            check("host-same-action-receiver-never-invoked",decoyHits.get()==0,decoyHits.get());
        }finally{unregisterReceiver(decoy);for(String generation:new ArrayList<>(active))stop(generation);}
    }
    private void testReceiverRetirement()throws Exception{
        String oldSpace=space("late-receiver"),newSpace=space("late-successor");install(oldSpace);install(newSpace);
        Bundle source=ensure(oldSpace,"dev.tripoder.p2"),target=ensure(oldSpace,"dev.tripoder.p2:receivers");
        int targetIndex=processIndex(target.getString("generation")),sourceIndex=processIndex(source.getString("generation"));
        Bundle prepared=call(request("prepareActivity","space",oldSpace,"package","dev.tripoder.p2","component","dev.tripoder.p2.LateReceiverActivity"));
        Intent entry=prepared.getParcelable("intent",Intent.class);call(request("probeReceiverGateArm","generation",source.getString("generation")));
        launchOwned(entry);
        JSONObject held=null;long deadline=SystemClock.elapsedRealtime()+10000;
        while(held==null&&SystemClock.elapsedRealtime()<deadline){Bundle b=call(request("probeReceiverGateHeld","generation",source.getString("generation")));if(b.containsKey("held"))held=new JSONObject(b.getString("held"));else Thread.sleep(25);}
        if(held==null)throw new IllegalStateException("RECEIVER_HOLD_NOT_OBSERVED");String ticket=held.getString("ticket");
        JSONObject before=receiverDelivery(ticket);check("late-receiver-held-before-real-platform-delivery",before.getString("state").equals("PREPARED")&&before.getString("target").equals(target.getString("generation")),before);
        stop(target.getString("generation"));JSONObject retired=receiverDelivery(ticket);
        check("dead-receiver-resource-retired-with-unknown-effect",retired.getString("processState").equals("DEAD")&&retired.getInt("resourceRetired")==1&&retired.getString("state").equals("UNKNOWN")&&retired.getString("execution").equals("UNKNOWN"),retired);
        Bundle successor=ensure(newSpace,"dev.tripoder.p2");check("retired-broadcast-slot-reused-by-different-space",processIndex(successor.getString("generation"))==targetIndex, json(successor));
        Bundle warmBefore=inspectReceiver(targetIndex,null,false),wrong=inspectReceiver(sourceIndex,ticket,false),unknown=inspectReceiver(targetIndex,"0".repeat(64),false),tampered=inspectReceiver(targetIndex,ticket,true);
        check("late-receiver-wrong-physical-slot-rejected",wrong.getBoolean("rejected")&&wrong.getString("reason").contains("BROADCAST_ENTRY_SLOT_MISMATCH"),json(wrong));
        check("late-receiver-unknown-ticket-rejected",unknown.getBoolean("rejected")&&unknown.getString("reason").contains("BROADCAST_TICKET_UNKNOWN"),json(unknown));
        check("late-receiver-tampered-envelope-rejected",tampered.getBoolean("rejected")&&tampered.getString("reason").contains("BROADCAST_ENVELOPE_INVALID"),json(tampered));
        call(request("probeReceiverGateRelease","generation",source.getString("generation")));JSONObject result=resultFor(entry,15);if(result==null)throw new IllegalStateException("LATE_RECEIVER_FINAL_RESULT_TIMEOUT");
        call(request("completeActivity","ticket",entry.getStringExtra("tripoder.ticket")));runs.put(result);
        JSONObject after=receiverDelivery(ticket);Bundle warmAfter=inspectReceiver(targetIndex,null,false);
        check("late-ordered-delivery-real-source-observes-platform-completion",result.getInt("code")==19&&result.getString("data").equals("untouched-initial")&&result.getBoolean("extrasAbsent")&&result.getInt("sourcePid")==source.getInt("pid")&&after.getInt("systemDone")==1,after);
        check("late-platform-completion-does-not-confirm-guest-effect",after.getString("state").equals("UNKNOWN")&&after.getString("execution").equals("UNKNOWN")&&after.getInt("returned")==0&&after.getInt("finished")==0,after);
        check("late-receiver-does-not-enter-successor-guest",warmAfter.getInt("pid")==successor.getInt("pid")&&warmBefore.getInt("pid")==warmAfter.getInt("pid")&&warmAfter.getString("generation").equals(successor.getString("generation"))&&warmAfter.getInt("applications")==1&&warmBefore.getInt("receivers")==0&&warmAfter.getInt("receivers")==0,json(warmAfter));
        stop(successor.getString("generation"));stop(source.getString("generation"));
        String priorStarts=call(request("probeStartHistory","space",oldSpace)).getString("starts");
        JSONArray stoppedStarts=new JSONArray(priorStarts);check("application-start-history-retains-real-dead-generations",stoppedStarts.length()==2,stoppedStarts);
        String priorAuthority=call(request("status")).getString("authority");
        try{call(request("probeCrash","operation",runId+"-receiver-restart","crashAt","RECEIVER_RESTART"));throw new IllegalStateException("AUTHORITY_DID_NOT_EXIT");}catch(android.os.RemoteException expected){}
        Link old=authority;FutureTask<Void> unbind=new FutureTask<>(()->{getApplicationContext().unbindService(old);return null;});runOnUiThread(unbind);unbind.get(5,TimeUnit.SECONDS);authority=bind();
        check("receiver-retirement-survives-authority-restart",!priorAuthority.equals(call(request("status")).getString("authority"))&&receiverDelivery(ticket).getInt("resourceRetired")==1,receiverDelivery(ticket));
        check("application-start-history-survives-authority-restart-without-new-times",priorStarts.equals(call(request("probeStartHistory","space",oldSpace)).getString("starts")),stoppedStarts);
        Intent late=new Intent().setPackage(getPackageName()).setComponent(new ComponentName(this,held.getString("stub"))).putExtra("tripoder.delivery",ticket);
        Bundle coldResult=platformBroadcast(late),cold=inspectReceiver(targetIndex,null,false);
        check("retired-cold-delivery-does-not-construct-runtime",coldResult.getInt("code")==23&&"physical-initial".equals(coldResult.getString("data"))&&!cold.getBoolean("engine"),json(cold));
        check("retired-cold-replay-preserves-unknown-operation",receiverDelivery(ticket).getString("execution").equals("UNKNOWN"),receiverDelivery(ticket));
        String completedTicket=call(request("probeCompletedReceiverTicket","space",runId+"-receivers-1")).getString("ticket");JSONObject completed=receiverDelivery(completedTicket);
        String processStub=completed.getString("activityStub");int completedIndex="ABCDEFGH".indexOf(processStub.charAt(processStub.length()-1));
        Intent duplicate=new Intent().setPackage(getPackageName()).setComponent(new ComponentName(this,"dev.tripoder.runtime.RuntimeReceiverSlots$R"+completedIndex)).putExtra("tripoder.delivery",completedTicket);
        Bundle duplicateResult=platformBroadcast(duplicate);
        check("completed-dead-delivery-is-also-fenced-without-reexecution",completed.getInt("resourceRetired")==1&&duplicateResult.getInt("code")==23&&!inspectReceiver(completedIndex,null,false).getBoolean("engine")&&receiverDelivery(completedTicket).getString("execution").equals("SUCCEEDED"),receiverDelivery(completedTicket));
    }
    private int processIndex(String generation)throws Exception{
        JSONArray rows=new JSONArray(call(request("probeSessions","runId",runId)).getString("sessions"));for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("generation").equals(generation)){String stub=row.getString("activityStub");return "ABCDEFGH".indexOf(stub.charAt(stub.length()-1));}}throw new IllegalStateException("PROBE_PROCESS_NOT_FOUND");
    }
    private JSONObject receiverDelivery(String ticket)throws Exception{return new JSONObject(call(request("probeReceiverDelivery","ticket",ticket)).getString("delivery"));}
    private Bundle inspectReceiver(int index,String ticket,boolean tamper)throws Exception{
        Intent intent=new Intent().setComponent(new ComponentName(this,"dev.tripoder.runtimeprobe.ReceiverInspection$R"+index));if(ticket!=null)intent.putExtra("probeTicket",ticket).putExtra("tamper",tamper);
        Bundle result=platformBroadcast(intent);if(result.getInt("code")!=72)throw new IllegalStateException("RECEIVER_INSPECTION_NOT_DELIVERED");return result.getBundle("extras");
    }
    private Bundle platformBroadcast(Intent intent)throws Exception{
        CompletableFuture<Bundle> result=new CompletableFuture<>();sendOrderedBroadcast(intent,null,new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){Bundle value=new Bundle();value.putInt("code",getResultCode());value.putString("data",getResultData());value.putBundle("extras",getResultExtras(false));result.complete(value);}},new Handler(Looper.getMainLooper()),23,"physical-initial",null);return result.get(15,TimeUnit.SECONDS);
    }
    private void testColdActivity()throws Exception{
        String space=space("cold");String install=install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Hold"));String prior=prepared.getString("generation");active.add(prior);
        Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject live=null;long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){JSONArray rows=new JSONArray(call(request("probeActivityTickets","space",space)).getString("tickets"));if(rows.length()==1&&rows.getJSONObject(0).getString("state").equals("LIVE")){live=rows.getJSONObject(0);break;}Thread.sleep(50);}
        check("cold-original-activity-live",live!=null,live);final int taskId=live.getInt("taskId");
        FutureTask<Boolean> background=new FutureTask<>(()->moveTaskToBack(true));runOnUiThread(background);check("cold-task-moved-to-background",background.get(5,TimeUnit.SECONDS),taskId);Thread.sleep(1200);
        stop(prior);check("dead-process-task-still-pins-install",rejected(request("uninstall","space",space,"package","dev.tripoder.p2","expectedGeneration",install,"operation",UUID.randomUUID().toString()),"INSTALL_IN_USE_BY_TASK"),install);
        JSONObject handoff=new JSONObject().put("runId",runId).put("taskId",taskId).put("action","launcher-resume-owned-task").put("priorGeneration",prior).put("priorPid",prepared.getInt("pid"));
        ProbeReports.publish(new File(getExternalFilesDir(null),"resume-task-"+runId+".json"),handoff.toString());
        check("cold-awaits-external-launcher-action",true,handoff);
        JSONObject restored=resultFor(intent,45);if(restored==null)throw new IllegalStateException("COLD_ACTIVITY_RESTORE_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(restored);
        check("cold-real-process-and-state-restored",restored.getInt("pid")!=prepared.getInt("pid")&&restored.getInt("starts")==2&&restored.getBoolean("saved")&&restored.getInt("taskId")==taskId&&restored.getString("process").equals("dev.tripoder.p2"),restored);
        Bundle current=ensure(space,"dev.tripoder.p2");check("cold-new-session-generation",!prior.equals(current.getString("generation")),json(current));
    }
    private void testTaskModes()throws Exception{
        String space=space("tasks");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.TaskModes$Coordinator"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,45);if(result==null)throw new IllegalStateException("TASK_MODES_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        check("single-task-alias-target-reuses-one-instance",result.getInt("rootCreates")==1&&result.getInt("newIntents")==2,result);
        check("single-task-clears-real-children",result.getInt("bDestroyed")==1&&result.getInt("cDestroyed")==1,result.getJSONArray("events"));
        check("single-task-affinity-creates-distinct-task",result.getInt("rootTask")!=result.getInt("coordinatorTask"),result);
        check("single-task-new-intent-does-not-replace-original",result.getBoolean("getIntentUnchanged"),result);
        JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));int reused=0;for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);if(launch.optString("kind").equals("NEW_INTENT")){reused++;check("single-task-delivery-received-"+reused,launch.getString("state").equals("RECEIVED")&&launch.getString("execution").equals("SUCCEEDED"),launch);}}check("single-task-two-real-deliveries",reused==2,launches);
        for(String generation:new ArrayList<>(active))stop(generation);
        report.put("singleTaskCompletedTasksDismissed",ProbeTasks.dismissCompleted(this,result.getInt("rootTask")));
    }
    private void testActivitySources()throws Exception{
        String space=space("sources");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.SourceAlias"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("ACTIVITY_SOURCE_TIMEOUT_UNKNOWN");check("activity-source-result-binds-request",!result.has("invalidResult")&&result.has("checks"),result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        JSONArray assertions=result.getJSONArray("checks");for(int i=0;i<assertions.length();i++){JSONObject item=assertions.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item.opt("observed"));}
        JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));int guest=0;
        for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);if(launch.getString("callerKind").equals("ACTIVITY")){guest++;check("activity-source-durable-"+guest,!launch.isNull("sourceTicket")&&!launch.isNull("sourceInstance")&&launch.getString("callerComponent").equals("dev.tripoder.p2.SourceAlias")&&launch.getString("callerPackage").equals("dev.tripoder.p2"),launch);}}
        check("rejected-source-does-not-create-launch",launches.length()==3&&guest==2,launches);
        ensure(space,"dev.tripoder.p2:sourceremote");for(String generation:new ArrayList<>(active))stop(generation);
    }
    private void testReparent()throws Exception{
        String space=space("reparent");install(space);Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ReparentTasks$Coordinator"));active.add(prepared.getString("generation"));Intent intent=prepared.getParcelable("intent",Intent.class);launchOwned(intent);
        JSONObject result=resultFor(intent,40);if(result==null)throw new IllegalStateException("REPARENT_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
        check("reparent-real-task-changes-without-recreation",result.getInt("beforeTask")==result.getInt("sourceTask")&&result.getInt("afterTask")==result.getInt("destinationTask")&&result.getInt("beforeTask")!=result.getInt("afterTask")&&result.getInt("creates")==2&&result.getInt("rootCreates")==1,result);
        check("reparent-distinct-single-top-aliases-retained",result.getInt("firstDestroyedBeforeCleanup")==0&&!result.getString("firstInstance").equals(result.getString("secondInstance")),result);
        check("reparent-result-source-remains-second-alias",result.getString("callerAfterMove").equals("dev.tripoder.p2/dev.tripoder.p2.ReparentAliasTwo")&&result.getString("entryComponent").equals("dev.tripoder.p2.ReparentAliasTwo"),result);
        JSONArray tickets=new JSONArray(call(request("probeActivityTickets","space",space)).getString("tickets"));int moved=0;for(int i=0;i<tickets.length();i++){JSONObject ticket=tickets.getJSONObject(i);if(ticket.getString("component").equals("dev.tripoder.p2.ReparentAliasTwo")){moved++;check("reparent-ledger-task-refreshed",ticket.getInt("taskId")==result.getInt("afterTask"),ticket);}}check("reparent-one-moved-instance-ticket",moved==1,tickets);
        JSONArray launches=new JSONArray(call(request("probeActivityLaunches","space",space)).getString("launches"));int taskFront=0;
        for(int i=0;i<launches.length();i++){JSONObject launch=launches.getJSONObject(i);String state=launch.getString("state");
            check("reparent-no-pending-delivery-"+i,state.equals("RECEIVED")||state.equals("SYSTEM_CONFIRMED"),launch);
            if("ACTIVITY".equals(launch.getString("callerKind")))check("reparent-system-result-recorded-"+i,!launch.isNull("startResult"),launch);
            if("TASK_TO_FRONT".equals(launch.optString("kind"))){taskFront++;check("reparent-task-front-has-system-receipt",state.equals("SYSTEM_CONFIRMED")&&launch.getInt("startResult")==2&&launch.getString("execution").equals("SUCCEEDED")&&!launch.isNull("receiver")&&!launch.isNull("instance"),launch);}
        }check("reparent-one-task-front-without-callback",taskFront==1,launches);
        for(String generation:new ArrayList<>(active))stop(generation);
        report.put("reparentCompletedTasksDismissed",ProbeTasks.dismissCompleted(this,result.getInt("sourceTask"),result.getInt("destinationTask")));
    }
    private void testResultFlows()throws Exception{
        String space=space("results");install(space);
        Bundle boundary=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ResultFlows$ControllerBoundary"));active.add(boundary.getString("generation"));Intent boundaryIntent=boundary.getParcelable("intent",Intent.class);launchOwned(boundaryIntent);
        JSONObject boundaryResult=resultFor(boundaryIntent,30);if(boundaryResult==null)throw new IllegalStateException("RESULT_BOUNDARY_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",boundaryIntent.getStringExtra("tripoder.ticket")));runs.put(boundaryResult);check("controller-activity-result-fd-and-clip-rejected",boundaryResult.getBoolean("fdBlocked")&&boundaryResult.getBoolean("stillLive")&&boundaryResult.getBoolean("clipBlocked"),boundaryResult);
        for(boolean cancel:new boolean[]{true,false}){
            Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.ResultFlows$Parent"));active.add(prepared.getString("generation"));
            Intent intent=prepared.getParcelable("intent",Intent.class);String challenge=UUID.randomUUID().toString();intent.putExtra("challenge",challenge).putExtra("cancel",cancel);launchOwned(intent);
            JSONObject result=resultFor(intent,30);if(result==null)throw new IllegalStateException("RESULT_FLOW_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));runs.put(result);
            check(cancel?"default-cancellation-delivered":"pending-result-survives-source-recreation",result.getString("challenge").equals(challenge)&&(cancel?(result.getInt("code")==RESULT_CANCELED&&result.isNull("value")):(result.getInt("code")==RESULT_OK&&result.getString("value").equals("fresh")&&result.getBoolean("recreated"))),result);
        }
        ensure(space,"dev.tripoder.p2:remote");for(String generation:new ArrayList<>(active))stop(generation);
    }
    private JSONObject launch(String space)throws Exception{
        Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Chain$Main"));active.add(prepared.getString("generation"));
        String challenge=UUID.randomUUID().toString();Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("challenge",challenge);
        launchOwned(intent);JSONObject result=resultFor(intent,60);if(result==null)throw new IllegalStateException("ACTIVITY_RESULT_TIMEOUT_UNKNOWN");call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("nested-roundtrip-challenge",challenge.equals(result.optString("challenge")),result);runs.put(result);return result;
    }
    private void launchOwned(Intent intent){
        int request=++nextControllerRequest;controllerRequests.put(intent,request);
        runOnUiThread(()->startActivityForResult(intent,request));
    }
    private JSONObject resultFor(Intent intent,int seconds)throws Exception{
        Integer request=controllerRequests.get(intent);if(request==null)throw new IllegalStateException("CONTROLLER_RESULT_REQUEST_MISSING");
        long deadline=SystemClock.elapsedRealtime()+seconds*1000L;
        while(SystemClock.elapsedRealtime()<deadline){
            JSONObject result=results.poll(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);if(result==null)return null;
            if(result.getInt("controllerRequest")==request)return result;
            unmatchedControllerResults.put(result);
        }return null;
    }
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{results.offer((code==RESULT_OK&&data!=null?new JSONObject(data.getStringExtra("result")):new JSONObject().put("invalidResult",code)).put("controllerRequest",request));}catch(JSONException error){throw new IllegalStateException(error);}}
    private Bundle ensure(String space,String process)throws Exception{Bundle out=call(request("ensureProcess","space",space,"package","dev.tripoder.p2","processName",process));active.add(out.getString("generation"));check("worker-real-process-"+process,out.getInt("pid")!=android.os.Process.myPid()&&out.getInt("uid")==android.os.Process.myUid()&&out.getString("state").equals("RUNNING"),json(out));return out;}
    private Bundle ensureConcurrently(String space)throws Exception{
        ExecutorService clients=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<Bundle>> requests=new ArrayList<>();
        try{for(int i=0;i<4;i++)requests.add(clients.submit(()->{start.await();return call(request("ensureProcess","space",space,"package","dev.tripoder.p2","processName","dev.tripoder.p2"));}));start.countDown();
            Bundle first=requests.get(0).get(40,TimeUnit.SECONDS);active.add(first.getString("generation"));
            for(int i=0;i<requests.size();i++){Bundle current=requests.get(i).get(40,TimeUnit.SECONDS);check("concurrent-ensure-one-session-"+i,current.getInt("pid")==first.getInt("pid")&&current.getString("generation").equals(first.getString("generation"))&&current.getString("state").equals("RUNNING"),json(current));}return first;
        }finally{clients.shutdownNow();}
    }
    private void stop(String generation)throws Exception{call(request("stopProcess","generation",generation));active.remove(generation);}
    private String space(String suffix)throws Exception{String value=runId+"-"+suffix;call(request("createSpace","space",value));return value;}
    private String install(String space)throws Exception{return call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture).toString())).getString("generation");}
    private void awaitCapacity(int expectedAllocated)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000L;
        while(SystemClock.elapsedRealtime()<deadline){Bundle cap=call(request("probeProcessCapacity"));if(cap.getInt("allocated")==expectedAllocated)return;Thread.sleep(25);}
        throw new IllegalStateException("CAPACITY_DRAIN_TIMEOUT:"+json(call(request("probeProcessCapacity"))));
    }
    private JSONObject rejection(Bundle input,String code)throws Exception{
        try{Bundle out=call(input);return new JSONObject().put("matched",false).put("expected",code).put("returned",json(out));}
        catch(RuntimeException expected){String msg=String.valueOf(expected.getMessage());boolean matched=msg.contains(code);JSONObject out=new JSONObject().put("matched",matched).put("expected",code).put("errorClass",expected.getClass().getName()).put("error",msg);if(!matched)out.put("capacity",json(call(request("probeProcessCapacity"))));return out;}
    }
    private boolean rejected(Bundle input,String code)throws Exception{try{call(input);return false;}catch(RuntimeException expected){return String.valueOf(expected.getMessage()).contains(code);}}
    private void check(String name,boolean passed,Object value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",value));if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
    private Bundle call(Bundle input)throws Exception{return RuntimeWire.call(authority.peer,RuntimeWire.CONTROL,input);}
    private static Bundle request(String command,String...pairs){Bundle b=new Bundle();b.putString("command",command);for(int i=0;i<pairs.length;i+=2)b.putString(pairs[i],pairs[i+1]);return b;}
    private JSONObject asset(String name)throws Exception{try(var in=getAssets().open(name)){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private static JSONObject json(Bundle bundle)throws Exception{JSONObject out=new JSONObject();for(String key:bundle.keySet())out.put(key,bundle.get(key));return out;}
    private Link bind()throws Exception{Link link=new Link();FutureTask<Boolean> bind=new FutureTask<>(()->getApplicationContext().bindService(new Intent(this,P1Authority.class),link,Context.BIND_AUTO_CREATE));runOnUiThread(bind);if(!bind.get(5,TimeUnit.SECONDS)||!link.connected.await(15,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("AUTHORITY_BIND_FAILED");return link;}
    private static final class Link implements ServiceConnection {final CountDownLatch connected=new CountDownLatch(1);IBinder peer;public void onServiceConnected(ComponentName name,IBinder value){peer=value;connected.countDown();}public void onServiceDisconnected(ComponentName name){} }
}
