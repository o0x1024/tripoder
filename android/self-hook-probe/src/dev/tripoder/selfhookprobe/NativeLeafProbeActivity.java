package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class NativeLeafProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-native-leaf").start();}
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_LEAF_RUN");result.put("schema",1).put("phase","H5-native-leaf-entry").put("runId",run);ProbeEnvironment.load(this,result);
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderleafhooks.so");result.put("leafNativeSha256",ProbeFiles.sha(library));JSONObject leaf=new JSONObject(NativeLeafProbe.run(library.getCanonicalPath()));result.put("leaf",leaf);ProbeFiles.require(leaf.getBoolean("passed"),"LEAF_ENTRY_FAILED");
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Native leaves "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}catch(Throwable error){runOnUiThread(()->view.setText("Leaf evidence failed: "+error));}
    }
}
