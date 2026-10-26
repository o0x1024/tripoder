package dev.tripoder.launchcontract;

import android.content.*;
import android.os.Bundle;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One real target and one real supplied result callback, also when both use the same object. */
public final class OrderedResultChecks {
    public interface Checks {void accept(String name,boolean passed)throws Exception;}
    public static void run(Context context,Checks checks)throws Exception{one(context,checks,false);one(context,checks,true);}
    private static void one(Context context,Checks checks,boolean same)throws Exception{
        String pkg=context.getPackageName(),action=pkg+".ORDERED_"+UUID.randomUUID(),label=same?"same-instance":"different-instances";
        CountDownLatch completed=new CountDownLatch(1);AtomicInteger targets=new AtomicInteger(),finals=new AtomicInteger();AtomicBoolean values=new AtomicBoolean(true);
        BroadcastReceiver target=new BroadcastReceiver(){
            @Override public void onReceive(Context received,Intent intent){
                if(same&&getResultCode()==38){finish(this,received,intent);return;}
                targets.incrementAndGet();Bundle extras=getResultExtras(false);
                if(!pkg.equals(received.getPackageName())||!action.equals(intent.getAction())||!pkg.equals(intent.getPackage())||getResultCode()!=17||!"owned-initial".equals(getResultData())||extras==null||!"initial".equals(extras.getString("owned-stage")))values.set(false);
                Bundle changed=new Bundle();changed.putString("owned-stage","target");setResult(38,"owned-target",changed);
            }
            private void finish(BroadcastReceiver receiver,Context received,Intent intent){finals.incrementAndGet();Bundle extras=receiver.getResultExtras(false);if(!pkg.equals(received.getPackageName())||!action.equals(intent.getAction())||!pkg.equals(intent.getPackage())||receiver.getResultCode()!=38||!"owned-target".equals(receiver.getResultData())||extras==null||!"target".equals(extras.getString("owned-stage")))values.set(false);completed.countDown();}
        };
        BroadcastReceiver result=same?target:new BroadcastReceiver(){
            @Override public void onReceive(Context received,Intent intent){finals.incrementAndGet();Bundle extras=getResultExtras(false);if(!pkg.equals(received.getPackageName())||!action.equals(intent.getAction())||!pkg.equals(intent.getPackage())||getResultCode()!=38||!"owned-target".equals(getResultData())||extras==null||!"target".equals(extras.getString("owned-stage")))values.set(false);completed.countDown();}
        };
        context.registerReceiver(target,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{
            Bundle initial=new Bundle();initial.putString("owned-stage","initial");context.sendOrderedBroadcast(new Intent(action).setPackage(pkg),null,result,null,17,"owned-initial",initial);
            checks.accept("ordered-supplied-result-"+label+"-target-and-final-each-once",completed.await(6,TimeUnit.SECONDS)&&targets.get()==1&&finals.get()==1);
            checks.accept("ordered-supplied-result-"+label+"-keeps-result-and-logical-intent",values.get());
        }finally{context.unregisterReceiver(target);}
    }
    private OrderedResultChecks(){}
}
