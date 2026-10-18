package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class NativeRangeProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"native-ranges").start();}
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_RANGE_RUN");
            result.put("schema",1).put("phase","native-core-ranges").put("runId",run);ProbeEnvironment.load(this,result);
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderrangehooks.so");result.put("rangeNativeSha256",ProbeFiles.sha(library));
            JSONObject ranges=new JSONObject(NativeRangeProbe.run(library.getCanonicalPath()));result.put("ranges",ranges);
            ProbeFiles.require(ranges.getBoolean("passed"),"RANGE_FAILED:"+ranges);
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text=result.toString();runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
