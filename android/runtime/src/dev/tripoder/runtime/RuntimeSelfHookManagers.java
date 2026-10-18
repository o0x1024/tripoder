package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Six separately owned manager facades; the fixed registry/installer remains the sole backend. */
final class RuntimeSelfHookManagers {
    interface Installer {
        /** Verify the fixed inventory is complete and every binding has live caller coverage before returning. */
        void install(RuntimeSelfHookManagerPolicy.Family family,Map<Method,RuntimeSelfHookManagerPolicy.Callback> callbacks)throws Exception;
    }
    private static volatile RuntimeSelfHookManagers current;
    private static boolean attempted;
    private final RuntimeEngine engine;
    private final RuntimeSelfHookManagerFramework framework;
    private final EnumSet<RuntimeSelfHookManagerPolicy.Family> installed=EnumSet.noneOf(RuntimeSelfHookManagerPolicy.Family.class);
    private final EnumSet<RuntimeSelfHookManagerPolicy.Family> attemptedFamilies=EnumSet.noneOf(RuntimeSelfHookManagerPolicy.Family.class);
    private volatile Throwable failure;
    private boolean installing;
    private RuntimeSelfHookManagers(RuntimeEngine engine,RuntimeSelfHookManagerFramework framework){this.engine=engine;this.framework=framework;}
    static synchronized void prepare(RuntimeEngine engine,RuntimeSelfHookManagerFramework framework) {
        if(attempted)throw new IllegalStateException("SELF_MANAGER_PREPARE_ALREADY_ATTEMPTED");attempted=true;
        if(engine==null||engine!=RuntimeEngine.current)throw new SecurityException("SELF_MANAGER_ENGINE_REQUIRED");
        current=new RuntimeSelfHookManagers(engine,Objects.requireNonNull(framework,"SELF_MANAGER_FRAMEWORK_REQUIRED"));
    }
    private static RuntimeSelfHookManagers require() {
        RuntimeSelfHookManagers value=current;
        if(value==null||value.engine!=RuntimeEngine.current)throw new IllegalStateException("SELF_MANAGER_ADAPTER_REQUIRED");
        if(value.failure!=null)throw new IllegalStateException("SELF_MANAGER_ADAPTER_FAILED",value.failure);
        return value;
    }
    static void requireActive(){require();}
    private static RuntimeSelfHookManagerFramework framework(Context host,RuntimeSelfHookManagerPolicy.Family family) {
        RuntimeSelfHookManagers value=require();
        if(host!=value.engine.host)throw new SecurityException("SELF_MANAGER_PHYSICAL_CONTEXT_REQUIRED");
        synchronized(value){if(!value.installed.contains(family))throw new IllegalStateException("SELF_MANAGER_FAMILY_NOT_INSTALLED:"+family);}
        return value.framework;
    }
    static ActivityManager activityManager(Context host){return Objects.requireNonNull(framework(host,RuntimeSelfHookManagerPolicy.Family.ACTIVITY_MANAGER).activityManager(host,new Handler(Looper.getMainLooper())));}
    static ConnectivityManager connectivityManager(Context host) {
        RuntimeSelfHookManagerFramework calls=framework(host,RuntimeSelfHookManagerPolicy.Family.CONNECTIVITY);
        ConnectivityManager physical=Objects.requireNonNull(host.getSystemService(ConnectivityManager.class),"PLATFORM_CONNECTIVITY_UNAVAILABLE");
        return Objects.requireNonNull(calls.connectivityManager(host,physical));
    }
    static int networkCallbackFlags(ConnectivityManager.NetworkCallback callback){return require().framework.networkCallbackFlags(Objects.requireNonNull(callback));}
    static TelephonyManager telephonyManager(Context host){return Objects.requireNonNull(framework(host,RuntimeSelfHookManagerPolicy.Family.TELEPHONY).telephonyManager(host));}
    static AudioManager audioManager(Context host){return Objects.requireNonNull(framework(host,RuntimeSelfHookManagerPolicy.Family.AUDIO).audioManager(host));}
    static android.os.BatteryManager batteryManager(){RuntimeSelfHookManagers value=require();return Objects.requireNonNull(framework(value.engine.host,RuntimeSelfHookManagerPolicy.Family.BATTERY).batteryManager());}
    static android.os.PowerManager powerManager(Context host){return Objects.requireNonNull(framework(host,RuntimeSelfHookManagerPolicy.Family.POWER).powerManager(host,new Handler(Looper.getMainLooper())));}

