package dev.tripoder.provideridentity;

import android.content.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class SystemBroadcastChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        String own=context.getPackageName()+".OWNED_MESSAGE",permission=context.getPackageName()+".permission.MESSAGE";
        CountDownLatch tick=new CountDownLatch(1),privateMessage=new CountDownLatch(1);AtomicBoolean identity=new AtomicBoolean(true),asyncFinished=new AtomicBoolean();AtomicLong observedAt=new AtomicLong();
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context delivered,Intent intent){
            identity.set(identity.get()&&delivered.getPackageName().equals(context.getPackageName()));
            if(own.equals(intent.getAction()))privateMessage.countDown();
            else if(Intent.ACTION_TIME_TICK.equals(intent.getAction())){
                PendingResult pending=goAsync();new Thread(()->{observedAt.set(System.currentTimeMillis());pending.finish();asyncFinished.set(true);tick.countDown();},"owned-system-finish").start();
            }else throw new AssertionError("UNEXPECTED_SYSTEM_ACTION");
        }};
        IntentFilter mixed=new IntentFilter(Intent.ACTION_TIME_TICK);mixed.addAction(own);
        Intent sticky=context.registerReceiver(receiver,mixed,permission,null,Context.RECEIVER_NOT_EXPORTED);
        try{
            check(checks,"system-receiver-mixed-registration-not-sticky",sticky==null);
            context.sendBroadcast(new Intent(own).setPackage(context.getPackageName()));
            check(checks,"system-receiver-own-permission-private-action",privateMessage.await(10,TimeUnit.SECONDS));
            check(checks,"system-receiver-real-time-tick-async-finished",tick.await(70,TimeUnit.SECONDS)&&asyncFinished.get()&&observedAt.get()>0);
            check(checks,"system-receiver-guest-context-preserved",identity.get());
        }finally{context.unregisterReceiver(receiver);}
        BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){}};
        context.registerReceiver(screen,new IntentFilter(Intent.ACTION_SCREEN_OFF));context.unregisterReceiver(screen);
        check(checks,"system-receiver-protected-only-zero-flags",true);
        for(String action:new String[]{PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED,PowerManager.ACTION_POWER_SAVE_MODE_CHANGED}){
            Intent initial=context.registerReceiver(screen,new IntentFilter(action));context.unregisterReceiver(screen);
            check(checks,"system-receiver-power-registration-not-sticky:"+action,initial==null);
            if(!baseline){boolean denied=false;try{context.sendBroadcast(new Intent(action));}catch(UnsupportedOperationException expected){denied=expected.getMessage().contains("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");}
                check(checks,"system-receiver-power-spoof-rejected:"+action,denied);}
        }
        IntentFilter media=new IntentFilter(Intent.ACTION_MEDIA_SHARED);media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);media.addDataScheme("file");
        context.registerReceiver(screen,media);context.unregisterReceiver(screen);
        check(checks,"system-receiver-media-root-filter-registration-only",true);
        IntentFilter mixedMedia=new IntentFilter(Intent.ACTION_MEDIA_SHARED);mixedMedia.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screen,mixedMedia);context.unregisterReceiver(screen);
        check(checks,"system-receiver-mixed-media-no-scheme-registration",true);
        android.net.Uri root=android.net.Uri.parse("file:///storage/owned-observation");
        check(checks,"system-receiver-native-filter-data-semantics",mixedMedia.match(Intent.ACTION_MEDIA_SHARED,null,"file",root,null,"OwnedFilter")<0&&media.match(Intent.ACTION_MEDIA_SHARED,null,"file",root,null,"OwnedFilter")>=0);
        if(!baseline){
            boolean spoof=false;try{context.sendBroadcast(new Intent(Intent.ACTION_TIME_TICK));}catch(UnsupportedOperationException expected){spoof=expected.getMessage().contains("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");}
            check(checks,"system-receiver-guest-cannot-send-system-action",spoof);
            boolean permissionRejected=false;try{context.registerReceiver(screen,new IntentFilter(own),"dev.tripoder.other.permission.MESSAGE",null,Context.RECEIVER_NOT_EXPORTED);}catch(IllegalStateException expected){permissionRejected=android.util.Log.getStackTraceString(expected).contains("RECEIVER_PERMISSION_NOT_OWNED");}
            check(checks,"system-receiver-foreign-permission-rejected",permissionRejected);
            boolean unregistered=false;try{context.unregisterReceiver(receiver);}catch(IllegalArgumentException expected){unregistered=true;}
            check(checks,"system-receiver-repeat-unregister-rejected",unregistered);
            JSONObject concurrent=ReceiverConcurrencyChecks.run(context);
            checks.put(new JSONObject().put("name","receiver-concurrent-registration-and-callback-no-lock-inversion").put("passed",concurrent.getBoolean("passed")).put("observed",concurrent));
            if(!concurrent.getBoolean("passed"))throw new AssertionError("RECEIVER_CONCURRENT_CALLBACK_FAILED");
        }
        return new JSONObject().put("pid",android.os.Process.myPid()).put("timeTickAt",observedAt.get()).put("asyncFinished",asyncFinished.get()).put("contextIdentity",identity.get());
    }
    private SystemBroadcastChecks(){}
}
