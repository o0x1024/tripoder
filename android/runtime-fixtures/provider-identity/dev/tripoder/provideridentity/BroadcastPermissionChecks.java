package dev.tripoder.provideridentity;

import android.content.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

final class BroadcastPermissionChecks {
    static void inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        String action=context.getPackageName()+".SEND_PERMISSION_CONTRACT";CountDownLatch allowed=new CountDownLatch(1),barrier=new CountDownLatch(1);AtomicInteger denied=new AtomicInteger();
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){switch(intent.getStringExtra("phase")){case "allowed":allowed.countDown();break;case "denied":denied.incrementAndGet();break;case "barrier":barrier.countDown();break;default:throw new AssertionError("UNEXPECTED_BROADCAST");}}};
        AtomicInteger emptyDeliveries=new AtomicInteger();BroadcastReceiver empty=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){emptyDeliveries.incrementAndGet();}};
        Intent emptySticky=context.registerReceiver(empty,new IntentFilter());check(checks,"empty-filter-registration-zero-flags-not-sticky",emptySticky==null);
        BroadcastReceiver battery=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){}};IntentFilter batteryFilter=new IntentFilter(Intent.ACTION_BATTERY_OKAY);batteryFilter.addAction(Intent.ACTION_BATTERY_LOW);batteryFilter.addAction(Intent.ACTION_POWER_CONNECTED);batteryFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);batteryFilter.addAction(android.os.BatteryManager.ACTION_CHARGING);batteryFilter.addAction(android.os.BatteryManager.ACTION_DISCHARGING);
        Intent batterySticky=context.registerReceiver(battery,batteryFilter);context.unregisterReceiver(battery);check(checks,"battery-protected-events-register-zero-flags-not-sticky",batterySticky==null);
        context.registerReceiver(receiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{
            context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()).putExtra("phase","allowed"),context.getPackageName()+".permission.MESSAGE");
            check(checks,"send-permission-requested-signed-recipient-receives",allowed.await(8,TimeUnit.SECONDS));
            context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()).putExtra("phase","denied"),context.getPackageName()+".permission.DECLARED_ONLY");
            context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()).putExtra("phase","barrier"));
            check(checks,"send-permission-unrestricted-broadcast-still-delivers",barrier.await(8,TimeUnit.SECONDS));
            check(checks,"send-permission-declared-without-request-not-delivered",denied.get()==0);
            check(checks,"empty-filter-no-action-is-not-a-wildcard",emptyDeliveries.get()==0);
            if(!baseline){boolean rejected=false;try{context.sendBroadcast(new Intent(action).setPackage(context.getPackageName()).putExtra("phase","denied"),"dev.tripoder.not-owned.permission");}catch(RuntimeException error){for(Throwable cause=error;cause!=null;cause=cause.getCause())rejected|=String.valueOf(cause.getMessage()).contains("RECEIVER_PERMISSION_NOT_OWNED");}check(checks,"send-permission-unowned-restriction-explicitly-rejected",rejected);}
        }finally{context.unregisterReceiver(receiver);context.unregisterReceiver(empty);}
        boolean emptyUnregistered=false;try{context.unregisterReceiver(empty);}catch(IllegalArgumentException expected){emptyUnregistered=true;}check(checks,"empty-filter-real-unregister-and-repeat-rejection",emptyUnregistered);
    }
    private static void check(JSONArray checks,String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private BroadcastPermissionChecks(){}
}
