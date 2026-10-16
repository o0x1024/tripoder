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

/** Real guest calls through Activity; never reflects fixture methods in the host. */
public final class P4ProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray(),runs=new JSONArray();private final JSONObject report=new JSONObject();
    private final BlockingQueue<JSONObject> results=new LinkedBlockingQueue<>();
    private final JSONArray expectedTerminations=new JSONArray();
    private Link authority;private TextView status;private String runId,space,generation;private int pid,nextRequest=100;
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setText("Tripoder P4 Java + Native Hook acceptance");setContentView(status);runId=getIntent().getStringExtra("runId");if(runId==null||!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(this::run,"p4-validation").start();}
    private void run(){boolean passed=false;try{
        report.put("schema",1).put("phase","P4-java-native-hook").put("runId",runId).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("fixtures",asset("fixtures.json")).put("p2Fixtures",asset("p2-fixtures.json"));
        report.put("priorOwnedProbeTasksRemoved",ProbeTasks.prepare(this));authority=bind();space=runId+"-hooks";
        call(request("createSpace","space",space));call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(asset("p2-fixtures.json")).toString()));
        process();check("target-not-initialized-at-bootstrap",activity("init").getInt("initializations")==0,"no Target calls");
        JSONObject observed=hook("observe",new JSONObject().put("selectors",P4Scripts.selectors()));JSONObject evidence=observed.getJSONObject("evidence");
        report.put("observedEvidence",observed);
        check("evidence-actual-process",observed.getJSONObject("identity").getInt("pid")==pid&&evidence.getString("processInstanceId").equals(pid+":"+generation),observed);
        check("evidence-nine-exact-overloads",evidence.getJSONArray("symbols").length()==9,evidence.getJSONArray("symbols"));
        check("observe-does-not-initialize-target",activity("init").getInt("initializations")==0,"Class.forName false");
        reject("bad-command-fields",()->hook("status",new JSONObject().put("extra",true)),"HOOK_COMMAND_FIELDS");
        reject("stale-evidence",()->hook("prepare",new JSONObject().put("evidenceId","old").put("source",P4Scripts.source())),"HOOK_EVIDENCE_STALE");
        reject("unrestricted-source",()->hook("prepare",new JSONObject().put("evidenceId",evidence.getString("id")).put("source","globalThis.Process.kill(1)")),"DRAFT_UNKNOWN_IDENTIFIER:globalThis");
        String prepareOp=UUID.randomUUID().toString();JSONObject prepareInput=new JSONObject().put("evidenceId",evidence.getString("id")).put("source",P4Scripts.source());
        JSONObject canceled=hook(prepareOp,"prepare",prepareInput);Bundle prepareJournal=call(request("query","operation",prepareOp));
        check("prepare-token-not-persisted-in-receipt",prepareJournal.getString("state").equals("SUCCEEDED")&&!new JSONObject(prepareJournal.getString("result")).has("token"),prepareJournal.getString("result"));
        check("prepared-status-explicit",hook("status",new JSONObject()).getString("state").equals("PREPARED"),"status distinguishes prepared and idle");
        check("cancel-preparation",hook("cancelPreparation",new JSONObject().put("token",canceled.getString("token"))).getString("state").equals("CANCELED"),"no method changed");
        reject("canceled-token-rejected",()->hook("activate",new JSONObject().put("token",canceled.getString("token")).put("sourceSha256",canceled.getString("sourceSha256"))),"HOOK_PREPARATION_STALE");
        JSONObject prepared=hook("prepare",prepareInput);String token=prepared.getString("token"),sha=prepared.getString("sourceSha256");
        check("prepare-source-bound",sha.equals(ApkStore.textDigest(P4Scripts.source()))&&prepared.getInt("rules")==9,sha);
        check("prepare-does-not-initialize-target",activity("init").getInt("initializations")==0,"before activation");
        baseline("prepared-no-effects",activity("all"));
        reject("wrong-prepare-token",()->hook("activate",new JSONObject().put("token","wrong").put("sourceSha256",sha)),"HOOK_PREPARATION_STALE");
        String activationOp=UUID.randomUUID().toString();JSONObject activateInput=new JSONObject().put("token",token).put("sourceSha256",sha);
        JSONObject churnBefore=activity("churn-start").getJSONObject("churn");
        check("deopt-four-concurrent-stacks-started",churnBefore.getInt("threads")==4&&churnBefore.getBoolean("running"),churnBefore);
        JSONObject activated=hook(activationOp,"activate",activateInput);String revision=activated.getString("revision");
        check("activation-ack",activated.getString("state").equals("ACTIVE")&&activated.getJSONObject("identity").getInt("pid")==pid,activated);
        JSONObject churnAfter=activity("churn-stop").getJSONObject("churn");
        check("deopt-concurrent-stacks-survive-and-progress",churnAfter.getBoolean("allAliveBeforeStop")&&churnAfter.getBoolean("allStopped")&&churnAfter.getLong("iterations")>churnBefore.getLong("iterations")&&churnAfter.getLong("checksum")>0,churnAfter);
        reject("operation-not-replayed",()->hook(activationOp,"activate",activateInput),"HOOK_ALREADY_ACCEPTED_QUERY_REQUIRED");
        Bundle journal=call(request("query","operation",activationOp));check("activation-durable-ack",journal.getString("state").equals("SUCCEEDED"),journal.getString("result"));
        JSONObject changed=activity("all"),counts=changed.getJSONObject("counts");
        check("before-original-after",changed.getInt("add")==30&&counts.getInt("add")==1,changed);
        check("replace-instance-overload-string-null",changed.getString("text").equals("hook:owned")&&changed.isNull("nullText")&&counts.getInt("text")==0,changed);
        check("long-exact-beyond-double",changed.getString("exact").equals("9007199254740994")&&counts.getInt("long")==0,changed);
        check("original-throwable-identity",changed.getBoolean("sameOriginalException")&&counts.getInt("throw")==1,changed);
        check("before-error-no-original",changed.getString("beforeError").contains("java.lang.IllegalStateException:HOOK_EXECUTION:")&&counts.getInt("before")==0,changed);
        check("after-error-original-once",changed.getString("afterError").contains("java.lang.IllegalStateException:HOOK_EXECUTION:")&&counts.getInt("after")==1,changed);
        check("recursive-invocations",changed.getInt("recursive")==7&&counts.getInt("recursive")==4,changed);
        check("eight-thread-real-hook-calls",changed.getInt("threadSum")==4400&&changed.getJSONArray("threadErrors").length()==0&&counts.getInt("thread")==0,changed);
        JSONObject active=hook("status",new JSONObject());check("hook-hit-error-counters",active.getInt("hits")==211&&active.getInt("errors")==2&&active.getInt("inFlight")==0,active);
        check("held-call-entered",activity("hold").getBoolean("entered"),"original body entered");
        JSONObject held=hook("status",new JSONObject());check("held-call-visible",held.getInt("inFlight")==1&&held.getInt("parameterVersion")==1,held);
        JSONObject updated=hook("updateParameters",new JSONObject().put("revision",revision).put("parameterVersion",1).put("parameters",new JSONObject().put("increment",200)));
        check("parameter-version-ack",updated.getInt("parameterVersion")==2&&updated.getJSONObject("parameters").getInt("increment")==200,updated);
        reject("stale-parameter-update",()->hook("updateParameters",new JSONObject().put("revision",revision).put("parameterVersion",1).put("parameters",new JSONObject().put("increment",300))),"HOOK_PARAMETER_VERSION_STALE");
        reject("parameter-range",()->hook("updateParameters",new JSONObject().put("revision",revision).put("parameterVersion",2).put("parameters",new JSONObject().put("increment",1001))),"PARAM_RANGE");
        JSONObject released=activity("release");check("inflight-immutable-parameter-snapshot",released.getBoolean("finished")&&released.isNull("heldError")&&released.getInt("heldResult")==107,released);
        check("new-call-entered",activity("hold").getBoolean("entered"),"updated version");
        JSONObject releasedNew=activity("release");check("new-call-new-parameters",releasedNew.getBoolean("finished")&&releasedNew.isNull("heldError")&&releasedNew.getInt("heldResult")==207,releasedNew);
        reject("wrong-disable-revision",()->hook("disable",new JSONObject().put("revision","old")),"HOOK_REVISION_STALE");
        check("disable-during-held-call-entered",activity("hold").getBoolean("entered"),"drain stimulus");
        JSONObject draining=hook("disable",new JSONObject().put("revision",revision));check("disable-inflight-does-not-block",draining.getString("state").equals("DRAINING")&&draining.getInt("inFlight")==1,draining);
        reject("draining-parameter-update",()->hook("updateParameters",new JSONObject().put("revision",revision).put("parameterVersion",2).put("parameters",new JSONObject().put("increment",300))),"HOOK_PARAMETER_VERSION_STALE");
        JSONObject drained=activity("release");check("drained-call-keeps-accepted-plan",drained.getBoolean("finished")&&drained.isNull("heldError")&&drained.getInt("heldResult")==207,drained);
        JSONObject disabled=hook("status",new JSONObject());check("disable-ack",disabled.getString("state").equals("DISABLED"),disabled);
        baseline("disabled-restores-methods",activity("all"));
        reject("consumed-token",()->hook("activate",activateInput),"HOOK_PREPARATION_STALE");
        JSONArray outside=new JSONArray().put(new JSONObject().put("id","host").put("kind","java").put("className","java.lang.String").put("methodName","length").put("argumentTypes",new JSONArray()));
        reject("framework-target-rejected",()->hook("observe",new JSONObject().put("selectors",outside)),"HOOK_CLASS_OUTSIDE_GUEST");
        reject("failed-observe-invalidates-evidence",()->hook("prepare",new JSONObject().put("evidenceId",evidence.getString("id")).put("source",P4Scripts.source())),"HOOK_EVIDENCE_STALE");
        String old=generation;call(request("stopProcess","generation",old));generation=null;process();check("restart-new-process",!generation.equals(old),generation);
        reject("old-process-command",()->call(request("hook","operation",UUID.randomUUID().toString(),"generation",old,"request",new JSONObject().put("command","status").put("processGeneration",old).toString())),"STALE_SESSION");
        reject("old-token-new-process",()->hook("activate",activateInput),"HOOK_PREPARATION_STALE");baseline("restart-no-implicit-hook",activity("all"));
        P4NativeChecks.run(this);
        report.put("expectedHookTerminations",expectedTerminations);
        passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("TripoderP4","failed",error);
    }finally{
        try{if(generation!=null)call(request("stopProcess","generation",generation));if(authority!=null){FutureTask<Void> release=new FutureTask<>(()->{getApplicationContext().unbindService(authority);return null;});runOnUiThread(release);release.get(5,TimeUnit.SECONDS);}}
        catch(Exception error){passed=false;try{report.put("cleanupError",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        try{report.put("expectedHookTerminations",expectedTerminations).put("passed",passed).put("checks",checks).put("runs",runs).put("finishedAt",System.currentTimeMillis()).put("fullP4Accepted",false).put("genericApkExecutionVerified",false).put("strongIsolationVerified",false);ProbeReports.publish(new File(getExternalFilesDir(null),"result-"+runId+".json"),report.toString(2));boolean done=passed;runOnUiThread(()->status.setText("Tripoder P4: "+(done?"PASS":"FAIL")+"\n"+checks.length()+" checks"));}catch(Exception error){android.util.Log.e("TripoderP4","report failed",error);}
    }}
    void process()throws Exception{Bundle result=call(request("ensureProcess","space",space,"package","dev.tripoder.p2","processName","dev.tripoder.p2:hooks"));generation=result.getString("generation");pid=result.getInt("pid");}
    JSONObject activity(String mode)throws Exception{
        Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Hooks$Main"));if(!generation.equals(prepared.getString("generation")))throw new IllegalStateException("FIXTURE_PROCESS_CHANGED");
        Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("mode",mode);int request=++nextRequest;runOnUiThread(()->startActivityForResult(intent,request));JSONObject result=results.poll(45,TimeUnit.SECONDS);if(result==null)throw new IllegalStateException("HOOK_FIXTURE_TIMEOUT_UNKNOWN");if(result.getInt("request")!=request)throw new IllegalStateException("HOOK_FIXTURE_CALLBACK_MISMATCH:"+result);runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        check("guest-result-"+runs.length(),!result.has("error")&&result.getInt("pid")==pid&&result.getString("package").equals("dev.tripoder.p2"),result);return result;
    }
    private void baseline(String name,JSONObject value)throws Exception{JSONObject count=value.getJSONObject("counts");check(name,value.getInt("add")==5&&value.getString("text").equals("original:owned")&&value.getString("nullText").equals("original-null")&&value.getString("exact").equals("9007199254740993")&&value.getBoolean("sameOriginalException")&&value.getInt("beforeValue")==7&&value.getInt("afterValue")==7&&value.getInt("recursive")==3&&value.getInt("threadSum")==2600&&value.getJSONArray("threadErrors").length()==0&&count.getInt("add")==1&&count.getInt("text")==2&&count.getInt("long")==1&&count.getInt("throw")==1&&count.getInt("before")==1&&count.getInt("after")==1&&count.getInt("recursive")==4&&count.getInt("thread")==200,value);}
    JSONObject hook(String command,JSONObject fields)throws Exception{return hook(UUID.randomUUID().toString(),command,fields);}
    private JSONObject hook(String op,String command,JSONObject fields)throws Exception{JSONObject input=new JSONObject(fields.toString()).put("command",command).put("processGeneration",generation);return new JSONObject(call(request("hook","operation",op,"generation",generation,"request",input.toString())).getString("result")).getJSONObject("result");}
    interface Action{void run()throws Exception;}
    void reject(String name,Action action,String expected)throws Exception{String error=null;try{action.run();}catch(Exception e){error=e.toString();}check(name,error!=null&&error.contains(expected),error==null?"unexpected success":error);}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{results.offer((code==RESULT_OK&&data!=null?new JSONObject(data.getStringExtra("result")):new JSONObject().put("invalidResult",code)).put("request",request));}catch(JSONException error){throw new IllegalStateException(error);}}
    void check(String name,boolean passed,Object value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",value));ProbeReports.checkpoint(new File(getExternalFilesDir(null),"progress-"+runId+".json"),new JSONObject().put("runId",runId).put("terminal",false).put("checks",checks).put("lastCompletedCheck",name).toString());if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
    void expectDeath(String mode)throws Exception{
        long started=System.currentTimeMillis();
        Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Hooks$Main"));String old=generation;
        Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("mode",mode);int request=++nextRequest;runOnUiThread(()->startActivityForResult(intent,request));
        JSONObject result=results.poll(35,TimeUnit.SECONDS);check("native-fatal-no-business-result-"+mode,result!=null&&result.optInt("request",-1)==request&&result.optInt("invalidResult",-1)==RESULT_CANCELED,result);
        call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
        Bundle observed=null;long deadline=SystemClock.elapsedRealtime()+5000;do{observed=call(request("probeProcessState","generation",old));if("DEAD".equals(observed.getString("state")))break;Thread.sleep(25);}while(SystemClock.elapsedRealtime()<deadline);
        check("native-fatal-process-death-"+mode,"DEAD".equals(observed.getString("state")),new JSONObject().put("processGeneration",old).put("pid",pid).put("state",observed.getString("state")));generation=null;
        JSONObject exit=null;long exitDeadline=SystemClock.elapsedRealtime()+15000;
        do{Bundle captured=call(request("probeProcessExit","generation",old,"since",Long.toString(started)));if(captured.containsKey("result")){exit=new JSONObject(captured.getString("result"));break;}Thread.sleep(50);}while(SystemClock.elapsedRealtime()<exitDeadline);
        boolean script=mode.equals("native-failure");int reason=script?android.app.ApplicationExitInfo.REASON_SIGNALED:android.app.ApplicationExitInfo.REASON_EXIT_SELF,status=script?9:198;
        check("native-fatal-exact-system-exit-"+mode,exit!=null&&exit.getInt("pid")==pid&&exit.getInt("uid")==android.os.Process.myUid()&&exit.getInt("reason")==reason&&exit.getInt("status")==status&&exit.getLong("timestamp")>=started,exit);
        expectedTerminations.put(new JSONObject().put("pid",pid).put("reason",script?"HOOK_TYPE":"HOOK_ACTIVE_MODULE_UNLOADED").put("startedAt",started).put("exitInfo",exit));
    }
    private Bundle call(Bundle input)throws Exception{return RuntimeWire.call(authority.peer,RuntimeWire.CONTROL,input);}
    private static Bundle request(String command,String...pairs){Bundle b=new Bundle();b.putString("command",command);for(int i=0;i<pairs.length;i+=2)b.putString(pairs[i],pairs[i+1]);return b;}
    private JSONObject asset(String name)throws Exception{try(var in=getAssets().open(name)){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private Link bind()throws Exception{Link link=new Link();FutureTask<Boolean> bind=new FutureTask<>(()->getApplicationContext().bindService(new Intent(this,P1Authority.class),link,Context.BIND_AUTO_CREATE));runOnUiThread(bind);if(!bind.get(5,TimeUnit.SECONDS)||!link.connected.await(15,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("AUTHORITY_BIND_FAILED");return link;}
    private static final class Link implements ServiceConnection{final CountDownLatch connected=new CountDownLatch(1);IBinder peer;public void onServiceConnected(ComponentName name,IBinder value){peer=value;connected.countDown();}public void onServiceDisconnected(ComponentName name){}}
}
