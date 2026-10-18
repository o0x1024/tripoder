package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;

/** Focused public-API routing regression; service-policy acceptance stays in Providers.Main. */
public final class ProviderLookupActivity extends Activity {
    private final JSONArray checks=new JSONArray();
    @Override public void onCreate(Bundle state){super.onCreate(state);new Thread(this::run,"provider-index-client").start();}
    private void check(String name,boolean passed,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new AssertionError(name);}
    private void run(){JSONObject report=new JSONObject();boolean passed=false;long begin=SystemClock.elapsedRealtime();try{
        ContentResolver resolver=getContentResolver();String local;
        try(Cursor c=resolver.query(Providers.uri(Providers.LOCAL),null,null,null,null)){check("indexed-local-provider-real-callback",c!=null&&c.moveToFirst()&&c.getInt(0)==android.os.Process.myPid(),android.os.Process.myPid());local=c.getString(c.getColumnIndexOrThrow("instance"));}
        try(Cursor c=resolver.query(Providers.uri(Providers.ALIAS),null,null,null,null)){check("indexed-alias-keeps-one-instance",c!=null&&c.moveToFirst()&&local.equals(c.getString(c.getColumnIndexOrThrow("instance"))),local);}
        Bundle remote=resolver.call(Providers.REMOTE,"snapshot",null,null);
        check("indexed-remote-provider-real-process",remote!=null&&remote.getInt("pid")!=android.os.Process.myPid(),remote==null?"null":remote.toString());
        try(Cursor c=resolver.query(Uri.parse("content://dev.tripoder.peer.public/items"),null,null,null,null)){check("indexed-cross-package-provider-real-callback",c!=null&&c.moveToFirst(),String.valueOf(c));}
        boolean denied=false;try{resolver.query(Uri.parse("content://dev.tripoder.peer.private/items"),null,null,null,null);}catch(SecurityException expected){denied=true;}
        check("indexed-nonexported-provider-remains-denied",denied,denied);
        check("indexed-absent-provider-is-null",resolver.getType(Providers.uri("dev.tripoder.p2.absent"))==null,true);
        Bundle again=resolver.call(Providers.REMOTE,"snapshot",null,null);
        check("indexed-repeat-keeps-process-and-instance",remote.getInt("pid")==again.getInt("pid")&&remote.getString("instance").equals(again.getString("instance")),again.toString());
        report.put("data",remote.getString("data")).put("remotePid",remote.getInt("pid")).put("pid",android.os.Process.myPid());passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
    finally{try{report.put("passed",passed).put("checks",checks).put("lookupElapsedMs",SystemClock.elapsedRealtime()-begin);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(JSONException error){throw new IllegalStateException(error);}}}
}
