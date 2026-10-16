package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

public final class CapacityProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView view=new TextView(this);setContentView(view);
        String run=getIntent().getStringExtra("runId");new Thread(()->run(run,view),"owned-hook-capacity").start();
    }
    private void run(String id,TextView view){
        JSONObject result=new JSONObject();File directory=null;
        try {
            String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);
            ProbeFiles.require(directory.mkdir(),"FRESH_CAPACITY_RUN");
            result.put("schema",1).put("phase","H4-process-capacity").put("runId",run);ProbeEnvironment.load(this,result);
            result.put("capacity",CapacityChecks.run());
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {
            if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");
            String text="Owned capacity "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));
        }catch(Throwable error){runOnUiThread(()->view.setText("Capacity evidence failed: "+error));}
    }
}
