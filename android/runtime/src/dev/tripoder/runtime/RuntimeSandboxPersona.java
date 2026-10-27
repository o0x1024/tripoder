package dev.tripoder.runtime;

import java.util.*;

/**
 * Per-process environment-persona overrides, read by the existing {@link RuntimeSelfHookManagers}
 * fixed-inventory facade callbacks (telephony/battery) instead of forwarding to the real platform
 * value. Extends an already-intercepted, already-non-identity-bearing subset of that facade; does
 * NOT touch the identity-bearing queries (IMEI/ANDROID_ID/etc.) that facade deliberately excludes.
 * Default: no overrides, behavior identical to today.
 */
final class RuntimeSandboxPersona {
    private RuntimeSandboxPersona(){}
    private static volatile Map<String,String> telephony = Map.of();
    /** Immutable mock fix; null means the real platform location is used. */
    record Fix(double latitude,double longitude,float accuracy,Double altitude,float speed,float bearing){}
    private static volatile Fix location;
    private static volatile Integer batteryLevel;
    private static volatile Boolean batteryCharging;
    private static volatile Locale bootLocale;
    private static volatile TimeZone bootTimeZone;
    private static volatile String localeLanguage = "";
    private static volatile String localeCountry = "";
    private static volatile String timeZoneId = "";
    private static volatile boolean hideVpn = false;

    private static synchronized void ensureBootDefaults() {
        if (bootLocale == null) bootLocale = Locale.getDefault();
        if (bootTimeZone == null) bootTimeZone = TimeZone.getDefault();
    }

    static synchronized Map<String,Object> configureLocale(String language, String country, String tzId) {
        ensureBootDefaults();
        String lang = language == null ? "" : language.trim();
        String ctry = country == null ? "" : country.trim();
        String tz = tzId == null ? "" : tzId.trim();
        if (!lang.isEmpty() && !lang.matches("[a-z]{2,3}")) throw new IllegalArgumentException("PERSONA_LOCALE_LANGUAGE");
        if (!ctry.isEmpty() && !ctry.matches("[A-Z]{2}")) throw new IllegalArgumentException("PERSONA_LOCALE_COUNTRY");
        if (!tz.isEmpty() && !tz.matches("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")) throw new IllegalArgumentException("PERSONA_TIMEZONE_ID");
        if (lang.isEmpty() && !ctry.isEmpty()) throw new IllegalArgumentException("PERSONA_LOCALE_LANGUAGE_REQUIRED_FOR_COUNTRY");

        if (lang.isEmpty()) {
            Locale.setDefault(bootLocale);
            localeLanguage = "";
            localeCountry = "";
        } else {
            Locale nextLocale = ctry.isEmpty() ? new Locale(lang) : new Locale(lang, ctry);
            Locale.setDefault(nextLocale);
            localeLanguage = lang;
            localeCountry = ctry;
        }
        if (tz.isEmpty()) {
            TimeZone.setDefault(bootTimeZone);
            timeZoneId = "";
        } else {
            TimeZone resolved = TimeZone.getTimeZone(tz);
            if ("GMT".equals(resolved.getID()) && !"GMT".equalsIgnoreCase(tz) && !"UTC".equalsIgnoreCase(tz)) {
                throw new IllegalArgumentException("PERSONA_TIMEZONE_UNKNOWN:" + tz);
            }
            TimeZone.setDefault(resolved);
            timeZoneId = resolved.getID();
        }
        return status();
    }

    static synchronized Map<String,Object> configureNetwork(boolean hide) {
        hideVpn = hide;
        return status();
    }

    static boolean hideVpn() { return hideVpn; }

