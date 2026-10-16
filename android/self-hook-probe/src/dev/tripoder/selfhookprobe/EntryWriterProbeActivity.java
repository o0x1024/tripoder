package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

/** Real fixed ART writer in this owned probe process only. */
public final class EntryWriterProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String run=getIntent().getStringExtra("runId"),kind=getIntent().getStringExtra("artCompilation");
        new Thread(()->run(run,kind,view),"owned-art-writer").start();
    }
    private void run(String id,String kind,TextView view){
        JSONObject result=new JSONObject();File directory=null;
        try{
            ProbeFiles.require(kind.equals("jit")||kind.equals("aot"),"WRITER_COMPILATION_MODE");String run=UUID.fromString(id).toString();
            directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_WRITER_RUN");
            result.put("schema",1).put("phase","H3k-fixed-art-writer").put("runId",run).put("artCompilation",kind);ProbeEnvironment.load(this,result);
            result.put("writer",EntryWriterChecks.run(directory,kind));
            ProbeFiles.write(new File(directory,"maps.txt"),ProbeFiles.read("/proc/self/maps"));
            ProbeFiles.write(new File(directory,"smaps.txt"),ProbeFiles.read("/proc/self/smaps"));
            result.put("mapsSha256",ProbeFiles.sha(new File(directory,"maps.txt"))).put("smapsSha256",ProbeFiles.sha(new File(directory,"smaps.txt"))).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{
            if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");
            String message="Owned ART writer "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(message));
        }catch(Throwable error){runOnUiThread(()->view.setText("Writer evidence failed: "+error));}
    }
}
