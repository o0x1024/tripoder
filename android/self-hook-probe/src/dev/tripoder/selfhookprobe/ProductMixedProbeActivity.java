package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.runtime.OwnedMixedBackendChecks;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class ProductMixedProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-product-mixed").start();}
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_MIXED_RUN");
            result.put("schema",1).put("phase","H5-product-mixed-backend").put("runId",run);ProbeEnvironment.load(this,result);
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderownedhooks.so"),audit=new File(getFilesDir(),"mixed-audit-"+run+".txt");String nativeSha=ProbeFiles.sha(library);result.put("ownedNativeSha256",nativeSha);
            result.put("control",OwnedMixedBackendChecks.run(getApplicationInfo().sourceDir,result.getString("apkSha256"),run,library.getCanonicalPath(),nativeSha,audit.getCanonicalPath()));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Mixed product "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Mixed evidence failed: "+error));}
    }
}
