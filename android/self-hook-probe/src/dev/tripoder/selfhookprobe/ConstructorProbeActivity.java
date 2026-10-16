package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class ConstructorProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-constructor").start();}
    private void run(String id,TextView view){JSONObject report=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_CONSTRUCTOR_RUN");
            report.put("schema",1).put("phase","H6-managed-constructor").put("runId",run);ProbeEnvironment.load(this,report);
            // The test caller must enter only after the policy; an already
            // compiled test frame cannot establish this execution boundary.
            ManagedHook.initializeProcess();report.put("constructor",OwnedConstructorChecks.run());
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");String text="Constructor "+report.optBoolean("passed")+" "+report.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Constructor evidence failed: "+error));}
    }
}
