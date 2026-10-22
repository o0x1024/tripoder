package dev.tripoder.runtime;

import android.content.Context;

/** Service and bootstrap provider share exactly one persistent writer in :authority. */
final class RuntimeAuthorityOwner {
    private static RuntimeBroker broker;
    static synchronized RuntimeBroker get(Context context){
        RuntimeRecoveryGate.require();
        if(!android.app.Application.getProcessName().equals(context.getPackageName()+":authority"))throw new IllegalStateException("AUTHORITY_PROCESS_REQUIRED");
        if(broker==null){long begin=android.os.SystemClock.elapsedRealtime();broker=new RuntimeBroker(context.getApplicationContext());android.util.Log.i("TripoderBoot","BROKER_READY ms="+(android.os.SystemClock.elapsedRealtime()-begin));}return broker;
    }
}
