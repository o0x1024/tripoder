package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.io.File;
import org.json.JSONObject;

/** The test controller closes admission only after this real Activity has been created. */
public final class ClosingActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private long deadline;
    @Override public void onCreate(Bundle state){super.onCreate(state);deadline=SystemClock.elapsedRealtime()+15000;main.post(this::awaitFence);}
    private void awaitFence(){
        if(!new File(getFilesDir(),"closing-gate").exists()){
            if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("CLOSING_GATE_TIMEOUT");
            main.postDelayed(this::awaitFence,25);return;
        }
        boolean rejected=false;
        try{startActivity(new Intent(this,ResultFlows.Cancel.class));}
        catch(RuntimeException expected){for(Throwable error=expected;error!=null;error=error.getCause())rejected|=String.valueOf(error.getMessage()).contains("PROCESS_CLOSING");}
        try{setResult(RESULT_OK,new Intent().putExtra("result",new JSONObject().put("newAdmissionRejected",rejected).put("pid",android.os.Process.myPid()).toString()));finish();}
        catch(Exception error){throw new IllegalStateException("CLOSING_COMPLETION_FAILED",error);}
    }
}
