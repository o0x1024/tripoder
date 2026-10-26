package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;

/** Observe policy + register only. No Bluetooth toggling, pairing or audio changes. */
final class BluetoothObserverChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        String audio="android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED",connection="android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            if(settings==null||settings.getLocalContentProvider()==null)throw new AssertionError("OWNED_SETTINGS_PROVIDER_REQUIRED");
            Class<?> access=Class.forName("dev.tripoder.selfhook.ComponentFrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            var query=access.getMethod("protectedBroadcast",String.class);
            check.test("headset-audio-and-connection-actions-are-real-platform-protected",Boolean.TRUE.equals(query.invoke(null,audio))&&Boolean.TRUE.equals(query.invoke(null,connection)));
        }
        IntentFilter filter=new IntentFilter(audio);filter.addAction(connection);
        BroadcastReceiver listener=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){}};
        activity.registerReceiver(listener,filter);
        activity.unregisterReceiver(listener);
        check.test("headset-observer-real-zero-flags-registration-and-unregistration",true);
    }
    private BluetoothObserverChecks(){}
}
