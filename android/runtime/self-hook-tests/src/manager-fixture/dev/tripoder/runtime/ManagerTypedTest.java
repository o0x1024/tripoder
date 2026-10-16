package dev.tripoder.runtime;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;

public final class ManagerTypedTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Object call(String family,String key,Object... args)throws Throwable {
        Class<?> owner=family.equals("battery")||family.equals("power")?RuntimeSelfHookDeviceManagers.class:RuntimeSelfHookManagers.class;
        var method=owner.getDeclaredMethod(family,String.class,Object[].class);method.setAccessible(true);
        try{return method.invoke(null,key,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    public static void main(String[] args)throws Throwable {
        Executor executor=Runnable::run;
        call("network","registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback)",new Object[]{null});
        check(RuntimeBridge.event.equals("network")&&RuntimeBridge.arguments.length==4&&RuntimeBridge.arguments[0]==null&&RuntimeBridge.arguments[3].equals(true));
        call("network","registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback,android.os.Handler)",null,null,null);
        check(RuntimeBridge.arguments[3].equals(false));
        call("network","unregisterNetworkCallback(android.net.ConnectivityManager$NetworkCallback)",new Object[]{null});check(RuntimeBridge.event.equals("network-remove"));
        call("telephony","registerTelephonyCallback(java.util.concurrent.Executor,android.telephony.TelephonyCallback)",executor,null);
        check(RuntimeBridge.event.equals("telephony")&&RuntimeBridge.arguments[0]==executor);
        call("telephony","registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)",0,executor,null);check(RuntimeBridge.arguments[0]==executor);
        RuntimeBridge.event=null;
        try{call("telephony","registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)",1,executor,null);throw new AssertionError();}
        catch(UnsupportedOperationException error){check(error.getMessage().equals("TELEPHONY_LOCATION_CALLBACK_UNSUPPORTED"));}
        check(RuntimeBridge.event==null);
        check(call("audio","getProperty(java.lang.String)","owned-property").equals("fixture-property"));check(RuntimeBridge.arguments[0].equals("owned-property"));
        call("audio","registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)",null,null);check(RuntimeBridge.event.equals("audio"));
        call("audio","unregisterAudioDeviceCallback(android.media.AudioDeviceCallback)",new Object[]{null});check(RuntimeBridge.event.equals("audio-remove"));
        check(call("battery","getIntProperty(int)",1).equals(Integer.MIN_VALUE));
        for(long value:new long[]{Long.MIN_VALUE,Long.MAX_VALUE,9007199254740993L}) {
            RuntimeBridge.batteryValue=value;
            check(call("battery","getLongProperty(int)",1).equals(value));check(RuntimeBridge.event.equals("battery-validate"));
            check(call("battery","computeChargeTimeRemaining()").equals(value));
        }
        int reads=RuntimeBridge.platformReads;
        try{call("battery","getLongProperty(int)",99);throw new AssertionError();}catch(UnsupportedOperationException error){check(error.getMessage().equals("fixture-denied"));}
        check(RuntimeBridge.platformReads==reads);
        call("power","addThermalStatusListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalStatusChangedListener)",executor,null);
        check(RuntimeBridge.event.equals("power-status")&&RuntimeBridge.arguments[0]==executor);
        call("power","addThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)",new Object[]{null});
        check(RuntimeBridge.event.equals("power-headroom")&&RuntimeBridge.arguments[0] instanceof Executor);
        call("power","removeThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)",new Object[]{null});check(RuntimeBridge.event.equals("power-headroom-remove"));
        for(String family:new String[]{"network","telephony","audio","battery","power"}) {
            RuntimeBridge.event=null;
            try{call(family,"unsupportedControl()");throw new AssertionError();}catch(UnsupportedOperationException error){check(error.getMessage().contains("CAPABILITY_UNSUPPORTED"));}
            check(RuntimeBridge.event==null);
        }
        RuntimeEngine engine=new RuntimeEngine();RuntimeEngine.current=engine;
        java.util.concurrent.atomic.AtomicInteger constructed=new java.util.concurrent.atomic.AtomicInteger();
        RuntimeSelfHookManagerFramework framework=(RuntimeSelfHookManagerFramework)java.lang.reflect.Proxy.newProxyInstance(
            ManagerTypedTest.class.getClassLoader(),new Class<?>[]{RuntimeSelfHookManagerFramework.class},(proxy,method,arguments)->{
                if(!method.getName().equals("batteryManager"))throw new AssertionError("unexpected constructor");
                constructed.incrementAndGet();return new android.os.BatteryManager();
            });
        RuntimeSelfHookManagers.prepare(engine,framework);
        try{RuntimeSelfHookManagers.batteryManager();throw new AssertionError();}
        catch(IllegalStateException error){check(error.getMessage().contains("FAMILY_NOT_INSTALLED"));}
        check(constructed.get()==0);
        var battery=RuntimeSelfHookManagerPolicy.Family.BATTERY;
        java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure=new java.util.concurrent.atomic.AtomicReference<>();
        RuntimeSelfHookManagers.install(battery,android.os.BatteryManager.class.getDeclaredMethods(),(family,callbacks)->{
            check(family==battery&&callbacks.size()==5);
            Thread worker=new Thread(()->{try{RuntimeSelfHookManagers.batteryManager();workerFailure.set(new AssertionError("constructed during installation"));}
                catch(IllegalStateException expected){if(!expected.getMessage().contains("FAMILY_NOT_INSTALLED"))workerFailure.set(expected);}});
            worker.start();worker.join(5000);check(!worker.isAlive()&&workerFailure.get()==null);
        });
        check(RuntimeSelfHookManagers.batteryManager()!=null&&constructed.get()==1);
        try{RuntimeSelfHookManagers.install(battery,android.os.BatteryManager.class.getDeclaredMethods(),(family,callbacks)->{throw new AssertionError();});throw new AssertionError();}
        catch(IllegalStateException error){check(error.getMessage().contains("ALREADY_ATTEMPTED"));}
        try{RuntimeSelfHookManagers.install(RuntimeSelfHookManagerPolicy.Family.AUDIO,null,(family,callbacks)->{throw new AssertionError();});throw new AssertionError();}
        catch(IllegalArgumentException error){check(error.getMessage().contains("FIXED_INVENTORY_REQUIRED"));}
        try{RuntimeSelfHookManagers.batteryManager();throw new AssertionError();}
        catch(IllegalStateException error){check(error.getMessage().contains("ADAPTER_FAILED"));}
        check(constructed.get()==1);
        System.out.println("manager typed checks="+checks);
    }
}
