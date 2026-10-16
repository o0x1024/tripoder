package dev.tripoder.provideridentity;

import android.content.*;
import android.os.Bundle;
import android.net.Uri;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

final class ReceiverPermissionChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        String pkg=context.getPackageName(),allowed=pkg+".EXPORTED_ALLOWED",privateAction=pkg+".EXPORTED_PRIVATE";
        CountDownLatch own=new CountDownLatch(1),peer=new CountDownLatch(1);AtomicInteger denied=new AtomicInteger();
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if("peer".equals(i.getStringExtra("sender")))peer.countDown();}};
        BroadcastReceiver privateReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if("peer".equals(i.getStringExtra("sender")))denied.incrementAndGet();else own.countDown();}};
        context.registerReceiver(receiver,new IntentFilter(allowed),pkg+".permission.MESSAGE",null,Context.RECEIVER_EXPORTED);
        context.registerReceiver(privateReceiver,new IntentFilter(privateAction),pkg+".permission.PRIVATE",null,Context.RECEIVER_EXPORTED);
        int peerPid=0;
        try{
            BroadcastReceiver declaredOnly=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){throw new AssertionError("DECLARED_ONLY_NOT_SENT");}};
            context.registerReceiver(declaredOnly,new IntentFilter(pkg+".DECLARED_ONLY"),pkg+".permission.DECLARED_ONLY",null,Context.RECEIVER_EXPORTED);context.unregisterReceiver(declaredOnly);
            check(checks,"exported-signature-or-system-restriction-without-owner-request",true);
            context.sendBroadcast(new Intent(privateAction).setPackage(pkg));
            check(checks,"exported-signature-receiver-own-delivery",own.await(10,TimeUnit.SECONDS));
            if(!baseline){
                Bundle extras=new Bundle();extras.putString("target",pkg);Uri uri=Uri.parse("content://dev.tripoder.peer.public/owned");
                Bundle response=context.getContentResolver().call(uri,"owned-broadcast",privateAction,extras);peerPid=response.getInt("pid");
                context.getContentResolver().call(uri,"owned-broadcast",allowed,extras);
                check(checks,"exported-signature-receiver-same-signer-requested-permission",peer.await(10,TimeUnit.SECONDS)&&peerPid!=android.os.Process.myPid());
                // Authority evidence also asserts that only one of these two sends created a delivery.
                check(checks,"exported-signature-receiver-missing-request-not-delivered",denied.get()==0);
            }
        }finally{context.unregisterReceiver(privateReceiver);context.unregisterReceiver(receiver);}
        return new JSONObject().put("peerPid",peerPid).put("deniedCallbacks",denied.get());
    }
    private ReceiverPermissionChecks(){}
}
