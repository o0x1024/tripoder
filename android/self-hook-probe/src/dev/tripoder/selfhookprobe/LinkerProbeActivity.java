package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
public final class LinkerProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");boolean death=getIntent().getBooleanExtra("activeUnload",false);new Thread(()->run(id,death,view),"owned-linker").start();}
    private void run(String id,boolean death,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_LINKER_RUN");
            result.put("schema",1).put("phase","H5-native-module-lifetime").put("runId",run);File fixture=ProbeEnvironment.load(this,result);
            File files=new File(getFilesDir(),"linker-"+run);ProbeFiles.require(files.mkdir(),"FRESH_OWNED_LIBRARIES");
            File good=new File(files,"libowned.so"),bad=new File(files,"invalid.so"),late=new File(files,"late-failure.so");
            Files.copy(fixture.toPath(),good.toPath());ProbeFiles.write(bad,"owned invalid ELF\n");
            Files.copy(new File(getApplicationInfo().nativeLibraryDir,"libtripoderlinkerfailure.so").toPath(),late.toPath());
            result.put("ownedLibrarySha256",ProbeFiles.sha(good)).put("lateLibrarySha256",ProbeFiles.sha(late));
            if(death){result.put("deathRequested",true);ProbeFiles.write(new File(directory,"before-active-unload.json"),result.toString(2)+"\n");}
            JSONObject lifetime=new JSONObject(LinkerProbe.run(good.getCanonicalPath(),bad.getCanonicalPath(),late.getCanonicalPath(),directory.getCanonicalPath(),death));result.put("lifetime",lifetime);
            ProbeFiles.require(lifetime.getBoolean("passed"),"LINKER_LIFETIME_FAILED");
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Native modules "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}
        catch(Throwable error){runOnUiThread(()->view.setText("Module evidence failed: "+error));}
    }
}
