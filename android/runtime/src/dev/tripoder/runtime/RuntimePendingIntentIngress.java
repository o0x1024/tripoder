package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Android starts this physical receiver in the authority process. Implicit
 * recipients are resolved when the token fires, using the existing broadcast
 * ledger and real Android dynamic receiver deliveries. */
public final class RuntimePendingIntentIngress extends BroadcastReceiver {
    // Leave 500 ms for final framework completion inside the unchanged 10 s
    // end-to-end acceptance budget, including cold worker construction.
    private static final long DELIVERY_BUDGET_MS=9500;
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,
        new SynchronousQueue<>(),task->new Thread(task,"pending-broadcast-ingress"),new ThreadPoolExecutor.AbortPolicy());
    @Override public void onReceive(Context context,Intent physical){
        Context host=context.getApplicationContext();boolean ordered=isOrderedBroadcast();int code=getResultCode();String data=getResultData();Bundle supplied=getResultExtras(false);
        long deadline=SystemClock.elapsedRealtime()+DELIVERY_BUDGET_MS;PendingResult result=goAsync();
        String version=null;
        try{
            version=RuntimePendingIntentPayload.version(host,physical);
            if(version==null||!new ComponentName(host,getClass()).equals(physical.getComponent()))throw new SecurityException("PENDING_INGRESS_COMPONENT");
            String selected=version;Bundle extras=copyResult(supplied);
            WORKERS.execute(()->deliver(host,new Intent(physical),selected,ordered,code,data,extras,deadline,result));
        }catch(RuntimeException error){try{failed(result,String.valueOf(version),error,ordered);}finally{result.finish();}}
    }
    private static void deliver(Context host,Intent physical,String version,boolean ordered,int code,String data,Bundle extras,long deadline,PendingResult result){
        RuntimeBroker broker=null;ArrayList<Bundle> deliveries=null;
        try{
            broker=RuntimeAuthorityOwner.get(host);PendingIntentLedger.Version value;
            synchronized(broker){
                try{value=broker.pendingIntents.get(version);}catch(SecurityException unknown){android.util.Log.i("TripoderReceiver","PENDING_VERSION_RETIRED:"+version);return;}
                var current=broker.apks.row(value.space,value.pkg);
                if(value.fenced||value.consumed||!value.current||!getName().equals(value.stub())||current==null||!"COMMITTED".equals(current.state)||!value.install.equals(current.generation)||!value.data.equals(current.dataId)){
                    android.util.Log.i("TripoderReceiver","PENDING_VERSION_RETIRED:"+version);return;
                }
                if(value.hookGeneration!=null){var live=broker.findProcess(value.space,value.pkg,value.process);if(live==null||!value.hookGeneration.equals(live.allocation.session.processGeneration)){android.util.Log.i("TripoderReceiver","PENDING_HOOK_RESTART_REQUIRES_EXPLICIT_START:"+version);return;}}
                deliveries=broker.broadcastBroker.pending(value,RuntimePendingIntentPayload.delivered(value.logical(),physical),ordered);
            }
            for(Bundle delivery:deliveries){
                if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("PENDING_INGRESS_DEADLINE");
                Intent routed=new Intent(delivery.getString("action")).setPackage(host.getPackageName()).setFlags(value.logical().getFlags()).putExtra("tripoder.delivery",delivery.getString("ticket"));
                if(delivery.containsKey("stub"))routed.setComponent(new ComponentName(host,delivery.getString("stub")));
                if(!ordered){host.sendBroadcast(routed);continue;}
                CountDownLatch finished=new CountDownLatch(1);Object[] observed=new Object[3];RuntimeException[] callbackError={null};
                BroadcastReceiver completion=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
                    try{observed[0]=getResultCode();observed[1]=getResultData();observed[2]=copyResult(getResultExtras(false));}catch(RuntimeException error){callbackError[0]=error;}finally{finished.countDown();}
                }};
                host.sendOrderedBroadcast(routed,null,completion,new Handler(Looper.getMainLooper()),code,data,extras);
                if(!finished.await(Math.max(0,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS)||observed[0]==null)throw new IllegalStateException("PENDING_ORDERED_RESULT_UNCONFIRMED");
                if(callbackError[0]!=null)throw callbackError[0];
                result.setResultCode((Integer)observed[0]);result.setResultData((String)observed[1]);result.setResultExtras((Bundle)observed[2]);
            }
            android.util.Log.i("TripoderReceiver","PENDING_IMPLICIT_DISPATCHED:version="+version+":targets="+deliveries.size()+":ordered="+ordered);
        }catch(Exception error){
            if(broker!=null&&deliveries!=null)synchronized(broker){for(Bundle delivery:deliveries)broker.broadcasts.externalSendUnknown(delivery.getString("ticket"),broker.contract);}
            failed(result,version,error,ordered);
        }finally{result.finish();}
    }
    private static String getName(){return RuntimePendingIntentIngress.class.getName();}
    private static Bundle copyResult(Bundle source){
        if(source==null)return null;
        // Android may supply opaque framework objects in its result extras.
        // Preserve the platform parcel without traversing or rewriting values;
        // this is not the guest-controlled Intent/provider input codec.
        Parcel parcel=Parcel.obtain();try{
            parcel.writeBundle(source);if(parcel.hasFileDescriptors()||parcel.dataSize()>32768)throw new IllegalArgumentException("PENDING_RESULT_PARCEL_UNSUPPORTED");
            parcel.setDataPosition(0);Bundle copy=parcel.readBundle(RuntimePendingIntentIngress.class.getClassLoader());
            if(parcel.dataAvail()!=0)throw new IllegalArgumentException("PENDING_RESULT_TRAILING_DATA");return copy;
        }finally{parcel.recycle();}
    }
    private static void failed(PendingResult result,String version,Exception error,boolean ordered){
        // This is an explicit adapter failure result, never a fabricated guest
        // success or receiver-completion receipt. The durable delivery stays UNKNOWN.
        if(ordered){result.setResultCode(-1000);result.setResultData("TRIPODER_PENDING_DELIVERY_FAILED");result.setResultExtras(null);}
        android.util.Log.e("TripoderReceiver","PENDING_INGRESS_FAILED:"+version,error);
    }
}