    static Map<String,Object> configureTelephony(Map<String,Object> fields) {
        if (fields.size() > 6) throw new IllegalArgumentException("PERSONA_TELEPHONY_FIELDS");
        Set<String> allowed = Set.of("networkCountryIso", "simCountryIso", "networkOperator", "networkOperatorName", "simOperator", "simOperatorName");
        Map<String,String> next = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (!allowed.contains(key)) throw new IllegalArgumentException("PERSONA_TELEPHONY_FIELD:" + key);
            Object value = entry.getValue();
            if (!(value instanceof String text)) throw new IllegalArgumentException("PERSONA_TELEPHONY_VALUE:" + key);
            if (text.isEmpty()) continue; // empty string clears that one field back to passthrough
            if ((key.equals("networkCountryIso") || key.equals("simCountryIso")) && !text.matches("[a-z]{2}"))
                throw new IllegalArgumentException("PERSONA_TELEPHONY_COUNTRY_ISO:" + key);
            if ((key.equals("networkOperator") || key.equals("simOperator")) && !text.matches("[0-9]{5,6}"))
                throw new IllegalArgumentException("PERSONA_TELEPHONY_OPERATOR:" + key);
            if ((key.equals("networkOperatorName") || key.equals("simOperatorName")) && text.length() > 64)
                throw new IllegalArgumentException("PERSONA_TELEPHONY_OPERATOR_NAME:" + key);
            next.put(key, text);
        }
        telephony = Map.copyOf(next);
        return status();
    }

    static Map<String,Object> configureLocation(Map<String,Object> fields) {
        Set<String> allowed = Set.of("latitude", "longitude", "accuracy", "altitude", "speed", "bearing");
        for (String key : fields.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("PERSONA_LOCATION_FIELD:" + key);
        if (fields.isEmpty()) { location = null; RuntimeLocation.personaChanged(); return status(); }
        if (!fields.containsKey("latitude") || !fields.containsKey("longitude")) throw new IllegalArgumentException("PERSONA_LOCATION_COORDINATES_REQUIRED");
        double lat = number(fields, "latitude"), lon = number(fields, "longitude");
        if (!(lat >= -90 && lat <= 90)) throw new IllegalArgumentException("PERSONA_LOCATION_LATITUDE");
        if (!(lon >= -180 && lon <= 180)) throw new IllegalArgumentException("PERSONA_LOCATION_LONGITUDE");
        double accuracy = fields.containsKey("accuracy") ? number(fields, "accuracy") : 5.0;
        if (!(accuracy > 0 && accuracy <= 10000)) throw new IllegalArgumentException("PERSONA_LOCATION_ACCURACY");
        Double altitude = fields.containsKey("altitude") ? Double.valueOf(number(fields, "altitude")) : null;
        double speed = fields.containsKey("speed") ? number(fields, "speed") : 0.0;
        if (!(speed >= 0 && speed <= 400)) throw new IllegalArgumentException("PERSONA_LOCATION_SPEED");
        double bearing = fields.containsKey("bearing") ? number(fields, "bearing") : 0.0;
        if (!(bearing >= 0 && bearing < 360)) throw new IllegalArgumentException("PERSONA_LOCATION_BEARING");
        location = new Fix(lat, lon, (float) accuracy, altitude, (float) speed, (float) bearing);
        RuntimeLocation.personaChanged();
        return status();
    }

    private static double number(Map<String,Object> fields, String key) {
        if (!(fields.get(key) instanceof Number n) || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) throw new IllegalArgumentException("PERSONA_LOCATION_VALUE:" + key);
        return n.doubleValue();
    }

    static Fix locationFix() { return location; }

    static Map<String,Object> configureBattery(Integer level, Boolean charging) {
        if (level != null && (level < 0 || level > 100)) throw new IllegalArgumentException("PERSONA_BATTERY_LEVEL");
        batteryLevel = level;
        batteryCharging = charging;
        return status();
    }

    static Map<String,Object> status() {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("telephony", telephony);
        Map<String,Object> battery = new LinkedHashMap<>();
        battery.put("level", batteryLevel == null ? null : (double) (int) batteryLevel);
        battery.put("charging", batteryCharging);
        out.put("battery", battery);
        Fix fix = location;
        Map<String,Object> loc = new LinkedHashMap<>();
        if (fix != null) {
            loc.put("latitude", fix.latitude()); loc.put("longitude", fix.longitude()); loc.put("accuracy", (double) fix.accuracy());
            if (fix.altitude() != null) loc.put("altitude", fix.altitude());
            loc.put("speed", (double) fix.speed()); loc.put("bearing", (double) fix.bearing());
        }
        out.put("location", loc);
        ensureBootDefaults();
        Map<String,Object> locale = new LinkedHashMap<>();
        locale.put("language", localeLanguage);
        locale.put("country", localeCountry);
        locale.put("timeZoneId", timeZoneId);
        locale.put("effectiveLocale", Locale.getDefault().toLanguageTag());
        locale.put("effectiveTimeZone", TimeZone.getDefault().getID());
        out.put("locale", locale);
        Map<String,Object> net = new LinkedHashMap<>();
        net.put("hideVpn", hideVpn);
        out.put("network", net);
        return Collections.unmodifiableMap(out);
    }

    /** Null means "no override, forward to the real platform value" — never an empty string. */
    static String telephonyOverride(String key) { return telephony.get(key); }
    static Integer batteryLevelOverride() { return batteryLevel; }
    static Boolean batteryChargingOverride() { return batteryCharging; }
}
