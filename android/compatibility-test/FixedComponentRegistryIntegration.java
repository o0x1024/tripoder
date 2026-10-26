package dev.tripoder.runtime;

import android.app.Instrumentation;
import android.os.Bundle;
import dev.tripoder.selfhook.FrameworkAccess;
import dev.tripoder.selfhook.OwnedProductFrameworkChecks;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;

/** Runs the existing physical fixed-adapter oracle in a fresh QA authority process. */
public final class FixedComponentRegistryIntegration extends Instrumentation {
    private String run;
    @Override public void onCreate(Bundle arguments){run=UUID.fromString(arguments.getString("run")).toString();start();}
    @Override public void onStart(){
        JSONObject result=new JSONObject();Bundle output=new Bundle();
        try(var progress=new FixedRegistryProgress(getTargetContext(),run)){
            progress.stage("LOADING_NATIVE");
            System.loadLibrary("tripoderselfhook");
            progress.stage("FRAMEWORK_CHECKS");
            result.put("run",run).put("framework",OwnedProductFrameworkChecks.run(getTargetContext().getApplicationContext(),run));
            progress.stage("INSTRUMENTATION_ENTRY_CHECKS");
            var entry=new java.util.concurrent.atomic.AtomicReference<JSONObject>();
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            runOnMainSync(()->{try{entry.set(new InstrumentationEntryChecks().run());}catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw failure.get();
            result.put("instrumentationEntry",entry.get()).put("registry",new JSONObject(FrameworkAccess.statusNative())).put("passed",true);
            progress.stage("COMPLETE");
        }catch(Throwable error){
            try{result.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}
        }
        try{
            result.put("dataCleared",false).put("productRoutingAccepted",false);
            File evidence=new File(getTargetContext().getExternalFilesDir(null),"fixed-registry-"+run+".json");
            Files.writeString(evidence.toPath(),result.toString(2)+"\n",StandardCharsets.UTF_8);
            output.putString("path",evidence.getAbsolutePath());output.putString("report",result.toString());
        }catch(Exception error){output.putString("evidenceError",error.toString());}
        finish(result.optBoolean("passed")?-1:0,output);
    }
}
