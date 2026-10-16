package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.tripoder.selfhook.FrameworkAccess;

/** Separate cold-start entry: no hook slots were configured before framework lookup. */
public final class FrameworkProbeActivity extends Activity {
    private static native String inspect(Application host);
    private static native String inspectFixed(Application host,java.lang.reflect.Field[] fields,Object[] methods);
    private static native String status();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view=new TextView(this);view.setPadding(32,64,32,32);setContentView(view);
        JSONObject report=new JSONObject();
        File directory=null;
        boolean loaded=false,owns=false;
        try {
            String runId=UUID.fromString(getIntent().getStringExtra("runId")).toString();
            File base=getExternalFilesDir(null);require(base!=null,"EVIDENCE_DIRECTORY_UNAVAILABLE");
            directory=new File(base,runId);require(directory.mkdir(),"RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");owns=true;
            boolean selfAccess=getIntent().getBooleanExtra("selfAccess",false);
            report.put("schema",1).put("phase",selfAccess?"H3f-fixed-access":"H3f-lookup").put("runId",runId);
            ProbeEnvironment.load(this,report);loaded=true;
            String observation;
            if(selfAccess){FrameworkAccess.Members members=FrameworkAccess.prepare();
                report.put("reflectedMethods",members.reflectedMethods);
                observation=inspectFixed(getApplication(),members.fields,members.methods);
            }else observation=inspect(getApplication());
            JSONObject framework=new JSONObject(observation);report.put("framework",framework);
            String maps=read("/proc/self/maps");write(new File(directory,"maps.txt"),maps);
            report.put("mapsSha256",sha(new File(directory,"maps.txt")));
            JSONArray forbidden=new JSONArray();
            for(String line:maps.split("\n")){String lower=line.toLowerCase(Locale.ROOT);
                if(lower.contains("frida")||lower.contains("gadget")||lower.contains("libgum"))forbidden.put(line);}
            report.put("forbiddenMappings",forbidden);
            require(forbidden.length()==0&&framework.getBoolean("passed"),"FRAMEWORK_LOOKUP_FAILED");
            report.put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {
            if(loaded)report.put("nativeStatus",new JSONObject(status())).put("frameworkAccess",new JSONObject(FrameworkAccess.statusNative()));
            if(!owns||directory==null){view.setText("Framework probe requires a fresh runId directory");return;}
            publishResult(directory,report.toString(2)+"\n");view.setText("Framework lookup "+(report.optBoolean("passed")?"PASS":"FAIL")+"\n"+report.optString("runId"));
        }catch(Exception error){view.setText("Framework evidence failed: "+error);}
    }
}
