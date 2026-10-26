package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;

/** Observe the real protected-action policy; never switch users or emit their events. */
final class ProtectedReceiverChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        String switched="android.intent.action.USER_SWITCHED",background="android.intent.action.USER_BACKGROUND";
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            if(settings==null||settings.getLocalContentProvider()==null)throw new AssertionError("OWNED_SETTINGS_PROVIDER_REQUIRED");
            Class<?> framework=Class.forName("dev.tripoder.selfhook.ComponentFrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            var query=framework.getMethod("protectedBroadcast",String.class);
            check.test("user-lifecycle-actions-are-protected-by-real-platform",Boolean.TRUE.equals(query.invoke(null,switched))&&Boolean.TRUE.equals(query.invoke(null,background)));
        }
        IntentFilter filter=new IntentFilter(switched);filter.addAction(background);
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){}};
        Intent sticky=activity.registerReceiver(receiver,filter,"android.permission.MANAGE_USERS",null);
        try{check.test("protected-user-action-observer-accepts-zero-flags-and-sender-permission",sticky==null);}
        finally{activity.unregisterReceiver(receiver);}
        boolean retired=false;try{activity.unregisterReceiver(receiver);}catch(IllegalArgumentException expected){retired=true;}
        check.test("protected-user-action-observer-unregisters-one-owned-record",retired);
        // The platform protection above is proven before these negative calls.
        // A guest must be rejected locally; no real user lifecycle event is sent.
        for(String action:new String[]{switched,background}){
            boolean denied=false;try{activity.sendBroadcast(new Intent(action));}catch(UnsupportedOperationException expected){denied="SYSTEM_BROADCAST_POLICY_UNSUPPORTED".equals(expected.getMessage());}
            check.test("guest-cannot-emit-protected-user-action-"+action,denied);
        }
    }
    private ProtectedReceiverChecks(){}
}
