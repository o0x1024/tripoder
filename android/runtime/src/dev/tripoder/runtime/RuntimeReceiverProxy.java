package dev.tripoder.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import dev.tripoder.selfhook.ComponentFrameworkAccess;

/** Android owns this dispatcher; the guest receives the very same PendingResult.
 * No guest class is modified when a receiver is registered or instantiated. */
final class RuntimeReceiverProxy extends BroadcastReceiver {
    interface Delivery {
        Intent before(BroadcastReceiver receiver,Intent physical,Object pending);
        Context context(Context physical);
        void after(BroadcastReceiver receiver,boolean success);
    }
    private final BroadcastReceiver guest;
    private final Delivery delivery;
    RuntimeReceiverProxy(BroadcastReceiver guest,Delivery delivery){
        this.guest=java.util.Objects.requireNonNull(guest);
        this.delivery=java.util.Objects.requireNonNull(delivery);
    }
    @Override public void onReceive(Context context,Intent physical){
        Object token=ComponentFrameworkAccess.pendingResult(this);
        ComponentFrameworkAccess.pendingResult(guest,token);
        boolean entered=false,success=false;Throwable failure=null;
        try{
            Intent logical=delivery.before(guest,physical,token);
            if(logical==null)return;
            entered=true;
            guest.onReceive(delivery.context(context),logical);
            success=true;
        }catch(RuntimeException|Error error){failure=error;throw error;
        }finally{
            // The platform leaves a finished PendingResult installed on the receiver: measured on this device
            // (control-b8c6edfb45981cd5, platform-accepts-set-result-data-after-delivery=accepted), a receiver
            // reference the guest kept still answers setResultData 250ms after the delivery returned. Clearing it
            // here created a state the platform never creates, and 微博's PushService died in its own pool thread
            // on exactly that (BroadcastReceiver.checkSynchronousHint, 2026-11-13). A goAsync hand-off is the only
            // state that clears it, and the guest does that itself.
            ComponentFrameworkAccess.pendingResult(this,ComponentFrameworkAccess.pendingResult(guest));
            if(entered)try{delivery.after(guest,success);}catch(RuntimeException|Error acknowledgement){
                if(failure==null)throw acknowledgement;
                if(failure!=acknowledgement)failure.addSuppressed(acknowledgement);
            }
        }
    }
}
