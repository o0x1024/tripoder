package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;

/** Observe availability only. Never suspend/unsuspend any package or emit its real events. */
final class SelfPackageReceiverChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        String[] actions={Intent.ACTION_MY_PACKAGE_SUSPENDED,Intent.ACTION_MY_PACKAGE_UNSUSPENDED};
        try(ContentProviderClient settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            if(settings==null||settings.getLocalContentProvider()==null)throw new AssertionError("OWNED_SETTINGS_PROVIDER_REQUIRED");
            Class<?> access=Class.forName("dev.tripoder.selfhook.ComponentFrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            var query=access.getMethod("protectedBroadcast",String.class);
            check.test("self-package-suspension-actions-are-real-platform-protected",Boolean.TRUE.equals(query.invoke(null,actions[0]))&&Boolean.TRUE.equals(query.invoke(null,actions[1])));
        }
        IntentFilter filter=new IntentFilter(actions[0]);filter.addAction(actions[1]);
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){}};
        activity.registerReceiver(receiver,filter);activity.unregisterReceiver(receiver);
        check.test("real-self-package-observer-registers-with-original-zero-flags-and-unregisters",true);
        for(String action:actions){
            boolean denied=false;try{activity.sendBroadcast(new Intent(action).setPackage(activity.getPackageName()));}catch(UnsupportedOperationException expected){denied="SYSTEM_BROADCAST_POLICY_UNSUPPORTED".equals(expected.getMessage());}
            check.test("guest-cannot-emit-self-package-suspension-"+action,denied);
        }
    }
    private SelfPackageReceiverChecks(){}
}
