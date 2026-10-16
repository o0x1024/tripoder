package dev.tripoder.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fixed supported capabilities. Every other instance method on an owned facade is denied. */
final class RuntimeSelfHookManagerPolicy {
    enum Family { ACTIVITY_MANAGER,CONNECTIVITY,TELEPHONY,AUDIO,BATTERY,POWER,WIFI_P2P,NETWORK_STATS,NOTIFICATIONS,SHORTCUTS,JOB_SCHEDULER }
    interface Route {boolean owns(Object receiver);Object execute(String signature,Object[] arguments)throws Throwable;default Object execute(Object receiver,String signature,Object[] arguments)throws Throwable{return execute(signature,arguments);}}
    @FunctionalInterface interface Callback {
        Object invoke(Object receiver,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable;
    }
    private static final Map<Family,Set<String>> SUPPORTED=catalogue();
    private static Map<Family,Set<String>> catalogue(){Map<Family,Set<String>> result=new java.util.EnumMap<>(Family.class);
        for(Family family:Family.values())result.put(family,definition(family));return Collections.unmodifiableMap(result);}
    static Set<String> required(Family family) {
        Set<String> result=SUPPORTED.get(family);if(result==null)throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");return result;
    }
    private static Set<String> definition(Family family) {
        switch(family) {
            case ACTIVITY_MANAGER:return Set.of("getMemoryClass()","getLargeMemoryClass()","isLowRamDevice()",
                "getMemoryInfo(android.app.ActivityManager$MemoryInfo)","getRunningAppProcesses()","getHistoricalProcessStartReasons(int)");
            case CONNECTIVITY:return Set.of("getActiveNetwork()","getActiveNetworkInfo()","getAllNetworks()","getAllNetworkInfo()",
                "getNetworkInfo(int)","getNetworkInfo(android.net.Network)","getNetworkCapabilities(android.net.Network)",
                "getLinkProperties(android.net.Network)","getDefaultProxy()","isActiveNetworkMetered()","getRestrictBackgroundStatus()",
                "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback)",
                "registerDefaultNetworkCallback(android.net.ConnectivityManager$NetworkCallback,android.os.Handler)",
                "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback)",
                "registerNetworkCallback(android.net.NetworkRequest,android.net.ConnectivityManager$NetworkCallback,android.os.Handler)",
                "unregisterNetworkCallback(android.net.ConnectivityManager$NetworkCallback)");
            case TELEPHONY:return Set.of("getPhoneType()","getSimState()","getSimState(int)","getPhoneCount()","getActiveModemCount()",
                "getSupportedModemCount()","isVoiceCapable()","isSmsCapable()","isDataCapable()",
                "getNetworkCountryIso()","getSimCountryIso()","getNetworkType()","getDataNetworkType()","getNetworkOperator()","getNetworkOperatorName()","getSimOperator()","getSimOperatorName()",
                "registerTelephonyCallback(java.util.concurrent.Executor,android.telephony.TelephonyCallback)",
                "registerTelephonyCallback(int,java.util.concurrent.Executor,android.telephony.TelephonyCallback)",
                "unregisterTelephonyCallback(android.telephony.TelephonyCallback)");
            case AUDIO:return Set.of("getMode()","getRingerMode()","isMusicActive()","isMicrophoneMute()","isSpeakerphoneOn()",
                "isBluetoothScoOn()","isBluetoothA2dpOn()","isBluetoothScoAvailableOffCall()","isWiredHeadsetOn()","isVolumeFixed()",
                "getStreamVolume(int)","getStreamMaxVolume(int)","getStreamMinVolume(int)","isStreamMute(int)","getDevices(int)",
                "getProperty(java.lang.String)","registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)",
                "unregisterAudioDeviceCallback(android.media.AudioDeviceCallback)");
            case BATTERY:return Set.of("isCharging()","computeChargeTimeRemaining()","getIntProperty(int)","getLongProperty(int)");
            case POWER:return Set.of("getCurrentThermalStatus()","getThermalHeadroom(int)","getThermalHeadroomThresholds()",
                "getBatteryDischargePrediction()","isBatteryDischargePredictionPersonalized()","isInteractive()","isScreenOn()",
                "isPowerSaveMode()","getLocationPowerSaveMode()","isDeviceIdleMode()","isDeviceLightIdleMode()",
                "isLowPowerStandbyEnabled()","isExemptFromLowPowerStandby()","isAllowedInLowPowerStandby(int)",
                "isAllowedInLowPowerStandby(java.lang.String)","isRebootingUserspaceSupported()","isSustainedPerformanceModeSupported()",
                "isWakeLockLevelSupported(int)","newWakeLock(int,java.lang.String)","isIgnoringBatteryOptimizations(java.lang.String)",
                "addThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)",
                "addThermalStatusListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalStatusChangedListener)",
                "removeThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)",
                "addThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)",
                "addThermalHeadroomListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalHeadroomChangedListener)",
                "removeThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)");
            case WIFI_P2P:return Set.of("initialize(android.content.Context,android.os.Looper,android.net.wifi.p2p.WifiP2pManager$ChannelListener)","isChannelConstrainedDiscoverySupported()","isGroupClientRemovalSupported()","isGroupOwnerIPv6LinkLocalAddressProvided()","isPccModeSupported()","isSetVendorElementsSupported()","isWiFiDirectR2Supported()");
            case NETWORK_STATS:return Set.of();
            case NOTIFICATIONS:return Set.of("areNotificationsEnabled()","areNotificationsPaused()","getImportance()","getCurrentInterruptionFilter()","shouldHideSilentStatusBarIcons()",
                "cancel(int)","cancel(java.lang.String,int)","cancelAll()","createNotificationChannel(android.app.NotificationChannel)","createNotificationChannels(java.util.List)",
                "createNotificationChannelGroup(android.app.NotificationChannelGroup)","createNotificationChannelGroups(java.util.List)","getNotificationChannel(java.lang.String)",
                "getNotificationChannel(java.lang.String,java.lang.String)","getNotificationChannels()","getNotificationChannelGroup(java.lang.String)","getNotificationChannelGroups()",
                "deleteNotificationChannel(java.lang.String)","deleteNotificationChannelGroup(java.lang.String)");
            case SHORTCUTS:return Set.of("isRequestPinShortcutSupported()");
            case JOB_SCHEDULER:return Set.of("schedule(android.app.job.JobInfo)","cancel(int)","cancelAll()","cancelInAllNamespaces()","getAllPendingJobs()","getPendingJob(int)","getPendingJobsInAllNamespaces()","forNamespace(java.lang.String)","getNamespace()");
            default:throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");
        }
    }
    static Object invoke(Family family,Route route,String signature,Object receiver,Object[] arguments,
                         RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(receiver))return original.call(arguments);
        if(!required(family).contains(signature)){
            if(family==Family.NETWORK_STATS)throw new SecurityException("NETWORK_HISTORY_CAPABILITY_NOT_GRANTED:"+signature);
            String capability=family==Family.NOTIFICATIONS?"NOTIFICATION":family==Family.SHORTCUTS?"SHORTCUT":family.name();
            throw new UnsupportedOperationException(capability+"_CAPABILITY_UNSUPPORTED:"+signature);
        }
        return route.execute(receiver,signature,arguments);
    }
    /** The inventory must come from the one fixed Framework registry, never filtered ordinary reflection. */
    static Map<Method,Callback> plan(Family family,Class<?> owner,Method[] inventory,Route route) {
        if(owner==null||inventory==null||inventory.length==0||inventory.length>1024||route==null)
            throw new IllegalArgumentException("SYSTEM_MANAGER_FIXED_INVENTORY_REQUIRED");
        Map<Method,Callback> result=new LinkedHashMap<>();Set<String> signatures=new HashSet<>();Set<Method> unique=new HashSet<>();
        for(Method method:inventory.clone()) {
            if(method==null||method.getDeclaringClass()!=owner||!unique.add(method))
                throw new IllegalArgumentException("SYSTEM_MANAGER_METHOD_IDENTITY");
            if(Modifier.isStatic(method.getModifiers()))continue;
            if(Modifier.isAbstract(method.getModifiers()))throw new IllegalArgumentException("SYSTEM_MANAGER_ABSTRACT_TARGET");
            String key=signature(method);signatures.add(key);
            result.put(method,(receiver,arguments,original)->invoke(family,route,key,receiver,arguments,original));
        }
        for(String required:required(family))if(!signatures.contains(required))
            throw new IllegalArgumentException(family+"_SIGNATURE_MISSING:"+required);
        return Collections.unmodifiableMap(result);
    }
    static String signature(Method method) {
        StringBuilder key=new StringBuilder(method.getName()).append('(');Class<?>[] types=method.getParameterTypes();
        for(int i=0;i<types.length;i++){if(i!=0)key.append(',');key.append(types[i].getName());}
        return key.append(')').toString();
    }
    private RuntimeSelfHookManagerPolicy(){}
}
