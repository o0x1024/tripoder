package dev.tripoder.provideridentity;

import android.content.*;
import android.os.BatteryManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Reads Android's real sticky battery snapshot; never changes or simulates device state. */
final class StickyBroadcastChecks {
    static void inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent query=context.registerReceiver(null,filter);check(checks,"sticky-battery-real-query",valid(query));
        Intent copy=new Intent(query);copy.putExtra(BatteryManager.EXTRA_LEVEL,-1000);Intent again=context.registerReceiver(null,filter);check(checks,"sticky-query-and-defensive-copy-preserve-real-values",valid(query)&&valid(again));
        CountDownLatch callback=new CountDownLatch(1);AtomicBoolean valid=new AtomicBoolean(),initial=new AtomicBoolean(),finished=new AtomicBoolean();
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context delivered,Intent intent){
            valid.set(valid(intent)&&delivered.getPackageName().equals(context.getPackageName()));initial.set(isInitialStickyBroadcast());PendingResult result=goAsync();new Thread(()->{result.finish();finished.set(true);callback.countDown();},"sticky-actual-finish").start();
        }};
        Intent returned=context.registerReceiver(receiver,filter);
        try{check(checks,"sticky-registration-returns-real-snapshot",valid(returned));check(checks,"sticky-actual-initial-callback-and-platform-async-finish",callback.await(8,TimeUnit.SECONDS)&&valid.get()&&initial.get()&&finished.get());}
        finally{context.unregisterReceiver(receiver);}
        if(!baseline){boolean denied=false;try{context.sendBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED));}catch(UnsupportedOperationException expected){denied=expected.getMessage().contains("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");}check(checks,"sticky-protected-event-cannot-be-sent-by-guest",denied);}
    }
    private static boolean valid(Intent intent){if(intent==null||!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))return false;int scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1),level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);return scale>0&&level>=0&&level<=scale&&intent.hasExtra(BatteryManager.EXTRA_STATUS)&&intent.hasExtra(BatteryManager.EXTRA_PLUGGED);}
    private static void check(JSONArray checks,String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private StickyBroadcastChecks(){}
}
