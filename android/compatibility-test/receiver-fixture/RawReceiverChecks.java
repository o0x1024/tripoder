package dev.tripoder.closurefixture;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real public ContextImpl receiver entries, dispatch loopers and shared unregistration. */
final class RawReceiverChecks {
    private static Context raw(Context context)throws Exception{
        Class<?> exact=Class.forName("android.app.ContextImpl",false,null);Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(context!=null&&seen.size()<16&&seen.add(context)){if(context.getClass()==exact)return context;if(!(context instanceof ContextWrapper))break;context=((ContextWrapper)context).getBaseContext();}
        throw new AssertionError("OWNED_RAW_CONTEXT_REQUIRED");
    }
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        Context source=raw(activity.getApplication());HandlerThread thread=new HandlerThread("owned-raw-receiver");thread.start();Handler handler=new Handler(thread.getLooper());
        try{
            for(int shape=0;shape<4;shape++){
                CountDownLatch delivered=new CountDownLatch(1);AtomicBoolean correct=new AtomicBoolean();Looper expected=shape<2?Looper.getMainLooper():thread.getLooper();
                BroadcastReceiver listener=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){correct.set(context.getPackageName().equals(activity.getPackageName())&&Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())&&Looper.myLooper()==expected);delivered.countDown();}};
                IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);Intent sticky;
                switch(shape){
                    case 0:sticky=source.registerReceiver(listener,filter);break;
                    case 1:sticky=source.registerReceiver(listener,filter,Context.RECEIVER_NOT_EXPORTED);break;
                    case 2:sticky=source.registerReceiver(listener,filter,null,handler);break;
                    case 3:sticky=source.registerReceiver(listener,filter,null,handler,Context.RECEIVER_NOT_EXPORTED);break;
                    default:throw new AssertionError("OWNED_RECEIVER_SHAPE");
                }
                try{check.test("raw-register-receiver-shape-"+shape+"-has-real-sticky-and-correct-looper",sticky!=null&&Intent.ACTION_BATTERY_CHANGED.equals(sticky.getAction())&&delivered.await(6,TimeUnit.SECONDS)&&correct.get());}
                finally{source.unregisterReceiver(listener);}
                boolean retired=false;try{source.unregisterReceiver(listener);}catch(IllegalArgumentException expectedFailure){retired=true;}
                check.test("raw-unregister-shape-"+shape+"-shares-existing-registration-owner",retired);
            }
            CountDownLatch received=new CountDownLatch(1);String action=activity.getPackageName()+".RAW_"+UUID.randomUUID();
            BroadcastReceiver listener=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(action.equals(intent.getAction()))received.countDown();}};
            source.registerReceiver(listener,new IntentFilter(action),null,handler,Context.RECEIVER_NOT_EXPORTED);
            try{activity.sendBroadcast(new Intent(action).setPackage(activity.getPackageName()));check.test("raw-register-and-wrapped-send-share-one-logical-delivery",received.await(6,TimeUnit.SECONDS));}
            finally{activity.unregisterReceiver(listener);}
        }finally{thread.quitSafely();}
    }
    private RawReceiverChecks(){}
}
