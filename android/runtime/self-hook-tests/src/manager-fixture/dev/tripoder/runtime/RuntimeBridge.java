package dev.tripoder.runtime;

/** Test fixture only; production RuntimeBridge is not packaged in this JVM check. */
public final class RuntimeBridge {
    public static String event;public static Object[] arguments;public static long batteryValue;public static int platformReads;
    private static void record(String value,Object... args){event=value;arguments=args;}
    public static void networkRegister(android.net.NetworkRequest request,android.net.ConnectivityManager.NetworkCallback callback,android.os.Handler handler,boolean defaults){record("network",request,callback,handler,defaults);}
    public static void networkUnregister(android.net.ConnectivityManager.NetworkCallback callback){record("network-remove",callback);}
    public static void telephonyRegister(java.util.concurrent.Executor executor,android.telephony.TelephonyCallback callback){record("telephony",executor,callback);}
    public static void telephonyUnregister(android.telephony.TelephonyCallback callback){record("telephony-remove",callback);}
    public static String audioProperty(String key){record("audio-property",key);return "fixture-property";}
    public static void audioRegister(android.media.AudioDeviceCallback callback,android.os.Handler handler){record("audio",callback,handler);}
    public static void audioUnregister(android.media.AudioDeviceCallback callback){record("audio-remove",callback);}
    public static int batteryIntProperty(int id){record("battery-int",id);return Integer.MIN_VALUE;}
    public static void batteryProperty(int id){record("battery-validate",id);if(id==99)throw new UnsupportedOperationException("fixture-denied");}
    public static android.os.BatteryManager batteryPlatform(){platformReads++;return new android.os.BatteryManager();}
    public static java.util.concurrent.Executor powerDefaultExecutor(){return Runnable::run;}
    public static void powerStatusAdd(java.util.concurrent.Executor executor,android.os.PowerManager.OnThermalStatusChangedListener listener){record("power-status",executor,listener);}
    public static void powerHeadroomAdd(java.util.concurrent.Executor executor,android.os.PowerManager.OnThermalHeadroomChangedListener listener){record("power-headroom",executor,listener);}
    public static void powerStatusRemove(android.os.PowerManager.OnThermalStatusChangedListener listener){record("power-status-remove",listener);}
    public static void powerHeadroomRemove(android.os.PowerManager.OnThermalHeadroomChangedListener listener){record("power-headroom-remove",listener);}
}
