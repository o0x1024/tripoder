package dev.tripoder.p2;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Remains live while the external owned harness tests old physical entry rejection. */
public abstract class EntryFenceActivity extends Activity {
    private int intents;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView label=new TextView(this);label.setText("Tripoder owned entry fence fixture");setContentView(label);publish();main.postDelayed(this::poll,50);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);intents++;publish();}
    private JSONObject value(){try{return new JSONObject().put("pid",android.os.Process.myPid()).put("starts",((App)getApplication()).starts).put("intents",intents).put("component",getClass().getName()).put("taskId",getTaskId());}catch(Exception error){throw new IllegalStateException(error);}}
    private void publish(){try{android.util.AtomicFile file=new android.util.AtomicFile(new File(getFilesDir(),"entry-state.json"));FileOutputStream out=file.startWrite();try{out.write(value().toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception error){file.failWrite(out);throw error;}}catch(Exception error){throw new IllegalStateException(error);}}
    private void poll(){if(isFinishing()||isDestroyed())return;File next=new File(getFilesDir(),"entry-new-intent");if(next.exists()){if(!next.delete())throw new IllegalStateException("OWNED_INTENT_SIGNAL_DELETE_FAILED");startActivity(new Intent(this,getClass()));}if(new File(getFilesDir(),"entry-finish").exists()){setResult(RESULT_OK,new Intent().putExtra("result",value().toString()));finish();}else main.postDelayed(this::poll,50);}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
    public static final class Standard extends EntryFenceActivity{}
    public static final class Top extends EntryFenceActivity{}
}
