package dev.tripoder.p2;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

/** Test fixture uses its acceptance host's adapter to deliver a physical framework event. */
public final class PackageBroadcastActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);JSONObject report=new JSONObject();try{
        Class<?> adapter=Class.forName("dev.tripoder.runtime.RuntimePackageBroadcastProbe",true,getBaseContext().getClass().getClassLoader());
        report=new JSONObject((String)adapter.getMethod("run").invoke(null));report.put("passed",true);
    }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();
    }
}
