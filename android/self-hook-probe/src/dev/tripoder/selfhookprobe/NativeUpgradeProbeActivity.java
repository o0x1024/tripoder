package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class NativeUpgradeProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String id=getIntent().getStringExtra("runId");boolean death=getIntent().getBooleanExtra("commitFailure",false);new Thread(()->run(id,death,view),"native-upgrade").start();}
    private void run(String id,boolean death,TextView view){JSONObject result=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);
            ProbeFiles.require(directory.mkdir(),"FRESH_UPGRADE_RUN");result.put("schema",1).put("phase","native-core-upgrade").put("runId",run);
            ProbeEnvironment.load(this,result);File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderownedhooks.so");
            result.put("ownedNativeSha256",ProbeFiles.sha(library));
            if(death){result.put("phase","native-core-commit-death").put("deathRequested",true);
                ProbeFiles.write(new File(directory,"before-active-unload.json"),result.toString(2)+"\n");
                NativeUpgradeProbe.commitDeath(library.getCanonicalPath(),directory.getCanonicalPath());throw new IllegalStateException("COMMIT_FAILURE_RETURNED");}
            JSONObject upgrade=new JSONObject(NativeUpgradeProbe.run(library.getCanonicalPath()));
            result.put("upgrade",upgrade);ProbeFiles.require(upgrade.getBoolean("passed"),"UPGRADE_FAILED:"+upgrade.optString("firstFailure"));
            result.put("javaResources",NativeUpgradeJavaChecks.run(library.getCanonicalPath(),new File(getFilesDir(),"upgrade-audit-"+run).getCanonicalPath()));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text=result.toString();runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
