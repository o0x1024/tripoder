package dev.tripoder.selfhook;

import android.app.ActivityManager;
import android.app.ApplicationStartInfo;
import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/** F4/F5 fixed constructors and values. Installation admission stays with Runtime. */
public final class ManagerFrameworkAccess {
    private static boolean attempted;private static volatile ManagerFrameworkAccess instance;
    private final Constructor<?> activity,connectivity,telephony,audio,battery,power,history,wifi,stats,notification,shortcut;
    private final Field service,callbackFlags,wifiService,groupId,groupChannels;
    private final Method[] wifiFeatures=new Method[6],notificationProperties=new Method[5];
    private final Method channelId,channelGroup,preDispatchKey;
    private final Method[] integers=new Method[9];private final Method packageName,processName,forceStopped,timestamp;
    private final int launchKey,onCreateKey;
    private ManagerFrameworkAccess()throws Exception {
        activity=FixedFrameworkMembers.constructor(7,"(Landroid/content/Context;Landroid/os/Handler;)V");
        connectivity=FixedFrameworkMembers.constructor(8,"(Landroid/content/Context;Landroid/net/IConnectivityManager;)V");
        preDispatchKey=FixedFrameworkMembers.method(10,"preDispatchKeyEvent","(Landroid/view/KeyEvent;I)V",false);
        telephony=FixedFrameworkMembers.constructor(9,"(Landroid/content/Context;)V");audio=FixedFrameworkMembers.constructor(10,"(Landroid/content/Context;)V");
        battery=FixedFrameworkMembers.constructor(11,"(Landroid/content/Context;Lcom/android/internal/app/IBatteryStats;Landroid/os/IBatteryPropertiesRegistrar;)V");
        power=FixedFrameworkMembers.constructor(12,"(Landroid/content/Context;Landroid/os/IPowerManager;Landroid/os/IThermalService;Landroid/os/Handler;)V");
        service=FixedFrameworkMembers.field(18,8,"mService","Landroid/net/IConnectivityManager;",false);
        callbackFlags=FixedFrameworkMembers.field(17,22,"mFlags","I",false);
        wifi=FixedFrameworkMembers.constructor(46,"(Landroid/net/wifi/p2p/IWifiP2pManager;)V");
        stats=FixedFrameworkMembers.constructor(36,"(Landroid/content/Context;Landroid/net/INetworkStatsService;)V");
        notification=FixedFrameworkMembers.constructor(38,"(Landroid/content/Context;Ljava/time/InstantSource;)V");
        shortcut=FixedFrameworkMembers.constructor(45,"(Landroid/content/Context;Landroid/content/pm/IShortcutService;)V");
        wifiService=FixedFrameworkMembers.field(21,46,"mService","Landroid/net/wifi/p2p/IWifiP2pManager;",false);
        groupId=FixedFrameworkMembers.field(22,40,"mId","Ljava/lang/String;",false);groupChannels=FixedFrameworkMembers.field(23,40,"mChannels","Ljava/util/List;",false);
        String[] wifiNames={"isChannelConstrainedDiscoverySupported","isGroupClientRemovalSupported","isGroupOwnerIPv6LinkLocalAddressProvided","isPccModeSupported","isSetVendorElementsSupported","isWiFiDirectR2Supported"};
        for(int i=0;i<wifiNames.length;i++)wifiFeatures[i]=FixedFrameworkMembers.method(46,wifiNames[i],"()Z",false);
        String[] notificationNames={"areNotificationsEnabled","areNotificationsPaused","getImportance","getCurrentInterruptionFilter","shouldHideSilentStatusBarIcons"};
        for(int i=0;i<notificationNames.length;i++)notificationProperties[i]=FixedFrameworkMembers.method(38,notificationNames[i],i==2||i==3?"()I":"()Z",false);
        channelId=FixedFrameworkMembers.method(39,"setId","(Ljava/lang/String;)V",false);channelGroup=FixedFrameworkMembers.method(39,"setGroup","(Ljava/lang/String;)V",false);
        history=FixedFrameworkMembers.constructor(23,"(J)V");
        String[] names={"setStartupState","setPid","setRealUid","setPackageUid","setDefiningUid","setReason","setStartType","setStartComponent","setLaunchMode"};
        for(int i=0;i<names.length;i++)integers[i]=FixedFrameworkMembers.method(23,names[i],"(I)V",false);
        packageName=FixedFrameworkMembers.method(23,"setPackageName","(Ljava/lang/String;)V",false);
        processName=FixedFrameworkMembers.method(23,"setProcessName","(Ljava/lang/String;)V",false);
        forceStopped=FixedFrameworkMembers.method(23,"setForceStopped","(Z)V",false);
        timestamp=FixedFrameworkMembers.method(23,"addStartupTimestamp","(IJ)V",false);
        launchKey=(Integer)FixedFrameworkMembers.get(FixedFrameworkMembers.field(19,23,"START_TIMESTAMP_LAUNCH","I",true),null);
        onCreateKey=(Integer)FixedFrameworkMembers.get(FixedFrameworkMembers.field(20,23,"START_TIMESTAMP_APPLICATION_ONCREATE","I",true),null);
    }
    public static synchronized void prepare()throws Exception{if(attempted)throw new IllegalStateException("MANAGER_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;instance=new ManagerFrameworkAccess();}
    private static ManagerFrameworkAccess require(){ManagerFrameworkAccess v=instance;if(v==null)throw new IllegalStateException("MANAGER_FRAMEWORK_NOT_READY");return v;}
    public static ActivityManager activity(Context host,Handler handler){return (ActivityManager)FixedFrameworkMembers.create(require().activity,Objects.requireNonNull(host),Objects.requireNonNull(handler));}
    public static ConnectivityManager connectivity(Context host,ConnectivityManager physical){ManagerFrameworkAccess v=require();Object service=Objects.requireNonNull(FixedFrameworkMembers.get(v.service,Objects.requireNonNull(physical)),"CONNECTIVITY_PHYSICAL_SERVICE_REQUIRED");return (ConnectivityManager)FixedFrameworkMembers.create(v.connectivity,Objects.requireNonNull(host),service);}
    public static TelephonyManager telephony(Context host){return (TelephonyManager)FixedFrameworkMembers.create(require().telephony,Objects.requireNonNull(host));}
    /** Volume/media key pre-dispatch on the physical host manager; the platform owns key policy. */
    public static void audioPreDispatchKeyEvent(AudioManager physical,android.view.KeyEvent event,int stream){FixedFrameworkMembers.call(require().preDispatchKey,Objects.requireNonNull(physical),Objects.requireNonNull(event),stream);}
    public static AudioManager audio(Context host){return (AudioManager)FixedFrameworkMembers.create(require().audio,Objects.requireNonNull(host));}
    public static BatteryManager battery(){return (BatteryManager)FixedFrameworkMembers.create(require().battery,null,null,null);}
    public static PowerManager power(Context host,Handler handler){return (PowerManager)FixedFrameworkMembers.create(require().power,Objects.requireNonNull(host),null,null,Objects.requireNonNull(handler));}
    public static android.net.wifi.p2p.WifiP2pManager wifi(Context host,android.net.wifi.p2p.WifiP2pManager physical){ManagerFrameworkAccess v=require();Object service=host==null?null:Objects.requireNonNull(FixedFrameworkMembers.get(v.wifiService,Objects.requireNonNull(physical)),"WIFI_P2P_PHYSICAL_SERVICE_REQUIRED");return (android.net.wifi.p2p.WifiP2pManager)FixedFrameworkMembers.create(v.wifi,service);}
    public static android.app.usage.NetworkStatsManager stats(){return (android.app.usage.NetworkStatsManager)FixedFrameworkMembers.create(require().stats,null,null);}
    public static android.app.NotificationManager notification(Context host){return (android.app.NotificationManager)FixedFrameworkMembers.create(require().notification,Objects.requireNonNull(host),java.time.InstantSource.system());}
    public static android.content.pm.ShortcutManager shortcut(Context context){return (android.content.pm.ShortcutManager)FixedFrameworkMembers.create(require().shortcut,Objects.requireNonNull(context),null);}
    public static boolean wifiFeature(android.net.wifi.p2p.WifiP2pManager physical,int index){if(index<0||index>=6)throw new IllegalArgumentException("WIFI_FEATURE_INDEX");return (Boolean)FixedFrameworkMembers.call(require().wifiFeatures[index],Objects.requireNonNull(physical));}
    public static Object notificationProperty(android.app.NotificationManager physical,int index){if(index<0||index>=5)throw new IllegalArgumentException("NOTIFICATION_PROPERTY_INDEX");return FixedFrameworkMembers.call(require().notificationProperties[index],Objects.requireNonNull(physical));}
    public static void channelIdentity(android.app.NotificationChannel value,String id,String group){ManagerFrameworkAccess v=require();Objects.requireNonNull(value);FixedFrameworkMembers.call(v.channelId,value,Objects.requireNonNull(id));FixedFrameworkMembers.call(v.channelGroup,value,group);}
    public static void groupIdentity(android.app.NotificationChannelGroup value,String id,java.util.List<android.app.NotificationChannel> channels){ManagerFrameworkAccess v=require();FixedFrameworkMembers.set(v.groupId,Objects.requireNonNull(value),Objects.requireNonNull(id));FixedFrameworkMembers.set(v.groupChannels,value,Objects.requireNonNull(channels));}
    public static int callbackFlags(ConnectivityManager.NetworkCallback callback){return (Integer)FixedFrameworkMembers.get(require().callbackFlags,Objects.requireNonNull(callback));}
    public static ApplicationStartInfo history(long createdMs,int state,int pid,int uid,int reason,int type,int component,int mode,
        String packageName,String processName,long startedNs,Long onCreateNs){
        ManagerFrameworkAccess v=require();ApplicationStartInfo value=(ApplicationStartInfo)FixedFrameworkMembers.create(v.history,createdMs);
        int[] data={state,pid,uid,uid,uid,reason,type,component,mode};for(int i=0;i<data.length;i++)FixedFrameworkMembers.call(v.integers[i],value,data[i]);
        FixedFrameworkMembers.call(v.packageName,value,Objects.requireNonNull(packageName));FixedFrameworkMembers.call(v.processName,value,Objects.requireNonNull(processName));
        FixedFrameworkMembers.call(v.forceStopped,value,false);FixedFrameworkMembers.call(v.timestamp,value,v.launchKey,startedNs);
        if(onCreateNs!=null)FixedFrameworkMembers.call(v.timestamp,value,v.onCreateKey,onCreateNs.longValue());return value;
    }
}
