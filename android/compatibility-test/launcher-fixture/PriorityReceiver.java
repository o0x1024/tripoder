package dev.tripoder.launchcontract;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.CountDownLatch;

/** One owned package-targeted manifest broadcast, observed without a dynamic receiver. */
public final class PriorityReceiver extends BroadcastReceiver {
    static final CountDownLatch received=new CountDownLatch(1);
    @Override public void onReceive(Context context,Intent intent){
        if("owned.launch.PRIORITY_RECEIVER".equals(intent.getAction())&&"dev.tripoder.launchcontract".equals(intent.getPackage()))received.countDown();
    }
}
