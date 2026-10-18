package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class NativeBenchmarkProbeActivity extends Activity {
    private TextView view;private boolean started;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);view=new TextView(this);setContentView(view);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);
        if(!focused||started)return;started=true;
        // Finish the foreground task-profile transition before choosing a CPU.
        view.postDelayed(()->{String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"native-benchmark").start();},1000);
    }
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_BENCHMARK_RUN");
            result.put("schema",1).put("phase","native-core-benchmark").put("runId",run);ProbeEnvironment.load(this,result);
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderownedhooks.so");String digest=ProbeFiles.sha(library);result.put("ownedNativeSha256",digest);
            if(getIntent().getBooleanExtra("affinityDiagnostic",false))result.put("affinity",new JSONObject(NativeBenchmark.affinityReport()));
            else result.put("benchmark",dev.tripoder.runtime.OwnedNativeBenchmarkChecks.run(library.getCanonicalPath(),digest,result.getString("apkSha256"),run,directory,getIntent().getIntExtra("benchmarkCpu",5),getIntent().getBooleanExtra("benchmarkDiagnostics",false)));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text=result.toString();runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
