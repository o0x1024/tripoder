package dev.tripoder.provideridentity;

import android.telephony.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Observe real modem state; never synthesize a telephony event. */
final class SignalChecks {
    static final class Signal extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        final CountDownLatch arrived=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();volatile SignalStrength value;volatile String thread;
        @Override public void onSignalStrengthsChanged(SignalStrength signal){value=signal;thread=Thread.currentThread().getName();calls.incrementAndGet();arrived.countDown();}
    }
    static final class Mixed extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener,TelephonyCallback.CallStateListener {
        @Override public void onSignalStrengthsChanged(SignalStrength signal){}
        @Override public void onCallStateChanged(int state){}
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(TelephonyManager manager,boolean baseline,JSONArray checks)throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor(r->new Thread(r,"owned-signal-callback"));Signal signal=new Signal();
        try{
            manager.registerTelephonyCallback(executor,signal);
            try{
                check(checks,"telephony-real-signal-callback",signal.arrived.await(10,TimeUnit.SECONDS)&&signal.value!=null&&signal.value.getLevel()>=0&&signal.value.getLevel()<=4&&signal.value.getTimestampMillis()>0&&"owned-signal-callback".equals(signal.thread));
                if(!baseline){boolean duplicate=false;try{manager.registerTelephonyCallback(executor,signal);}catch(IllegalStateException expected){duplicate=expected.getMessage().contains("TELEPHONY_CALLBACK_ALREADY_REGISTERED");}check(checks,"telephony-duplicate-callback-rejected",duplicate);}
            }finally{manager.unregisterTelephonyCallback(signal);}
            if(!baseline){
                boolean unknown=false;try{manager.unregisterTelephonyCallback(signal);}catch(IllegalArgumentException expected){unknown=expected.getMessage().contains("TELEPHONY_CALLBACK_NOT_REGISTERED");}check(checks,"telephony-repeat-unregister-rejected",unknown);
                boolean mixed=false;try{manager.registerTelephonyCallback(executor,new Mixed());}catch(UnsupportedOperationException expected){mixed=expected.getMessage().contains("TELEPHONY_CALLBACK_INTERFACES_UNSUPPORTED");}check(checks,"telephony-ungranted-listener-set-rejected",mixed);
                boolean location=false;try{manager.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_FINE,executor,new Signal());}catch(UnsupportedOperationException expected){location=expected.getMessage().contains("TELEPHONY_LOCATION_CALLBACK_UNSUPPORTED");}check(checks,"telephony-location-callback-rejected",location);
                BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();Signal cancelled=new Signal();manager.registerTelephonyCallback(queued::add,cancelled);
                Runnable delivery;try{delivery=queued.poll(10,TimeUnit.SECONDS);check(checks,"telephony-real-callback-queued-before-cancel",delivery!=null);}finally{manager.unregisterTelephonyCallback(cancelled);}
                delivery.run();check(checks,"telephony-cancel-suppresses-queued-guest-callback",cancelled.calls.get()==0);
            }
            return new JSONObject().put("level",signal.value.getLevel()).put("modemTimestamp",signal.value.getTimestampMillis()).put("thread",signal.thread).put("observedCallbacks",signal.calls.get());
        }finally{executor.shutdownNow();}
    }
    private SignalChecks(){}
}
