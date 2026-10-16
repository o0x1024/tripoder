package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import static dev.tripoder.selfhookprobe.ScriptFixturePlatform.map;

/** Separate owned-script integration entry. Never loads a product/third-party target. */
public final class ScriptControlProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);view.setPadding(32,64,32,32);setContentView(view);
        String id=getIntent().getStringExtra("runId"),compilation=getIntent().getStringExtra("artCompilation");boolean owned=getIntent().getBooleanExtra("ownedScriptScope",false);
        new Thread(()->run(id,compilation,owned,view),"owned-script-control-probe").start();
    }
    private void run(String id,String compilation,boolean owned,TextView view){
        JSONObject report=new JSONObject();File directory=null;boolean owns=false,loaded=false;
        try{
            String run=UUID.fromString(id).toString();require("jit".equals(compilation)||"aot".equals(compilation),"EXPLICIT_COMPILATION_REQUIRED");
            File base=getExternalFilesDir(null);require(base!=null,"EVIDENCE_DIRECTORY_UNAVAILABLE");directory=new File(base,run);require(directory.mkdir(),"RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");owns=true;
            report.put("schema",1).put("phase","H5-owned-script-control").put("runId",run).put("artCompilation",compilation)
                .put("ownedScopeRequested",owned).put("productBackend",false).put("fullCallerCoverage",false).put("authorityApprovalPath",false);
            require(owned,"OWNED_SCRIPT_SCOPE_REQUIRED");require("dev.tripoder.selfhookprobe".equals(getPackageName()),"OWNED_SCRIPT_PACKAGE_REQUIRED");
            ProbeEnvironment.load(this,report);loaded=true;
            Map<String,Object> identity=map("space","owned-self-hook-probe","installGeneration",report.getJSONObject("build").getString("buildId"),
                "descriptorSha256",report.getJSONObject("build").getString("sourceDigest"),"processGeneration",run,"pid",(double)android.os.Process.myPid(),
                "target",map("packageName",getPackageName(),"versionCode",Long.toString(getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode()),"apkSha256",report.getString("apkSha256")));
            ScriptFixturePlatform platform=new ScriptFixturePlatform(identity,getApplicationInfo().sourceDir,directory,owned);
            report.put("script",new ScriptControlChecks(platform,directory,compilation,run).run()).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());if(error.getCause()!=null)report.put("cause",error.getCause().toString());}catch(Exception ignored){}}
        try{
            if(loaded)report.put("storage",new JSONObject(dev.tripoder.selfhook.OriginalMethodStorage.report()));
            if(!owns||directory==null){runOnUiThread(()->view.setText("Script probe requires a fresh UUID"));return;}
            if(loaded){write(new File(directory,"maps.txt"),read("/proc/self/maps"));report.put("mapsSha256",sha(new File(directory,"maps.txt")));
                write(new File(directory,"smaps.txt"),read("/proc/self/smaps"));report.put("smapsSha256",sha(new File(directory,"smaps.txt")));}
            publishResult(directory,report.toString(2)+"\n");
            String message="Owned script control "+(report.optBoolean("passed")?"PASS":"FAIL")+"\n"+report.optString("error",id);runOnUiThread(()->view.setText(message));
        }catch(Throwable error){String message="Script evidence failed: "+error;runOnUiThread(()->view.setText(message));}
    }
}
