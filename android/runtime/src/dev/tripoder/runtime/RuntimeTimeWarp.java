package dev.tripoder.runtime;

import android.app.AlarmManager;
import android.os.SystemClock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-scoped virtual clock offset and delay scale factor for {@code time_warp_control}
 * and {@code time_warp_query}.
 *
 * <p>Default is passthrough ({@code offsetMillis = 0L}, {@code delayScale = 1.0d}).
 */
public final class RuntimeTimeWarp {
    private static volatile long offsetMillis = 0L;
    private static volatile double delayScale = 1.0d;

    private RuntimeTimeWarp() {}

    public static synchronized Map<String, Object> configure(long offset, double scale) {
        if (offset < -31_536_000_000L || offset > 31_536_000_000L) {
            throw new IllegalArgumentException("TIME_WARP_OFFSET_RANGE");
        }
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.01d || scale > 10.0d) {
            throw new IllegalArgumentException("TIME_WARP_SCALE_RANGE");
        }
        offsetMillis = offset;
        delayScale = scale;
        return status();
    }

    public static synchronized Map<String, Object> reset() {
        offsetMillis = 0L;
        delayScale = 1.0d;
        return status();
    }

    public static long offsetMillis() {
        return offsetMillis;
    }

    public static double delayScale() {
        return delayScale;
    }

    public static boolean isActive() {
        return offsetMillis != 0L || delayScale != 1.0d;
    }

    public static long currentTimeMillis() {
        return System.currentTimeMillis() + offsetMillis;
    }

    public static long elapsedRealtime() {
        return SystemClock.elapsedRealtime() + offsetMillis;
    }

    public static long scaleDelayMillis(long delayMs) {
        if (delayMs <= 0L || delayScale == 1.0d) return delayMs;
        long scaled = Math.round(delayMs * delayScale);
        return Math.max(1L, scaled);
    }

    public static long adjustAlarmTrigger(int type, long triggerAtMillis) {
        if (!isActive()) return triggerAtMillis;
        boolean rtc = (type == AlarmManager.RTC || type == AlarmManager.RTC_WAKEUP);
        long now = rtc ? System.currentTimeMillis() : SystemClock.elapsedRealtime();
        long delta = triggerAtMillis - now;
        if (delta > 0L && delayScale != 1.0d) {
            return now + scaleDelayMillis(delta);
        }
        return triggerAtMillis;
    }

    public static synchronized Map<String, Object> status() {
        long realWall = System.currentTimeMillis();
        long virtualWall = realWall + offsetMillis;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", isActive());
        out.put("offsetMillis", offsetMillis);
        out.put("delayScale", delayScale);
        out.put("realTimeMillis", realWall);
        out.put("virtualTimeMillis", virtualWall);
        return Collections.unmodifiableMap(out);
    }
}
