package dev.tripoder.fixture;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Test-only receiver: records the last delivered broadcast so component delivery can be proven. */
public final class FixtureReceiver extends BroadcastReceiver {
    public static volatile String lastBroadcast = "";
    public static volatile int deliveries;
    public static String last() { return lastBroadcast; }
    @Override public void onReceive(Context context, Intent intent) {
        android.os.Bundle extras = intent.getExtras();
        lastBroadcast = intent.getAction() + "|" + intent.getStringExtra("who") + "|" + intent.getIntExtra("n", -1) + "|" + (extras == null ? "" : extras.getBundle("nested") == null ? "" : extras.getBundle("nested").getString("k"));
        deliveries++;
    }
}
