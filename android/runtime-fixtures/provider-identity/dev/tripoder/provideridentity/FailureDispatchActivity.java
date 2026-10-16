package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.*;

/** One-process failure diagnostics contract, independent of multi-process capacity tests. */
public final class FailureDispatchActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);new Thread(()->{
        JSONObject report=new JSONObject();JSONArray checks=new JSONArray();boolean passed=false;
        try{FailureDispatchChecks.inspect(checks);ReflectionAccessChecks.inspect(checks);PackageWorldChecks.inspect(this,checks);passed=true;}catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{report.put("checks",checks).put("passed",passed).put("completed",true).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());}
        catch(Exception error){throw new IllegalStateException(error);}
        runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});
    },"owned-failure-contract").start();}
}
