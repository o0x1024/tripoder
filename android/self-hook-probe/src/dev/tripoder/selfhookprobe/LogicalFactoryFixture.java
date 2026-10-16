package dev.tripoder.selfhookprobe;
import android.app.AppComponentFactory;
public final class LogicalFactoryFixture extends AppComponentFactory {
    public static int constructors;
    public LogicalFactoryFixture(){constructors++;}
}
