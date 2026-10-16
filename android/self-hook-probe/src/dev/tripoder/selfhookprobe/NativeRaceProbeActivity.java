package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class NativeRaceProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");boolean death=getIntent().getBooleanExtra("activeUnload",false);new Thread(()->run(id,death,view),"owned-native-races").start();}
    private void run(String id,boolean death,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_NATIVE_RACE_RUN");
            result.put("schema",1).put("phase","H5-native-export-races").put("runId",run);ProbeEnvironment.load(this,result);
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderownedhooks.so"),audit=new File(getFilesDir(),"native-race-audit-"+run+".txt");
            result.put("ownedNativeSha256",ProbeFiles.sha(library));
            if(death){result.put("deathRequested",true);ProbeFiles.write(new File(directory,"before-active-unload.json"),result.toString(2)+"\n");}
            JSONObject race=new JSONObject(NativeRaceProbe.run(library.getCanonicalPath(),audit.getCanonicalPath(),directory.getCanonicalPath(),death));result.put("race",race);
            ProbeFiles.require(race.getBoolean("passed"),"NATIVE_RACE_FAILED");
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Native races "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Race evidence failed: "+error));}
    }
}
