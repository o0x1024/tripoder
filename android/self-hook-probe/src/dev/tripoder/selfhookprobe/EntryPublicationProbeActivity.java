package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

/** Owned Native page and immutable near ELF only; no ART writer is patched. */
public final class EntryPublicationProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String run=getIntent().getStringExtra("runId");new Thread(()->run(run,view),"owned-entry-publication").start();
    }
    private void run(String id,TextView view){
        JSONObject result=new JSONObject();File directory=null;
        try{
            String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);
            ProbeFiles.require(directory.mkdir(),"FRESH_ENTRY_RUN");
            result.put("schema",1).put("phase","H3k-owned-entry-publication").put("runId",run);ProbeEnvironment.load(this,result);
            result.put("entry",EntryOwnerEvidence.run(directory));
            ProbeFiles.write(new File(directory,"maps.txt"),ProbeFiles.read("/proc/self/maps"));
            ProbeFiles.write(new File(directory,"smaps.txt"),ProbeFiles.read("/proc/self/smaps"));
            result.put("mapsSha256",ProbeFiles.sha(new File(directory,"maps.txt"))).put("smapsSha256",ProbeFiles.sha(new File(directory,"smaps.txt"))).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{
            if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");
            String status="Owned entry publication "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(status));
        }catch(Throwable error){runOnUiThread(()->view.setText("Entry evidence failed: "+error));}
    }
}
