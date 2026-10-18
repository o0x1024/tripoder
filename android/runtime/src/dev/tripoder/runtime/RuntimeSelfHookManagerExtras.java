package dev.tripoder.runtime;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import java.util.List;
/** Remaining fixed manager routes. State and system operations stay in their
 * existing Runtime owners; only their typed callback dispatch lives here. */
final class RuntimeSelfHookManagerExtras {
    private static final List<String> WIFI=List.of("isChannelConstrainedDiscoverySupported()","isGroupClientRemovalSupported()",
        "isGroupOwnerIPv6LinkLocalAddressProvided()","isPccModeSupported()","isSetVendorElementsSupported()","isWiFiDirectR2Supported()");
    private static final List<String> NOTIFICATION=List.of("areNotificationsEnabled()","areNotificationsPaused()","getImportance()",
        "getCurrentInterruptionFilter()","shouldHideSilentStatusBarIcons()");
    private RuntimeSelfHookManagerExtras(){}
    static Object wifi(String key,Object[] args){
        if(key.equals("initialize(android.content.Context,android.os.Looper,android.net.wifi.p2p.WifiP2pManager$ChannelListener)"))
            return RuntimeBridge.wifiP2pInitialize((Context)args[0],(Looper)args[1],(WifiP2pManager.ChannelListener)args[2]);
        int index=WIFI.indexOf(key);if(index<0)throw new UnsupportedOperationException("WIFI_P2P_CAPABILITY_UNSUPPORTED:"+key);
        return RuntimeSelfHookManagers.extraFramework().wifiFeature(RuntimeBridge.wifiP2pPlatform(),index);
    }
    @SuppressWarnings("unchecked") static Object notification(String key,Object[] args){
        int index=NOTIFICATION.indexOf(key);if(index>=0)return RuntimeSelfHookManagers.extraFramework().notificationProperty(RuntimeBridge.notificationPlatform(),index);
        switch(key){
            case "notify(int,android.app.Notification)":RuntimeBridge.notificationPost(null,(Integer)args[0],(Notification)args[1]);return null;
            case "notify(java.lang.String,int,android.app.Notification)":RuntimeBridge.notificationPost((String)args[0],(Integer)args[1],(Notification)args[2]);return null;
            case "cancel(int)":RuntimeBridge.notificationCancel(null,(Integer)args[0]);return null;
            case "cancel(java.lang.String,int)":RuntimeBridge.notificationCancel((String)args[0],(Integer)args[1]);return null;
            case "cancelAll()":RuntimeBridge.notificationCancelAll();return null;
            case "createNotificationChannel(android.app.NotificationChannel)":RuntimeBridge.notificationCreateChannels(List.of((NotificationChannel)args[0]));return null;
            case "createNotificationChannels(java.util.List)":RuntimeBridge.notificationCreateChannels((List<NotificationChannel>)args[0]);return null;
            case "createNotificationChannelGroup(android.app.NotificationChannelGroup)":RuntimeBridge.notificationCreateGroups(List.of((NotificationChannelGroup)args[0]));return null;
            case "createNotificationChannelGroups(java.util.List)":RuntimeBridge.notificationCreateGroups((List<NotificationChannelGroup>)args[0]);return null;
            case "getNotificationChannel(java.lang.String)":return RuntimeBridge.notificationChannel((String)args[0],null);
            case "getNotificationChannel(java.lang.String,java.lang.String)":return RuntimeBridge.notificationChannel((String)args[0],(String)args[1]);
            case "getNotificationChannels()":return RuntimeBridge.notificationChannels();
            case "getNotificationChannelGroup(java.lang.String)":return RuntimeBridge.notificationGroup((String)args[0]);
            case "getNotificationChannelGroups()":return RuntimeBridge.notificationGroups();
            case "deleteNotificationChannel(java.lang.String)":RuntimeBridge.notificationDeleteChannel((String)args[0]);return null;
            case "deleteNotificationChannelGroup(java.lang.String)":RuntimeBridge.notificationDeleteGroup((String)args[0]);return null;
            default:throw new UnsupportedOperationException("NOTIFICATION_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    static Object shortcut(String key,Object[] args){
        switch(key){
            case "isRequestPinShortcutSupported()":return RuntimeBridge.shortcutPinSupported();
            default:throw new UnsupportedOperationException("SHORTCUT_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
}
