package dev.tripoder.componentrestartfixture;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** A live, bound, distinct process must also be terminated by a package state change. */
public final class Remote extends Service {
    private volatile boolean observing=true;
    @Override public void onCreate(){
        super.onCreate();new Thread(()->{
            while(observing){
                var processes=getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();
                getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(this,Remote.class));
                boolean own=false;for(var process:processes)if(process.pid==android.os.Process.myPid())own=true;
                if(!own)throw new AssertionError("LIVE_SELF_MISSING_FROM_REAL_PROCESS_VIEW");
                android.os.SystemClock.sleep(5);
            }
        },"owned-process-observer").start();
    }
    @Override public void onDestroy(){observing=false;super.onDestroy();}
    private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper()){
        @Override public void handleMessage(Message request){try{Message reply=Message.obtain();reply.arg1=android.os.Process.myPid();request.replyTo.send(reply);}catch(RemoteException failure){throw new IllegalStateException(failure);}}
    });
    @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
}
