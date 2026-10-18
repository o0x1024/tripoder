package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import static dev.tripoder.selfhookprobe.ProbeFiles.*;

public final class NoInlineProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);view.setText("E1 fixed ART experiment");setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"e1-no-inline").start();}
    private void run(String id,TextView view){JSONObject report=new JSONObject();File directory=null;NoInlineProbeChecks checks=null;
        try{
            String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);require(directory.mkdir(),"E1_FRESH_RUN_REQUIRED");
            report.put("schema",1).put("phase","P2-E1-owned-no-inline").put("runId",run).put("artCompilation","jit");
            File observer=ProbeEnvironment.load(this,report);System.load(observer.toString());
            checks=new NoInlineProbeChecks(report);checks.run(this,directory);report.put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        finally{
            try{
                if(checks!=null)checks.finish();
            }catch(Throwable error){try{report.put("passed",false).put("cleanupError",error.toString());}catch(Exception ignored){}}
            try{
                if(directory==null)return;File maps=new File(directory,"maps.txt");write(maps,read("/proc/self/maps"));report.put("mapsSha256",sha(maps));
                publishResult(directory,report.toString(2)+"\n");runOnUiThread(()->view.setText("E1 "+(report.optBoolean("passed")?"PASS":"FAIL")));
            }catch(Exception error){android.util.Log.e("TripoderE1","Evidence failed",error);}
        }
    }
}
