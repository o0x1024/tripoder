package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Target of a guest's PendingIntent.getService / getForegroundService token. */
public final class RuntimePendingServiceIngress extends Service {
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent physical,int flags,int startId){
        android.content.Context host=getApplicationContext();
        Thread worker=new Thread(()->{
            try{if(physical!=null)RuntimePendingDispatch.run(host,physical,"service");}
            catch(Exception error){android.util.Log.e("TripoderReceiver","PENDING_SERVICE_DISPATCH_FAILED",error);}
            finally{stopSelf(startId);}
        },"pending-service-ingress");
        worker.setDaemon(true);worker.start();
        return START_NOT_STICKY;
    }
}
