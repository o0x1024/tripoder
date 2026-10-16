package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
/** Owned compiler-vs-assembly ABI comparison; no target discovery or Hook install. */
public final class ScalarProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");
        new Thread(()->run(id,view),"owned-native-scalar").start();
    }
    private void run(String id,TextView view){
        JSONObject report=new JSONObject();File directory=null;
        try{
            String run=UUID.fromString(id).toString();File root=getExternalFilesDir(null);ProbeFiles.require(root!=null,"OWNED_FILES_REQUIRED");
            directory=new File(root,run);ProbeFiles.require(directory.mkdir(),"FRESH_RUN_REQUIRED");
            report.put("schema",1).put("phase","H2e-native-scalar-abi").put("runId",run);ProbeEnvironment.load(this,report);
            JSONObject scalar=new JSONObject(NativeScalarProbe.run());report.put("scalar",scalar);
            ProbeFiles.require(scalar.getBoolean("passed"),"SCALAR_ABI_FAILED");
            ProbeFiles.write(new File(directory,"maps.txt"),ProbeFiles.read("/proc/self/maps"));
            report.put("mapsSha256",ProbeFiles.sha(new File(directory,"maps.txt"))).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{
            if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");
            String text="Owned scalar "+report.optBoolean("passed")+" "+report.optString("error");runOnUiThread(()->view.setText(text));
        }catch(Throwable error){runOnUiThread(()->view.setText("Evidence failed: "+error));}
    }
}
