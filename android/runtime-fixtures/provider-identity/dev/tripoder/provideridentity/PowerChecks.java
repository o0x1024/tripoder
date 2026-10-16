package dev.tripoder.provideridentity;

import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class PowerChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        PowerManager manager=context.getSystemService(PowerManager.class);
        check(checks,"power-manager-stable",manager!=null&&manager==context.getSystemService(Context.POWER_SERVICE));
        int current=manager.getCurrentThermalStatus();check(checks,"power-real-thermal-status-range",valid(current));
        ExecutorService executor=Executors.newSingleThreadExecutor(r->new Thread(r,"owned-thermal-executor"));
        CountDownLatch initial=new CountDownLatch(1);AtomicInteger observed=new AtomicInteger(-1);AtomicReference<String> callbackThread=new AtomicReference<>();
        PowerManager.OnThermalStatusChangedListener listener=value->{observed.set(value);callbackThread.set(Thread.currentThread().getName());initial.countDown();};
        boolean registered=false;
        try{
            manager.addThermalStatusListener(executor,listener);registered=true;
            check(checks,"power-real-initial-thermal-callback-on-requested-executor",initial.await(3,TimeUnit.SECONDS)&&valid(observed.get())&&"owned-thermal-executor".equals(callbackThread.get()));
            boolean duplicate=false;try{manager.addThermalStatusListener(executor,listener);}catch(IllegalArgumentException expected){duplicate=true;}
            check(checks,"power-duplicate-thermal-listener-rejected",duplicate);
            manager.removeThermalStatusListener(listener);registered=false;
            if(!baseline){
                boolean missing=false,wake=false,mutation=false,other=false;
                try{manager.removeThermalStatusListener(listener);}catch(IllegalArgumentException expected){missing=expected.getMessage().contains("THERMAL_LISTENER_NOT_REGISTERED");}
                try{manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"owned-not-granted");}catch(UnsupportedOperationException expected){wake=expected.getMessage().contains("POWER_CAPABILITY_UNSUPPORTED");}
                try{manager.reboot(null);}catch(UnsupportedOperationException expected){mutation=expected.getMessage().contains("POWER_CAPABILITY_UNSUPPORTED");}
                try{manager.isIgnoringBatteryOptimizations("dev.tripoder.not-this-guest");}catch(SecurityException expected){other=expected.getMessage().contains("POWER_PACKAGE_NOT_GRANTED");}
                check(checks,"power-unregistered-listener-and-power-control-denied",missing&&wake&&mutation&&other);
                BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();AtomicInteger delivered=new AtomicInteger();PowerManager.OnThermalStatusChangedListener queued=value->delivered.incrementAndGet();
                manager.addThermalStatusListener(queue::add,queued);
                try{Runnable event=queue.poll(3,TimeUnit.SECONDS);check(checks,"power-queued-callback-is-real-platform-event",event!=null);manager.removeThermalStatusListener(queued);event.run();for(Runnable more:queue)more.run();check(checks,"power-remove-prevents-queued-callback-admission",delivered.get()==0);}
                catch(Throwable error){try{manager.removeThermalStatusListener(queued);}catch(IllegalArgumentException alreadyRemoved){}throw error;}
            }
            JSONObject headroom=headroom(manager,executor,checks);
            if(baseline){CountDownLatch onMain=new CountDownLatch(1);AtomicBoolean main=new AtomicBoolean();PowerManager.OnThermalStatusChangedListener defaultListener=value->{main.set(Looper.myLooper()==Looper.getMainLooper());onMain.countDown();};manager.addThermalStatusListener(defaultListener);try{check(checks,"power-default-listener-native-main-executor",onMain.await(3,TimeUnit.SECONDS)&&main.get());}finally{manager.removeThermalStatusListener(defaultListener);}}
            return new JSONObject().put("deviceWide",true).put("thermalStatus",current).put("initialCallbackStatus",observed.get()).put("headroom",headroom).put("interactive",manager.isInteractive()).put("screenOn",manager.isScreenOn()).put("powerSave",manager.isPowerSaveMode()).put("deviceIdle",manager.isDeviceIdleMode()).put("deviceLightIdle",manager.isDeviceLightIdleMode()).put("ignoringOptimizationForPhysicalEngine",manager.isIgnoringBatteryOptimizations(context.getPackageName()));
        }finally{if(registered)manager.removeThermalStatusListener(listener);executor.shutdownNow();if(!executor.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("THERMAL_TEST_EXECUTOR_NOT_STOPPED");}
    }
    private static JSONObject headroom(PowerManager manager,Executor executor,JSONArray checks)throws Exception{
        float forecast=manager.getThermalHeadroom(0);Map<Integer,Float> thresholds=manager.getThermalHeadroomThresholds();
        check(checks,"power-headroom-preserves-number-or-unavailable",Float.isNaN(forecast)||forecast>=0);
        CountDownLatch initial=new CountDownLatch(1);AtomicReference<JSONObject> value=new AtomicReference<>();
        PowerManager.OnThermalHeadroomChangedListener listener=(current,predicted,seconds,limits)->{try{value.set(new JSONObject().put("current",number(current)).put("forecast",number(predicted)).put("forecastSeconds",seconds).put("thresholdCount",limits.size()).put("thread",Thread.currentThread().getName()));initial.countDown();}catch(JSONException error){throw new IllegalStateException(error);}};
        manager.addThermalHeadroomListener(executor,listener);
        try{check(checks,"power-real-initial-headroom-callback",initial.await(3,TimeUnit.SECONDS)&&"owned-thermal-executor".equals(value.get().getString("thread")));return new JSONObject().put("query",number(forecast)).put("thresholdCount",thresholds.size()).put("callback",value.get());}
        finally{manager.removeThermalHeadroomListener(listener);}
    }
    private static Object number(float value){return Float.isNaN(value)?"NaN":Float.isInfinite(value)?String.valueOf(value):value;}
    private static boolean valid(int value){return value>=PowerManager.THERMAL_STATUS_NONE&&value<=PowerManager.THERMAL_STATUS_SHUTDOWN;}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private PowerChecks(){}
}
