package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.OwnedServiceTokenChecks;
import dev.tripoder.selfhook.FrameworkAccess;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

public final class ServiceTokenProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView view=new TextView(this);setContentView(view);JSONObject result=new JSONObject();File directory=null;
        try {
            String run=UUID.fromString(getIntent().getStringExtra("runId")).toString();directory=new File(getExternalFilesDir(null),run);
            ProbeFiles.require(directory.mkdir(),"FRESH_SERVICE_TOKEN_RUN");
            result.put("schema",1).put("phase","H4-service-token").put("runId",run);ProbeEnvironment.load(this,result);
            result.put("service",OwnedServiceTokenChecks.run()).put("frameworkAccess",new JSONObject(FrameworkAccess.statusNative()));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{
            if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");
            view.setText("Service token "+result.optBoolean("passed")+" "+result.optString("error"));
        }catch(Exception error){view.setText("Service token evidence failed: "+error);}
    }
}
