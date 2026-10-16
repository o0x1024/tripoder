package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

public final class ManagedAbiProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);view.setPadding(32,64,32,32);setContentView(view);
        String run=getIntent().getStringExtra("runId"),compilation=getIntent().getStringExtra("artCompilation");
        new Thread(()->execute(run,compilation,view),"owned-managed-abi-probe").start();
    }
    private void execute(String id,String compilation,TextView view){
        JSONObject report=new JSONObject();File directory=null;boolean owns=false,loaded=false;
        try{
            String run=UUID.fromString(id).toString();require("jit".equals(compilation)||"aot".equals(compilation)||"cold".equals(compilation),"EXPLICIT_COMPILATION_REQUIRED");
            File base=getExternalFilesDir(null);require(base!=null,"EVIDENCE_DIRECTORY_UNAVAILABLE");
            directory=new File(base,run);require(directory.mkdir(),"RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");owns=true;
            report.put("schema",1).put("phase","H3h-managed-abi").put("runId",run).put("artCompilation",compilation);
            ProbeEnvironment.load(this,report);loaded=true;
            report.put("abi",new ManagedAbiChecks(directory,compilation,getApplicationInfo().sourceDir).run());
            write(new File(directory,"maps.txt"),read("/proc/self/maps"));report.put("mapsSha256",sha(new File(directory,"maps.txt")));
            write(new File(directory,"smaps.txt"),read("/proc/self/smaps"));report.put("smapsSha256",sha(new File(directory,"smaps.txt")));
            report.put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());if(error.getCause()!=null)report.put("cause",error.getCause().toString());}catch(Exception ignored){}}
        try{
            if(loaded){
                // Successful reports share the same paused root snapshot. A
                // second capture could observe another GC during JSON creation.
                report.put("storage",report.has("abi")?report.getJSONObject("abi").getJSONObject("storage"):
                    new JSONObject(dev.tripoder.selfhook.OriginalMethodStorage.report()));
                if(owns&&directory!=null){
                    write(new File(directory,"maps.txt"),read("/proc/self/maps"));report.put("mapsSha256",sha(new File(directory,"maps.txt")));
                    write(new File(directory,"smaps.txt"),read("/proc/self/smaps"));report.put("smapsSha256",sha(new File(directory,"smaps.txt")));
                }
            }
            if(!owns||directory==null){runOnUiThread(()->view.setText("ABI probe requires a fresh UUID"));return;}
            publishResult(directory,report.toString(2)+"\n");
            String message="Owned managed ABI "+(report.optBoolean("passed")?"PASS":"FAIL")+"\n"+report.optString("error",id);runOnUiThread(()->view.setText(message));
        }catch(Throwable error){String message="ABI evidence failed: "+error;runOnUiThread(()->view.setText(message));}
    }
}
