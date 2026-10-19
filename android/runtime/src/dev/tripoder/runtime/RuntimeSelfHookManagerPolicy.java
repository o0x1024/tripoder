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
    interface Route {/** Called before an explicit platform operation (e.g. require its declared permission). */default void admitObservation(Family family){}default Object platform(){throw new IllegalStateException("SYSTEM_MANAGER_PHYSICAL_ROUTE_REQUIRED");}boolean owns(Object receiver);Object execute(String signature,Object[] arguments)throws Throwable;default Object execute(Object receiver,String signature,Object[] arguments)throws Throwable{return execute(signature,arguments);}}
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
                "getMemoryInfo(android.app.ActivityManager$MemoryInfo)","getRunningAppProcesses()","getProcessMemoryInfo([I)","getHistoricalProcessStartReasons(int)","setProcessStateSummary([B)","getHistoricalProcessExitReasons(java.lang.String,int,int)",
                "getRunningTasks(int)","getRecentTasks(int,int)","getAppTasks()","getRunningServices(int)","getProcessesInErrorState()",
                "getDeviceConfigurationInfo()","getLauncherLargeIconDensity()","getLauncherLargeIconSize()","getLockTaskModeState()","isInLockTaskMode()","isBackgroundRestricted()","getAppTaskThumbnailSize()",
                "killBackgroundProcesses(java.lang.String)","restartPackage(java.lang.String)","moveTaskToFront(int,int)","moveTaskToFront(int,int,android.os.Bundle)",
                "setWatchHeapLimit(long)","clearWatchHeapLimit()","addStartInfoTimestamp(int,long)","addApplicationStartInfoCompletionListener(java.util.concurrent.Executor,java.util.function.Consumer)","removeApplicationStartInfoCompletionListener(java.util.function.Consumer)");
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
                "preDispatchKeyEvent(android.view.KeyEvent,int)","getProperty(java.lang.String)","registerAudioDeviceCallback(android.media.AudioDeviceCallback,android.os.Handler)",
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
                "notify(int,android.app.Notification)","notify(java.lang.String,int,android.app.Notification)","cancel(int)","cancel(java.lang.String,int)","cancelAll()","createNotificationChannel(android.app.NotificationChannel)","createNotificationChannels(java.util.List)",
                "createNotificationChannelGroup(android.app.NotificationChannelGroup)","createNotificationChannelGroups(java.util.List)","getNotificationChannel(java.lang.String)",
                "getNotificationChannel(java.lang.String,java.lang.String)","getNotificationChannels()","getNotificationChannelGroup(java.lang.String)","getNotificationChannelGroups()",
                "deleteNotificationChannel(java.lang.String)","deleteNotificationChannelGroup(java.lang.String)");
            case SHORTCUTS:return Set.of("isRequestPinShortcutSupported()","getDynamicShortcuts()","getManifestShortcuts()","getPinnedShortcuts()","getShortcuts(int)",
                "getMaxShortcutCountPerActivity()","getIconMaxWidth()","getIconMaxHeight()","isRateLimitingActive()",
                "setDynamicShortcuts(java.util.List)","addDynamicShortcuts(java.util.List)","updateShortcuts(java.util.List)","requestPinShortcut(android.content.pm.ShortcutInfo,android.content.IntentSender)",
                "pushDynamicShortcut(android.content.pm.ShortcutInfo)","removeDynamicShortcuts(java.util.List)","removeAllDynamicShortcuts()","removeLongLivedShortcuts(java.util.List)",
                "enableShortcuts(java.util.List)","disableShortcuts(java.util.List)","disableShortcuts(java.util.List,java.lang.CharSequence)","reportShortcutUsed(java.lang.String)",
                "createShortcutResultIntent(android.content.pm.ShortcutInfo)");
            case JOB_SCHEDULER:return Set.of("schedule(android.app.job.JobInfo)","cancel(int)","cancelAll()","cancelInAllNamespaces()","getAllPendingJobs()","getPendingJob(int)","getPendingJobsInAllNamespaces()","forNamespace(java.lang.String)","getNamespace()");
            default:throw new IllegalArgumentException("SYSTEM_MANAGER_FAMILY");
        }
    }
    private static final java.util.regex.Pattern OBSERVATION=java.util.regex.Pattern.compile("(get|is|has|are|can|supports|query|check)[A-Z0-9][A-Za-z0-9]*\\(.*");
    private static final java.util.regex.Pattern AUDIO_CONTROL=java.util.regex.Pattern.compile("(requestAudioFocus|abandonAudioFocus|adjustVolume|adjustStreamVolume|adjustSuggestedStreamVolume|setStreamVolume|setMode|setSpeakerphoneOn|setBluetoothScoOn|startBluetoothSco|stopBluetoothSco|setMicrophoneMute|playSoundEffect|loadSoundEffects|unloadSoundEffects|isBluetoothScoOn|isSpeakerphoneOn|isMicrophoneMute|isMusicActive|isStreamMute|getStreamVolume|getStreamMaxVolume|getMode|getRingerMode|getAllowedCapturePolicy|setAllowedCapturePolicy|registerAudioRecordingCallback|unregisterAudioRecordingCallback|registerAudioPlaybackCallback|unregisterAudioPlaybackCallback|generateAudioSessionId|getSpatializer)\\(.*");
    private static final Set<String> PROCESS_NETWORK=Set.of("bindProcessToNetwork(android.net.Network)","setProcessDefaultNetwork(android.net.Network)");
    /** Families whose state is device-wide and carries no identifier of the device or the caller: network state and power/thermal state. */
    static boolean observation(Family family,String signature){
        // Audio (Bluetooth addresses), battery (serial/manufacture identifiers) and telephony expose device identifiers; they stay explicit-only.
        // Audio focus, volume and routing state are device-wide and tied to no package identity. Device enumeration, recording configurations and callbacks (which expose other
        // clients and Bluetooth addresses) are not among them.
        if(family==Family.AUDIO)return AUDIO_CONTROL.matcher(signature).matches();
        if(family!=Family.CONNECTIVITY&&family!=Family.POWER)return false;
        if(family==Family.CONNECTIVITY&&PROCESS_NETWORK.contains(signature))return true;
        return OBSERVATION.matcher(signature).matches()&&!signature.contains("Callback")&&!signature.contains("Listener");
    }
    static Object invoke(Family family,Route route,String signature,Object receiver,Object[] arguments,
                         RuntimeSelfHookLifecycle.Original original)throws Throwable {
        if(!route.owns(receiver))return original.call(arguments);
        if(!required(family).contains(signature)){
            if(family==Family.NETWORK_STATS)throw new SecurityException("NETWORK_HISTORY_CAPABILITY_NOT_GRANTED:"+signature);
            // Pure observation of device state does not depend on which package asks. The guest is the same process and UID as the host, so the platform's own
            // implementation (and its permission checks) is the faithful answer; refusing only makes apps that merely read state fail.
            if(observation(family,signature)){
                route.admitObservation(family);
                return original.call(arguments);
            }
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
            result.put(method,(receiver,arguments,original)->invoke(family,route,key,receiver,arguments,
                values->physicalOriginal(method,route,receiver,values,original)));
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
    /** An admitted platform operation runs on its physical manager. Running it
     * on the guest facade intercepts private helpers again (e.g. AudioManager.getContext)
     * and confuses implementation dependencies with guest-granted capabilities. */
    static Object physicalOriginal(Method method,Route route,Object receiver,Object[] args,RuntimeSelfHookLifecycle.Original original)throws Throwable{
        if(!route.owns(receiver))return original.call(args);
        Object physical=route.platform();
        if(physical==null||physical==receiver||route.owns(physical)||!method.getDeclaringClass().isInstance(physical))throw new SecurityException("SYSTEM_MANAGER_PHYSICAL_RECEIVER_INVALID");
        try{method.setAccessible(true);return method.invoke(physical,args);}
        catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
    }
    private RuntimeSelfHookManagerPolicy(){}
}
