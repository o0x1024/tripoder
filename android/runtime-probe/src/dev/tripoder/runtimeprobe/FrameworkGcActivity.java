package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.runtime.ApkStore;
import dev.tripoder.runtime.FrameworkGcPlatform;
import dev.tripoder.selfhook.FrameworkRootGcChecks;
import dev.tripoder.selfhook.FrameworkAccess;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class FrameworkGcActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("Framework root GC validation");setContentView(view);
        String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("framework-gc-[a-f0-9]{16}"))throw new IllegalArgumentException("RUN_ID_REQUIRED");new Thread(()->run(run),"framework-gc-validation").start();}
    private void run(String run){JSONObject report=new JSONObject();JSONArray checks=new JSONArray();boolean passed=false,loaded=false;
        try{
            report.put("runId",run).put("startedAt",System.currentTimeMillis()).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());
            try(var input=getAssets().open("fixtures.json")){report.put("fixtures",new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8)));}
            FrameworkGcPlatform.prepare();loaded=true;report.put("frameworkGc",FrameworkRootGcChecks.run(this,checks));passed=true;
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));if(loaded)report.put("registry",new JSONObject(FrameworkAccess.statusNative()));}catch(Exception ignored){}}
        try{report.put("passed",passed).put("checks",checks).put("finishedAt",System.currentTimeMillis()).put("businessScenarioVerified",false);ProbeReports.publish(new File(getExternalFilesDir(null),"result-"+run+".json"),report.toString(2));}
        catch(Exception error){android.util.Log.e("TripoderGcProbe","report failed",error);}
    }
}
