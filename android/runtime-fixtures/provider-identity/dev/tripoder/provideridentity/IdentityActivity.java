package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.net.Uri;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class IdentityActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView text=new TextView(this);text.setText("Tripoder · Provider identity observation");setContentView(text);new Thread(()->{
        JSONObject report=new JSONObject();
        try{
            report.put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());
            if("true".equals(getIntent().getStringExtra("baseline"))){
                android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.GET_PROVIDERS|android.content.pm.PackageManager.GET_META_DATA|android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
                JSONArray providers=new JSONArray();for(var provider:info.providers)providers.put(new JSONObject().put("name",provider.name).put("authority",provider.authority).put("label",provider.metaData==null?"missing":provider.metaData.getString("identity.label")));report.put("packageProviders",providers);
            }
            String first=getIntent().getBooleanExtra("secondFirst",false)?"dev.tripoder.identity.second":"dev.tripoder.identity.first";
            report.put("first",observe(first)).put("repeatBeforeOther",observe(first)).put("second",observe("dev.tripoder.identity.second")).put("firstAgain",observe("dev.tripoder.identity.first"));
            var pm=getPackageManager();int metadata=android.content.pm.PackageManager.GET_META_DATA;
            var component=new android.content.ComponentName(getPackageName(),IdentityProvider.class.getName());
            report.put("componentLabel",pm.getProviderInfo(component,metadata).metaData.getString("identity.label"));
            report.put("firstResolvedLabel",pm.resolveContentProvider("dev.tripoder.identity.first",metadata).metaData.getString("identity.label"));
            report.put("secondResolvedLabel",pm.resolveContentProvider("dev.tripoder.identity.second",metadata).metaData.getString("identity.label"));
            var copy=pm.resolveContentProvider("dev.tripoder.identity.first",metadata);copy.metaData.putString("identity.label","tampered");
            report.put("metadataCopyIsolated",pm.resolveContentProvider("dev.tripoder.identity.first",metadata).metaData.getString("identity.label").equals("first"));
            report.put("metadataOmittedWithoutFlag",pm.getProviderInfo(component,0).metaData==null);
            if("true".equals(getIntent().getStringExtra("baseline"))){
                String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("BASELINE_RUN_REQUIRED");
                try(var out=new FileOutputStream(new File(getExternalFilesDir(null),"result-"+run+".json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            }
            runOnUiThread(()->{text.setText(report.toString());setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));if(!"true".equals(getIntent().getStringExtra("baseline")))finish();});
        }catch(Exception error){throw new IllegalStateException("PROVIDER_IDENTITY_FIXTURE_FAILED",error);}
    },"owned-provider-identity").start();}
    private JSONObject observe(String authority)throws Exception{
        JSONObject out=new JSONObject();try{
            Bundle b=getContentResolver().call(Uri.parse("content://"+authority),"identity",null,null);
            if(b==null)throw new IllegalStateException("PROVIDER_RETURNED_NULL");for(String key:b.keySet())out.put(key,b.get(key));out.put("returned",true);
        }catch(RuntimeException error){out.put("returned",false).put("errorClass",error.getClass().getName()).put("error",String.valueOf(error.getMessage())).put("stack",android.util.Log.getStackTraceString(error));}return out;
    }
}
