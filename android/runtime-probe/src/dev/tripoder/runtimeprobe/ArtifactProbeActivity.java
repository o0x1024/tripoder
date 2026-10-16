package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Uses only public installed APK files, and the production FD installation/DEX paths. */
public final class ArtifactProbeActivity extends Activity {
    private final JSONArray checks=new JSONArray();
    private final JSONObject report=new JSONObject();
    private Link authority,worker;
    private String runId;
    private TextView status;
    @Override public void onCreate(Bundle state){super.onCreate(state);status=new TextView(this);status.setTextSize(18);status.setPadding(32,70,32,32);setContentView(status);
        runId=getIntent().getStringExtra("runId");if(runId==null||!runId.matches("(?:artifact|target)-[a-z0-9]{16}"))throw new IllegalArgumentException("PROBE_RUN_ID_INVALID");new Thread(this::run,"artifact-device-validation").start();}
    private void run(){boolean passed=false;try{
        report.put("schema",1).put("runId",runId).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("deviceBuild",Build.FINGERPRINT).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid());
        try(var in=getAssets().open("fixtures.json")){report.put("fixtures",new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));}
        phase("连接安装内核");authority=bind(P1Authority.class);call(request("status"));
        boolean launching=getIntent().getBooleanExtra("launchTargets",false);boolean all=true;
        String selected=getIntent().getStringExtra("targetPackage");
        if(selected!=null&&!List.of("com.tencent.mm","com.tencent.tmgp.sgame").contains(selected))throw new IllegalArgumentException("TARGET_PACKAGE_INVALID");
        for(String pkg:selected==null?List.of("com.tencent.mm","com.tencent.tmgp.sgame"):List.of(selected))try{
            if(pkg.equals("com.tencent.mm"))target("com.tencent.mm","f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d",18,190053436L);
        else target("com.tencent.tmgp.sgame","6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384",6,40277916L);
        }catch(Exception error){if(!launching)throw error;all=false;JSONObject failure=new JSONObject().put("package",pkg).put("error",android.util.Log.getStackTraceString(error));checks.put(new JSONObject().put("name",pkg+":launch").put("passed",false).put("observed",failure));}
        passed=all;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}android.util.Log.e("TripoderArtifact","failed",error);
    }finally{
        try{stopWorker();if(authority!=null&&!getIntent().getBooleanExtra("launchTargets",false))unbind(authority);}catch(Exception error){passed=false;try{report.put("cleanupError",error.toString());}catch(JSONException ignored){}}
        try{report.put("passed",passed).put("checks",checks).put("finishedAt",System.currentTimeMillis()).put("applicationInitialized",getIntent().getBooleanExtra("launchTargets",false)&&passed).put("businessScenarioVerified",false).put("strongIsolationVerified",false);
            ProbeReports.publish(new File(getExternalFilesDir(null),"result-"+runId+".json"),report.toString(2));phase(passed?(getIntent().getBooleanExtra("launchTargets",false)?"启动观察通过；登录与业务场景尚未验收":"安装与 DEX 类加载验证通过；尚未启动应用"):"验证失败，证据已保留");
        }catch(Exception error){android.util.Log.e("TripoderArtifact","report failed",error);}
    }}
    private void target(String pkg,String expected,int dexFiles,long dexBytes)throws Exception{
        phase("验证公开 APK："+pkg);ApplicationInfo host=getPackageManager().getApplicationInfo(pkg,0);
        if(host.splitSourceDirs!=null&&host.splitSourceDirs.length!=0)throw new IllegalStateException("TARGET_VERSION_NOW_SPLIT");
        File source=new File(host.sourceDir);String hash=ApkStore.digest(source);check(pkg+":original-apk",expected.equals(hash),hash);
        String reused=getIntent().getStringExtra("targetSpace");
        if(reused!=null&&(!getIntent().getBooleanExtra("launchTargets",false)||getIntent().getStringExtra("targetPackage")==null||!reused.matches("[a-zA-Z0-9-]{1,100}")))throw new IllegalArgumentException("TARGET_SPACE_REUSE_INVALID");
        String space=reused==null?runId+"-"+(pkg.equals("com.tencent.mm")?"wechat":"honor"):reused;
        long start=SystemClock.elapsedRealtime();String generation;Bundle inspected;
        if(reused!=null){
            phase("核验保留的同一安装："+pkg);inspected=call(request("inspect","space",space,"package",pkg));generation=inspected.getString("generation");
            check(pkg+":existing-install-exact-apk",expected.equals(ApkStore.digest(new File(inspected.getString("apk")))),json(inspected));
        }else{
        call(request("createSpace","space",space));
        Bundle install=request("install","space",space,"operation",runId+"-install-"+(pkg.equals("com.tencent.mm")?"wechat":"honor"));install.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));install.putStringArrayList("sha256",new ArrayList<>(List.of(expected)));
        phase("安装并校验制品："+pkg);
        try(var fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)){install.putParcelable("fd0",fd);generation=call(install).getString("generation");}
        inspected=call(request("inspect","space",space,"package",pkg));JSONObject installed=json(inspected).put("installMs",SystemClock.elapsedRealtime()-start);
        check(pkg+":install-committed",generation.equals(inspected.getString("generation"))&&"SUCCEEDED".equals(call(request("query","operation",install.getString("operation"))).getString("state")),installed);
        }
        Bundle flags=call(request("probeManifestFlags","space",space,"package",pkg));check(pkg+":device-manifest-resolved",flags.getBoolean("rawResourceReference")&&flags.getInt("componentCount")>2,json(flags));
        phase("独立进程加载 DEX（不初始化应用）："+pkg);worker=bind(ArtifactDexInspectionService.class);
        Bundle check=request("inspect","artifact",new File(inspected.getString("apk")).getParent(),"descriptorSha",inspected.getString("descriptorSha"),"package",pkg);
        try{Bundle loaded=RuntimeWire.call(worker.peer,RuntimeWire.CHECK,check);
        check(pkg+":all-dex-loaded",loaded.getInt("pid")!=android.os.Process.myPid()&&loaded.getInt("uid")==android.os.Process.myUid()&&loaded.getInt("dexFiles")==dexFiles&&loaded.getLong("dexBytes")==dexBytes&&loaded.getBoolean("observedMappingsReadOnly")&&!loaded.getBoolean("applicationInitialized"),json(loaded));
        }finally{stopWorker();}check(pkg+":source-unchanged",ApkStore.digest(source).equals(hash),hash);
        if(getIntent().getBooleanExtra("launchTargets",false)){
            Intent entry=getPackageManager().getLaunchIntentForPackage(pkg);if(entry==null||entry.getComponent()==null)throw new IllegalStateException("TARGET_LAUNCHER_MISSING");
            phase("初始化客体 Application："+pkg);
            Bundle prepared=call(request("prepareActivity","space",space,"package",pkg,"component",entry.getComponent().getClassName()));String process=prepared.getString("generation");
            Intent physical=prepared.getParcelable("intent",Intent.class);physical.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            report.put("launchGeneration",process).put("launchTicket",physical.getStringExtra("tripoder.ticket"));phase("派发并观察客体 Activity："+pkg);
            FutureTask<Void> launch=new FutureTask<>(()->{startActivity(physical);return null;});runOnUiThread(launch);launch.get(10,TimeUnit.SECONDS);
            JSONArray samples=new JSONArray();report.put("startupSamples",samples);JSONObject observed=null;
            long until=SystemClock.elapsedRealtime()+30000;
            do{observed=TargetStartupObservation.read(this,space,pkg,process);samples.put(observed);checkpoint();
                if(!"RUNNING".equals(observed.getString("state")))break;
                Thread.sleep(1000);
            }while(SystemClock.elapsedRealtime()<until);
            check(pkg+":guest-activity-created-and-retained","RUNNING".equals(observed.getString("state"))&&observed.getBoolean("createdActivityInTask"),observed);
            // Observation never kills the worker: that would inject a recovery scenario
            // while Android still owns its task. The external runner owns later force-stop.
        }

    }
    private void phase(String value){try{report.put("phase",value);checkpoint();}catch(Exception error){throw new IllegalStateException("PROBE_PROGRESS_FAILED",error);}runOnUiThread(()->status.setText("Tripoder · 制品验证\n\n"+value));}
    private void checkpoint()throws Exception{report.put("checks",checks).put("observedAt",System.currentTimeMillis());ProbeReports.checkpoint(new File(getExternalFilesDir(null),"progress-"+runId+".json"),report.toString(2));}
    private void check(String name,boolean ok,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",ok).put("observed",observed));checkpoint();if(!ok)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
    private static JSONObject json(Bundle b)throws Exception{JSONObject out=new JSONObject();for(String key:b.keySet())out.put(key,b.get(key));return out;}
    private static Bundle request(String command,String...pairs){Bundle b=new Bundle();b.putString("command",command);for(int i=0;i<pairs.length;i+=2)b.putString(pairs[i],pairs[i+1]);return b;}
    private Bundle call(Bundle b)throws Exception{return RuntimeWire.call(authority.peer,RuntimeWire.CONTROL,b);}
    private Link bind(Class<?> type)throws Exception{Link link=new Link();runOnUiThread(()->{link.bound=getApplicationContext().bindService(new Intent(this,type),link,BIND_AUTO_CREATE);if(!link.bound)link.connected.countDown();});if(!link.connected.await(15,TimeUnit.SECONDS)||link.peer==null){if(link.bound)unbind(link);throw new IOException("PROBE_BIND_FAILED");}link.peer.linkToDeath(link.dead::countDown,0);return link;}
    private void unbind(Link link)throws Exception{FutureTask<Void> task=new FutureTask<>(()->{if(link.bound){getApplicationContext().unbindService(link);link.bound=false;}return null;});runOnUiThread(task);task.get(5,TimeUnit.SECONDS);}
    private void stopWorker()throws Exception{if(worker==null)return;Link old=worker;worker=null;try{RuntimeWire.call(old.peer,RuntimeWire.STOP,new Bundle());}catch(DeadObjectException ignored){}unbind(old);if(!old.dead.await(10,TimeUnit.SECONDS))throw new IllegalStateException("PROBE_WORKER_DEATH_UNCONFIRMED");}
    private static final class Link implements ServiceConnection{
        final CountDownLatch connected=new CountDownLatch(1),dead=new CountDownLatch(1);volatile IBinder peer;volatile boolean bound;
        public void onServiceConnected(ComponentName n,IBinder p){peer=p;connected.countDown();}
        public void onServiceDisconnected(ComponentName n){}
        public void onNullBinding(ComponentName n){connected.countDown();}
    }
}
