package dev.tripoder.runtime;

/** Typed fixed-member operations. Implement through the existing single Framework registry. */
interface RuntimeSelfHookManagerFramework {
    android.app.ActivityManager activityManager(android.content.Context host,android.os.Handler handler);
    android.net.ConnectivityManager connectivityManager(android.content.Context host,android.net.ConnectivityManager physical);
    int networkCallbackFlags(android.net.ConnectivityManager.NetworkCallback callback);
    android.telephony.TelephonyManager telephonyManager(android.content.Context host);
    android.media.AudioManager audioManager(android.content.Context host);
    android.location.LocationManager locationManager(android.content.Context host,android.location.LocationManager physical);
    android.os.BatteryManager batteryManager();
    android.os.PowerManager powerManager(android.content.Context host,android.os.Handler handler);
    android.net.wifi.p2p.WifiP2pManager wifiP2pManager(android.content.Context host,android.net.wifi.p2p.WifiP2pManager physical);
    android.app.usage.NetworkStatsManager networkStatsManager();
    android.app.NotificationManager notificationManager(android.content.Context host);
    android.content.pm.ShortcutManager shortcutManager(android.content.Context context);
    boolean wifiFeature(android.net.wifi.p2p.WifiP2pManager physical,int index);
    Object notificationProperty(android.app.NotificationManager physical,int index);
    android.app.ApplicationStartInfo applicationStartInfo(RuntimeSelfHookHistory.Row row);
}
