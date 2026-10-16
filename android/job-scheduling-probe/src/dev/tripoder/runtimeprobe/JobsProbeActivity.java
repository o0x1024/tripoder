package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.os.*;
import android.widget.TextView;
import dev.tripoder.runtime.*;
import java.io.File;
import org.json.*;

/** Real scheduler acceptance coordinator; it never starts or kills a third-party application. */
public final class JobsProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView status=new TextView(this);status.setText("JobScheduler: checking durable scheduling");setContentView(status);
        String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");
        new Thread(()->{JSONObject report=new JSONObject();try{
            report.put("schema",1).put("runId",run).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("deviceBuild",Build.FINGERPRINT).put("api",Build.VERSION.SDK_INT).put("hostUid",android.os.Process.myUid()).put("hostPid",android.os.Process.myPid());
            JSONObject result=RuntimeJobSchedulingProbe.run(getApplicationContext(),run);for(var keys=result.keys();keys.hasNext();){String key=keys.next();report.put(key,result.get(key));}
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        finally{try{report.put("finishedAt",System.currentTimeMillis());ProbeReports.publish(new File(getExternalFilesDir(null),"result-"+run+".json"),report.toString(2));boolean passed=report.optBoolean("passed");runOnUiThread(()->status.setText("JobScheduler: "+(passed?"PASS":"FAIL")));}catch(Exception error){android.util.Log.e("TripoderJobs","report failed",error);}}},"jobs-scheduling-probe").start();
    }
}
