package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class ManagedAdmissionProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"managed-admission").start();}
    private void run(String id,TextView view){JSONObject report=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_MANAGED_ADMISSION_RUN");
            report.put("schema",1).put("phase","managed-admission-gc").put("runId",run);ProbeEnvironment.load(this,report);ManagedHook.initializeProcess();
            report.put("admission",ManagedAdmissionChecks.run(this,directory));File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");String text=report.toString();runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
