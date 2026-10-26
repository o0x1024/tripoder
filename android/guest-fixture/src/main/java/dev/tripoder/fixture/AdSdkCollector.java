package dev.tripoder.fixture;

/** Simulates a statically-linked third-party SDK class the guest app calls into; a realistic Hook target for privacy probes since it is guest-owned. */
public final class AdSdkCollector {
    public static String collectDeviceId(android.content.Context context) {
        return android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    }
}
