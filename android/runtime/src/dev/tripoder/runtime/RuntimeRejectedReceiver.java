package dev.tripoder.runtime;

import android.content.*;

/** A retired physical delivery ends through Android's normal receiver lifecycle. */
final class RuntimeRejectedReceiver extends BroadcastReceiver {
    private final String reason;
    RuntimeRejectedReceiver(String reason){if(!java.util.Set.of("BROADCAST_RESOURCE_RETIRED","PENDING_VERSION_RETIRED","PROCESS_CLOSING").contains(reason))throw new SecurityException("RECEIVER_REJECTION_INVALID");this.reason=reason;}
    @Override public void onReceive(Context context,Intent intent){
        android.util.Log.i("TripoderReceiver",reason+";pid="+android.os.Process.myPid()+";enginePresent="+(RuntimeEngine.current!=null));
        // No guest callback, no fabricated PendingResult, no guest completion ACK.
    }
}
