package dev.tripoder.runtime;

import android.app.Activity;
import android.os.Bundle;

/** Target of a guest's PendingIntent.getActivity token. It has no UI: the system may start it from the background on the sender's behalf, and the app then has a visible activity,
 *  which is what lets the guest process start its own activity. */
public final class RuntimePendingActivityIngress extends Activity {
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        android.content.Intent physical=getIntent();android.content.Context host=getApplicationContext();
        Thread worker=new Thread(()->{
            try{RuntimePendingDispatch.run(host,physical,"activity");}
            catch(Exception error){android.util.Log.e("TripoderReceiver","PENDING_ACTIVITY_DISPATCH_FAILED",error);}
            finally{runOnUiThread(this::finish);}
        },"pending-activity-ingress");
        worker.setDaemon(true);worker.start();
    }
}