    static android.net.wifi.p2p.WifiP2pManager wifiP2pManager(Context host){RuntimeSelfHookManagers value=require();RuntimeSelfHookManagerFramework calls=framework(value.engine.host,RuntimeSelfHookManagerPolicy.Family.WIFI_P2P);
        if(host!=null&&host!=value.engine.host)throw new SecurityException("SELF_MANAGER_PHYSICAL_CONTEXT_REQUIRED");
        android.net.wifi.p2p.WifiP2pManager physical=host==null?null:Objects.requireNonNull(host.getSystemService(android.net.wifi.p2p.WifiP2pManager.class),"PLATFORM_WIFI_P2P_UNAVAILABLE");return Objects.requireNonNull(calls.wifiP2pManager(host,physical));}
    static android.app.usage.NetworkStatsManager networkStatsManager(){RuntimeSelfHookManagers value=require();return Objects.requireNonNull(framework(value.engine.host,RuntimeSelfHookManagerPolicy.Family.NETWORK_STATS).networkStatsManager());}
    static android.app.NotificationManager notificationManager(Context host){return Objects.requireNonNull(framework(host,RuntimeSelfHookManagerPolicy.Family.NOTIFICATIONS).notificationManager(host));}
    static android.content.pm.ShortcutManager shortcutManager(Context context){RuntimeSelfHookManagers value=require();if(!(context instanceof GuestContext)||((GuestContext)context).engine!=value.engine)throw new SecurityException("SHORTCUT_GUEST_CONTEXT_REQUIRED");return Objects.requireNonNull(framework(value.engine.host,RuntimeSelfHookManagerPolicy.Family.SHORTCUTS).shortcutManager(context));}
    static RuntimeSelfHookManagerFramework extraFramework(){return require().framework;}
    static void install(RuntimeSelfHookManagerPolicy.Family family,Method[] completeFixedInventory,Installer installer)throws Exception {
        RuntimeSelfHookManagers value=require();
        synchronized(value) {
            if(value.installing)throw new IllegalStateException("SELF_MANAGER_INSTALL_IN_PROGRESS");
            if(!value.attemptedFamilies.add(Objects.requireNonNull(family)))throw new IllegalStateException("SELF_MANAGER_FAMILY_ALREADY_ATTEMPTED:"+family);
            value.installing=true;
        }
        try {
            Map<Method,RuntimeSelfHookManagerPolicy.Callback> callbacks=plan(family,completeFixedInventory);
            Objects.requireNonNull(installer,"SELF_MANAGER_INSTALLER_REQUIRED").install(family,callbacks);
            synchronized(value){value.installed.add(family);}
        }catch(Exception|Error error){value.failure=error;throw error;}
        finally{synchronized(value){value.installing=false;}}
    }

