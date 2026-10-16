package dev.tripoder.runtime;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.Context;

/** Implement only with pinned framework members. This interface does not install any Hook. */
interface RuntimeSelfHookFrameworkCalls {
    default boolean stopServiceToken(android.app.Service service,android.content.ComponentName physicalComponent,int startId)throws Exception {
        return dev.tripoder.selfhook.ServiceTokenAccess.stop(service,physicalComponent,startId);
    }
    Object receiverPendingResult(BroadcastReceiver receiver);
    boolean protectedBroadcast(String action);
    long bindServiceFlags(Context.BindServiceFlags flags);
    Object providerHandle(ContentProvider provider);
    RuntimeSelfHookSelection selectionArguments(ContentProviderOperation operation);
}
