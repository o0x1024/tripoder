package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

/** Read-only ART inventory and owned late-loading evidence. No global hook claim. */
public final class CoverageProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String id=getIntent().getStringExtra("runId");
        new Thread(()->run(id,view),"owned-coverage-probe").start();
    }
    private void run(String id,TextView view){
        JSONObject result=new JSONObject();File directory=null;
        try{
            String run=UUID.fromString(id).toString();
            directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_RUN_REQUIRED");
            result.put("schema",1).put("phase","H3j-loaded-classes").put("runId",run);
            ProbeEnvironment.load(this,result);
            result.put("coverage",CoverageProbeChecks.run(this,directory));
            ProbeFiles.write(new File(directory,"maps.txt"),ProbeFiles.read("/proc/self/maps"));
            ProbeFiles.write(new File(directory,"smaps.txt"),ProbeFiles.read("/proc/self/smaps"));
            result.put("mapsSha256",ProbeFiles.sha(new File(directory,"maps.txt"))).put("smapsSha256",ProbeFiles.sha(new File(directory,"smaps.txt"))).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{
            if(directory!=null){ProbeFiles.publishResult(directory,result.toString(2)+"\n");}
            String message="Owned coverage "+result.optBoolean("passed")+" "+result.optString("error");
            runOnUiThread(()->view.setText(message));
        }catch(Throwable error){runOnUiThread(()->view.setText("Evidence failed: "+error));}
    }
}