    static Map<Method,RuntimeSelfHookManagerPolicy.Callback> plan(RuntimeSelfHookManagerPolicy.Family family,Method[] completeFixedInventory) {
        require();Class<?> owner;
        switch(family) {
            case ACTIVITY_MANAGER:owner=ActivityManager.class;break;
            case CONNECTIVITY:owner=ConnectivityManager.class;break;
            case TELEPHONY:owner=TelephonyManager.class;break;
            case AUDIO:owner=AudioManager.class;break;
            case BATTERY:owner=android.os.BatteryManager.class;break;
            case POWER:owner=android.os.PowerManager.class;break;
            case WIFI_P2P:owner=android.net.wifi.p2p.WifiP2pManager.class;break;
            case NETWORK_STATS:owner=android.app.usage.NetworkStatsManager.class;break;
            case NOTIFICATIONS:owner=android.app.NotificationManager.class;break;
            case JOB_SCHEDULER:try{owner=Class.forName("android.app.JobSchedulerImpl",false,null);}catch(ClassNotFoundException error){throw new IllegalStateException(error);}break;
            case SHORTCUTS:owner=android.content.pm.ShortcutManager.class;break;
            default:throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");
        }
        return RuntimeSelfHookManagerPolicy.plan(family,owner,completeFixedInventory,new RuntimeSelfHookManagerPolicy.Route() {
            public Object platform(){
                switch(family){
                    case AUDIO:return RuntimeBridge.audioPlatform();
                    case CONNECTIVITY:return RuntimeBridge.networkPlatform();
                    case POWER:return RuntimeBridge.powerPlatform();
                    default:throw new IllegalStateException("SYSTEM_MANAGER_PHYSICAL_ROUTE_UNSUPPORTED:"+family);
                }
            }
            public void admitObservation(RuntimeSelfHookManagerPolicy.Family observed) {
                if(observed==RuntimeSelfHookManagerPolicy.Family.CONNECTIVITY)RuntimeBridge.networkPlatform();// the guest must have declared ACCESS_NETWORK_STATE
            }
            public boolean owns(Object receiver) {
                require();
                switch(family) {
                    case ACTIVITY_MANAGER:return RuntimeBridge.ownsActivityManager((ActivityManager)receiver);
                    case CONNECTIVITY:return RuntimeBridge.ownsConnectivityManager((ConnectivityManager)receiver);
                    case TELEPHONY:return RuntimeBridge.ownsTelephonyManager((TelephonyManager)receiver);
                    case AUDIO:return RuntimeBridge.ownsAudioManager((AudioManager)receiver);
                    case BATTERY:return RuntimeBridge.ownsBatteryManager((android.os.BatteryManager)receiver);
                    case POWER:return RuntimeBridge.ownsPowerManager((android.os.PowerManager)receiver);
                    case WIFI_P2P:return RuntimeBridge.ownsWifiP2pManager((android.net.wifi.p2p.WifiP2pManager)receiver);
                    case NETWORK_STATS:return RuntimeBridge.ownsNetworkStatsManager((android.app.usage.NetworkStatsManager)receiver);
                    case NOTIFICATIONS:return RuntimeBridge.ownsNotificationManager((android.app.NotificationManager)receiver);
                    case JOB_SCHEDULER:return RuntimeEngine.current.jobScheduler.owns(receiver);
                    case SHORTCUTS:return RuntimeBridge.ownsShortcutCapability((android.content.pm.ShortcutManager)receiver);
                    default:throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");
                }
            }
            public Object execute(Object receiver,String key,Object[] arguments)throws Throwable {
                if(family==RuntimeSelfHookManagerPolicy.Family.JOB_SCHEDULER)return RuntimeEngine.current.jobScheduler.dispatch((android.app.job.JobScheduler)receiver,key,arguments);
                return execute(key,arguments);
            }
            public Object execute(String key,Object[] arguments)throws Throwable {
                switch(family) {
                    case ACTIVITY_MANAGER:return activity(key,arguments);
                    case CONNECTIVITY:return network(key,arguments);
                    case TELEPHONY:return telephony(key,arguments);
                    case AUDIO:return audio(key,arguments);
                    case BATTERY:return RuntimeSelfHookDeviceManagers.battery(key,arguments);
                    case POWER:return RuntimeSelfHookDeviceManagers.power(key,arguments);
                    case WIFI_P2P:return RuntimeSelfHookManagerExtras.wifi(key,arguments);
                    case NETWORK_STATS:throw new SecurityException("NETWORK_HISTORY_CAPABILITY_NOT_GRANTED:"+key);
                    case NOTIFICATIONS:return RuntimeSelfHookManagerExtras.notification(key,arguments);
                    case SHORTCUTS:return RuntimeSelfHookManagerExtras.shortcut(key,arguments);
                    default:throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");
                }
            }
        });
    }
    private static Object activity(String key,Object[] args)throws Exception {
        switch(key) {
            case "getMemoryClass()":return RuntimeBridge.memoryClass(false);
            case "getLargeMemoryClass()":return RuntimeBridge.memoryClass(true);
            case "isLowRamDevice()":return RuntimeBridge.lowRamDevice();
            case "getMemoryInfo(android.app.ActivityManager$MemoryInfo)":RuntimeBridge.memoryInfo((ActivityManager.MemoryInfo)args[0]);return null;
            case "getRunningAppProcesses()":return RuntimeBridge.processView();
            case "getProcessMemoryInfo([I)":return RuntimeBridge.processMemory((int[])args[0]);
            case "getHistoricalProcessStartReasons(int)":return RuntimeSelfHookHistory.query((Integer)args[0],require().framework);
            case "setProcessStateSummary([B)":RuntimeBridge.setProcessStateSummary((byte[])args[0]);return null;
            case "getHistoricalProcessExitReasons(java.lang.String,int,int)":return RuntimeBridge.historicalExits((String)args[0],(Integer)args[1],(Integer)args[2]);
            default:throw new UnsupportedOperationException("ACTIVITY_MANAGER_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    @SuppressWarnings("deprecation") private static Object network(String key,Object[] args) {
        switch(key) {
            case "getActiveNetwork()":return RuntimeBridge.networkPlatform().getActiveNetwork();
            case "getActiveNetworkInfo()":return RuntimeBridge.networkPlatform().getActiveNetworkInfo();
            case "getAllNetworks()":return RuntimeBridge.networkPlatform().getAllNetworks();
            case "getAllNetworkInfo()":return RuntimeBridge.networkPlatform().getAllNetworkInfo();
            case "getNetworkInfo(int)":return RuntimeBridge.networkPlatform().getNetworkInfo((Integer)args[0]);
            case "getNetworkInfo(android.net.Network)":return RuntimeBridge.networkPlatform().getNetworkInfo((Network)args[0]);
            case "getNetworkCapabilities(android.net.Network)":return RuntimeBridge.networkPlatform().getNetworkCapabilities((Network)args[0]);
            case "getLinkProperties(android.net.Network)":return RuntimeBridge.networkPlatform().getLinkProperties((Network)args[0]);
            case "getDefaultProxy()":return RuntimeBridge.networkPlatform().getDefaultProxy();
            case "isActiveNetworkMetered()":return RuntimeBridge.networkPlatform().isActiveNetworkMetered();
            case "getRestrictBackgroundStatus()":return RuntimeBridge.networkPlatform().getRestrictBackgroundStatus();
            case "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback)":
                RuntimeBridge.networkRegister(null,(ConnectivityManager.NetworkCallback)args[0],null,true);return null;
            case "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback,android.os.Handler)":
                RuntimeBridge.networkRegister(null,(ConnectivityManager.NetworkCallback)args[0],(Handler)args[1],true);return null;
            case "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback)":
                RuntimeBridge.networkRegister((NetworkRequest)args[0],(ConnectivityManager.NetworkCallback)args[1],null,false);return null;
            case "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback,android.os.Handler)":
                RuntimeBridge.networkRegister((NetworkRequest)args[0],(ConnectivityManager.NetworkCallback)args[1],(Handler)args[2],false);return null;
            case "unregisterNetworkCallback(android.net.ConnectivityManager$NetworkCallback)":RuntimeBridge.networkUnregister((ConnectivityManager.NetworkCallback)args[0]);return null;
            default:throw new UnsupportedOperationException("CONNECTIVITY_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    @SuppressWarnings("deprecation") private static Object telephony(String key,Object[] args) {
        switch(key) {
            case "getPhoneType()":return RuntimeBridge.telephonyPlatform().getPhoneType();
            case "getSimState()":return RuntimeBridge.telephonyPlatform().getSimState();
            case "getSimState(int)":return RuntimeBridge.telephonyPlatform().getSimState((Integer)args[0]);
            case "getPhoneCount()":return RuntimeBridge.telephonyPlatform().getPhoneCount();
            case "getActiveModemCount()":return RuntimeBridge.telephonyPlatform().getActiveModemCount();
            case "getSupportedModemCount()":return RuntimeBridge.telephonyPlatform().getSupportedModemCount();
            case "isVoiceCapable()":return RuntimeBridge.telephonyPlatform().isVoiceCapable();
            case "isSmsCapable()":return RuntimeBridge.telephonyPlatform().isSmsCapable();
            case "isDataCapable()":return RuntimeBridge.telephonyPlatform().isDataCapable();
            case "getNetworkCountryIso()":return RuntimeBridge.telephonyPlatform().getNetworkCountryIso();
            case "getSimCountryIso()":return RuntimeBridge.telephonyPlatform().getSimCountryIso();
            case "getNetworkType()":return RuntimeBridge.telephonyPlatform().getNetworkType();
            case "getDataNetworkType()":return RuntimeBridge.telephonyPlatform().getDataNetworkType();
            case "getNetworkOperator()":return RuntimeBridge.telephonyPlatform().getNetworkOperator();
            case "getNetworkOperatorName()":return RuntimeBridge.telephonyPlatform().getNetworkOperatorName();
            case "getSimOperator()":return RuntimeBridge.telephonyPlatform().getSimOperator();
            case "getSimOperatorName()":return RuntimeBridge.telephonyPlatform().getSimOperatorName();
            case "registerTelephonyCallback(java.util.concurrent.Executor,android.telephony.TelephonyCallback)":
                RuntimeBridge.telephonyRegister((Executor)args[0],(TelephonyCallback)args[1]);return null;
            case "registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)":
                if((Integer)args[0]!=TelephonyManager.INCLUDE_LOCATION_DATA_NONE)throw new UnsupportedOperationException("TELEPHONY_LOCATION_CALLBACK_UNSUPPORTED");
                RuntimeBridge.telephonyRegister((Executor)args[1],(TelephonyCallback)args[2]);return null;
            case "unregisterTelephonyCallback(android.telephony.TelephonyCallback)":RuntimeBridge.telephonyUnregister((TelephonyCallback)args[0]);return null;
            default:throw new UnsupportedOperationException("TELEPHONY_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    @SuppressWarnings("deprecation") private static Object audio(String key,Object[] args) {
        switch(key) {
            case "getMode()":return RuntimeBridge.audioPlatform().getMode();
            case "getRingerMode()":return RuntimeBridge.audioPlatform().getRingerMode();
            case "isMusicActive()":return RuntimeBridge.audioPlatform().isMusicActive();
            case "isMicrophoneMute()":return RuntimeBridge.audioPlatform().isMicrophoneMute();
            case "isSpeakerphoneOn()":return RuntimeBridge.audioPlatform().isSpeakerphoneOn();
            case "isBluetoothScoOn()":return RuntimeBridge.audioPlatform().isBluetoothScoOn();
            case "isBluetoothA2dpOn()":return RuntimeBridge.audioPlatform().isBluetoothA2dpOn();
            case "isBluetoothScoAvailableOffCall()":return RuntimeBridge.audioPlatform().isBluetoothScoAvailableOffCall();
            case "isWiredHeadsetOn()":return RuntimeBridge.audioPlatform().isWiredHeadsetOn();
            case "isVolumeFixed()":return RuntimeBridge.audioPlatform().isVolumeFixed();
            case "getStreamVolume(int)":return RuntimeBridge.audioPlatform().getStreamVolume((Integer)args[0]);
            case "getStreamMaxVolume(int)":return RuntimeBridge.audioPlatform().getStreamMaxVolume((Integer)args[0]);
            case "getStreamMinVolume(int)":return RuntimeBridge.audioPlatform().getStreamMinVolume((Integer)args[0]);
            case "isStreamMute(int)":return RuntimeBridge.audioPlatform().isStreamMute((Integer)args[0]);
            case "getDevices(int)":return RuntimeBridge.audioPlatform().getDevices((Integer)args[0]);
            case "preDispatchKeyEvent(android.view.KeyEvent,int)":dev.tripoder.selfhook.ManagerFrameworkAccess.audioPreDispatchKeyEvent(RuntimeBridge.audioPlatform(),(android.view.KeyEvent)args[0],(Integer)args[1]);return null;
            case "getProperty(java.lang.String)":return RuntimeBridge.audioProperty((String)args[0]);
            case "registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)":RuntimeBridge.audioRegister((AudioDeviceCallback)args[0],(Handler)args[1]);return null;
            case "unregisterAudioDeviceCallback(android.media.AudioDeviceCallback)":RuntimeBridge.audioUnregister((AudioDeviceCallback)args[0]);return null;
            default:throw new UnsupportedOperationException("AUDIO_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
}
