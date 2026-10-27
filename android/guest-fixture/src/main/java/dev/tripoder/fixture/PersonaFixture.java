package dev.tripoder.fixture;
import android.content.Context;
import android.os.BatteryManager;
import android.telephony.TelephonyManager;

/** Reads telephony/battery values through the framework managers, as a normal app would. */
public final class PersonaFixture {
    public static String networkCountryIso() { return tm().getNetworkCountryIso(); }
    public static String simCountryIso() { return tm().getSimCountryIso(); }
    public static String networkOperator() { return tm().getNetworkOperator(); }
    public static String networkOperatorName() { return tm().getNetworkOperatorName(); }
    public static int batteryPercent() { return bm().getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); }
    public static boolean batteryCharging() { return bm().isCharging(); }
    public static String diag() { return FixtureApplication.instance.getBaseContext().getClass().getName() + "|" + tm().getClass().getName() + "|" + bm().getClass().getName(); }
    private static volatile String lastUpdate = "none";
    private static volatile int updates;
    private static final android.location.LocationListener LISTENER = new android.location.LocationListener() {
        @Override public void onLocationChanged(android.location.Location l) { updates++; lastUpdate = fmt(l); }
    };
    private static String fmt(android.location.Location l) { return l == null ? "null" : l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy() + "," + l.getProvider(); }
    private static android.location.LocationManager lm() { return (android.location.LocationManager) FixtureApplication.instance.getSystemService(Context.LOCATION_SERVICE); }
    public static String lmClass() { return lm().getClass().getName(); }
    public static String lastKnown() { try { return fmt(lm().getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)); } catch (Throwable t) { return "ERR:" + t; } }
    public static boolean gpsEnabled() { return lm().isProviderEnabled(android.location.LocationManager.GPS_PROVIDER); }
    public static String startUpdates() {
        try { lm().requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, LISTENER, android.os.Looper.getMainLooper()); return "started"; }
        catch (Throwable t) { return "ERR:" + t; }
    }
    public static String lastUpdate() { return updates + "|" + lastUpdate; }
    public static String stopUpdates() { lm().removeUpdates(LISTENER); return "stopped"; }
    public static String currentLocaleTag() { return java.util.Locale.getDefault().toLanguageTag(); }
    public static String currentTimeZoneId() { return java.util.TimeZone.getDefault().getID(); }
    @SuppressWarnings("deprecation")
    public static String networkSummary() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) FixtureApplication.instance.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.Network active = cm.getActiveNetwork();
        boolean metered = cm.isActiveNetworkMetered();
        android.net.NetworkInfo vpnInfo = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_VPN);
        android.net.NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        boolean hasVpnCap = caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        boolean hasWifi = caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        boolean hasCell = caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
        return "active=" + (active != null) + "|metered=" + metered + "|vpnInfo=" + (vpnInfo != null) + "|vpnCap=" + hasVpnCap + "|wifi=" + hasWifi + "|cell=" + hasCell;
    }
    private static TelephonyManager tm() { return (TelephonyManager) FixtureApplication.instance.getSystemService(Context.TELEPHONY_SERVICE); }
    private static BatteryManager bm() { return (BatteryManager) FixtureApplication.instance.getSystemService(Context.BATTERY_SERVICE); }
}
