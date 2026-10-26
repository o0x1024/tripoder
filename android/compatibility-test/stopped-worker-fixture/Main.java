package dev.tripoder.stopcontract;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Deliberately stops only this owned worker; no signal is sent to another PID. */
public final class Main extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView text=new TextView(this);text.setText("OWNED WORKER: STOP RPC MUST RETURN UNKNOWN WITHIN ITS DEADLINE");setContentView(text);
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            android.util.Log.i("TripoderStoppedWorkerContract","OWNED_SELF_SIGSTOP");
            try{android.system.Os.kill(android.os.Process.myPid(),android.system.OsConstants.SIGSTOP);}
            catch(android.system.ErrnoException failure){throw new IllegalStateException("OWNED_SELF_STOP_FAILED",failure);}
        },3000);
    }
}
