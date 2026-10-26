package dev.tripoder.antidetect;
import android.app.Application;

public final class AntiDetectApplication extends Application {
    public static int created;
    @Override public void onCreate() { super.onCreate(); created++; }
}
