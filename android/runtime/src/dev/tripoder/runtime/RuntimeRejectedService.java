package dev.tripoder.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** A system recreation cannot retry guest code while recovery is fenced. */
public final class RuntimeRejectedService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int id){stopSelf(id);return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
}
