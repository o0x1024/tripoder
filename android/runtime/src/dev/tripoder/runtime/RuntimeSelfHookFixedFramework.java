package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.app.ApplicationStartInfo;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import dev.tripoder.selfhook.ComponentFrameworkAccess;
import dev.tripoder.selfhook.ManagerFrameworkAccess;

/** One fixed registry backend for product components, manager facades and values. */
final class RuntimeSelfHookFixedFramework implements RuntimeSelfHookFrameworkCalls,RuntimeSelfHookManagerFramework {
    static final RuntimeSelfHookFixedFramework INSTANCE=new RuntimeSelfHookFixedFramework();
    private RuntimeSelfHookFixedFramework(){}
    public Object receiverPendingResult(BroadcastReceiver receiver){return ComponentFrameworkAccess.pendingResult(receiver);}
    public boolean protectedBroadcast(String action){return ComponentFrameworkAccess.protectedBroadcast(action);}
    public long bindServiceFlags(Context.BindServiceFlags flags){return ComponentFrameworkAccess.bindFlags(flags);}
    public Object providerHandle(ContentProvider provider){return ComponentFrameworkAccess.providerHandle(provider);}
    public RuntimeSelfHookSelection selectionArguments(ContentProviderOperation operation){
        ComponentFrameworkAccess.Selection snapshot=ComponentFrameworkAccess.selection(operation);Object[] values=snapshot.values();
        for(int i=0;i<values.length;i++)if(values[i] instanceof ComponentFrameworkAccess.BackReference){
            ComponentFrameworkAccess.BackReference ref=(ComponentFrameworkAccess.BackReference)values[i];values[i]=new RuntimeSelfHookSelection.BackReference(ref.fromIndex,ref.fromKey);
        }
        return new RuntimeSelfHookSelection(snapshot.keys(),values);
    }
    public ActivityManager activityManager(Context host,Handler handler){return ManagerFrameworkAccess.activity(host,handler);}
    public ConnectivityManager connectivityManager(Context host,ConnectivityManager physical){return ManagerFrameworkAccess.connectivity(host,physical);}
    public int networkCallbackFlags(ConnectivityManager.NetworkCallback callback){return ManagerFrameworkAccess.callbackFlags(callback);}
    public TelephonyManager telephonyManager(Context host){return ManagerFrameworkAccess.telephony(host);}
    public AudioManager audioManager(Context host){return ManagerFrameworkAccess.audio(host);}
    public android.location.LocationManager locationManager(Context host,android.location.LocationManager physical){return ManagerFrameworkAccess.location(host,physical);}
    public BatteryManager batteryManager(){return ManagerFrameworkAccess.battery();}
    public PowerManager powerManager(Context host,Handler handler){return ManagerFrameworkAccess.power(host,handler);}
    public android.net.wifi.p2p.WifiP2pManager wifiP2pManager(Context host,android.net.wifi.p2p.WifiP2pManager physical){return ManagerFrameworkAccess.wifi(host,physical);}
    public android.app.usage.NetworkStatsManager networkStatsManager(){return ManagerFrameworkAccess.stats();}
    public android.app.NotificationManager notificationManager(Context host){return ManagerFrameworkAccess.notification(host);}
    public android.content.pm.ShortcutManager shortcutManager(Context context){return ManagerFrameworkAccess.shortcut(context);}
    public boolean wifiFeature(android.net.wifi.p2p.WifiP2pManager physical,int index){return ManagerFrameworkAccess.wifiFeature(physical,index);}
    public Object notificationProperty(android.app.NotificationManager physical,int index){return ManagerFrameworkAccess.notificationProperty(physical,index);}
    public ApplicationStartInfo applicationStartInfo(RuntimeSelfHookHistory.Row row){
        return ManagerFrameworkAccess.history(row.createdMs,row.startupState,row.pid,row.uid,row.reason,row.startType,row.component,row.launchMode,
            row.packageName,row.processName,row.startedNs,row.onCreateNs);
    }
}
