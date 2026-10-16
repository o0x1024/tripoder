package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.NativeModules;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
public final class ModuleCatalogProbeActivity extends Activity {
    private int checks;private void check(boolean ok,String name){checks++;ProbeFiles.require(ok,name);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");boolean empty=getIntent().getBooleanExtra("emptyCatalog",false);new Thread(()->run(id,empty,view),"owned-module-catalog").start();}
    private void run(String id,boolean empty,TextView view){JSONObject result=new JSONObject();File directory=null;
        try {String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_CATALOG_RUN");result.put("schema",1).put("phase","H5-module-catalog").put("runId",run);ProbeEnvironment.load(this,result);
            boolean rejected=false;try{NativeModules.initialize(new String[4097]);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"CATALOG_BUDGET_BEFORE_NATIVE_SIDE_EFFECT");
            File library=new File(getApplicationInfo().nativeLibraryDir,"libtripoderownedhooks.so");String[] paths=new String[empty?0:4096];if(!empty){paths[0]=library.getCanonicalPath();for(int i=1;i<paths.length;i++)paths[i]=new File(getFilesDir(),"owned-unloaded-"+i+".so").getCanonicalPath();}
            long begin=android.os.SystemClock.elapsedRealtimeNanos();NativeModules.initialize(paths);long elapsed=android.os.SystemClock.elapsedRealtimeNanos()-begin;check(true,"REAL_LINKER_NOTIFIER_INITIALIZED");
            check(NativeModules.events().length==0,"NO_FAKE_INITIAL_EVENTS");
            check(NativeJniCaller.open(library.getCanonicalPath(),new File(getFilesDir(),"catalog-audit-"+run).getCanonicalPath()),"OWNED_LIBRARY_LOADED");
            if(empty){
                rejected=false;try{NativeModules.snapshot(0);}catch(IllegalStateException expected){rejected=true;}check(rejected,"EMPTY_CATALOG_HAS_NO_TARGET_SLOT");check(NativeModules.events().length==0,"UNAPPROVED_LIBRARY_NOT_ADMITTED");
                check(NativeJniCaller.close(),"EMPTY_CATALOG_DOES_NOT_PIN_LIBRARY");check(NativeModules.events().length==0,"EMPTY_CATALOG_STAYS_EMPTY");
            }else{
                check(NativeModules.snapshot(0).live,"FIRST_CATALOG_ENTRY");check(!NativeModules.snapshot(4095).live&&NativeModules.snapshot(4095).generation==0,"LAST_CATALOG_ENTRY");
                check(NativeModules.events().length>0,"ACTUAL_APPROVED_ADD_EVENT");check(NativeJniCaller.close(),"CATALOG_DOES_NOT_PIN");check(!NativeModules.snapshot(0).live,"ACTUAL_CATALOG_REMOVAL");
                rejected=false;try{NativeModules.snapshot(4096);}catch(IllegalStateException expected){rejected=true;}check(rejected,"CATALOG_INDEX_BOUNDARY");
            }
            result.put("catalog",new JSONObject().put("checks",checks).put("paths",paths.length).put("initializationNs",elapsed).put("realNotifier",true).put("ownedSyntheticCatalog",true).put("productAccepted",false));
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps)).put("passed",true);
        }catch(Throwable error){try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text="Native catalog "+result.optBoolean("passed")+" "+result.optString("error");runOnUiThread(()->view.setText(text));}catch(Throwable error){runOnUiThread(()->view.setText("Catalog evidence failed: "+error));}
    }
}
