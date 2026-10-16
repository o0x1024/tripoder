package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.runtime.OwnedManagerInstallerChecks;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class ManagerInstallerProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-manager-installer").start();}
    private void run(String id,TextView view){JSONObject report=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_MANAGER_INSTALLER_RUN");
            report.put("schema",1).put("phase","H6-fixed-manager-installer").put("runId",run);ProbeEnvironment.load(this,report);
            report.put("installer",OwnedManagerInstallerChecks.run(getIntent().getBooleanExtra("allManagers",false)));File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));
            report.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,report.toString(2)+"\n");String text="Manager installer "+report.optBoolean("passed")+" "+report.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Manager installer evidence failed: "+error));}
    }
}
