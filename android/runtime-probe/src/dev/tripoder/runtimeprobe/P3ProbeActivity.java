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

/** Controller observes public-SDK guest work in two independent data incarnations. */
public final class P3ProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray(),runs=new JSONArray();private final JSONObject report=new JSONObject();
    private final BlockingQueue<JSONObject> results=new LinkedBlockingQueue<>();private final Set<String> active=new LinkedHashSet<>();
    private Link authority;private TextView status;private String runId;
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setText("Tripoder P3a storage acceptance");setContentView(status);runId=getIntent().getStringExtra("runId");if(runId==null||!runId.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(this::run,"p3-validation").start();}
    private void run(){boolean passed=false;try{
        report.put("schema",1).put("phase","P3a-context-storage").put("runId",runId).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS[0]).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("fixtures",asset("fixtures.json")).put("p2Fixtures",asset("p2-fixtures.json"));
        report.put("priorOwnedProbeTasksRemoved",ProbeTasks.prepare(this));authority=bind();JSONObject fixture=asset("p2-fixtures.json");
        String sentinel="host-owned-"+runId;try(var out=openFileOutput("storage-value",MODE_PRIVATE)){out.write(sentinel.getBytes(StandardCharsets.UTF_8));}getSharedPreferences("storage",MODE_PRIVATE).edit().putString("token",sentinel).commit();
        try(var db=openOrCreateDatabase("storage.db",MODE_PRIVATE,null)){db.execSQL("CREATE TABLE IF NOT EXISTS value (id INTEGER PRIMARY KEY, token TEXT)");db.execSQL("INSERT OR REPLACE INTO value VALUES(1,?)",new Object[]{sentinel});}
        String[] spaces={runId+"-left",runId+"-right"},tokens={UUID.randomUUID().toString(),UUID.randomUUID().toString()};JSONObject[] prior=new JSONObject[2];String[] generations=new String[2];
        for(String space:spaces){call(request("createSpace","space",space));call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"files",new JSONArray().put(fixture).toString()));}
        for(int round=0;round<3;round++)for(int index=0;index<2;index++){
            String space=spaces[index];
            if(round==2){Bundle before=call(request("inspect","space",space,"package","dev.tripoder.p2"));call(request("probeInstall","space",space,"operation",UUID.randomUUID().toString(),"expectedGeneration",before.getString("generation"),"files",new JSONArray().put(fixture.getJSONObject("upgrade")).toString()));Bundle after=call(request("inspect","space",space,"package","dev.tripoder.p2"));check("storage-upgrade-new-install-same-data-"+index,after.getLong("version")==2&&!after.getString("generation").equals(before.getString("generation"))&&after.getString("data").equals(before.getString("data")),after.toString());}
            Bundle prepared=call(request("prepareActivity","space",space,"package","dev.tripoder.p2","component","dev.tripoder.p2.Storage$Main"));active.add(prepared.getString("generation"));
            Intent intent=prepared.getParcelable("intent",Intent.class);intent.putExtra("round",round).putExtra("token",tokens[index]);runOnUiThread(()->startActivityForResult(intent,33));
            JSONObject result=results.poll(60,TimeUnit.SECONDS);if(result==null)throw new IllegalStateException("STORAGE_ACCEPTANCE_TIMEOUT_UNKNOWN");runs.put(result);call(request("completeActivity","ticket",intent.getStringExtra("tripoder.ticket")));
            JSONArray observed=result.getJSONArray("checks");for(int i=0;i<observed.length();i++){JSONObject item=observed.getJSONObject(i);check(item.getString("name")+"-space-"+index+"-round-"+round,item.getBoolean("passed"),item.opt("observed"));}check("storage-fixture-completed-"+index+"-"+round,result.optBoolean("passed"),result);
            if(prior[index]!=null)check("storage-restart-preserves-data-not-process-"+index,result.getString("data").equals(prior[index].getString("data"))&&result.getInt("pid")!=prior[index].getInt("pid")&&!generations[index].equals(prepared.getString("generation")),result);
            if(index==1)check("storage-spaces-have-distinct-roots-"+round,!result.getString("data").equals(prior[0].getString("data"))&&!result.getString("token").equals(prior[0].getString("token")),result);
            prior[index]=result;generations[index]=prepared.getString("generation");Bundle remote=call(request("ensureProcess","space",space,"package","dev.tripoder.p2","processName","dev.tripoder.p2:storageremote"));active.add(remote.getString("generation"));
            for(String generation:new ArrayList<>(active))stop(generation);
        }
        check("host-file-decoy-unchanged",Files.readString(getFileStreamPath("storage-value").toPath()).equals(sentinel),sentinel);
        check("host-prefs-decoy-unchanged",getSharedPreferences("storage",MODE_PRIVATE).getString("token","").equals(sentinel),sentinel);
        try(var db=openOrCreateDatabase("storage.db",MODE_PRIVATE,null);var cursor=db.rawQuery("SELECT token FROM value WHERE id=1",null)){check("host-database-decoy-unchanged",cursor.moveToFirst()&&cursor.getString(0).equals(sentinel),sentinel);}
        passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("TripoderP3","failed",error);
    }finally{
        try{for(String generation:new ArrayList<>(active))stop(generation);if(authority!=null){FutureTask<Void> release=new FutureTask<>(()->{getApplicationContext().unbindService(authority);return null;});runOnUiThread(release);release.get(5,TimeUnit.SECONDS);}}
        catch(Exception error){passed=false;try{report.put("cleanupError",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        try{report.put("passed",passed).put("checks",checks).put("runs",runs).put("finishedAt",System.currentTimeMillis()).put("fullP3Accepted",false).put("genericApkExecutionVerified",false).put("strongIsolationVerified",false);File output=new File(getExternalFilesDir(null),"result-"+runId+".json");ProbeReports.publish(output,report.toString(2));boolean done=passed;runOnUiThread(()->status.setText("Tripoder P3a: "+(done?"PASS":"FAIL")+"\n"+checks.length()+" checks"));}catch(Exception error){android.util.Log.e("TripoderP3","report failed",error);}
    }}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{results.offer(request==33&&code==RESULT_OK&&data!=null?new JSONObject(data.getStringExtra("result")):new JSONObject().put("invalidResult",code));}catch(JSONException error){throw new IllegalStateException(error);}}
    private void check(String name,boolean passed,Object value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",value));if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
    private void stop(String generation)throws Exception{call(request("stopProcess","generation",generation));active.remove(generation);}
    private Bundle call(Bundle input)throws Exception{return RuntimeWire.call(authority.peer,RuntimeWire.CONTROL,input);}
    private static Bundle request(String command,String...pairs){Bundle b=new Bundle();b.putString("command",command);for(int i=0;i<pairs.length;i+=2)b.putString(pairs[i],pairs[i+1]);return b;}
    private JSONObject asset(String name)throws Exception{try(var in=getAssets().open(name)){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private Link bind()throws Exception{Link link=new Link();FutureTask<Boolean> bind=new FutureTask<>(()->getApplicationContext().bindService(new Intent(this,P1Authority.class),link,Context.BIND_AUTO_CREATE));runOnUiThread(bind);if(!bind.get(5,TimeUnit.SECONDS)||!link.connected.await(15,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("AUTHORITY_BIND_FAILED");return link;}
    private static final class Link implements ServiceConnection {final CountDownLatch connected=new CountDownLatch(1);IBinder peer;public void onServiceConnected(ComponentName name,IBinder value){peer=value;connected.countDown();}public void onServiceDisconnected(ComponentName name){} }
}
