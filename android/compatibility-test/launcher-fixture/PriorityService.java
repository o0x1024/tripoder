package dev.tripoder.launchcontract;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Declared for resource-backed Service priority metadata; never started by the fixture. */
public final class PriorityService extends Service {
    @Override public IBinder onBind(Intent intent){return null;}
}
