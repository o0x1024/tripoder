package dev.tripoder.jobsfixture;

import android.app.Activity;
import android.app.job.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

public final class JobsActivity extends Activity {
    private final JSONArray checks=new JSONArray();
    private JSONObject report=new JSONObject();
    private JobScheduler jobs;
    @Override public void onCreate(Bundle state){super.onCreate(state);new Thread(this::run,"owned-job-api").start();}
    private void run(){boolean passed=false;try{
        JobScheduler root=getSystemService(JobScheduler.class);jobs=root.forNamespace("owned");
        check("guest-job-facade-default-and-named-namespace",root.getNamespace()==null&&"owned".equals(jobs.getNamespace()));
        check("guest-schedule-sync-accepted",jobs.schedule(job(41,"sync"))==JobScheduler.RESULT_SUCCESS);
        JSONObject first=await(41,"start",1,60000);check("real-job-runs-in-distinct-logical-process",first.getInt("pid")!=android.os.Process.myPid()&&first.getString("process").equals(getPackageName()+":jobs"));
        awaitGone(41);check("sync-job-real-pending-retired",jobs.getPendingJob(41)==null);
        check("guest-schedule-async-accepted",jobs.schedule(job(42,"async"))==JobScheduler.RESULT_SUCCESS);await(42,"finish-requested",1,30000);awaitGone(42);check("real-job-finished-removes-pending",jobs.getPendingJob(42)==null);
        check("guest-schedule-stop-accepted",jobs.schedule(job(43,"stop"))==JobScheduler.RESULT_SUCCESS);await(43,"start",1,30000);jobs.cancel(43);await(43,"stop",0,30000);check("cancel-running-job-delivers-real-stop",jobs.getPendingJob(43)==null);
        check("guest-schedule-cold-recovery-accepted",jobs.schedule(job(44,"recover"))==JobScheduler.RESULT_SUCCESS);JSONObject prior=await(44,"retry-requested",1,30000);
        OwnedJob.write(new File(getFilesDir(),"jobs-recovery-ready.json"),prior.toString());
        JSONObject recovered=await(44,"start",2,120000);check("system-retries-job-in-new-worker",recovered.getInt("pid")!=prior.getInt("pid"));awaitGone(44);
        check("namespace-query-has-no-pending-jobs",jobs.getAllPendingJobs().isEmpty());passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
    finally{try{if(jobs!=null)jobs.cancelAll();report.put("passed",passed).put("completed",true).put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());OwnedJob.write(new File(getFilesDir(),"jobs-contract.json"),report.toString());runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(Exception error){throw new IllegalStateException(error);}}}
    private JobInfo job(int id,String mode){PersistableBundle extras=new PersistableBundle();extras.putString("payload","owned-payload");extras.putString("mode",mode);return new JobInfo.Builder(id,new ComponentName(this,OwnedJob.class)).setMinimumLatency(0).setBackoffCriteria(10000,JobInfo.BACKOFF_POLICY_LINEAR).setExtras(extras).build();}
    private JSONObject await(int id,String event,int count,long timeout)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;File file=new File(getFilesDir(),"job-"+id+".json");while(SystemClock.elapsedRealtime()<deadline){if(file.exists()){JSONObject value=new JSONObject(Files.readString(file.toPath()));if(value.getString("event").equals(event)&&value.getInt("count")==count)return value;}Thread.sleep(30);}throw new IllegalStateException("JOB_REAL_CALLBACK_TIMEOUT:"+id+":"+event+":"+count);}
    private void awaitGone(int id)throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<deadline){if(jobs.getPendingJob(id)==null)return;Thread.sleep(30);}throw new IllegalStateException("JOB_SYSTEM_RETIREMENT_TIMEOUT:"+id);}
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
}
