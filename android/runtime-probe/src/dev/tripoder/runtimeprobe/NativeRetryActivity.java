package dev.tripoder.runtimeprobe;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.ApkStore;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Same physical UID and retry shape as the guest fixture, with no Hook initialization. */
public final class NativeRetryActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("Native JobScheduler retry baseline");setContentView(view);String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("jobs-native-[a-f0-9]{16}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(()->run(run),"native-retry-coordinator").start();}
    private void run(String run){JSONObject report=new JSONObject();JSONArray checks=new JSONArray(),samples=new JSONArray();JobScheduler scheduler=null;boolean passed=false;
        try{
            report.put("runId",run).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid()).put("scope","native-platform-retry-no-hook").put("nativeHookInitialized",false);
            try(var input=getAssets().open("fixtures.json")){report.put("fixtures",new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));}
            String namespace="tripoder.job."+ApkStore.textDigest(run);report.put("namespace",namespace);scheduler=getSystemService(JobScheduler.class).forNamespace(namespace);
            PersistableBundle extras=new PersistableBundle();extras.putString("run",run);
            check(checks,"native-platform-schedule-accepted",scheduler.schedule(new JobInfo.Builder(44,new ComponentName(this,NativeRetryService.class)).setMinimumLatency(0).setBackoffCriteria(10000,JobInfo.BACKOFF_POLICY_LINEAR).setExtras(extras).build())==JobScheduler.RESULT_SUCCESS);
            File receipt=new File(getFilesDir(),run+".json");long deadline=SystemClock.elapsedRealtime()+150000,last=0;String prior="";int originalPid=0;boolean killed=false;
            while(SystemClock.elapsedRealtime()<deadline){
                JobInfo pending=scheduler.getPendingJob(44);int[] reasons=scheduler.getPendingJobReasons(44);boolean executing=false,invalid=false;JSONArray why=new JSONArray();for(int reason:reasons){why.put(reason);executing|=reason==JobScheduler.PENDING_JOB_REASON_EXECUTING;invalid|=reason==JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID;}
                JSONObject current=receipt.exists()?new JSONObject(Files.readString(receipt.toPath())):null;
                if(current!=null&&current.getInt("uid")!=android.os.Process.myUid())throw new SecurityException("NATIVE_RETRY_UID_MISMATCH");
                String sig=(pending!=null)+":"+why+":"+killed+":"+(current==null?0:current.getInt("count"));long now=SystemClock.elapsedRealtime();
                if(!sig.equals(prior)||now-last>=1000){samples.put(new JSONObject().put("elapsed",now).put("pending",pending!=null).put("reasons",why).put("workerTerminated",killed).put("receipt",current==null?JSONObject.NULL:current));last=now;prior=sig;ProbeReports.checkpoint(new File(getExternalFilesDir(null),"job-system-"+run+".json"),new JSONObject().put("runId",run).put("samples",samples).toString());}
                if(!killed&&current!=null&&current.getString("event").equals("finish-enqueued-and-main-drained")&&pending!=null&&!executing&&!invalid){
                    int pid=current.getInt("pid");boolean owned=false;for(var process:getSystemService(ActivityManager.class).getRunningAppProcesses())if(process.pid==pid&&process.uid==android.os.Process.myUid()&&process.processName.equals(getPackageName()+":nativejob"))owned=true;
                    if(!owned)throw new SecurityException("NATIVE_RETRY_PROCESS_NOT_OBSERVED");originalPid=pid;android.os.Process.killProcess(pid);killed=true;
                }
                if(current!=null&&current.getInt("count")>=2){check(checks,"native-system-retries-in-new-process",killed&&current.getInt("pid")!=originalPid);passed=true;break;}
                Thread.sleep(100);
            }
            if(!passed)throw new IllegalStateException("NATIVE_PLATFORM_RETRY_TIMEOUT");
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{try{if(scheduler!=null)scheduler.cancelAll();report.put("passed",passed).put("checks",checks).put("samples",samples).put("finishedAt",System.currentTimeMillis());ProbeReports.publish(new File(getExternalFilesDir(null),"result-"+run+".json"),report.toString(2));}catch(Exception error){android.util.Log.e("TripoderNativeRetry","report failed",error);}}
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
}
