package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.OwnedProductFrameworkChecks;
import dev.tripoder.selfhook.FrameworkAccess;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

public final class ProductFrameworkProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-product-framework").start();}
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_PRODUCT_FRAMEWORK_RUN");
            result.put("schema",1).put("phase","H4-product-framework").put("runId",run);ProbeEnvironment.load(this,result);
            result.put("framework",OwnedProductFrameworkChecks.run(getApplication(),run)).put("registry",new JSONObject(FrameworkAccess.statusNative()));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString()).put("errorStack",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Product framework "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Product framework evidence failed: "+error));}
    }
}
