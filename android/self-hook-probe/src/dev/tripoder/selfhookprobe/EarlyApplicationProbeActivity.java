package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.runtime.OwnedEarlyApplicationChecks;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
public final class EarlyApplicationProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);JSONObject report=new JSONObject();File directory=null;
        try{String run=UUID.fromString(getIntent().getStringExtra("runId")).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_EARLY_APPLICATION_RUN");
            report.put("schema",1).put("phase","H6-early-application-identity").put("runId",run);ProbeEnvironment.load(this,report);
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread worker=new Thread(()->{try{OwnedEarlyApplicationChecks.prepare(getApplication());}catch(Throwable error){failure.set(error);}},"owned-early-bootstrap");
            worker.start();worker.join();if(failure.get()!=null)throw failure.get();
            report.put("application",OwnedEarlyApplicationChecks.run());File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString()).put("cause",String.valueOf(error.getCause()));}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");view.setText("Early application "+report.optBoolean("passed")+" "+report.optString("error"));}
        catch(Throwable error){view.setText("Early application evidence failed: "+error);}
    }
}
