package dev.tripoder.runtimeprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Thin acceptance coordinator; loading, routing and state rules all come from runtime. */
public final class ProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray(),runs=new JSONArray();
    private final BlockingQueue<JSONObject> completed=new LinkedBlockingQueue<>();
    private final List<Connection> workers=new ArrayList<>();
    private RuntimeBroker broker;
    private TextView status;
    private String runId;
    private JSONObject report=new JSONObject();
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setText("Tripoder P0: running");status.setTextSize(20);setContentView(status);
        runId=getIntent().getStringExtra("runId");if(runId==null)runId=UUID.randomUUID().toString();if(!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("INVALID_RUN_ID");
        new Thread(this::run,"p0-coordinator").start();
    }
    private void check(String name,boolean passed,Object observed)throws Exception {checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name+":"+observed);}
    private void run(){boolean passed=false;try{
        report.put("priorOwnedProbeTasksRemoved",ProbeTasks.prepare(this));broker=new RuntimeBroker(getApplicationContext());
        report.put("schema",1).put("runId",runId).put("startedAt",System.currentTimeMillis()).put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("targetSdk",getApplicationInfo().targetSdkVersion).put("debuggable",(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir)));
        check("non-debuggable-ordinary-uid",(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0&&android.os.Process.myUid()>=10000,android.os.Process.myUid());
        check("host-decoy-installed",getPackageManager().getPackageInfo("dev.tripoder.p0.b",0)!=null,"dev.tripoder.p0.b");
        JSONObject manifest;try(var in=getAssets().open("fixtures.json")){manifest=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}report.put("fixtures",manifest);
        String s1="s1-"+runId,s2="s2-"+runId;
        broker.catalog.createSpace(s1);broker.catalog.createSpace(s2);broker.catalog.createSpace("bad-"+runId);
        install(s1,"a",manifest);install(s1,"b",manifest);install(s2,"a",manifest);install(s2,"b",manifest);
        boolean badRejected=false;try{broker.apks.install("bad-"+runId,"a.apk","0".repeat(64));}catch(SecurityException e){badRejected=e.getMessage().contains("APK_DIGEST_MISMATCH");}check("corrupt-artifact-rejected",badRejected,badRejected);
        File canary=new File(getFilesDir(),"synthetic-host-canary.txt");try(var out=new FileOutputStream(canary)){out.write("SYNTHETIC-NO-SECRET".getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Connection a=start(s1,"a"),b=start(s1,"b");String oldGen=a.generation;
        JSONObject first=launch(a,"recreate",canary);verify(first,1,true);
        String callback=broker.lastCallback,callbackOp=broker.lastOperation;
        Bundle replay=new Bundle();replay.putString("callback",callback);replay.putString("operation",callbackOp);
        boolean replayRejected=false;try{RuntimeWire.call(b.peer,RuntimeWire.CHECK,replay);}catch(IllegalStateException e){replayRejected=e.getMessage().contains("CALLBACK_");}check("binder-callback-replay-rejected",replayRejected,callbackOp);
        JSONObject denied=launch(a,"context-denied",canary);check("context-overload-no-host-launch",denied.getString("route").equals("context-denied"),denied);
        JSONObject capabilities=launch(a,"capability-checks",canary).getJSONObject("capabilities");
        for(var keys=capabilities.keys();keys.hasNext();){String key=keys.next();check(key,capabilities.getBoolean(key),capabilities.getBoolean(key));}
        JSONObject cancelled=launch(a,"return-without-finish",canary);check("authorized-but-undelivered-is-not-success",cancelled.getString("route").equals("cancelled")&&broker.contract.query(broker.lastOperation).state==ExecutionContract.OperationState.FAILED,broker.contract.query(broker.lastOperation).state.name());
        stopAll();
        Connection a2=start(s2,"a");start(s2,"b");JSONObject second=launch(a2,"roundtrip",canary);verify(second,1,false);
        check("space-data-distinct",!first.getString("javaFiles").equals(second.getString("javaFiles")),new JSONArray().put(first.getString("javaFiles")).put(second.getString("javaFiles")));
        Bundle stale=new Bundle(replay);stale.putString("overrideGeneration",oldGen);boolean staleRejected=false;try{RuntimeWire.call(a2.peer,RuntimeWire.CHECK,stale);}catch(IllegalStateException e){staleRejected=e.getMessage().contains("PROCESS_")||e.getMessage().contains("SESSION_");}check("binder-old-generation-rejected",staleRejected,oldGen);
        launch(a2,"finish-then-die",canary);var lateAck=broker.contract.query(broker.lastOperation);
        check("delivered-after-target-death-reconciled",lateAck.state==ExecutionContract.OperationState.SUCCEEDED&&lateAck.evidence.contains("source="+a2.generation)&&lateAck.evidence.contains("operation="+lateAck.id),lateAck.evidence);
        stopAll();
        Connection a3=start(s1,"a"),b3=start(s1,"b");JSONObject third=launch(a3,"roundtrip",canary);verify(third,2,false);
        check("restart-new-generation",!oldGen.equals(a3.generation),a3.generation);
        check("restart-data-preserved",first.getString("javaFiles").equals(third.getString("javaFiles")),third.getString("javaFiles"));
        stop(b3);broker.remove(s1,"dev.tripoder.p0.b");JSONObject missing=launch(a3,"missing",canary);check("missing-package-fails-closed",missing.getString("route").equals("missing")&&missing.getString("error").contains("PACKAGE_NOT_IN_SPACE"),missing);
        stopAll();
        String op="lost-ack-"+runId,digest=ApkStore.textDigest("single-side-effect:"+runId);File effect=new File(getFilesDir(),"effect-"+runId);check("operation-admitted",executeEffect(op,digest,effect),"created=true");
        // Deliberately omit the ack after the actual durable side effect, then recover authority.
        broker.recover();check("sqlite-recovery-unknown",broker.contract.query(op).state==ExecutionContract.OperationState.UNKNOWN,broker.contract.query(op).state.name());
        check("duplicate-does-not-admit-side-effect",!executeEffect(op,digest,effect),"created=false");
        check("effect-not-replayed",new String(java.nio.file.Files.readAllBytes(effect.toPath()),StandardCharsets.UTF_8).equals("effect\n"),effect.getName());
        broker.contract.reconcile(op,ExecutionContract.OperationState.SUCCEEDED,"effect=1","file-sha256="+ApkStore.digest(effect));
        broker.recover();check("reconciliation-evidence-durable",broker.contract.query(op).state==ExecutionContract.OperationState.SUCCEEDED&&broker.contract.query(op).evidence.startsWith("file-sha256="),broker.contract.query(op).evidence);
        report.put("sameUidNativeIsolation",false).put("scope","owned-single-base-apk-explicit-activity-on-one-device").put("genericApkSupportVerified",false).put("strongIsolationVerified",false).put("productMigrated",false);passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}android.util.Log.e("TripoderP0","failed",error);
    }finally{try{stopAll();}catch(Exception cleanup){passed=false;try{report.put("cleanupError",cleanup.toString());}catch(JSONException ignored){}}
        try{report.put("passed",passed).put("checks",checks).put("runs",runs).put("events",broker==null?new JSONArray():broker.events).put("finishedAt",System.currentTimeMillis());File output=new File(getExternalFilesDir(null),"result-"+runId+".json");ProbeReports.publish(output,report.toString(2));final boolean success=passed;runOnUiThread(()->status.setText("Tripoder P0: "+(success?"PASS":"FAIL")+"\n"+checks.length()+" checks\n"+output));}catch(Exception e){android.util.Log.e("TripoderP0","report failed",e);}
        finally{if(broker!=null)broker.store.close();}}
    }
    private void install(String space,String fixture,JSONObject manifest)throws Exception{
        String sha=manifest.getJSONObject(fixture).getString("sha256"),op="install-"+space+"-"+fixture;var a=broker.contract.accept(op,"p0-controller",ApkStore.textDigest(space+":"+sha));if(!a.created)throw new IllegalStateException("RUN_ID_ALREADY_USED");broker.contract.start(op);var installed=broker.apks.install(space,fixture+".apk",sha);broker.contract.succeed(op,installed.generation);check("install-"+space+"-"+fixture,installed.sha256.equals(sha),installed.generation);
    }
    private Connection start(String space,String fixture)throws Exception{
        var slot=broker.processes.ensure(space,"dev.tripoder.p0."+fixture,"dev.tripoder.p0."+fixture);
        Connection connection=new Connection();connection.peer=slot.peer;connection.generation=slot.allocation.session.processGeneration;connection.space=space;workers.add(connection);
        var session=broker.contract.getSession(connection.generation);
        check("worker-real-process-"+fixture,session.pid!=android.os.Process.myPid()&&session.uid==android.os.Process.myUid(),new JSONObject().put("pid",session.pid).put("uid",session.uid).put("generation",connection.generation));return connection;
    }
    private JSONObject launch(Connection a,String mode,File canary)throws Exception{
        String challenge=UUID.randomUUID().toString();Intent intent=broker.launch(a.generation).putExtra("mode",mode).putExtra("hostCanary",canary.toString()).putExtra("challenge",challenge);
        runOnUiThread(()->{try{startActivityForResult(intent,101);}catch(Exception error){completed.offer(failure(error.toString()));}});
        JSONObject output=completed.poll(30,TimeUnit.SECONDS);if(output==null)throw new IllegalStateException("ACTIVITY_RESULT_TIMEOUT_UNKNOWN");
        if(output.has("componentError"))throw new IllegalStateException("OWNED_COMPONENT_RESULT_FAILED:"+mode+":"+output.getString("componentError"));
        if(!output.has("launchError"))broker.completeActivity(intent.getStringExtra("tripoder.ticket"));
        runs.put(new JSONObject().put("mode",mode).put("space",a.space).put("generation",a.generation).put("result",output));
        if(output.has("b")||"return-without-finish".equals(mode)){
            String operation=broker.lastOperation;long deadline=SystemClock.elapsedRealtime()+5000;var state=broker.contract.query(operation);
            while(state.state==ExecutionContract.OperationState.RUNNING||state.state==ExecutionContract.OperationState.UNKNOWN){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("DELIVERY_ACK_TIMEOUT_UNKNOWN");Thread.sleep(10);state=broker.contract.query(operation);}
            if(output.has("b"))check("source-delivery-ack",state.state==ExecutionContract.OperationState.SUCCEEDED&&state.result.equals("source-onActivityResult-completed;resultCode="+Activity.RESULT_OK),operation);
        }
        if(output.has("b"))check("real-challenge-returned",challenge.equals(output.getJSONObject("b").getString("challenge")),challenge);return output;
    }
    private void verify(JSONObject a,int starts,boolean recreated)throws Exception{
        check("a-application-activity-resource-jni",a.getString("package").equals("dev.tripoder.p0.a")&&a.getString("applicationPackage").equals("dev.tripoder.p0.a")&&a.getBoolean("sameGuestLoader")&&a.getString("title").equals("Owned A resource")&&a.getString("asset").equals("owned-a-asset")&&a.getString("nativeResult").equals("dev.tripoder.p0.a:"+starts)&&a.getInt("starts")==starts&&a.getString("javaFiles").equals(a.getString("applicationFiles")),a);
        check("activity-recreation",a.getBoolean("recreated")==recreated,a.getBoolean("recreated"));
        check("libc-host-private-path-rejected",!a.getBoolean("nativeCanReadHostCanary"),"libc path adapter rejects synthetic host path");
        check("same-uid-native-boundary-observed",a.getBoolean("nativeRawCanReadHostCanary"),"Direct syscall can read synthetic host canary: no strong isolation claim");
        JSONObject b=a.getJSONObject("b");check("b-real-component-resource-jni",a.getString("route").equals("roundtrip")&&b.getString("package").equals("dev.tripoder.p0.b")&&b.getString("title").equals("Owned B resource")&&b.getString("asset").equals("owned-b-asset")&&b.getString("nativeResult").equals("dev.tripoder.p0.b:"+starts)&&b.getInt("pid")!=a.getInt("pid")&&b.getInt("uid")==a.getInt("uid"),b);
    }
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{if(request!=101||code!=RESULT_OK||data==null)completed.offer(new JSONObject().put("componentError","invalid-result:"+code));else completed.offer(new JSONObject(data.getStringExtra("result")));}catch(Exception e){throw new IllegalStateException(e);}}
    private void stop(Connection connection)throws Exception{
        if(!workers.remove(connection))return;broker.processes.stop(connection.generation);
    }
    private void stopAll()throws Exception{for(Connection c:new ArrayList<>(workers))stop(c);}
    private boolean executeEffect(String op,String digest,File file)throws Exception{var admission=broker.contract.accept(op,"p0-controller",digest);if(!admission.created)return false;broker.contract.start(op);try(var out=new FileOutputStream(file,true)){out.write("effect\n".getBytes(StandardCharsets.UTF_8));out.getFD().sync();}return true;}
    private static JSONObject failure(String message){try{return new JSONObject().put("launchError",message);}catch(JSONException e){throw new IllegalStateException(e);}}
    private static final class Connection {IBinder peer;String generation,space;}
}
